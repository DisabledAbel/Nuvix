/* Copyright (C) 2026 Nuvix Contributors. Licensed under GPL-3.0-or-later. */
package app.opentv

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.annotation.OptIn
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.room.Room
import app.opentv.core.ServiceLocator
import app.opentv.data.db.OpenTvDatabase
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.PlayerView
import app.opentv.player.LivePlaybackSession
import app.opentv.player.PlayerController
import app.opentv.player.LivePresentation
import app.opentv.player.LivePlaybackCoordinator
import app.opentv.sports.SportsAutoSwitchover
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Job
import kotlinx.coroutines.test.runTest
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

/** Regression tests for live playback session lifecycle. */
@OptIn(UnstableApi::class)
@kotlin.OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class LivePlaybackSessionLifecycleTest {
    /** Minimal activity host for retained live-session lifecycle assertions. */
    class PlaybackOwnerActivity : ComponentActivity()

    @Test fun `late outgoing fullscreen and preview updates cannot reclaim the current surface`() {
        Dispatchers.setMain(StandardTestDispatcher())
        val activity = Robolectric.buildActivity(PlaybackOwnerActivity::class.java).setup()
        try {
            val session = ViewModelProvider(activity.get())[LivePlaybackSession::class.java]
            val player = session.controller.player
            val fullscreen = PlayerView(activity.get())
            val preview = PlayerView(activity.get())
            session.setPresentation(LivePresentation.BROWSING)
            assertThat(session.attachVideoSurface(preview, LivePresentation.BROWSING)).isTrue()
            assertThat(session.attachVideoSurface(fullscreen, LivePresentation.FULLSCREEN)).isFalse()
            assertThat(preview.player).isSameInstanceAs(player)
            session.setPresentation(LivePresentation.FULLSCREEN)
            assertThat(session.attachVideoSurface(fullscreen, LivePresentation.FULLSCREEN)).isTrue()
            assertThat(session.attachVideoSurface(preview, LivePresentation.BROWSING)).isFalse()
            assertThat(fullscreen.player).isSameInstanceAs(player)
            assertThat(preview.player).isNull()
        } finally {
            activity.pause().stop().destroy()
            Dispatchers.resetMain()
        }
    }

    @Test fun `initial inactivity preserves sports selection but later inactivity clears it`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        val activity = Robolectric.buildActivity(PlaybackOwnerActivity::class.java).setup()
        val graph = ServiceLocator.Graph(activity.get().application)
        val graphField = ServiceLocator::class.java.getDeclaredField("instance").apply { isAccessible = true }
        val originalGraph = graphField.get(null)
        graphField.set(null, graph)
        val databaseField = ServiceLocator.Graph::class.java.getDeclaredField("database\$delegate").apply { isAccessible = true }
        val database = Room.inMemoryDatabaseBuilder(activity.get().application, OpenTvDatabase::class.java).build()
        databaseField.set(graph, lazyOf(database))
        val session = ViewModelProvider(activity.get())[LivePlaybackSession::class.java]
        try {
            val recovery = LivePlaybackSession::class.java.getDeclaredField("sportsSwitch")
                .apply { isAccessible = true }.get(session) as SportsAutoSwitchover
            val coordinator = LivePlaybackSession::class.java.getDeclaredField("coordinator")
                .apply { isAccessible = true }.get(session) as LivePlaybackCoordinator
            @Suppress("UNCHECKED_CAST")
            val active = LivePlaybackCoordinator::class.java.getDeclaredField("_active")
                .apply { isAccessible = true }.get(coordinator) as MutableStateFlow<Boolean>

            // Reproduce the interval between selecting a game and activating its tune,
            // without resolving a provider or opening a stream.
            recovery.start("game", 123L)
            dispatcher.scheduler.runCurrent()
            assertThat(recovery.selection.value?.eventId).isEqualTo("game")
            active.value = true
            dispatcher.scheduler.runCurrent()
            assertThat(recovery.selection.value?.channelId).isEqualTo(123L)
            active.value = false
            dispatcher.scheduler.runCurrent()
            assertThat(recovery.selection.value).isNull()
        } finally {
            activity.pause().stop().destroy()
            dispatcher.scheduler.runCurrent()
            session.viewModelScope.coroutineContext[Job]?.join()
            database.close()
            graphField.set(null, originalGraph)
            Dispatchers.resetMain()
        }
    }

    /** Checks: automatic live request synchronously releases registered guide preview before resolving. */
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

    /** Checks: non-live playback survives optional memory cleanup then yields before a live request. */
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

    /** Checks: Activity recreation retains owner and player while final destruction clears all leases. */
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
