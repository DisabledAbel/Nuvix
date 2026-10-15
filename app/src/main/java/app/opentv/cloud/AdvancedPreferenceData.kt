/* Nuvix modifications Copyright (C) 2026 Nuvix Contributors. GPL-3.0-or-later. */
package app.opentv.cloud

import android.content.Context
import app.opentv.core.AppSettings
import app.opentv.launcher.LauncherSettingsStore
import app.opentv.launcher.StartupDestination
import app.opentv.ui.home.HomePreferencesStore
import app.opentv.ui.home.HomeSection

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
        HomeSection.entries.forEach { section ->
            result += AdvancedCloudRecord(section.key, "home_shelf", updatedAtMillis = now, deviceId = deviceId,
                boolValue = homePreferences.isVisible(section))
        }
        return result.filter { it.isSafe(now) }
    }

    /** Applies validated portable values through existing setters, without touching HOME roles. */
    fun apply(record: AdvancedCloudRecord): Boolean {
        if (!record.isSafe(System.currentTimeMillis()) || record.deleted) return false
        val applied = when (record.kind) {
            "app_order" -> record.recordKey == "apps" && launcher.applyRemoteAppOrder(record.entries)
            "home_shelf" -> record.boolValue?.let { home.applyRemoteVisibility(record.recordKey, it) } ?: false
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
        if (applied && record.kind == "setting" && record.recordKey != "startup_destination") {
            check(settingsStorage.edit().commit()) { "Unable to persist synchronized preferences." }
        }
        return applied
    }

    fun close() { launcher.close(); home.close() }

    private fun readEnum(key: String, allowed: List<String>, default: String): String =
        runCatching { settingsStorage.getString(key, default)?.takeIf { it in allowed } ?: default }.getOrDefault(default)
}
