/* Copyright (C) 2026 Nuvix Contributors. Licensed under GPL-3.0-or-later. */
package app.opentv.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import app.opentv.core.AppSettings
import app.opentv.player.DiagnosticSnapshot
import app.opentv.player.PlaybackDiagnostics
import kotlinx.coroutines.delay
import java.util.Locale

/** A separate focus window prevents remote keys from reaching channel/seek handlers underneath. */
@Composable
fun StreamInformation(diagnostics: PlaybackDiagnostics, onClose: () -> Unit) {
    val snapshot by diagnostics.snapshot.collectAsState()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(diagnostics, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) { diagnostics.sample(); delay(1_000) }
        }
    }
    StreamInformation(snapshot, diagnostics::clear, onClose)
}

/** Session recovery remains inspectable when no Media3 controller survived reconnection. */
@Composable
fun StreamInformation(snapshot: DiagnosticSnapshot, onClear: () -> Unit, onClose: () -> Unit) {
    val settings = rememberSettings()
    val enabled by settings.diagnosticsEnabled.collectAsState()
    val detailed by settings.diagnosticsDetailed.collectAsState()
    val technical by settings.diagnosticsTechnical.collectAsState()
    if (!enabled) { LaunchedEffect(Unit) { onClose() }; return }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        val closeFocus = remember { FocusRequester() }
        Column(Modifier.widthIn(max = 960.dp).fillMaxWidth(0.92f).fillMaxHeight(0.88f)
            .onPreviewKeyEvent {
                if ((it.key == Key.Back || it.key == Key.Escape) && it.type == KeyEventType.KeyDown) {
                    onClose(); true
                } else false
            }.background(Color(0xF2111721)).padding(16.dp)) {
            Text("Stream Information", color = Color.White, style = MaterialTheme.typography.titleLarge)
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DiagnosticButton(if (detailed) "View: Detailed" else "View: Compact") {
                    settings.setDiagnosticsDetailed(!detailed)
                }
                DiagnosticButton("Clear", onClick = onClear)
                DiagnosticButton("Close", Modifier.focusRequester(closeFocus), onClose)
            }
            LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                items(diagnosticRows(snapshot, detailed, technical), key = { it.first }) { (label, value) ->
                    DiagnosticRow(label, value)
                }
                if (detailed && technical) {
                    item { DiagnosticRow("History", "Last five minutes · ${snapshot.history.size} events (maximum 300)") }
                    items(snapshot.history.asReversed(), key = null) { event ->
                        DiagnosticRow("${event.elapsedMs / 1000}s · ${event.kind}",
                            "${event.state} · transfer ${rate(event.throughputBps)} · dropped ${event.droppedFrames}")
                    }
                }
            }
        }
        LaunchedEffect(Unit) { closeFocus.requestFocus() }
    }
}

/** Obtains the shared application settings for the diagnostic controls. */
@Composable
private fun rememberSettings(): AppSettings {
    val context = LocalContext.current
    return remember(context) { AppSettings.get(context) }
}

/** Displays a focusable stream information action. */
@Composable
private fun DiagnosticButton(label: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Button(onClick = onClick, modifier = modifier.focusProperties { canFocus = true }.onFocusChanged { focused = it.isFocused }
        .border(2.dp, if (focused) Color.White else Color.Transparent, MaterialTheme.shapes.extraLarge)) {
        Text(label)
    }
}

/** Displays one stream diagnostic label and formatted value. */
@Composable
private fun DiagnosticRow(label: String, value: String) {
    var focused by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().onFocusChanged { focused = it.isFocused }
        .semantics(mergeDescendants = true) { contentDescription = "$label: $value" }
        .border(2.dp, if (focused) Color.White else Color.Transparent).focusable().padding(8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(label, Modifier.weight(0.44f), color = Color(0xFFD0DAE8), style = MaterialTheme.typography.bodyMedium)
        Text(value, Modifier.weight(0.56f), color = Color.White, style = MaterialTheme.typography.bodyMedium)
    }
}

private const val UNAVAILABLE = "Unavailable"
/** Formats an optional diagnostic value using an unavailable-value fallback. */
private fun value(value: Any?) = value?.toString() ?: UNAVAILABLE
/** Formats a diagnostic duration in seconds. */
private fun seconds(ms: Long?) = ms?.let { String.format(Locale.ROOT, "%.1f s", it / 1000.0) } ?: UNAVAILABLE
/** Formats a diagnostic transfer rate for display. */
private fun rate(bps: Long?) = bps?.let { String.format(Locale.ROOT, "%.2f Mbps", it / 1_000_000.0) } ?: UNAVAILABLE

/** Builds stream information rows from the current diagnostic snapshot and detail preferences. */
internal fun diagnosticRows(s: DiagnosticSnapshot, detailed: Boolean, technical: Boolean): List<Pair<String, String>> = buildList {
    s.recovery?.let { recovery ->
        add("Recovery" to recovery.state.name)
        add("Recovery attempts" to "${recovery.attempts} / ${recovery.maximum}")
        add("Last failure" to value(recovery.failure))
        add("Recovery duration" to seconds(recovery.durationMs))
        add("Since successful playback" to seconds(recovery.sinceSuccessMs))
        if (detailed) recovery.history.forEachIndexed { index, entry -> add("Recovery event ${index + 1}" to entry.event.name) }
    }
    s.transition.forEach { add(it.phase.label to "${it.elapsedMs} ms from selection") }
    add("Playback health" to s.health.label)
    add("Playback status" to s.state)
    if (technical) {
        add("Resolution" to value(s.video.resolution))
        add("Video codec" to value(s.video.codec))
        add("Frame rate (format)" to (s.video.frameRate?.let { "$it fps" } ?: UNAVAILABLE))
    }
    add("Buffered duration" to seconds(s.bufferedMs))
    if (detailed) {
        add("Rebuffer count / stalls" to s.rebuffers.toString())
        add("Rebuffers per minute (session)" to (s.rebuffersPerMinute?.let { String.format(Locale.ROOT, "%.2f", it) } ?: UNAVAILABLE))
        add("Time buffering (includes startup)" to seconds(s.bufferingMs))
        add("Decoder errors" to s.decoderErrors.toString())
        add("Stream interruptions" to s.interruptions.toString())
        add("Last playback error" to (s.error ?: "None"))
        if (technical) {
            add("Video bitrate (measured)" to UNAVAILABLE)
            add("Video bitrate (declared by format)" to rate(s.video.declaredBitrate?.toLong()))
            add("Video decoder" to value(s.decoder))
            add("HDR format" to value(s.video.hdr))
            add("Dropped video frames" to value(s.droppedFrames))
            add("Rendered video frames" to value(s.renderedFrames))
            add("Audio codec" to value(s.audio.codec))
            add("Audio channels" to value(s.audio.channels))
            add("Audio sample rate" to (s.audio.sampleRate?.let { "$it Hz" } ?: UNAVAILABLE))
            add("Audio language" to value(s.audio.language))
            add("Selected audio track" to value(s.audioTrack))
            add("Stream protocol" to value(s.protocol))
            add("Network throughput (last transfer)" to rate(s.throughputBps))
            add("Available bandwidth (estimate)" to rate(s.estimatedBandwidthBps))
            add("Playback position" to seconds(s.positionMs))
            add("Playback speed" to (s.speed?.let { "${it}×" } ?: UNAVAILABLE))
        }
    }
}
