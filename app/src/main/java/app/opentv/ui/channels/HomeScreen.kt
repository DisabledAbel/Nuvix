/* Nuvix modifications Copyright (C) 2026 Nuvix Contributors. GPL-3.0-or-later. */
/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.ui.channels

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.StarOutline
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.viewmodel.compose.viewModel
import android.content.Intent
import android.widget.Toast
import androidx.compose.ui.window.Dialog
import com.disabledabel.nuvix.R
import app.opentv.core.GuideChannelFilter
import app.opentv.core.GuideLayout
import app.opentv.core.GuideChannelSort
import app.opentv.core.AppSettings
import app.opentv.core.ServiceLocator
import app.opentv.core.findActivity
import app.opentv.core.requestIgnoreBatteryOptimizations
import app.opentv.connections.AccountConnectionManager
import app.opentv.connections.ConnectionPurpose
import app.opentv.connections.ConnectionRequest
import app.opentv.connections.ReservationResult
import app.opentv.data.model.Channel
import app.opentv.data.model.Programme
import app.opentv.data.model.Reminder
import app.opentv.data.model.shownName
import app.opentv.reminders.ReminderScheduler
import app.opentv.player.PlaybackQueue
import app.opentv.player.LivePlaybackSession
import app.opentv.ui.player.LivePlaybackVideo
import app.opentv.player.PlayerController
import app.opentv.ui.ChannelsViewModel
import app.opentv.ui.RecordingBackgroundDialog
import app.opentv.ui.RecordingBackgroundPrompt
import coil.compose.AsyncImage
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.withContext

/**
 * Live TV: a category rail on the left, the channel list on the right.
 *
 * The rail is vertical because provider category lists run to dozens of entries and a
 * horizontal chip row hides all but the first few — on a d-pad, anything you cannot see
 * you cannot reach. Favourites and All are pinned at the top.
 *
 * The list shows what is on *now* and *next* against every channel. That doubles as a
 * permanent, glanceable health indicator for the guide: when the EPG stops updating, these
 * lines say so honestly instead of going quietly stale.
 */
@Composable
fun HomeScreen(
    isTelevision: Boolean,
    liveSession: LivePlaybackSession,
    onReturnToLive: () -> Unit,
    hasSources: Boolean,
    isSyncing: Boolean,
    onPlayChannel: (Channel) -> Unit,
    onAddSource: () -> Unit,
    onRefresh: () -> Unit,
    onPlayCatchup: (mediaKey: String, url: String, title: String, ua: String) -> Unit = { _, _, _, _ -> },
    viewModel: ChannelsViewModel = viewModel(),
    layoutOverride: AppSettings.ChannelLayout? = null,
) {
    val context = LocalContext.current
    val graph = remember { ServiceLocator.get(context) }
    val settings = remember { graph.settings }
    val previewEnabled by settings.guidePreviewVideo.collectAsState()
    val livePreviewEnabled by settings.livePreview.collectAsState()
    val persistentActive by liveSession.active.collectAsState()
    val optionalPreviewSuppressed by liveSession.guidePreviewSuppressed.collectAsState()
    val inPip by app.opentv.core.PipState.inPip.collectAsState()
    var screenResumed by remember { mutableStateOf(true) }
    val preferredChannelLayout by settings.channelLayout.collectAsState()
    val channelLayout = layoutOverride ?: preferredChannelLayout
    val guideLayout by settings.guideLayout.collectAsState()
    val guideDensity by settings.guideDensity.collectAsState()
    val guideArtwork by settings.showGuideArtwork.collectAsState()
    val guideProgress by settings.showGuideProgress.collectAsState()
    val rememberPosition by settings.rememberGuidePosition.collectAsState()
    val restorePosition = remember { settings.lastGuidePosition.value }
    val guideFilter by viewModel.guideFilter.collectAsState()
    val guideSort by viewModel.guideSort.collectAsState()
    val customGroups by viewModel.customGuideGroups.collectAsState()
    val selectedGroupId by settings.guideFilterGroupId.collectAsState()
    val guideLoaded by viewModel.guideLoaded.collectAsState()
    val currentChannel by liveSession.currentChannel.collectAsState()
    var showGuideOptions by remember { mutableStateOf(false) }

    val categories by viewModel.visibleCategoryGroups.collectAsState()
    val rows by viewModel.rows.collectAsState()
    val selectedCategory by viewModel.selectedCategory.collectAsState()
    val favouritesOnly by viewModel.favouritesOnly.collectAsState()
    val sources by viewModel.sources.collectAsState()
    val selectedSource by viewModel.selectedSource.collectAsState()
    val windowStart by viewModel.windowStartMillis.collectAsState()
    // How many days ahead the guide is scrolled (0 = live now). Drives the ‹ Today › nav.
    val guideDayOffset by viewModel.guideDayOffset.collectAsState()
    // Tri-state: null = still checking the catalogue, true = channels on disk, false = confirmed
    // empty. Drives the choice between the loading spinner and a recoverable error below.
    val channelsPresent by viewModel.channelsPresent.collectAsState()

    // Two separate ideas, on purpose:
    //  - highlightedRow: where the d-pad is in the grid. Moves freely with up/down.
    //  - selectedRow: what the preview pane plays. Only changes when you press OK, so scrolling
    //    the list is calm and silent instead of re-tuning a stream on every keypress.
    var highlightedRow by remember { mutableStateOf<ChannelsViewModel.Row?>(null) }
    var selectedRow by remember { mutableStateOf<ChannelsViewModel.Row?>(null) }
    val previewSound by settings.guidePreviewSound.collectAsState()
    var nowMillis by remember { mutableLongStateOf(System.currentTimeMillis()) }

    // Recording from the guide: what's capturing now, and a scope to kick a capture off.
    val activeRecordingSnapshot by graph.recordingRepository.observeActive().collectAsState(initial = null)
    val activeRecordings = activeRecordingSnapshot.orEmpty()
    val recordScope = rememberCoroutineScope()
    // The programme the user pressed OK on in the grid — drives the per-programme record menu.
    var recordTarget by remember { mutableStateOf<Pair<ChannelsViewModel.Row, Programme>?>(null) }
    // The channel whose OK menu (Watch / Record / Schedule) is open.
    var channelMenu by remember { mutableStateOf<ChannelsViewModel.Row?>(null) }

    // First time the user records or schedules while OpenTV isn't exempt from battery optimisation,
    // offer the exemption so the capture survives standby. Once per session; never blocks recording.
    var showBackgroundPrompt by remember { mutableStateOf(false) }
    fun promptBackgroundIfNeeded() {
        if (RecordingBackgroundPrompt.shouldShow(context)) {
            RecordingBackgroundPrompt.markShown()
            showBackgroundPrompt = true
        }
    }

    // ---- Category rail collapse (Change 2) ---------------------------------------------------
    // The rail is full-width while focus is on it, then slides shut once focus moves into the
    // guide (onFocusRow) so the grid gets the whole width. Pressing d-pad LEFT from the guide's
    // leftmost (channel) column slides it back and drops focus on the selected category.
    var railExpanded by remember { mutableStateOf(true) }
    val railWidth by animateDpAsState(
        targetValue = if (railExpanded) 240.dp else 0.dp,
        label = "railWidth",
    )
    val railFocusRequester = remember { FocusRequester() }
    // Set when LEFT reopens the rail; the effect waits for the rail to be laid out again before
    // moving focus onto it — a just-revealed node isn't focusable on the very same frame.
    var pendingRailFocus by remember { mutableStateOf(false) }
    LaunchedEffect(pendingRailFocus) {
        if (pendingRailFocus) {
            delay(50)
            // runCatching: if the selected category is scrolled out of the rail's list it may not
            // be composed; a second LEFT press then still reaches the rail by ordinary navigation.
            runCatching { railFocusRequester.requestFocus() }
            pendingRailFocus = false
        }
    }

    // Re-evaluate "now" once a minute so progress bars advance without leaving the screen.
    LaunchedEffect(Unit) {
        while (true) {
            nowMillis = System.currentTimeMillis()
            viewModel.tick()
            delay(60_000)
        }
    }

    // Keep both valid as the list changes (e.g. switching category): stay on the same channel if
    // it's still present, otherwise fall back to the top of the new list.
    LaunchedEffect(rows) {
        highlightedRow = rows.firstOrNull { it.key == highlightedRow?.key }
            ?: rows.firstOrNull { row -> restorePosition?.channelId?.let { id -> row.variants.any { it.id == id } } == true }
            ?: rows.firstOrNull()
        selectedRow = rows.firstOrNull { it.key == selectedRow?.key } ?: rows.firstOrNull()
    }

    // No standalone preview player exists while the shared live session owns playback.
    // Wait for Room's initial recording snapshot; subsequent account capacity is checked by the
    // same allocator as live playback and capture, including pending connection attempts.
    val recordingSnapshotReady = activeRecordingSnapshot != null
    val accountConnections = remember { AccountConnectionManager.get(context) }
    val previewScope = remember { CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate) }
    val previewController = remember(previewEnabled, livePreviewEnabled, screenResumed, recordingSnapshotReady, persistentActive, optionalPreviewSuppressed) {
        if (previewEnabled && livePreviewEnabled && screenResumed && recordingSnapshotReady && !persistentActive && !optionalPreviewSuppressed) {
            PlayerController(context.applicationContext, previewScope, graph.streamingHttpClient, subtitlesEnabled = false, preview = true)
                .also { it.player.volume = 0f }
        } else null
    }
    val previewConnection = remember(previewController) {
        previewController?.let { GuidePreviewConnection(accountConnections, it::stop) }
    }
    DisposableEffect(previewController, previewConnection) {
        if (previewController != null && previewConnection != null) {
            liveSession.registerGuidePreview(previewController, previewConnection::onPlaybackReleased)
        }
        onDispose { previewController?.let(liveSession::unregisterGuidePreview) }
    }
    DisposableEffect(Unit) { onDispose { previewScope.cancel() } }

    // Explicit Watch uses the shared live owner's account admission and per-request approval UI.
    fun requestLive(channel: Channel) {
        PlaybackQueue.items = rows.map {
            PlaybackQueue.Item(it.primary.id, it.primary.shownName, it.primary.logoUrl, it.primary.number)
        }
        previewController?.let(liveSession::unregisterGuidePreview)
        onPlayChannel(channel)
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, previewConnection) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> screenResumed = true
                Lifecycle.Event.ON_PAUSE -> {
                    screenResumed = false
                    previewConnection?.stop()
                }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // Preview audio follows the setting; muted by default so browsing stays quiet.
    LaunchedEffect(previewSound, previewController) {
        previewController?.player?.volume = if (previewSound) 1f else 0f
    }

    // An optional preview never asks to open another connection on an unknown account. Watch
    // remains available and routes through the live owner's explicit approval surface.
    LaunchedEffect(selectedRow?.key, previewController, previewConnection) {
        val controller = previewController ?: return@LaunchedEffect
        val connection = previewConnection ?: return@LaunchedEffect
        val attempt = connection.beginTune()
        val channel = selectedRow?.primary ?: return@LaunchedEffect
        try {
            val source = graph.sourceRepository.byId(channel.sourceId)
                ?.takeIf { it.enabled } ?: return@LaunchedEffect
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            if (!connection.isCurrent(attempt) || controller.isReleased) return@LaunchedEffect
            accountConnections.registerSource(source)
            val result = accountConnections.reserve(ConnectionRequest(
                connection.sessionId, attempt, channel.sourceId, ConnectionPurpose.PREVIEW,
            ))
            if (result is ReservationResult.ApprovalRequired) {
                accountConnections.discardChallenge(result.challenge)
                return@LaunchedEffect
            }
            val token = (result as? ReservationResult.Reserved)?.token ?: return@LaunchedEffect
            if (!connection.attach(token, attempt)) return@LaunchedEffect
            accountConnections.registerOwner(token) {
                withContext(Dispatchers.Main.immediate) {
                    if (connection.owns(token)) liveSession.unregisterGuidePreview(controller)
                }
            }
            val url = graph.catalogRepository.resolvePlaybackUrl(channel, source)
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            if (!connection.isCurrent(attempt) || !connection.owns(token) || controller.isReleased) return@LaunchedEffect
            controller.play(
                PlayerController.Request(
                    url = url, title = channel.shownName,
                    userAgent = source.userAgent, isLive = true,
                ),
                debounce = true,
            )
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            connection.stop(attempt)
            throw cancelled
        } catch (_: Exception) {
            // Provider exceptions may contain private URLs. Keep the guide's logo fallback.
            connection.stop(attempt)
        }
    }
    LaunchedEffect(previewController, previewConnection) {
        val controller = previewController ?: return@LaunchedEffect
        val connection = previewConnection ?: return@LaunchedEffect
        controller.state.collect { state ->
            when (state) {
                is PlayerController.State.Playing -> connection.markActive()
                is PlayerController.State.Error -> if (!state.canRetry) connection.stop()
                else -> Unit
            }
        }
    }

    Row(Modifier.fillMaxSize()) {

        // ---- Category rail -----------------------------------------------------------------
        // Width animates to 0 while focus is in the guide (see onFocusRow) so the grid gets the
        // whole screen; d-pad LEFT from the guide's channel column slides it back (onExitLeft…).
        Column(
            Modifier
                .width(railWidth)
                .fillMaxHeight()
                .background(MaterialTheme.colorScheme.surface)
                .clipToBounds()
                .padding(vertical = 16.dp),
        ) {
            Text(
                stringResource(R.string.nav_live_tv),
                style = MaterialTheme.typography.headlineMedium,
                modifier = Modifier.padding(horizontal = 20.dp),
            )
            Spacer(Modifier.height(12.dp))
            RailEntry("Guide options", false, { showGuideOptions = true })
            // The top "Search channels" bar was removed — Search now lives in the global nav rail.

            LazyColumn(
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                // Provider switch — only when there's more than one source. Lets the user keep
                // several playlists and flip between them (cardiodoc's request); "All" folds them.
                if (sources.size > 1 || guideFilter == GuideChannelFilter.PROVIDER) {
                    item(key = "provider-header") {
                        Text(
                            stringResource(R.string.channels_manager_source_header),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        )
                    }
                    item(key = "provider-all") {
                        RailEntry(
                            label = stringResource(R.string.channels_manager_all_sources),
                            selected = selectedSource == null,
                            onClick = { viewModel.selectSource(null) },
                        )
                    }
                    items(sources, key = { "src-${it.id}" }) { source ->
                        RailEntry(
                            label = source.name,
                            selected = selectedSource == source.id,
                            onClick = { viewModel.selectSource(source.id) },
                            modifier = if (selectedSource == source.id && selectedCategory == null) Modifier.focusRequester(railFocusRequester) else Modifier,
                        )
                    }
                    item(key = "provider-divider") { Spacer(Modifier.height(10.dp)) }
                }
                // The currently-selected entry carries the rail's FocusRequester, so reopening the
                // rail (d-pad LEFT in the guide) lands focus straight back on the current category.
                item {
                    RailEntry(
                        label = stringResource(R.string.guide_favourites),
                        selected = favouritesOnly,
                        onClick = viewModel::selectFavourites,
                        modifier = if (favouritesOnly) Modifier.focusRequester(railFocusRequester) else Modifier,
                    )
                }
                item {
                    val allSelected = guideFilter == GuideChannelFilter.ALL && !favouritesOnly && selectedCategory == null
                    RailEntry(
                        label = stringResource(R.string.guide_all_channels),
                        selected = allSelected,
                        onClick = { viewModel.selectGuideFilter(GuideChannelFilter.ALL) },
                        modifier = if (allSelected) Modifier.focusRequester(railFocusRequester) else Modifier,
                    )
                }
                items(listOf(GuideChannelFilter.RECENT, GuideChannelFilter.WITH_EPG, GuideChannelFilter.AIRING), key = { "guide-filter-$it" }) { filter ->
                    RailEntry(guideFilterLabel(filter), guideFilter == filter, { viewModel.selectGuideFilter(filter) },
                        if (guideFilter == filter) Modifier.focusRequester(railFocusRequester) else Modifier)
                }
                items(customGroups, key = { "custom-guide-${it.id}" }) { group ->
                    val selected = guideFilter == GuideChannelFilter.GROUP && selectedGroupId == group.id
                    RailEntry(group.name, selected, { viewModel.selectGuideFilter(GuideChannelFilter.GROUP, group.id) },
                        if (selected) Modifier.focusRequester(railFocusRequester) else Modifier)
                }
                items(categories, key = { it.key }) { group ->
                    val groupSelected = !favouritesOnly && selectedCategory == group.key
                    RailEntry(
                        label = group.label,
                        selected = groupSelected,
                        onClick = { viewModel.selectCategory(group.key) },
                        modifier = if (groupSelected) Modifier.focusRequester(railFocusRequester) else Modifier,
                    )
                }
            }
        }

        // ---- Preview + guide ---------------------------------------------------------------
        Column(Modifier.weight(1f)) {
            if (rows.isEmpty()) {
                when {
                    favouritesOnly -> NoFavouritesState()
                    // Work genuinely in progress: a sync is running, the catalogue check hasn't
                    // returned yet, or channels ARE on disk and the guide is still building. A
                    // large provider takes a while, and showing "No channels" during it reads as
                    // failure — which is how someone concludes an app is broken thirty seconds
                    // after installing it.
                    isSyncing || channelsPresent == null || !guideLoaded -> LoadingState(isSyncing)
                    channelsPresent == true -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("No channels match this filter", style = MaterialTheme.typography.titleLarge)
                            androidx.compose.material3.TextButton(onClick = { viewModel.selectGuideFilter(GuideChannelFilter.ALL) }) { Text("All Channels") }
                        }
                    }
                    // Nothing is syncing and the catalogue is confirmed empty. With a provider
                    // configured, the last load failed or returned nothing — surface a clear error
                    // with Retry and a way back to setup instead of spinning forever.
                    hasSources -> ChannelsErrorState(onRetry = onRefresh, onEditProvider = onAddSource)
                    else -> EmptyState(onAddSource)
                }
            } else {
                // Hand the player the list you're browsing so it can zap channel up/down.
                fun goFullscreen(channel: Channel) = requestLive(channel)

                // Record the highlighted channel's now-programme (bounded to its end), or stop it
                // if it's already recording. Powers the preview pane's quick record dot.
                fun recordSelected() {
                    val row = highlightedRow ?: return
                    val active = activeRecordings.firstOrNull { it.channelId == row.primary.id }
                    if (active != null) {
                        graph.recordingEngine.stop(active.id)
                        Toast.makeText(context, context.getString(R.string.rec_recording_stopped), Toast.LENGTH_SHORT).show()
                    } else {
                        liveSession.recordChannel(row.primary, row.now)
                        Toast.makeText(context, context.getString(R.string.rec_recording_started_see_tab, row.primary.shownName), Toast.LENGTH_LONG).show()
                        promptBackgroundIfNeeded()
                    }
                }

                val dayLabel = when (guideDayOffset) {
                    0 -> stringResource(R.string.guide_today)
                    1 -> stringResource(R.string.guide_tomorrow)
                    else -> remember(windowStart) {
                        SimpleDateFormat("EEE d MMM", Locale.getDefault()).format(Date(windowStart))
                    }
                }
                GuidePreview(
                    row = highlightedRow,
                    nowMillis = nowMillis,
                    onWatch = { highlightedRow?.let { goFullscreen(it.primary) } },
                    onRefresh = onRefresh,
                    onAddSource = onAddSource,
                    previewPlayer = previewController?.player,
                    persistentPreview = if (persistentActive && previewEnabled && livePreviewEnabled && !inPip) {
                        { LivePlaybackVideo(liveSession, onReturnToLive) }
                    } else null,
                    isRecording = highlightedRow?.primary?.id?.let { id ->
                        activeRecordings.any { it.channelId == id }
                    } == true,
                    onRecord = { recordSelected() },
                    dayLabel = dayLabel,
                    canGoPrevDay = guideDayOffset > -1,
                    canGoNextDay = guideDayOffset < 6,
                    recordAvailable = highlightedRow?.primary?.let { channel ->
                        GuideProgrammeActions.canRecordChannel(channel, sources.firstOrNull { it.id == channel.sourceId })
                    } == true,
                    onPrevDay = { viewModel.nudgeGuideDay(-1) },
                    onNextDay = { viewModel.nudgeGuideDay(1) },
                )
                // Shared by both layouts: focus follows the highlight and collapses the rail; LEFT
                // from the leftmost element reopens the rail (consumed only when it was hidden).
                val onFocusChannel: (ChannelsViewModel.Row) -> Unit = {
                    highlightedRow = it
                    railExpanded = false
                }
                val onExitLeftChannel: () -> Boolean = {
                    if (!railExpanded) {
                        railExpanded = true
                        pendingRailFocus = true
                        true
                    } else {
                        false
                    }
                }
                if (channelLayout == AppSettings.ChannelLayout.LIST) {
                    ChannelList(
                        rows = rows,
                        selectedKey = highlightedRow?.key,
                        onSelectRow = { row -> selectedRow = row; channelMenu = row },
                        onFocusRow = onFocusChannel,
                        onToggleFavourite = { viewModel.toggleFavourite(it) },
                        onExitLeftFromChannel = onExitLeftChannel,
                        modifier = Modifier.weight(1f).padding(start = 12.dp, end = 12.dp),
                        nowMillis = nowMillis,
                        rememberPosition = rememberPosition,
                        restorePosition = restorePosition,
                        onPositionChanged = settings::setGuidePosition,
                        onVisibleRowsChanged = viewModel::setVisibleGuideRows,
                    )
                } else {
                    GuideGrid(
                        rows = rows,
                        windowStartMillis = windowStart,
                        dayOffset = guideDayOffset,
                        selectedKey = highlightedRow?.key,
                        // OK on a channel opens its menu: Watch, Record now, Schedule a later show,
                        // Record series. Focus never retunes the preview.
                        onSelectRow = { row -> selectedRow = row; channelMenu = row },
                        onFocusRow = onFocusChannel,
                        onProgramme = { row, programme -> recordTarget = row to programme },
                        onToggleFavourite = { viewModel.toggleFavourite(it) },
                        onExitLeftFromChannel = onExitLeftChannel,
                        modifier = Modifier.weight(1f).padding(start = 12.dp, end = 12.dp),
                        layout = guideLayout, density = guideDensity,
                        showArtwork = guideArtwork, showProgress = guideProgress,
                        rememberPosition = rememberPosition, restorePosition = restorePosition,
                        nowMillis = nowMillis, currentChannelId = currentChannel?.id,
                        onPositionChanged = settings::setGuidePosition,
                        onVisibleRowsChanged = viewModel::setVisibleGuideRows,
                        onPrevDay = { viewModel.nudgeGuideDay(-1) },
                        onNextDay = { viewModel.nudgeGuideDay(1) },
                        onJumpToNow = viewModel::guideToNow,
                        onReturnToCurrentChannel = { viewModel.selectGuideFilter(GuideChannelFilter.ALL) },
                        canGoPrevDay = guideDayOffset > -1, canGoNextDay = guideDayOffset < 6,
                    )
                }
            }
        }
    }

    if (showGuideOptions) Dialog(onDismissRequest = { showGuideOptions = false }) {
        Column(Modifier.width(460.dp).heightIn(max = 540.dp).background(MaterialTheme.colorScheme.surface)
            .verticalScroll(rememberScrollState()).padding(20.dp)) {
            Text("Guide layout", style = MaterialTheme.typography.titleLarge)
            GuideLayout.entries.forEach { layout ->
                RecordActionRow((if (guideLayout == layout) "✓ " else "") + layout.name.lowercase().replaceFirstChar { it.uppercase() }) {
                    settings.setGuideLayout(layout)
                }
            }
            Text("Channel sorting", style = MaterialTheme.typography.titleLarge)
            GuideChannelSort.entries.forEach { sort ->
                RecordActionRow((if (guideSort == sort) "✓ " else "") + sort.name.lowercase().replace('_', ' ')) { settings.setGuideSort(sort) }
            }
            RecordActionRow("Back to Guide") { showGuideOptions = false }
        }
    }

    recordTarget?.let { (targetRow, programme) ->
        ProgrammeDetailsDialog(
            targetRow, programme, nowMillis, sources, activeRecordings, liveSession, recordScope,
            onReleasePreview = { previewController?.let(liveSession::unregisterGuidePreview) },
            onWatch = ::requestLive,
            onPlayCatchup = onPlayCatchup,
            onRecordingScheduled = ::promptBackgroundIfNeeded,
            onDismiss = { recordTarget = null },
        )
    }

    // The channel menu — opened by pressing OK on a channel. Watch, record what's on now, schedule
    // a later programme, or record the whole series. A plain vertical list, so it's reliable on any
    // remote — no fiddly timeline navigation needed.
    channelMenu?.let { menuRow ->
        val channel = menuRow.primary
        val nowProg = menuRow.now
        val recordingThis = activeRecordings.firstOrNull { it.channelId == channel.id }
        val upcoming = menuRow.programmes.filter { it.startUtcMillis > nowMillis }.take(8)
        Dialog(onDismissRequest = { channelMenu = null }) {
            Column(
                Modifier
                    .width(480.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(MaterialTheme.colorScheme.surface)
                    .padding(20.dp),
            ) {
                Text(
                    channel.shownName,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                nowProg?.let {
                    Text(
                        stringResource(R.string.guide_now_title, it.title),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(Modifier.height(14.dp))

                Column(
                    Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    RecordActionRow(stringResource(R.string.guide_watch)) {
                        channelMenu = null
                        requestLive(channel)
                    }
                    RecordActionRow(stringResource(R.string.guide_open_external)) {
                        channelMenu = null
                        recordScope.launch {
                            val ua = graph.sourceRepository.byId(channel.sourceId)?.userAgent ?: "Nuvix/0.1 (Android)"
                            val intent = Intent(Intent.ACTION_VIEW).apply {
                                setDataAndType(android.net.Uri.parse(channel.streamUrl), "video/*")
                                putExtra("title", channel.shownName)
                                // MX Player / VLC read the User-Agent from this header extra.
                                putExtra("headers", arrayOf("User-Agent", ua))
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            }
                            runCatching {
                                context.startActivity(
                                    Intent.createChooser(intent, context.getString(R.string.guide_play_channel_with))
                                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                                )
                            }.onFailure {
                                Toast.makeText(context, context.getString(R.string.guide_no_external_player), Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                    if (recordingThis != null) {
                        RecordActionRow(stringResource(R.string.rec_stop_recording), primary = true) {
                            graph.recordingEngine.stop(recordingThis.id)
                            Toast.makeText(context, context.getString(R.string.rec_recording_stopped), Toast.LENGTH_SHORT).show()
                            channelMenu = null
                        }
                    } else if (GuideProgrammeActions.canRecordChannel(channel, sources.firstOrNull { it.id == channel.sourceId })) {
                        RecordActionRow(stringResource(R.string.guide_record_now_playing), primary = true) {
                            liveSession.recordChannel(channel, nowProg)
                            Toast.makeText(context, context.getString(R.string.rec_recording_started_see_tab, channel.shownName), Toast.LENGTH_LONG).show()
                            promptBackgroundIfNeeded()
                            channelMenu = null
                        }
                    }
                    if (nowProg != null && GuideProgrammeActions.canRecordChannel(channel, sources.firstOrNull { it.id == channel.sourceId })) {
                        RecordActionRow(stringResource(R.string.rec_record_series_named, nowProg.title)) {
                            recordScope.launch {
                                graph.recordingEngine.recordSeries(channel, nowProg, menuRow.programmes)
                            }
                            Toast.makeText(context, context.getString(R.string.rec_series_recording_set, nowProg.title), Toast.LENGTH_LONG).show()
                            promptBackgroundIfNeeded()
                            channelMenu = null
                        }
                    }
                    if (upcoming.isNotEmpty()) {
                        Spacer(Modifier.height(10.dp))
                        Text(
                            stringResource(R.string.guide_later_header),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                        )
                        upcoming.forEach { programme ->
                            RecordActionRow("${formatTime(programme.startUtcMillis)}   ${programme.title}") {
                                // Open the programme dialog so the choice is record, remind or auto-switch —
                                // not a surprise one-tap recording.
                                channelMenu = null
                                recordTarget = menuRow to programme
                            }
                        }
                    }
                    RecordActionRow(stringResource(R.string.common_cancel)) { channelMenu = null }
                }
            }
        }
    }

    if (showBackgroundPrompt) {
        RecordingBackgroundDialog(
            onAllow = {
                showBackgroundPrompt = false
                context.requestIgnoreBatteryOptimizations()
            },
            onDismiss = { showBackgroundPrompt = false },
        )
    }
}

@Composable
private fun RailEntry(label: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Text(
        text = label,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
        color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer
        else MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(
                if (selected) MaterialTheme.colorScheme.primaryContainer
                else MaterialTheme.colorScheme.surface,
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
    )
}

@Composable
private fun ChannelRow(
    row: ChannelsViewModel.Row,
    isSelected: Boolean,
    onClick: () -> Unit,
    onToggleFavourite: () -> Unit,
) {
    val now = System.currentTimeMillis()
    val background =
        if (isSelected) MaterialTheme.colorScheme.surfaceVariant
        else MaterialTheme.colorScheme.background

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(background)
            .clickable(onClick = onClick)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AsyncImage(
            model = row.primary.logoUrl,
            contentDescription = null,
            modifier = Modifier
                .size(48.dp)
                .clip(RoundedCornerShape(6.dp)),
        )
        Spacer(Modifier.width(14.dp))

        Column(Modifier.weight(1f)) {
            // Logo, name, guide — and nothing else. Quality is a playback decision;
            // its switch lives in the player, not as clutter on every row.
            Text(
                row.primary.shownName,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = row.now?.let { "${formatTime(it.startUtcMillis)}  ${it.title}" }
                    // Honest rather than blank. A user who sees this on every channel knows
                    // the guide is the problem, not their provider.
                    ?: stringResource(R.string.guide_no_info),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            row.now?.let { programme ->
                Spacer(Modifier.height(6.dp))
                LinearProgressIndicator(
                    progress = { programme.progressAt(now) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(3.dp),
                )
            }
            row.next?.let { next ->
                Spacer(Modifier.height(4.dp))
                Text(
                    stringResource(R.string.guide_next_prefix, formatTime(next.startUtcMillis), next.title),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        IconButton(onClick = onToggleFavourite) {
            Icon(
                imageVector = if (row.primary.favourite) Icons.Default.Star
                else Icons.Outlined.StarOutline,
                contentDescription = if (row.primary.favourite) stringResource(R.string.common_remove_favourite) else stringResource(R.string.common_favourite),
            )
        }
    }
}

@Composable
private fun LoadingState(isSyncing: Boolean) {
    val s by app.opentv.core.StatusBus.message.collectAsState()
    val p by app.opentv.core.StatusBus.progress.collectAsState()
    val status = s
    val progress = p
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.widthIn(max = 460.dp),
        ) {
            if (progress == null) {
                CircularProgressIndicator()
            } else {
                Text(
                    "${(progress * 100).toInt()}%",
                    style = MaterialTheme.typography.headlineMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            Spacer(Modifier.height(20.dp))
            Text(stringResource(R.string.guide_loading_channels), style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(8.dp))
            Text(
                when {
                    // Actively assembling — this is work in progress, not a failure.
                    status != null -> status
                    isSyncing -> stringResource(R.string.guide_fetching_desc)
                    // Channels are already on the device; the guide is being built from them.
                    // A big provider takes a moment (longer on this debug build) — not a failure.
                    else -> stringResource(R.string.guide_building_desc)
                },
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            if (progress != null) {
                Spacer(Modifier.height(20.dp))
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier.fillMaxWidth().height(6.dp),
                )
            }
        }
    }
}

@Composable
private fun NoFavouritesState() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(stringResource(R.string.guide_no_favourites_title), style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(R.string.guide_no_favourites_desc),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * Shown when a provider is configured but there are no channels to display and nothing is
 * syncing — i.e. the last catalogue load failed or came back empty. Replaces the endless
 * "Loading your channels" spinner with something the user can act on.
 */
@Composable
private fun ChannelsErrorState(onRetry: () -> Unit, onEditProvider: () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.widthIn(max = 460.dp),
        ) {
            Text(
                stringResource(R.string.guide_load_failed_title),
                style = MaterialTheme.typography.headlineSmall,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(R.string.guide_load_failed_desc),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(20.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                androidx.compose.material3.Button(onClick = onRetry) {
                    Text(stringResource(R.string.common_try_again))
                }
                androidx.compose.material3.OutlinedButton(onClick = onEditProvider) {
                    Text(stringResource(R.string.guide_edit_provider))
                }
            }
        }
    }
}

@Composable
private fun EmptyState(onAddSource: () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(stringResource(R.string.guide_no_channels_title), style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(R.string.guide_add_provider_desc),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(16.dp))
            androidx.compose.material3.Button(onClick = onAddSource) { Text(stringResource(R.string.guide_add_provider_button)) }
        }
    }
}

/** UTC in the database, device zone on screen. Converted here and nowhere else. */
private val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())

private fun formatTime(utcMillis: Long): String = timeFormat.format(Date(utcMillis))

@Composable
private fun guideFilterLabel(filter: GuideChannelFilter): String = stringResource(
    when (filter) {
        GuideChannelFilter.ALL -> R.string.advanced_guide_filter_all
        GuideChannelFilter.FAVOURITES -> R.string.advanced_guide_filter_favourites
        GuideChannelFilter.RECENT -> R.string.advanced_guide_filter_recent
        GuideChannelFilter.GROUP -> R.string.advanced_guide_filter_group
        GuideChannelFilter.PROVIDER -> R.string.advanced_guide_filter_provider
        GuideChannelFilter.WITH_EPG -> R.string.advanced_guide_filter_epg
        GuideChannelFilter.AIRING -> R.string.advanced_guide_filter_airing
    },
)
