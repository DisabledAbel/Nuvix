/* Copyright (C) 2026 Nuvix Contributors. GPL-3.0-or-later. */
package app.opentv.recording

import app.opentv.data.model.Recording
import app.opentv.data.model.RecordingStatus
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class DvrStorageCleanupTest {
    private val now = 100 * 86_400_000L
    private var policy = DvrStoragePolicy(cleanupEnabled = true, targetBytes = 100)
    private var cap = StorageCapacity(StorageAvailability.AVAILABLE, now, 0, 1000)
    private var capacityReads = 0
    private val deleted = mutableListOf<Long>()
    private fun row(id: Long) = Recording(id = id, channelId = 1, sourceId = 1, channelName = "Synthetic", title = "Synthetic",
        filePath = "/synthetic-$id.ts", streamUrl = "https://example.invalid", status = RecordingStatus.COMPLETED,
        endedAtMillis = id * 1000, sizeBytes = 100)
    private fun runner(rows: List<Recording> = (1L..20).reversed().map(::row),
        owns: (Recording) -> Boolean = { true }, delete: suspend (Recording) -> StorageDeleteResult = {
            deleted += it.id; StorageDeleteResult.DELETED
        }) = DvrStorageCleanup({ policy }, { capacityReads++; cap }, { rows }, owns, delete, { now })

    @Test fun explicitOptInIsRequiredBeforeAnyStorageAccess() = runBlocking {
        policy = DvrStoragePolicy()
        assertTrue(runner().run().contains("off"))
        assertEquals(0, capacityReads); assertTrue(deleted.isEmpty())
    }
    @Test fun oldestEligibleFirstRechecksCapacityAndStopsAtMeasuredTarget() = runBlocking {
        val rows = listOf(row(4), row(2).copy(protectedRecording = true), row(1).copy(status = RecordingStatus.RECORDING), row(3))
        val report = runner(rows, delete = { deleted += it.id; cap = cap.copy(availableBytes = deleted.size * 50L); StorageDeleteResult.DELETED }).run()
        assertEquals(listOf(3L, 4L), deleted)
        assertEquals(3, capacityReads)
        assertTrue(report.contains("target met"))
    }
    @Test fun workIsBoundedAndDoesNotInventFreedCapacityFromRecordedSizes() = runBlocking {
        assertTrue(runner().run().contains("could not meet"))
        assertEquals((1L..10).toList(), deleted)
        assertEquals(11, capacityReads)
    }
    @Test fun unknownStaleAndDisconnectedStorageStopWithoutDeletion() = runBlocking {
        for (c in listOf(cap.copy(availableBytes = null), cap.copy(checkedAtMillis = now - 60_001), cap.copy(availability = StorageAvailability.DISCONNECTED))) {
            cap = c
            assertTrue(runner().run().contains("unknown")); assertTrue(deleted.isEmpty())
        }
    }
    @Test fun partialSuccessStopsOnErrorOrLostCapacityAndReportsIt() = runBlocking {
        var calls = 0
        val report = runner(delete = {
            if (++calls == 2) StorageDeleteResult.STORAGE_ERROR else { deleted += it.id; StorageDeleteResult.DELETED }
        }).run()
        assertEquals(listOf(1L), deleted); assertEquals(2, calls)
        assertTrue(report.contains("storage error")); assertTrue(report.contains("1 recording"))
        deleted.clear()
        val lost = runner(delete = { deleted += it.id; cap = cap.copy(availableBytes = null); StorageDeleteResult.DELETED }).run()
        assertEquals(listOf(1L), deleted); assertTrue(lost.contains("uncertain"))
    }
    @Test fun concurrentProtectionAndOptOutCannotBeTreatedAsSuccessfulDeletions() = runBlocking {
        var calls = 0
        val report = runner(delete = {
            calls++; policy = policy.copy(cleanupEnabled = false); StorageDeleteResult.CHANGED
        }).run()
        assertEquals(1, calls); assertTrue(deleted.isEmpty()); assertTrue(report.contains("disabled"))
    }
    @Test fun unownedRecentProtectedAndActiveRowsNeverReachDeletion() = runBlocking {
        val rows = listOf(row(1), row(2).copy(protectedRecording = true), row(3).copy(status = RecordingStatus.PREPARING), row(4).copy(endedAtMillis = now))
        assertTrue(runner(rows, owns = { false }).run().contains("could not meet"))
        assertTrue(deleted.isEmpty())
    }
    @Test fun manualBatchFreezesReviewSkipsChangedRowsAndStopsAfterPartialFailure() = runBlocking {
        val review = (1L..4).map(::row).toMutableList()
        val attempted = mutableListOf<Long>()
        val result = deleteReviewedRecordings(review) {
            attempted += it.id
            review += row(99) // A later list/selection update cannot broaden the confirmed batch.
            when (it.id) {
                1L -> StorageDeleteResult.CHANGED
                2L -> StorageDeleteResult.DELETED
                else -> StorageDeleteResult.STORAGE_ERROR
            }
        }
        assertEquals(listOf(1L, 2L, 3L), attempted)
        assertEquals(StorageDeletionSummary(1, 1, true), result)
    }
}
