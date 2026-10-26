/* Copyright (C) 2026 Nuvix Contributors. GPL-3.0-or-later. */
package app.opentv.sports

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Supported default and active browsing filters for the Sports Hub. */
enum class SportsFilter(val label: String) {
    ALL("All games"), LIVE("Live / Airing"), TODAY("Today"), TOMORROW("Tomorrow"), UPCOMING("Upcoming"),
    FAVORITE_TEAMS("Favorite Teams"), FAVORITE_LEAGUES("Favorite Leagues"), WITH_STREAMS("Games With Available Streams")
}
/** Saved sports browsing, ordering, suggestion and reminder presentation choices. */
data class SportsPreferences(
    val defaultView: SportsFilter = SportsFilter.ALL,
    val streamSort: SportsStreamSort = SportsStreamSort.CONFIDENCE,
    val showPossible: Boolean = true,
    val showScores: Boolean = true,
    val reminders: Boolean = true,
    val autoSwitchover: Boolean = false,
    val playbackDelay: Boolean = false,
)

/** Uses the established typed local preference pattern, deliberately outside cloud export allowlists. */
class SportsPreferencesStore(context: Context) {
    private val storage = context.applicationContext.getSharedPreferences("nuvix_sports", Context.MODE_PRIVATE)
    private val state = MutableStateFlow(read())
    val preferences = state.asStateFlow()
    private val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> state.value = read() }
    init { storage.registerOnSharedPreferenceChangeListener(listener) }
    /** Persists updated sports preferences and publishes the resulting state. */
    fun set(value: SportsPreferences) {
        check(storage.edit().putString("default_view", value.defaultView.name).putString("stream_sort", value.streamSort.name)
            .putBoolean("show_possible", value.showPossible).putBoolean("show_scores", value.showScores)
            .putBoolean("reminders", value.reminders)
            .putBoolean("auto_switchover", value.autoSwitchover)
            .putBoolean("playback_delay", value.playbackDelay).commit())
        state.value = read()
    }
    /** Restores sports preferences with defaults for malformed stored values. */
    private fun read() = SportsPreferences(
        runCatching { SportsFilter.valueOf(storage.getString("default_view", "ALL")!!) }.getOrDefault(SportsFilter.ALL),
        runCatching { SportsStreamSort.valueOf(storage.getString("stream_sort", "CONFIDENCE")!!) }.getOrDefault(SportsStreamSort.CONFIDENCE),
        bool("show_possible"), bool("show_scores"), bool("reminders"),
        bool("auto_switchover", false), bool("playback_delay", false),
    )
    /** Reads a saved boolean with a fallback for missing or invalid storage. */
    private fun bool(key: String, default: Boolean = true) = runCatching { storage.getBoolean(key, default) }.getOrDefault(default)
    /** Unregisters the preference listener when the store is released. */
    fun close() { storage.unregisterOnSharedPreferenceChangeListener(listener) }
}
