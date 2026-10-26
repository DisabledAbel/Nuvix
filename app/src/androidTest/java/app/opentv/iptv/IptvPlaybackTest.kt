/* Copyright (C) 2026 Nuvix Contributors. GPL-3.0-or-later. */
package app.opentv.iptv

import android.app.Application
import android.content.Context
import android.graphics.Color
import android.os.SystemClock
import androidx.annotation.OptIn
import androidx.lifecycle.ViewModelStore
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.room.Room
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.opentv.connections.AccountConnectionManager
import app.opentv.core.AppSettings
import app.opentv.core.RecentChannels
import app.opentv.core.ServiceLocator
import app.opentv.data.db.OpenTvDatabase
import app.opentv.data.model.Channel
import app.opentv.data.model.Source
import app.opentv.data.model.SourceKind
import app.opentv.data.parser.M3uParser
import app.opentv.data.parser.XmltvParser
import app.opentv.data.remote.XtreamApi
import app.opentv.data.remote.StalkerApi
import app.opentv.player.ChannelTransitionPhase
import app.opentv.player.LivePlaybackSession
import app.opentv.player.LivePresentation
import app.opentv.player.PlayerController
import app.opentv.recording.RecordingCapture
import java.io.ByteArrayOutputStream
import java.io.File
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Real production session → controller → Media3 decoder → retained PlayerView → visible pixels.
 * Only a loopback provider reached through `adb reverse tcp:8765 tcp:8765` is used.
 */
@OptIn(UnstableApi::class)
@RunWith(AndroidJUnit4::class)
class IptvPlaybackTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app get() = instrumentation.targetContext.applicationContext as Application
    private val base = "http://127.0.0.1:8765"
    private val http = OkHttpClient.Builder().callTimeout(5, java.util.concurrent.TimeUnit.SECONDS).build()
    private lateinit var db: OpenTvDatabase
    private lateinit var graph: ServiceLocator.Graph
    private lateinit var connections: AccountConnectionManager
    private lateinit var session: LivePlaybackSession
    private lateinit var host: ActivityScenario<IptvPlaybackActivity>
    private lateinit var activity: IptvPlaybackActivity
    private val store = ViewModelStore()
    private val restored = mutableListOf<Pair<Class<*>, Any?>>()
    private var reportId = "recovery"
    private var completed = false
    private var surfaceAcquired = false
    private val metrics = JSONObject()

    private fun <T> main(block: () -> T): T {
        var outcome: Result<T>? = null
        instrumentation.runOnMainSync { outcome = runCatching(block) }
        return requireNotNull(outcome).getOrThrow()
    }

    private fun singleton(type: Class<*>, value: Any?) {
        val field = type.getDeclaredField("instance").apply { isAccessible = true }
        restored += type to field.get(null)
        field.set(null, value)
    }

    @Before fun setup() {
        listOf("opentv_settings", "nuvix_private_settings", "nuvix_connection_settings", "nuvix_recent_channels")
            .forEach { app.getSharedPreferences(it, Context.MODE_PRIVATE).edit().clear().commit() }
        db = Room.inMemoryDatabaseBuilder(app, OpenTvDatabase::class.java).allowMainThreadQueries().build()
        graph = ServiceLocator.Graph(app)
        fun dependency(name: String, value: Any) {
            ServiceLocator.Graph::class.java.getDeclaredField("${name}\$delegate")
                .apply { isAccessible = true }.set(graph, lazyOf(value))
        }
        val settings = AppSettings::class.java.getDeclaredConstructor(Context::class.java)
            .apply { isAccessible = true }.newInstance(app)
        settings.setDiagnosticsEnabled(true)
        dependency("database", db)
        dependency("settings", settings)
        dependency("httpClient", http)
        dependency("streamingHttpClient", http)
        connections = AccountConnectionManager.createForTest(app)
        singleton(ServiceLocator::class.java, graph)
        singleton(AccountConnectionManager::class.java, connections)
        singleton(RecentChannels::class.java, null)
        host = ActivityScenario.launch(IptvPlaybackActivity::class.java)
        host.onActivity { activity = it }
        main {
            session = LivePlaybackSession(app)
            store.put("iptv", session)
            session.setPresentation(LivePresentation.FULLSCREEN)
            session.setForeground(true)
        }
    }

    @After fun cleanup() {
        try {
            main { store.clear(); if (::graph.isInitialized) graph.applicationScope.cancel() }
            if (::host.isInitialized) host.close()
            if (::db.isInitialized) db.close()
        } finally {
            restored.asReversed().forEach { (type, old) ->
                type.getDeclaredField("instance").apply { isAccessible = true }.set(null, old)
            }
            val results = JSONArray().put(JSONObject().put("scenario", reportId).put("passed", completed).put("metrics", metrics))
            val report = JSONObject().put("schema_version", 1).put("suite", "media3-local-v1").put("results", results)
            File(app.filesDir, "iptv-reports").apply { mkdirs() }.resolve("$reportId.json").writeText(report.toString())
        }
    }

    private fun seed(maximum: Int?, extension: String, kind: SourceKind = SourceKind.XTREAM) {
        val source = Source(id = 1, name = "Synthetic provider", kind = kind,
            url = if (kind == SourceKind.STALKER) "$base/c" else base,
            username = if (kind == SourceKind.XTREAM) "fixture" else null,
            password = if (kind == SourceKind.XTREAM) "fixture" else null,
            macAddress = if (kind == SourceKind.STALKER) "02:00:00:00:00:01" else null)
        runBlocking {
            db.sources().insert(source)
            db.channels().upsertAll((1L..2L).map { id ->
                val path = if (extension == "m3u8") "${if (id == 1L) "a" else "b"}/index.m3u8"
                           else "${if (id == 1L) "a" else "b"}.ts"
                Channel(id = id, sourceId = 1, streamId = "$id", name = "Synthetic $id",
                    categoryId = null, logoUrl = null, epgChannelId = null, number = id.toInt(),
                    streamUrl = if (kind == SourceKind.STALKER) "$base/unresolved" else "$base/$path",
                    cmd = if (kind == SourceKind.STALKER) (if (id == 1L) "a" else "b") else null)
            })
        }
        connections.registerSource(source)
        connections.setMaximumConnections(1, maximum)
    }

    private fun await(description: String, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 15_000
        while (SystemClock.elapsedRealtime() < deadline) {
            if (main(condition)) return
            SystemClock.sleep(25)
        }
        main {
            session.controllerOrNull.value?.let { controller ->
                controller.diagnostics.sample()
                metrics.put("media3_state", controller.player.playbackState)
                metrics.put("rendered_frames", controller.diagnostics.snapshot.value.renderedFrames ?: 0)
                metrics.put("error_code", controller.player.playerError?.errorCode ?: 0)
                metrics.put("surface_bound", if (activity.video.player === controller.player) 1 else 0)
            }
        }
        fail("Local Media3 milestone timed out: $description") // no provider data in failures
    }

    private fun rendered(id: Long, blue: Boolean) {
        await("controller identity and first rendered frame") {
            val controller = session.controllerOrNull.value ?: return@await false
            if (!surfaceAcquired) {
                surfaceAcquired = session.attachVideoSurface(activity.video, LivePresentation.FULLSCREEN)
            } else {
                session.attachVideoSurface(activity.video, LivePresentation.FULLSCREEN, rebind = true)
            }
            // The production information panel samples explicitly while visible; no panel runs here.
            controller.diagnostics.sample()
            session.currentChannel.value?.id == id && session.playingChannel.value?.id == id &&
                controller.player.playbackState == Player.STATE_READY && activity.video.player === controller.player &&
                controller.diagnostics.snapshot.value.transition.any { it.phase == ChannelTransitionPhase.FIRST_FRAME }
        }
        // A decoder callback precedes compositor presentation. Poll actual pixels with a bound;
        // one screenshot during an emulator buffer swap can still contain the previous surface.
        val deadline = SystemClock.elapsedRealtime() + 2_000
        do {
            val bitmap = instrumentation.uiAutomation.takeScreenshot()
            if (bitmap != null) {
                val pixel = bitmap.getPixel(bitmap.width / 2, bitmap.height / 2)
                bitmap.recycle()
                val matched = if (blue) Color.blue(pixel) > Color.red(pixel) + 80
                              else Color.red(pixel) > Color.blue(pixel) + 80
                if (matched) return
            }
            SystemClock.sleep(50)
        } while (SystemClock.elapsedRealtime() < deadline)
        fail(if (blue) "Replacement must render synthetic blue video" else "Initial channel must render synthetic red video")
    }

    private fun switch(maximum: Int?, extension: String, scenario: String, kind: SourceKind = SourceKind.XTREAM) {
        reportId = scenario
        seed(maximum, extension, kind)
        main { session.openChannel(1) }
        rendered(1, false)
        val previous = main { requireNotNull(session.controllerOrNull.value) }
        val previousToken = requireNotNull(connections.snapshotForSource(1)).reservations.single().token
        val started = SystemClock.elapsedRealtime()
        main { session.openChannel(2) }
        metrics.put("request_ms", SystemClock.elapsedRealtime() - started)
        // The retained view must be rebound by the same production API used by Compose.
        rendered(2, true)
        main {
            assertTrue("Previous controller must be released", previous.isReleased)
            assertTrue("Media3 must release the previous player", previous.player.isReleased)
            assertNotSame(previous, session.controllerOrNull.value)
            assertEquals(2L, session.currentChannel.value?.id)
            assertFalse("A stale reservation cannot free the new player", connections.release(previousToken))
            val phases = session.controller.diagnostics.snapshot.value.transition.associate { it.phase to it.elapsedMs }
            for (phase in listOf(ChannelTransitionPhase.CLOSE_STARTED, ChannelTransitionPhase.CLOSE_COMPLETED,
                ChannelTransitionPhase.RESERVATION_ACQUIRED, ChannelTransitionPhase.PREPARE_STARTED,
                ChannelTransitionPhase.READY, ChannelTransitionPhase.FIRST_FRAME)) assertTrue("Missing phase $phase", phase in phases)
            phases.forEach { (phase, ms) -> metrics.put(phase.name, ms) }
            metrics.put("release_ms", phases.getValue(ChannelTransitionPhase.CLOSE_COMPLETED) - phases.getValue(ChannelTransitionPhase.CLOSE_STARTED))
            metrics.put("prepare_ms", phases.getValue(ChannelTransitionPhase.READY) - phases.getValue(ChannelTransitionPhase.PREPARE_STARTED))
            assertTrue("Synthetic switch must finish before the historical minute stall", phases.getValue(ChannelTransitionPhase.FIRST_FRAME) < 15_000)
            assertEquals(1, requireNotNull(connections.snapshotForSource(1)).locallyReserved)
            metrics.put("reservations", 1)
            val replacement = session.controller
            session.stop()
            assertTrue("Stopping must release the replacement Media3 player", replacement.player.isReleased)
            assertEquals(0, requireNotNull(connections.snapshotForSource(1)).locallyReserved)
            assertNull(activity.video.player)
        }
        completed = true
    }

    @Test fun hlsOneConnectionSwitch() = switch(1, "m3u8", "hls-one")
    @Test fun hlsSpareConnectionSwitch() = switch(3, "m3u8", "hls-spare")
    @Test fun transportStreamUnknownAllowanceSwitch() = switch(null, "ts", "ts-unknown")
    @Test fun stalkerOneConnectionSwitch() = switch(1, "ts", "stalker-one", SourceKind.STALKER)

    @Test fun rapidUpDownLatestRequestRenders() {
        reportId = "rapid"
        seed(1, "ts")
        main { session.openChannel(1) }
        rendered(1, false)
        val previous = main { session.controller }
        main { listOf(2L, 1L, 2L, 1L, 2L).forEach(session::openChannel) }
        rendered(2, true)
        main { assertTrue(previous.isReleased); assertEquals(1, connections.snapshotForSource(1)!!.locallyReserved) }
        completed = true
    }

    @Test fun failedMediaThenExplicitRecoveryRendersNewChannel() {
        reportId = "recovery"
        seed(1, "ts")
        runBlocking { db.channels().upsertAll(listOf(db.channels().byId(2)!!.copy(streamUrl = "$base/fault/auth"))) }
        main { session.openChannel(1) }
        rendered(1, false)
        main { session.openChannel(2) }
        await("failed playback") { session.state.value is PlayerController.State.Error }
        metrics.put("failures", 1)
        runBlocking { db.channels().upsertAll(listOf(db.channels().byId(2)!!.copy(streamUrl = "$base/b.ts"))) }
        main { session.openChannel(1) }
        rendered(1, false)
        main { session.openChannel(2) }
        rendered(2, true)
        completed = true
    }

    private fun body(path: String): ByteArray = http.newCall(Request.Builder().url(base + path).build()).execute().use {
        check(it.isSuccessful)
        requireNotNull(it.body).bytes()
    }

    @Test fun syntheticProvidersPassProductionImportersAndAuthentication() = runBlocking {
        reportId = "providers"
        var start = SystemClock.elapsedRealtime()
        val playlist = M3uParser.parse(body("/playlist.m3u").inputStream(), 1)
        assertEquals(2, playlist.channels.size)
        assertEquals(0, playlist.skippedEntries)
        metrics.put("import_ms", SystemClock.elapsedRealtime() - start)
        val epg = XmltvParser.parse(body("/epg.xml").inputStream(), 1, onProgramme = {})
        assertEquals(1, epg.programmeCount)
        val source = Source(id = 1, name = "Synthetic", kind = SourceKind.XTREAM,
            url = base, username = "fixture", password = "fixture")
        start = SystemClock.elapsedRealtime()
        val xtream = XtreamApi(http)
        assertEquals(1, xtream.authenticate(source).maxConnections)
        assertEquals(2, xtream.liveStreams(source).size)
        try { xtream.authenticate(source.copy(password = "invalid")); fail("Invalid synthetic auth accepted") }
        catch (_: XtreamApi.XtreamException) { }
        val stalker = StalkerApi(http)
        val portal = source.copy(kind = SourceKind.STALKER, url = "$base/c", macAddress = "02:00:00:00:00:01")
        assertEquals(1, stalker.liveCategories(portal).size)
        assertEquals("$base/b.ts", stalker.createLink(portal, "synthetic"))
        metrics.put("provider_ms", SystemClock.elapsedRealtime() - start)
        completed = true
    }

    @Test fun dvrCapturesSyntheticTransportAndStopsAtDeadline() = runBlocking {
        reportId = "dvr"
        val output = ByteArrayOutputStream()
        var starts = 0
        var clock = 0L
        val result = RecordingCapture(http, nowMillis = { clock }, retryDelayMillis = 0).copy("$base/limited.ts", "Synthetic", output,
            500, {}, {}, {}, onStarted = { starts++; clock = 600 })
        assertEquals(1, starts)
        assertTrue(result.bytes >= 188)
        assertEquals(0x47.toByte(), output.toByteArray()[0])
        assertNull(result.error)
        completed = true
    }
}
