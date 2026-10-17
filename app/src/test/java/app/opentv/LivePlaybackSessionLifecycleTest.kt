/* Copyright (C) 2026 Nuvix Contributors. Licensed under GPL-3.0-or-later. */
package app.opentv

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.annotation.OptIn
import androidx.lifecycle.ViewModelProvider
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.PlayerView
import app.opentv.player.LivePlaybackSession
import app.opentv.player.PlayerController
import app.opentv.player.LivePresentation
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import okhttp3.OkHttpClient
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(UnstableApi::class)
@kotlin.OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class LivePlaybackSessionLifecycleTest {
    class PlaybackOwnerActivity : ComponentActivity()

    @Test fun `automatic live request synchronously releases registered guide preview before resolving`() {
        Dispatchers.setMain(StandardTestDispatcher())
        val activity = Robolectric.buildActivity(PlaybackOwnerActivity::class.java).setup()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val preview = PlayerController(activity.get().applicationContext, scope, OkHttpClient(), preview = true)
        try {
            val session = ViewModelProvider(activity.get())[LivePlaybackSession::class.java]
            session.setPresentation(LivePresentation.BROWSING)
            var releases = 0
            session.registerGuidePreview(preview) {
                assertThat(preview.isReleased).isTrue()
                releases++
            }
            assertThat(preview.isReleased).isFalse()
            session.openChannel(123L) // Repository job stays queued; no live player or URL is needed.
            assertThat(preview.isReleased).isTrue()
            assertThat(session.controllerOrNull.value).isNull()
            assertThat(session.active.value).isTrue()
            session.unregisterGuidePreview(preview) // late Compose disposal remains harmless
            assertThat(releases).isEqualTo(1)
        } finally {
            activity.pause().stop().destroy()
            preview.release()
            scope.cancel()
            Dispatchers.resetMain()
        }
    }

    @Test fun `non-live playback survives optional memory cleanup then yields before a live request`() {
        Dispatchers.setMain(StandardTestDispatcher())
        val activity = Robolectric.buildActivity(PlaybackOwnerActivity::class.java).setup()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val recordingPlayer = PlayerController(activity.get().applicationContext, scope, OkHttpClient())
        try {
            val session = ViewModelProvider(activity.get())[LivePlaybackSession::class.java]
            session.registerNonLivePlayback(recordingPlayer)
            session.setPresentation(LivePresentation.HIDDEN)
            session.onMemoryPressure(android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL)
            assertThat(recordingPlayer.isReleased).isFalse()
            session.stop() // stopping live ownership does not stop dedicated recording playback
            assertThat(recordingPlayer.isReleased).isFalse()
            session.openChannel(123L)
            assertThat(recordingPlayer.isReleased).isTrue()
            assertThat(session.controllerOrNull.value).isNull()
            session.unregisterNonLivePlayback(recordingPlayer)
        } finally {
            activity.pause().stop().destroy()
            recordingPlayer.release()
            scope.cancel()
            Dispatchers.resetMain()
        }
    }

    @Test fun `Activity recreation retains owner and player while final destruction clears all leases`() {
        // Keep async repository/settings observers queued: this ownership test uses no database or stream.
        Dispatchers.setMain(StandardTestDispatcher())
        val activity = Robolectric.buildActivity(PlaybackOwnerActivity::class.java).setup()
        try {
            val first = ViewModelProvider(activity.get())[LivePlaybackSession::class.java]
            val controller = first.controller
            first.startupNavigationHandled = true
            val oldView = PlayerView(activity.get())
            assertThat(first.surfaceOwner.attach(oldView)).isTrue()
            first.surfaceOwner.detach(oldView)

            activity.recreate()
            val recreated = ViewModelProvider(activity.get())[LivePlaybackSession::class.java]
            assertThat(recreated).isSameInstanceAs(first)
            assertThat(recreated.controller).isSameInstanceAs(controller)
            assertThat(recreated.startupNavigationHandled).isTrue()
            assertThat(oldView.player).isNull()
            val newView = PlayerView(activity.get())
            assertThat(recreated.surfaceOwner.attach(newView)).isTrue()
            assertThat(newView.player).isSameInstanceAs(controller.player)

            activity.pause().stop().destroy()
            assertThat(newView.player).isNull()
            assertThat(recreated.controllerOrNull.value).isNull()
            assertThat(recreated.engineReady.value).isFalse()
            assertThat(recreated.active.value).isFalse()
        } finally {
            if (!activity.get().isDestroyed) activity.pause().stop().destroy()
            Dispatchers.resetMain()
        }
    }
}
