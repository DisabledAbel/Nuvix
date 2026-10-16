/* Nuvix modifications Copyright (C) 2026 Nuvix Contributors. GPL-3.0-or-later. */
package app.opentv.cloud

import android.app.Application
import android.content.Context
import app.opentv.core.AppSettings
import app.opentv.core.GuideChannelFilter
import app.opentv.core.GuideChannelSort
import app.opentv.core.GuideLayout
import app.opentv.core.GuideTimelineDensity
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
class GuideCloudPreferencesTest {
    private val settingsInstance = AppSettings::class.java.getDeclaredField("instance").apply { isAccessible = true }
    private val device = "00000000-0000-0000-0000-000000000001"
    private val owner = "11111111-1111-1111-1111-111111111111"
    private fun record(key: String, text: String? = null, flag: Boolean? = null) =
        AdvancedCloudRecord(key, "guide_setting", 1000, device, textValue = text, boolValue = flag)

    @Before fun resetSettingsForCurrentApplication() {
        // Robolectric can reuse app classes across Application instances. A previous singleton
        // otherwise writes to its old Context's preferences while this test reads the new one.
        settingsInstance.set(null, null)
        val context: Context = RuntimeEnvironment.getApplication()
        check(context.getSharedPreferences("opentv_settings", Context.MODE_PRIVATE).edit().clear().commit())
    }

    @After fun releaseSettingsApplication() {
        // Do not retain this test's Application or restore a stale singleton for the next test.
        settingsInstance.set(null, null)
    }

    @Test fun `portable enums and flags have exact typed shapes under existing optional category`() {
        val enums = mapOf(
            "guide_layout" to GuideLayout.entries.map { it.name },
            "guide_density" to GuideTimelineDensity.entries.map { it.name },
            "guide_default_filter" to GuideChannelFilter.entries.map { it.name },
            "guide_sort" to GuideChannelSort.entries.map { it.name },
        )
        enums.forEach { (key, values) ->
            values.forEach { value ->
                val valid = record(key, text = value)
                assertThat(valid.isSafe(1000)).isTrue()
                assertThat(valid.category).isEqualTo(AdvancedSyncCategory.COMPATIBLE_SETTINGS)
                assertThat(valid.copy(boolValue = true).isSafe(1000)).isFalse()
                assertThat(valid.copy(entries = listOf(value)).isSafe(1000)).isFalse()
                assertThat(valid.copy(kind = "setting").isSafe(1000)).isFalse()
            }
            assertThat(record(key, text = "https://provider?token=secret").isSafe(1000)).isFalse()
            assertThat(record(key, flag = true).isSafe(1000)).isFalse()
        }
        listOf("guide_show_artwork", "guide_show_progress", "guide_remember_position").forEach { key ->
            assertThat(record(key, flag = false).isSafe(1000)).isTrue()
            assertThat(record(key, flag = true).isSafe(1000)).isTrue()
            assertThat(record(key, text = "false").isSafe(1000)).isFalse()
            assertThat(record(key, flag = true).copy(intValue = 1).isSafe(1000)).isFalse()
        }
        listOf("guide_filter_group_id", "guide_filter_provider_id", "guide_position_channel_id",
            "guide_position_time_ms", "provider_url", "guide_artwork_url").forEach { key ->
            assertThat(record(key, text = "secret").isSafe(1000)).isFalse()
            assertThat(record(key, flag = true).isSafe(1000)).isFalse()
        }
        assertThat(record("guide_layout", text = "STANDARD").copy(schemaVersion = 2).isSafe(1000)).isFalse()
        assertThat(record("guide_layout", text = "STANDARD").copy(deleted = true).isSafe(1000)).isFalse()
        assertThat(AdvancedSyncCategory.DEFAULTS).doesNotContain(AdvancedSyncCategory.COMPATIBLE_SETTINGS)
    }

    @Test fun `snapshot exports display choices while retaining local selection position and secrets`() {
        val context = RuntimeEnvironment.getApplication()
        val storage = context.getSharedPreferences("opentv_settings", Context.MODE_PRIVATE)
        storage.edit().clear()
            .putString("guide_layout", "EXPANDED")
            .putString("guide_density", "WIDE")
            .putString("guide_default_filter", "PROVIDER")
            .putString("guide_sort", "CUSTOM")
            .putBoolean("guide_show_artwork", false)
            .putLong("guide_filter_group_id", 12)
            .putLong("guide_filter_provider_id", 34)
            .putLong("guide_position_channel_id", 56)
            .putLong("guide_position_time_ms", 78)
            .putString("provider_url", "https://provider?token=secret").commit()
        val data = AdvancedPreferenceData(context)
        try {
            val records = data.snapshot(device, 1000).filter { it.kind == "guide_setting" }
            assertThat(records.map { it.recordKey }).containsExactly("guide_layout", "guide_density",
                "guide_default_filter", "guide_sort", "guide_show_artwork", "guide_show_progress", "guide_remember_position")
            assertThat(records.first { it.recordKey == "guide_layout" }.textValue).isEqualTo("EXPANDED")
            assertThat(records.first { it.recordKey == "guide_density" }.textValue).isEqualTo("WIDE")
            assertThat(records.first { it.recordKey == "guide_show_artwork" }.boolValue).isFalse()
            assertThat(records.all { it.isSafe(1000) }).isTrue()
            assertThat(data.apply(record("guide_layout", text = "COMPACT"))).isTrue()
            assertThat(data.apply(record("guide_density", text = "DENSE"))).isTrue()
            assertThat(data.apply(record("guide_default_filter", text = "GROUP"))).isTrue()
            assertThat(data.apply(record("guide_sort", text = "ALPHABETICAL"))).isTrue()
            assertThat(data.apply(record("guide_show_artwork", flag = true))).isTrue()
            assertThat(data.apply(record("guide_show_progress", flag = false))).isTrue()
            assertThat(data.apply(record("guide_remember_position", flag = true))).isTrue()
            val settings = AppSettings.get(context)
            assertThat(settings.guideLayout.value).isEqualTo(GuideLayout.COMPACT)
            assertThat(settings.guideDensity.value).isEqualTo(GuideTimelineDensity.DENSE)
            assertThat(settings.guideDefaultFilter.value).isEqualTo(GuideChannelFilter.GROUP)
            assertThat(settings.guideSort.value).isEqualTo(GuideChannelSort.ALPHABETICAL)
            assertThat(settings.showGuideArtwork.value).isTrue()
            assertThat(settings.showGuideProgress.value).isFalse()
            assertThat(storage.getString("guide_layout", null)).isEqualTo("COMPACT")
            assertThat(storage.getLong("guide_filter_group_id", 0)).isEqualTo(12)
            assertThat(storage.getLong("guide_filter_provider_id", 0)).isEqualTo(34)
            assertThat(storage.getLong("guide_position_channel_id", 0)).isEqualTo(56)
            assertThat(storage.getLong("guide_position_time_ms", 0)).isEqualTo(78)
            assertThat(storage.getString("provider_url", null)).isEqualTo("https://provider?token=secret")
            assertThat(data.apply(record("guide_filter_provider_id", text = "1"))).isFalse()
        } finally { data.close() }
    }

    @Test fun `malformed local values use safe defaults and concurrent local choices win`() {
        val context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("opentv_settings", Context.MODE_PRIVATE).edit().clear()
            .putInt("guide_layout", 12).putString("guide_density", "UNKNOWN")
            .putString("guide_show_progress", "secret").commit()
        val data = AdvancedPreferenceData(context)
        try {
            val snapshot = data.snapshot(device, 1000).associateBy { it.recordKey }
            assertThat(snapshot.getValue("guide_layout").textValue).isEqualTo("STANDARD")
            assertThat(snapshot.getValue("guide_density").textValue).isEqualTo("COMFORTABLE")
            assertThat(snapshot.getValue("guide_show_progress").boolValue).isTrue()
            val before = snapshot.getValue("guide_layout")
            val local = before.copy(textValue = "EXPANDED")
            assertThat(data.applyIfUnchanged(record("guide_layout", text = "COMPACT"), before, local)).isFalse()
        } finally { data.close() }
    }

    @Test fun `disabled category excludes guide preferences and offline replay remains owner bound`() {
        var disk: String? = null
        var journal = AdvancedCloudJournal({ disk }, { disk = it })
        journal.bindOwner(owner)
        val layout = record("guide_layout", text = "COMPACT")
        val disabled = setOf(AdvancedSyncCategory.HOME_PREFERENCES)
        journal.capture(owner, listOf(layout), disabled, device, 1000)
        assertThat(journal.pending(owner, disabled, 1000)).isEmpty()
        assertThat(journal.mergeRemote(owner, listOf(layout), disabled, 1000)).isEmpty()
        val enabled = setOf(AdvancedSyncCategory.COMPATIBLE_SETTINGS)
        journal.capture(owner, listOf(layout), enabled, device, 1000)
        journal.capture(owner, listOf(layout.copy(textValue = "EXPANDED")), enabled, device, 2000)
        journal = AdvancedCloudJournal({ disk }, { disk = it })
        assertThat(journal.pending(owner, enabled, 2000).single().textValue).isEqualTo("EXPANDED")
        val otherOwner = "22222222-2222-2222-2222-222222222222"
        journal.bindOwner(otherOwner)
        assertThat(journal.pending(otherOwner, enabled, 2000)).isEmpty()
    }
}
