/* Nuvix modifications Copyright (C) 2026 Nuvix Contributors. GPL-3.0-or-later. */
package app.opentv.ui.home

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LiveTv
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import app.opentv.core.RecentChannels
import app.opentv.core.ServiceLocator
import app.opentv.data.model.*
import app.opentv.data.parser.displayTitle
import app.opentv.ui.VodViewModel
import coil.compose.AsyncImage
import kotlinx.coroutines.delay

@Composable
fun TelevisionHomeScreen(
    hasSources: Boolean,
    onPlayChannel: (Channel) -> Unit,
    onOpenMovie: (Movie) -> Unit,
    onOpenSeries: (Series) -> Unit,
    onResume: (String, String, String) -> Unit,
    onAddSource: () -> Unit,
    onOpenGuide: () -> Unit,
    onOpenApps: () -> Unit,
    vod: VodViewModel = viewModel(),
) {
    val context = LocalContext.current
    val graph = remember { ServiceLocator.get(context) }
    val history = remember { RecentChannels.get(context) }
    val ids by history.ids.collectAsState()
    val recentFlow = remember(ids) { graph.database.channels().observeByIds(ids) }
    val recentRaw by recentFlow.collectAsState(initial = emptyList())
    val recent = remember(ids, recentRaw) { ids.mapNotNull { id -> recentRaw.firstOrNull { it.id == id } } }
    val channels by remember { graph.database.channels().observeHomeChannels() }.collectAsState(initial = emptyList())
    val favourites by remember { graph.database.channels().observeFavourites() }.collectAsState(initial = emptyList())
    val resume by vod.continueWatching.collectAsState()
    val recommended by vod.recommendedMovies.collectAsState()
    val movies by vod.recentlyAddedMovies.collectAsState()
    val series by vod.recentlyAddedSeries.collectAsState()
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { delay(30_000); now = System.currentTimeMillis() } }
    val nowFlow = remember(now) { graph.epgRepository.observeNow(now) }
    val programmes by nowFlow.collectAsState(initial = emptyList())
    val featured = recent.firstOrNull() ?: favourites.firstOrNull() ?: channels.firstOrNull()
    val programme = featured?.let { c -> programmes.firstOrNull { it.epgChannelId == (c.epgOverrideId ?: c.matchedEpgId ?: c.epgChannelId) } }
    LaunchedEffect(hasSources) { if (hasSources) vod.loadHomeFeeds() }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 28.dp, vertical = 22.dp),
        verticalArrangement = Arrangement.spacedBy(22.dp),
    ) {
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Make yourself at home.", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                    Text("Everything you love, all in one place.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text("NUVIX", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge)
            }
        }
        item {
            Box(
                Modifier.fillMaxWidth().heightIn(min = 220.dp).clip(RoundedCornerShape(22.dp))
                    .background(Brush.horizontalGradient(listOf(Color(0xFF142E54), Color(0xFF0D213B), Color(0xFF0A172A)))),
            ) {
                Row(Modifier.fillMaxWidth().padding(24.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(if (featured != null) "LIVE · ${featured.shownName}" else "WELCOME TO NUVIX", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge)
                        Text(
                            programme?.title ?: if (featured != null) "Your next watch starts here." else "Your television. Your way.",
                            style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            programme?.description?.takeIf { it.isNotBlank() } ?: if (hasSources) "Discover your live channels, explore your guide, or pick up where you left off." else "Add your authorized IPTV provider to bring your channels, movies, and shows together.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.widthIn(max = 550.dp),
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Button(onClick = { if (featured != null) onPlayChannel(featured) else onAddSource() }) {
                                Icon(Icons.Filled.PlayArrow, contentDescription = null)
                                Spacer(Modifier.width(6.dp))
                                Text(if (featured != null) "Watch live" else "Add provider")
                            }
                            OutlinedButton(onClick = onOpenGuide) { Text("Explore guide") }
                        }
                    }
                    if (featured?.logoUrl != null) {
                        AsyncImage(featured.logoUrl, featured.shownName, Modifier.padding(start = 24.dp).size(100.dp), contentScale = ContentScale.Fit)
                    }
                }
            }
        }
        if (resume.isNotEmpty()) item {
            HomeShelf("Continue watching", resume, { it.mediaKey }) { item ->
                ContentTile(item.title, item.posterUrl, "Resume watching", item.progress) { onResume(item.mediaKey, item.streamUrl, item.title) }
            }
        }
        if (recent.isNotEmpty()) item { ChannelShelf("Recently watched", recent.take(16), programmes, onPlayChannel) }
        if (favourites.isNotEmpty()) item { ChannelShelf("Your favorite channels", favourites.take(24), programmes, onPlayChannel) }
        if (channels.isNotEmpty()) item { ChannelShelf("On now", channels.distinctBy { it.groupKey.ifBlank { "id:${it.id}" } }.take(16), programmes, onPlayChannel) }
        val movieShelf = recommended.ifEmpty { movies }
        if (movieShelf.isNotEmpty()) item {
            HomeShelf(if (recommended.isNotEmpty()) "Recommended for you" else "Recently added movies", movieShelf.take(18), { it.id.toString() }) { movie ->
                ContentTile(movie.displayTitle, movie.posterUrl, "Movie") { onOpenMovie(movie) }
            }
        }
        if (series.isNotEmpty()) item {
            HomeShelf("Recently added shows", series.take(18), { it.id.toString() }) { show ->
                ContentTile(show.displayTitle, show.posterUrl, "TV show") { onOpenSeries(show) }
            }
        }
        item { app.opentv.launcher.FavoriteAppsRow(onOpenApps = onOpenApps) }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = onOpenApps) { Text("Browse installed apps") }
                OutlinedButton(onClick = onAddSource) { Text("Add another provider") }
            }
        }
    }
}

@Composable
internal fun ChannelShelf(title: String, channels: List<Channel>, programmes: List<Programme>, onPlay: (Channel) -> Unit) {
    HomeShelf(title, channels, { it.id.toString() }) { channel ->
        val current = programmes.firstOrNull { it.epgChannelId == (channel.epgOverrideId ?: channel.matchedEpgId ?: channel.epgChannelId) }
        ContentTile(channel.shownName, channel.logoUrl, current?.title ?: "Live TV", logo = true) { onPlay(channel) }
    }
}

@Composable
internal fun <T> HomeShelf(title: String, items: List<T>, key: (T) -> String, tile: @Composable (T) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
        LazyRow(horizontalArrangement = Arrangement.spacedBy(14.dp), contentPadding = PaddingValues(vertical = 4.dp)) {
            items(items, key = key) { tile(it) }
        }
    }
}

@Composable
internal fun ContentTile(title: String, art: String?, subtitle: String, progress: Float? = null, logo: Boolean = false, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (focused) 1.025f else 1f, label = "homeTileFocus")
    val shape = RoundedCornerShape(14.dp)
    Column(
        Modifier.width(190.dp).graphicsLayer { scaleX = scale; scaleY = scale }
            .onFocusChanged { focused = it.isFocused }.clip(shape)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .border(if (focused) 3.dp else 1.dp, if (focused) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline.copy(alpha = .4f), shape)
            .clickable(onClick = onClick),
    ) {
        Box(Modifier.fillMaxWidth().height(106.dp).background(MaterialTheme.colorScheme.surface), contentAlignment = Alignment.Center) {
            if (art.isNullOrBlank()) Icon(Icons.Filled.LiveTv, null, Modifier.size(32.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            else AsyncImage(art, null, Modifier.fillMaxSize().padding(if (logo) 20.dp else 0.dp), contentScale = if (logo) ContentScale.Fit else ContentScale.Crop)
        }
        if (progress != null) LinearProgressIndicator(progress = { progress.coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth().height(3.dp))
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}
