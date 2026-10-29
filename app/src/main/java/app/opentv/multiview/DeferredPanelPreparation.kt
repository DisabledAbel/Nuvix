/* Copyright (C) 2026 Nuvix Contributors. GPL-3.0-or-later. */
package app.opentv.multiview

import app.opentv.player.RecoveryBudget
import app.opentv.player.RecoveryFailure
import app.opentv.player.RecoveryState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** A deferred prepare stays resumable after transient focus loss; it never owns a player. */
internal class DeferredPanelPreparation(
    private val scope: CoroutineScope,
    private val budget: RecoveryBudget,
    private val clock: () -> Long,
    private val allowed: () -> Boolean,
    private val prepare: () -> Unit,
) : AutoCloseable {
    private var job: Job? = null
    private var prepared = false
    private var closed = false
    fun resume() {
        if (closed || prepared || job?.isActive == true) return
        job = scope.launch {
            while (budget.notBeforeMs > clock()) {
                budget.publish(RecoveryState.WAITING, clock(), RecoveryFailure.RATE_LIMIT)
                delay(budget.notBeforeMs - clock())
            }
            if (closed || !allowed()) return@launch
            prepared = true
            prepare()
        }
    }
    override fun close() { closed = true; job?.cancel(); job = null }
}
