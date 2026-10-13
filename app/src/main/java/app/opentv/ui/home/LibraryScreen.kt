/* Nuvix modifications Copyright (C) 2026 Nuvix Contributors. GPL-3.0-or-later. */
package app.opentv.ui.home

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import app.opentv.core.ServiceLocator
import app.opentv.data.model.*
import app.opentv.data.parser.displayTitle
import app.opentv.ui.VodViewModel
import app.opentv.ui.recordings.RecordingsScreen

@Composable
fun LibraryScreen(onPlayRecording: (Recording) -> Unit, onPlayChannel: (Channel) -> Unit, onOpenMovie: (Movie) -> Unit, onResume: (String, String, String) -> Unit, vod: VodViewModel = viewModel()) {
    var recordings by remember { mutableStateOf(true) }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.padding(horizontal = 28.dp, vertical = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            FilterChip(recordings, onClick = { recordings = true }, label = { Text("Recordings") })
            FilterChip(!recordings, onClick = { recordings = false }, label = { Text("Favorites & continue watching") })
        }
        if (recordings) RecordingsScreen(onPlay = onPlayRecording)
        else {
            val context = LocalContext.current
            val graph = remember { ServiceLocator.get(context) }
            val favourites by remember { graph.database.channels().observeFavourites() }.collectAsState(initial = emptyList())
            val movies by remember { graph.database.movies().observeFavourites() }.collectAsState(initial = emptyList())
            val resume by vod.continueWatching.collectAsState()
            LazyColumn(contentPadding = PaddingValues(28.dp), verticalArrangement = Arrangement.spacedBy(24.dp)) {
                if (resume.isNotEmpty()) item {
                    HomeShelf("Continue watching", resume, { it.mediaKey }) { r ->
                        ContentTile(r.title, r.posterUrl, "Resume", r.progress) { onResume(r.mediaKey, r.streamUrl, r.title) }
                    }
                }
                if (favourites.isNotEmpty()) item { ChannelShelf("Favorite channels", favourites, emptyList(), onPlayChannel) }
                if (movies.isNotEmpty()) item {
                    HomeShelf("Favorite movies", movies, { it.id.toString() }) { m ->
                        ContentTile(m.displayTitle, m.posterUrl, "Movie") { onOpenMovie(m) }
                    }
                }
                if (resume.isEmpty() && favourites.isEmpty() && movies.isEmpty()) item {
                    Text("Your favorites live here", style = MaterialTheme.typography.headlineMedium)
                    Text("Favorite a channel or movie, or start watching a movie or episode to build your library.")
                }
            }
        }
    }
}
