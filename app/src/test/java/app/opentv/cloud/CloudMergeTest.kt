package app.opentv.cloud

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CloudMergeTest {
    private val profile = "b".repeat(64)
    private val device = "00000000-0000-0000-0000-000000000001"
    private fun record(key: String = "a".repeat(64), time: Long = 1000, position: Long = 900) =
        CloudRecord(key, "movie_progress", profile, position, 1000, time, device)

    @Test fun aFailedUploadSurvivesAnEmptyLocalSnapshot() {
        val pending = record()
        assertEquals(listOf(pending), buildCloudOutbox(emptyList(), listOf(pending), emptyList()))
    }

    @Test fun latestRewindCoalescesWithoutDroppingOtherRecords() {
        val old = record()
        val rewind = record(time = 2000, position = 50)
        val unrelated = record(key = "c".repeat(64))
        assertEquals(setOf(rewind, unrelated), buildCloudOutbox(emptyList(), listOf(old, unrelated), listOf(rewind)).toSet())
    }

    @Test fun acknowledgedRemoteVersionDoesNotBecomeANewLocalUpload() {
        val local = record()
        val remote = local.copy(deviceId = "00000000-0000-0000-0000-000000000002")
        assertTrue(buildCloudOutbox(listOf(remote), emptyList(), listOf(local)).isEmpty())
        assertTrue(buildCloudOutbox(listOf(remote.copy(updatedAtMillis = 2000)), emptyList(), listOf(local)).isEmpty())
    }

    @Test fun remoteFavouritesDoNotRestoreLocalRemovalsAndMissingContentIsNotDismissed() {
        assertEquals(setOf("removed"), locallyDismissedFavourites(
            setOf("removed", "stillSelected", "missing"), setOf("removed", "stillSelected", "newRemote"), setOf("stillSelected")))
    }
}
