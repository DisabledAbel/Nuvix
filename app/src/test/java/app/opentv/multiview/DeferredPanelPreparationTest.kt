/* Copyright (C) 2026 Nuvix Contributors. GPL-3.0-or-later. */
package app.opentv.multiview

import app.opentv.player.RecoveryBudget
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DeferredPanelPreparationTest {
    @Test fun focusReturningAfterCooldownResumesExactlyOnePreparation() = runTest {
        val budget = RecoveryBudget().apply { deferFor(5_000, 0) }
        var allowed = false
        var prepared = 0
        val preparation = DeferredPanelPreparation(backgroundScope, budget, { testScheduler.currentTime }, { allowed }) { prepared++ }
        preparation.resume(); runCurrent()
        advanceTimeBy(5_000); runCurrent()
        assertEquals(0, prepared)
        allowed = true
        preparation.resume(); runCurrent()
        preparation.resume(); runCurrent()
        assertEquals(1, prepared)
        preparation.close()
    }
    @Test fun anExtendedCooldownAndReleaseStillPreventPreparation() = runTest {
        val budget = RecoveryBudget().apply { deferFor(5_000, 0) }
        var prepared = 0
        val preparation = DeferredPanelPreparation(backgroundScope, budget, { testScheduler.currentTime }, { true }) { prepared++ }
        preparation.resume(); runCurrent()
        advanceTimeBy(4_000); budget.deferFor(10_000, testScheduler.currentTime)
        advanceTimeBy(1_000); runCurrent()
        assertEquals(0, prepared)
        preparation.close(); advanceTimeBy(60_000); runCurrent()
        preparation.resume(); runCurrent()
        assertEquals(0, prepared)
    }
}
