/* Nuvix Copyright (C) 2026 Nuvix Contributors. GPL-3.0-or-later. */
package app.opentv.ui.multiview

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.room.InvalidationTracker
import app.opentv.core.ServiceLocator
import app.opentv.data.model.shownName
import app.opentv.multiview.*
import app.opentv.recording.RecordingConflictAlternatives
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date

/** One consolidated, catalog-only preview. Only confirmed loading calls the session manager. */
@Composable
internal fun MultiViewPresetReviewDialog(
    manager: MultiViewSessionManager,
    store: MultiViewPresetStore,
    presentation: MultiViewPresentation,
    title: String,
    onDismiss: () -> Unit,
    onRestored: () -> Unit,
    preset: MultiViewPreset? = null,
) {
    val graph = ServiceLocator.get(LocalContext.current)
    val scope = rememberCoroutineScope()
    var proposal by remember(presentation) { mutableStateOf(presentation) }
    val currentProposal by rememberUpdatedState(proposal)
    var selectedMatches by remember { mutableStateOf<Map<String, MultiViewChannelReference>>(emptyMap()) }
    val currentMatches by rememberUpdatedState(selectedMatches)
    var changePanel by remember { mutableStateOf<String?>(null) }
    var alternativesFor by remember { mutableStateOf<String?>(null) }
    var alternatives by remember { mutableStateOf<List<PresetAlternative>>(emptyList()) }
    var alternativesLoading by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    val inspector = remember(graph, manager) { MultiViewPresetInspector(graph,
        replacedReservations = { manager.playbackReferences().map { it.token.reservationId }.toSet() }) }
    val controller = remember(inspector, presentation) { MultiViewPresetPreviewController(inspect = { proposed ->
        val checked = inspector.inspect(proposed)
        val rejected = mutableSetOf<String>()
        val replaced = manager.playbackReferences().map { it.token.reservationId }.toSet()
        val at = System.currentTimeMillis()
        for ((panelId, original) in currentMatches) {
            val target = proposed.panels.firstOrNull { it.panelId == panelId } ?: continue
            val key = graph.connections.accountKeyForSource(original.sourceId)
            val stillMatches = key != null && withContext(Dispatchers.IO) {
                RecordingConflictAlternatives(graph).choices(original.channelId, key, at,
                    planningUntil = RecordingPlanningWindow().end(at), ignoredReservations = replaced)
                    .any { it.channel.id == target.channel.channelId && it.channel.sourceId == target.channel.sourceId }
            }
            if (!stillMatches) rejected += panelId
        }
        checked.copy(plan = checked.plan.copy(unavailablePanelIds = checked.plan.unavailablePanelIds + rejected))
    }) { checked ->
        val preferences = graph.settings.multiViewPreferences.value
        if (preferences.savePresentationPreferences) graph.settings.setMultiViewPreferences(preferences.copy(showLabels = checked.showLabels))
        manager.restorePresentation(checked)
    } }
    val review by controller.state.collectAsState()
    val accounts by graph.connections.snapshots.collectAsState()
    val hidden by graph.settings.hiddenCategories.collectAsState()
    val unlocked by graph.settings.hiddenUnlocked.collectAsState()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var foreground by remember { mutableStateOf(lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) }
    var databaseRevision by remember { mutableLongStateOf(0) }
    DisposableEffect(graph, controller, lifecycle) {
        val observer = object : InvalidationTracker.Observer("sources", "channels", "categories", "sports_events", "sports_programmes", "sports_decisions") {
            override fun onInvalidated(tables: Set<String>) { scope.launch {
                controller.invalidate(); databaseRevision++
            } }
        }
        val lifecycleObserver = LifecycleEventObserver { _, _ ->
            foreground = lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
            controller.invalidate(); databaseRevision++
        }
        graph.database.invalidationTracker.addObserver(observer)
        lifecycle.addObserver(lifecycleObserver)
        onDispose {
            controller.invalidate()
            graph.database.invalidationTracker.removeObserver(observer)
            lifecycle.removeObserver(lifecycleObserver)
        }
    }
    LaunchedEffect(graph, controller) {
        // Captured-byte progress cannot change the recording plan.
        graph.recordingRepository.observeAll().map { rows -> rows.map { it.copy(sizeBytes = 0) } }
            .distinctUntilChanged().collect { controller.invalidate(); databaseRevision++ }
    }
    LaunchedEffect(proposal, accounts, hidden, unlocked, databaseRevision, foreground) {
        controller.invalidate()
        if (foreground) {
            controller.refresh(proposal)
            while (true) { delay(30_000); controller.refresh(currentProposal) }
        }
    }
    fun edit(next: MultiViewPresentation) {
        selectedMatches = selectedMatches.filter { (id, _) ->
            proposal.panels.firstOrNull { it.panelId == id }?.channel == next.panels.firstOrNull { it.panelId == id }?.channel
        }
        controller.invalidate(); proposal = next; alternativesFor = null; alternatives = emptyList(); message = null
    }
    LaunchedEffect(alternativesFor, proposal, accounts, databaseRevision, hidden, unlocked) {
        alternatives = emptyList()
        val panel = proposal.panels.firstOrNull { it.panelId == alternativesFor } ?: return@LaunchedEffect
        val account = accounts.firstOrNull { panel.channel.sourceId in it.sourceIds } ?: return@LaunchedEffect
        alternativesLoading = true
        try {
            // Reuse exact guide / confirmed Sports identities, then assess the entire proposed wall.
            val replaced = manager.playbackReferences().map { it.token.reservationId }.toSet()
            val at = System.currentTimeMillis()
            val choices = withContext(Dispatchers.IO) {
                RecordingConflictAlternatives(graph).choices(panel.channel.channelId, account.accountKey, at,
                    planningUntil = RecordingPlanningWindow().end(at), ignoredReservations = replaced)
            }.take(40)
            val candidates = choices.map { choice ->
                val key = graph.connections.accountKeyForSource(choice.channel.sourceId)
                proposal.withChannel(panel.panelId, MultiViewChannelReference(choice.channel.id, choice.channel.sourceId, key))
            }
            alternatives = inspector.inspectAll(candidates).zip(choices).mapNotNull { (candidate, choice) ->
                if (!candidate.canLoad || candidate.plan.conflicts.any { panel.panelId in it.panelIds }) null
                else PresetAlternative(candidate.presentation.panels.first { it.panelId == panel.panelId }.channel,
                    choice.channel.shownName, choice.providerName, choice.capacity, choice.verified)
            }

        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { message = "Authorized alternatives could not be checked. Refresh or choose a channel explicitly." }
        finally { alternativesLoading = false }
    }
    PresetReviewContent(title, proposal, review, alternativesFor, alternatives, alternativesLoading, message,
        canSave = preset != null && proposal != preset.presentation && !review.busy,
        onRemove = { id -> edit(proposal.withoutPanel(id)) },
        onPrimary = { id -> edit(proposal.copy(primaryPanelId = id)) },
        onReplace = { changePanel = it }, onAlternatives = { alternativesFor = it },
        onAlternative = { panelId, ref ->
            val original = selectedMatches[panelId] ?: proposal.panels.first { it.panelId == panelId }.channel
            edit(proposal.withChannel(panelId, ref))
            selectedMatches = selectedMatches + (panelId to original)
        },
        onReview = { id -> app.opentv.core.RecordingSignals.requestReview(id) },
        onSave = {
            val savedPreset = preset ?: return@PresetReviewContent
            val proposed = proposal
            scope.launch {
                try {
                    // Saving never authorizes playback. Revalidate references before persisting.
                    val checked = inspector.inspect(proposed)
                    if (currentProposal != proposed || checked.plan.unavailablePanelIds.isNotEmpty()) {
                        message = "The proposal changed or contains unavailable channels. Review it before saving."
                    } else {
                        val saved = withContext(Dispatchers.IO) { store.upsert(savedPreset.name, checked.presentation, savedPreset.id) }
                        message = if (saved != null) "Changes saved to preset." else "The preset could not be saved."
                    }
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { message = "The preset could not be checked or saved. Refresh and try again." }

            }
        },
        onRefresh = { scope.launch { controller.refresh(proposal) } },
        onLoad = { scope.launch { if (controller.confirm(proposal)) onRestored() } },
        onDismiss = onDismiss)
    changePanel?.let { id -> MultiViewChannelPicker(
        title = "Replace proposed channel — no playback", onDismiss = { changePanel = null },
        onSelect = { channel ->
            edit(proposal.withChannel(id, MultiViewChannelReference(channel.id, channel.sourceId)))
            changePanel = null
        }, onOpenGuide = { changePanel = null }, selectionHint = "Selecting changes this proposal only; no stream opens",
    ) }
}

/** URL-free alternative values; explicit selection is required. */
internal data class PresetAlternative(val reference: MultiViewChannelReference, val name: String,
    val provider: String, val capacity: String, val confirmedSports: Boolean)

@Composable
internal fun PresetReviewContent(
    title: String, proposal: MultiViewPresentation, review: MultiViewPresetReviewState,
    alternativesFor: String? = null, alternatives: List<PresetAlternative> = emptyList(),
    alternativesLoading: Boolean = false, message: String? = null, canSave: Boolean = false,
    onRemove: (String) -> Unit, onPrimary: (String) -> Unit, onReplace: (String) -> Unit,
    onAlternatives: (String) -> Unit, onAlternative: (String, MultiViewChannelReference) -> Unit,
    onReview: (Long) -> Unit, onSave: () -> Unit, onRefresh: () -> Unit,
    onLoad: () -> Unit, onDismiss: () -> Unit,
) {
    val checked = review.inspection
    val plan = checked?.plan
    val first = remember { FocusRequester() }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxWidth(0.9f).fillMaxHeight(0.9f), shape = MaterialTheme.shapes.large) {
            Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(title, style = MaterialTheme.typography.headlineSmall)
                Text("${proposal.panels.size} channels · ${proposal.layout.name.lowercase()} · Next 24 hours")
                PresetReviewButton("Cancel", Modifier.focusRequester(first), onClick = onDismiss)
                LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    item {
                        Text("Prediction only. Provider-reported and configured limits describe local admission; other devices and provider behavior make availability uncertain. No streams open during preview.")
                        (message ?: review.message)?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
                        if (checked == null) Text("Checking local catalog and recordings… Use Refresh if unavailable.")
                    }
                    items(proposal.panels, key = { it.panelId }) { panel ->
                        val channel = checked?.channels?.firstOrNull { it.channelId == panel.channel.channelId && it.sourceId == panel.channel.sourceId }
                        Text("${channel?.channelName ?: "Unavailable channel"} · ${channel?.providerName ?: "Unavailable provider"}${if (panel.panelId == proposal.primaryPanelId) " · Primary" else ""}", style = MaterialTheme.typography.titleMedium)
                        if (panel.panelId in plan?.unavailablePanelIds.orEmpty()) Text("Channel unavailable, duplicated, account changed or broadcast match expired. Replace or exclude this panel before loading.", color = MaterialTheme.colorScheme.error)
                        PresetReviewButton("Replace Channel: ${channel?.channelName ?: panel.panelId}", enabled = !review.busy) { onReplace(panel.panelId) }
                        PresetReviewButton("Choose Another Provider: ${channel?.channelName ?: panel.panelId}", enabled = checked != null && !review.busy) { onAlternatives(panel.panelId) }
                        PresetReviewButton("Exclude Panel: ${channel?.channelName ?: panel.panelId}", enabled = !review.busy) { onRemove(panel.panelId) }
                        if (panel.panelId != proposal.primaryPanelId) PresetReviewButton("Make Primary: ${channel?.channelName ?: panel.panelId}", enabled = !review.busy) { onPrimary(panel.panelId) }
                        if (alternativesFor == panel.panelId) {
                            Text("Confirmed Sports or exact guide identities only. Language/commentary may differ; review your choice. Future provider availability is not guaranteed.")
                            if (alternativesLoading) Text("Checking authorized alternatives…")
                            else if (alternatives.isEmpty()) Text("No eligible independent broadcast has predicted capacity. Replace Channel lets you choose a different broadcast explicitly.")
                            alternatives.forEach { choice -> PresetReviewButton("${choice.name} · ${choice.provider}\n${choice.capacity} · ${if (choice.confirmedSports) "Confirmed Sports broadcast" else "Exact guide identity"}", enabled = !review.busy) { onAlternative(panel.panelId, choice.reference) } }
                        }
                    }
                    plan?.let { forecast ->
                        item { HorizontalDivider(); Text("Local connection requirements", style = MaterialTheme.typography.titleMedium) }
                        items(forecast.accounts) { account ->
                            val providers = checked.channels.filter { it.sourceId in account.sourceIds }.map { it.providerName }.distinct().joinToString(" / ")
                            Text("$providers: ${account.currentLocal} currently reserved; ${account.retainedLocal} retained + ${account.proposedAdditional} proposed after replacing this wall. Allowance: ${account.maximum ?: "Unknown"} (${account.allowance.name.lowercase().replace('_', ' ')}).")
                            if (account.maximum == null) Text("Unknown capacity: additional streams require individual approval. Unknown does not mean unlimited.")
                            if (account.currentCapacityExceeded) Text("Immediate conflict: current playback and recording demand exceed the configured allowance. Exclude or replace a panel.", color = MaterialTheme.colorScheme.error)
                        }
                        if (forecast.conflicts.isEmpty()) item { Text("No known upcoming recording conflict within this window. Provider-side availability remains uncertain.") }
                        items(forecast.conflicts) { conflict ->
                            val names = proposal.panels.filter { it.panelId in conflict.panelIds }.map { panel -> checked.channels.firstOrNull { it.channelId == panel.channel.channelId }?.channelName ?: panel.panelId }
                            val recordings = forecast.recordings.filter { it.id in conflict.recordingIds }
                            Text("${if (conflict.kind == PresetConflictKind.IMMEDIATE) "Immediate" else "Upcoming"} Recording Conflict", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.error)
                            Text("${recordings.joinToString { it.title }} needs account capacity at ${presetTime(conflict.atMillis)}. Affected panels: ${names.joinToString()}.")
                        }
                        items(forecast.recordings, key = { it.id }) { rec ->
                            Text("${rec.title} · ${rec.channelName}\nExpected capture: ${presetTime(rec.startMillis)} – ${presetTime(rec.endMillis)}\nProtect Recording: ${if (rec.protectedRecording) "On" else "Off"} · Lock Padding: ${if (rec.lockPadding) "On" else "Off"} · ${if (rec.automaticSeries) "Automatic series" else "Manual"}${if (rec.paddingAdjusted) "\nEffective padding reflects existing conflict fallback." else ""}")
                            PresetReviewButton("Review Recording: ${rec.title}") { onReview(rec.id) }
                        }
                    }
                    item {
                        Text("At the deadline Nuvix releases conflicting previews, then secondary panels, then the primary if necessary. Recording priority remains authoritative. An additional pre-recording warning may appear; acknowledging this preview does not dismiss it.")
                        Text("Loading rechecks channels, accounts, schedules and capacity. The primary starts first; secondaries follow incrementally with atomic reservations and required Unknown approvals. Decoder capacity is checked during startup.")
                        if (canSave) PresetReviewButton("Save Changes to Preset", enabled = !review.busy, onClick = onSave)
                        PresetReviewButton("Refresh", enabled = !review.busy, onClick = onRefresh)
                        PresetReviewButton(if (plan?.conflicts?.isNotEmpty() == true) "Continue With Warning — Load Modified Layout Once" else "Load Modified Layout Once",
                            enabled = checked?.canLoad == true && !review.busy, onClick = onLoad)
                    }
                }
            }
        }
        LaunchedEffect(Unit) { first.requestFocus() }
    }
}

private fun presetTime(at: Long): String = if (at == Long.MAX_VALUE) "Until stopped" else DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(at))

@Composable
private fun PresetReviewButton(label: String, modifier: Modifier = Modifier, enabled: Boolean = true, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    OutlinedButton(onClick = onClick, enabled = enabled, modifier = modifier.fillMaxWidth().onFocusChanged { focused = it.isFocused },
        border = BorderStroke(if (focused) 3.dp else 1.dp, if (focused) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline)) { Text(label) }
}
