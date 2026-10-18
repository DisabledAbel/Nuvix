/* Copyright (C) 2026 Nuvix Contributors. Licensed under GPL-3.0-or-later. */
package app.opentv

import android.app.Application
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import app.opentv.player.PlayerController
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import okhttp3.OkHttpClient
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Regression tests for live player controller. */
@OptIn(UnstableApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class LivePlayerControllerTest {
    /** Runs an assertion block with a player controller and releases it afterward. */
    private fun withController(test: (PlayerController) -> Unit) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val controller = PlayerController(RuntimeEnvironment.getApplication(), scope, OkHttpClient())
        try { test(controller) } finally { controller.release(); scope.cancel() }
    }

    /** Checks: explicit captions off survives applying defaults after fullscreen reentry. */
    @Test fun `explicit captions off survives applying defaults after fullscreen reentry`() = withController { controller ->
        controller.disableText()
        controller.applySubtitleDefault(true)
        assertThat(controller.player.trackSelectionParameters.disabledTrackTypes).contains(C.TRACK_TYPE_TEXT)
    }

    /** Checks: user pause survives lifecycle suppression without any media requests. */
    @Test fun `user pause survives lifecycle suppression without any media requests`() = withController { controller ->
        controller.setUserPaused(true)
        controller.setSuspended(true)
        controller.setSuspended(false)
        assertThat(controller.userPaused.value).isTrue()
        assertThat(controller.player.playWhenReady).isFalse()
        assertThat(controller.player.mediaItemCount).isEqualTo(0)
    }

    /** Checks: request made while suspended does not prepare media or open stream. */
    @Test fun `request made while suspended does not prepare media or open stream`() = withController { controller ->
        controller.setSuspended(true)
        controller.play(PlayerController.Request("https://example.invalid/never-request", "Test", "Nuvix test"), debounce = false)
        assertThat(controller.player.mediaItemCount).isEqualTo(0)
        assertThat(controller.player.playWhenReady).isFalse()
        controller.release()
        controller.release() // Guide teardown and navigation can both relinquish the old controller.
    }
}
