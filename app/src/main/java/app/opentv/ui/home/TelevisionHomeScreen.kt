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
    val homeStore = remember(context) { HomePreferencesStore(context) }
    val homePreferences by homeStore.preferences.collectAsState()
    var customizeHome by remember { mutableStateOf(false) }
    val presentation = homePreferences.presentation
    val customGroups = app.opentv.ui.channels.rememberCustomGroupShelves { homePreferences.isVisible(HomeShelfOrder.groupKey(it.cloudId)) }
    val groupNames = remember(customGroups) { customGroups.associate { HomeShelfOrder.groupKey(it.first.cloudId) to it.first.name } }
    val order = remember(homePreferences.shelfOrder, groupNames) { HomeShelfOrder.normalize(homePreferences.shelfOrder, groupNames.keys.toList()) }
    val focusedArtwork = remember { mutableStateOf<String?>(null) }
    val tileStyle = remember(presentation) { HomeTileStyle(presentation) { focusedArtwork.value = it } }
    DisposableEffect(homeStore) { onDispose { homeStore.close() } }
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
    val featureSource = selectHomeFeature(presentation.featured, resume.isNotEmpty(), recent.isNotEmpty(), favourites.isNotEmpty(), channels.isNotEmpty())
    val resumeFeature = resume.firstOrNull().takeIf { featureSource == HomeFeatureSource.CONTINUE }
    val featured = when (featureSource) {
        HomeFeatureSource.RECENT -> recent.firstOrNull()
        HomeFeatureSource.FAVOURITE -> favourites.firstOrNull()
        HomeFeatureSource.CHANNEL -> channels.firstOrNull()
        else -> null
    }
    val programme = featured?.let { c -> programmes.firstOrNull { it.epgChannelId == (c.epgOverrideId ?: c.matchedEpgId ?: c.epgChannelId) } }
    LaunchedEffect(hasSources) { if (hasSources) vod.loadHomeFeeds() }

    MaterialTheme(colorScheme = if (presentation.background == HomeBackground.DEFAULT) MaterialTheme.colorScheme else app.opentv.ui.theme.DarkScheme) {
        CompositionLocalProvider(LocalHomeTileStyle provides tileStyle) {
            Box(Modifier.fillMaxSize()) {
                HomeBackdrop(presentation.background, focusedArtwork, !customizeHome)
                LazyColumn(
                    Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(
                        horizontal = if (presentation.layout == HomeLayout.CINEMA) 16.dp else 28.dp,
                        vertical = if (presentation.layout == HomeLayout.COMPACT) 16.dp else 22.dp,
                    ),
                    verticalArrangement = Arrangement.spacedBy(when (presentation.layout) { HomeLayout.COMPACT -> 12.dp; HomeLayout.CINEMA -> 28.dp; else -> 22.dp }),
                ) {
                    item(key = "header") {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("Make yourself at home.", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                                Text("Everything you love, all in one place.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            HomeCustomizationAccess(homePreferences, homeStore, groupNames) { customizeHome = it }
                            Spacer(Modifier.width(16.dp))
                            Text("NUVIX", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge)
                        }
                    }
                    if (presentation.featured != HomeFeatured.DISABLED) item(key = "hero") {
                        Box(
                            Modifier.fillMaxWidth().heightIn(min = when (presentation.layout) { HomeLayout.COMPACT -> 160.dp; HomeLayout.CINEMA -> 280.dp; else -> 220.dp }).clip(RoundedCornerShape(22.dp))
                                .background(Brush.horizontalGradient(listOf(Color(0xFF142E54), Color(0xFF0D213B), Color(0xFF0A172A)))),
                        ) {
                            Row(Modifier.fillMaxWidth().padding(24.dp), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                    Text(if (resumeFeature != null) "CONTINUE WATCHING" else if (featured != null) "LIVE · ${featured.shownName}" else "WELCOME TO NUVIX", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge)
                                    Text(
                                        resumeFeature?.title ?: programme?.title ?: if (featured != null) "Your next watch starts here." else "Your television. Your way.",
                                        style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis,
                                    )
                                    Text(
                                        programme?.description?.takeIf { it.isNotBlank() } ?: if (hasSources) "Discover your live channels, explore your guide, or pick up where you left off." else "Add your authorized IPTV provider to bring your channels, movies, and shows together.",
                                        color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.widthIn(max = if (presentation.layout == HomeLayout.CINEMA) 650.dp else 550.dp),
                                    )
                                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                        Button(onClick = { if (resumeFeature != null) onResume(resumeFeature.mediaKey, resumeFeature.streamUrl, resumeFeature.title) else if (featured != null) onPlayChannel(featured) else onAddSource() }) {
                                            Icon(Icons.Filled.PlayArrow, contentDescription = null)
                                            Spacer(Modifier.width(6.dp))
                                            Text(if (resumeFeature != null) "Resume watching" else if (featured != null) "Watch live" else "Add provider")
                                        }
                                        OutlinedButton(onClick = onOpenGuide) { Text("Explore guide") }
                                    }
                                }
                                if (featured?.logoUrl != null) {
                                    AsyncImage(featured.logoUrl, featured.shownName, Modifier.padding(start = 24.dp).size(if (presentation.layout == HomeLayout.CINEMA) 140.dp else 100.dp), contentScale = ContentScale.Fit)
                                }
                            }
                        }
                    }
                    val movieShelf = recommended.ifEmpty { movies }
                    order.forEach { key ->
                        if (homePreferences.isVisible(key)) when (HomeSection.fromKey(key)) {
                            HomeSection.CONTINUE -> if (resume.isNotEmpty()) item(key = key) {
                                HomeShelf("Continue watching", resume, { it.mediaKey }) { item ->
                                    ContentTile(item.title, item.posterUrl, "Resume watching", item.progress) { onResume(item.mediaKey, item.streamUrl, item.title) }
                                }
                            }
                            HomeSection.RECENT -> if (recent.isNotEmpty()) item(key = key) { ChannelShelf("Recently watched", recent.take(16), programmes, onPlayChannel) }
                            HomeSection.FAVOURITES -> if (favourites.isNotEmpty()) item(key = key) { ChannelShelf("Your favorite channels", favourites.take(24), programmes, onPlayChannel) }
                            HomeSection.ON_NOW -> if (channels.isNotEmpty()) item(key = key) { ChannelShelf("On now", channels.distinctBy { it.groupKey.ifBlank { "id:${it.id}" } }.take(16), programmes, onPlayChannel) }
                            HomeSection.MOVIES -> if (movieShelf.isNotEmpty()) item(key = key) {
                                HomeShelf(if (recommended.isNotEmpty()) "Recommended for you" else "Recently added movies", movieShelf.take(18), { it.id.toString() }) { movie ->
                                    ContentTile(movie.displayTitle, movie.posterUrl, "Movie", size = presentation.movieSize, artworkBackground = true) { onOpenMovie(movie) }
                                }
                            }
                            HomeSection.SERIES -> if (series.isNotEmpty()) item(key = key) {
                                HomeShelf("Recently added shows", series.take(18), { it.id.toString() }) { show ->
                                    ContentTile(show.displayTitle, show.posterUrl, "TV show", size = presentation.seriesSize, artworkBackground = true) { onOpenSeries(show) }
                                }
                            }
                            HomeSection.APPS -> item(key = key) { app.opentv.launcher.FavoriteAppsRow(onOpenApps = onOpenApps) }
                            null -> {
                                val group = customGroups.firstOrNull { HomeShelfOrder.groupKey(it.first.cloudId) == key }
                                if (group != null && group.second.isNotEmpty()) item(key = key) {
                                    ChannelShelf(group.first.name, group.second, programmes, onPlayChannel)
                                }
                            }
                        }
                    }
                    item(key = "navigation") {
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            if (presentation.featured == HomeFeatured.DISABLED) OutlinedButton(onClick = onOpenGuide) { Text("Explore guide") }
                            OutlinedButton(onClick = onOpenApps) { Text("Browse installed apps") }
                            OutlinedButton(onClick = onAddSource) { Text("Add another provider") }
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun ChannelShelf(title: String, channels: List<Channel>, programmes: List<Programme>, onPlay: (Channel) -> Unit) {
    HomeShelf(title, channels, { it.id.toString() }) { channel ->
        val current = programmes.firstOrNull { it.epgChannelId == (channel.epgOverrideId ?: channel.matchedEpgId ?: channel.epgChannelId) }
        ContentTile(channel.shownName, channel.logoUrl, current?.title ?: "Live TV", logo = true, size = LocalHomeTileStyle.current.presentation.channelSize) { onPlay(channel) }
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
internal fun ContentTile(title: String, art: String?, subtitle: String, progress: Float? = null, logo: Boolean = false, size: HomeCardSize = HomeCardSize.MEDIUM, artworkBackground: Boolean = false, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (focused) 1.025f else 1f, label = "homeTileFocus")
    val style = LocalHomeTileStyle.current
    val presentation = style.presentation
    val width = homeCardWidth(presentation.layout, size).dp
    val shape = RoundedCornerShape(presentation.corners.radius.dp)
    Column(
        Modifier.width(width).graphicsLayer { scaleX = scale; scaleY = scale }
            .onFocusChanged {
                focused = it.isFocused
                if (it.isFocused) style.onArtwork(if (artworkBackground) art else null)
            }.clip(shape)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .border(if (focused) 3.dp else 1.dp, if (focused) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline.copy(alpha = .4f), shape)
            .clickable(onClick = onClick),
    ) {
        Box(Modifier.fillMaxWidth().height(if (artworkBackground && presentation.layout == HomeLayout.CINEMA) width * 1.35f else width * (106f / 190f)).background(MaterialTheme.colorScheme.surface), contentAlignment = Alignment.Center) {
            if (art.isNullOrBlank()) Icon(Icons.Filled.LiveTv, null, Modifier.size(32.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            else AsyncImage(art, null, Modifier.fillMaxSize().padding(if (logo) 20.dp else 0.dp), contentScale = if (logo) ContentScale.Fit else ContentScale.Crop)
        }
        if (progress != null) LinearProgressIndicator(progress = { progress.coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth().height(3.dp))
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (presentation.metadata) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}
