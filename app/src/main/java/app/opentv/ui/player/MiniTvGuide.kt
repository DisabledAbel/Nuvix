/* Copyright (C) 2026 Nuvix Contributors. GPL-3.0-or-later. */
package app.opentv.ui.player

import android.text.format.DateFormat
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.opentv.data.model.Programme
import app.opentv.player.MiniGuideProgrammes
import app.opentv.player.MiniGuideState
import app.opentv.player.PlaybackQueue
import coil.compose.AsyncImage
import com.disabledabel.nuvix.R
import java.util.Date
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.Flow

/** Metadata-only overlay. It has no reference to a controller, player, or stream URL. */
@Composable
@OptIn(ExperimentalComposeUiApi::class)
internal fun MiniTvGuide(
    queue: List<PlaybackQueue.Item>,
    state: MiniGuideState,
    programmes: (List<String>, Long) -> Flow<List<Programme>>,
    enabled: Boolean,
    opacity: Float,
    previousId: Long?,
    onHighlight: (Long) -> Unit,
    onSelect: (Long) -> Unit,
    onToggleFavourites: () -> Unit,
    onRowFocusChanged: (Long, Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val rows = remember(queue, state.favouritesOnly) { state.channels(queue) }
    val listState = rememberLazyListState()
    val rowFocus = remember { FocusRequester() }
    val emptyFocus = remember { FocusRequester() }
    var visibleIds by remember { mutableStateOf(emptySet<Long>()) }
    // Lazy composition may prefetch a row. Only actually visible rows subscribe to Room.
    LaunchedEffect(listState) {
        snapshotFlow { listState.layoutInfo.visibleItemsInfo.mapNotNull { it.key as? Long }.toSet() }
            .distinctUntilChanged().collect { visibleIds = it }
    }
    val now by produceState(System.currentTimeMillis()) {
        while (true) { value = System.currentTimeMillis(); delay(15_000) }
    }
    LaunchedEffect(state.highlightedId, rows) {
        val index = rows.indexOfFirst { it.id == state.highlightedId }
        if (index >= 0) {
            val visible = listState.layoutInfo.visibleItemsInfo.any { it.index == index }
            if (!visible) listState.scrollToItem(index)
            withFrameNanos { }
            rowFocus.requestFocus()
        } else {
            emptyFocus.requestFocus()
        }
    }
    Column(
        modifier
            .fillMaxHeight()
            .widthIn(max = if (enabled) 520.dp else 380.dp)
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.safeDrawing)
            // Extra inset for televisions with overscan; video retains its original bounds.
            .padding(horizontal = 24.dp, vertical = 24.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(Color(0xFF101A26).copy(alpha = opacity))
            .padding(16.dp)
            .focusProperties {
                exit = { if (state.visible) FocusRequester.Cancel else FocusRequester.Default }
            }
            .focusGroup(),
    ) {
        Text(
            stringResource(if (enabled) R.string.mini_guide_title else R.string.common_channels),
            color = Color.White, style = MaterialTheme.typography.titleLarge,
        )
        if (enabled) {
            Text(stringResource(R.string.mini_guide_controls), color = Color.White, style = MaterialTheme.typography.bodyMedium)
            OutlinedButton(onClick = onToggleFavourites) {
                Text(
                    stringResource(if (state.favouritesOnly) R.string.mini_guide_favourites else R.string.mini_guide_browse_context),
                    color = Color.White,
                )
            }
        }
        val previous = rows.firstOrNull { it.isPlaying(previousId) && !it.isPlaying(state.playingId) }
        if (previous != null) {
            OutlinedButton(onClick = { onSelect(previous.id) }) {
                Text(
                    stringResource(R.string.mini_guide_last_channel, stringResource(R.string.player_last), previous.name),
                    color = Color.White, maxLines = 1,
                )
            }
        }
        if (rows.isEmpty()) {
            Text(
                stringResource(R.string.mini_guide_empty), color = Color.White,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.focusRequester(emptyFocus).focusable().padding(vertical = 24.dp),
            )
        }
        LazyColumn(state = listState, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(rows, key = { it.id }) { item ->
                MiniGuideRow(
                    item, item.isPlaying(state.playingId), item.id == state.highlightedId,
                    if (item.id == state.highlightedId) rowFocus else null,
                    enabled, programmes, item.id in visibleIds, now,
                    onFocus = { onHighlight(item.id) }, onClick = { onSelect(item.id) },
                    onRowFocusChanged = onRowFocusChanged,
                )
            }
        }
    }
}

@Composable
private fun MiniGuideRow(
    item: PlaybackQueue.Item,
    playing: Boolean,
    highlighted: Boolean,
    focusRequester: FocusRequester?,
    enabled: Boolean,
    programmes: (List<String>, Long) -> Flow<List<Programme>>,
    visible: Boolean,
    now: Long,
    onFocus: () -> Unit,
    onClick: () -> Unit,
    onRowFocusChanged: (Long, Boolean) -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val playingLabel = stringResource(R.string.mini_guide_playing)
    val focusedLabel = stringResource(R.string.mini_guide_highlighted)
    val accent = MaterialTheme.colorScheme.primary
    Column(
        Modifier.fillMaxWidth()
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .onFocusChanged {
                focused = it.isFocused
                onRowFocusChanged(item.id, it.isFocused)
                if (it.isFocused) onFocus()
            }
            .clip(RoundedCornerShape(10.dp))
            .background(if (highlighted) accent.copy(alpha = 0.28f) else Color.Black.copy(alpha = 0.15f))
            .border(if (focused) 3.dp else 1.dp, if (focused) accent else Color.White.copy(alpha = 0.2f), RoundedCornerShape(10.dp))
            .semantics(mergeDescendants = true) {
                selected = highlighted
                stateDescription = listOfNotNull(playingLabel.takeIf { playing }, focusedLabel.takeIf { highlighted }).joinToString(", ")
            }
            .clickable(onClick = onClick)
            .padding(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(item.number?.toString().orEmpty(), color = Color.White, style = MaterialTheme.typography.titleMedium, modifier = Modifier.width(44.dp))
            AsyncImage(item.logoUrl, contentDescription = null, modifier = Modifier.size(40.dp))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(item.name, color = Color.White, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (playing) Text(playingLabel, color = Color.White, style = MaterialTheme.typography.labelLarge)
            }
        }
        if (enabled && visible) {
            MiniGuideProgrammeInfo(item, programmes, now)
        }
    }
}

@Composable
private fun MiniGuideProgrammeInfo(item: PlaybackQueue.Item, load: (List<String>, Long) -> Flow<List<Programme>>, now: Long) {
    var queryTime by remember(item.id) { mutableLongStateOf(now) }
    val programmes by remember(item.epgCandidates, queryTime) {
        load(item.epgCandidates, queryTime).catch { emit(emptyList()) }
    }.collectAsState(initial = emptyList())
    // Refresh precisely at a programme boundary. A minute tick also recovers from clock changes
    // and metadata failures. Both are bounded local reads, never a feed/network refresh.
    LaunchedEffect(now / 60_000) { queryTime = now }
    LaunchedEffect(programmes) {
        val time = System.currentTimeMillis()
        val boundary = programmes.flatMap { listOf(it.startUtcMillis, it.endUtcMillis) }.filter { it > time }.minOrNull()
        if (boundary != null) { delay((boundary - time).coerceAtLeast(1)); queryTime = System.currentTimeMillis() }
    }
    val data = MiniGuideProgrammes.at(programmes, maxOf(now, queryTime))
    val formatter = DateFormat.getTimeFormat(LocalContext.current)
    Spacer(Modifier.height(8.dp))
    Text(
        data.current?.title?.takeIf { it.isNotBlank() } ?: stringResource(R.string.mini_guide_no_programme),
        color = Color.White, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis,
    )
    data.current?.let { current ->
        Text(
            stringResource(R.string.mini_guide_live_time, formatter.format(Date(current.startUtcMillis)), formatter.format(Date(current.endUtcMillis))),
            color = Color.White, style = MaterialTheme.typography.labelLarge,
        )
        val progress = current.progressAt(maxOf(now, queryTime))
        // This metadata bar is intentionally not focusable.
        Box(Modifier.fillMaxWidth().height(4.dp).background(Color.White.copy(alpha = 0.25f))) {
            Box(Modifier.fillMaxWidth(progress).height(4.dp).background(MaterialTheme.colorScheme.primary))
        }
    }
    Text(
        stringResource(R.string.mini_guide_next, data.next?.title?.takeIf { it.isNotBlank() } ?: stringResource(R.string.mini_guide_no_programme)),
        color = Color.White.copy(alpha = 0.9f), style = MaterialTheme.typography.bodyMedium,
        maxLines = 1, overflow = TextOverflow.Ellipsis,
    )
}
