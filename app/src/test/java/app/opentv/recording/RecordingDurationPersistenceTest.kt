/* Copyright (C) 2026 Nuvix Contributors. GPL-3.0-or-later. */
package app.opentv.recording

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.opentv.data.db.OpenTvDatabase
import app.opentv.data.model.Recording
import app.opentv.data.model.RecordingStatus
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28])
class RecordingDurationPersistenceTest {
    private val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), OpenTvDatabase::class.java)
        .allowMainThreadQueries().build()
    private val dao get() = db.recordings()
    private fun row() = Recording(channelId = 1, sourceId = 1, channelName = "Game", title = "Game",
        filePath = "/game.ts", streamUrl = "https://private.invalid/user/password", status = RecordingStatus.RECORDING,
        startedAtMillis = 1000, scheduledStartMillis = 1000, programmeEndMillis = 600_000,
        scheduledEndMillis = 900_000, protectedRecording = true, lockPadding = true, sizeBytes = 564)
    private suspend fun demand() = (dao.active() + dao.scheduled()).map { it.copy(sizeBytes = 0) }.sortedBy { it.id }
    @After fun close() = db.close()

    @Test fun atomicExtensionPreservesCaptureAndPreferencesAndLogsOnce() = runBlocking {
        val id = dao.insert(row())
        val before = demand()
        assertTrue(dao.commitDuration(id, 0, 1_200_000, 900_000, 100_000, false, before, "RECORDING_EXTENDED"))
        assertFalse(dao.commitDuration(id, 0, 1_200_000, 900_000, 100_000, false, before, "RECORDING_EXTENDED"))
        val changed = dao.byId(id)!!
        assertEquals(1, changed.durationRevision)
        assertEquals(300_000, changed.manualExtensionMillis)
        assertEquals(row().copy(id = id, scheduledEndMillis = 1_200_000, originalEndMillis = 900_000,
            durationRevision = 1, manualExtensionMillis = 300_000, durationModifiedAtMillis = 100_000), changed)
        val events = db.recordingEvents().forRecording(id)
        assertEquals(1, events.size)
        assertFalse(events.single().message.contains("password"))
        assertFalse(events.single().message.contains("private.invalid"))
    }
    @Test fun oldStopIsIgnoredAndFinalizationWinningRaceRejectsEditor() = runBlocking {
        val id = dao.insert(row())
        assertTrue(dao.commitDuration(id, 0, 1_200_000, 900_000, 100_000, false, demand(), "RECORDING_EXTENDED"))
        assertEquals(0, dao.claimDurationDeadline(id, 0, 900_000))
        assertEquals(0, dao.claimDurationDeadline(id, 1, 900_000))
        val stale = demand()
        assertEquals(1, dao.claimDurationDeadline(id, 1, 1_200_000))
        assertFalse(dao.commitDuration(id, 1, 1_500_000, 900_000, 1_200_000, false, stale, "RECORDING_EXTENDED"))
        assertEquals(RecordingStatus.FINALIZING, dao.byId(id)!!.status)
    }
    @Test fun progressDoesNotInvalidateButNewBookingAndProtectionDo() = runBlocking {
        val id = dao.insert(row())
        var before = demand()
        dao.setSize(id, 999)
        assertTrue(dao.commitDuration(id, 0, 1_200_000, 900_000, 100_000, false, before, "RECORDING_EXTENDED"))
        before = demand()
        dao.insert(row().copy(status = RecordingStatus.SCHEDULED, channelId = 2))
        assertFalse(dao.commitDuration(id, 1, 1_500_000, 900_000, 100_000, false, before, "RECORDING_EXTENDED"))
        before = demand()
        dao.setProtection(id, false, true)
        assertFalse(dao.commitDuration(id, 1, 1_500_000, 900_000, 100_000, false, before, "RECORDING_EXTENDED"))
    }
    @Test fun explicitStopClaimsFinalizationBeforeAnotherExtension() = runBlocking {
        val id = dao.insert(row())
        assertTrue(dao.commitDuration(id, 0, 100_000, 900_000, 100_000, true, demand(), "STOPPED_MANUALLY"))
        assertEquals(RecordingStatus.FINALIZING, dao.byId(id)!!.status)
        assertEquals(564, dao.byId(id)!!.sizeBytes)
        assertFalse(dao.commitDuration(id, 1, 1_500_000, 900_000, 100_000, false, demand(), "RECORDING_EXTENDED"))
    }
    @Test fun persistedMetadataSurvivesRepositoryRecreationAndRecoveryRetainsPartial() = runBlocking {
        val id = dao.insert(row())
        assertTrue(dao.commitDuration(id, 0, 1_200_000, 900_000, 100_000, false, demand(), "RECORDING_EXTENDED"))
        val repo = app.opentv.data.repo.RecordingRepository(dao, db.seriesRules(), db.recordingEvents())
        assertEquals(1_200_000, repo.byId(id)!!.scheduledEndMillis)
        repo.failInterrupted()
        val recovered = repo.byId(id)!!
        assertEquals(RecordingStatus.PARTIAL, recovered.status)
        assertEquals(1_200_000, recovered.scheduledEndMillis)
        assertEquals(900_000, recovered.originalEndMillis)
        assertTrue(recovered.protectedRecording && recovered.lockPadding)
    }
}
