/* Nuvix modifications Copyright (C) 2026 Nuvix Contributors. GPL-3.0-or-later. */
package app.opentv.recording

import android.content.Context
import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.opentv.data.db.OpenTvDatabase
import app.opentv.data.model.Recording
import app.opentv.data.model.RecordingEvent
import app.opentv.data.model.RecordingStatus
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28])
class RecordingPersistenceTest {
    private lateinit var db: OpenTvDatabase

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), OpenTvDatabase::class.java)
            .allowMainThreadQueries().build()
    }

    @After fun tearDown() = db.close()

    @Test fun `duplicate deliveries claim a durable recording only once`() = runBlocking {
        val id = db.recordings().insert(recording())
        val claims = (1..8).map { async { db.recordings().claimScheduled(id, 1234) } }.awaitAll()
        assertThat(claims.sum()).isEqualTo(1)
        assertThat(db.recordings().byId(id)?.status).isEqualTo(RecordingStatus.PREPARING)
        assertThat(db.recordings().beginFinalizing(id)).isEqualTo(1)
        assertThat(db.recordings().beginFinalizing(id)).isEqualTo(0)
    }

    @Test fun `interruption preserves partial locator bytes and independent preferences`() = runBlocking {
        val id = db.recordings().insert(recording().copy(status = RecordingStatus.RECORDING, sizeBytes = 42, protectedRecording = true))
        db.recordings().failInterrupted()
        val recovered = db.recordings().byId(id)!!
        assertThat(recovered.status).isEqualTo(RecordingStatus.PARTIAL)
        assertThat(recovered.filePath).isEqualTo("/recordings/test.ts")
        assertThat(recovered.sizeBytes).isEqualTo(42)
        assertThat(recovered.protectedRecording).isTrue()
        assertThat(recovered.lockPadding).isFalse()
    }

    @Test fun `history is persisted in stable chronological order`() = runBlocking {
        val id = db.recordings().insert(recording())
        db.recordingEvents().insert(RecordingEvent(recordingId = id, atMillis = 20, kind = "padding", message = "End padding trimmed"))
        db.recordingEvents().insert(RecordingEvent(recordingId = id, atMillis = 10, kind = "scheduled", message = "Recording scheduled"))
        assertThat(db.recordingEvents().forRecording(id).map { it.kind }).containsExactly("scheduled", "padding").inOrder()
    }

    @Test fun `core airing identity survives padding overrides and conflict trimming`() = runBlocking {
        val id = db.recordings().insert(recording().copy(programmeStartMillis = 1000, programmeEndMillis = 2000,
            scheduledStartMillis = 800, scheduledEndMillis = 2300, seriesRuleId = 4))
        db.recordings().setScheduledWindow(id, 900, 2200)
        assertThat(db.recordings().bookingAt(1, 1000)?.id).isEqualTo(id)
        assertThat(db.recordings().countForRuleAt(4, 1, 1000)).isEqualTo(1)
    }

    @Test fun `preference edits cannot return an already claimed recording to scheduled`() = runBlocking {
        val id = db.recordings().insert(recording())
        db.recordings().claimScheduled(id, 1000)
        db.recordings().setProtection(id, protectedRecording = true, lockPadding = true)
        assertThat(db.recordings().setPadding(id, 3, 8, 2000, 3000)).isEqualTo(0)
        val claimed = db.recordings().byId(id)!!
        assertThat(claimed.status).isEqualTo(RecordingStatus.PREPARING)
        assertThat(claimed.protectedRecording).isTrue()
        assertThat(claimed.lockPadding).isTrue()
        assertThat(db.recordings().cancelScheduled(id)).isEqualTo(0)
        assertThat(db.recordings().prepareRetry(id, "/recordings/retry.ts")).isEqualTo(0)
        assertThat(db.recordings().setScheduledStatus(id, RecordingStatus.FAILED, "Late start rejection")).isEqualTo(0)
    }

    @Test fun `retry atomically archives partial bytes once and preserves protection preferences`() = runBlocking {
        val partial = recording().copy(status = RecordingStatus.PARTIAL, sizeBytes = 99, protectedRecording = true, lockPadding = true)
        val id = db.recordings().insert(partial)
        assertThat(db.recordings().retryPreserving(id, "/recordings/retry.ts", partial)).isTrue()
        assertThat(db.recordings().retryPreserving(id, "/recordings/duplicate.ts", partial)).isFalse()
        val rows = db.recordings().all()
        assertThat(rows).hasSize(2)
        val archive = rows.single { it.id != id }
        assertThat(archive.filePath).isEqualTo("/recordings/test.ts")
        assertThat(archive.sizeBytes).isEqualTo(99)
        val retry = rows.single { it.id == id }
        assertThat(retry.status).isEqualTo(RecordingStatus.SCHEDULED)
        assertThat(retry.sizeBytes).isEqualTo(0)
        assertThat(retry.protectedRecording).isTrue()
        assertThat(retry.lockPadding).isTrue()
    }

    @Test fun `a new padding lock prevents a stale conflict plan from trimming its window`() = runBlocking {
        val id = db.recordings().insert(recording().copy(scheduledStartMillis = 1000, scheduledEndMillis = 2000))
        db.recordings().setProtection(id, protectedRecording = false, lockPadding = true)
        assertThat(db.recordings().setScheduledWindow(id, 1100, 1900)).isEqualTo(0)
        assertThat(db.recordings().byId(id)?.scheduledStartMillis).isEqualTo(1000)
        assertThat(db.recordings().byId(id)?.scheduledEndMillis).isEqualTo(2000)
    }

    private fun recording() = Recording(channelId = 1, sourceId = 1, channelName = "News", title = "News",
        filePath = "/recordings/test.ts", streamUrl = "https://example.invalid/live.ts", status = RecordingStatus.SCHEDULED)
}
