/* Nuvix modifications Copyright (C) 2026 Nuvix Contributors. GPL-3.0-or-later. */
package app.opentv.core

import android.app.Application
import android.content.Context
import app.opentv.multiview.MultiViewAudioMode
import app.opentv.multiview.MultiViewPreferences
import app.opentv.multiview.MultiViewRecoveryPolicy
import app.opentv.multiview.MultiViewRestorePolicy
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28])
class AppSettingsMultiViewPreferencesTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private val prefs get() = context.getSharedPreferences("opentv_settings", Context.MODE_PRIVATE)
    @Before fun setup() { prefs.edit().clear().commit() }
    private fun reopen(): AppSettings = AppSettings::class.java.getDeclaredConstructor(Context::class.java)
        .apply { isAccessible = true }.newInstance(context)

    @Test fun `defaults enable MultiView with 150ms follow focus and confirmed restoration`() {
        assertThat(reopen().multiViewPreferences.value).isEqualTo(MultiViewPreferences())
        val value = reopen().multiViewPreferences.value
        assertThat(value.enabled).isTrue()
        assertThat(value.audioMode).isEqualTo(MultiViewAudioMode.FOLLOW_FOCUS)
        assertThat(value.focusDelayMs).isEqualTo(150)
        assertThat(value.fullscreenRestoration).isEqualTo(MultiViewRestorePolicy.ASK_FIRST)
        assertThat(value.recovery).isEqualTo(MultiViewRecoveryPolicy.ASK_BEFORE_RESTORE)
    }

    @Test fun `all settings persist and update live state independently of legacy playback`() {
        prefs.edit().putBoolean("persistent_live_playback", false).putLong("last_channel_id", 42).commit()
        val settings = reopen()
        val chosen = MultiViewPreferences(enabled = false, audioMode = MultiViewAudioMode.PRESS_OK,
            focusDelayMs = 300, fullscreenRestoration = MultiViewRestorePolicy.KEEP_FULLSCREEN,
            bufferingSuggestions = false, compactGuide = false, showLabels = false,
            savePresentationPreferences = false, recovery = MultiViewRecoveryPolicy.DISABLED)
        settings.setMultiViewPreferences(chosen)
        assertThat(settings.multiViewPreferences.value).isEqualTo(chosen)
        assertThat(reopen().multiViewPreferences.value).isEqualTo(chosen)
        assertThat(reopen().persistentLivePlayback.value).isFalse()
        assertThat(prefs.getLong("last_channel_id", -1)).isEqualTo(42)
        assertThat(prefs.getInt("multiview_preferences_version", -1)).isEqualTo(1)
    }

    @Test fun `all fullscreen and audio policies survive process recreation`() {
        val settings = reopen()
        MultiViewRestorePolicy.entries.forEach { policy ->
            settings.setMultiViewPreferences(settings.multiViewPreferences.value.copy(fullscreenRestoration = policy))
            assertThat(reopen().multiViewPreferences.value.fullscreenRestoration).isEqualTo(policy)
        }
        MultiViewAudioMode.entries.forEach { mode ->
            settings.setMultiViewPreferences(settings.multiViewPreferences.value.copy(audioMode = mode))
            assertThat(reopen().multiViewPreferences.value.audioMode).isEqualTo(mode)
        }
    }

    @Test fun `malformed values safely default and never enable unrecognized automatic recovery`() {
        prefs.edit().putString("multiview_enabled", "false").putBoolean("multiview_audio_mode", true)
            .putLong("multiview_focus_delay_ms", 10).putString("multiview_fullscreen_restoration", "AUTOMATIC_UNKNOWN")
            .putInt("multiview_compact_guide", 0).putString("multiview_recovery", "AUTO_RESTORE").commit()
        assertThat(reopen().multiViewPreferences.value).isEqualTo(MultiViewPreferences())
        prefs.edit().putInt("multiview_focus_delay_ms", -150).commit()
        assertThat(reopen().multiViewPreferences.value.focusDelayMs).isEqualTo(150)
    }

    @Test fun `unknown preference schema defaults safely without rewriting stored values`() {
        prefs.edit().putInt("multiview_preferences_version", 99).putBoolean("multiview_enabled", false).commit()
        assertThat(reopen().multiViewPreferences.value).isEqualTo(MultiViewPreferences())
        assertThat(prefs.getInt("multiview_preferences_version", 0)).isEqualTo(99)
        assertThat(prefs.getBoolean("multiview_enabled", true)).isFalse()
    }

    @Test fun `focus delay has bounded valid configuration`() {
        val settings = reopen()
        settings.setMultiViewPreferences(MultiViewPreferences(focusDelayMs = -1))
        assertThat(settings.multiViewPreferences.value.focusDelayMs).isEqualTo(0)
        settings.setMultiViewPreferences(MultiViewPreferences(focusDelayMs = Int.MAX_VALUE))
        assertThat(reopen().multiViewPreferences.value.focusDelayMs).isEqualTo(2_000)
    }
}
