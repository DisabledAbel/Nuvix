/* Copyright (C) 2026 Nuvix Contributors. GPL-3.0-or-later. */
package app.opentv.recording

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.opentv.core.AppSettings
import app.opentv.data.db.OpenTvDatabase
import app.opentv.data.model.*
import app.opentv.data.repo.RecordingRepository
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28])
class RecordingLateStartPersistenceTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val db = Room.inMemoryDatabaseBuilder(context, OpenTvDatabase::class.java).allowMainThreadQueries().build()
    private val repo = RecordingRepository(db.recordings(), db.seriesRules(), db.recordingEvents())
    private fun row() = Recording(channelId = 1, sourceId = 1, channelName = "News", title = "News",
        filePath = File(context.cacheDir, "never-created.ts").absolutePath, streamUrl = "https://secret.invalid/password",
        status = RecordingStatus.SCHEDULED, programmeStartMillis = 1_000_000, programmeEndMillis = 4_600_000,
        scheduledStartMillis = 880_000, scheduledEndMillis = 4_900_000, protectedRecording = true, lockPadding = true,
        originalEndMillis = 4_900_000, durationRevision = 2)
    @After fun close() = db.close()

    @Test fun preparationIsNotUsefulCaptureAndSkipIsAtomicVisibleAndFileFree() = runBlocking {
        val id = repo.insert(row().copy(lateStartPolicy = "SKIP_IF_LATE"))
        assertTrue(repo.claimScheduled(id, 1_000_000)); assertFalse(repo.claimScheduled(id, 1_000_001))
        val preparing = repo.byId(id)!!
        assertEquals(0L, preparing.startedAtMillis)
        val options = LateStartOptions(LateStartPolicy.SKIP_IF_LATE)
        val decision = RecordingLateStart.evaluate(preparing, 1_006_000, options)
        assertTrue(repo.commitLateStart(preparing, 1_006_000, decision))
        assertFalse(repo.commitLateStart(preparing, 1_006_000, decision))
        assertFalse(repo.claimScheduled(id, 1_007_000))
        val missed = repo.byId(id)!!
        assertEquals(RecordingStatus.MISSED, missed.status); assertEquals(0, missed.sizeBytes)
        assertFalse(File(missed.filePath).exists()); assertTrue(repo.all().any { it.id == id })
        assertEquals(1, db.recordingEvents().forRecording(id).size)
    }
    @Test fun processRestartDuringPreparationRearmsAndKeepsExplicitPolicyAndDuration() = runBlocking {
        val id = repo.insert(row().copy(status = RecordingStatus.PREPARING, lateStartPolicy = "SKIP_IF_LATE"))
        repo.failInterrupted()
        val recovered = repo.byId(id)!!
        assertEquals(RecordingStatus.SCHEDULED, recovered.status)
        assertEquals("SKIP_IF_LATE", recovered.lateStartPolicy)
        assertEquals(2, recovered.durationRevision)
        assertNotEquals(row().filePath, recovered.filePath)
        assertEquals(0, recovered.startedAtMillis)
        assertEquals(RecordingLateStart.Decision.Action.SKIP, RecordingLateStart.evaluate(recovered, 1_006_000,
            LateStartOptions(LateStartPolicy.SKIP_IF_LATE)).action)
        assertEquals("STARTUP_RECOVERED", db.recordingEvents().forRecording(id).single().kind)
    }
    @Test fun interruptedExternalPreparationPreservesUncertainFileInsteadOfReusingIt() = runBlocking {
        val id = repo.insert(row().copy(status = RecordingStatus.PREPARING, filePath = "content://test/old-destination"))
        repo.failInterrupted()
        assertEquals(RecordingStatus.SCHEDULED, repo.byId(id)!!.status)
        assertNotEquals("content://test/old-destination", repo.byId(id)!!.filePath)
        assertEquals("content://test/old-destination", repo.all().single { it.id != id }.filePath)
    }
    @Test fun actualStartSnapshotSurvivesRestartAndLateBytesBecomePartial() = runBlocking {
        val id = repo.insert(row())
        assertTrue(repo.claimScheduled(id, 1_000_000))
        val preparing = repo.byId(id)!!
        val decision = RecordingLateStart.evaluate(preparing, 1_120_000, LateStartOptions(LateStartPolicy.RECORD_REMAINING))
        assertTrue(repo.commitLateStart(preparing, 1_120_000, decision))
        repo.setSize(id, 564)
        val restarted = RecordingRepository(db.recordings(), db.seriesRules(), db.recordingEvents())
        assertEquals(1_120_000, restarted.byId(id)!!.startedAtMillis)
        assertEquals(120_000, restarted.byId(id)!!.captureDelayMillis)
        restarted.failInterrupted()
        val recovered = restarted.byId(id)!!
        assertEquals(RecordingStatus.PARTIAL, recovered.status); assertTrue(recovered.contentMissed)
        assertTrue(recovered.protectedRecording && recovered.lockPadding)
        assertEquals(2, recovered.durationRevision); assertEquals(4_900_000, recovered.scheduledEndMillis)
    }
    @Test fun stalePolicyCallbackCannotSkipOrStartChangedOrCanceledBooking() = runBlocking {
        val id = repo.insert(row())
        val stale = repo.byId(id)!!
        assertTrue(repo.setLateStart(stale, LateStartOptions(LateStartPolicy.SKIP_IF_LATE)))
        assertFalse(repo.setLateStart(stale, LateStartOptions()))
        assertFalse(repo.commitLateStart(stale, 1_006_000,
            RecordingLateStart.evaluate(stale, 1_006_000, LateStartOptions(LateStartPolicy.SKIP_IF_LATE))))
        assertTrue(repo.cancelScheduled(id))
        assertFalse(repo.claimScheduled(id, 1_000_000))
        assertEquals(RecordingStatus.CANCELED, repo.byId(id)!!.status)
    }
    @Test fun optionalPaddingDelayCompletesAndRetryRetainsPolicyAndArchivedContent() = runBlocking {
        val id = repo.insert(row().copy(lateStartPolicy = "MINIMUM_REMAINING", minimumRemainingMinutes = 7))
        assertTrue(repo.claimScheduled(id, 990_000))
        val preparing = repo.byId(id)!!
        val d = RecordingLateStart.evaluate(preparing, 999_000, LateStartOptions(LateStartPolicy.MINIMUM_REMAINING, 7))
        assertTrue(repo.commitLateStart(preparing, 999_000, d))
        repo.markFinished(id, !d.contentMissed, 4_900_000, 564, null)
        assertEquals(RecordingStatus.COMPLETED, repo.byId(id)!!.status)
        repo.setStatus(id, RecordingStatus.PARTIAL, "Interrupted")
        val archive = repo.byId(id)!!.copy(id = 0, seriesRuleId = null)
        assertTrue(repo.retryPreserving(id, "/new-destination.ts", archive))
        val retry = repo.byId(id)!!
        assertEquals("MINIMUM_REMAINING", retry.lateStartPolicy); assertEquals(7, retry.minimumRemainingMinutes)
        assertEquals(0, retry.startedAtMillis); assertEquals("", retry.capturePolicy)
        assertEquals(564, repo.all().first { it.id != id }.sizeBytes)
    }
    @Test fun globalAndSeriesInheritanceRemainLiveWhileEpisodeOverridePersists() = runBlocking {
        fun settings() = AppSettings::class.java.getDeclaredConstructor(Context::class.java).apply { isAccessible = true }.newInstance(context)
        val global = settings(); global.setRecordLateStart(LateStartOptions(LateStartPolicy.RECORD_REMAINING))
        val ruleId = repo.addRule(SeriesRule(channelId = 1, channelName = "News", titleKey = "news", title = "News"))
        val id = repo.insert(row().copy(seriesRuleId = ruleId))
        global.setRecordLateStart(LateStartOptions(LateStartPolicy.SKIP_IF_LATE))
        assertEquals(LateStartPolicy.SKIP_IF_LATE, RecordingLateStart.resolve(repo.byId(id)!!, repo.ruleById(ruleId), settings().recordLateStart.value).policy)
        assertTrue(repo.setRuleLateStart(ruleId, LateStartOptions(LateStartPolicy.MINIMUM_REMAINING, 15)))
        assertEquals(15, RecordingLateStart.resolve(repo.byId(id)!!, repo.ruleById(ruleId), global.recordLateStart.value).minimumMinutes)
        assertTrue(repo.setLateStart(repo.byId(id)!!, LateStartOptions(LateStartPolicy.RECORD_REMAINING)))
        assertEquals(LateStartPolicy.RECORD_REMAINING, RecordingLateStart.resolve(repo.byId(id)!!, repo.ruleById(ruleId), global.recordLateStart.value).policy)
        Unit
    }
}
