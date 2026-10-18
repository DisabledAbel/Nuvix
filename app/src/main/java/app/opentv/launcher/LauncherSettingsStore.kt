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

/** Supported initial application destination when entering the launcher. */
enum class StartupDestination(val title: String) {
    HOME("Nuvix Home"),
    LIVE_TV("Live TV"),
    GUIDE("TV Guide"),
    LAST_WATCHED("Last watched channel"),
    APPS("Apps"),
}

/** Saved startup destination and ordered favorite application packages. */
data class LauncherPreferences(
    val startupDestination: StartupDestination = StartupDestination.HOME,
    val favoritePackages: List<String> = emptyList(),
)

/** Ordered operations shared by app tiles and the local preferences store. */
object AppOrder {
    /** Adds a package to the favorite order without creating duplicates. */
    fun pin(order: List<String>, id: String): List<String> =
        if (id.isBlank()) order.distinct() else (order + id).distinct()

    /** Removes a package from the favorite order. */
    fun unpin(order: List<String>, id: String): List<String> = order.filterNot { it == id }.distinct()

    /** Moves an existing favorite within the supported ordering bounds. */
    fun move(order: List<String>, id: String, offset: Int): List<String> {
        val result = order.distinct().toMutableList()
        val from = result.indexOf(id)
        if (from < 0) return result
        val to = (from.toLong() + offset.toLong()).coerceIn(0, (result.size - 1).toLong()).toInt()
        if (from != to) result.add(to, result.removeAt(from))
        return result
    }

    /** Filters favorite ordering to packages still present in the installed-app set. */
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
    /** Read storage directly when a preferences listener is capturing a just-authored edit. */
    val currentPreferences: LauncherPreferences get() = read()

    private val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
        state.value = read()
    }

    init { storage.registerOnSharedPreferenceChangeListener(listener) }

    /** Persists and publishes the selected initial application destination. */
    fun setStartupDestination(destination: StartupDestination) {
        storage.edit().putString("startup_destination", destination.name).apply()
        state.value = read()
    }

    /** Adds an eligible package to persisted application favorites. */
    fun pin(packageName: String) = updateFavorites { AppOrder.pin(it, packageName) }
    /** Removes a package from persisted application favorites. */
    fun unpin(packageName: String) = updateFavorites { AppOrder.unpin(it, packageName) }
    /** Moves a favorite application within its saved order. */
    fun moveFavorite(packageName: String, offset: Int) = updateFavorites { AppOrder.move(it, packageName, offset) }
    /**
     * Availability is a display filter only. A package missing on this TV may be installed on
     * another TV, or installed here later; it must stay in the user's desired order.
     */
    fun pruneFavorites(availablePackages: Set<String>): List<String> =
        AppOrder.retainInstalled(read().favoritePackages, availablePackages)

    /** Replace the explicitly synchronized desired order, including packages absent locally. */
    fun applyRemoteAppOrder(packageNames: List<String>): Boolean {
        if (packageNames.size > MAX_FAVORITES || packageNames.distinct().size != packageNames.size ||
            packageNames.any { !isSupportedPackage(it) }) return false
        check(storage.edit().putString("favorite_packages", JSONArray(packageNames).toString()).commit()) {
            "Unable to persist synchronized app order."
        }
        state.value = read()
        return true
    }

    /** This changes in-app navigation only; it never requests or changes Android's HOME role. */
    fun applyRemoteStartupDestination(destination: StartupDestination) {
        check(storage.edit().putString("startup_destination", destination.name).commit()) {
            "Unable to persist synchronized startup destination."
        }
        state.value = read()
    }

    /** Release the listener when a screen leaves composition. */
    fun close() { storage.unregisterOnSharedPreferenceChangeListener(listener) }

    /** Validates and saves the ordered favorite package list. */
    private fun updateFavorites(update: (List<String>) -> List<String>) {
        val previous = read().favoritePackages
        val next = update(previous).filter(::isSupportedPackage).distinct()
        if (next != previous) storage.edit().putString("favorite_packages", JSONArray(next).toString()).apply()
        state.value = read()
    }

    /** Restores launcher preferences with defaults for malformed stored values. */
    private fun read(): LauncherPreferences {
        val destination = runCatching {
            StartupDestination.valueOf(storage.getString("startup_destination", null).orEmpty())
        }.getOrDefault(StartupDestination.HOME)
        val favorites = runCatching {
            val array = JSONArray(storage.getString("favorite_packages", "[]"))
            List(array.length()) { array.optString(it) }
                .filter(::isSupportedPackage).distinct()
        }.getOrDefault(emptyList())
        return LauncherPreferences(destination, favorites)
    }

    companion object {
        const val MAX_FAVORITES = 200
        // Android package identifiers, never launch intents, filesystem paths, or URLs.
        private val PACKAGE_NAME = Regex("[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z_][A-Za-z0-9_]*)+")
        /** Checks whether a package name is eligible for the launcher favorite list. */
        fun isSupportedPackage(value: String): Boolean = value.length <= 255 && PACKAGE_NAME.matches(value)
    }
}
