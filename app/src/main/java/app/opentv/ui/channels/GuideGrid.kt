/* Nuvix modifications Copyright (C) 2026 Nuvix Contributors. GPL-3.0-or-later. */
/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.ui.channels

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.StarOutline
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.opentv.core.GuideLayout
import app.opentv.core.GuidePosition
import app.opentv.core.GuideTimelineDensity
import app.opentv.data.model.Programme
import app.opentv.data.model.shownName
import app.opentv.ui.ChannelsViewModel
import coil.compose.AsyncImage
import com.disabledabel.nuvix.R
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Channel-lazy guide with a synchronized timeline. Programme cells outside the visible time
 * range are omitted, and all cell widths remain exact so short shows cannot shift the schedule.
 * Focusing a row only updates the host highlight; explicit selection owns playback as before.
 */
@Composable
fun GuideGrid(
    rows: List<ChannelsViewModel.Row>,
    windowStartMillis: Long,
    selectedKey: Any?,
    onSelectRow: (ChannelsViewModel.Row) -> Unit,
    onFocusRow: (ChannelsViewModel.Row) -> Unit,
    onProgramme: (ChannelsViewModel.Row, Programme) -> Unit = { _, _ -> },
    onToggleFavourite: (ChannelsViewModel.Row) -> Unit = {},
    onExitLeftFromChannel: () -> Boolean = { false },
    dayOffset: Int = 0,
    modifier: Modifier = Modifier,
    layout: GuideLayout = GuideLayout.STANDARD,
    density: GuideTimelineDensity = GuideTimelineDensity.COMFORTABLE,
    showProgress: Boolean = true,
    showArtwork: Boolean = true,
    rememberPosition: Boolean = true,
    nowMillis: Long = System.currentTimeMillis(),
    currentChannelId: Long? = null,
    restorePosition: GuidePosition? = null,
    onPositionChanged: (Long, Long) -> Unit = { _, _ -> },
    onVisibleRowsChanged: (List<ChannelsViewModel.Row>) -> Unit = {},
    onPrevDay: (() -> Unit)? = null,
    onNextDay: (() -> Unit)? = null,
    onJumpToNow: (() -> Unit)? = null,
    canGoPrevDay: Boolean = true,
    canGoNextDay: Boolean = true,
    onReturnToCurrentChannel: () -> Unit = {},
) {
    val scroll = rememberScrollState()
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val displayDensity = LocalDensity.current
    val metrics = remember(layout, density) { guideLayoutMetrics(layout, density) }
    val pixelsPerMinute = with(displayDensity) { metrics.minuteWidthDp.dp.toPx() }
    val latestRows by rememberUpdatedState(rows)
    val reportVisible by rememberUpdatedState(onVisibleRowsChanged)
    var restored by rememberSaveable { mutableStateOf(false) }
    var focusedChannelId by rememberSaveable { mutableStateOf<Long?>(null) }
    var focusedTime by rememberSaveable { mutableStateOf<Long?>(null) }
    var requestSerial by remember { mutableStateOf(0) }
    var focusRequest by remember { mutableStateOf<GuideFocusRequest?>(null) }
    var pendingNow by remember { mutableStateOf(false) }
    var pendingCurrentChannel by remember { mutableStateOf(false) }
    var previousDay by remember { mutableStateOf(dayOffset) }
    var previousWindowStart by remember { mutableStateOf(windowStartMillis) }

    fun savePosition(channelId: Long, timeMillis: Long) {
        focusedChannelId = channelId
        focusedTime = timeMillis
        if (rememberPosition) onPositionChanged(channelId, timeMillis)
    }
    fun scrollToTime(timeMillis: Long) {
        scope.launch {
            val target = ((timeMillis - windowStartMillis) / 60_000.0 * pixelsPerMinute).toInt()
            scroll.scrollTo(target.coerceIn(0, scroll.maxValue))
        }
    }
    fun requestRow(index: Int, timeMillis: Long?, channelOnly: Boolean = false) {
        val row = rows.getOrNull(index) ?: return
        val programme = if (channelOnly) null else timeMillis?.let { guideProgrammeAt(row.programmes, it) }
        savePosition(row.primary.id, timeMillis ?: windowStartMillis)
        val request = GuideFocusRequest(row.key, programme?.startUtcMillis, ++requestSerial)
        scope.launch {
            if (listState.layoutInfo.visibleItemsInfo.none { it.index == index }) listState.scrollToItem(index)
            if (programme != null) {
                val startPx = ((maxOf(programme.startUtcMillis, windowStartMillis) - windowStartMillis) / 60_000.0 * pixelsPerMinute).toInt()
                val endPx = ((programme.endUtcMillis - windowStartMillis) / 60_000.0 * pixelsPerMinute).toInt()
                if (startPx < scroll.value || endPx > scroll.value + scroll.viewportSize) {
                    scroll.scrollTo(startPx.coerceIn(0, scroll.maxValue))
                }
            }
            if (request.serial == requestSerial) focusRequest = request
        }
    }
    fun moveRow(row: ChannelsViewModel.Row, delta: Int, timeMillis: Long?, channelOnly: Boolean): Boolean {
        val index = rows.indexOfFirst { it.primary.id == focusedChannelId }.takeIf { it >= 0 }
            ?: rows.indexOfFirst { it.key == row.key }
        val target = index + delta
        if (target !in rows.indices) return false
        requestRow(target, timeMillis, channelOnly)
        return true
    }

    // Report channel IDs only when the viewport changes. Programme updates for the same slice
    // do not trigger another load. Even huge catalogues request at most 60 prefetched rows.
    LaunchedEffect(listState) {
        snapshotFlow {
            val currentRows = latestRows
            val range = guideVisibleRange(currentRows.size, listState.layoutInfo.visibleItemsInfo.map { it.index })
            range.map { currentRows[it] }
        }.distinctUntilChanged { previous, next ->
            previous.map { it.guideLoadKey() } == next.map { it.guideLoadKey() }
        }.collect { reportVisible(it) }
    }
    LaunchedEffect(dayOffset, pendingNow, windowStartMillis) {
        val dayChanged = previousDay != dayOffset
        val oldWindowStart = previousWindowStart
        previousDay = dayOffset
        previousWindowStart = windowStartMillis
        if (pendingNow) {
            if (nowMillis in windowStartMillis until windowStartMillis + GUIDE_WINDOW_MILLIS) {
                scroll.scrollTo(((nowMillis - windowStartMillis) / 60_000.0 * pixelsPerMinute).toInt().coerceIn(0, scroll.maxValue))
                pendingNow = false
            }
        } else if (dayChanged) {
            scroll.scrollTo(0)
        } else if (oldWindowStart != windowStartMillis && scroll.value > 0) {
            val viewedTime = oldWindowStart + (scroll.value / pixelsPerMinute * 60_000.0).toLong()
            val target = ((viewedTime - windowStartMillis) / 60_000.0 * pixelsPerMinute).toInt()
            scroll.scrollTo(target.coerceIn(0, scroll.maxValue))
        }
    }
    LaunchedEffect(rows.isNotEmpty(), rememberPosition) {
        if (rows.isEmpty() || restored) return@LaunchedEffect
        restored = true
        if (rememberPosition && restorePosition != null) {
            val index = rows.indexOfFirst { row -> row.variants.any { it.id == restorePosition.channelId } }
            if (index >= 0) {
                listState.scrollToItem(index)
                focusedChannelId = rows[index].primary.id
                focusedTime = restorePosition.timeMs
                focusRequest = GuideFocusRequest(rows[index].key, null, ++requestSerial)
            }
            val target = ((restorePosition.timeMs - windowStartMillis) / 60_000.0 * pixelsPerMinute).toInt()
            scroll.scrollTo(target.coerceIn(0, scroll.maxValue))
        } else {
            if (!rememberPosition) listState.scrollToItem(0)
            val initialTime = nowMillis.takeIf { it in windowStartMillis until windowStartMillis + GUIDE_WINDOW_MILLIS }
                ?: windowStartMillis
            val target = ((initialTime - windowStartMillis) / 60_000.0 * pixelsPerMinute).toInt()
            scroll.scrollTo(target.coerceIn(0, scroll.maxValue))
        }
    }
    LaunchedEffect(pendingCurrentChannel, rows, currentChannelId) {
        if (pendingCurrentChannel) {
            val index = rows.indexOfFirst { row -> row.variants.any { it.id == currentChannelId } }
            if (index >= 0) {
                pendingCurrentChannel = false
                requestRow(index, nowMillis, channelOnly = true)
            }
        }
    }
    // Keep the same timestamp visible when the user changes timeline density.
    var previousMinuteWidth by remember { mutableStateOf(pixelsPerMinute) }
    LaunchedEffect(pixelsPerMinute) {
        val oldWidth = previousMinuteWidth
        previousMinuteWidth = pixelsPerMinute
        if (oldWidth != pixelsPerMinute && oldWidth > 0) {
            scroll.scrollTo((scroll.value / oldWidth * pixelsPerMinute).toInt().coerceIn(0, scroll.maxValue))
        }
    }

    Column(modifier.fillMaxSize().testTag("advanced-guide")) {
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (onPrevDay != null) GuideNavigationButton(onClick = onPrevDay, enabled = canGoPrevDay) { Text("Previous day") }
            GuideNavigationButton(
                onClick = {
                    val target = (scroll.value - 60 * pixelsPerMinute).toInt().coerceAtLeast(0)
                    scope.launch {
                        scroll.scrollTo(target)
                        (focusedChannelId ?: rows.getOrNull(listState.firstVisibleItemIndex)?.primary?.id)?.let {
                            savePosition(it, guideTimeAtOffset(windowStartMillis, scroll.value / displayDensity.density, metrics.minuteWidthDp))
                        }
                    }
                },
                enabled = scroll.value > 0,
            ) { Text("Previous hour") }
            GuideNavigationButton(onClick = {
                pendingNow = true
                onJumpToNow?.invoke()
                if (nowMillis in windowStartMillis until windowStartMillis + GUIDE_WINDOW_MILLIS) scrollToTime(nowMillis)
                focusedChannelId?.let { savePosition(it, nowMillis) }
            }) { Text("Now") }
            GuideNavigationButton(
                onClick = {
                    val target = (scroll.value + 60 * pixelsPerMinute).toInt().coerceAtMost(scroll.maxValue)
                    scope.launch {
                        scroll.scrollTo(target)
                        (focusedChannelId ?: rows.getOrNull(listState.firstVisibleItemIndex)?.primary?.id)?.let {
                            savePosition(it, guideTimeAtOffset(windowStartMillis, scroll.value / displayDensity.density, metrics.minuteWidthDp))
                        }
                    }
                },
                enabled = scroll.value < scroll.maxValue,
            ) { Text("Next hour") }
            if (onNextDay != null) GuideNavigationButton(onClick = onNextDay, enabled = canGoNextDay) { Text("Next day") }
            val currentIndex = rows.indexOfFirst { row -> row.variants.any { it.id == currentChannelId } }
            if (currentChannelId != null) GuideNavigationButton(onClick = {
                pendingNow = true
                onJumpToNow?.invoke()
                if (currentIndex >= 0) requestRow(currentIndex, nowMillis, channelOnly = true)
                else {
                    pendingCurrentChannel = true
                    onReturnToCurrentChannel()
                }
            }) { Text("Current channel") }
        }
        TimeHeader(windowStartMillis, scroll, metrics.minuteWidthDp, nowMillis)
        if (rows.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                Text("No channels match this guide filter.", style = MaterialTheme.typography.bodyLarge)
            }
        }
        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            items(rows, key = { it.key }) { row ->
                GuideRow(
                    row = row,
                    windowStartMillis = windowStartMillis,
                    nowMillis = nowMillis,
                    scroll = scroll,
                    layout = layout,
                    metrics = metrics,
                    showProgress = showProgress,
                    showArtwork = showArtwork,
                    isSelected = row.key == selectedKey,
                    focusRequest = focusRequest?.takeIf { it.rowKey == row.key },
                    onFocusRequested = { serial -> if (focusRequest?.serial == serial) focusRequest = null },
                    onSelect = { onSelectRow(row) },
                    onFocus = { time ->
                        savePosition(row.primary.id, time ?: guideTimeAtOffset(windowStartMillis, scroll.value / displayDensity.density, metrics.minuteWidthDp))
                        onFocusRow(row)
                    },
                    onProgramme = { onProgramme(row, it) },
                    onMoveRow = { delta, time, channelOnly -> moveRow(row, delta, time, channelOnly) },
                    onMoveProgramme = { programme -> requestRow(rows.indexOf(row), programme.startUtcMillis) },
                    onToggleFavourite = { onToggleFavourite(row) },
                    onExitLeft = onExitLeftFromChannel,
                )
            }
        }
    }
}

@Composable
private fun GuideNavigationButton(onClick: () -> Unit, enabled: Boolean = true, content: @Composable () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    TextButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.heightIn(min = 48.dp)
            .onFocusChanged { focused = it.isFocused }
            .then(if (focused) Modifier.border(2.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(12.dp)) else Modifier),
    ) { content() }
}

private data class GuideFocusRequest(val rowKey: Any, val programmeStart: Long?, val serial: Int)

// Include matching inputs so a provider refresh can reload visible programmes without a scroll.
private fun ChannelsViewModel.Row.guideLoadKey() = primary.id to variants.map { it.id to it.epgCandidates }

/**
 * The live channels as a plain vertical list instead of the time-grid — one channel per row with
 * its logo, name, and now/next line. This is the "list mode instead of tiles" some users prefer on
 * a busy line-up. It keeps the exact same focus/key contract as [GuideGrid] so [HomeScreen] can
 * swap between the two with nothing else changing: focus follows the d-pad (updating the preview),
 * OK opens the channel menu, and LEFT from a row asks the host to reopen the category rail.
 */
@Composable
fun ChannelList(
    rows: List<ChannelsViewModel.Row>,
    selectedKey: Any?,
    onSelectRow: (ChannelsViewModel.Row) -> Unit,
    onFocusRow: (ChannelsViewModel.Row) -> Unit,
    onToggleFavourite: (ChannelsViewModel.Row) -> Unit = {},
    onExitLeftFromChannel: () -> Boolean = { false },
    modifier: Modifier = Modifier,
    nowMillis: Long = System.currentTimeMillis(),
    onVisibleRowsChanged: (List<ChannelsViewModel.Row>) -> Unit = {},
    rememberPosition: Boolean = true,
    restorePosition: GuidePosition? = null,
    onPositionChanged: (Long, Long) -> Unit = { _, _ -> },
) {
    val listState = rememberLazyListState()
    val latestRows by rememberUpdatedState(rows)
    val reportVisible by rememberUpdatedState(onVisibleRowsChanged)
    var restored by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(listState) {
        snapshotFlow {
            val currentRows = latestRows
            guideVisibleRange(currentRows.size, listState.layoutInfo.visibleItemsInfo.map { it.index }).map { currentRows[it] }
        }.distinctUntilChanged { previous, next -> previous.map { it.guideLoadKey() } == next.map { it.guideLoadKey() } }
            .collect { reportVisible(it) }
    }
    LaunchedEffect(rows.isNotEmpty(), rememberPosition) {
        if (rows.isEmpty() || restored) return@LaunchedEffect
        restored = true
        if (rememberPosition && restorePosition != null) {
            rows.indexOfFirst { row -> row.variants.any { it.id == restorePosition.channelId } }
                .takeIf { it >= 0 }?.let { listState.scrollToItem(it) }
        } else if (!rememberPosition) listState.scrollToItem(0)
    }
    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        items(rows, key = { it.key }) { row ->
            ChannelListRow(
                row = row,
                nowMillis = nowMillis,
                isSelected = row.key == selectedKey,
                onSelect = { onSelectRow(row) },
                onFocus = {
                    if (rememberPosition) onPositionChanged(row.primary.id, nowMillis)
                    onFocusRow(row)
                },
                onToggleFavourite = { onToggleFavourite(row) },
                onExitLeft = onExitLeftFromChannel,
            )
        }
    }
}

@Composable
private fun ChannelListRow(
    row: ChannelsViewModel.Row,
    nowMillis: Long,
    isSelected: Boolean,
    onSelect: () -> Unit,
    onFocus: () -> Unit,
    onToggleFavourite: () -> Unit,
    onExitLeft: () -> Boolean,
) {
    var focused by remember { mutableStateOf(false) }
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(
                if (isSelected) MaterialTheme.colorScheme.primaryContainer
                else MaterialTheme.colorScheme.surface,
            )
            .then(
                if (focused) Modifier.border(2.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(8.dp))
                else Modifier,
            )
            // LEFT from a row reopens the collapsed rail, exactly like the grid's channel column.
            .onPreviewKeyEvent { e ->
                if (e.type == KeyEventType.KeyDown && e.key == Key.DirectionLeft) onExitLeft() else false
            }
            .onFocusChanged {
                focused = it.isFocused
                if (it.isFocused) onFocus()
            }
            .clickable(onClick = onSelect)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        row.primary.number?.let { num ->
            Text(
                "$num",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                modifier = Modifier.width(30.dp),
            )
        }
        AsyncImage(
            model = row.primary.logoUrl,
            contentDescription = null,
            modifier = Modifier.size(40.dp).clip(RoundedCornerShape(4.dp)),
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    row.primary.shownName,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (row.variants.size > 1) {
                    Spacer(Modifier.width(8.dp))
                    Text(
                        stringResource(R.string.guide_qualities_count, row.variants.size),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                    )
                }
            }
            Spacer(Modifier.height(2.dp))
            val nowProg = row.now
            Text(
                text = nowProg?.let { "${clockFormat.format(Date(it.startUtcMillis))}  ${it.title}" }
                    ?: stringResource(R.string.guide_no_info),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (nowProg != null) {
                Spacer(Modifier.height(6.dp))
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(3.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                ) {
                    Box(
                        Modifier
                            .fillMaxWidth(nowProg.progressAt(nowMillis).coerceIn(0f, 1f))
                            .height(3.dp)
                            .background(MaterialTheme.colorScheme.primary),
                    )
                }
            }
            row.next?.let { next ->
                Spacer(Modifier.height(3.dp))
                Text(
                    stringResource(R.string.guide_next_prefix, clockFormat.format(Date(next.startUtcMillis)), next.title),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        IconButton(onClick = onToggleFavourite, modifier = Modifier.size(40.dp)) {
            Icon(
                imageVector = if (row.primary.favourite) Icons.Filled.Star else Icons.Outlined.StarOutline,
                contentDescription = if (row.primary.favourite) stringResource(R.string.common_remove_favourite)
                else stringResource(R.string.common_favourite),
                tint = if (row.primary.favourite) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun TimeHeader(windowStartMillis: Long, scroll: ScrollState, minuteWidthDp: Float, nowMillis: Long) {
    Row(Modifier.fillMaxWidth().height(32.dp)) {
        Box(Modifier.width(CHANNEL_COLUMN + 3.dp).fillMaxHeight(), contentAlignment = Alignment.CenterStart) {
            Text("Channel", modifier = Modifier.padding(start = 10.dp), style = MaterialTheme.typography.labelLarge)
        }
        Box(Modifier.horizontalScroll(scroll).background(MaterialTheme.colorScheme.surface)) {
            Row {
                repeat(48) { i ->
                    Box(Modifier.width((30 * minuteWidthDp).dp).height(32.dp).padding(start = 6.dp), contentAlignment = Alignment.CenterStart) {
                        Text(clockFormat.format(Date(windowStartMillis + i * 30 * 60_000L)), style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
            CurrentTimeMarker(windowStartMillis, nowMillis, minuteWidthDp)
        }
    }
}

@Composable
private fun GuideRow(
    row: ChannelsViewModel.Row,
    windowStartMillis: Long,
    nowMillis: Long,
    scroll: ScrollState,
    layout: GuideLayout,
    metrics: GuideLayoutMetrics,
    showProgress: Boolean,
    showArtwork: Boolean,
    isSelected: Boolean,
    focusRequest: GuideFocusRequest?,
    onFocusRequested: (Int) -> Unit,
    onSelect: () -> Unit,
    onFocus: (Long?) -> Unit,
    onProgramme: (Programme) -> Unit,
    onMoveRow: (Int, Long?, Boolean) -> Boolean,
    onMoveProgramme: (Programme) -> Unit,
    onToggleFavourite: () -> Unit,
    onExitLeft: () -> Boolean,
) {
    var focused by remember { mutableStateOf(false) }
    val channelFocus = remember { FocusRequester() }
    val slices = remember(row.programmes, windowStartMillis) { guideProgrammeSlices(row.programmes, windowStartMillis) }
    val localDensity = LocalDensity.current
    // Honour font scaling instead of clipping large accessibility text into a fixed-height row.
    val rowHeight = (metrics.rowHeightDp * localDensity.fontScale.coerceAtLeast(1f)).dp
    LaunchedEffect(focusRequest) {
        val request = focusRequest ?: return@LaunchedEffect
        if (request.programmeStart == null || slices.none { it.programme.startUtcMillis == request.programmeStart }) {
            channelFocus.requestFocus()
            onFocusRequested(request.serial)
        }
    }
    Row(Modifier.fillMaxWidth().height(rowHeight)) {
        Row(
            Modifier.width(CHANNEL_COLUMN).fillMaxHeight()
                .clip(RoundedCornerShape(8.dp))
                .background(if (isSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface)
                .then(if (focused) Modifier.border(2.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(8.dp)) else Modifier)
                .focusRequester(channelFocus)
                .onPreviewKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown) false else when (event.key) {
                        Key.DirectionLeft -> onExitLeft()
                        Key.DirectionUp -> onMoveRow(-1, null, true)
                        Key.DirectionDown -> onMoveRow(1, null, true)
                        else -> false
                    }
                }
                .onFocusChanged { focused = it.isFocused; if (it.isFocused) onFocus(null) }
                .clickable(onClick = onSelect)
                .testTag("guide-channel-${row.primary.id}")
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            row.primary.number?.let { Text("$it", style = MaterialTheme.typography.labelMedium, maxLines = 1, modifier = Modifier.width(28.dp)) }
            AsyncImage(model = row.primary.logoUrl, contentDescription = null, modifier = Modifier.size(if (layout == GuideLayout.COMPACT) 28.dp else 34.dp))
            Spacer(Modifier.width(6.dp))
            Column(Modifier.weight(1f)) {
                Text(row.primary.shownName, style = if (layout == GuideLayout.COMPACT) MaterialTheme.typography.bodyMedium else MaterialTheme.typography.titleMedium,
                    maxLines = if (layout == GuideLayout.EXPANDED) 2 else 1, overflow = TextOverflow.Ellipsis)
                if (row.variants.size > 1 && layout != GuideLayout.COMPACT) {
                    Text(stringResource(R.string.guide_qualities_count, row.variants.size), style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary, maxLines = 1)
                }
            }
            IconButton(onClick = onToggleFavourite, modifier = Modifier.size(40.dp)) {
                Icon(if (row.primary.favourite) Icons.Filled.Star else Icons.Outlined.StarOutline,
                    contentDescription = if (row.primary.favourite) stringResource(R.string.common_remove_favourite) else stringResource(R.string.common_favourite),
                    tint = if (row.primary.favourite) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Spacer(Modifier.width(3.dp))
        BoxWithConstraints(Modifier.weight(1f).fillMaxHeight()) {
            val viewportWidth = maxWidth
            val visibleStart = guideTimeAtOffset(windowStartMillis, scroll.value / localDensity.density, metrics.minuteWidthDp)
            val visibleEnd = guideTimeAtOffset(windowStartMillis, scroll.value / localDensity.density + viewportWidth.value, metrics.minuteWidthDp)
            val visible = visibleGuideSlices(slices, visibleStart, visibleEnd)
            Box(Modifier.horizontalScroll(scroll).width((24 * 60 * metrics.minuteWidthDp).dp).fillMaxHeight()) {
                if (slices.isEmpty()) {
                    Box(Modifier.offset(x = (scroll.value / localDensity.density).dp).width(viewportWidth).fillMaxHeight()
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)).padding(horizontal = 12.dp),
                        contentAlignment = Alignment.CenterStart) {
                        Text(stringResource(R.string.guide_no_info), style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                visible.forEach { slice ->
                    key(slice.programme.feedId, slice.programme.epgChannelId, slice.programme.startUtcMillis) {
                        val programme = slice.programme
                        val index = slices.indexOf(slice)
                        ProgrammeBlock(
                            programme = programme,
                            channelName = row.primary.shownName,
                            widthDp = ((slice.endMillis - slice.startMillis) / 60_000.0 * metrics.minuteWidthDp).toFloat(),
                            modifier = Modifier.offset(x = guideOffsetDp(windowStartMillis, slice.startMillis, metrics.minuteWidthDp).dp),
                            layout = layout,
                            metrics = metrics,
                            nowMillis = nowMillis,
                            showProgress = showProgress,
                            showArtwork = showArtwork,
                            request = focusRequest?.takeIf { it.programmeStart == programme.startUtcMillis },
                            onFocusRequested = onFocusRequested,
                            onFocus = { onFocus(programme.startUtcMillis) },
                            onMoveRow = { delta -> onMoveRow(delta, programme.startUtcMillis, false) },
                            onMoveProgramme = { delta -> slices.getOrNull(index + delta)?.let { onMoveProgramme(it.programme); true } ?: false },
                            onClick = { onProgramme(programme) },
                        )
                    }
                }
                CurrentTimeMarker(windowStartMillis, nowMillis, metrics.minuteWidthDp)
            }
        }
    }
}

@Composable
private fun ProgrammeBlock(
    programme: Programme,
    channelName: String,
    widthDp: Float,
    modifier: Modifier,
    layout: GuideLayout,
    metrics: GuideLayoutMetrics,
    nowMillis: Long,
    showProgress: Boolean,
    showArtwork: Boolean,
    request: GuideFocusRequest?,
    onFocusRequested: (Int) -> Unit,
    onFocus: () -> Unit,
    onMoveRow: (Int) -> Boolean,
    onMoveProgramme: (Int) -> Boolean,
    onClick: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val requester = remember { FocusRequester() }
    val isNow = programme.isLiveAt(nowMillis)
    val foreground = when {
        focused -> MaterialTheme.colorScheme.onPrimary
        isNow -> MaterialTheme.colorScheme.onPrimaryContainer
        else -> MaterialTheme.colorScheme.onSurface
    }
    val timeRange = "${clockFormat.format(Date(programme.startUtcMillis))}–${clockFormat.format(Date(programme.endUtcMillis))}"
    LaunchedEffect(request) {
        if (request != null) { requester.requestFocus(); onFocusRequested(request.serial) }
    }
    Box(
        modifier.width(widthDp.coerceAtLeast(1f).dp).fillMaxHeight().padding(end = if (widthDp >= 8f) 3.dp else 0.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(when { focused -> MaterialTheme.colorScheme.primary; isNow -> MaterialTheme.colorScheme.primaryContainer; else -> MaterialTheme.colorScheme.surfaceVariant })
            .then(if (focused || isNow) Modifier.border(if (focused) 2.dp else 1.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(6.dp)) else Modifier)
            .focusRequester(requester)
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) false else when (event.key) {
                    Key.DirectionUp -> onMoveRow(-1)
                    Key.DirectionDown -> onMoveRow(1)
                    Key.DirectionLeft -> onMoveProgramme(-1)
                    Key.DirectionRight -> onMoveProgramme(1)
                    else -> false
                }
            }
            .onFocusChanged { focused = it.isFocused; if (it.isFocused) onFocus() }
            .clickable(onClick = onClick)
            .semantics { contentDescription = "${programme.title}, $timeRange, $channelName" }
            .testTag("guide-programme-${programme.feedId}-${programme.epgChannelId}-${programme.startUtcMillis}"),
    ) {
        Row(Modifier.fillMaxSize().padding(horizontal = if (widthDp >= 24) 8.dp else 0.dp, vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
            if (layout == GuideLayout.EXPANDED && showArtwork && widthDp >= 180f && !programme.iconUrl.isNullOrBlank()) {
                ProgrammeArtwork(programme.iconUrl, Modifier.size(48.dp).clip(RoundedCornerShape(4.dp)))
                Spacer(Modifier.width(8.dp))
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.Center) {
                if (layout != GuideLayout.COMPACT && widthDp >= 72f) {
                    Text(timeRange, style = MaterialTheme.typography.labelSmall, color = foreground, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Text(programme.title, style = MaterialTheme.typography.bodyMedium, color = foreground,
                    maxLines = metrics.titleLines, overflow = TextOverflow.Ellipsis)
                if (layout == GuideLayout.EXPANDED && widthDp >= 120f) {
                    (programme.category?.takeIf { it.isNotBlank() } ?: programme.description?.takeIf { it.isNotBlank() })?.let {
                        Text(it, style = MaterialTheme.typography.labelMedium, color = foreground.copy(alpha = 0.85f), maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
        if (showProgress && isNow) {
            Box(Modifier.align(Alignment.BottomStart).fillMaxWidth(programme.progressAt(nowMillis)).height(3.dp)
                .background(if (focused) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.primary))
        }
    }
}

@Composable
private fun CurrentTimeMarker(windowStartMillis: Long, nowMillis: Long, minuteWidthDp: Float) {
    if (nowMillis in windowStartMillis until windowStartMillis + GUIDE_WINDOW_MILLIS) {
        Box(Modifier.offset(x = guideOffsetDp(windowStartMillis, nowMillis, minuteWidthDp).dp)
            .width(2.dp).fillMaxHeight().background(MaterialTheme.colorScheme.error)
            .semantics { contentDescription = "Current time ${clockFormat.format(Date(nowMillis))}" })
    }
}

private val CHANNEL_COLUMN = 236.dp
private val clockFormat = SimpleDateFormat("HH:mm", Locale.getDefault())
