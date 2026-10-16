/* Nuvix modifications Copyright (C) 2026 Nuvix Contributors. GPL-3.0-or-later. */
package app.opentv.ui.home

import kotlinx.serialization.builtins.serializer
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

enum class HomeLayout { CLASSIC, COMPACT, CINEMA }
enum class HomeCardSize { SMALL, MEDIUM, LARGE }
enum class HomeCorners(val radius: Int) { SQUARE(0), SOFT(14), ROUND(24) }
enum class HomeBackground { DEFAULT, MIDNIGHT, CHARCOAL, FOREST, OCEAN, DUSK, DYNAMIC_ARTWORK }
enum class HomeFeatured { AUTOMATIC, CONTINUE, RECENT, FAVOURITE, DISABLED }

data class HomePresentation(
    val layout: HomeLayout = HomeLayout.CLASSIC,
    val movieSize: HomeCardSize = HomeCardSize.MEDIUM,
    val seriesSize: HomeCardSize = HomeCardSize.MEDIUM,
    val channelSize: HomeCardSize = HomeCardSize.MEDIUM,
    val corners: HomeCorners = HomeCorners.SOFT,
    val metadata: Boolean = true,
    val background: HomeBackground = HomeBackground.DEFAULT,
    val featured: HomeFeatured = HomeFeatured.AUTOMATIC,
) {
    /** Versioned, bounded, URL-free protocol. Names make every entry unique. */
    fun portable(): List<String> = listOf("v1", "layout=${layout.name}", "movie=${movieSize.name}",
        "series=${seriesSize.name}", "channel=${channelSize.name}", "corners=${corners.name}",
        "metadata=$metadata", "background=${background.name}", "featured=${featured.name}")

    companion object {
        fun parse(values: List<String>): HomePresentation? = runCatching {
            require(values.size == 9 && values[0] == "v1")
            fun value(index: Int, name: String): String {
                require(values[index].startsWith("$name="))
                return values[index].substringAfter('=')
            }
            HomePresentation(HomeLayout.valueOf(value(1, "layout")), HomeCardSize.valueOf(value(2, "movie")),
                HomeCardSize.valueOf(value(3, "series")), HomeCardSize.valueOf(value(4, "channel")),
                HomeCorners.valueOf(value(5, "corners")), value(6, "metadata").let {
                    require(it == "true" || it == "false"); it == "true"
                }, HomeBackground.valueOf(value(7, "background")), HomeFeatured.valueOf(value(8, "featured")))
        }.getOrNull()
    }
}

object HomeShelfOrder {
    const val MAX_SHELVES = 160
    val defaults = HomeSection.entries.map { it.key }
    private val localKey = Regex("[a-z][a-z0-9_]{0,47}")
    fun groupKey(id: String) = "group:$id"
    fun isGroup(key: String): Boolean = key.startsWith("group:") && runCatching {
        java.util.UUID.fromString(key.removePrefix("group:")).toString() == key.removePrefix("group:")
    }.getOrDefault(false)
    fun portable(key: String) = HomeSection.fromKey(key) != null || isGroup(key)
    fun normalize(saved: List<String>, groups: List<String> = emptyList()): List<String> {
        val safe = saved.take(MAX_SHELVES).filter { localKey.matches(it) || isGroup(it) }.distinct()
        // New groups appear after live shelves in the default layout. Saved positions always win.
        val expected = defaults.take(4) + groups.filter(::isGroup).take(128) + defaults.drop(4)
        val result = safe.toMutableList()
        expected.forEach { key ->
            if (key !in result) {
                val previous = expected.takeWhile { it != key }.lastOrNull { it in result }
                result.add(if (previous == null) 0 else result.indexOf(previous) + 1, key)
            }
        }
        while (result.size > MAX_SHELVES) {
            val removable = result.indexOfLast { it !in expected }
            if (removable < 0) break
            result.removeAt(removable)
        }
        return result
    }
    fun move(order: List<String>, key: String, delta: Int): List<String> = order.toMutableList().apply {
        val from = indexOf(key)
        if (from >= 0) add((from + delta.coerceIn(-1, 1)).coerceIn(0, lastIndex), removeAt(from))
    }
}

data class HomePreferences(
    val visibleSections: Set<HomeSection> = HomeSection.entries.toSet(),
    val shelfOrder: List<String> = HomeShelfOrder.defaults,
    val hiddenGroups: Set<String> = emptySet(),
    val presentation: HomePresentation = HomePresentation(),
) {
    fun isVisible(section: HomeSection): Boolean = section in visibleSections
    fun isVisible(key: String): Boolean = HomeSection.fromKey(key)?.let(::isVisible) ?: (key !in hiddenGroups)
}

/** Local-first presentation only. Existing visibility keys and unrelated settings are preserved. */
class HomePreferencesStore(context: Context) {
    private val storage = context.applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
    private val state = MutableStateFlow(read())
    val preferences: StateFlow<HomePreferences> = state.asStateFlow()
    val currentPreferences: HomePreferences get() = read()
    private val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> state.value = read() }
    init { storage.registerOnSharedPreferenceChangeListener(listener) }

    fun setVisible(section: HomeSection, visible: Boolean) = setVisible(section.key, visible)
    fun setVisible(key: String, visible: Boolean) {
        require(HomeShelfOrder.portable(key))
        save(storage.edit().putBoolean(key, visible))
    }
    fun toggleVisibility(key: String) = setVisible(key, !read().isVisible(key))
    fun updatePresentation(update: (HomePresentation) -> HomePresentation) = setPresentation(update(read().presentation))
    fun setPresentation(value: HomePresentation) = save(storage.edit().putString(PRESENTATION, encode(value.portable())))
    fun setOrder(order: List<String>) = save(storage.edit().putString(ORDER, encode(HomeShelfOrder.normalize(order))))
    fun moveShelf(key: String, delta: Int, groups: List<String> = emptyList()) =
        setOrder(HomeShelfOrder.move(HomeShelfOrder.normalize(read().shelfOrder, groups), key, delta))
    fun resetOrder() = save(storage.edit().remove(ORDER))
    fun reset() {
        val edit = storage.edit().remove(ORDER).remove(PRESENTATION)
        HomeSection.entries.forEach { edit.putBoolean(it.key, true) }
        storage.all.keys.filter(HomeShelfOrder::isGroup).forEach { edit.putBoolean(it, true) }
        save(edit)
    }
    fun applyRemoteVisibility(key: String, visible: Boolean): Boolean {
        if (HomeSection.fromKey(key) == null) return false
        setVisible(key, visible)
        return true
    }
    val groupVisibility: Map<String, Boolean> get() = storage.all.keys.filter(HomeShelfOrder::isGroup).associateWith(::visible)
    fun applyRemoteOrder(order: List<String>) {
        // Retain syntactically safe future local identifiers without exporting unknown protocol values.
        val future = read().shelfOrder.filterNot(HomeShelfOrder::portable)
        setOrder(order + future)
    }
    fun close() { storage.unregisterOnSharedPreferenceChangeListener(listener) }
    private fun save(edit: SharedPreferences.Editor) {
        check(edit.commit()) { "Unable to persist home preferences." }
        state.value = read()
    }
    private fun decode(key: String): List<String> = runCatching {
        val raw = storage.getString(key, null) ?: return emptyList()
        require(raw.length <= 16384)
        kotlinx.serialization.json.Json.decodeFromString<List<String>>(raw)
    }.getOrDefault(emptyList())
    private fun encode(values: List<String>): String = kotlinx.serialization.json.Json.encodeToString(
        kotlinx.serialization.builtins.ListSerializer(String.serializer()), values)
    private fun read(): HomePreferences = HomePreferences(
        HomeSection.entries.filter { visible(it.key) }.toSet(), HomeShelfOrder.normalize(decode(ORDER)),
        storage.all.keys.filter { HomeShelfOrder.isGroup(it) && !visible(it) }.toSet(),
        HomePresentation.parse(decode(PRESENTATION)) ?: HomePresentation(),
    )
    private fun visible(key: String) = runCatching { storage.getBoolean(key, true) }.getOrDefault(true)
    companion object {
        const val PREFERENCES = "nuvix_home"
        const val ORDER = "shelf_order_v1"
        const val PRESENTATION = "presentation_v1"
    }
}
