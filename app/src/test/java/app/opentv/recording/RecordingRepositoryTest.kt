/* Copyright (C) 2026 Nuvix Contributors. GPL-3.0-or-later. */
package app.opentv.recording

import android.app.Application
import androidx.room.Room
import app.opentv.data.db.OpenTvDatabase
import app.opentv.data.model.Recording
import app.opentv.data.model.RecordingStatus
import app.opentv.data.repo.RecordingRepository
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

/** Regression tests for recording repository. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28])
class RecordingRepositoryTest {
    /** Checks: duplicate Alarm Claims Exactly One Job And Startup Cannot Interrupt It. */
    @Test fun duplicateAlarmClaimsExactlyOneJobAndStartupCannotInterruptIt() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), OpenTvDatabase::class.java).allowMainThreadQueries().build()
        try {
            val repo = RecordingRepository(db.recordings(), db.seriesRules(), db.recordingEvents())
            val id = repo.insert(recording(RecordingStatus.SCHEDULED))
            val claims = (1..12).map { async { repo.claimScheduled(id, 1_000) } }.awaitAll()
            assertEquals(1, claims.count { it })
            repo.failInterrupted()
            assertEquals(RecordingStatus.PREPARING, repo.byId(id)?.status)
            repo.markStarted(id, 1_100)
            assertEquals(RecordingStatus.RECORDING, repo.byId(id)?.status)
            assertFalse(repo.claimScheduled(id, 1_200))
        } finally { db.close() }
    }

    /** Checks: interruption Recovers Bytes Written Since Last Database Checkpoint. */
    @Test fun interruptionRecoversBytesWrittenSinceLastDatabaseCheckpoint() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val file = File.createTempFile("partial", ".ts", context.cacheDir).apply { writeBytes(ByteArray(188)) }
        val db = Room.inMemoryDatabaseBuilder(context, OpenTvDatabase::class.java).allowMainThreadQueries().build()
        try {
            val repo = RecordingRepository(db.recordings(), db.seriesRules(), db.recordingEvents())
            val id = repo.insert(recording(RecordingStatus.RECORDING).copy(filePath = file.absolutePath, sizeBytes = 0))
            repo.failInterrupted()
            assertEquals(RecordingStatus.PARTIAL, repo.byId(id)?.status)
            assertEquals(188L, repo.byId(id)?.sizeBytes)
            assertTrue(file.exists())
            assertEquals("INTERRUPTED", db.recordingEvents().forRecording(id).single().kind)
        } finally { db.close(); file.delete() }
    }

    /** Checks: failed Capture With Content Is Partial While An Empty Capture Fails. */
    @Test fun failedCaptureWithContentIsPartialWhileAnEmptyCaptureFails() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), OpenTvDatabase::class.java).allowMainThreadQueries().build()
        try {
            val repo = RecordingRepository(db.recordings(), db.seriesRules())
            val partial = repo.insert(recording(RecordingStatus.RECORDING))
            val empty = repo.insert(recording(RecordingStatus.RECORDING))
            repo.markFinished(partial, false, 2_000, 188, "Destination removed")
            repo.markFinished(empty, false, 2_000, 0, "Destination removed")
            assertEquals(RecordingStatus.PARTIAL, repo.byId(partial)?.status)
            assertEquals(RecordingStatus.FAILED, repo.byId(empty)?.status)
        } finally { db.close() }
    }

    /** Provides the recording fixture used by the current scheduling or capture scenario. */
    private fun recording(status: RecordingStatus) = Recording(channelId = 1, sourceId = 1,
        channelName = "Test", title = "Programme", filePath = "/missing/test.ts", streamUrl = "https://example.com/live.ts", status = status)
}
