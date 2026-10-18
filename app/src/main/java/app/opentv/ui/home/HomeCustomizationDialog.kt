/* Nuvix modifications Copyright (C) 2026 Nuvix Contributors. GPL-3.0-or-later. */
package app.opentv.ui.home

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

private val customizationPages = listOf("Home Layout", "Rearrange Sections", "Show or Hide Sections",
    "Poster and Card Sizes", "Background and Appearance", "Featured Content", "Reset Home Layout")

/** Owns dialog focus independently of playback or content-loading state. */
@Composable
internal fun HomeCustomizationAccess(preferences: HomePreferences, store: HomePreferencesStore,
    groups: Map<String, String> = emptyMap(), onActiveChanged: (Boolean) -> Unit = {}) {
    var open by rememberSaveable { mutableStateOf(false) }
    val focus = remember { FocusRequester() }
    var wasOpen by remember { mutableStateOf(false) }
    HomeOptionButton("Customize home", Modifier.focusRequester(focus)) { open = true }
    if (open) HomeCustomizationDialog(preferences, store, groups) { open = false }
    LaunchedEffect(open) {
        onActiveChanged(open)
        if (wasOpen && !open) focus.requestFocus()
        wasOpen = open
    }
}

/** Standard focusable buttons support D-pad/Select; Back returns to the menu, then Home. */
@Composable
internal fun HomeCustomizationDialog(
    preferences: HomePreferences,
    store: HomePreferencesStore,
    groups: Map<String, String> = emptyMap(),
    onDismiss: () -> Unit,
) {
    var page by rememberSaveable { mutableStateOf<String?>(null) }
    var selectedShelf by rememberSaveable { mutableStateOf(HomeSection.CONTINUE.key) }
    val order = remember(preferences.shelfOrder, groups) {
        HomeShelfOrder.normalize(preferences.shelfOrder, groups.keys.toList()).filter {
            HomeSection.fromKey(it) != null || it in groups
        }
    }
    LaunchedEffect(order) { if (selectedShelf !in order) selectedShelf = order.firstOrNull() ?: HomeSection.CONTINUE.key }
    val listState = rememberLazyListState()
    val initialFocus = remember { FocusRequester() }
    val presentation = preferences.presentation
    fun title(key: String) = HomeSection.fromKey(key)?.title ?: groups[key].orEmpty()
    fun back() { if (page == null) onDismiss() else page = null }
    Dialog(onDismissRequest = ::back, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        BackHandler(onBack = ::back)
        Surface(Modifier.fillMaxWidth(.92f).heightIn(max = 520.dp), shape = MaterialTheme.shapes.large) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(page ?: "Customize home", style = MaterialTheme.typography.headlineSmall)
                Text("Use D-pad to focus, Select to change, Back to return. Changes save immediately.",
                    style = MaterialTheme.typography.bodySmall)
                if (page == "Rearrange Sections" || page == "Show or Hide Sections") {
                    Text("Select a section, then use the buttons below. Hidden sections keep their position.")
                    LaunchedEffect(order, selectedShelf) {
                        val index = order.indexOf(selectedShelf)
                        if (index >= 0) listState.scrollToItem(index)
                    }
                    LazyColumn(Modifier.weight(1f, fill = false).heightIn(max = 230.dp), state = listState) {
                        items(order, key = { it }) { key ->
                            HomeOptionButton("${title(key)} · ${if (preferences.isVisible(key)) "Shown" else "Hidden"}",
                                Modifier.fillMaxWidth().semantics { selected = selectedShelf == key },
                                selected = selectedShelf == key) { selectedShelf = key }
                        }
                    }
                    Text("Selected: ${title(selectedShelf)}", style = MaterialTheme.typography.labelLarge)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        // Keep controls focusable at boundaries so movement cannot strand focus.
                        if (page == "Rearrange Sections") {
                            HomeOptionButton("Move up") { store.moveShelf(selectedShelf, -1, groups.keys.toList()) }
                            HomeOptionButton("Move down") { store.moveShelf(selectedShelf, 1, groups.keys.toList()) }
                        }
                        HomeOptionButton(if (preferences.isVisible(selectedShelf)) "Hide section" else "Restore section") {
                            store.toggleVisibility(selectedShelf)
                        }
                        if (page == "Rearrange Sections") HomeOptionButton("Reset order") { store.resetOrder() }
                    }
                } else {
                    LazyColumn(Modifier.weight(1f, fill = false).heightIn(max = 330.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        when (page) {
                            null -> items(customizationPages) { name ->
                                HomeOptionButton(name, Modifier.fillMaxWidth()) { page = name }
                            }
                            "Home Layout" -> {
                                item { Text("Classic: balanced shelves. Compact: smaller cards and spacing. Cinema: larger artwork and hero.") }
                                items(HomeLayout.entries) { layout ->
                                    HomeOptionButton(layout.label(), Modifier.fillMaxWidth(), presentation.layout == layout) {
                                        store.updatePresentation { it.copy(layout = layout) }
                                    }
                                }
                            }
                            "Poster and Card Sizes" -> {
                                item { Text("Select an option to cycle Small, Medium and Large. Layout presets scale these sizes.") }
                                item { HomeOptionButton("Movie posters: ${presentation.movieSize.label()}") { store.updatePresentation { it.copy(movieSize = it.movieSize.next()) } } }
                                item { HomeOptionButton("TV series posters: ${presentation.seriesSize.label()}") { store.updatePresentation { it.copy(seriesSize = it.seriesSize.next()) } } }
                                item { HomeOptionButton("Live channel cards: ${presentation.channelSize.label()}") { store.updatePresentation { it.copy(channelSize = it.channelSize.next()) } } }
                                item { HomeOptionButton("Card corners: ${presentation.corners.label()}") { store.updatePresentation { it.copy(corners = it.corners.next()) } } }
                                item { HomeOptionButton("Secondary metadata: ${if (presentation.metadata) "Shown" else "Hidden"}") { store.updatePresentation { it.copy(metadata = !it.metadata) } } }
                            }
                            "Background and Appearance" -> {
                                item { Text("Midnight, Charcoal and Forest are solid colors. Ocean and Dusk are gradients. Dynamic artwork follows focused movies and shows with a dark overlay; missing art uses the default.") }
                                items(HomeBackground.entries) { background ->
                                    HomeOptionButton(background.label(), Modifier.fillMaxWidth(), presentation.background == background) {
                                        store.updatePresentation { it.copy(background = background) }
                                    }
                                }
                            }
                            "Featured Content" -> {
                                item { Text("Choose the hero's source. When no matching content exists, navigation actions remain available.") }
                                items(HomeFeatured.entries) { featured ->
                                    HomeOptionButton(featured.label(), Modifier.fillMaxWidth(), presentation.featured == featured) {
                                        store.updatePresentation { it.copy(featured = featured) }
                                    }
                                }
                            }
                            "Reset Home Layout" -> {
                                item { Text("Restore Classic, medium cards, soft corners, metadata, default background, automatic featured content, default section order and all sections shown. App favorites, groups and playback are preserved.") }
                                item { HomeOptionButton("Restore home defaults") { store.reset(); page = null } }
                            }
                        }
                    }
                }
                HomeOptionButton(if (page == null) "Done" else "Back to customization", Modifier.focusRequester(initialFocus), onClick = ::back)
            }
        }
        LaunchedEffect(page) { initialFocus.requestFocus() }
    }
}

/** Returns the display label for a home presentation choice. */
internal fun Enum<*>.label(): String = when (this) {
    HomeFeatured.AUTOMATIC -> "Automatic Featured Content"
    HomeFeatured.CONTINUE -> "Continue Watching"
    HomeFeatured.RECENT -> "Recently Watched Channel"
    HomeFeatured.FAVOURITE -> "Favorite Channel"
    HomeFeatured.DISABLED -> "Featured Content Disabled"
    else -> name.lowercase().replace('_', ' ').replaceFirstChar { it.uppercase() }
}
/** Cycles to the next supported enum choice for a home customization control. */
private inline fun <reified T : Enum<T>> T.next(): T = enumValues<T>().let { it[(ordinal + 1) % it.size] }

/** Displays a focusable home customization choice and its activation callback. */
@Composable
internal fun HomeOptionButton(label: String, modifier: Modifier = Modifier, selected: Boolean = false, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    OutlinedButton(onClick = onClick, modifier = modifier.semantics { this.selected = selected }.onFocusChanged { focused = it.isFocused }
        .border(if (focused) 3.dp else 0.dp, if (focused) MaterialTheme.colorScheme.primary else androidx.compose.ui.graphics.Color.Transparent, MaterialTheme.shapes.extraLarge),
        colors = ButtonDefaults.outlinedButtonColors(containerColor = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface)) {
        Text(label)
    }
}
