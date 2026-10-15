/* Nuvix modifications Copyright (C) 2026 Nuvix Contributors. GPL-3.0-or-later. */
package app.opentv

import android.app.Application
import android.content.Context
import app.opentv.core.AppSettings
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28])
class LivePlaybackSettingsTest {
    private lateinit var context: Application

    @Before
    fun reset() {
        context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("opentv_settings", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("nuvix_private_settings", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test
    fun `new installs retain live playback and allow preview and in-app audio`() {
        val settings = freshSettings()

        assertThat(settings.persistentLivePlayback.value).isTrue()
        assertThat(settings.livePreview.value).isTrue()
        assertThat(settings.continueAudioWhileBrowsing.value).isTrue()
        assertThat(settings.resumeLastChannel.value).isFalse()
    }

    @Test
    fun `local playback choices survive reconstructing settings and can be enabled again`() {
        val original = freshSettings()
        original.setPersistentLivePlayback(false)
        original.setLivePreview(false)
        original.setContinueAudioWhileBrowsing(false)

        assertThat(original.persistentLivePlayback.value).isFalse()
        assertThat(original.livePreview.value).isFalse()
        assertThat(original.continueAudioWhileBrowsing.value).isFalse()
        val restored = freshSettings()
        assertThat(restored.persistentLivePlayback.value).isFalse()
        assertThat(restored.livePreview.value).isFalse()
        assertThat(restored.continueAudioWhileBrowsing.value).isFalse()

        restored.setPersistentLivePlayback(true)
        restored.setLivePreview(true)
        restored.setContinueAudioWhileBrowsing(true)
        val enabled = freshSettings()
        assertThat(enabled.persistentLivePlayback.value).isTrue()
        assertThat(enabled.livePreview.value).isTrue()
        assertThat(enabled.continueAudioWhileBrowsing.value).isTrue()
    }

    @Test
    fun `persistent preview preferences leave independent guide and startup choices intact`() {
        val settings = freshSettings()
        settings.setGuidePreviewVideo(false)
        settings.setGuidePreviewSound(true)
        settings.setResumeLastChannel(true)
        settings.lastChannelId = 42L
        settings.setPersistentLivePlayback(false)
        settings.setLivePreview(false)
        settings.setContinueAudioWhileBrowsing(false)

        val restored = freshSettings()
        assertThat(restored.guidePreviewVideo.value).isFalse()
        assertThat(restored.guidePreviewSound.value).isTrue()
        assertThat(restored.resumeLastChannel.value).isTrue()
        assertThat(restored.lastChannelId).isEqualTo(42L)
    }

    // Reconstruct the store without modifying the production singleton or its ownership model.
    private fun freshSettings(): AppSettings = AppSettings::class.java
        .getDeclaredConstructor(Context::class.java)
        .apply { isAccessible = true }
        .newInstance(context)
}
