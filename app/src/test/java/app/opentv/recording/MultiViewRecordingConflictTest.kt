/* Nuvix Copyright (C) 2026 Nuvix Contributors. GPL-3.0-or-later. */
package app.opentv.recording

import app.opentv.connections.*
import app.opentv.data.model.Recording
import app.opentv.data.model.RecordingStatus
import org.junit.Assert.*
import org.junit.Test

class MultiViewRecordingConflictTest {
    private fun booking(id: Long = 1, source: Long = 1, start: Long = 240_000) = Recording(
        id = id, channelId = 99, sourceId = source, channelName = "Recording channel", title = "NHL Game",
        streamUrl = "https://example.invalid/capture", filePath = "/fake.ts", status = RecordingStatus.SCHEDULED,
        scheduledStartMillis = start, scheduledEndMillis = start + 600_000,
        programmeStartMillis = start + 120_000, programmeEndMillis = start + 300_000)
    private fun ledger(max: Int? = 1, shared: Boolean = false) = ConnectionAllocator().apply {
        registerSource(1, "one", AccountConnectionPolicy(max, false))
        registerSource(2, if (shared) "one" else "two", AccountConnectionPolicy(max, false))
    }
    private fun ConnectionAllocator.open(session: String, source: Long = 1, purpose: ConnectionPurpose = ConnectionPurpose.MULTIVIEW): ReservationToken {
        var result = reserve(ConnectionRequest(session, 1, source, purpose))
        if (result is ReservationResult.ApprovalRequired) result = reserve(result.challenge.request, approve(result.challenge))
        return (result as ReservationResult.Reserved).token
    }
    private fun detect(ledger: ConnectionAllocator, rows: List<Recording> = listOf(booking()), now: Long = 120_000, primary: String? = "primary") =
        MultiViewRecordingConflictPolicy.detect(rows, ledger.snapshots.value, now, primarySessionId = primary)

    @Test fun primaryConflictUsesExactReservation() {
        val ledger = ledger(); val token = ledger.open("primary")
        assertEquals(listOf(token), detect(ledger).single().victims)
    }
    @Test fun sufficientConfirmedCapacityProducesNoWarning() {
        val ledger = ledger(2); ledger.open("primary")
        assertTrue(detect(ledger).isEmpty())
    }
    @Test fun unrelatedProviderAndDuplicateRecordingChannelDoNotShareCapacity() {
        val ledger = ledger(); ledger.open("primary", 2)
        assertTrue(detect(ledger).isEmpty())
    }
    @Test fun explicitlyLinkedSourcesShareTheRecordingAccount() {
        val ledger = ledger(shared = true); ledger.open("primary", 2)
        assertEquals("one", detect(ledger).single().account.accountKey)
    }
    @Test fun unknownAllowanceIsExplicitAndDoesNotAuthorizeRecording() {
        val ledger = ledger(null); ledger.open("primary")
        assertTrue(detect(ledger).single().unknownAllowance)
        assertEquals(1, ledger.snapshotForSource(1)!!.locallyReserved)
    }
    @Test fun unknownAdditionalRecorderCannotBeSolvedByClosingViewers() {
        val ledger = ledger(null); ledger.open("primary"); ledger.open("capture", purpose = ConnectionPurpose.RECORDING)
        val active = booking(2, start = 1).copy(status = RecordingStatus.RECORDING, scheduledEndMillis = 900_000)
        assertTrue(detect(ledger, listOf(booking(), active)).isEmpty())
    }
    @Test fun warningBeginsTwoMinutesBeforePaddedStartAndExpiresAtDeadline() {
        val ledger = ledger(); ledger.open("primary")
        assertTrue(detect(ledger, now = 119_999).isEmpty())
        assertEquals(240_000L, detect(ledger, now = 120_000).single().effectiveStartMillis)
        assertTrue(detect(ledger, now = 240_000).isEmpty())
    }
    @Test fun duplicateEvaluationIsReadOnlyAndIdempotent() {
        val ledger = ledger(); ledger.open("primary")
        assertEquals(detect(ledger), detect(ledger))
        assertEquals(1, ledger.snapshotForSource(1)!!.locallyReserved)
    }
    @Test fun cancellationAndAlreadyStartedCaptureExpireWarning() {
        val ledger = ledger(); ledger.open("primary")
        assertTrue(detect(ledger, listOf(booking().copy(status = RecordingStatus.CANCELED))).isEmpty())
        assertTrue(detect(ledger, listOf(booking().copy(status = RecordingStatus.RECORDING))).isEmpty())
    }
    @Test fun reschedulingAndPaddingProduceNewStableWarningKeys() {
        assertNotEquals(booking().warningKey(), booking(start = 300_000).warningKey())
        assertNotEquals(booking().warningKey(), booking().copy(padEndMinutes = 10).warningKey())
        assertEquals(booking().warningKey(), booking().copy(title = "New title").warningKey())
    }
    @Test fun changedLimitAndFreedCapacityRemoveConflict() {
        val ledger = ledger(); val token = ledger.open("primary")
        ledger.configure(1, AccountConnectionPolicy(2, false)); assertTrue(detect(ledger).isEmpty())
        ledger.configure(1, AccountConnectionPolicy(1, false)); assertFalse(detect(ledger).isEmpty())
        ledger.release(token); assertTrue(detect(ledger).isEmpty())
    }
    @Test fun previewsThenSecondaryPanelsYieldBeforePrimary() {
        val ledger = ledger(3)
        ledger.open("primary"); val preview = ledger.open("preview", purpose = ConnectionPurpose.PREVIEW)
        val secondary = ledger.open("secondary")
        assertTrue("Preview alone supplies capacity", detect(ledger).isEmpty())
        val rows = listOf(booking(), booking(2).copy(lockPadding = true))
        assertEquals(listOf(preview, secondary), detect(ledger, rows).first().victims)
    }
    @Test fun simultaneousRecordingsAccountForAllDemandWithoutDoubleCountingActiveCapture() {
        val ledger = ledger(2); ledger.open("primary"); val secondary = ledger.open("secondary")
        val conflicts = detect(ledger, listOf(booking(), booking(2)))
        assertEquals(2, conflicts.size)
        assertEquals(setOf("primary", "secondary"), conflicts.first().victims.map { it.sessionId }.toSet())
        ledger.release(secondary)
        val capture = ledger.open("capture", purpose = ConnectionPurpose.RECORDING)
        val active = booking(2, start = 1).copy(status = RecordingStatus.RECORDING, scheduledEndMillis = 900_000)
        assertEquals(1, detect(ledger, listOf(booking(), active)).single().victims.size)
        assertEquals(ReservationState.PENDING, ledger.reservationState(capture))
    }
    @Test fun protectedManualPriorityAndLockedPaddingAreUnchanged() {
        val ledger = ledger(); ledger.open("primary")
        val manual = booking(2).copy(lockPadding = true)
        val series = booking().copy(seriesRuleId = 1, lockPadding = true)
        assertEquals(2L, detect(ledger, listOf(series, manual)).single().recording.id)
        val protected = series.copy(protectedRecording = true)
        assertEquals(protected, detect(ledger, listOf(manual, protected)).single().recording)
        assertEquals(240_000L, protected.scheduledStartMillis)
    }
    @Test fun processRestartWithNoRuntimeReservationsHasNoConflict() {
        assertTrue(detect(ledger()).isEmpty())
    }
}
