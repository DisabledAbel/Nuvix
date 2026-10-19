/* Copyright (C) 2026 Nuvix Contributors. GPL-3.0-or-later. */
package app.opentv.recording

import app.opentv.data.model.Recording
import app.opentv.data.model.RecordingStatus
import app.opentv.connections.*
import app.opentv.multiview.*
import org.junit.Assert.*
import org.junit.Test

class RecordingDurationConflictTest {
    private fun active() = Recording(id = 1, channelId = 1, sourceId = 1, channelName = "Game", title = "Game",
        filePath = "/game.ts", streamUrl = "https://private.invalid/token", status = RecordingStatus.RECORDING,
        startedAtMillis = 1000, scheduledStartMillis = 1000, programmeStartMillis = 1000,
        programmeEndMillis = 600_000, scheduledEndMillis = 900_000)
    private fun next() = active().copy(id = 2, sourceId = 2, channelId = 2, status = RecordingStatus.SCHEDULED,
        scheduledStartMillis = 900_000, scheduledEndMillis = 1_800_000, programmeStartMillis = 1_020_000,
        programmeEndMillis = 1_500_000, seriesRuleId = 1)
    private fun plan(rows: List<Recording>, shared: Boolean = true, capacity: Int? = 1) =
        DvrConflictPolicy.resolve(rows, 100_000, { if (shared) "shared" else "account:$it" }, { capacity })

    @Test fun extensionDetectsSharedAccountDemandWhileIndependentAccountsRemainUnaffected() {
        val extended = active().copy(scheduledEndMillis = 1_400_000)
        assertTrue(plan(listOf(active(), next())).rejectedIds.isEmpty())
        assertEquals(setOf(2L), plan(listOf(extended, next())).rejectedIds)
        assertTrue(plan(listOf(extended, next()), shared = false).rejectedIds.isEmpty())
        assertTrue(plan(listOf(extended, next()), capacity = 2).rejectedIds.isEmpty())
    }
    @Test fun lockedPaddingAndProtectedPriorityAreNeverTrimmedByExtensionPlanning() {
        val extended = active().copy(scheduledEndMillis = 1_000_000, lockPadding = true)
        val protected = next().copy(protectedRecording = true, lockPadding = true)
        val result = plan(listOf(extended, protected))
        assertTrue(1L in result.rejectedIds)
        assertEquals(protected.scheduledEndMillis, result.windows[2]!!.endMillis)
        assertTrue(result.adjustments.none { it.recordingId == 2L })
        val preferred = plan(listOf(extended.copy(protectedRecording = true), next()))
        assertEquals(1_000_000, preferred.windows[1]!!.endMillis)
        assertTrue(preferred.adjustments.all { it.recordingId != 1L })
    }
    @Test fun manualPriorityUnknownCapacityAndSameChannelAreInheritedFromExistingPolicy() {
        val extended = active().copy(scheduledEndMillis = 1_400_000)
        val manual = next().copy(seriesRuleId = null, channelId = 1)
        assertTrue(2L in plan(listOf(extended, manual)).rejectedIds)
        val unknown = plan(listOf(extended, manual), capacity = null)
        assertEquals(setOf(1L, 2L), unknown.unknownCapacityIds)
    }
    @Test fun presetForecastRecalculatesEndWithoutChangingAnyLiveReservations() {
        val allocator = ConnectionAllocator()
        allocator.registerSource(1, "account", AccountConnectionPolicy(2, false))
        val result = allocator.reserve(ConnectionRequest("recording:1", 1, 1, ConnectionPurpose.RECORDING)) as ReservationResult.Reserved
        allocator.markActive(result.token)
        allocator.reserve(ConnectionRequest("existing-viewer", 1, 1, ConnectionPurpose.MULTIVIEW))
        val originalReservations = allocator.snapshots.value
        val presentation = MultiViewPresentation(listOf(MultiViewSavedPanel("p", MultiViewChannelReference(3, 1))), "p", "p", MultiViewLayout.GRID)
        fun forecast(row: Recording) = MultiViewPresetRecordingPlanner.plan(presentation,
            listOf(MultiViewPreviewChannel(3, 1, "News", "Provider")), allocator.snapshots.value, listOf(row), 100_000)
        assertEquals(900_000, forecast(active()).recordings.single().endMillis)
        val extended = active().copy(scheduledEndMillis = 1_500_000, durationRevision = 1, manualExtensionMillis = 600_000)
        assertEquals(1_500_000, forecast(extended).recordings.single().endMillis)
        assertEquals(originalReservations, allocator.snapshots.value)
        assertEquals(ReservationState.ACTIVE, allocator.reservationState(result.token))
    }
}
