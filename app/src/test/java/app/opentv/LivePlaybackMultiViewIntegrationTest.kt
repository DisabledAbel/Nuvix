/* Nuvix modifications Copyright (C) 2026 Nuvix Contributors. GPL-3.0-or-later. */
package app.opentv

import android.app.Application
import android.content.Context
import androidx.activity.ComponentActivity
import androidx.annotation.OptIn
import androidx.lifecycle.ViewModelProvider
import androidx.media3.common.util.UnstableApi
import app.opentv.multiview.MultiViewChannelReference
import app.opentv.multiview.MultiViewLayout
import app.opentv.multiview.MultiViewPresetStore
import app.opentv.multiview.MultiViewPresentation
import app.opentv.multiview.MultiViewSavedPanel
import app.opentv.player.LivePlaybackSession
import app.opentv.player.PlayerController
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Integration ownership tests keep repository work queued; no real stream or credentials exist. */
@OptIn(UnstableApi::class)
@kotlin.OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class LivePlaybackMultiViewIntegrationTest {
    class OwnerActivity : ComponentActivity()
    private val context: Application get() = RuntimeEnvironment.getApplication()

    @Before fun setup() {
        Dispatchers.setMain(StandardTestDispatcher())
        context.getSharedPreferences(MultiViewPresetStore.STORAGE_NAME, Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("opentv_settings", Context.MODE_PRIVATE).edit().clear().commit()
    }
    @After fun cleanup() { Dispatchers.resetMain() }

    @Test fun `adding first MultiView panel closes optional preview before repository work begins`() {
        val activity = Robolectric.buildActivity(OwnerActivity::class.java).setup()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val preview = PlayerController(context, scope, OkHttpClient(), preview = true)
        try {
            val session = ViewModelProvider(activity.get())[LivePlaybackSession::class.java]
            var released = false
            session.registerGuidePreview(preview) { released = true }
            session.multiView.add(123)
            assertThat(released).isTrue()
            assertThat(preview.isReleased).isTrue()
            assertThat(session.controllerOrNull.value).isNull()
            assertThat(session.active.value).isFalse()
            assertThat(session.multiView.state.value.panels.map { it.channelId }).containsExactly(123L)
            assertThat(session.multiView.player(session.multiView.state.value.panels.single().id)).isNull()
        } finally {
            activity.pause().stop().destroy(); preview.release(); scope.cancel()
        }
    }

    @Test fun `MultiView and live or VOD synchronously relinquish previous ownership`() {
        val activity = Robolectric.buildActivity(OwnerActivity::class.java).setup()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val vod = PlayerController(context, scope, OkHttpClient())
        val laterVod = PlayerController(context, scope, OkHttpClient())
        try {
            val session = ViewModelProvider(activity.get())[LivePlaybackSession::class.java]
            session.registerNonLivePlayback(vod)
            session.multiView.add(1)
            assertThat(vod.isReleased).isTrue()
            session.openChannel(2)
            assertThat(session.multiView.state.value.panels).isEmpty()
            assertThat(session.active.value).isTrue()
            assertThat(session.controllerOrNull.value).isNull()
            session.multiView.add(3)
            assertThat(session.active.value).isFalse()
            assertThat(session.multiView.state.value.panels.map { it.channelId }).containsExactly(3L)
            session.registerNonLivePlayback(laterVod)
            assertThat(session.multiView.state.value.panels).isEmpty()
            assertThat(session.active.value).isFalse()
            assertThat(laterVod.isReleased).isFalse()
        } finally {
            activity.pause().stop().destroy(); vod.release(); laterVod.release(); scope.cancel()
        }
    }

    @Test fun `configuration recreation retains manager and stable panel identity without additional startup`() {
        val activity = Robolectric.buildActivity(OwnerActivity::class.java).setup()
        try {
            val original = ViewModelProvider(activity.get())[LivePlaybackSession::class.java]
            original.multiView.add(123)
            val manager = original.multiView
            val ids = manager.state.value.panels.map { it.id }
            original.startupNavigationHandled = true
            activity.recreate()
            val recreated = ViewModelProvider(activity.get())[LivePlaybackSession::class.java]
            assertThat(recreated).isSameInstanceAs(original)
            assertThat(recreated.multiView).isSameInstanceAs(manager)
            assertThat(recreated.multiView.state.value.panels.map { it.id }).containsExactlyElementsIn(ids).inOrder()
            assertThat(recreated.startupNavigationHandled).isTrue()
            assertThat(recreated.controllerOrNull.value).isNull()
            activity.pause().stop().destroy()
            assertThat(manager.state.value.panels).isEmpty()
        } finally {
            if (!activity.get().isDestroyed) activity.pause().stop().destroy()
        }
    }

    @Test fun `opening a fresh owner with interrupted presentation never starts streams before confirmation`() {
        val snapshot = MultiViewPresentation(listOf(
            MultiViewSavedPanel("first", MultiViewChannelReference(1, 1)),
            MultiViewSavedPanel("second", MultiViewChannelReference(2, 2)),
        ), "first", "second", MultiViewLayout.TWO)
        val store = MultiViewPresetStore(context)
        val activity = Robolectric.buildActivity(OwnerActivity::class.java).setup()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val preview = PlayerController(context, scope, OkHttpClient(), preview = true)
        try {
            store.saveInterrupted(snapshot)
            val session = ViewModelProvider(activity.get())[LivePlaybackSession::class.java]
            assertThat(session.multiView.state.value.panels).isEmpty()
            assertThat(session.controllerOrNull.value).isNull()
            assertThat(session.active.value).isFalse()
            assertThat(session.guidePreviewSuppressed.value).isTrue()
            session.registerGuidePreview(preview)
            assertThat(preview.isReleased).isTrue()
            session.setRecoveryReviewPending(false)
            assertThat(session.guidePreviewSuppressed.value).isFalse()
            assertThat(store.peekRecovery()).isEqualTo(snapshot)
            activity.recreate()
            val recreated = ViewModelProvider(activity.get())[LivePlaybackSession::class.java]
            assertThat(recreated.multiView.state.value.panels).isEmpty()
            assertThat(recreated.controllerOrNull.value).isNull()
            assertThat(store.peekRecovery()).isEqualTo(snapshot)
        } finally {
            activity.pause().stop().destroy(); store.close(); preview.release(); scope.cancel()
        }
    }
}
