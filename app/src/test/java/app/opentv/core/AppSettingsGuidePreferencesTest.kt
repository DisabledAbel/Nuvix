/* Nuvix modifications Copyright (C) 2026 Nuvix Contributors. GPL-3.0-or-later. */
package app.opentv.core

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28])
class AppSettingsGuidePreferencesTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private lateinit var prefs: SharedPreferences

    @Before fun setup() {
        prefs = context.getSharedPreferences("opentv_settings", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
    }

    // A fresh instance exercises the on-disk read path used after process recreation.
    private fun reopen(): AppSettings = AppSettings::class.java.getDeclaredConstructor(Context::class.java)
        .apply { isAccessible = true }.newInstance(context)

    @Test fun `new guide defaults preserve legacy channel list choice and previews`() {
        prefs.edit().putString("channel_layout", "LIST").putBoolean("guide_preview_video", false)
            .putBoolean("guide_preview_sound", true).commit()
        val settings = reopen()
        assertThat(settings.channelLayout.value).isEqualTo(AppSettings.ChannelLayout.LIST)
        assertThat(settings.guidePreviewVideo.value).isFalse()
        assertThat(settings.guidePreviewSound.value).isTrue()
        assertThat(settings.guideLayout.value).isEqualTo(GuideLayout.STANDARD)
        assertThat(settings.guideDensity.value).isEqualTo(GuideTimelineDensity.COMFORTABLE)
        assertThat(settings.guideDefaultFilter.value).isEqualTo(GuideChannelFilter.ALL)
        assertThat(settings.guideSort.value).isEqualTo(GuideChannelSort.PROVIDER_ORDER)
        assertThat(settings.showGuideArtwork.value).isTrue()
        assertThat(settings.showGuideProgress.value).isTrue()
        assertThat(settings.rememberGuidePosition.value).isTrue()
        assertThat(settings.lastGuidePosition.value).isNull()
        settings.setGuideLayout(GuideLayout.COMPACT)
        assertThat(reopen().channelLayout.value).isEqualTo(AppSettings.ChannelLayout.LIST)
        assertThat(prefs.getString("channel_layout", null)).isEqualTo("LIST")
    }

    @Test fun `every layout density filter and sort persists and updates live state`() {
        val settings = reopen()
        GuideLayout.entries.forEach {
            settings.setGuideLayout(it)
            assertThat(settings.guideLayout.value).isEqualTo(it)
            assertThat(reopen().guideLayout.value).isEqualTo(it)
        }
        GuideTimelineDensity.entries.forEach {
            settings.setGuideDensity(it)
            assertThat(settings.guideDensity.value).isEqualTo(it)
            assertThat(reopen().guideDensity.value).isEqualTo(it)
        }
        GuideChannelFilter.entries.forEach {
            settings.setGuideDefaultFilter(it)
            assertThat(settings.guideDefaultFilter.value).isEqualTo(it)
            assertThat(reopen().guideDefaultFilter.value).isEqualTo(it)
        }
        GuideChannelSort.entries.forEach {
            settings.setGuideSort(it)
            assertThat(settings.guideSort.value).isEqualTo(it)
            assertThat(reopen().guideSort.value).isEqualTo(it)
        }
    }

    @Test fun `artwork progress and remember preferences persist independently`() {
        val settings = reopen()
        settings.setShowGuideArtwork(false)
        settings.setShowGuideProgress(false)
        settings.setRememberGuidePosition(false)
        val restored = reopen()
        assertThat(restored.showGuideArtwork.value).isFalse()
        assertThat(restored.showGuideProgress.value).isFalse()
        assertThat(restored.rememberGuidePosition.value).isFalse()
        restored.setShowGuideProgress(true)
        assertThat(reopen().showGuideArtwork.value).isFalse()
        assertThat(reopen().showGuideProgress.value).isTrue()
    }

    @Test fun `local filter targets survive switching and portable default changes`() {
        val settings = reopen()
        settings.setGuideFilter(GuideChannelFilter.GROUP, groupId = 12)
        settings.setGuideFilter(GuideChannelFilter.PROVIDER, providerId = 45)
        settings.setGuideDefaultFilter(GuideChannelFilter.ALL)
        val restored = reopen()
        assertThat(restored.guideDefaultFilter.value).isEqualTo(GuideChannelFilter.ALL)
        assertThat(restored.guideFilterGroupId.value).isEqualTo(12L)
        assertThat(restored.guideFilterProviderId.value).isEqualTo(45L)
        restored.setGuideFilter(GuideChannelFilter.GROUP, groupId = -1, providerId = 0)
        assertThat(reopen().guideFilterGroupId.value).isEqualTo(12L)
        assertThat(reopen().guideFilterProviderId.value).isEqualTo(45L)
    }

    @Test fun `guide navigation does not retune or overwrite last watched channel`() {
        prefs.edit().putLong("last_channel_id", 99).commit()
        val settings = reopen()
        settings.setGuidePosition(42, 1_800_000_000_000)
        assertThat(settings.lastGuidePosition.value).isEqualTo(GuidePosition(42, 1_800_000_000_000))
        assertThat(reopen().lastGuidePosition.value).isEqualTo(settings.lastGuidePosition.value)
        assertThat(prefs.getLong("last_channel_id", -1)).isEqualTo(99L)
        settings.setGuidePosition(-1, 0)
        settings.setGuidePosition(43, -1)
        assertThat(settings.lastGuidePosition.value?.channelId).isEqualTo(42L)
    }

    @Test fun `disabling remembered position clears old state and prevents further storage`() {
        val settings = reopen()
        settings.setGuidePosition(42, 1_800_000_000_000)
        settings.setRememberGuidePosition(false)
        settings.setGuidePosition(43, 1_800_001_000_000)
        assertThat(settings.lastGuidePosition.value).isNull()
        assertThat(reopen().lastGuidePosition.value).isNull()
        assertThat(prefs.contains("guide_position_channel_id")).isFalse()
        assertThat(prefs.contains("guide_position_time_ms")).isFalse()
        settings.setRememberGuidePosition(true)
        settings.setGuidePosition(43, 1_800_001_000_000)
        assertThat(reopen().lastGuidePosition.value?.channelId).isEqualTo(43L)
    }

    @Test fun `unknown enums invalid types and incomplete position recover to safe defaults`() {
        prefs.edit().putString("guide_layout", "FUTURE_LAYOUT").putInt("guide_density", 99)
            .putString("guide_default_filter", "NO_LONGER_SUPPORTED").putBoolean("guide_sort", true)
            .putString("guide_show_artwork", "false").putInt("guide_show_progress", 0)
            .putString("guide_remember_position", "true").putLong("guide_filter_group_id", -1)
            .putString("guide_filter_provider_id", "45").putLong("guide_position_channel_id", 42).commit()
        val settings = reopen()
        assertThat(settings.guideLayout.value).isEqualTo(GuideLayout.STANDARD)
        assertThat(settings.guideDensity.value).isEqualTo(GuideTimelineDensity.COMFORTABLE)
        assertThat(settings.guideDefaultFilter.value).isEqualTo(GuideChannelFilter.ALL)
        assertThat(settings.guideSort.value).isEqualTo(GuideChannelSort.PROVIDER_ORDER)
        assertThat(settings.showGuideArtwork.value).isTrue()
        assertThat(settings.showGuideProgress.value).isTrue()
        assertThat(settings.rememberGuidePosition.value).isTrue()
        assertThat(settings.guideFilterGroupId.value).isNull()
        assertThat(settings.guideFilterProviderId.value).isNull()
        assertThat(settings.lastGuidePosition.value).isNull()
        // Preserve unknown values for forward compatibility until the user explicitly changes one.
        assertThat(prefs.getString("guide_layout", null)).isEqualTo("FUTURE_LAYOUT")
    }
}
