/* Nuvix modifications Copyright (C) 2026 Nuvix Contributors. GPL-3.0-or-later. */
package app.opentv.cloud

import android.content.Context
import app.opentv.core.AppSettings
import app.opentv.core.GuideChannelFilter
import app.opentv.core.GuideChannelSort
import app.opentv.core.GuideLayout
import app.opentv.core.GuideTimelineDensity
import app.opentv.launcher.LauncherSettingsStore
import app.opentv.launcher.StartupDestination
import app.opentv.ui.home.HomePreferencesStore
import app.opentv.ui.home.HomeSection
import app.opentv.ui.home.HomePresentation
import app.opentv.ui.home.HomeShelfOrder

/** Explicit adapter for portable, user-facing preferences; never exports a preferences dump. */
internal class AdvancedPreferenceData(context: Context) {
    private val appContext = context.applicationContext
    private val launcher = LauncherSettingsStore(appContext)
    private val home = HomePreferencesStore(appContext)
    private val settingsStorage = appContext.getSharedPreferences("opentv_settings", Context.MODE_PRIVATE)
    private val settings by lazy { AppSettings.get(appContext) }

    /**
     * The sync engine assigns revisions only to changed records. Read the typed storage values
     * directly because SharedPreferences notifies listeners before a setter updates its Flow.
     */
    fun snapshot(deviceId: String, now: Long): List<AdvancedCloudRecord> {
        val launcherPreferences = launcher.currentPreferences
        val homePreferences = home.currentPreferences
        val result = mutableListOf(
            AdvancedCloudRecord("apps", "app_order", updatedAtMillis = now, deviceId = deviceId,
                entries = launcherPreferences.favoritePackages),
            AdvancedCloudRecord("startup_destination", "setting", updatedAtMillis = now, deviceId = deviceId,
                textValue = launcherPreferences.startupDestination.name),
            AdvancedCloudRecord("theme_mode", "setting", updatedAtMillis = now, deviceId = deviceId,
                textValue = readEnum("theme_mode", AppSettings.ThemeMode.entries.map { it.name }, "SYSTEM")),
            AdvancedCloudRecord("channel_layout", "setting", updatedAtMillis = now, deviceId = deviceId,
                textValue = readEnum("channel_layout", AppSettings.ChannelLayout.entries.map { it.name }, "GRID")),
            AdvancedCloudRecord("subtitles_enabled", "setting", updatedAtMillis = now, deviceId = deviceId,
                boolValue = runCatching { settingsStorage.getBoolean("subtitles_enabled", true) }.getOrDefault(true)),
        )
        // Portable display choices only. Selected provider/group IDs and the remembered channel
        // and timeline remain local; never export a guide position or preferences dump.
        listOf(
            Triple("guide_layout", GuideLayout.entries.map { it.name }, "STANDARD"),
            Triple("guide_density", GuideTimelineDensity.entries.map { it.name }, "COMFORTABLE"),
            Triple("guide_default_filter", GuideChannelFilter.entries.map { it.name }, "ALL"),
            Triple("guide_sort", GuideChannelSort.entries.map { it.name }, "PROVIDER_ORDER"),
        ).forEach { (key, values, default) ->
            result += AdvancedCloudRecord(key, "guide_setting", updatedAtMillis = now, deviceId = deviceId,
                textValue = readEnum(key, values, default))
        }
        listOf("guide_show_artwork", "guide_show_progress", "guide_remember_position").forEach { key ->
            result += AdvancedCloudRecord(key, "guide_setting", updatedAtMillis = now, deviceId = deviceId,
                boolValue = runCatching { settingsStorage.getBoolean(key, true) }.getOrDefault(true))
        }
        HomeSection.entries.forEach { section ->
            result += AdvancedCloudRecord(section.key, "home_shelf", updatedAtMillis = now, deviceId = deviceId,
                boolValue = homePreferences.isVisible(section))
        }
        result += AdvancedCloudRecord("shelves_v1", "home_order", updatedAtMillis = now, deviceId = deviceId,
            entries = homePreferences.shelfOrder.filter(HomeShelfOrder::portable))
        result += AdvancedCloudRecord("presentation_v1", "home_layout", updatedAtMillis = now, deviceId = deviceId,
            entries = homePreferences.presentation.portable())
        home.groupVisibility.forEach { (key, visible) ->
            result += AdvancedCloudRecord(key, "home_group", updatedAtMillis = now, deviceId = deviceId, boolValue = visible)
        }
        return result.filter { it.isSafe(now) }
    }

    /** Applies validated portable values through existing setters, without touching HOME roles. */
    fun apply(record: AdvancedCloudRecord): Boolean {
        if (!record.isSafe(System.currentTimeMillis()) || record.deleted) return false
        val applied = when (record.kind) {
            "app_order" -> record.recordKey == "apps" && launcher.applyRemoteAppOrder(record.entries)
            "home_order" -> { home.applyRemoteOrder(record.entries); true }
            "home_layout" -> { home.setPresentation(HomePresentation.parse(record.entries) ?: return false); true }
            "home_group" -> { home.setVisible(record.recordKey, record.boolValue ?: return false); true }
            "home_shelf" -> record.boolValue?.let { home.applyRemoteVisibility(record.recordKey, it) } ?: false
            "guide_setting" -> when (record.recordKey) {
                "guide_layout" -> {
                    settings.setGuideLayout(GuideLayout.entries.firstOrNull { it.name == record.textValue } ?: return false)
                    true
                }
                "guide_density" -> {
                    settings.setGuideDensity(GuideTimelineDensity.entries.firstOrNull { it.name == record.textValue } ?: return false)
                    true
                }
                "guide_default_filter" -> {
                    settings.setGuideDefaultFilter(GuideChannelFilter.entries.firstOrNull { it.name == record.textValue } ?: return false)
                    true
                }
                "guide_sort" -> {
                    settings.setGuideSort(GuideChannelSort.entries.firstOrNull { it.name == record.textValue } ?: return false)
                    true
                }
                "guide_show_artwork" -> { settings.setShowGuideArtwork(record.boolValue ?: return false); true }
                "guide_show_progress" -> { settings.setShowGuideProgress(record.boolValue ?: return false); true }
                "guide_remember_position" -> { settings.setRememberGuidePosition(record.boolValue ?: return false); true }
                else -> false
            }
            "setting" -> when (record.recordKey) {
                "theme_mode" -> {
                    val mode = AppSettings.ThemeMode.entries.firstOrNull { it.name == record.textValue } ?: return false
                    settings.setThemeMode(mode)
                    true
                }
                "channel_layout" -> {
                    val layout = AppSettings.ChannelLayout.entries.firstOrNull { it.name == record.textValue } ?: return false
                    settings.setChannelLayout(layout)
                    true
                }
                "subtitles_enabled" -> {
                    settings.setSubtitlesEnabled(record.boolValue ?: return false)
                    true
                }
                "startup_destination" -> {
                    val destination = StartupDestination.entries.firstOrNull { it.name == record.textValue } ?: return false
                    launcher.applyRemoteStartupDestination(destination)
                    true
                }
                else -> false
            }
            else -> false
        }
        if (applied && record.kind in setOf("setting", "guide_setting") && record.recordKey != "startup_destination") {
            check(settingsStorage.edit().commit()) { "Unable to persist synchronized preferences." }
        }
        return applied
    }

    /** Main-thread caller compares a pre-pull snapshot; newly received group keys are sparse. */
    fun applyIfUnchanged(record: AdvancedCloudRecord, before: AdvancedCloudRecord?, current: AdvancedCloudRecord?): Boolean {
        val unchanged = if (before == null) record.kind == "home_group" && current == null
            else current?.samePayload(before) == true
        return unchanged && apply(record)
    }

    fun close() { launcher.close(); home.close() }

    private fun readEnum(key: String, allowed: List<String>, default: String): String =
        runCatching { settingsStorage.getString(key, default)?.takeIf { it in allowed } ?: default }.getOrDefault(default)
}
