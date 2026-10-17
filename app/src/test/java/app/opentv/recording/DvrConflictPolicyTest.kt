/* Nuvix modifications Copyright (C) 2026 Nuvix Contributors. GPL-3.0-or-later. */
package app.opentv.recording

import app.opentv.data.model.Recording
import app.opentv.data.model.RecordingStatus
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class DvrConflictPolicyTest {
    @Test fun `defaults snapshot two and five minute padding independently from protection`() {
        val recording = booking(1)
        assertThat(recording.padStartMinutes).isEqualTo(2)
        assertThat(recording.padEndMinutes).isEqualTo(5)
        assertThat(recording.copy(lockPadding = true).protectedRecording).isFalse()
        assertThat(recording.copy(protectedRecording = true).lockPadding).isFalse()
    }

    @Test fun `independent accounts can capture the same airtime`() {
        val plan = resolve(listOf(booking(1), booking(2, source = 2)))
        assertThat(plan.rejectedIds).isEmpty()
        assertThat(plan.windows.keys).containsExactly(1L, 2L)
    }

    @Test fun `manually linked source aliases share one allowance`() {
        val plan = DvrConflictPolicy.resolve(listOf(booking(1), booking(2, source = 2)), 0, { "shared" }, { 1 })
        assertThat(plan.rejectedIds).containsExactly(2L)
    }

    @Test fun `two connections accommodate overlapping airtime`() {
        val plan = resolve(listOf(booking(1), booking(2)), capacity = 2)
        assertThat(plan.rejectedIds).isEmpty()
    }

    @Test fun `unknown allowance requires review and is never treated as one`() {
        val plan = resolve(listOf(booking(1), booking(2)), capacity = null)
        assertThat(plan.rejectedIds).isEmpty()
        assertThat(plan.unknownCapacityIds).containsExactly(1L, 2L)
        assertThat(plan.adjustments).isEmpty()
    }

    @Test fun `protection outranks manual scheduling and active capture`() {
        val protected = booking(2).copy(protectedRecording = true, seriesRuleId = 5)
        val active = booking(1).copy(status = RecordingStatus.RECORDING)
        assertThat(resolve(listOf(active, protected)).rejectedIds).containsExactly(1L)
    }

    @Test fun `manual scheduling outranks active automatic series at equal protection`() {
        val series = booking(1).copy(status = RecordingStatus.RECORDING, seriesRuleId = 7)
        assertThat(resolve(listOf(series, booking(2))).rejectedIds).containsExactly(1L)
    }

    @Test fun `active capture precedes scheduled time and stable identifier tie breaks`() {
        val active = booking(9).copy(status = RecordingStatus.RECORDING)
        assertThat(resolve(listOf(booking(1), active)).rejectedIds).containsExactly(1L)
        assertThat(resolve(listOf(booking(9), booking(1))).rejectedIds).containsExactly(9L)
    }

    @Test fun `unattended fallback trims minimum optional padding and preserves both programmes`() {
        val first = booking(1, start = 10, end = 20).copy(scheduledStartMillis = 8, scheduledEndMillis = 25)
        val second = booking(2, start = 20, end = 30).copy(scheduledStartMillis = 18, scheduledEndMillis = 35)
        val plan = resolve(listOf(second, first))
        assertThat(plan.rejectedIds).isEmpty()
        assertThat(plan.windows[1]).isEqualTo(DvrWindow(8, 20))
        assertThat(plan.windows[2]).isEqualTo(DvrWindow(20, 35))
        assertThat(plan.adjustments.map { it.recordingId }).containsExactly(1L, 2L)
    }

    @Test fun `padding lock is never trimmed even when that makes the next broadcast missed`() {
        val locked = booking(1, start = 10, end = 20).copy(scheduledStartMillis = 8, scheduledEndMillis = 25, lockPadding = true)
        val next = booking(2, start = 20, end = 30).copy(scheduledStartMillis = 18, scheduledEndMillis = 35)
        val plan = resolve(listOf(next, locked))
        assertThat(plan.windows[1]).isEqualTo(DvrWindow(8, 25))
        assertThat(plan.rejectedIds).containsExactly(2L)
        assertThat(plan.adjustments).isEmpty()
    }

    @Test fun `legacy padded windows are preserved because actual airtime is unknown`() {
        val legacy = booking(1).copy(programmeStartMillis = 0, programmeEndMillis = 0)
        val plan = resolve(listOf(legacy, booking(2)))
        assertThat(plan.windows[1]).isEqualTo(DvrWindow(10, 20))
        assertThat(plan.rejectedIds).containsExactly(2L)
    }

    @Test fun `past overlap does not block remaining airtime`() {
        val plan = DvrConflictPolicy.resolve(listOf(booking(1, start = 10, end = 20), booking(2, start = 15, end = 30)), 21, { "account" }, { 1 })
        assertThat(plan.rejectedIds).isEmpty()
    }

    private fun resolve(recordings: List<Recording>, capacity: Int? = 1) =
        DvrConflictPolicy.resolve(recordings, 0, { "account-$it" }, { capacity })

    private fun booking(id: Long, source: Long = 1, start: Long = 10, end: Long = 20) = Recording(
        id = id, channelId = id, sourceId = source, channelName = "Channel $id", title = "Programme $id",
        filePath = "/recordings/$id.ts", streamUrl = "https://example.invalid/$id.ts",
        scheduledStartMillis = start, scheduledEndMillis = end, programmeStartMillis = start,
        programmeEndMillis = end, status = RecordingStatus.SCHEDULED,
    )
}
