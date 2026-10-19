/* Copyright (C) 2026 Nuvix Contributors. GPL-3.0-or-later. */
package app.opentv.ui.multiview

import androidx.activity.compose.BackHandler
import androidx.annotation.OptIn
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import app.opentv.core.ServiceLocator
import app.opentv.multiview.*

/** Session allocation is exclusively owned by [manager]; composing a panel only leases a surface. */
@Composable
fun MultiViewScreen(
    manager: MultiViewSessionManager,
    onExit: () -> Unit,
    onOpenGuide: () -> Unit,
    modifier: Modifier = Modifier,
    showApproval: Boolean = true,
    activeRecordings: List<app.opentv.data.model.Recording> = emptyList(),
) {
    val state by manager.state.collectAsState()
    val context = LocalContext.current
    val graph = remember(context) { ServiceLocator.get(context) }
    val preferences by graph.settings.multiViewPreferences.collectAsState()
    val inPip by app.opentv.core.PipState.inPip.collectAsState()
    var editing by remember { mutableStateOf(false) }
    var actions by remember { mutableStateOf<String?>(null) }
    var picker by remember { mutableStateOf(false) }
    var replacement by remember { mutableStateOf<String?>(null) }
    var alternativeFor by remember { mutableStateOf<Long?>(null) }
    var presets by remember { mutableStateOf(false) }
    var chooseLayout by remember { mutableStateOf(false) }
    val recoveryStore = remember(context) { MultiViewPresetStore(context) }
    var interrupted by remember { mutableStateOf(if (state.panels.isEmpty() && preferences.enabled && preferences.recovery == MultiViewRecoveryPolicy.ASK_BEFORE_RESTORE) recoveryStore.peekRecovery() else null) }
    DisposableEffect(recoveryStore) { onDispose { recoveryStore.close() } }
    val focused = state.panels.firstOrNull { it.id == state.focusedPanelId } ?: state.panels.firstOrNull()

    if (inPip) {
        MultiViewCanvas(manager, state, showLabels = false, modifier = modifier.fillMaxSize())
        return
    }

    BackHandler {
        when {
            editing -> editing = false
            state.fullscreenPanelId != null -> manager.leaveFullscreen()
            else -> onExit()
        }
    }
    Column(modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(12.dp)) {
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically,
        ) {
            activeRecordings.filter { it.status == app.opentv.data.model.RecordingStatus.RECORDING }.forEach { recording ->
                OutlinedButton(onClick = { app.opentv.core.RecordingSignals.requestDuration(recording.id) }) { Text("Recording Duration: ${recording.title}") }
            }
            Text(if (editing) "Edit Layout" else "Multi-View", style = MaterialTheme.typography.titleLarge)
            if (editing) {
                Button(onClick = { editing = false }, modifier = Modifier.testTag("multiview-done")) { Text("Done") }
            } else {
                Button(onClick = { replacement = null; alternativeFor = null; picker = true }, enabled = state.panels.size < 4) { Text("Add Channel") }
                OutlinedButton(onClick = { actions = focused?.id }, enabled = focused != null) { Text("Panel Actions") }
                OutlinedButton(onClick = { manager.setMuted(!state.muted) }, enabled = state.panels.isNotEmpty()) { Text(if (state.muted) "Unmute" else "Mute") }
                if (state.panels.any { it.status == MultiViewPanelStatus.PAUSED } && state.fullscreenPanelId == null) {
                    OutlinedButton(onClick = manager::resume) { Text("Resume Channels") }
                }
                OutlinedButton(onClick = { editing = true }, enabled = state.panels.size > 1) { Text("Edit Layout") }
                OutlinedButton(onClick = { chooseLayout = true }, enabled = state.panels.isNotEmpty()) { Text("Layout") }
                OutlinedButton(onClick = onOpenGuide) { Text("Guide") }
                OutlinedButton(onClick = { presets = true }) { Text("Presets") }
                OutlinedButton(onClick = onExit) { Text("Exit Multi-View") }
            }
        }
        Text(
            when {
                editing -> "Focus a panel, then use the D-pad to move it. Select Done or press Back to finish."
                state.fullscreenPanelId != null -> "Back returns to Multi-View. Panel Actions offers channel controls."
                preferences.audioMode == MultiViewAudioMode.PRESS_OK -> "D-pad selects a panel. OK switches audio. Panel Actions opens controls."
                else -> "D-pad selects a panel and switches audio after ${preferences.focusDelayMs} ms. Panel Actions opens controls."
            },
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(vertical = 8.dp),
        )
        state.notice?.let { notice ->
            Row(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant).padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(notice, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                TextButton(onClick = manager::dismissNotice) { Text("Dismiss") }
            }
        }
        if (focused?.repeatedBuffering == true && preferences.bufferingSuggestions) Row(
            Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("${focused.channelName} is repeatedly buffering.", Modifier.weight(1f), style = MaterialTheme.typography.labelMedium)
            TextButton(onClick = { replacement = focused.id; alternativeFor = focused.channelId; picker = true }) { Text("Browse Alternative Broadcasts") }
        }
        if (state.panels.isEmpty()) {
            Column(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Watch two to four live channels together", style = MaterialTheme.typography.headlineSmall)
                Text("Add channels from your providers, favorites, recent channels, Guide or Sports Hub.", Modifier.padding(12.dp))
                Button(onClick = { replacement = null; alternativeFor = null; picker = true }) { Text("Choose First Channel") }
            }
        } else MultiViewCanvas(
            manager = manager, state = state, showLabels = preferences.showLabels, editing = editing,
            onActions = { actions = it }, modifier = Modifier.weight(1f).fillMaxWidth(),
        )
    }

    if (picker) MultiViewChannelPicker(
        title = if (replacement == null) "Add Channel to Multi-View" else "Replace Selected Panel",
        alternativeFor = alternativeFor,
        onDismiss = { picker = false },
        onSelect = { channel ->
            picker = false
            replacement?.let { manager.replace(it, channel.id) } ?: manager.add(channel.id)
        },
        onOpenGuide = { picker = false; onOpenGuide() },
    )
    actions?.let { id ->
        val panel = state.panels.firstOrNull { it.id == id }
        if (panel != null) AlertDialog(
            onDismissRequest = { actions = null }, title = { Text(panel.channelName) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(panel.providerName)
                    Button(onClick = { manager.selectAudio(id); actions = null }, modifier = Modifier.fillMaxWidth()) { Text("Listen to This Panel") }
                    OutlinedButton(onClick = { manager.enterFullscreen(id); actions = null }, modifier = Modifier.fillMaxWidth()) { Text("Watch Fullscreen") }
                    OutlinedButton(onClick = { replacement = id; alternativeFor = null; picker = true; actions = null }, modifier = Modifier.fillMaxWidth()) { Text("Replace Channel") }
                    if (panel.repeatedBuffering && preferences.bufferingSuggestions) OutlinedButton(onClick = {
                        replacement = id; alternativeFor = panel.channelId; picker = true; actions = null
                    }, modifier = Modifier.fillMaxWidth()) { Text("Browse Alternative Broadcasts") }
                    if (panel.status == MultiViewPanelStatus.PAUSED && panel.error?.contains("recording", ignoreCase = true) == true) OutlinedButton(onClick = {
                        replacement = id; alternativeFor = panel.channelId; picker = true; actions = null
                    }, modifier = Modifier.fillMaxWidth()) { Text("Choose Authorized Alternative") }
                    OutlinedButton(onClick = { manager.retry(id); actions = null }, modifier = Modifier.fillMaxWidth()) { Text("Retry Playback") }
                    if (panel.failureKind == MultiViewFailureKind.DECODER) OutlinedButton(onClick = {
                        manager.remove(id); actions = null
                    }, modifier = Modifier.fillMaxWidth()) { Text("Use Smaller Layout") }
                    OutlinedButton(onClick = { manager.setPrimary(id); actions = null }, modifier = Modifier.fillMaxWidth()) { Text("Make Primary Panel") }
                    OutlinedButton(onClick = { manager.remove(id); actions = null }, modifier = Modifier.fillMaxWidth()) { Text("Remove Panel") }
                }
            }, confirmButton = { TextButton(onClick = { actions = null }) { Text("Done") } },
        )
    }
    if (chooseLayout) AlertDialog(
        onDismissRequest = { chooseLayout = false }, title = { Text("Video Layout") },
        text = {
            Column {
                Text("The balanced layout follows the number of channels. Focused makes the primary panel larger.")
                TextButton(onClick = {
                    manager.setLayout(when (state.panels.size) { 2 -> MultiViewLayout.TWO; 3 -> MultiViewLayout.THREE; else -> MultiViewLayout.GRID })
                    chooseLayout = false
                }) { Text("Balanced") }
                TextButton(onClick = { manager.setLayout(MultiViewLayout.FOCUSED); chooseLayout = false }) { Text("Focused") }
            }
        }, confirmButton = { TextButton(onClick = { chooseLayout = false }) { Text("Cancel") } },
    )
    state.approval?.takeIf { showApproval }?.let { approval -> AlertDialog(
        onDismissRequest = { manager.answerApproval(false) }, title = { Text("Unknown Provider Allowance") },
        text = { Text("${approval.providerName} has no confirmed connection allowance. Open an additional connection for ${approval.channelName}? This approval applies only to this request. Active recordings keep their reservations.") },
        confirmButton = { Button(onClick = { manager.answerApproval(true) }) { Text("Open This Stream") } },
        dismissButton = { TextButton(onClick = { manager.answerApproval(false) }) { Text("Cancel") } },
    ) }
    state.duplicate?.let { duplicate -> AlertDialog(
        onDismissRequest = { manager.resolveDuplicate(MultiViewDuplicateChoice.CANCEL) }, title = { Text("Channel Already Playing") },
        text = {
            Column {
                Text("Choose an existing panel without opening a duplicate stream.")
                Button(onClick = { manager.resolveDuplicate(MultiViewDuplicateChoice.FOCUS_EXISTING) }) { Text("Focus Existing Panel") }
                OutlinedButton(onClick = { manager.resolveDuplicate(MultiViewDuplicateChoice.REPLACE_SELECTED) }, enabled = duplicate.targetPanelId != null) { Text("Replace Selected Panel") }
            }
        }, confirmButton = { TextButton(onClick = { manager.resolveDuplicate(MultiViewDuplicateChoice.CANCEL) }) { Text("Cancel") } },
    ) }
    if (state.restorationRequired) AlertDialog(
        onDismissRequest = { manager.leaveFullscreen(false) }, title = { Text("Restore Multi-View?") },
        text = { Text("Restore the secondary channels progressively? Provider allowances and device resources will be checked again. Unknown allowances require a new approval.") },
        confirmButton = { Button(onClick = { manager.leaveFullscreen(true) }) { Text("Restore Channels") } },
        dismissButton = { TextButton(onClick = { manager.leaveFullscreen(false) }) { Text("Keep One Channel") } },
    )
    if (presets) MultiViewPresetsDialog(manager, onDismiss = { presets = false })
    interrupted?.let { presentation -> MultiViewPresetReviewDialog(
        manager = manager, store = recoveryStore, presentation = presentation, title = "Review Interrupted Multi-View",
        onDismiss = { interrupted = null; recoveryStore.clearRecovery() },
        onRestored = { interrupted = null },
    ) }
}

/** Compact Guide presentation attaches the same engines and deliberately leaves audio alone. */
@Composable
fun MultiViewCompactPreview(
    manager: MultiViewSessionManager,
    onFocusMultiView: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by manager.state.collectAsState()
    val context = LocalContext.current
    val preferences by remember(context) { ServiceLocator.get(context).settings }.multiViewPreferences.collectAsState()
    if (state.panels.isEmpty() || !preferences.compactGuide) return
    Column(modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("Multi-View · ${state.panels.size} channels", style = MaterialTheme.typography.titleMedium)
            OutlinedButton(onClick = onFocusMultiView, modifier = Modifier.testTag("focus-multiview")) { Text("Focus Multi-View") }
        }
        // Surface views cannot be clicked or focused here; normal Guide navigation never changes audio.
        MultiViewCanvas(manager, state, preferences.showLabels, compact = true, modifier = Modifier.fillMaxWidth().height(175.dp))
    }
}

@Composable
private fun MultiViewCanvas(
    manager: MultiViewSessionManager,
    state: MultiViewState,
    showLabels: Boolean,
    modifier: Modifier = Modifier,
    editing: Boolean = false,
    compact: Boolean = false,
    onActions: (String) -> Unit = {},
) {
    val focusRequesters = remember { mutableMapOf<String, FocusRequester>() }
    SideEffect { focusRequesters.keys.retainAll(state.panels.map { it.id }.toSet()) }
    BoxWithConstraints(modifier.testTag(if (compact) "multiview-compact" else "multiview-canvas")) {
        val panels = state.panels
        val primaryIndex = panels.indexOfFirst { it.id == state.primaryPanelId }.coerceAtLeast(0)
        val bounds = multiViewBounds(panels.size, state.layout, primaryIndex)
        panels.forEachIndexed { index, panel -> key(panel.id) {
            val fullscreen = state.fullscreenPanelId
            val visible = compact || fullscreen == null || fullscreen == panel.id
            val rect = if (fullscreen == panel.id && !compact) PanelBounds(0f, 0f, 1f, 1f) else bounds[index]
            val x by animateDpAsState(maxWidth * rect.x, tween(180), label = "panel-x")
            val y by animateDpAsState(maxHeight * rect.y, tween(180), label = "panel-y")
            val width by animateDpAsState(maxWidth * rect.width, tween(180), label = "panel-width")
            val height by animateDpAsState(maxHeight * rect.height, tween(180), label = "panel-height")
            val requester = focusRequesters.getOrPut(panel.id) { FocusRequester() }
            if (visible) MultiViewPanelVideo(
                manager, panel,
                audible = state.audioPanelId == panel.id && !state.muted && state.audioFocusGranted,
                selected = !compact && state.focusedPanelId == panel.id,
                showLabels = showLabels,
                modifier = Modifier.offset(x, y).size(width, height).padding(3.dp)
                    .then(if (compact) Modifier else Modifier.focusRequester(requester)
                        .onFocusChanged { if (it.isFocused) manager.focus(panel.id) }
                        .onPreviewKeyEvent { event ->
                            if (event.type != KeyEventType.KeyDown) false
                            else if (event.key == Key.Menu || event.key == Key.F2) { onActions(panel.id); true }
                            else if (editing) {
                                val direction = when (event.key) {
                                    Key.DirectionLeft -> -1 to 0
                                    Key.DirectionRight -> 1 to 0
                                    Key.DirectionUp -> 0 to -1
                                    Key.DirectionDown -> 0 to 1
                                    else -> null
                                }
                                if (direction == null) false else {
                                    val offset = panelMoveOffset(index, panels.size, state.layout, direction.first, direction.second)
                                    if (offset == 0) false else { manager.move(panel.id, offset); true }
                                }
                            } else false
                        }.clickable(onClickLabel = "Listen to ${panel.channelName}") { manager.selectAudio(panel.id) }),
            )
        } }
    }
    LaunchedEffect(state.focusedPanelId, compact) {
        if (!compact) state.focusedPanelId?.let { focusRequesters[it]?.let { requester -> runCatching { requester.requestFocus() } } }
    }
}

@OptIn(UnstableApi::class)
@Composable
private fun MultiViewPanelVideo(
    manager: MultiViewSessionManager,
    panel: MultiViewPanel,
    audible: Boolean,
    selected: Boolean,
    showLabels: Boolean,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(8.dp)
    val owner = manager.surfaceOwner(panel.id)
    var surfaceAvailable by remember(owner) { mutableStateOf(true) }
    Box(
        modifier.clip(shape).background(Color.Black)
            .border(if (selected) 3.dp else 1.dp, if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline, shape)
            .testTag("multiview-panel-${panel.id}")
            .semantics { contentDescription = "${panel.channelName}, ${panel.providerName}${if (audible) ", active audio" else ", muted"}${if (selected) ", selected" else ""}" },
        contentAlignment = Alignment.Center,
    ) {
        if (panel.engineReady && owner != null) key(owner) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { context -> PlayerView(context).apply {
                    useController = false
                    isFocusable = false
                    isFocusableInTouchMode = false
                    resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                    setShutterBackgroundColor(android.graphics.Color.BLACK)
                    surfaceAvailable = runCatching { owner.attach(this) }.getOrDefault(false)
                } },
                onRelease = { owner.detach(it) }, update = {},
            )
        }
        if (!surfaceAvailable) Text("This device cannot move this video surface. Return to the previous view or retry the panel.", Modifier.padding(16.dp), color = Color.White)
        if (panel.status == MultiViewPanelStatus.STARTING || panel.status == MultiViewPanelStatus.BUFFERING) CircularProgressIndicator(Modifier.size(30.dp))
        if (panel.status == MultiViewPanelStatus.FAILED || panel.status == MultiViewPanelStatus.PAUSED) {
            Column(Modifier.background(Color.Black.copy(alpha = 0.85f)).padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(panel.error ?: "Playback paused", color = Color.White, style = MaterialTheme.typography.bodyMedium)
                Text("Panel Actions: Retry · Replace Channel · Remove Panel", color = Color.LightGray, style = MaterialTheme.typography.labelSmall)
            }
        }
        if (showLabels || audible) Row(
            Modifier.align(Alignment.BottomStart).fillMaxWidth().background(Color.Black.copy(alpha = 0.78f)).padding(8.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (audible) Icon(Icons.Default.VolumeUp, "Active audio", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
            if (showLabels) Column(Modifier.weight(1f)) {
                Text(panel.channelName, maxLines = 1, overflow = TextOverflow.Ellipsis, color = Color.White, style = MaterialTheme.typography.labelLarge)
                Text(panel.providerName, maxLines = 1, overflow = TextOverflow.Ellipsis, color = Color.LightGray, style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}
