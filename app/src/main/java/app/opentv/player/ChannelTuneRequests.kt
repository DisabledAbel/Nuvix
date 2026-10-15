/* Copyright (C) 2026 Nuvix Contributors. GPL-3.0-or-later. */
package app.opentv.player

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** Serialises provider URL resolution before requests reach the existing PlayerController. */
class ChannelTuneRequests(private val scope: CoroutineScope) {
    var pendingId: Long? = null
        private set
    private var job: Job? = null
    private var generation = 0L

    fun cancel() {
        generation++
        job?.cancel()
        pendingId = null
    }

    fun submit(id: Long, playingId: Long?, resolveAndPlay: suspend () -> Unit) {
        if (id == playingId) { cancel(); return }
        if (id == pendingId) return
        cancel()
        val requestGeneration = generation
        pendingId = id
        job = scope.launch {
            try { resolveAndPlay() }
            finally { if (generation == requestGeneration) pendingId = null }
        }
    }
}
