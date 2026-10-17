/* Nuvix modifications Copyright (C) 2026 Nuvix Contributors. GPL-3.0-or-later. */
package app.opentv.ui.channels

import android.widget.Toast
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import app.opentv.core.ServiceLocator
import app.opentv.data.model.*
import app.opentv.player.LivePlaybackSession
import app.opentv.reminders.ReminderScheduler
import app.opentv.ui.ChannelsViewModel
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import com.disabledabel.nuvix.R
import kotlinx.coroutines.launch

/** One details/actions surface shared by the guide and bounded programme search. */
@Composable
fun ProgrammeDetailsDialog(
    targetRow: ChannelsViewModel.Row,
    programme: Programme,
    nowMillis: Long,
    sources: List<Source>,
    activeRecordings: List<Recording>,
    liveSession: LivePlaybackSession,
    actionScope: kotlinx.coroutines.CoroutineScope,
    onReleasePreview: () -> Unit = {},
    onWatch: (Channel) -> Unit,
    onPlayCatchup: (String, String, String, String) -> Unit,
    onRecordingScheduled: () -> Unit = {},
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val graph = remember { ServiceLocator.get(context) }
    val showArtwork by graph.settings.showGuideArtwork.collectAsState()
    val channel = targetRow.primary
    var chosenVariant by remember(channel.id) { mutableStateOf(channel) }
    var sourceOptions by remember(channel.id) { mutableStateOf<List<Channel>>(emptyList()) }
    var actionPending by remember { mutableStateOf(false) }
    LaunchedEffect(channel.id) { sourceOptions = graph.catalogRepository.recordSourceOptions(channel) }
    val recordingSourceIds = activeRecordings.mapTo(mutableSetOf()) { it.sourceId }
    fun actionsFor(candidate: Channel, at: Long = nowMillis) = GuideProgrammeActions.evaluate(
        programme, candidate, sources.firstOrNull { it.id == candidate.sourceId }, at, recordingSourceIds)
    val actions = actionsFor(channel)
    val recordingActions = actionsFor(chosenVariant)
    val providerOptions = sourceOptions.filter { actionsFor(it).let { a -> a.canRecordNow || a.canScheduleRecording } }
    val multiSource = providerOptions.any { it.sourceId != channel.sourceId } || providerOptions.map { it.sourceId }.distinct().size > 1
    val recordingOptions = if (multiSource) providerOptions else targetRow.variants.filter {
        actionsFor(it).let { a -> a.canRecordNow || a.canScheduleRecording }
    }
    LaunchedEffect(recordingOptions) {
        if (recordingOptions.isNotEmpty() && recordingOptions.none { it.id == chosenVariant.id }) {
            chosenVariant = recordingOptions.firstOrNull { it.sourceId == channel.sourceId } ?: recordingOptions.first()
        }
    }
    val recordingThis = activeRecordings.firstOrNull { it.channelId == channel.id || it.channelId == chosenVariant.id }
    // Host scope survives dialog dismissal. Errors never display provider URLs or credentials.
    fun submit(block: suspend () -> Unit) {
        if (actionPending) return
        actionPending = true
        actionScope.launch {
            try {
                block()
                onDismiss()
            } catch (error: kotlinx.coroutines.CancellationException) {
                throw error
            } catch (_: Exception) {
                Toast.makeText(context, context.getString(R.string.guide_details_action_failed), Toast.LENGTH_LONG).show()
            } finally { actionPending = false }
        }
    }
    suspend fun freshActions(candidate: Channel): GuideProgrammeActions {
        val source = graph.sourceRepository.byId(candidate.sourceId)
        val busySources = graph.recordingRepository.active().mapTo(mutableSetOf()) { it.sourceId }
        return GuideProgrammeActions.evaluate(programme, candidate, source, System.currentTimeMillis(), busySources)
    }
    fun success(message: String) { Toast.makeText(context, message, Toast.LENGTH_LONG).show() }

    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier.width(440.dp).clip(RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.surface)
                .heightIn(max = 580.dp).verticalScroll(rememberScrollState()).padding(24.dp),
        ) {
            ProgrammeDetailsMetadata(programme, channel, actions.status, showArtwork)
            if (actions.canWatchNow) RecordActionRow(stringResource(R.string.guide_details_watch_now), primary = true, enabled = !actionPending) {
                submit { check(freshActions(channel).canWatchNow); onWatch(channel) }
            }
            if (multiSource || recordingOptions.size > 1) {
                Text(stringResource(if (multiSource) R.string.rec_record_from else R.string.rec_quality_label),
                    style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(6.dp))
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    recordingOptions.forEach { option ->
                        QualityChip(
                            label = if (multiSource) sources.firstOrNull { it.id == option.sourceId }?.name
                                ?: stringResource(R.string.rec_source_unknown)
                                else option.qualityLabel.ifBlank { stringResource(R.string.rec_quality_sd) },
                            selected = option.id == chosenVariant.id,
                            onClick = { chosenVariant = option },
                        )
                    }
                }
                Spacer(Modifier.height(16.dp))
            }
            when {
                actions.status == GuideProgrammeStatus.AIRING && recordingThis != null ->
                    RecordActionRow(stringResource(R.string.rec_stop_recording), primary = true, enabled = !actionPending) {
                        graph.recordingEngine.stop(recordingThis.id)
                        success(context.getString(R.string.guide_details_stopping_recording))
                        onDismiss()
                    }
                recordingActions.canRecordNow -> RecordActionRow(stringResource(R.string.rec_record_now), primary = true, enabled = !actionPending) {
                    val selected = chosenVariant
                    submit {
                        check(freshActions(selected).canRecordNow)
                        graph.recordingEngine.startChannel(selected, programme)
                        success(context.getString(R.string.guide_details_starting_recording, selected.shownName))
                        onRecordingScheduled()
                    }
                }
                actions.canCatchup -> RecordActionRow(stringResource(R.string.guide_watch_from_start), primary = true, enabled = !actionPending) {
                    submit {
                        val source = graph.sourceRepository.byId(channel.sourceId)
                        val busySources = graph.recordingRepository.active().mapTo(mutableSetOf()) { it.sourceId }
                        check(GuideProgrammeActions.evaluate(programme, channel, source, System.currentTimeMillis(), busySources).canCatchup)
                        checkNotNull(source)
                        val minutes = (programme.durationMillis / 60_000).coerceIn(1, Int.MAX_VALUE.toLong()).toInt()
                        val url = graph.xtreamApi.catchupUrl(source, channel.streamId, programme.startUtcMillis, minutes)
                        onReleasePreview()
                        onPlayCatchup("catchup:${channel.id}:${programme.startUtcMillis}", url,
                            "${channel.shownName} — ${programme.title}", source.userAgent)
                    }
                }
                recordingActions.canScheduleRecording -> RecordActionRow(stringResource(R.string.rec_schedule_recording), primary = true, enabled = !actionPending) {
                    val selected = chosenVariant
                    submit {
                        check(freshActions(selected).canScheduleRecording)
                        graph.recordingEngine.scheduleProgramme(selected, programme)
                        success(context.getString(R.string.rec_scheduled_title, programme.title))
                        onRecordingScheduled()
                    }
                }
            }
            if (actions.canSetReminder) {
                var reminderSet by remember(channel.id, programme.startUtcMillis) { mutableStateOf<Boolean?>(null) }
                LaunchedEffect(channel.id, programme.startUtcMillis) {
                    reminderSet = graph.reminderRepository.forProgramme(channel.id, programme.startUtcMillis) != null
                }
                if (reminderSet == true) {
                    RecordActionRow(stringResource(R.string.guide_cancel_reminder), enabled = !actionPending) {
                        submit {
                            graph.reminderRepository.forProgramme(channel.id, programme.startUtcMillis)?.let {
                                ReminderScheduler.cancel(context, it.id)
                                graph.reminderRepository.delete(it.id)
                            }
                            success(context.getString(R.string.guide_reminder_removed))
                        }
                    }
                } else if (reminderSet == false) {
                    RecordActionRow(stringResource(R.string.guide_remind_me), enabled = !actionPending) {
                        submit {
                            check(freshActions(channel).canSetReminder)
                            check(setReminder(graph.reminderRepository, context, channel, programme, autoTune = false))
                            success(context.getString(R.string.guide_reminder_set, programme.title))
                        }
                    }
                    RecordActionRow(stringResource(R.string.guide_auto_switch), enabled = !actionPending) {
                        submit {
                            check(freshActions(channel).canSetReminder)
                            check(setReminder(graph.reminderRepository, context, channel, programme, autoTune = true))
                            success(context.getString(R.string.guide_will_switch, channel.shownName))
                        }
                    }
                }
            }
            if (recordingActions.canRecordSeries) RecordActionRow(stringResource(R.string.rec_record_series), enabled = !actionPending) {
                val selected = chosenVariant
                submit {
                    val current = freshActions(selected)
                    check(current.canRecordSeries)
                    graph.recordingEngine.recordSeries(selected, programme, targetRow.programmes)
                    success(context.getString(R.string.rec_series_recording_set, programme.title))
                    onRecordingScheduled()
                }
            }
            if (actions.canViewChannel) RecordActionRow(stringResource(R.string.guide_watch_channel), enabled = !actionPending) {
                submit { check(freshActions(channel).canViewChannel); onWatch(channel) }
            }
            RecordActionRow(stringResource(R.string.guide_details_back)) { onDismiss() }
        }
    }
}

/** Inserts a reminder for a future programme and arms its alarm. No-op if one already exists. */
internal suspend fun setReminder(
    repository: app.opentv.data.repo.ReminderRepository,
    context: android.content.Context,
    channel: Channel,
    programme: Programme,
    autoTune: Boolean,
    nowMillis: Long? = null,
): Boolean {
    val now = nowMillis ?: System.currentTimeMillis()
    if (programme.startUtcMillis <= now || programme.startUtcMillis < 0 || programme.endUtcMillis <= programme.startUtcMillis) return false
    val existing = repository.forProgramme(channel.id, programme.startUtcMillis)
    // The lookup can suspend across the programme boundary; never arm a newly stale reminder.
    if (programme.startUtcMillis <= (nowMillis ?: System.currentTimeMillis())) return false
    if (existing != null) return existing.autoTune == autoTune
    val id = repository.insert(Reminder(channelId = channel.id, channelName = channel.shownName,
        logoUrl = channel.logoUrl, title = programme.title, startUtcMillis = programme.startUtcMillis,
        endUtcMillis = programme.endUtcMillis, autoTune = autoTune, createdAtMillis = now))
    try {
        ReminderScheduler.set(context, id, programme.startUtcMillis)
    } catch (error: Exception) {
        repository.delete(id)
        throw error
    }
    return true
}

@Composable
internal fun RecordActionRow(label: String, primary: Boolean = false, enabled: Boolean = true, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val bg = when {
        focused -> MaterialTheme.colorScheme.primary
        primary -> MaterialTheme.colorScheme.primaryContainer
        else -> MaterialTheme.colorScheme.surfaceVariant
    }
    val fg = when {
        focused -> MaterialTheme.colorScheme.onPrimary
        primary -> MaterialTheme.colorScheme.onPrimaryContainer
        else -> MaterialTheme.colorScheme.onSurface
    }
    Text(
        label,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = if (primary) FontWeight.SemiBold else FontWeight.Normal,
        color = fg,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp)
            .onFocusChanged { focused = it.isFocused }
            .clip(RoundedCornerShape(10.dp))
            .background(bg)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
    )
}

/** A small focusable quality pill (FHD / HD / SD…) for the record dialog's "record HD or SD" choice. */
@Composable
internal fun QualityChip(label: String, selected: Boolean, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val bg = when {
        focused -> MaterialTheme.colorScheme.primary
        selected -> MaterialTheme.colorScheme.primaryContainer
        else -> MaterialTheme.colorScheme.surfaceVariant
    }
    val fg = when {
        focused -> MaterialTheme.colorScheme.onPrimary
        selected -> MaterialTheme.colorScheme.onPrimaryContainer
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Text(
        label,
        style = MaterialTheme.typography.labelLarge,
        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
        color = fg,
        modifier = Modifier
            .onFocusChanged { focused = it.isFocused }
            .clip(RoundedCornerShape(8.dp))
            .background(bg)
            .semantics { this.selected = selected }
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
    )
}
