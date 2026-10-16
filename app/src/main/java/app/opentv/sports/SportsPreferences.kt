/* Copyright (C) 2026 Nuvix Contributors. GPL-3.0-or-later. */
package app.opentv.sports

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class SportsFilter(val label: String) {
    ALL("All games"), LIVE("Live / Airing"), TODAY("Today"), TOMORROW("Tomorrow"), UPCOMING("Upcoming"),
    FAVORITE_TEAMS("Favorite Teams"), FAVORITE_LEAGUES("Favorite Leagues"), WITH_STREAMS("Games With Available Streams")
}
data class SportsPreferences(
    val defaultView: SportsFilter = SportsFilter.ALL,
    val streamSort: SportsStreamSort = SportsStreamSort.CONFIDENCE,
    val showPossible: Boolean = true,
    val showScores: Boolean = true,
    val reminders: Boolean = true,
)

/** Uses the established typed local preference pattern, deliberately outside cloud export allowlists. */
class SportsPreferencesStore(context: Context) {
    private val storage = context.applicationContext.getSharedPreferences("nuvix_sports", Context.MODE_PRIVATE)
    private val state = MutableStateFlow(read())
    val preferences = state.asStateFlow()
    private val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> state.value = read() }
    init { storage.registerOnSharedPreferenceChangeListener(listener) }
    fun set(value: SportsPreferences) {
        check(storage.edit().putString("default_view", value.defaultView.name).putString("stream_sort", value.streamSort.name)
            .putBoolean("show_possible", value.showPossible).putBoolean("show_scores", value.showScores)
            .putBoolean("reminders", value.reminders).commit())
        state.value = read()
    }
    private fun read() = SportsPreferences(
        runCatching { SportsFilter.valueOf(storage.getString("default_view", "ALL")!!) }.getOrDefault(SportsFilter.ALL),
        runCatching { SportsStreamSort.valueOf(storage.getString("stream_sort", "CONFIDENCE")!!) }.getOrDefault(SportsStreamSort.CONFIDENCE),
        bool("show_possible"), bool("show_scores"), bool("reminders"),
    )
    private fun bool(key: String) = runCatching { storage.getBoolean(key, true) }.getOrDefault(true)
    fun close() { storage.unregisterOnSharedPreferenceChangeListener(listener) }
}
