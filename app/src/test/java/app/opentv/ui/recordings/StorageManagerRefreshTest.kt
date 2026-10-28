/* Copyright (C) 2026 Nuvix Contributors. GPL-3.0-or-later. */
package app.opentv.ui.recordings

import app.opentv.core.AppSettings
import app.opentv.recording.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class StorageManagerRefreshTest {
    private fun snapshot(bytes: Long) = listOf(StorageSummary(
        RecordingDestination("opaque", "This device", AppSettings.RecordingTarget.INTERNAL),
        StorageCapacity(StorageAvailability.AVAILABLE, 1), bytes, null, DvrStoragePolicy()))

    @Test fun repositoryFailureRetainsLastSnapshotAndNextScheduledRefreshRecovers() = runTest {
        val first = snapshot(100)
        val recovered = snapshot(200)
        var visible = emptyList<StorageSummary>()
        var attempts = 0
        var failures = 0
        val job = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            refreshStorageSummaries(load = {
                when (++attempts) {
                    1 -> first
                    2 -> throw java.io.IOException("Synthetic repository read failure")
                    else -> recovered
                }
            }, onLoaded = { visible = it }, onFailure = { failures++ })
        }
        assertEquals(first, visible)
        advanceTimeBy(30_000); runCurrent()
        assertEquals(1, failures); assertEquals(first, visible)
        advanceTimeBy(30_000); runCurrent()
        assertEquals(recovered, visible); assertEquals(3, attempts)
        job.cancelAndJoin()
        advanceTimeBy(30_000); runCurrent()
        assertEquals(3, attempts)
    }

    @Test fun refreshCancellationPropagatesWithoutReportingARepositoryFailure() = runTest {
        var failures = 0
        val job = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            refreshStorageSummaries(load = { throw CancellationException("Canceled") },
                onLoaded = { fail("Canceled refresh must not replace the snapshot") }, onFailure = { failures++ })
        }
        job.join()
        assertTrue(job.isCancelled)
        assertEquals(0, failures)
    }
}
