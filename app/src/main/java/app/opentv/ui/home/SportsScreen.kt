/* Nuvix modifications Copyright (C) 2026 Nuvix Contributors. GPL-3.0-or-later. */
package app.opentv.ui.home

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.opentv.core.ServiceLocator
import app.opentv.data.model.Channel
import app.opentv.data.model.shownName
import kotlinx.coroutines.delay

@Composable
fun SportsScreen(onPlayChannel: (Channel) -> Unit) {
    val context = LocalContext.current
    val graph = remember { ServiceLocator.get(context) }
    val channels by remember { graph.database.channels().observeSportsChannels() }.collectAsState(initial = emptyList())
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { delay(30_000); now = System.currentTimeMillis() } }
    val programmes by remember(now) { graph.epgRepository.observeNow(now) }.collectAsState(initial = emptyList())
    Column(Modifier.fillMaxSize().padding(28.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("The best seat in the house.", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Text("Live sports from your provider", color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (channels.isEmpty()) {
            Spacer(Modifier.height(24.dp))
            Text("No sports channels yet", style = MaterialTheme.typography.titleLarge)
            Text("Sports appear here when your provider supplies sports categories. Add or refresh a provider in Settings.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            LazyVerticalGrid(columns = GridCells.Adaptive(190.dp), verticalArrangement = Arrangement.spacedBy(18.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                items(channels.distinctBy { it.groupKey.ifBlank { "id:${it.id}" } }, key = { it.id }) { channel ->
                    val p = programmes.firstOrNull { it.epgChannelId == (channel.epgOverrideId ?: channel.matchedEpgId ?: channel.epgChannelId) }
                    ContentTile(channel.shownName, channel.logoUrl, p?.title ?: "Live sports", logo = true) { onPlayChannel(channel) }
                }
            }
        }
    }
}
