/* Copyright (C) 2026 Nuvix Contributors. Licensed under GPL-3.0-or-later. */
package app.opentv.ui.player

import android.app.Application
import android.content.Context
import android.view.View
import android.view.ViewGroup
import androidx.annotation.OptIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.ViewModelStore
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.PlayerView
import androidx.room.Room
import app.opentv.connections.AccountConnectionManager
import app.opentv.core.AppSettings
import app.opentv.core.RecentChannels
import app.opentv.core.ServiceLocator
import app.opentv.data.db.OpenTvDatabase
import app.opentv.data.model.Channel
import app.opentv.data.model.Source
import app.opentv.data.model.SourceKind
import app.opentv.player.LivePlaybackSession
import app.opentv.player.LivePresentation
import app.opentv.player.PlaybackQueue
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Real fullscreen Compose/Media3 binding and Xtream admission; suspension prevents network I/O. */
@OptIn(UnstableApi::class)
@kotlin.OptIn(ExperimentalCoroutinesApi::class, ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34], qualifiers = "w1280dp-h720dp-land")
class FullscreenChannelSwitchTest {
    @get:Rule val compose = createComposeRule()

    private fun singleton(type: Class<*>, value: Any?): Any? = type.getDeclaredField("instance").run {
        isAccessible = true
        get(null).also { set(null, value) }
    }
    private fun views(root: View): List<PlayerView> = when (root) {
        is PlayerView -> listOf(root)
        is ViewGroup -> (0 until root.childCount).flatMap { views(root.getChildAt(it)) }
        else -> emptyList()
    }

    @Test fun `remote Up Down and rapid navigation rebind the retained fullscreen view to newest Xtream player`() {
        val app = RuntimeEnvironment.getApplication()
        listOf("opentv_settings", "nuvix_connection_settings", "nuvix_recent_channels")
            .forEach { app.getSharedPreferences(it, Context.MODE_PRIVATE).edit().clear().commit() }
        val dispatcher = StandardTestDispatcher()
        Dispatchers.setMain(dispatcher)
        val db = Room.inMemoryDatabaseBuilder(app, OpenTvDatabase::class.java).allowMainThreadQueries()
            .setQueryExecutor { it.run() }.setTransactionExecutor { it.run() }.build()
        val graph = ServiceLocator.Graph(app)
        val settings = AppSettings::class.java.getDeclaredConstructor(Context::class.java)
            .apply { isAccessible = true }.newInstance(app)
        fun dependency(name: String, value: Any) {
            ServiceLocator.Graph::class.java.getDeclaredField("${name}\$delegate")
                .apply { isAccessible = true }.set(graph, lazyOf(value))
        }
        dependency("database", db)
        dependency("settings", settings)
        val connections = AccountConnectionManager.createForTest(app)
        val savedGraph = singleton(ServiceLocator::class.java, graph)
        val savedConnections = singleton(AccountConnectionManager::class.java, connections)
        val savedRecent = singleton(RecentChannels::class.java, null)
        val savedQueue = PlaybackQueue.items
        val store = ViewModelStore()
        var visible by mutableStateOf(true)
        var preview by mutableStateOf(false)
        lateinit var root: View
        try {
            val source = Source(id = 1, name = "Synthetic Xtream", kind = SourceKind.XTREAM,
                url = "https://fixture.invalid", username = "synthetic", password = "synthetic")
            val channels = (1L..5L).map { id -> Channel(id = id, sourceId = 1, streamId = "$id",
                name = "Channel $id", categoryId = null, logoUrl = null, epgChannelId = null,
                number = id.toInt(), streamUrl = "https://fixture.invalid/$id.ts") }
            runBlocking { db.sources().insert(source); db.channels().upsertAll(channels) }
            connections.registerSource(source)
            connections.setMaximumConnections(1, 1)
            PlaybackQueue.items = channels.map { PlaybackQueue.Item(it.id, it.name, null) }
            val session = LivePlaybackSession(app).also { store.put("live", it); it.setForeground(false) }
            session.openChannel(3)
            dispatcher.scheduler.runCurrent()
            compose.setContent {
                root = LocalView.current.rootView
                MaterialTheme {
                    if (visible) {
                        if (preview) LivePlaybackVideo(session, onFullscreen = {})
                        else PlayerScreen(session, onBack = {})
                    }
                }
            }
            compose.runOnIdle {
                assertThat(views(root).single().player).isSameInstanceAs(session.controllerOrNull.value!!.player)
            }
            val first = session.controllerOrNull.value!!
            compose.onNodeWithText("Pause").performSemanticsAction(SemanticsActions.RequestFocus)
            compose.onRoot().performKeyInput { pressKey(Key.DirectionUp) }
            compose.runOnIdle { dispatcher.scheduler.runCurrent() }
            compose.runOnIdle {
                assertThat(session.currentChannel.value?.id).isEqualTo(2)
                assertThat(first.isReleased).isTrue()
                // Fails with the old AndroidView.update: view.player remains null after release.
                assertThat(views(root).single().player).isSameInstanceAs(session.controllerOrNull.value!!.player)
            }
            compose.onRoot().performKeyInput { pressKey(Key.DirectionDown) }
            compose.runOnIdle { dispatcher.scheduler.runCurrent() }
            compose.runOnIdle { assertThat(session.currentChannel.value?.id).isEqualTo(3) }
            compose.onRoot().performKeyInput {
                pressKey(Key.DirectionUp); pressKey(Key.DirectionUp)
                pressKey(Key.DirectionDown); pressKey(Key.DirectionUp)
            }
            compose.runOnIdle { dispatcher.scheduler.runCurrent() }
            compose.runOnIdle {
                assertThat(session.currentChannel.value?.id).isEqualTo(1)
                assertThat(views(root).single().player).isSameInstanceAs(session.controllerOrNull.value!!.player)
                assertThat(connections.snapshotForSource(1)!!.locallyReserved).isEqualTo(1)
            }
            compose.runOnIdle { session.setPresentation(LivePresentation.BROWSING); preview = true }
            compose.runOnIdle {
                assertThat(views(root).single().player).isSameInstanceAs(session.controllerOrNull.value!!.player)
                session.openChannel(2)
                dispatcher.scheduler.runCurrent()
            }
            compose.runOnIdle {
                assertThat(session.currentChannel.value?.id).isEqualTo(2)
                assertThat(views(root).single().player).isSameInstanceAs(session.controllerOrNull.value!!.player)
            }
        } finally {
            compose.runOnIdle { visible = false }
            compose.waitForIdle()
            store.clear()
            dispatcher.scheduler.runCurrent()
            db.close()
            graph.applicationScope.cancel()
            PlaybackQueue.items = savedQueue
            singleton(ServiceLocator::class.java, savedGraph)
            singleton(AccountConnectionManager::class.java, savedConnections)
            singleton(RecentChannels::class.java, savedRecent)
            Dispatchers.resetMain()
        }
    }
}
