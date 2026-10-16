/* Nuvix modifications Copyright (C) 2026 Nuvix Contributors. GPL-3.0-or-later. */
package app.opentv.cloud

import android.app.Application
import android.content.Context
import app.opentv.core.AppSettings
import app.opentv.launcher.LauncherSettingsStore
import app.opentv.launcher.StartupDestination
import app.opentv.ui.home.HomePreferencesStore
import app.opentv.ui.home.HomeSection
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28])
class AdvancedPreferenceDataTest {
    private val device = "00000000-0000-0000-0000-000000000001"
    private lateinit var data: AdvancedPreferenceData
    private val context: Context get() = RuntimeEnvironment.getApplication()

    @Before fun setup() {
        listOf("nuvix_launcher", HomePreferencesStore.PREFERENCES, "opentv_settings").forEach {
            context.getSharedPreferences(it, Context.MODE_PRIVATE).edit().clear().commit()
        }
        data = AdvancedPreferenceData(context)
    }

    @After fun close() { data.close() }

    private fun setting(key: String, text: String? = null, flag: Boolean? = null) = AdvancedCloudRecord(
        key, "setting", updatedAtMillis = 1_000, deviceId = device, textValue = text, boolValue = flag,
    )

    @Test fun `snapshot exports only portable implemented keys and ignores secrets and hardware tuning`() {
        context.getSharedPreferences("opentv_settings", Context.MODE_PRIVATE).edit()
            .putString("provider_url", "https://provider/password")
            .putString("parental_pin_hash", "secret")
            .putString("recording_target", "/device/storage")
            .putInt("player_buffer", 123)
            .putString("theme_mode", "LIGHT")
            .putBoolean("subtitles_enabled", false)
            .commit()
        val records = data.snapshot(device, 1_000)

        assertThat(records).hasSize(14)
        assertThat(records.filter { it.kind == "setting" }.map { it.recordKey })
            .containsExactly("theme_mode", "channel_layout", "subtitles_enabled", "startup_destination")
        assertThat(records.first { it.recordKey == "theme_mode" }.textValue).isEqualTo("LIGHT")
        assertThat(records.first { it.recordKey == "subtitles_enabled" }.boolValue).isFalse()
        assertThat(records.all { it.isSafe(1_000) }).isTrue()
    }

    @Test fun `remote portable settings update actual application state and navigation preference`() {
        assertThat(data.apply(setting("theme_mode", text = "DARK"))).isTrue()
        assertThat(data.apply(setting("channel_layout", text = "LIST"))).isTrue()
        assertThat(data.apply(setting("subtitles_enabled", flag = false))).isTrue()
        assertThat(data.apply(setting("startup_destination", text = "GUIDE"))).isTrue()

        val settings = AppSettings.get(context)
        assertThat(settings.themeMode.value).isEqualTo(AppSettings.ThemeMode.DARK)
        assertThat(settings.channelLayout.value).isEqualTo(AppSettings.ChannelLayout.LIST)
        assertThat(settings.subtitlesEnabled.value).isFalse()
        val launcher = LauncherSettingsStore(context)
        assertThat(launcher.currentPreferences.startupDestination).isEqualTo(StartupDestination.GUIDE)
        launcher.close()
    }

    @Test fun `remote app order is complete while the installed display subset is local`() {
        val record = AdvancedCloudRecord("apps", "app_order", updatedAtMillis = 1_000, deviceId = device,
            entries = listOf("tv.notinstalled", "tv.beta", "tv.alpha"))
        assertThat(data.apply(record)).isTrue()
        val launcher = LauncherSettingsStore(context)
        assertThat(launcher.pruneFavorites(setOf("tv.alpha", "tv.beta")))
            .containsExactly("tv.beta", "tv.alpha").inOrder()
        assertThat(data.snapshot(device, 2_000).first { it.kind == "app_order" }.entries)
            .containsExactly("tv.notinstalled", "tv.beta", "tv.alpha").inOrder()
        assertThat(data.apply(record.copy(entries = listOf("tv.notinstalled", "tv.alpha")))).isTrue()
        assertThat(launcher.currentPreferences.favoritePackages).containsExactly("tv.notinstalled", "tv.alpha").inOrder()
        launcher.close()
    }

    @Test fun `remote home shelf visibility has a persisted user-facing value`() {
        val record = AdvancedCloudRecord("recent", "home_shelf", updatedAtMillis = 1_000, deviceId = device, boolValue = false)
        assertThat(data.apply(record)).isTrue()
        val home = HomePreferencesStore(context)
        assertThat(home.currentPreferences.isVisible(HomeSection.RECENT)).isFalse()
        assertThat(home.currentPreferences.isVisible(HomeSection.MOVIES)).isTrue()
        assertThat(data.snapshot(device, 2_000).first { it.kind == "home_shelf" && it.recordKey == "recent" }.boolValue).isFalse()
        home.close()
    }

    @Test fun `unknown malformed credential and hardware keys cannot be applied`() {
        listOf(
            setting("provider_url", text = "https://provider/password"),
            setting("parental_pin_hash", text = "secret"),
            setting("player_buffer", text = "10000"),
            setting("theme_mode", text = "AUTOMATIC"),
            setting("subtitles_enabled", text = "false"),
            setting("startup_destination", text = "ANDROID_HOME_ROLE"),
            setting("theme_mode", text = "LIGHT").copy(schemaVersion = 99),
            AdvancedCloudRecord("unknown", "home_shelf", updatedAtMillis = 1_000, deviceId = device, boolValue = false),
        ).forEach { assertThat(data.apply(it)).isFalse() }
        val snapshot = data.snapshot(device, 2_000)
        assertThat(snapshot.first { it.recordKey == "theme_mode" }.textValue).isEqualTo("SYSTEM")
        assertThat(context.getSharedPreferences("opentv_settings", Context.MODE_PRIVATE).contains("provider_url")).isFalse()
    }
}
