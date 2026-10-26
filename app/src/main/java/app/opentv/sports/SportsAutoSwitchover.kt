/* Copyright (C) 2026 Nuvix Contributors. GPL-3.0-or-later. */
package app.opentv.sports

import app.opentv.data.model.Channel
import app.opentv.player.PlayerController
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.coroutines.coroutineContext

/** Memory-only game ownership. A normal channel selection ends automatic sports recovery. */
internal data class SportsPlaybackSelection(val eventId: String, val channelId: Long)

/** Single-player recovery: never probes streams, opens parallel decoders or revisits failed choices. */
internal class SportsAutoSwitchover(
    private val scope: CoroutineScope,
    private val nextChannel: suspend (String, Set<Long>) -> Channel?,
    private val switchChannel: (Long) -> Unit,
    private val notify: (String) -> Unit,
) {
    private val _selection = MutableStateFlow<SportsPlaybackSelection?>(null)
    val selection = _selection.asStateFlow()
    private val attempted = mutableSetOf<Long>()
    private var job: Job? = null
    private var played = false
    private var receivedMediaState = false
    private var exhausted = false

    fun start(eventId: String, channelId: Long) {
        clear()
        attempted += channelId
        _selection.value = SportsPlaybackSelection(eventId, channelId)
    }

    fun clear() {
        job?.cancel()
        job = null
        _selection.value = null
        attempted.clear()
        played = false
        receivedMediaState = false
        exhausted = false
    }

    /** Called on state, setting, pause or lifecycle changes; cancellation prevents stale tuning. */
    fun observe(state: PlayerController.State, allowed: Boolean, enabled: Boolean,
        playbackBufferMs: Int = 0, rebufferMs: Int = 0) {
        job?.cancel()
        job = null
        val selected = selection.value ?: return
        if (!allowed || !enabled || exhausted) return
        if (state is PlayerController.State.Playing) {
            played = true
            receivedMediaState = true
            return
        }
        if (state is PlayerController.State.Idle && !receivedMediaState) return
        receivedMediaState = true
        job = scope.launch {
            // Give startup more time to build a buffer. Brief stalls can recover in place.
            if (state is PlayerController.State.Buffering) delay(
                if (played) maxOf(STALL_TIMEOUT_MS, rebufferMs + 5_000L)
                else maxOf(START_TIMEOUT_MS, playbackBufferMs + 10_000L),
            )
            val next = try {
                if (attempted.size >= MAX_ATTEMPTS) null else nextChannel(selected.eventId, attempted.toSet())
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (_: Exception) { null }
            coroutineContext.ensureActive()
            if (selection.value != selected) return@launch
            if (next == null || next.id in attempted) {
                exhausted = true
                notify("No more confirmed streams are available for this game. Retry or choose a broadcast in Sports.")
            } else {
                attempted += next.id
                played = false
                receivedMediaState = false
                notify("Switching to another confirmed broadcast of this game.")
                switchChannel(next.id)
                _selection.value = selected.copy(channelId = next.id)
            }
        }
    }

    companion object {
        const val STALL_TIMEOUT_MS = 8_000L
        const val START_TIMEOUT_MS = 20_000L
        const val MAX_ATTEMPTS = 200
    }
}
