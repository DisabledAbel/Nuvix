/* Copyright (C) 2026 Nuvix Contributors. GPL-3.0-or-later. */
package app.opentv.ui.multiview

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.opentv.core.ServiceLocator
import app.opentv.data.model.shownName
import app.opentv.data.model.Channel
import app.opentv.data.model.Source
import app.opentv.multiview.*
import app.opentv.ui.channels.OnScreenKeyboard

@Composable
internal fun MultiViewPresetsDialog(manager: MultiViewSessionManager, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val store = remember(context) { MultiViewPresetStore(context) }
    DisposableEffect(store) { onDispose { store.close() } }
    val presets by store.presets.collectAsState()
    val state by manager.state.collectAsState()
    var editId by remember { mutableStateOf<String?>(null) }
    var naming by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var preview by remember { mutableStateOf<MultiViewPreset?>(null) }
    var replace by remember { mutableStateOf<MultiViewPreset?>(null) }
    var delete by remember { mutableStateOf<MultiViewPreset?>(null) }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxWidth(0.85f).fillMaxHeight(0.85f), shape = MaterialTheme.shapes.large) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Saved Multi-View Presets", style = MaterialTheme.typography.headlineSmall)
                Text("Presets contain local channel references and presentation settings. Previewing a preset never opens provider connections.", style = MaterialTheme.typography.bodyMedium)
                if (!store.storageAvailable) Text("Saved data uses an unsupported or damaged format. Existing data has been preserved.", color = MaterialTheme.colorScheme.error)
                Button(onClick = { editId = null; name = ""; naming = true; error = null }, enabled = state.panels.isNotEmpty() && store.storageAvailable) { Text("Save Current Layout") }
                LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (presets.isEmpty()) item { Text("No saved presets yet.") }
                    items(presets, key = { it.id }) { preset ->
                        Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = MaterialTheme.shapes.medium) {
                            Column(Modifier.fillMaxWidth().padding(12.dp)) {
                                Text(preset.name, style = MaterialTheme.typography.titleMedium)
                                Text("${preset.presentation.panels.size} channels · ${preset.presentation.layout.name.lowercase().replaceFirstChar(Char::uppercase)}", style = MaterialTheme.typography.labelMedium)
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Button(onClick = { preview = preset }) { Text("Preview & Load") }
                                    OutlinedButton(onClick = { editId = preset.id; name = preset.name; naming = true; error = null }) { Text("Rename") }
                                    OutlinedButton(onClick = { replace = preset }, enabled = state.panels.isNotEmpty()) { Text("Replace") }
                                    TextButton(onClick = { delete = preset }) { Text("Delete") }
                                }
                            }
                        }
                    }
                }
                TextButton(onClick = onDismiss) { Text("Done") }
            }
        }
    }
    if (naming) Dialog(onDismissRequest = { naming = false }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.widthIn(max = 800.dp).fillMaxWidth(0.9f).heightIn(max = 540.dp), shape = MaterialTheme.shapes.large) {
            Column(Modifier.verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(if (editId == null) "Name Preset" else "Rename Preset", style = MaterialTheme.typography.headlineSmall)
                OutlinedTextField(name, { name = it.take(80) }, label = { Text("Preset name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OnScreenKeyboard(onKey = { name = (name + it).take(80) }, onSpace = { name = (name + " ").take(80) }, onBackspace = { name = name.dropLast(1) }, onClear = { name = "" })
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(onClick = {
                        val id = editId
                        val saved = if (id != null) store.rename(id, name) else {
                            val presentation = manager.capturePresentation()
                            if (presentation == null) {
                                error = "Panels must finish starting successfully or be removed before saving this layout."
                                return@Button
                            }
                            store.upsert(name, presentation) != null
                        }
                        if (saved) naming = false else error = "Use a plain name of 1–80 characters. Up to 32 presets can be saved."
                    }, enabled = name.isNotBlank()) { Text("Save") }
                    TextButton(onClick = { naming = false }) { Text("Cancel") }
                }
            }
        }
    }
    replace?.let { preset -> AlertDialog(
        onDismissRequest = { replace = null }, title = { Text("Replace ${preset.name}?") },
        text = { Text("Save the current channels and arrangement over this preset?") },
        confirmButton = { Button(onClick = {
            manager.capturePresentation()?.let { store.upsert(preset.name, it, preset.id) }
            replace = null
        }) { Text("Replace Preset") } }, dismissButton = { TextButton(onClick = { replace = null }) { Text("Cancel") } },
    ) }
    delete?.let { preset -> AlertDialog(
        onDismissRequest = { delete = null }, title = { Text("Delete ${preset.name}?") },
        text = { Text("This removes the saved preset. Current playback continues.") },
        confirmButton = { Button(onClick = { store.delete(preset.id); delete = null }) { Text("Delete Preset") } },
        dismissButton = { TextButton(onClick = { delete = null }) { Text("Cancel") } },
    ) }
    preview?.let { preset -> MultiViewPresetReviewDialog(
        manager, store, preset.presentation, "Preview ${preset.name}",
        onDismiss = { preview = null }, onRestored = { preview = null; onDismiss() },
    ) }
}

/** Reads four catalog rows at most and local account observations; no player or token is allocated. */
@Composable
internal fun MultiViewPresetReviewDialog(
    manager: MultiViewSessionManager,
    store: MultiViewPresetStore,
    presentation: MultiViewPresentation,
    title: String,
    onDismiss: () -> Unit,
    onRestored: () -> Unit,
) {
    val context = LocalContext.current
    val graph = remember(context) { ServiceLocator.get(context) }
    val accounts by graph.connections.snapshots.collectAsState()
    val preferences by graph.settings.multiViewPreferences.collectAsState()
    var channels by remember(presentation) { mutableStateOf<List<MultiViewPreviewChannel>?>(null) }
    var lookupFailed by remember(presentation) { mutableStateOf(false) }
    LaunchedEffect(presentation) {
        try {
            val categories = graph.database.categories().allByKind(app.opentv.data.model.StreamKind.LIVE)
            channels = loadMultiViewPreviewChannels(presentation, graph.database.channels()::byId,
                graph.database.sources()::byId, visible = { channel ->
                    app.opentv.data.repo.LocalCustomGroups.visible(channel, categories,
                        graph.settings.hiddenCategories.value, graph.settings.hiddenUnlocked.value)
                }, registerSource = { source -> graph.connections.registerSource(source) })
        } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
        catch (_: Exception) { lookupFailed = true }
    }
    val preview = remember(presentation, channels, accounts) { channels?.let { store.preview(presentation, it, accounts) } }
    val available = preview?.panels.orEmpty().filter { it.available && !it.accountChanged }
    AlertDialog(
        onDismissRequest = onDismiss, title = { Text(title) },
        text = {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (lookupFailed) item { Text("The local catalog could not be checked. No streams have been opened.") }
                else if (preview == null) item { CircularProgressIndicator() }
                preview?.let { checked ->
                    items(checked.panels, key = { it.panel.panelId }) { panel ->
                        Column {
                            Text(panel.channelName ?: "Unavailable channel #${panel.panel.channel.channelId}", style = MaterialTheme.typography.titleMedium)
                            Text(panel.providerName ?: "Provider #${panel.panel.channel.sourceId}", style = MaterialTheme.typography.labelMedium)
                            if (!panel.available) Text("Unavailable — this panel will be skipped.", color = MaterialTheme.colorScheme.error)
                            if (panel.accountChanged) Text("Provider account changed — this panel will be skipped.", color = MaterialTheme.colorScheme.error)
                        }
                    }
                    item { HorizontalDivider(); Text("Local connection requirements", style = MaterialTheme.typography.titleMedium) }
                    items(checked.requirements) { requirement ->
                        val providerNames = checked.panels.filter { it.panel.channel.sourceId in requirement.sourceIds }.mapNotNull { it.providerName }.distinct().joinToString(" / ")
                        Text("${providerNames.ifBlank { "Provider" }}: ${requirement.requestedConnections} requested; ${requirement.locallyReserved} currently reserved; allowance ${requirement.configuredMaximum ?: "unknown"}.")
                        if (requirement.unknownAllowance) Text("Additional connections require request-specific approval.", color = MaterialTheme.colorScheme.primary)
                        if (requirement.exceedsConfiguredAllowance) Text("The current local total exceeds the configured allowance. Playback will be revalidated and recordings retain priority.", color = MaterialTheme.colorScheme.error)
                    }
                    item { Text("Device decoder capacity is uncertain until streams start. The primary channel starts first; secondary channels follow progressively. No stream opens before you confirm.") }
                }
            }
        },
        confirmButton = { Button(onClick = {
            val ids = available.map { it.panel.panelId }.toSet()
            val panels = presentation.panels.filter { it.panelId in ids }
            if (panels.isNotEmpty()) {
                if (preferences.savePresentationPreferences) graph.settings.setMultiViewPreferences(preferences.copy(showLabels = presentation.showLabels))
                manager.restorePresentation(presentation.copy(
                    panels = panels,
                    primaryPanelId = presentation.primaryPanelId.takeIf { it in ids } ?: panels.first().panelId,
                    audioPanelId = presentation.audioPanelId?.takeIf { it in ids },
                ))
                onRestored()
            }
        }, enabled = available.isNotEmpty() && !lookupFailed) { Text(if (available.size == presentation.panels.size) "Restore Channels" else "Restore Available Channels") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** Registering account metadata is local-only and restores configured limits after a fresh launch. */
internal suspend fun loadMultiViewPreviewChannels(
    presentation: MultiViewPresentation,
    channelById: suspend (Long) -> Channel?,
    sourceById: suspend (Long) -> Source?,
    registerSource: (Source) -> Unit,
    visible: (Channel) -> Boolean = { !it.hidden },
): List<MultiViewPreviewChannel> = presentation.panels.mapNotNull { saved ->
    val channel = channelById(saved.channel.channelId)
    val source = sourceById(saved.channel.sourceId)
    if (channel != null && source?.enabled == true && channel.sourceId == source.id && !channel.hidden && visible(channel)) {
        registerSource(source)
        MultiViewPreviewChannel(channel.id, source.id, channel.shownName, source.name)
    } else null
}
