/* Copyright (C) 2026 Nuvix Contributors. Licensed under GPL-3.0-or-later. */
package app.opentv

import android.app.Application
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.LoadControl
import androidx.media3.exoplayer.analytics.PlayerId
import androidx.media3.exoplayer.source.MediaSource.MediaPeriodId
import app.opentv.player.BufferPolicy
import app.opentv.player.ConfigurableLoadControl
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(UnstableApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class RuntimeBufferTest {
    @Test fun `changing load control policy keeps allocator and applies without reconstruction`() {
        val control = ConfigurableLoadControl(BufferPolicy.LARGE, 32 * 1024 * 1024)
        val allocator = control.allocator
        val parameters = LoadControl.Parameters(
            PlayerId.UNSET, Timeline.EMPTY, MediaPeriodId("test"), 0L,
            20_000_000L, 1f, true, false, C.TIME_UNSET,
        )
        assertThat(control.shouldContinueLoading(parameters)).isTrue()
        control.update(BufferPolicy.SMALL)
        assertThat(control.shouldContinueLoading(parameters)).isFalse()
        assertThat(control.allocator).isSameInstanceAs(allocator)
    }
}
