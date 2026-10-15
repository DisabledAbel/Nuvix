/* Copyright (C) 2026 Nuvix Contributors. GPL-3.0-or-later. */
package app.opentv.player

import android.app.Application
import android.content.Context
import app.opentv.core.AppSettings
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class MiniGuideSettingsTest {
    private fun fresh(): AppSettings = AppSettings::class.java.getDeclaredConstructor(Context::class.java).run {
        isAccessible = true
        newInstance(RuntimeEnvironment.getApplication())
    }

    @Test fun `defaults and changed preferences survive a fresh settings instance`() {
        val context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("opentv_settings", Context.MODE_PRIVATE).edit().clear().commit()
        val settings = fresh()
        assertThat(settings.miniGuideEnabled.value).isTrue()
        assertThat(settings.miniGuideTransparency.value).isEqualTo(AppSettings.GuideTransparency.MEDIUM)
        assertThat(settings.miniGuidePosition.value).isEqualTo(AppSettings.GuidePosition.LEFT)
        assertThat(settings.miniGuideAutoClose.value).isEqualTo(10)
        settings.setMiniGuideEnabled(false)
        settings.setMiniGuideTransparency(AppSettings.GuideTransparency.HIGH)
        settings.setMiniGuidePosition(AppSettings.GuidePosition.RIGHT)
        settings.setMiniGuideAutoClose(0)
        val reloaded = fresh()
        assertThat(reloaded.miniGuideEnabled.value).isFalse()
        assertThat(reloaded.miniGuideTransparency.value).isEqualTo(AppSettings.GuideTransparency.HIGH)
        assertThat(reloaded.miniGuidePosition.value).isEqualTo(AppSettings.GuidePosition.RIGHT)
        assertThat(reloaded.miniGuideAutoClose.value).isEqualTo(0)
    }

    @Test fun `unknown persisted options recover to supported values`() {
        RuntimeEnvironment.getApplication().getSharedPreferences("opentv_settings", Context.MODE_PRIVATE).edit()
            .putString("mini_guide_position", "UNKNOWN").putString("mini_guide_transparency", "UNKNOWN")
            .putInt("mini_guide_auto_close", 999).commit()
        val settings = fresh()
        assertThat(settings.miniGuidePosition.value).isEqualTo(AppSettings.GuidePosition.LEFT)
        assertThat(settings.miniGuideTransparency.value).isEqualTo(AppSettings.GuideTransparency.MEDIUM)
        assertThat(settings.miniGuideAutoClose.value).isEqualTo(10)
        settings.setMiniGuideAutoClose(-5)
        assertThat(settings.miniGuideAutoClose.value).isEqualTo(10)
    }
}
