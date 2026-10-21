/* Copyright (C) 2026 Nuvix Contributors. GPL-3.0-or-later. */
package app.opentv.recording

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.opentv.connections.*
import app.opentv.core.AppSettings
import app.opentv.data.db.OpenTvDatabase
import app.opentv.data.model.*
import app.opentv.data.remote.*
import app.opentv.data.repo.*
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28])
class RecordingLateStartEngineTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val db = Room.inMemoryDatabaseBuilder(context, OpenTvDatabase::class.java).allowMainThreadQueries().build()
    private val manager = AccountConnectionManager.createForTest(context)
    private val repo = RecordingRepository(db.recordings(), db.seriesRules(), db.recordingEvents())
    private val alarms = mutableListOf<Pair<Long, Long>>()
    private var clock = 1_006_000L
    private lateinit var engine: RecordingEngine
    private val source = Source(id = 1, name = "Provider", kind = SourceKind.XTREAM,
        url = "https://private.invalid", username = "private-user", password = "private-password")
    private fun row() = Recording(id = 1, channelId = 1, sourceId = 1, channelName = "Game", title = "Game",
        filePath = "/missing-never-created.ts", streamUrl = "https://private.invalid/secret", status = RecordingStatus.SCHEDULED,
        scheduledStartMillis = 880_000, programmeStartMillis = 1_000_000, programmeEndMillis = 4_600_000,
        scheduledEndMillis = 4_900_000, protectedRecording = true, lockPadding = true)
    @Before fun setup() = runBlocking<Unit> {
        db.sources().insert(source); manager.registerSource(source); manager.setMaximumConnections(1, 1)
        val http = OkHttpClient.Builder().addInterceptor { error("No provider requests allowed") }.build()
        val api = XtreamApi(http)
        val settings = AppSettings::class.java.getDeclaredConstructor(Context::class.java).apply { isAccessible = true }.newInstance(context)
        settings.setRecordLateStart(LateStartOptions(LateStartPolicy.RECORD_REMAINING))
        engine = RecordingEngine(context, repo, SourceRepository(db.sources(), api, StalkerApi(http)), settings, db.channels(),
            EpgRepository(db.programmes(), db.epgFeeds(), db.epgAliases(), db.channels(), db.sources(), api, http, db, context.cacheDir),
            durationConnections = manager, durationClock = { clock }, durationChanged = {}, durationStop = {},
            scheduleAlarm = { id, at -> alarms += id to at })
    }
    @After fun close() = db.close()
    @Test fun dueSkipNeverReclaimsMultiViewAndDuplicateAlarmCannotReviveIt() = runBlocking<Unit> {
        repo.insert(row().copy(lateStartPolicy = "SKIP_IF_LATE"))
        val panel = (manager.reserve(ConnectionRequest("panel", 1, 1, ConnectionPurpose.MULTIVIEW)) as ReservationResult.Reserved).token
        val snapshots = manager.snapshots.value
        assertFalse(engine.prepareDue(1)); assertFalse(engine.prepareDue(1))
        assertEquals(RecordingStatus.MISSED, repo.byId(1)!!.status)
        assertEquals(snapshots, manager.snapshots.value)
        assertEquals(1, db.recordingEvents().forRecording(1).size)
        assertFalse(repo.claimScheduled(1, clock)); manager.release(panel)
    }
    @Test fun latePolicyRevalidationPreventsReclaimingPanelsAfterAdmissionDelay() = runBlocking<Unit> {
        clock = 1_000_000
        repo.insert(row().copy(lateStartPolicy = "SKIP_IF_LATE"))
        manager.reserve(ConnectionRequest("panel", 1, 1, ConnectionPurpose.MULTIVIEW))
        val snapshots = manager.snapshots.value
        var checks = 0
        val result = manager.reserveForRecording(ConnectionRequest("recording:1", 1, 1, ConnectionPurpose.RECORDING),
            allowInterruptLive = true, canStart = {
                if (++checks > 1) clock = 1_006_000
                engine.allowLateStart(repo.byId(1)!!, clock)
            })
        assertTrue(result is ReservationResult.Denied)
        assertEquals(RecordingStatus.MISSED, repo.byId(1)!!.status)
        assertEquals(snapshots, manager.snapshots.value)
    }
    @Test fun reconciliationUsesCurrentDefaultsAndExplicitOverrideIsUnchanged() = runBlocking<Unit> {
        repo.insert(row()); repo.insert(row().copy(id = 2, channelId = 2, sourceId = 2, lateStartPolicy = "RECORD_REMAINING"))
        engine.updateGlobalLateStart(LateStartOptions(LateStartPolicy.SKIP_IF_LATE))
        assertEquals(RecordingStatus.MISSED, repo.byId(1)!!.status)
        assertEquals(RecordingStatus.SCHEDULED, repo.byId(2)!!.status)
        assertTrue(alarms.any { it.first == 2L })
    }
    @Test fun accountConflictWaitsWithoutChangingActiveProtectedLockedOrExtendedWindow() = runBlocking<Unit> {
        repo.insert(row().copy(protectedRecording = false, seriesRuleId = 3))
        val active = row().copy(id = 2, channelId = 2, status = RecordingStatus.RECORDING,
            scheduledStartMillis = 100_000, programmeStartMillis = 100_000, programmeEndMillis = 1_500_000,
            scheduledEndMillis = 1_900_000, sizeBytes = 564, startedAtMillis = 100_000,
            originalEndMillis = 1_800_000, durationRevision = 2, manualExtensionMillis = 100_000)
        repo.insert(active)
        assertFalse(engine.prepareDue(1))
        assertEquals(RecordingStatus.SCHEDULED, repo.byId(1)!!.status)
        assertEquals(active, repo.byId(2)); assertEquals(1L to 1_901_000L, alarms.last())
        clock = 1_901_000
        repo.markFinished(2, true, clock, 564, null)
        assertTrue(engine.prepareDue(1))
        assertEquals(4_900_000, repo.byId(1)!!.scheduledEndMillis)
    }
    @Test fun anticipatedReleaseMustMeetMinimumAndLaterExtensionIsRevalidated() = runBlocking<Unit> {
        repo.insert(row().copy(lateStartPolicy = "MINIMUM_REMAINING", minimumRemainingMinutes = 15, protectedRecording = false, seriesRuleId = 3))
        val blocker = row().copy(id = 2, channelId = 2, status = RecordingStatus.RECORDING,
            scheduledStartMillis = 100_000, programmeStartMillis = 100_000, programmeEndMillis = 3_000_000,
            scheduledEndMillis = 3_500_000, sizeBytes = 564, startedAtMillis = 100_000)
        repo.insert(blocker)
        assertFalse(engine.prepareDue(1)); assertEquals(RecordingStatus.SCHEDULED, repo.byId(1)!!.status)
        db.recordings().update(blocker.copy(scheduledEndMillis = 4_000_000, durationRevision = 1))
        clock = 3_501_000
        assertFalse(engine.prepareDue(1)); assertEquals(RecordingStatus.MISSED, repo.byId(1)!!.status)
        assertEquals(1, repo.byId(2)!!.durationRevision)
    }
    @Test fun independentAccountDoesNotBlockLateRecordingAndRemovedProviderFailsWithoutOutput() = runBlocking<Unit> {
        val independent = source.copy(id = 2, username = "another-account")
        db.sources().insert(independent); manager.registerSource(independent); manager.setMaximumConnections(2, 1)
        repo.insert(row()); repo.insert(row().copy(id = 2, sourceId = 2, status = RecordingStatus.RECORDING))
        assertTrue(engine.prepareDue(1))
        db.sources().delete(1)
        assertFalse(engine.prepareDue(1)); assertEquals(RecordingStatus.FAILED, repo.byId(1)!!.status)
        assertFalse(java.io.File(row().filePath).exists())
    }
    @Test fun stalePreferenceRevisionCannotResetOtherProperties() = runBlocking<Unit> {
        clock = 800_000
        repo.insert(row())
        assertTrue(engine.updateLateStart(1, LateStartOptions(LateStartPolicy.MINIMUM_REMAINING, 7), 0))
        val before = repo.byId(1)!!
        assertFalse(engine.updatePreferences(1, 0, 0, false, false, LateStartOptions(), 0))
        assertEquals(before, repo.byId(1))
        assertTrue(engine.updatePreferences(1, 1, 6, true, true))
        assertEquals("MINIMUM_REMAINING", repo.byId(1)!!.lateStartPolicy)
        assertEquals(7, repo.byId(1)!!.minimumRemainingMinutes)
    }
}
