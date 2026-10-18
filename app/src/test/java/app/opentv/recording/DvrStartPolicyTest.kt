/* Copyright (C) 2026 Nuvix Contributors. GPL-3.0-or-later. */
package app.opentv.recording

import app.opentv.data.model.Recording
import app.opentv.data.model.RecordingStatus
import org.junit.Assert.*
import org.junit.Test

/** Regression tests for dvr start policy. */
class DvrStartPolicyTest {
    /** Checks: starting One Account Cannot Stop A Rejected Recording On Another Account. */
    @Test fun startingOneAccountCannotStopARejectedRecordingOnAnotherAccount() {
        val due = recording(1, 1, protected = true)
        val unrelated = recording(2, 2)
        val victims = DvrStartPolicy.victims(due, listOf(unrelated), setOf(2), 1_000, 1) { "account-$it" }
        assertTrue(victims.isEmpty())
    }

    /** Checks: known Spare Capacity Does Not Preempt An Active Recording Rejected For A Future Window. */
    @Test fun knownSpareCapacityDoesNotPreemptAnActiveRecordingRejectedForAFutureWindow() {
        val due = recording(1, 1, protected = true)
        val active = recording(2, 1)
        assertTrue(DvrStartPolicy.victims(due, listOf(active), setOf(2), 1_000, 2) { "shared" }.isEmpty())
    }

    /** Checks: same Account Priority Preempts Only The Capacity Needed Now. */
    @Test fun sameAccountPriorityPreemptsOnlyTheCapacityNeededNow() {
        val due = recording(1, 1, protected = true)
        val earlier = recording(2, 1)
        val later = recording(3, 2)
        val unrelated = recording(4, 3)
        val victims = DvrStartPolicy.victims(due, listOf(earlier, later, unrelated), setOf(2, 3, 4), 1_000, 2) {
            if (it <= 2) "manually-linked-account" else "independent"
        }
        assertEquals(listOf(3L), victims.map { it.id })
    }

    /** Checks: unknown Allowance And Equal Priority Do Not Authorize Preemption. */
    @Test fun unknownAllowanceAndEqualPriorityDoNotAuthorizePreemption() {
        val due = recording(3, 1)
        val active = recording(2, 1).copy(status = RecordingStatus.RECORDING)
        assertTrue(DvrStartPolicy.victims(due, listOf(active), setOf(2), 1_000, null) { "shared" }.isEmpty())
        assertTrue(DvrStartPolicy.victims(due, listOf(active), setOf(2), 1_000, 1) { "shared" }.isEmpty())
    }

    /** Checks: ended And Not Yet Started Windows Do Not Consume Current Preemption Capacity. */
    @Test fun endedAndNotYetStartedWindowsDoNotConsumeCurrentPreemptionCapacity() {
        val due = recording(1, 1, protected = true)
        val ended = recording(2, 1).copy(status = RecordingStatus.FINALIZING, scheduledEndMillis = 1_000)
        val future = recording(3, 1).copy(status = RecordingStatus.PREPARING, scheduledStartMillis = 1_001)

        assertTrue(DvrStartPolicy.victims(due, listOf(ended, future), setOf(2, 3), 1_000, 1) { "shared" }.isEmpty())
    }

    /** Checks: missing Verified Account Never Treats Unregistered Sources As A Shared Pool. */
    @Test fun missingVerifiedAccountNeverTreatsUnregisteredSourcesAsASharedPool() {
        val due = recording(1, 1, protected = true)
        val active = recording(2, 2).copy(status = RecordingStatus.RECORDING)

        assertTrue(DvrStartPolicy.victims(due, listOf(active), setOf(2), 1_000, 1) { null }.isEmpty())
        assertTrue(DvrStartPolicy.victims(due, listOf(active), setOf(2), 1_000, 1) {
            if (it == due.sourceId) "verified" else null
        }.isEmpty())
    }

    /** Provides the recording fixture used by the current scheduling or capture scenario. */
    private fun recording(id: Long, source: Long, protected: Boolean = false) = Recording(
        id = id, channelId = id, sourceId = source, channelName = "Channel", title = "Programme", filePath = "/test/$id.ts",
        streamUrl = "https://example.com/$id.ts", status = RecordingStatus.SCHEDULED, protectedRecording = protected,
        scheduledStartMillis = 100, scheduledEndMillis = 10_000)
}
