/*
 * Nuvix modifications, Copyright (C) 2026 Nuvix Contributors.
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.launcher

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray

enum class StartupDestination(val title: String) {
    HOME("Nuvix Home"),
    LIVE_TV("Live TV"),
    GUIDE("TV Guide"),
    LAST_WATCHED("Last watched channel"),
    APPS("Apps"),
}

data class LauncherPreferences(
    val startupDestination: StartupDestination = StartupDestination.HOME,
    val favoritePackages: List<String> = emptyList(),
)

/** Ordered operations shared by app tiles and the local preferences store. */
object AppOrder {
    fun pin(order: List<String>, id: String): List<String> =
        if (id.isBlank()) order.distinct() else (order + id).distinct()

    fun unpin(order: List<String>, id: String): List<String> = order.filterNot { it == id }.distinct()

    fun move(order: List<String>, id: String, offset: Int): List<String> {
        val result = order.distinct().toMutableList()
        val from = result.indexOf(id)
        if (from < 0) return result
        val to = (from.toLong() + offset.toLong()).coerceIn(0, (result.size - 1).toLong()).toInt()
        if (from != to) result.add(to, result.removeAt(from))
        return result
    }

    fun retainInstalled(order: List<String>, installed: Set<String>): List<String> =
        order.filter { it in installed }.distinct()
}

/**
 * Launcher preferences contain package names and a destination only; provider credentials never
 * enter this store. A preferences listener keeps independently constructed screens consistent.
 */
class LauncherSettingsStore(context: Context) {
    private val storage = context.applicationContext.getSharedPreferences("nuvix_launcher", Context.MODE_PRIVATE)
    private val state = MutableStateFlow(read())
    val preferences: StateFlow<LauncherPreferences> = state.asStateFlow()

    private val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
        state.value = read()
    }

    init { storage.registerOnSharedPreferenceChangeListener(listener) }

    fun setStartupDestination(destination: StartupDestination) {
        storage.edit().putString("startup_destination", destination.name).apply()
        state.value = read()
    }

    fun pin(packageName: String) = updateFavorites { AppOrder.pin(it, packageName) }
    fun unpin(packageName: String) = updateFavorites { AppOrder.unpin(it, packageName) }
    fun moveFavorite(packageName: String, offset: Int) = updateFavorites { AppOrder.move(it, packageName, offset) }
    fun pruneFavorites(availablePackages: Set<String>) =
        updateFavorites { AppOrder.retainInstalled(it, availablePackages) }

    /** Release the listener when a screen leaves composition. */
    fun close() { storage.unregisterOnSharedPreferenceChangeListener(listener) }

    private fun updateFavorites(update: (List<String>) -> List<String>) {
        val previous = read().favoritePackages
        val next = update(previous)
        if (next != previous) storage.edit().putString("favorite_packages", JSONArray(next).toString()).apply()
        state.value = read()
    }

    private fun read(): LauncherPreferences {
        val destination = runCatching {
            StartupDestination.valueOf(storage.getString("startup_destination", null).orEmpty())
        }.getOrDefault(StartupDestination.HOME)
        val favorites = runCatching {
            val array = JSONArray(storage.getString("favorite_packages", "[]"))
            List(array.length()) { array.optString(it) }.filter { it.isNotBlank() }.distinct()
        }.getOrDefault(emptyList())
        return LauncherPreferences(destination, favorites)
    }
}
