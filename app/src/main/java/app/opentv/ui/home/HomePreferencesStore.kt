/* Nuvix modifications Copyright (C) 2026 Nuvix Contributors. GPL-3.0-or-later. */
package app.opentv.ui.home

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Only sections rendered by TelevisionHomeScreen can be customized or synchronized. */
enum class HomeSection(val key: String, val title: String) {
    CONTINUE("continue", "Continue watching"),
    RECENT("recent", "Recently watched"),
    FAVOURITES("favourites", "Your favorite channels"),
    ON_NOW("on_now", "On now"),
    MOVIES("movies", "Movies"),
    SERIES("series", "Shows"),
    APPS("apps", "Installed apps");

    companion object {
        fun fromKey(key: String): HomeSection? = entries.firstOrNull { it.key == key }
    }
}

data class HomePreferences(val visibleSections: Set<HomeSection> = HomeSection.entries.toSet()) {
    fun isVisible(section: HomeSection): Boolean = section in visibleSections
}

/** Local-first shelf visibility. No playback, focus, provider, or permission state lives here. */
class HomePreferencesStore(context: Context) {
    private val storage = context.applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
    private val state = MutableStateFlow(read())
    val preferences: StateFlow<HomePreferences> = state.asStateFlow()
    val currentPreferences: HomePreferences get() = read()
    private val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == null || HomeSection.fromKey(key) != null) state.value = read()
    }

    init { storage.registerOnSharedPreferenceChangeListener(listener) }

    fun setVisible(section: HomeSection, visible: Boolean) {
        storage.edit().putBoolean(section.key, visible).apply()
        state.value = read()
    }

    /** Unknown keys are ignored instead of becoming arbitrary synchronized preferences. */
    fun applyRemoteVisibility(key: String, visible: Boolean): Boolean {
        val section = HomeSection.fromKey(key) ?: return false
        check(storage.edit().putBoolean(section.key, visible).commit()) { "Unable to persist synchronized home preference." }
        state.value = read()
        return true
    }

    fun close() { storage.unregisterOnSharedPreferenceChangeListener(listener) }

    private fun read(): HomePreferences = HomePreferences(HomeSection.entries.filter { section ->
        runCatching { storage.getBoolean(section.key, true) }.getOrDefault(true)
    }.toSet())

    companion object { const val PREFERENCES = "nuvix_home" }
}
