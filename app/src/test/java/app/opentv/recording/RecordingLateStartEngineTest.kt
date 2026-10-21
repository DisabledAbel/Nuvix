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
    @Test fun conflictPreviewExcludesUnavailableScheduledSourcesAndKeepsAllActiveRows() = runBlocking<Unit> {
        val disabled = source.copy(id = 2, enabled = false, username = "disabled-account")
        db.sources().insert(disabled)
        repo.insert(row())
        repo.insert(row().copy(id = 2, sourceId = 2))
        repo.insert(row().copy(id = 3, sourceId = 3)) // Removed provider.
        repo.insert(row().copy(id = 4, sourceId = 2, status = RecordingStatus.RECORDING))
        repo.insert(row().copy(id = 5, sourceId = 3, status = RecordingStatus.PREPARING))
        repo.insert(row().copy(id = 6, sourceId = 4, status = RecordingStatus.FINALIZING))
        val before = repo.scheduled() + repo.active()
        val plan = engine.previewConflicts()
        assertEquals(setOf(1L, 4L, 5L, 6L), plan.windows.keys + plan.rejectedIds)
        assertFalse(2L in plan.unknownCapacityIds || 3L in plan.unknownCapacityIds)
        assertEquals(before, repo.scheduled() + repo.active())
        assertNotNull(manager.accountKeyForSource(2)) // Active disabled sources still register.
    }

    @Test fun seriesLateStartUpdateReportsMissingRuleAndSuccessfulPersistence() = runBlocking<Unit> {
        val options = LateStartOptions(LateStartPolicy.MINIMUM_REMAINING, 23)
        assertFalse(engine.updateSeriesLateStart(99, options))
        assertTrue(alarms.isEmpty())
        val id = repo.addRule(SeriesRule(channelId = 1, channelName = "Game", titleKey = "game", title = "Game"))
        repo.insert(row().copy(seriesRuleId = id))
        assertTrue(engine.updateSeriesLateStart(id, options))
        assertEquals(options.policy.name, repo.ruleById(id)!!.lateStartPolicy)
        assertEquals(23, repo.ruleById(id)!!.minimumRemainingMinutes)
        assertTrue(alarms.any { it.first == 1L })
    }

    private fun channel(id: Long = 1) = Channel(id = id, sourceId = 1, streamId = "$id", name = "Test $id",
        categoryId = null, logoUrl = null, epgChannelId = null, number = null, streamUrl = "https://private.invalid/live.ts")

    @Test fun manualUnboundedBookingPinsInheritedPolicyAndPreservesExplicitPolicies() = runBlocking<Unit> {
        engine.updateGlobalLateStart(LateStartOptions(LateStartPolicy.SKIP_IF_LATE))
        val inherited = repo.byId(engine.startChannel(channel()))!!
        assertEquals("RECORD_REMAINING", inherited.lateStartPolicy)
        assertEquals(0L, inherited.programmeEndMillis)
        for ((index, policy) in listOf(LateStartPolicy.RECORD_REMAINING, LateStartPolicy.SKIP_IF_LATE,
            LateStartPolicy.MINIMUM_REMAINING).withIndex()) {
            val explicit = repo.byId(engine.startChannel(channel(index + 2L),
                lateStart = LateStartOptions(policy, 23)))!!
            assertEquals(policy.name, explicit.lateStartPolicy)
            assertEquals(23, explicit.minimumRemainingMinutes)
        }
        val series = repo.byId(engine.startChannel(channel(5), ruleId = 10))!!
        assertEquals("INHERIT", series.lateStartPolicy)
        val now = System.currentTimeMillis()
        val bounded = repo.byId(engine.startChannel(channel(6), Programme(feedId = 1, epgChannelId = "test",
            title = "Test", startUtcMillis = now - 10_000, endUtcMillis = now + 60_000)))!!
        assertEquals("INHERIT", bounded.lateStartPolicy)
    }

    @Test fun reusedSeriesRuleSavesExplicitPolicyAndInheritLeavesItUnchanged() = runBlocking<Unit> {
        val channel = channel()
        val now = System.currentTimeMillis()
        val programme = Programme(feedId = 1, epgChannelId = "test", title = "Game",
            startUtcMillis = now + 600_000, endUtcMillis = now + 3_600_000)
        val ruleId = engine.recordSeries(channel, programme, emptyList(), LateStartOptions(LateStartPolicy.SKIP_IF_LATE))
        val original = repo.ruleById(ruleId)!!
        assertEquals("SKIP_IF_LATE", original.lateStartPolicy)
        val selected = LateStartOptions(LateStartPolicy.MINIMUM_REMAINING, 23)
        assertEquals(ruleId, engine.recordSeries(channel, programme, listOf(programme), selected))
        val updated = original.copy(lateStartPolicy = selected.policy.name, minimumRemainingMinutes = 23)
        assertEquals(updated, repo.ruleById(ruleId))
        val booking = repo.scheduled().single()
        assertEquals(ruleId, booking.seriesRuleId)
        assertEquals("INHERIT", booking.lateStartPolicy)
        assertEquals(selected, RecordingLateStart.resolve(booking, updated, LateStartOptions(LateStartPolicy.SKIP_IF_LATE)))
        assertEquals(ruleId, engine.recordSeries(channel, programme, listOf(programme)))
        assertEquals(updated, repo.ruleById(ruleId))
        assertEquals(1, repo.enabledRules().size)
        assertEquals(1, repo.scheduled().size)
    }
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
        val independent = source.copy(id = 3, username = "independent-account")
        db.sources().insert(independent); manager.registerSource(independent); manager.setMaximumConnections(3, 1)
        repo.insert(row().copy(id = 3, channelId = 3, sourceId = 3))
        assertFalse(engine.prepareDue(1))
        assertEquals(RecordingStatus.SCHEDULED, repo.byId(1)!!.status)
        assertEquals(active, repo.byId(2)); assertEquals(1L to 1_901_000L, alarms.last())
        clock = 1_100_000 // The user stops the active capture before its expected end.
        repo.markFinished(2, false, clock, 564, "Stopped early")
        alarms.clear()
        engine.rearmScheduled(excludingId = 2, releasedSourceId = 1)
        assertEquals(listOf(1L to 1_101_000L), alarms)
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
        assertFalse(engine.prepareDue(1)); assertEquals(RecordingStatus.SCHEDULED, repo.byId(1)!!.status)
        assertEquals(1L to 3_700_001L, alarms.last())
        val waiting = db.recordingEvents().forRecording(1).last()
        assertEquals(clock, waiting.atMillis)
        assertTrue(waiting.message.contains("expected capture start=4001000"))
        clock = 3_700_001 // Equality was still allowed one millisecond earlier.
        assertFalse(engine.prepareDue(1)); assertEquals(RecordingStatus.MISSED, repo.byId(1)!!.status)
        assertEquals(1, repo.byId(2)!!.durationRevision)
        val missedEvent = db.recordingEvents().forRecording(1).single { it.kind == "MISSED" }
        assertEquals(clock, missedEvent.atMillis)
        assertEquals(0L, repo.byId(1)!!.startedAtMillis)
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
