/* Nuvix Copyright (C) 2026 Nuvix Contributors. GPL-3.0-or-later. */
package app.opentv.multiview

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class MultiViewPresetPreviewControllerTest {
    private val proposal = MultiViewPresentation(listOf(MultiViewSavedPanel("p0", MultiViewChannelReference(1, 1, "one"))), "p0", "p0", MultiViewLayout.GRID)
    private fun inspection() = MultiViewPresetInspection(proposal, listOf(MultiViewPreviewChannel(1, 1, "Channel", "Provider")),
        PresetRecordingPlan(listOf(PresetAccountForecast("one", setOf(1), 0, 0, 1, 2, PresetAllowanceKind.CONFIGURED, false)), emptyList(), emptyList(), emptySet()))

    @Test fun refreshNeverLoadsAndConfirmationAlwaysReReads() = runTest {
        var reads = 0; val loaded = mutableListOf<MultiViewPresentation>()
        val controller = MultiViewPresetPreviewController({ reads++; inspection() }, loaded::add)
        controller.refresh(proposal)
        assertEquals(1, reads); assertTrue(loaded.isEmpty())
        assertTrue(controller.confirm(proposal))
        assertEquals(2, reads); assertEquals(listOf(proposal), loaded)
    }
    @Test fun repeatedOkWhileConfirmationSuspendsLoadsExactlyOnce() = runTest {
        val gate = CompletableDeferred<Unit>(); var gated = false; var loads = 0
        val controller = MultiViewPresetPreviewController({ if (gated) gate.await(); inspection() }, { loads++ })
        controller.refresh(proposal); gated = true
        val pending = launch { controller.confirm(proposal) }; runCurrent()
        assertFalse(controller.confirm(proposal)); gate.complete(Unit); pending.join()
        assertEquals(1, loads); assertFalse(controller.confirm(proposal))
    }
    @Test fun staleAsyncRefreshCannotOverwriteNewerProposal() = runTest {
        val gate = CompletableDeferred<Unit>(); var first = true
        val controller = MultiViewPresetPreviewController({ p -> if (first) { first = false; gate.await() }; inspection().copy(presentation = p) }, { fail("No load during preview") })
        val old = launch { controller.refresh(proposal) }; runCurrent()
        val newer = proposal.copy(layout = MultiViewLayout.FOCUSED)
        controller.refresh(newer); gate.complete(Unit); old.join()
        assertEquals(newer, controller.state.value.inspection!!.presentation)
    }
    @Test fun settingsChangedDuringConfirmationRequireAnotherChoice() = runTest {
        var current = inspection(); var loads = 0
        val controller = MultiViewPresetPreviewController({ current }, { loads++ })
        controller.refresh(proposal)
        current = current.copy(plan = current.plan.copy(accounts = current.plan.accounts.map { it.copy(maximum = 3) }))
        assertFalse(controller.confirm(proposal)); assertEquals(0, loads)
        assertNotNull(controller.state.value.message)
        assertTrue(controller.confirm(proposal)); assertEquals(1, loads)
    }
    @Test fun providerDeletionCannotLoadStaleCatalog() = runTest {
        var current = inspection(); var loads = 0
        val controller = MultiViewPresetPreviewController({ current }, { loads++ })
        controller.refresh(proposal)
        current = current.copy(channels = emptyList(), plan = current.plan.copy(unavailablePanelIds = setOf("p0")))
        assertFalse(controller.confirm(proposal)); assertFalse(controller.confirm(proposal)); assertEquals(0, loads)
    }
    @Test fun recordingBecomesDueWhileConfirmingRequiresReviewAndBlocksCurrentConflict() = runTest {
        var current = inspection(); var loads = 0
        val controller = MultiViewPresetPreviewController({ current }, { loads++ })
        controller.refresh(proposal)
        current = current.copy(plan = current.plan.copy(conflicts = listOf(PresetRecordingConflict(PresetConflictKind.IMMEDIATE, 100, 200, listOf(1), listOf("p0"), "one"))))
        assertFalse(controller.confirm(proposal)); assertFalse(controller.confirm(proposal)); assertEquals(0, loads)
    }
    @Test fun futureWarningDoesNotPreventLoadingOrWriteAcknowledgment() = runTest {
        val future = inspection().let { it.copy(plan = it.plan.copy(conflicts = listOf(PresetRecordingConflict(PresetConflictKind.UPCOMING, 100, 200, listOf(1), listOf("p0"), "one")))) }
        var loads = 0
        val controller = MultiViewPresetPreviewController({ future }, { loads++ })
        controller.refresh(proposal); assertTrue(controller.confirm(proposal)); assertEquals(1, loads)
    }
    @Test fun cancellationReschedulingAndPaddingChangesRequireReview() = runTest {
        val recording = PresetRecordingWindow(1, "Game", "Channel", 1, 100, 200, true, true, false, false)
        for (changed in listOf(emptyList(), listOf(recording.copy(startMillis = 120)), listOf(recording.copy(endMillis = 300)))) {
            var current = inspection().let { it.copy(plan = it.plan.copy(recordings = listOf(recording))) }
            var loads = 0
            val controller = MultiViewPresetPreviewController({ current }, { loads++ })
            controller.refresh(proposal); current = current.copy(plan = current.plan.copy(recordings = changed))
            assertFalse(controller.confirm(proposal)); assertEquals(0, loads)
        }
    }
    @Test fun invalidationDuringAwaitAndBackgroundingCannotCommit() = runTest {
        val gate = CompletableDeferred<Unit>(); var gated = false; var loads = 0
        val controller = MultiViewPresetPreviewController({ if (gated) gate.await(); inspection() }, { loads++ })
        controller.refresh(proposal); gated = true
        val pending = launch { controller.confirm(proposal) }; runCurrent()
        controller.invalidate(); gate.complete(Unit); pending.join()
        assertEquals(0, loads); assertNull(controller.state.value.inspection)
        assertEquals("Availability or recording requirements changed. Review the updated plan and choose again.", controller.state.value.message)
        assertFalse(controller.state.value.busy)
    }
    @Test fun restartingPreviewHasNoImplicitPlaybackOrStoredConsent() = runTest {
        val controller = MultiViewPresetPreviewController({ inspection() }, { fail("No playback without preview and confirmation") })
        assertFalse(controller.confirm(proposal)); controller.refresh(proposal)
        assertFalse(controller.state.value.busy)
    }
    @Test fun errorsAreSanitizedAndKeepPlaybackClosed() = runTest {
        val controller = MultiViewPresetPreviewController({ error("https://user:secret@private.invalid") }, { fail("Must stay closed") })
        controller.refresh(proposal)
        assertNull(controller.state.value.inspection)
        assertFalse(controller.state.value.message!!.contains("secret"))
        assertFalse(controller.confirm(proposal))
    }
}
