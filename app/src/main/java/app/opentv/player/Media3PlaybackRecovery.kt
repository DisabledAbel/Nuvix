/* Copyright (C) 2026 Nuvix Contributors. GPL-3.0-or-later. */
package app.opentv.player

import android.os.SystemClock
import androidx.annotation.OptIn
import androidx.media3.common.Player
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** One inexpensive watchdog per existing live viewer. No network receiver, new player or capture work. */
@OptIn(UnstableApi::class)
class Media3PlaybackRecovery(
    private val player: ExoPlayer,
    scope: CoroutineScope,
    val budget: RecoveryBudget,
    private val allowed: () -> Boolean,
    private val buffer: () -> BufferPolicy,
    private val reconnect: () -> Unit,
    private val failed: (RecoveryFailure) -> Unit,
    private val clock: () -> Long = SystemClock::elapsedRealtime,
) : AutoCloseable {
    private var monitor = PlaybackRecoveryMonitor(budget, clock)
    private var budgetEpoch = budget.epoch
    private var closed = false
    private var seeking = false
    private var graceUntil = clock()
    private var handling = false
    private var detached = false
    private val listener = object : Player.Listener {
        override fun onPlayerError(error: PlaybackException) {
            if (!closed && allowed() && player.playerError === error) {
                val failure = PlaybackErrors.recoveryFailure(error)
                monitor.failure(failure, PlaybackErrors.retryAfterMs(error))
            }
        }
        override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) {
            if (handling) return
            monitor.suspendMonitoring()
            graceUntil = clock() + 15_000
            // Explicit rewind/catch-up remains outside live-edge recovery until the viewer returns live.
            if (reason == Player.DISCONTINUITY_REASON_SEEK) seeking = player.currentLiveOffset.let {
                it == androidx.media3.common.C.TIME_UNSET || it > buffer().maximumMs
            }
        }
        override fun onRenderedFirstFrame() { if (!closed && allowed()) budget.record(RecoveryEvent.FIRST_FRAME, clock()) }
    }
    private val job: Job
    init {
        player.addListener(listener)
        job = scope.launch {
            while (!closed && !detached) {
                delay(1_000)
                if (closed || detached) break
                if (budgetEpoch != budget.epoch) {
                    // An explicit selection can be a no-op on a retained healthy player.
                    // Rebind only while this exact owner is still the selected playback generation.
                    if (!allowed()) continue
                    monitor = PlaybackRecoveryMonitor(budget, clock)
                    budgetEpoch = budget.epoch
                }
                val expected = allowed() && player.playWhenReady &&
                    player.playbackSuppressionReason == Player.PLAYBACK_SUPPRESSION_REASON_NONE &&
                    (player.playerError != null || (!seeking && clock() >= graceUntil))
                if (expected) player.playerError?.let { monitor.failure(PlaybackErrors.recoveryFailure(it), PlaybackErrors.retryAfterMs(it)) }
                val counters = player.videoDecoderCounters
                counters?.ensureUpdated()
                val sample = RecoverySample(expected,
                    buffering = player.playbackState == Player.STATE_BUFFERING,
                    ready = player.playbackState == Player.STATE_READY && player.playerError == null,
                    ended = player.playbackState == Player.STATE_ENDED,
                    positionMs = player.currentPosition,
                    renderedFrames = counters?.renderedOutputBufferCount?.toLong() ?: 0,
                    bufferedMs = player.totalBufferedDuration,
                    videoExpected = player.videoFormat != null && counters != null)
                val policy = buffer()
                when (monitor.sample(sample, policy.playbackMs, policy.rebufferMs)) {
                    RecoveryAction.LIGHTWEIGHT -> {
                        // Keep the account slot and player. Media3 closes the previous load before preparing.
                        budget.record(RecoveryEvent.LIGHTWEIGHT, clock())
                        handling = true
                        try {
                            player.stop()
                            if (player.isCommandAvailable(Player.COMMAND_SEEK_TO_DEFAULT_POSITION)) player.seekToDefaultPosition()
                            player.prepare()
                            budget.record(RecoveryEvent.PREPARE, clock())
                        } finally { handling = false }
                    }
                    RecoveryAction.RECONNECT -> {
                        // The owner must close this real player before releasing/reacquiring its slot.
                        detached = true
                        reconnect()
                    }
                    RecoveryAction.FAIL -> {
                        detached = true
                        player.stop()
                        failed(budget.snapshot.value.failure ?: RecoveryFailure.UNKNOWN)
                    }
                    null -> Unit
                }
            }
        }
    }
    fun suspendMonitoring() = monitor.suspendMonitoring()
    override fun close() {
        if (closed) return
        closed = true; job.cancel(); player.removeListener(listener)
        if (!detached) monitor.cancel()
    }
}
