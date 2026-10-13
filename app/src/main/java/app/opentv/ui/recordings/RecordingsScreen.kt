/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.ui.recordings

import android.app.Application
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.runtime.collectAsState
import app.opentv.R
import app.opentv.core.ServiceLocator
import app.opentv.data.model.Profile
import app.opentv.data.model.Recording
import app.opentv.data.model.RecordingStatus
import app.opentv.data.model.Reminder
import app.opentv.data.model.SeriesRule
import app.opentv.recording.RecordingStorage
import app.opentv.reminders.ReminderScheduler
import coil.compose.AsyncImage
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class RecordingsViewModel(app: Application) : AndroidViewModel(app) {
    private val graph = ServiceLocator.get(app)

    val recordings: StateFlow<List<Recording>> =
        graph.recordingRepository.observeAll()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val reminders: StateFlow<List<Reminder>> =
        graph.reminderRepository.observeAll()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val profiles: StateFlow<List<Profile>> =
        graph.profiles.observeAll()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Active series-link rules — shown as their own section so a link is visible even before its
     *  first episode has been booked (e.g. the next airing isn't in the loaded guide window yet). */
    val seriesRules: StateFlow<List<SeriesRule>> =
        graph.recordingRepository.observeRules()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    // NB: cold-start reconciliation of orphaned "recording" rows happens once in OpenTvApp, NOT
    // here — opening this screen must never mark an actively-capturing recording as interrupted.

    fun stop(id: Long) = graph.recordingEngine.stop(id)

    /** Re-attempt a failed/interrupted recording (re-arms a future booking or restarts a live one). */
    fun retry(recording: Recording) {
        viewModelScope.launch { graph.recordingEngine.retry(recording) }
    }

    /** Delete a series link and cancel its future (not-yet-started) bookings. */
    fun removeSeriesRule(rule: SeriesRule) {
        viewModelScope.launch { graph.recordingEngine.removeSeriesRule(rule.id) }
    }

    fun cancelReminder(reminder: Reminder) {
        viewModelScope.launch {
            ReminderScheduler.cancel(getApplication(), reminder.id)
            graph.reminderRepository.delete(reminder.id)
        }
    }

    fun delete(recording: Recording) {
        viewModelScope.launch {
            if (recording.status == RecordingStatus.RECORDING) graph.recordingEngine.stop(recording.id)
            RecordingStorage.delete(getApplication(), graph.settings, recording.filePath)
            graph.recordingRepository.delete(recording.id)
        }
    }
}

@Composable
fun RecordingsScreen(onPlay: (Recording) -> Unit) {
    val viewModel: RecordingsViewModel = viewModel()
    val recordings by viewModel.recordings.collectAsState()
    val reminders by viewModel.reminders.collectAsState()
    val profiles by viewModel.profiles.collectAsState()
    val seriesRules by viewModel.seriesRules.collectAsState()

    val now = System.currentTimeMillis()
    val upcomingReminders = reminders.filter { !it.fired && it.endUtcMillis > now }
    // Split bookings from the library so "what's coming up" reads separately from "what I have".
    val scheduled = recordings.filter { it.status == RecordingStatus.SCHEDULED }
        .sortedBy { it.scheduledStartMillis }
    val recorded = recordings.filter { it.status != RecordingStatus.SCHEDULED }
    val profileNames = profiles.associate { it.id to it.name }
    val showProfiles = profiles.size > 1
    // Flag scheduled bookings that overlap *on the same provider* — that's the real single-
    // connection clash. Overlaps spread across two providers (the multi-provider fallback did its
    // job) both record fine and aren't flagged.
    val clashingIds = buildSet {
        for (i in scheduled.indices) for (j in i + 1 until scheduled.size) {
            val a = scheduled[i]; val b = scheduled[j]
            if (a.sourceId == b.sourceId &&
                a.scheduledStartMillis < b.scheduledEndMillis && b.scheduledStartMillis < a.scheduledEndMillis
            ) {
                add(a.id); add(b.id)
            }
        }
    }

    Column(Modifier.fillMaxSize().padding(horizontal = 28.dp, vertical = 20.dp)) {
        Text(stringResource(R.string.rec_screen_title), style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(4.dp))
        Text(
            stringResource(R.string.rec_screen_desc),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(16.dp))

        if (recordings.isEmpty() && upcomingReminders.isEmpty() && seriesRules.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    stringResource(R.string.rec_empty_state),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (upcomingReminders.isNotEmpty()) {
                    item(key = "reminders-header") { SectionHeader(stringResource(R.string.rec_upcoming_reminders)) }
                    items(upcomingReminders, key = { "rem-${it.id}" }) { rem ->
                        ReminderRow(reminder = rem, onCancel = { viewModel.cancelReminder(rem) })
                    }
                }
                if (seriesRules.isNotEmpty()) {
                    item(key = "series-header") { SectionHeader(stringResource(R.string.rec_section_series_links)) }
                    items(seriesRules, key = { "series-${it.id}" }) { rule ->
                        val ruleEpisodes = scheduled.filter { it.seriesRuleId == rule.id }
                        SeriesRuleRow(
                            rule = rule,
                            upcomingCount = ruleEpisodes.size,
                            nextAtMillis = ruleEpisodes.minByOrNull { it.scheduledStartMillis }?.scheduledStartMillis,
                            onRemove = { viewModel.removeSeriesRule(rule) },
                        )
                    }
                }
                if (scheduled.isNotEmpty()) {
                    item(key = "scheduled-header") { SectionHeader(stringResource(R.string.rec_section_scheduled)) }
                    items(scheduled, key = { "sch-${it.id}" }) { rec ->
                        RecordingRow(
                            recording = rec,
                            profileNames = profileNames,
                            showProfiles = showProfiles,
                            clashes = rec.id in clashingIds,
                            onPlay = { onPlay(rec) },
                            onStop = { viewModel.stop(rec.id) },
                            onRetry = { viewModel.retry(rec) },
                            onDelete = { viewModel.delete(rec) },
                        )
                    }
                }
                if (recorded.isNotEmpty()) {
                    item(key = "recorded-header") { SectionHeader(stringResource(R.string.rec_section_recorded)) }
                    items(recorded, key = { it.id }) { rec ->
                        RecordingRow(
                            recording = rec,
                            profileNames = profileNames,
                            showProfiles = showProfiles,
                            onPlay = { onPlay(rec) },
                            onStop = { viewModel.stop(rec.id) },
                            onRetry = { viewModel.retry(rec) },
                            onDelete = { viewModel.delete(rec) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 6.dp, bottom = 2.dp),
    )
}

@Composable
private fun ReminderRow(reminder: Reminder, onCancel: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(44.dp).clip(RoundedCornerShape(6.dp))
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Filled.Notifications,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(
                reminder.title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(2.dp))
            val prefix = if (reminder.autoTune) stringResource(R.string.rec_reminder_autoswitch)
            else stringResource(R.string.rec_reminder_plain)
            Text(
                "$prefix · ${reminder.channelName} · ${formatWhen(reminder.startUtcMillis)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(10.dp))
        ActionButton(Icons.Filled.Delete, stringResource(R.string.rec_cancel_reminder), onCancel)
    }
}

private fun formatWhen(utcMillis: Long): String =
    java.text.SimpleDateFormat("EEE d MMM, HH:mm", java.util.Locale.getDefault())
        .format(java.util.Date(utcMillis))

@Composable
private fun SeriesRuleRow(rule: SeriesRule, upcomingCount: Int, nextAtMillis: Long?, onRemove: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(44.dp).clip(RoundedCornerShape(6.dp))
                .background(Color(0xFFE53935).copy(alpha = 0.15f)),
            contentAlignment = Alignment.Center,
        ) {
            // A repeat glyph, tinted record-red — reads as "records every time it's on".
            Icon(Icons.Filled.Repeat, contentDescription = null, tint = Color(0xFFE53935))
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(
                rule.title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(2.dp))
            // Show when the next episode actually records, once one's on the schedule; otherwise the
            // rule is armed but the guide hasn't revealed the next airing yet.
            val line = when {
                nextAtMillis != null && upcomingCount > 1 ->
                    stringResource(R.string.rec_series_next_more, formatWhen(nextAtMillis), upcomingCount - 1)
                nextAtMillis != null ->
                    stringResource(R.string.rec_series_next_at, rule.channelName, formatWhen(nextAtMillis))
                else -> stringResource(R.string.rec_series_waiting, rule.channelName)
            }
            Text(
                line,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(10.dp))
        ActionButton(Icons.Filled.Delete, stringResource(R.string.rec_series_remove), onRemove)
    }
}

@Composable
private fun RecordingRow(
    recording: Recording,
    profileNames: Map<Long, String>,
    showProfiles: Boolean,
    clashes: Boolean = false,
    onPlay: () -> Unit,
    onStop: () -> Unit,
    onRetry: () -> Unit,
    onDelete: () -> Unit,
) {
    val playable = recording.status == RecordingStatus.COMPLETED
    val retryable = recording.status == RecordingStatus.FAILED
    // An in-progress recording can be watched as it records — tail-followed off disk (internal) or
    // over SMB (NAS), with no second provider connection. USB (SAF) in-progress isn't offered.
    val watchableLive = recording.status == RecordingStatus.RECORDING &&
        !RecordingStorage.isContent(recording.filePath) &&
        !RecordingStorage.isUsbPlaceholder(recording.filePath)
    val profileName = recording.profileId?.takeIf { it != 0L }?.let { profileNames[it] }
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AsyncImage(
            model = recording.logoUrl,
            contentDescription = null,
            modifier = Modifier.size(44.dp).clip(RoundedCornerShape(6.dp)),
        )
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(
                recording.title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                statusLine(recording),
                style = MaterialTheme.typography.bodySmall,
                color = statusColor(recording.status),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (showProfiles && profileName != null) {
                Spacer(Modifier.height(2.dp))
                Text(
                    stringResource(R.string.rec_for_profile, profileName),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (clashes) {
                Spacer(Modifier.height(2.dp))
                Text(
                    stringResource(R.string.rec_clashes),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Spacer(Modifier.width(10.dp))
        if (playable) {
            ActionButton(Icons.Filled.PlayArrow, stringResource(R.string.common_play), onPlay)
            Spacer(Modifier.width(8.dp))
        }
        if (watchableLive) {
            ActionButton(Icons.Filled.PlayArrow, stringResource(R.string.rec_watch_live), onPlay)
            Spacer(Modifier.width(8.dp))
        }
        if (recording.status == RecordingStatus.RECORDING) {
            ActionButton(Icons.Filled.Stop, stringResource(R.string.common_stop), onStop)
            Spacer(Modifier.width(8.dp))
        }
        if (retryable) {
            ActionButton(Icons.Filled.Refresh, stringResource(R.string.rec_retry), onRetry)
            Spacer(Modifier.width(8.dp))
        }
        ActionButton(Icons.Filled.Delete, stringResource(R.string.common_delete), onDelete)
    }
}

@Composable
private fun ActionButton(icon: ImageVector, label: String, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val bg = if (focused) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface
    val fg = if (focused) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface
    Box(
        Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(bg)
            .onFocusChanged { focused = it.isFocused }
            .clickable(onClick = onClick)
            .padding(10.dp),
    ) {
        Icon(icon, contentDescription = label, tint = fg)
    }
}

@Composable
private fun statusLine(rec: Recording): String {
    // The time it was meant to record (scheduled window), falling back to when it actually began —
    // so a failed booking still shows "when", which is exactly what was missing before.
    val whenMillis = if (rec.scheduledStartMillis > 0) rec.scheduledStartMillis else rec.startedAtMillis
    val whenStr = if (whenMillis > 0) formatWhen(whenMillis) else ""
    return when (rec.status) {
        RecordingStatus.SCHEDULED ->
            stringResource(R.string.rec_status_scheduled_at, whenStr, rec.channelName)
        RecordingStatus.RECORDING ->
            stringResource(R.string.rec_status_recording, rec.channelName, formatSize(rec.sizeBytes))
        RecordingStatus.COMPLETED ->
            listOf(whenStr, rec.channelName, formatDuration(rec.durationMillis), formatSize(rec.sizeBytes))
                .filter { it.isNotBlank() }.joinToString(" · ")
        RecordingStatus.FAILED ->
            stringResource(R.string.rec_status_failed_at, whenStr, rec.error ?: stringResource(R.string.rec_error_unknown))
    }
}

@Composable
private fun statusColor(status: RecordingStatus): Color = when (status) {
    RecordingStatus.RECORDING -> Color(0xFFE53935)
    RecordingStatus.FAILED -> MaterialTheme.colorScheme.error
    RecordingStatus.SCHEDULED -> MaterialTheme.colorScheme.primary
    RecordingStatus.COMPLETED -> MaterialTheme.colorScheme.onSurfaceVariant
}

private fun formatSize(bytes: Long): String {
    if (bytes <= 0) return "0 MB"
    val mb = bytes / (1024.0 * 1024.0)
    return if (mb >= 1024) String.format("%.1f GB", mb / 1024.0) else String.format("%.0f MB", mb)
}

private fun formatDuration(millis: Long): String {
    val totalMinutes = millis / 60000
    val h = totalMinutes / 60
    val m = totalMinutes % 60
    return if (h > 0) "${h}h ${m}m" else "${m}m"
}
