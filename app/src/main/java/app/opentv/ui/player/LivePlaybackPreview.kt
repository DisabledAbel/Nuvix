/* Nuvix Copyright (C) 2026 Nuvix Contributors. GPL-3.0-or-later. */
package app.opentv.ui.player

import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import app.opentv.core.ServiceLocator
import app.opentv.data.model.Programme
import app.opentv.data.model.shownName
import app.opentv.player.LivePlaybackSession
import app.opentv.player.PlayerController
import coil.compose.AsyncImage
import com.disabledabel.nuvix.R
import kotlinx.coroutines.delay

/** A reserved browsing header: navigation and programme rows remain unobscured. */
@Composable
fun LivePlaybackPreview(
    session: LivePlaybackSession,
    onFullscreen: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val settings = remember { ServiceLocator.get(context).settings }
    val enabled by settings.livePreview.collectAsState()
    val active by session.active.collectAsState()
    val inPip by app.opentv.core.PipState.inPip.collectAsState()
    if (!active || !enabled || inPip) return
    BoxWithConstraints(modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        // The card scales down on phones and leaves the rest of the header free on a TV.
        Box(Modifier.width(maxWidth.coerceAtMost(320.dp)).aspectRatio(16f / 9f)) {
            LivePlaybackVideo(session, onFullscreen)
        }
    }
}

/** The guide uses its existing preview slot. It never tunes the highlighted channel. */
@OptIn(UnstableApi::class)
@Composable
fun LivePlaybackVideo(session: LivePlaybackSession, onFullscreen: () -> Unit) {
    val context = LocalContext.current
    val graph = remember { ServiceLocator.get(context) }
    val channel by session.currentChannel.collectAsState()
    val requestedChannel by session.requestedChannel.collectAsState()
    val state by session.state.collectAsState()
    val controller by session.controllerOrNull.collectAsState()
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = System.currentTimeMillis()
            delay(60_000)
        }
    }
    // A compact preview must not load the provider's entire EPG into memory.
    var programme by remember(channel?.id) { mutableStateOf<Programme?>(null) }
    LaunchedEffect(channel?.id, now) {
        programme = null
        try {
            for (epgId in channel?.epgCandidates.orEmpty()) {
                val candidate = graph.epgRepository.upcoming(epgId, now, limit = 1)
                    .firstOrNull { it.startUtcMillis <= now }
                if (candidate != null) {
                    programme = candidate
                    break
                }
            }
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // Optional guide metadata must not interrupt the retained stream.
            programme = null
        }
    }
    var focused by remember { mutableStateOf(false) }
    var surfaceAvailable by remember { mutableStateOf(true) }
    val shape = RoundedCornerShape(10.dp)
    Column(
        Modifier.fillMaxSize().clip(shape).background(Color.Black)
            .border(if (focused) 3.dp else 1.dp, if (focused) MaterialTheme.colorScheme.primary else Color.DarkGray, shape)
            .onFocusChanged { focused = it.hasFocus },
    ) {
        Box(
            Modifier.weight(1f).fillMaxWidth().clickable(onClickLabel = stringResource(R.string.live_preview_fullscreen), onClick = onFullscreen),
            contentAlignment = Alignment.Center,
        ) {
            AsyncImage(channel?.logoUrl, contentDescription = null, modifier = Modifier.fillMaxSize(0.55f))
            // Allocation belongs to the active session; attaching this View cannot open a stream.
            if (controller != null) AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    PlayerView(ctx).apply {
                        useController = false
                        isFocusable = false
                        isFocusableInTouchMode = false
                        resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                        setShutterBackgroundColor(android.graphics.Color.TRANSPARENT)
                        surfaceAvailable = runCatching { session.attachVideoSurface(this, app.opentv.player.LivePresentation.BROWSING) }.getOrDefault(false)
                    }
                },
                onRelease = { session.surfaceOwner.detach(it) },
                update = {
                    surfaceAvailable = controller != null && session.attachVideoSurface(it, app.opentv.player.LivePresentation.BROWSING, rebind = true)
                },
            )
            if (!surfaceAvailable) Text(
                stringResource(R.string.live_preview_surface_unavailable), color = Color.White,
                modifier = Modifier.padding(12.dp), style = MaterialTheme.typography.labelSmall,
            )
            if (state is PlayerController.State.Buffering) CircularProgressIndicator(Modifier.size(26.dp))
            Text(
                stringResource(R.string.live_preview_live), color = Color.White,
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.align(Alignment.TopStart).background(Color(0xFFAD2020)).padding(4.dp),
            )
        }
        Row(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).padding(start = 8.dp)) {
                Text(channel?.shownName.orEmpty(), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelLarge)
                Text(if (state is PlayerController.State.Buffering && requestedChannel != null)
                    stringResource(R.string.player_switch_loading, requestedChannel!!.shownName)
                else programme?.title ?: stringResource(R.string.live_preview_fullscreen),
                    maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelSmall)
            }
            IconButton(onClick = onFullscreen) {
                Icon(Icons.Default.Fullscreen, contentDescription = stringResource(R.string.live_preview_fullscreen))
            }
            TextButton(onClick = session::stop, contentPadding = PaddingValues(horizontal = 8.dp)) {
                Text(stringResource(R.string.live_preview_stop), style = MaterialTheme.typography.labelSmall)
            }
        }
        (state as? PlayerController.State.Error)?.let { error ->
            Row(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.errorContainer), verticalAlignment = Alignment.CenterVertically) {
                Text(error.message, Modifier.weight(1f).padding(4.dp), maxLines = 2, style = MaterialTheme.typography.labelSmall)
                TextButton(onClick = session::retry) { Text(stringResource(R.string.live_preview_retry)) }
            }
        }
    }
}
