/* Copyright (C) 2026 Nuvix Contributors. Licensed under GPL-3.0-or-later. */
package app.opentv

import app.opentv.player.BufferConfiguration
import app.opentv.player.BufferPolicy
import app.opentv.player.BufferPreset
import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test

/** Regression tests for buffer policy. */
class BufferPolicyTest {
    /** Checks: automatic uses a smaller reservoir on constrained devices. */
    @Test fun `automatic uses a smaller reservoir on constrained devices`() {
        assertThat(BufferConfiguration().resolve(true)).isEqualTo(BufferPolicy.LOW_MEMORY_AUTO)
        assertThat(BufferConfiguration().resolve(false)).isEqualTo(BufferPolicy.MEDIUM)
    }

    /** Checks: rejects invalid ordering and excessive reservoir values. */
    @Test fun `rejects invalid ordering and excessive reservoir values`() {
        assertThrows(IllegalArgumentException::class.java) { BufferPolicy(1_000, 120_001, 500, 500) }
        assertThrows(IllegalArgumentException::class.java) { BufferPolicy(1_000, 5_000, 2_000, 500) }
        assertThrows(IllegalArgumentException::class.java) { BufferPolicy(5_000, 4_000, 500, 500) }
        assertThrows(IllegalArgumentException::class.java) { BufferPolicy(-1, 5_000, 500, 500) }
        assertThrows(IllegalArgumentException::class.java) { BufferPolicy(60_001, 90_000, 500, 500) }
    }

    /** Checks: byte budget takes priority even below the minimum duration. */
    @Test fun `byte budget takes priority even below the minimum duration`() {
        assertThat(BufferPolicy.LARGE.shouldContinueLoading(1_000_000, 1f, 16_000, 16_000, true)).isFalse()
    }

    /** Checks: a full DVR allocator cannot deadlock a depleted forward reservoir. */
    @Test fun `a full DVR allocator cannot deadlock a depleted forward reservoir`() {
        assertThat(BufferPolicy.MEDIUM.shouldContinueLoading(0, 1f, 16_000, 16_000, false)).isTrue()
    }

    /** Checks: loading retains hysteresis between minimum and maximum. */
    @Test fun `loading retains hysteresis between minimum and maximum`() {
        val policy = BufferPolicy.SMALL
        assertThat(policy.shouldContinueLoading(1_000_000, 1f, 0, 1_000, false)).isTrue()
        assertThat(policy.shouldContinueLoading(8_000_000, 1f, 0, 1_000, true)).isTrue()
        assertThat(policy.shouldContinueLoading(8_000_000, 1f, 0, 1_000, false)).isFalse()
        assertThat(policy.shouldContinueLoading(16_000_000, 1f, 0, 1_000, true)).isFalse()
    }

    /** Checks: rebuffer and playback speed affect the start reservoir. */
    @Test fun `rebuffer and playback speed affect the start reservoir`() {
        val policy = BufferPolicy.MEDIUM
        assertThat(policy.shouldStartPlayback(3_000_000, 1f, false, null, false)).isTrue()
        assertThat(policy.shouldStartPlayback(3_000_000, 1f, true, null, false)).isFalse()
        assertThat(policy.shouldStartPlayback(3_000_000, 2f, false, null, false)).isFalse()
    }

    /** Checks: live target limits startup delay and changing policy affects the next decision. */
    @Test fun `live target limits startup delay and changing policy affects the next decision`() {
        assertThat(BufferPolicy.LARGE.shouldStartPlayback(1_000_000, 1f, false, 2_000_000, false)).isTrue()
        val large = BufferConfiguration(BufferPreset.LARGE).resolve(false)
        val small = BufferConfiguration(BufferPreset.SMALL).resolve(false)
        assertThat(large.shouldContinueLoading(20_000_000, 1f, 0, 1_000, true)).isTrue()
        assertThat(small.shouldContinueLoading(20_000_000, 1f, 0, 1_000, true)).isFalse()
    }
}
