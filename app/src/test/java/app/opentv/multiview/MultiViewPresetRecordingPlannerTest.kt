/* Nuvix Copyright (C) 2026 Nuvix Contributors. GPL-3.0-or-later. */
package app.opentv.multiview

import app.opentv.connections.*
import app.opentv.data.model.Recording
import app.opentv.data.model.RecordingStatus
import app.opentv.recording.*
import org.junit.Assert.*
import org.junit.Test

class MultiViewPresetRecordingPlannerTest {
    private val now = 1_000_000L
    private fun booking(id: Long = 1, source: Long = 1, start: Long = now + 120_000, end: Long = start + 900_000) = Recording(
        id = id, channelId = 99, sourceId = source, channelName = "Recording channel", title = "Game $id",
        streamUrl = "https://private.invalid/user/secret", filePath = "/private/capture.ts", status = RecordingStatus.SCHEDULED,
        scheduledStartMillis = start, scheduledEndMillis = end,
        programmeStartMillis = start + 120_000, programmeEndMillis = end - 300_000)
    private fun ledger(max: Int? = 1, shared: Boolean = false) = ConnectionAllocator().apply {
        registerSource(1, "one", AccountConnectionPolicy(max, false))
        registerSource(2, if (shared) "one" else "two", AccountConnectionPolicy(max, false))
    }
    private fun presentation(vararg sources: Long) = MultiViewPresentation(sources.mapIndexed { i, source ->
        MultiViewSavedPanel("p$i", MultiViewChannelReference(i + 1L, source))
    }, "p0", "p0", MultiViewLayout.GRID)
    private fun catalog(p: MultiViewPresentation) = p.panels.map { MultiViewPreviewChannel(it.channel.channelId, it.channel.sourceId, "Channel ${it.panelId}", "Provider ${it.channel.sourceId}") }
    private fun plan(rows: List<Recording> = emptyList(), ledger: ConnectionAllocator = ledger(),
        proposal: MultiViewPresentation = presentation(1), at: Long = now, replaced: Set<String> = emptySet(),
        channels: List<MultiViewPreviewChannel> = catalog(proposal), window: RecordingPlanningWindow = RecordingPlanningWindow()) =
        MultiViewPresetRecordingPlanner.plan(proposal, channels, ledger.snapshots.value, rows, at, replaced, window)
    private fun ConnectionAllocator.open(id: String = "viewer", source: Long = 1, purpose: ConnectionPurpose = ConnectionPurpose.MULTIVIEW): ReservationToken {
        var result = reserve(ConnectionRequest(id, 1, source, purpose))
        if (result is ReservationResult.ApprovalRequired) result = reserve(result.challenge.request, approve(result.challenge))
        return (result as ReservationResult.Reserved).token
    }

    @Test fun noRecordingsMeansNoKnownConflictButExternalUsageIsAlwaysUncertain() {
        val plan = plan()
        assertTrue(plan.conflicts.isEmpty())
        assertTrue(PresetConflictKind.NO_KNOWN_CONFLICT in plan.classifications)
        assertTrue(PresetConflictKind.UNCERTAIN_AVAILABILITY in plan.classifications)
    }
    @Test fun futureConflictDoesNotBlockCurrentCapacity() {
        val result = plan(listOf(booking()))
        assertFalse(result.currentCapacityExceeded)
        assertEquals(PresetConflictKind.UPCOMING, result.conflicts.single().kind)
        assertEquals(now + 120_000, result.conflicts.single().atMillis)
        assertEquals(listOf("p0"), result.conflicts.single().panelIds)
    }
    @Test fun defaultTwentyFourHoursIsConfigurableAndHalfOpen() {
        val start = RecordingPlanningWindow().end(now)
        assertTrue(plan(listOf(booking(start = start))).conflicts.isEmpty())
        assertEquals(1, plan(listOf(booking(start = start - 1))).conflicts.size)
        assertTrue(plan(listOf(booking()), window = RecordingPlanningWindow(60_000)).conflicts.isEmpty())
    }
    @Test fun startAndEndPaddingUsePersistedEffectiveCaptureWindow() {
        val rec = booking()
        val result = plan(listOf(rec))
        assertEquals(rec.programmeStartMillis - 120_000, result.recordings.single().startMillis)
        assertEquals(rec.programmeEndMillis + 300_000, result.recordings.single().endMillis)
        assertEquals(PresetConflictKind.IMMEDIATE, plan(listOf(rec), at = rec.programmeEndMillis + 60_000).conflicts.single().kind)
        assertTrue(plan(listOf(rec), at = rec.scheduledEndMillis).conflicts.isEmpty())
    }
    @Test fun activeRecordingIsImmediateAndNotCountedTwice() {
        val ledger = ledger(2)
        ledger.open("recording:1", purpose = ConnectionPurpose.RECORDING)
        val rec = booking(start = now - 60_000).copy(status = RecordingStatus.RECORDING)
        assertTrue(plan(listOf(rec), ledger).conflicts.isEmpty())
        assertFalse(plan(listOf(rec), ledger).currentCapacityExceeded)
        ledger.configure(1, AccountConnectionPolicy(1, false))
        assertEquals(PresetConflictKind.IMMEDIATE, plan(listOf(rec), ledger).conflicts.single().kind)
    }
    @Test fun knownRecordingEndReleasesPredictedCapacityForNextBooking() {
        val ledger = ledger(2)
        ledger.open("recording:1", purpose = ConnectionPurpose.RECORDING)
        val active = booking(start = now - 600_000, end = now + 60_000).copy(status = RecordingStatus.RECORDING)
        val upcoming = booking(id = 2, start = now + 120_000)
        assertTrue(plan(listOf(active, upcoming), ledger).conflicts.isEmpty())
    }
    @Test fun overlappingRecordingsRequireCombinedCapacityAndYieldSecondaryFirst() {
        val result = plan(listOf(booking(), booking(2, start = now + 180_000)), ledger(3), presentation(1, 1))
        val overlap = result.conflicts.single()
        assertEquals(listOf(1L, 2L), overlap.recordingIds)
        assertEquals(listOf("p1"), overlap.panelIds)
        assertEquals(now + 180_000, overlap.atMillis)
    }
    @Test fun paddingOnlyOverlapIsEvaluatedAndLockedWindowsStayIntact() {
        val first = booking(end = now + 900_000).copy(lockPadding = true)
        val second = booking(2, start = now + 660_000).copy(lockPadding = true)
        val result = plan(listOf(first, second), ledger(2))
        assertEquals(now + 660_000, result.conflicts.first().atMillis)
        assertEquals(first.scheduledEndMillis, result.recordings.first().endMillis)
        assertTrue(result.recordings.all { it.lockPadding })
    }
    @Test fun protectedManualAndAutomaticPriorityComesFromDvrPolicy() {
        val series = booking().copy(seriesRuleId = 1)
        val manual = booking(2)
        assertEquals(listOf(2L), plan(listOf(series, manual)).recordings.map { it.id })
        val protected = series.copy(protectedRecording = true, lockPadding = true)
        val result = plan(listOf(protected, manual))
        assertEquals(listOf(1L), result.recordings.map { it.id })
        assertTrue(result.recordings.single().protectedRecording)
        assertTrue(result.recordings.single().automaticSeries)
    }
    @Test fun acceptedPaddingEditsAndExtensionsAreNotRecomputedFromDefaults() {
        val edited = booking().copy(scheduledStartMillis = now + 180_000, scheduledEndMillis = now + 2_000_000)
        val result = plan(listOf(edited))
        assertEquals(edited.scheduledStartMillis, result.recordings.single().startMillis)
        assertEquals(edited.scheduledEndMillis, result.recordings.single().endMillis)
    }
    @Test fun independentProvidersAndExplicitSharedAccounts() {
        assertTrue(plan(listOf(booking(source = 2)), ledger(), presentation(1)).conflicts.isEmpty())
        assertFalse(plan(listOf(booking(source = 2)), ledger(shared = true), presentation(1)).conflicts.isEmpty())
        val shared = plan(ledger = ledger(3, shared = true), proposal = presentation(1, 2))
        assertEquals(1, shared.accounts.size)
        assertEquals(2, shared.accounts.single().proposedAdditional)
    }
    @Test fun unknownIsNotUnlimitedAndDoesNotMintApproval() {
        val ledger = ledger(null)
        val before = ledger.snapshots.value
        val result = plan(listOf(booking()), ledger)
        assertTrue(PresetConflictKind.UNKNOWN_CAPACITY in result.classifications)
        assertEquals(PresetAllowanceKind.UNKNOWN, result.accounts.single().allowance)
        assertEquals(PresetConflictKind.UPCOMING, result.conflicts.single().kind)
        assertEquals(before, ledger.snapshots.value)
        ledger.open()
        assertTrue(ledger.reserve(ConnectionRequest("other", 1, 1, ConnectionPurpose.MULTIVIEW)) is ReservationResult.ApprovalRequired)
    }
    @Test fun providerReportedLimitsRemainDifferentFromManualConfiguration() {
        val ledger = ledger()
        ledger.configure(1, AccountConnectionPolicy(useProviderMetadata = true, providerMaximum = 2))
        assertEquals(PresetAllowanceKind.PROVIDER_REPORTED, plan(ledger = ledger).accounts.single().allowance)
        ledger.configure(1, AccountConnectionPolicy(2, false))
        assertEquals(PresetAllowanceKind.CONFIGURED, plan(ledger = ledger).accounts.single().allowance)
    }
    @Test fun pendingAndReleasingReservationsConsumeCapacity() {
        val ledger = ledger(2)
        val token = ledger.open()
        assertFalse(plan(ledger = ledger).currentCapacityExceeded)
        ledger.beginRelease(token)
        assertTrue(plan(ledger = ledger, proposal = presentation(1, 1)).currentCapacityExceeded)
        ledger.release(token)
        assertFalse(plan(ledger = ledger, proposal = presentation(1, 1)).currentCapacityExceeded)
    }
    @Test fun restoringWallExcludesOnlyExplicitlyReplacedTokens() {
        val ledger = ledger()
        val token = ledger.open()
        assertTrue(plan(ledger = ledger).currentCapacityExceeded)
        val replaced = plan(ledger = ledger, replaced = setOf(token.reservationId))
        assertFalse(replaced.currentCapacityExceeded)
        assertEquals(1, replaced.accounts.single().currentLocal)
        assertEquals(0, replaced.accounts.single().retainedLocal)
        assertEquals(ReservationState.PENDING, ledger.reservationState(token))
    }
    @Test fun changedLimitsCancellationAndReschedulingReplan() {
        val ledger = ledger()
        val rec = booking()
        assertFalse(plan(listOf(rec), ledger).conflicts.isEmpty())
        assertTrue(plan(listOf(rec.copy(status = RecordingStatus.CANCELED)), ledger).conflicts.isEmpty())
        assertEquals(now + 300_000, plan(listOf(rec.copy(scheduledStartMillis = now + 300_000)), ledger).conflicts.single().atMillis)
        ledger.configure(1, AccountConnectionPolicy(2, false))
        assertTrue(plan(listOf(rec), ledger).conflicts.isEmpty())
    }
    @Test fun missingProviderChannelOrChangedAccountRequiresExplicitRepair() {
        assertEquals(setOf("p0"), plan(channels = emptyList()).unavailablePanelIds)
        val moved = presentation(1).withChannel("p0", MultiViewChannelReference(1, 1, "old-account"))
        assertEquals(setOf("p0"), plan(proposal = moved).unavailablePanelIds)
        assertEquals(setOf("p0"), plan(ledger = ConnectionAllocator()).unavailablePanelIds)
    }
    @Test fun oneTimeEditsPreserveOriginalAndChooseRemainingPrimary() {
        val saved = presentation(1, 2)
        val edited = saved.withoutPanel("p0").withChannel("p1", MultiViewChannelReference(3, 2))
        assertEquals(2, saved.panels.size)
        assertEquals("p0", saved.primaryPanelId)
        assertEquals("p1", edited.primaryPanelId)
        assertNull(edited.audioPanelId)
        assertEquals(3L, edited.panels.single().channel.channelId)
    }
    @Test fun predictionDoesNotSuppressExistingPreDeadlineWarning() {
        val ledger = ledger()
        val rec = booking()
        plan(listOf(rec), ledger)
        ledger.open("primary")
        val warning = MultiViewRecordingConflictPolicy.detect(listOf(rec), ledger.snapshots.value, now)
        assertEquals(1, warning.size)
        assertEquals(rec.scheduledStartMillis, warning.single().effectiveStartMillis)
    }
    @Test fun previewContainsNoRecordingSecretsAndAllocatesNothingWithLargeCatalog() {
        val ledger = ledger(4)
        val before = ledger.snapshots.value
        val proposal = presentation(1, 1, 2, 2)
        val catalog = catalog(proposal) + (100..40_000).map { MultiViewPreviewChannel(it.toLong(), 1, "Synthetic $it", "Provider") }
        val result = plan(listOf(booking()), ledger, proposal, channels = catalog)
        assertFalse(result.toString().contains("private"))
        assertFalse(result.toString().contains("https://"))
        assertEquals(before, ledger.snapshots.value)
    }
}
