/* Copyright (C) 2026 Nuvix Contributors. GPL-3.0-or-later. */
package app.opentv.ui.recordings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.opentv.core.ServiceLocator
import app.opentv.data.model.Recording
import app.opentv.data.model.RecordingStatus
import app.opentv.recording.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Calendar
import java.util.Date

/** Shared active-recording editor. Every remote action previews before a separate confirmation. */
@Composable
fun RecordingDurationDialog(recordingId: Long, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val graph = remember(context) { ServiceLocator.get(context) }
    val rows by graph.recordingRepository.observeAll().collectAsState(initial = emptyList())
    val row = rows.firstOrNull { it.id == recordingId }
    val scope = rememberCoroutineScope()
    var review by remember(recordingId) { mutableStateOf<DurationReview?>(null) }
    var error by remember(recordingId) { mutableStateOf<String?>(null) }
    var customEnd by remember(recordingId) { mutableStateOf(false) }
    var alternativesFor by remember(recordingId) { mutableStateOf<Long?>(null) }
    var busy by remember(recordingId) { mutableStateOf(false) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { now = System.currentTimeMillis(); delay(1_000) } }
    fun preview(choice: DurationChoice) {
        if (busy) return
        busy = true
        scope.launch {
            try {
                // Pure validation provides useful errors without displaying provider/storage exceptions.
                val current = row
                if (current != null) {
                    try { RecordingDuration.proposedEnd(current, choice, System.currentTimeMillis()) }
                    catch (invalid: IllegalArgumentException) { error = invalid.message; return@launch }
                }
                review = graph.recordingEngine.previewDuration(recordingId, choice); error = null
            }
            catch (_: Exception) { error = "Cannot edit this recording. It may have finalized; refresh and review again." }
            finally { busy = false }
        }
    }
    if (customEnd) DurationCustomEndDialog(row?.scheduledEndMillis?.takeIf { it > now } ?: now,
        onDismiss = { customEnd = false }, onChoose = { customEnd = false; preview(DurationChoice.EndAt(it)) })
    alternativesFor?.let { id -> DurationAlternativeDialog(id, onDismiss = { alternativesFor = null },
        onChanged = { alternativesFor = null; review = null; error = "Provider choice saved. Review the extension again." }) }
    DurationEditor(row, review, now, busy, error, onDismiss, ::preview,
        onAdjust = { review = null; error = null },
        onConfirm = {
            if (!busy) {
                busy = true
                scope.launch {
                    try {
                        error = graph.recordingEngine.applyDuration(requireNotNull(review), confirmStop = true)
                        if (error == null) onDismiss() else review = null
                    } catch (_: Exception) { error = "Recording changed or storage is unavailable. Review again."; review = null }
                    finally { busy = false }
                }
            }
        },
        onAlternative = { alternativesFor = it },
        onCustom = { customEnd = true })
}

/** Stateless editor also used by remote-navigation and accessibility tests. */
@Composable
internal fun DurationEditor(
    row: Recording?, review: DurationReview?, now: Long, busy: Boolean, error: String?,
    onDismiss: () -> Unit, onPreview: (DurationChoice) -> Unit, onAdjust: () -> Unit,
    onConfirm: () -> Unit, onCustom: () -> Unit, onAlternative: (Long) -> Unit = {},
) {
    val active = row?.status == RecordingStatus.RECORDING
    AlertDialog(onDismissRequest = onDismiss,
        title = { Text("Recording Duration${row?.let { " — ${it.title}" }.orEmpty()}") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                row?.let {
                    Text("Original scheduled end: ${durationTime(it.programmeEndMillis)}")
                    Text("Original padded end: ${durationTime(RecordingDuration.originalEnd(it))}")
                    Text("Current end: ${durationTime(it.scheduledEndMillis)}")
                    Text("Time remaining: ${if (it.scheduledEndMillis > 0) "${((it.scheduledEndMillis - now).coerceAtLeast(0) + 59_999) / 60_000} minutes" else "No scheduled end"}")
                    Text("End padding: ${it.padEndMinutes} minutes · Manual change: ${it.manualExtensionMillis / 60_000} minutes")
                    Text("Protect Recording: ${it.protectedRecording} · Lock Padding: ${it.lockPadding}")
                    if (it.durationModifiedAtMillis > 0) Text("Last changed: ${durationTime(it.durationModifiedAtMillis)}")
                }
                if (!active) Text("Recording is not capturing. Completed and finalizing files cannot be extended.")
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                if (review == null) {
                    RecordingDuration.increments.forEach { minutes ->
                        OutlinedButton(onClick = { onPreview(DurationChoice.Add(minutes)) }, enabled = active && !busy) { Text("Add $minutes Minutes") }
                    }
                    OutlinedButton(onClick = onCustom, enabled = active && !busy) { Text("Choose End Time") }
                    OutlinedButton(onClick = { onPreview(DurationChoice.Restore) }, enabled = active && !busy && row?.let { RecordingDuration.originalEnd(it) > 0 } == true) { Text("Restore Original End") }
                    OutlinedButton(onClick = { onPreview(DurationChoice.Stop) }, enabled = active && !busy) { Text("Stop Recording Now") }
                } else {
                    Text("Proposed end: ${durationTime(review.proposedEndMillis)}", style = MaterialTheme.typography.titleMedium)
                    Text("Provider: ${review.accountLabel} · Account capacity: ${review.capacity ?: "Unknown"}")
                    if (review.additionalBytes > 0) Text("Estimated additional storage: ${review.additionalBytes / (1024 * 1024)} MB")
                    review.warnings.forEach { Text(it) }
                    review.affected.forEach { affected ->
                        Text("${affected.title} · ${affected.channelName} · Protect: ${affected.protectedRecording} · Lock Padding: ${affected.lockPadding}")
                        if (affected.status == RecordingStatus.SCHEDULED) OutlinedButton(onClick = { onAlternative(affected.id) }, enabled = !busy) { Text("Choose an Authorized Alternative: ${affected.title}") }
                        OutlinedButton(onClick = { app.opentv.core.RecordingSignals.requestReview(affected.id); onDismiss() }) { Text("Review Conflicting Recording: ${affected.title}") }
                    }
                    OutlinedButton(onClick = onAdjust, enabled = !busy) { Text("Adjust Extension") }
                }
            }
        },
        confirmButton = {
            if (review != null) TextButton(onClick = onConfirm, enabled = active && !busy && !review.blocked) {
                Text(if (review.needsStopConfirmation) "Confirm Stop Recording" else if (review.warnings.isNotEmpty()) "Confirm With Warning" else "Confirm New End")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } })
}

private fun durationTime(millis: Long): String = if (millis <= 0) "Unscheduled" else DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.LONG).format(Date(millis))

/** Alternatives change a scheduled booking only after a separate provider confirmation. */
@Composable
private fun DurationAlternativeDialog(id: Long, onDismiss: () -> Unit, onChanged: () -> Unit) {
    val graph = ServiceLocator.get(LocalContext.current)
    val scope = rememberCoroutineScope()
    var choices by remember(id) { mutableStateOf<List<RecordingConflictAlternative>?>(null) }
    var selected by remember(id) { mutableStateOf<RecordingConflictAlternative?>(null) }
    var error by remember(id) { mutableStateOf<String?>(null) }
    var busy by remember(id) { mutableStateOf(false) }
    LaunchedEffect(id) {
        try { choices = graph.recordingEngine.durationAlternatives(id) }
        catch (_: Exception) { choices = emptyList(); error = "Cannot verify alternatives. Adjust the extension or cancel." }
    }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Choose an Authorized Alternative") },
        text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Select an independent provider for the conflicting scheduled recording. The booking keeps its time, padding and protection.")
            if (choices == null) Text("Checking authorized broadcasts…")
            if (choices?.isEmpty() == true) Text("No verified or exact-guide matching broadcast with known account allowance is available.")
            choices.orEmpty().forEach { choice ->
                OutlinedButton(onClick = { selected = choice }, enabled = !busy) { Text("${choice.channel.displayName} · ${choice.providerName} · ${choice.capacity}") }
            }
            selected?.let { Text("Selected provider: ${it.providerName}") }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        } },
        confirmButton = { TextButton(enabled = selected != null && !busy, onClick = {
            if (!busy) { busy = true; scope.launch {
                try {
                    if (graph.recordingEngine.applyDurationAlternative(id, requireNotNull(selected).channel.id)) onChanged()
                    else error = "Booking or capacity changed. Cancel and review again."
                } catch (_: Exception) { error = "This alternative could not be verified. Cancel and review again." }
                finally { busy = false }
            } }
        }) { Text("Confirm Provider Choice") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } })
}

/** Date/hour/minute steppers are reachable with D-pad; no touch clock or keyboard is required. */
@Composable
internal fun DurationCustomEndDialog(initialMillis: Long, onDismiss: () -> Unit, onChoose: (Long) -> Unit) {
    var selected by remember { mutableLongStateOf(initialMillis - initialMillis % 60_000) }
    fun change(field: Int, amount: Int) {
        selected = Calendar.getInstance().apply { timeInMillis = selected; add(field, amount) }.timeInMillis
    }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Choose End Time") },
        text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Selected end: ${durationTime(selected)}")
            listOf("Day" to Calendar.DATE, "Hour" to Calendar.HOUR_OF_DAY, "Minute" to Calendar.MINUTE).forEach { (label, field) ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { change(field, -1) }, modifier = Modifier.weight(1f)) { Text("Earlier $label") }
                    OutlinedButton(onClick = { change(field, 1) }, modifier = Modifier.weight(1f)) { Text("Later $label") }
                }
            }
            Text("Review the resolved date, time and timezone before confirming. No change is applied here.")
        } },
        confirmButton = { TextButton(onClick = { onChoose(selected) }) { Text("Review End Time") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } })
}
