/*
 * Nuvix runtime buffer policy. Copyright (C) 2026 Nuvix Contributors.
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.player

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.LoadControl
import androidx.media3.exoplayer.upstream.DefaultAllocator

/**
 * Media3 keeps this load control and allocator for the player's full lifetime. Updating the
 * volatile policy changes future loading decisions, preserving the media item, position, codecs,
 * surface and already-buffered data. The byte target limits reservoirs on low-memory TV sticks;
 * a small forward-buffer floor prevents deadlock when the allocator also holds DVR history.
 */
@OptIn(UnstableApi::class)
class ConfigurableLoadControl(
    initial: BufferPolicy,
    private val budgetBytes: Int,
    backBufferMs: Int = 0,
) : DefaultLoadControl(
    DefaultAllocator(true, C.DEFAULT_BUFFER_SEGMENT_SIZE),
    initial.minimumMs, initial.maximumMs, initial.playbackMs, initial.rebufferMs,
    budgetBytes, false, backBufferMs, backBufferMs > 0,
) {
    private data class Configuration(val policy: BufferPolicy, val useLiveTarget: Boolean)
    @Volatile private var configuration = Configuration(initial, true)
    private var wasLoading = false

    /** Replaces the buffering policy used for subsequent loading and startup decisions. */
    fun update(policy: BufferPolicy, useLiveTarget: Boolean = true) { configuration = Configuration(policy, useLiveTarget) }

    /** Combines configured time thresholds with allocator byte limits and loading hysteresis. */
    override fun shouldContinueLoading(parameters: LoadControl.Parameters): Boolean {
        wasLoading = configuration.policy.shouldContinueLoading(
            parameters.bufferedDurationUs, parameters.playbackSpeed,
            allocator.totalBytesAllocated, budgetBytes, wasLoading,
        )
        return wasLoading
    }

    /** Evaluates playback startup using the current buffer policy and live target. */
    override fun shouldStartPlayback(parameters: LoadControl.Parameters): Boolean {
        val selected = configuration
        return selected.policy.shouldStartPlayback(
            parameters.bufferedDurationUs, parameters.playbackSpeed, parameters.rebuffering,
            parameters.targetLiveOffsetUs.takeUnless { it == C.TIME_UNSET || !selected.useLiveTarget },
            allocator.totalBytesAllocated >= budgetBytes,
        )
    }
}
