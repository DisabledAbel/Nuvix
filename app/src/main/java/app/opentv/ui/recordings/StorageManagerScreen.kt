/* Copyright (C) 2026 Nuvix Contributors. GPL-3.0-or-later. */
package app.opentv.ui.recordings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
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
import app.opentv.core.ServiceLocator
import app.opentv.data.model.Recording
import app.opentv.data.model.RecordingEvent
import app.opentv.data.model.RecordingStatus
import app.opentv.recording.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date
import java.util.Locale

internal data class StorageSummary(
    val destination: RecordingDestination,
    val capacity: StorageCapacity,
    val usageBytes: Long,
    val estimatedHours: Double?,
    val policy: DvrStoragePolicy,
    val report: String? = null,
    val configurable: Boolean = true,
)

/** Read and transform the latest policy on the I/O dispatcher; callers handle the result on UI. */
internal suspend fun updateStoredPolicy(store: DvrStorageStore, destination: String,
    update: (DvrStoragePolicy) -> DvrStoragePolicy): Boolean = withContext(Dispatchers.IO) {
    store.save(destination, update(store.policy(destination)))
}

/** A failed refresh retains the last successful snapshot and does not terminate periodic updates. */
internal suspend fun refreshStorageSummaries(load: suspend () -> List<StorageSummary>,
    onLoaded: (List<StorageSummary>) -> Unit, onFailure: () -> Unit) {
    while (true) {
        try { onLoaded(load()) }
        catch (cancel: kotlinx.coroutines.CancellationException) { throw cancel }
        catch (_: Exception) { onFailure() }
        delay(30_000)
    }
}

@Composable
fun StorageManagerScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val graph = remember { ServiceLocator.get(context) }
    val store = remember { DvrStorageStore(context) }
    val scope = rememberCoroutineScope()
    val rows by remember { graph.recordingRepository.observeAll() }.collectAsState(emptyList())
    val history by remember { graph.recordingRepository.observeStorageEvents() }.collectAsState(emptyList())
    var summaries by remember { mutableStateOf<List<StorageSummary>>(emptyList()) }
    var refresh by remember { mutableIntStateOf(0) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    // Refresh capacities independently of frequent active-capture byte updates.
    LaunchedEffect(refresh) {
        refreshStorageSummaries(load = {
            withContext(Dispatchers.IO) {
                val snapshot = graph.recordingRepository.all()
                val destinations = RecordingDestinations.configured(context, graph.settings)
                val mapped = snapshot.groupBy { RecordingDestinations.forRecording(context, graph.settings, store, it) }
                val summaries = destinations.map { destination ->
                    val tracked = mapped[destination.id].orEmpty()
                    val capacity = RecordingDestinations.probe(context, graph.settings, destination)
                    StorageSummary(destination, capacity, DvrStorageRules.usage(tracked),
                        DvrStorageRules.remainingHours(capacity, tracked, System.currentTimeMillis()),
                        store.policy(destination.id), store.report(destination.id))
                }
                val other = mapped.filterKeys { id -> destinations.none { it.id == id } }.values.flatten()
                    .filter { it.status != RecordingStatus.DELETED }
                if (other.isEmpty()) summaries else summaries + StorageSummary(
                    RecordingDestination("unmapped", "Other tracked destinations", app.opentv.core.AppSettings.RecordingTarget.INTERNAL),
                    StorageCapacity(StorageAvailability.UNAVAILABLE, System.currentTimeMillis()),
                    DvrStorageRules.usage(other), null, DvrStoragePolicy(),
                    "Previous or unidentifiable destinations. Reconnect storage in Recording settings. Automatic cleanup is unavailable.", false)
            }
        }, onLoaded = { summaries = it }, onFailure = {
            message = "Storage refresh failed. Previously loaded information was retained. Try Refresh storage."
        })
    }
    StorageManagerContent(summaries, rows, history, busy, message,
        onBack = onBack,
        onRefresh = { refresh++ },
        onPolicy = { destination, update ->
            val saved = updateStoredPolicy(store, destination.id, update)
            if (!saved) message = "Storage policy could not be saved. Review again."
            else { DvrStorageWorker.schedule(context); refresh++ }
            saved
        },
        onDelete = { review ->
            if (!busy) {
                busy = true
                scope.launch {
                    try {
                        val result = deleteReviewedRecordings(review) { graph.recordingEngine.deleteStoredRecording(it) }
                        message = "${result.deleted} recording(s) deleted; ${result.changed} changed/protected recording(s) skipped." +
                            if (result.storageError) " Deletion stopped: storage operation failed or could not be confirmed. No further files were attempted. Unconfirmed entries and history were retained; check the destination before retrying." else " History retained."
                    } catch (cancel: kotlinx.coroutines.CancellationException) { throw cancel }
                    catch (_: Exception) { message = "Deletion stopped. Check storage and refresh the recording history before trying again." }
                    finally { busy = false; refresh++ }
                }
            }
        })
}

/** One focusable control per recording, stable keys, and a separate immutable deletion review. */
@Composable
internal fun StorageManagerContent(
    summaries: List<StorageSummary>, rows: List<Recording>, history: List<RecordingEvent>, busy: Boolean,
    message: String?, onBack: () -> Unit, onRefresh: () -> Unit,
    onPolicy: suspend (RecordingDestination, (DvrStoragePolicy) -> DvrStoragePolicy) -> Boolean, onDelete: (List<Recording>) -> Unit,
) {
    var selected by remember { mutableStateOf(setOf<Long>()) }
    var sortBySize by remember { mutableStateOf(false) }
    var review by remember { mutableStateOf<List<Recording>?>(null) }
    var enabling by remember { mutableStateOf<StorageSummary?>(null) }
    var policyEditor by remember { mutableStateOf<StorageSummary?>(null) }
    val refreshFocus = remember { FocusRequester() }
    val reviewFocus = remember { FocusRequester() }
    val listState = rememberLazyListState()
    val focusScope = rememberCoroutineScope()
    var lastFocused by remember { mutableStateOf<Long?>(null) }
    val completed = rows.filter { it.status in setOf(RecordingStatus.COMPLETED, RecordingStatus.PARTIAL, RecordingStatus.FAILED) }
    val visible = if (sortBySize) completed.sortedWith(compareByDescending<Recording> { it.sizeBytes }.thenBy { it.id })
        else completed.sortedWith(compareByDescending<Recording> { it.endedAtMillis }.thenBy { it.id })
    val ids = visible.map { it.id }
    val focusableIds = visible.filter { DvrStorageRules.deletable(it) }.map { it.id }
    LaunchedEffect(ids, focusableIds) {
        selected = selected.intersect(focusableIds.toSet())
        if (lastFocused != null && lastFocused !in focusableIds) {
            listState.scrollToItem(0)
            refreshFocus.requestFocus()
            lastFocused = null
        }
    }
    BackHandler(enabled = review == null && enabling == null && policyEditor == null, onBack = onBack)
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 28.dp, vertical = 20.dp), state = listState,
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item(key = "header") {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Storage Manager", style = MaterialTheme.typography.headlineMedium)
                Text("Tracked recording usage includes active captures. Capacity and remaining hours may be Unknown. No destination or recording priority is changed.")
                OutlinedButton(onClick = onBack) { Text("Back to Recordings / Settings") }
                OutlinedButton(onClick = onRefresh, modifier = Modifier.focusRequester(refreshFocus)) { Text("Refresh storage") }
                if (message != null) Text(message)
                if (busy) Text("Deleting confirmed recordings…")
            }
        }
        items(summaries, key = { "destination-${it.destination.id}" }) { summary ->
            StorageDestinationCard(summary, onEdit = { policyEditor = summary }, onToggleCleanup = {
                if (summary.policy.cleanupEnabled) focusScope.launch {
                    onPolicy(summary.destination) { it.copy(cleanupEnabled = false) }
                } else enabling = summary
            })
        }
        item(key = "review-controls") {
            Column {
                Text("Completed recordings", style = MaterialTheme.typography.titleLarge)
                Text("Select recordings to review their dates and sizes. Protected recordings must be unprotected in Recording Options first. Lock Padding is independent of deletion protection.")
                OutlinedButton(onClick = { sortBySize = !sortBySize }) { Text(if (sortBySize) "Sort by date (newest first)" else "Sort by size (largest first)") }
                OutlinedButton(enabled = selected.isNotEmpty() && !busy, modifier = Modifier.focusRequester(reviewFocus),
                    onClick = { review = visible.filter { it.id in selected && DvrStorageRules.deletable(it) } }) {
                    Text("Review deletion (${selected.size})")
                }
                if (visible.isEmpty()) Text("No completed recordings to review.")
            }
        }
        items(visible, key = { "recording-${it.id}" }) { row ->
            OutlinedButton(enabled = !busy && DvrStorageRules.deletable(row),
                modifier = Modifier.fillMaxWidth().onFocusChanged { if (it.isFocused) lastFocused = row.id },
                onClick = { selected = if (row.id in selected) selected - row.id else selected + row.id }) {
                Text("${if (row.id in selected) "Selected" else "Select"}: ${row.title}\n${storageDate(row.endedAtMillis)} · ${storageSize(row.sizeBytes)} · ${storageLocationLabel(row)}${if (row.protectedRecording) " · Protected Recording" else ""}")
            }
        }
        item(key = "audit-header") { Text("Deletion and cleanup history (latest 50)", style = MaterialTheme.typography.titleLarge) }
        items(history, key = { "event-${it.id}" }) { event ->
            StorageInfo("${storageDate(event.atMillis)} · ${rows.firstOrNull { it.id == event.recordingId }?.title ?: "Recording ${event.recordingId}"}\n${event.message}")
        }
    }
    review?.let { frozen ->
        StorageDeletionConfirmation(frozen, onDismiss = {
            review = null
            focusScope.launch {
                if (selected.isNotEmpty()) {
                    listState.scrollToItem(1 + summaries.size)
                    reviewFocus.requestFocus()
                } else {
                    listState.scrollToItem(0)
                    refreshFocus.requestFocus()
                }
            }
        }, onConfirm = {
            review = null
            selected = emptySet()
            focusScope.launch { listState.scrollToItem(0); refreshFocus.requestFocus() }
            onDelete(frozen)
        })
    }
    enabling?.let { summary ->
        val keepOffFocus = remember { FocusRequester() }
        AlertDialog(onDismissRequest = { enabling = null }, title = { Text("Enable automatic cleanup for ${summary.destination.label}?") },
            text = { Column(Modifier.verticalScroll(rememberScrollState())) { Text("Nuvix will try to keep ${storageSize(summary.policy.targetBytes)} free by deleting its own tracked, completed recordings older than ${summary.policy.retentionDays} days, oldest first. Active and protected recordings are never eligible. Lock Padding does not protect files. Each run scans at most 200 candidates and deletes at most 10 files. Cleanup stops on storage errors or unknown/stale capacity; the target is not guaranteed. USB and NAS capacity may be unsupported, so cleanup may do nothing. Review deletion history in Storage Manager.") } },
            confirmButton = { TextButton(onClick = {
                focusScope.launch {
                    if (onPolicy(summary.destination) { it.copy(cleanupEnabled = true) } && enabling == summary) enabling = null
                }
            }) { Text("Enable cleanup for this destination") } },
            dismissButton = {
                TextButton(modifier = Modifier.focusRequester(keepOffFocus), onClick = { enabling = null }) { Text("Keep cleanup off") }
                LaunchedEffect(Unit) { keepOffFocus.requestFocus() }
            })
    }
    policyEditor?.let { summary ->
        StoragePolicyDialog(summary, onDismiss = { policyEditor = null }, onSave = { draft ->
            focusScope.launch {
                if (onPolicy(summary.destination) { draft } && policyEditor == summary) policyEditor = null
            }
        })
    }
}

@Composable
private fun StorageDestinationCard(summary: StorageSummary, onEdit: () -> Unit, onToggleCleanup: () -> Unit) {
    val capacity = summary.capacity
    val now = System.currentTimeMillis()
    val reliable = capacity.reliable(now)
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(summary.destination.label, style = MaterialTheme.typography.titleLarge)
            StorageInfo(capacity.availability.explanation)
            StorageInfo("Available / total: ${if (reliable) "${storageSize(capacity.availableBytes!!)} / ${storageSize(capacity.totalBytes!!)}" else "Unknown"}")
            StorageInfo("Tracked recording usage: ${storageSize(summary.usageBytes)}")
            StorageInfo("Estimated remaining recording hours: ${if (reliable && summary.estimatedHours != null) String.format(Locale.getDefault(), "%.1f hours", summary.estimatedHours) else "Unknown"}")
            Text("Estimate uses recent measured completed recordings and leaves the capture reserve free. Bitrates and concurrent captures vary; this is not guaranteed capacity.")
            if (!capacity.fresh(now)) StorageInfo("Capacity is stale. Refresh before making storage decisions.")
            else if (!reliable) StorageInfo("This destination does not expose reliable free space. Automatic cleanup cannot run without it.")
            if (DvrStorageRules.lowSpace(capacity, summary.policy, now) == true)
                StorageInfo("Low-space warning: available space is at or below ${storageSize(maxOf(summary.policy.warningBytes, capacity.totalBytes!! / 20))}.")
            StorageInfo("Warnings: ${if (summary.policy.warnings) "On" else "Off"}; threshold ${storageSize(summary.policy.warningBytes)} or 5% of total, whichever is greater.")
            StorageInfo("Automatic cleanup: ${if (summary.policy.cleanupEnabled) "On" else "Off"}; target ${storageSize(summary.policy.targetBytes)}; retention ${summary.policy.retentionDays} days.")
            summary.report?.let { StorageInfo(it) }
            if (summary.configurable) {
                OutlinedButton(onClick = onEdit) { Text("Configure storage policy for ${summary.destination.label}") }
                OutlinedButton(onClick = onToggleCleanup) { Text("${if (summary.policy.cleanupEnabled) "Disable" else "Enable"} automatic cleanup for ${summary.destination.label}") }
            }
        }
    }
}

@Composable
internal fun StorageDeletionConfirmation(rows: List<Recording>, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    val cancelFocus = remember { FocusRequester() }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Delete ${rows.size} recording(s)?") },
        text = { Column(Modifier.verticalScroll(rememberScrollState())) {
            Text("Permanently delete only these recording files (${storageSize(DvrStorageRules.usage(rows))}). This cannot be undone. Prior history is retained. Deletion status is recorded only after each storage operation succeeds. Changed, active or protected recordings will be skipped.")
            rows.forEach { Text("${it.title} · ${storageDate(it.endedAtMillis)} · ${storageSize(it.sizeBytes)} · ${storageLocationLabel(it)}", Modifier.padding(top = 8.dp)) }
        } },
        confirmButton = { TextButton(onClick = onConfirm, enabled = rows.isNotEmpty()) { Text("Confirm delete recording files") } },
        dismissButton = {
            TextButton(modifier = Modifier.focusRequester(cancelFocus), onClick = onDismiss) { Text("Cancel deletion") }
            LaunchedEffect(Unit) { cancelFocus.requestFocus() }
        })
}

@Composable
private fun StoragePolicyDialog(summary: StorageSummary, onDismiss: () -> Unit, onSave: (DvrStoragePolicy) -> Unit) {
    var draft by remember(summary.destination.id) { mutableStateOf(summary.policy) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Storage policy: ${summary.destination.label}") },
        text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { draft = draft.copy(warnings = !draft.warnings) }) { Text("Low-space warnings: ${if (draft.warnings) "On" else "Off"}") }
            Text("Warning threshold: ${storageSize(draft.warningBytes)} or 5% of total. Warnings never change recording behavior.")
            OutlinedButton(onClick = { draft = draft.copy(warningBytes = (draft.warningBytes - DvrStoragePolicy.GIB).coerceAtLeast(DvrStoragePolicy.GIB)) }) { Text("Decrease warning threshold") }
            OutlinedButton(onClick = { draft = draft.copy(warningBytes = (draft.warningBytes + DvrStoragePolicy.GIB).coerceAtMost(100 * DvrStoragePolicy.GIB)) }) { Text("Increase warning threshold") }
            Text("Cleanup minimum free-space target: ${storageSize(draft.targetBytes)}")
            OutlinedButton(onClick = { draft = draft.copy(targetBytes = (draft.targetBytes - DvrStoragePolicy.GIB).coerceAtLeast(DvrStoragePolicy.GIB)) }) { Text("Decrease free-space target") }
            OutlinedButton(onClick = { draft = draft.copy(targetBytes = (draft.targetBytes + DvrStoragePolicy.GIB).coerceAtMost(100 * DvrStoragePolicy.GIB)) }) { Text("Increase free-space target") }
            Text("Keep recordings for at least ${draft.retentionDays} days")
            OutlinedButton(onClick = { draft = draft.copy(retentionDays = (draft.retentionDays - 1).coerceAtLeast(1)) }) { Text("Decrease retention period") }
            OutlinedButton(onClick = { draft = draft.copy(retentionDays = (draft.retentionDays + 1).coerceAtMost(3650)) }) { Text("Increase retention period") }
            Text("Cleanup is enabled separately after reviewing its policy. A shorter retention period or higher target can delete more eligible recordings on the next maintenance run.")
        } }, confirmButton = { TextButton(onClick = { onSave(draft) }) { Text("Save storage policy") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel policy changes") } })
}

internal fun storageSize(bytes: Long): String {
    if (bytes < 0) return "Unknown"
    val units = listOf("B", "KiB", "MiB", "GiB", "TiB")
    var value = bytes.toDouble()
    var unit = 0
    while (value >= 1024 && unit < units.lastIndex) { value /= 1024; unit++ }
    return String.format(Locale.getDefault(), if (unit == 0) "%.0f %s" else "%.1f %s", value, units[unit])
}

private fun storageDate(at: Long): String = if (at > 0) DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(at)) else "Date unknown"

private fun storageLocationLabel(row: Recording): String = when {
    row.filePath.startsWith("smb://") -> "NAS / SMB"
    row.filePath.startsWith("content://") || row.filePath.startsWith("usb://") -> "USB / external folder"
    else -> "Local recording destination"
}

/** Read-only values/history also need D-pad focus so a TV can scroll to and read them. */
@Composable
private fun StorageInfo(text: String) {
    var focused by remember { mutableStateOf(false) }
    Text(text, color = if (focused) MaterialTheme.colorScheme.onSecondaryContainer else LocalContentColor.current,
        modifier = Modifier.fillMaxWidth()
            .background(if (focused) MaterialTheme.colorScheme.secondaryContainer else androidx.compose.ui.graphics.Color.Transparent,
                MaterialTheme.shapes.small)
            .padding(4.dp).onFocusChanged { focused = it.isFocused }.focusable())
}
