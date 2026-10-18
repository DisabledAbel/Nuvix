/* Nuvix Copyright (C) 2026 Nuvix Contributors. GPL-3.0-or-later. */
package app.opentv.ui.recordings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import app.opentv.core.ServiceLocator
import app.opentv.data.model.shownName
import app.opentv.multiview.MultiViewSessionManager
import app.opentv.recording.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

@Composable
fun RecordingConflictHost(coordinator: RecordingConflictCoordinator, manager: MultiViewSessionManager) {
    val warning by coordinator.warning.collectAsState()
    val state by manager.state.collectAsState()
    val current = warning ?: return
    val reference = current.panels.firstOrNull() ?: return
    val panel = state.panels.firstOrNull { it.id == reference.panelId } ?: return
    val context = LocalContext.current
    val graph = remember(context) { ServiceLocator.get(context) }
    val scope = rememberCoroutineScope()
    val key = current.conflict.key
    var recordingProvider by remember(current.conflict.recording.sourceId) { mutableStateOf("Recording provider") }
    LaunchedEffect(current.conflict.recording.sourceId) {
        recordingProvider = graph.sourceRepository.byId(current.conflict.recording.sourceId)?.name ?: "Unavailable provider"
    }
    var choose by remember(key, reference) { mutableStateOf(false) }
    var details by remember(key) { mutableStateOf(false) }
    var busy by remember(key, reference.panelId) { mutableStateOf(false) }
    var error by remember(key) { mutableStateOf<String?>(null) }
    var choices by remember(key, reference) { mutableStateOf<List<RecordingConflictAlternative>>(emptyList()) }
    var loading by remember(key, reference) { mutableStateOf(false) }
    LaunchedEffect(choose, reference) {
        if (choose) {
            loading = true
            try { choices = RecordingConflictAlternatives(graph).choices(reference.channelId, reference.token.accountKey, System.currentTimeMillis(), current.conflict.effectiveStartMillis) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { error = "Broadcast choices could not be loaded. Keep watching or release this panel." }
            finally { loading = false }
        }
    }
    // The ordinary Multi-View screen owns its normal consent dialog. This global host also
    // supports Guide/fullscreen by supplying consent for warning-initiated switches here.
    if (busy && state.approval != null) {
        val prompt = state.approval!!
        AlertDialog(onDismissRequest = { manager.answerApproval(false) },
            title = { Text("Open an additional connection?") },
            text = { Text("${prompt.providerName}: the allowance is unknown. Open ${prompt.channelName} for this request? Other devices may also be using the account.") },
            confirmButton = { TextButton(onClick = { manager.answerApproval(true) }) { Text("Open once") } },
            dismissButton = { TextButton(onClick = { manager.answerApproval(false) }) { Text("Cancel") } })
        return
    }
    RecordingConflictDialog(current, panel.channelName, panel.providerName,
        recordingProviderName = recordingProvider,
        primary = state.primaryPanelId == reference.panelId, choose = choose, details = details,
        alternatives = choices, loading = loading, error = error ?: panel.error, busy = busy,
        onSwitch = { choose = true }, onDetails = { details = !details },
        onKeep = { coordinator.keepWatching(key) },
        onRelease = { scope.launch { coordinator.release(key, reference) } },
        onAlternative = { selected ->
            scope.launch {
                busy = true
                try {
                    val validated = RecordingConflictAlternatives(graph).choices(reference.channelId, reference.token.accountKey, System.currentTimeMillis(), current.conflict.effectiveStartMillis)
                        .firstOrNull { it.channel.id == selected.channel.id && it.channel.sourceId == selected.channel.sourceId }
                    if (validated == null || !coordinator.switch(key, reference, selected.channel.id)) {
                        error = "This choice changed or no longer has capacity. Choose again or keep watching."
                        busy = false
                    } else { choose = false }
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { error = "The provider could not be validated. Your current panel is retained."; busy = false }
            }
        })
    LaunchedEffect(reference, state.approval, panel.error) {
        if (state.approval == null && panel.error != null) busy = false
    }
}

/** Explicit focusable remote actions; Back means keep watching until the deadline. */
@Composable
internal fun RecordingConflictDialog(
    warning: RecordingConflictWarning,
    channelName: String,
    providerName: String,
    primary: Boolean,
    recordingProviderName: String = providerName,
    choose: Boolean = false,
    details: Boolean = false,
    alternatives: List<RecordingConflictAlternative> = emptyList(),
    loading: Boolean = false,
    error: String? = null,
    busy: Boolean = false,
    onSwitch: () -> Unit,
    onRelease: () -> Unit,
    onDetails: () -> Unit,
    onKeep: () -> Unit,
    onAlternative: (RecordingConflictAlternative) -> Unit = {},
) {
    val first = remember { FocusRequester() }
    val rec = warning.conflict.recording
    val role = if (primary) "Primary Panel" else "Secondary Panel"
    Dialog(onDismissRequest = onKeep) {
        Surface(shape = MaterialTheme.shapes.large) {
            Column(Modifier.widthIn(max = 620.dp).verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Recording Starting Soon", style = MaterialTheme.typography.headlineSmall)
                Text("\"${rec.title}\" needs capacity used by your $role: $channelName.")
                Text("Viewing provider: $providerName\nRecording provider: $recordingProviderName · ${warning.conflict.account.allowanceLabel} connection allowance")
                Text("Recording begins at ${DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(warning.conflict.effectiveStartMillis))} · ${((warning.conflict.effectiveStartMillis - warning.nowMillis).coerceAtLeast(0) + 999) / 1_000} seconds remaining")
                Text("At the deadline: release conflicting previews first, then secondary panels, then the primary only if necessary. Existing recording priority applies; other accounts continue.")
                if (warning.conflict.unknownAllowance) Text("This account's allowance is unknown. Extra recording capacity is not confirmed; viewing may yield for the first unattended connection.")
                if (details) {
                    Text("Recording channel: ${rec.channelName}\nProtect Recording: ${if (rec.protectedRecording) "On" else "Off"}\nLock Padding: ${if (rec.lockPadding) "On" else "Off"}\nStart padding: ${rec.padStartMinutes} min · End padding: ${rec.padEndMinutes} min")
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                if (choose) {
                    Text("Choose a matching broadcast from an independent account.")
                    if (loading) Text("Checking authorized broadcasts…")
                    else if (alternatives.isEmpty()) Text("No verified or exact-guide matching broadcast has available local capacity. Keep watching or release the panel.")
                    alternatives.forEach { choice ->
                        RemoteConflictButton("${choice.channel.shownName} · ${choice.providerName}\n${choice.capacity}${if (choice.verified) " · Confirmed Sports broadcast" else " · Exact guide identity"}", enabled = !busy) { onAlternative(choice) }
                    }
                }
                RemoteConflictButton("Switch $role to Another Provider", Modifier.focusRequester(first), enabled = !busy, onClick = onSwitch)
                RemoteConflictButton("Release Conflicting Viewing Panel", enabled = !busy, onClick = onRelease)
                RemoteConflictButton("View Recording Details", onClick = onDetails)
                RemoteConflictButton("Keep Watching Until Recording Starts", onClick = onKeep)
                if (busy) Text("Validating replacement… The recording deadline remains active.")
            }
        }
        LaunchedEffect(Unit) { first.requestFocus() }
    }
}

@Composable
private fun RemoteConflictButton(label: String, modifier: Modifier = Modifier, enabled: Boolean = true, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    OutlinedButton(onClick = onClick, enabled = enabled, modifier = modifier.fillMaxWidth().onFocusChanged { focused = it.isFocused },
        border = BorderStroke(if (focused) 3.dp else 1.dp, if (focused) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline)) { Text(label) }
}
