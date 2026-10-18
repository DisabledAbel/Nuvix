/* Copyright (C) 2026 Nuvix Contributors. GPL-3.0-or-later. */
package app.opentv.ui.multiview

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.opentv.core.RecentChannels
import app.opentv.core.ServiceLocator
import app.opentv.data.model.Channel
import app.opentv.data.model.StreamKind
import app.opentv.data.model.shownName
import app.opentv.data.repo.LocalCustomGroups
import app.opentv.ui.channels.OnScreenKeyboard

private enum class ChannelCollection(val label: String) {
    ALL("All Channels"), FAVORITES("Favorites"), RECENT("Recent Channels"), GROUPS("Custom Groups"), PROVIDERS("IPTV Providers"),
}

/** Catalog-only picker: highlighting, filtering and browsing never reserve a provider connection. */
@Composable
internal fun MultiViewChannelPicker(
    title: String,
    onDismiss: () -> Unit,
    onSelect: (Channel) -> Unit,
    onOpenGuide: () -> Unit,
    alternativeFor: Long? = null,
) {
    val context = LocalContext.current
    val graph = remember(context) { ServiceLocator.get(context) }
    val channels by remember(graph) { graph.database.customGroups().observeAuthorizedChannels() }.collectAsState(initial = emptyList())
    val sources by remember(graph) { graph.database.sources().observeAll() }.collectAsState(initial = emptyList())
    val groups by remember(graph) { graph.database.customGroups().observeActive() }.collectAsState(initial = emptyList())
    val categories by remember(graph) { graph.catalogRepository.observeCategories(StreamKind.LIVE) }.collectAsState(initial = emptyList())
    val hidden by graph.settings.hiddenCategories.collectAsState()
    val unlocked by graph.settings.hiddenUnlocked.collectAsState()
    val recent by remember(context) { RecentChannels.get(context) }.ids.collectAsState()
    var collection by remember { mutableStateOf(ChannelCollection.ALL) }
    var providerId by remember { mutableStateOf<Long?>(null) }
    var groupId by remember { mutableStateOf<String?>(null) }
    var query by remember { mutableStateOf("") }
    var keyboard by remember { mutableStateOf(false) }
    var alternativesOnly by remember(alternativeFor) { mutableStateOf(alternativeFor != null) }
    var alternativeIds by remember(alternativeFor) { mutableStateOf<Set<Long>>(emptySet()) }
    LaunchedEffect(alternativeFor) {
        if (alternativeFor != null) {
            try {
                graph.database.channels().byId(alternativeFor)?.let { channel ->
                    alternativeIds = (graph.catalogRepository.variants(channel) + graph.catalogRepository.recordSourceOptions(channel))
                        .map { it.id }.filter { it != alternativeFor }.toSet()
                }
            } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (_: Exception) { alternativeIds = emptySet() }
        }
    }
    val providers = remember(sources) { sources.filter { it.enabled }.associateBy { it.id } }
    val visible = remember(channels, categories, hidden, unlocked, providers) {
        channels.filter { it.sourceId in providers && LocalCustomGroups.visible(it, categories, hidden, unlocked) }
    }
    val choices = remember(visible, collection, providerId, groupId, groups, recent, query, alternativesOnly, alternativeIds) {
        val selected = when (collection) {
            ChannelCollection.ALL -> visible
            ChannelCollection.FAVORITES -> visible.filter { it.favourite }
            ChannelCollection.RECENT -> {
                val byId = visible.associateBy { it.id }
                recent.mapNotNull(byId::get)
            }
            ChannelCollection.GROUPS -> groups.firstOrNull { it.cloudId == groupId }?.let { group ->
                LocalCustomGroups.resolve(group, channels, categories, hidden, unlocked)
            }.orEmpty()
            ChannelCollection.PROVIDERS -> visible.filter { it.sourceId == providerId }
        }
        selected.filter { (!alternativesOnly || it.id in alternativeIds) && (query.isBlank() || it.shownName.contains(query, ignoreCase = true) || providers[it.sourceId]?.name?.contains(query, ignoreCase = true) == true) }
    }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxWidth(0.92f).fillMaxHeight(0.94f), shape = MaterialTheme.shapes.large) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(title, style = MaterialTheme.typography.headlineSmall)
                if (alternativesOnly) Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Matching broadcasts from your authorized catalog", Modifier.weight(1f))
                    TextButton(onClick = { alternativesOnly = false }) { Text("Browse All Channels") }
                }
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(ChannelCollection.entries) { item ->
                        FilterChip(selected = collection == item, onClick = { collection = item }, label = { Text(item.label) })
                    }
                    item { OutlinedButton(onClick = onOpenGuide) { Text("TV Guide") } }
                }
                if (collection == ChannelCollection.PROVIDERS) LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(providers.values.toList(), key = { it.id }) { source ->
                        FilterChip(selected = providerId == source.id, onClick = { providerId = source.id }, label = { Text(source.name) })
                    }
                }
                if (collection == ChannelCollection.GROUPS) LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(groups, key = { it.cloudId }) { group ->
                        FilterChip(selected = groupId == group.cloudId, onClick = { groupId = group.cloudId }, label = { Text(group.name) })
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(value = query, onValueChange = { query = it.take(120) }, label = { Text("Find channel or provider") }, modifier = Modifier.weight(1f), singleLine = true)
                    OutlinedButton(onClick = { keyboard = !keyboard }) { Text(if (keyboard) "Hide Keyboard" else "Remote Keyboard") }
                }
                LazyColumn(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (choices.isEmpty()) item {
                        Text(when {
                            collection == ChannelCollection.GROUPS && groupId == null -> "Choose a custom group above."
                            collection == ChannelCollection.PROVIDERS && providerId == null -> "Choose a provider above."
                            else -> "No matching channels. Choose another collection or clear the search."
                        }, Modifier.padding(16.dp))
                    }
                    items(choices, key = { it.id }) { channel ->
                        OutlinedButton(onClick = { onSelect(channel) }, modifier = Modifier.fillMaxWidth(), contentPadding = PaddingValues(12.dp)) {
                            Column(Modifier.weight(1f)) {
                                Text(channel.shownName, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium)
                                Text(providers[channel.sourceId]?.name.orEmpty(), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelMedium)
                            }
                            if (channel.favourite) Text("★", Modifier.padding(start = 12.dp))
                        }
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("${choices.size} channels · No stream opens until selected", style = MaterialTheme.typography.labelMedium)
                    TextButton(onClick = onDismiss) { Text("Cancel") }
                }
            }
        }
    }
    if (keyboard) Dialog(onDismissRequest = { keyboard = false }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.widthIn(max = 800.dp).fillMaxWidth(0.9f), shape = MaterialTheme.shapes.large) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Find Channel", style = MaterialTheme.typography.titleLarge)
                Text(query.ifEmpty { "Enter a channel or provider name" }, maxLines = 1, overflow = TextOverflow.Ellipsis)
                OnScreenKeyboard(
                    onKey = { query = (query + it).take(120) }, onSpace = { query = (query + " ").take(120) },
                    onBackspace = { query = query.dropLast(1) }, onClear = { query = "" },
                )
                Button(onClick = { keyboard = false }) { Text("Show Channels") }
            }
        }
    }
}
