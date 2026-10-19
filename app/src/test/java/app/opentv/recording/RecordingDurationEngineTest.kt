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
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28])
class RecordingDurationEngineTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val db = Room.inMemoryDatabaseBuilder(context, OpenTvDatabase::class.java).allowMainThreadQueries().build()
    private val manager = AccountConnectionManager.createForTest(context)
    private var clock = 100_000L
    private var rearmed = 0
    private val stops = mutableListOf<Long>()
    private lateinit var engine: RecordingEngine
    private val source = Source(id = 1, name = "Provider", kind = SourceKind.XTREAM,
        url = "https://private.invalid", username = "private-user", password = "private-password")
    private fun row() = Recording(id = 1, channelId = 1, sourceId = 1, channelName = "Game", title = "Game",
        filePath = File(context.filesDir.apply { mkdirs() }, "game.ts").absolutePath,
        streamUrl = "https://private.invalid/secret", status = RecordingStatus.RECORDING,
        startedAtMillis = 1000, scheduledStartMillis = 1000, programmeStartMillis = 1000,
        programmeEndMillis = 600_000, scheduledEndMillis = 900_000, protectedRecording = true, lockPadding = true)
    @Before fun setup() = runBlocking {
        db.sources().insert(source)
        manager.registerSource(source)
        manager.setMaximumConnections(1, 1)
        val http = OkHttpClient.Builder().addInterceptor { error("No provider requests allowed") }.build()
        val api = XtreamApi(http)
        val settings = AppSettings::class.java.getDeclaredConstructor(Context::class.java).apply { isAccessible = true }.newInstance(context)
        engine = RecordingEngine(context, RecordingRepository(db.recordings(), db.seriesRules(), db.recordingEvents()),
            SourceRepository(db.sources(), api, StalkerApi(http)), settings, db.channels(),
            EpgRepository(db.programmes(), db.epgFeeds(), db.epgAliases(), db.channels(), db.sources(), api, http, db, context.cacheDir),
            durationConnections = manager, durationClock = { clock }, durationChanged = { rearmed++ }, durationStop = { stops += it })
        db.recordings().insert(row())
        Unit
    }
    @After fun close() = db.close()
    @Test fun reviewedExtensionIsAppliedOnceRearmsAndPreservesAllReservations() = runBlocking {
        manager.reserve(ConnectionRequest("recording:1", 1, 1, ConnectionPurpose.RECORDING))
        val reservations = manager.snapshots.value
        val review = engine.previewDuration(1, DurationChoice.Add(5))
        assertNull(engine.applyDuration(review, false))
        assertNotNull(engine.applyDuration(review, false))
        assertEquals(1_200_000, db.recordings().byId(1)!!.scheduledEndMillis)
        assertEquals(1, rearmed)
        assertEquals(reservations, manager.snapshots.value)
        assertTrue(stops.isEmpty())
    }
    @Test fun staleAccountPolicyNewBookingAndCanceledEditorAreRejected() = runBlocking {
        var review = engine.previewDuration(1, DurationChoice.Add(5))
        manager.setMaximumConnections(1, 2)
        assertNotNull(engine.applyDuration(review, false))
        review = engine.previewDuration(1, DurationChoice.Add(5))
        db.recordings().insert(row().copy(id = 2, status = RecordingStatus.SCHEDULED, scheduledStartMillis = 2_000_000))
        assertNotNull(engine.applyDuration(review, false))
        review = engine.previewDuration(1, DurationChoice.Add(5))
        db.recordings().beginFinalizing(1)
        assertNotNull(engine.applyDuration(review, false))
    }
    @Test fun shorteningPastEndNeedsExplicitStopAndKeepsProtectionAndPaddingLock() = runBlocking {
        val review = engine.previewDuration(1, DurationChoice.EndAt(99_000))
        assertNotNull(engine.applyDuration(review, false))
        assertEquals(RecordingStatus.RECORDING, db.recordings().byId(1)!!.status)
        assertNull(engine.applyDuration(review, true))
        assertEquals(listOf(1L), stops)
        val changed = db.recordings().byId(1)!!
        assertTrue(changed.protectedRecording && changed.lockPadding)
        assertEquals(RecordingStatus.FINALIZING, changed.status)
    }
    @Test fun higherPriorityConflictBlocksAndPermittedConflictRequiresReviewedDecision() = runBlocking {
        val other = row().copy(id = 2, channelId = 2, status = RecordingStatus.SCHEDULED, scheduledStartMillis = 1_100_000,
            scheduledEndMillis = 2_000_000, programmeStartMillis = 1_100_000, programmeEndMillis = 1_700_000)
        db.recordings().insert(other)
        val review = engine.previewDuration(1, DurationChoice.Add(15))
        assertFalse(review.blocked) // Active protected manual has established priority.
        assertEquals(listOf(2L), review.affected.map { it.id })
        assertNull(engine.applyDuration(review, false))
        val events = db.recordingEvents().forRecording(1)
        assertEquals("CONFLICTING_EXTENSION_CONFIRMED", events.last().kind)
        assertEquals(other, db.recordings().byId(2))
    }
    @Test fun removedProviderBlocksOnlyLongerChangesAndUnboundedAddUsesFrozenPreview() = runBlocking {
        db.sources().delete(1)
        val review = engine.previewDuration(1, DurationChoice.Add(5))
        assertTrue(review.blocked)
        assertNotNull(engine.applyDuration(review, false))
        assertFalse(engine.previewDuration(1, DurationChoice.EndAt(500_000)).blocked)
    }    @Test fun unboundedAddFreezesEndAtPreviewAndDoesNotInventAnOriginalEnd() = runBlocking {
        db.recordings().update(row().copy(programmeEndMillis = 0, scheduledEndMillis = 0))
        val review = engine.previewDuration(1, DurationChoice.Add(5))
        clock += 1000
        assertNull(engine.applyDuration(review, false))
        val changed = db.recordings().byId(1)!!
        assertEquals(400_000, changed.scheduledEndMillis)
        assertEquals(-1, changed.originalEndMillis)
        assertTrue(changed.manualExtensionMillis in 299_000L..300_000L)
        assertThrows(IllegalArgumentException::class.java) { RecordingDuration.proposedEnd(changed, DurationChoice.Restore, clock) }
        Unit
    }
    @Test fun restoreAfterExtensionIgnoresSubsequentEpgAndGlobalPaddingChanges() = runBlocking {
        assertNull(engine.applyDuration(engine.previewDuration(1, DurationChoice.Add(5)), false))
        val changed = db.recordings().byId(1)!!
        db.recordings().update(changed.copy(programmeEndMillis = 700_000))
        val restore = engine.previewDuration(1, DurationChoice.Restore)
        assertEquals(900_000, restore.proposedEndMillis)
        assertNull(engine.applyDuration(restore, false))
        assertEquals(0, db.recordings().byId(1)!!.manualExtensionMillis)
    }
    @Test fun unavailableLocalStorageBlocksExtensionButAllowsShortening() = runBlocking {
        db.recordings().update(row().copy(filePath = "/missing-parent-for-test/subdirectory/recording.ts"))
        assertTrue(engine.previewDuration(1, DurationChoice.Add(5)).blocked)
        assertFalse(engine.previewDuration(1, DurationChoice.EndAt(500_000)).blocked)
    }
    @Test fun protectedUpcomingBookingRejectsLowerPriorityActiveExtension() = runBlocking {
        db.recordings().setProtection(1, false, true)
        db.recordings().insert(row().copy(id = 2, channelId = 2, status = RecordingStatus.SCHEDULED,
            scheduledStartMillis = 1_100_000, scheduledEndMillis = 2_000_000,
            programmeStartMillis = 1_100_000, programmeEndMillis = 1_700_000))
        val review = engine.previewDuration(1, DurationChoice.Add(15))
        assertTrue(review.blocked)
        assertEquals(listOf(2L), review.affected.map { it.id })
        assertNotNull(engine.applyDuration(review, false))
        assertEquals(900_000, db.recordings().byId(1)!!.scheduledEndMillis)
    }

}
