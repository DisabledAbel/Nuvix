/* Nuvix modifications Copyright (C) 2026 Nuvix Contributors. GPL-3.0-or-later. */
package app.opentv.ui.channels

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.opentv.core.ServiceLocator
import app.opentv.data.model.Channel
import app.opentv.data.model.CustomChannelGroup
import app.opentv.data.model.Programme
import app.opentv.data.model.StreamKind
import app.opentv.data.model.shownName
import app.opentv.data.repo.LocalCustomGroups
import app.opentv.ui.home.ChannelShelf
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch

/** Reuses Channel Manager and the provider catalogue; no cloud payload can create a channel. */
@Composable
fun CustomGroupsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val graph = remember { ServiceLocator.get(context) }
    val store = remember { LocalCustomGroups(graph.database) }
    val groups by remember { store.dao.observeActive() }.collectAsState(initial = emptyList())
    val channels by remember { store.dao.observeAuthorizedChannels() }.collectAsState(initial = emptyList())
    val categories by remember { graph.database.categories().observe(StreamKind.LIVE) }.collectAsState(initial = emptyList())
    val hidden by graph.settings.hiddenCategories.collectAsState()
    val unlocked by graph.settings.hiddenUnlocked.collectAsState()
    val choices = remember(channels, categories, hidden, unlocked) {
        LocalCustomGroups.matchingChannels(channels).values.filter {
            LocalCustomGroups.visible(it, categories, hidden, unlocked)
        }.sortedBy { it.shownName }
    }
    val matches = remember(channels) { LocalCustomGroups.matchingChannels(channels) }
    var selectedId by remember { mutableStateOf<String?>(null) }
    val selected = groups.firstOrNull { it.cloudId == selectedId }
    var nameDialog by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<CustomChannelGroup?>(null) }
    var name by remember { mutableStateOf("") }
    var choosing by remember { mutableStateOf(false) }
    var search by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    fun mutate(action: suspend () -> Unit) { scope.launch {
        runCatching { action() }.onFailure { error = "The group could not be saved. Please try again." }
    } }

    BackHandler { if (choosing) choosing = false else onBack() }
    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Custom channel groups", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.weight(1f))
            OutlinedButton(onClick = { editing = null; name = ""; nameDialog = true }, enabled = groups.size < LocalCustomGroups.MAX_GROUPS) { Text("Create group") }
            OutlinedButton(onClick = onBack) { Text("Done") }
        }
        Text("Create your own channel shelves. Channels stay in their provider and unavailable references are kept for later.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (error != null) Text(error!!, color = MaterialTheme.colorScheme.error)
        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            LazyColumn(Modifier.width(260.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (groups.isEmpty()) item { Text("Create a group to start organizing channels.") }
                items(groups, key = { it.cloudId }) { group ->
                    Card(Modifier.fillMaxWidth().clickable { selectedId = group.cloudId; choosing = false },
                        colors = CardDefaults.cardColors(containerColor = if (selectedId == group.cloudId) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant)) {
                        Column(Modifier.padding(12.dp)) {
                            Text(group.name, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text("${group.members.size} channels", style = MaterialTheme.typography.bodySmall)
                            Row {
                                TextButton(onClick = { mutate { store.moveGroup(group.cloudId, -1) } }, enabled = groups.firstOrNull()?.cloudId != group.cloudId) { Text("Up") }
                                TextButton(onClick = { mutate { store.moveGroup(group.cloudId, 1) } }, enabled = groups.lastOrNull()?.cloudId != group.cloudId) { Text("Down") }
                            }
                        }
                    }
                }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (selected == null) {
                    Text("Select a group to rename it, add channels, or change their order.")
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(selected.name, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        TextButton(onClick = { editing = selected; name = selected.name; nameDialog = true }) { Text("Rename") }
                        TextButton(onClick = { choosing = !choosing; search = "" }) { Text(if (choosing) "Finish adding" else "Add channels") }
                        TextButton(onClick = { mutate { store.delete(selected.cloudId) } }) { Text("Delete group") }
                    }
                    if (choosing) {
                        OutlinedTextField(search, { search = it.take(120) }, label = { Text("Search channels") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                        val filtered = remember(choices, search, selected.members) {
                            choices.filter { it.shownName.contains(search.trim(), ignoreCase = true) && LocalCustomGroups.fingerprint(it) !in selected.members }.take(200)
                        }
                        Text("Available authorized channels (up to 200 matches). Hidden and ambiguous channels are excluded.", style = MaterialTheme.typography.bodySmall)
                        LazyColumn(Modifier.weight(1f)) {
                            items(filtered, key = { it.id }) { channel ->
                                TextButton(onClick = { mutate { store.addMember(selected.cloudId, LocalCustomGroups.fingerprint(channel)) } }, enabled = selected.members.size < LocalCustomGroups.MAX_MEMBERS, modifier = Modifier.fillMaxWidth()) {
                                    Text("Add ${channel.shownName}", modifier = Modifier.fillMaxWidth())
                                }
                            }
                        }
                    } else {
                        if (selected.members.isEmpty()) Text("This group is empty. Choose Add channels.")
                        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            items(selected.members, key = { it }) { fingerprint ->
                                val match = matches[fingerprint]?.takeIf { LocalCustomGroups.visible(it, categories, hidden, unlocked) }
                                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                    Column(Modifier.weight(1f)) {
                                        Text(match?.shownName ?: "Unavailable channel", maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        if (match == null) Text("Reference retained · ${fingerprint.take(8)}", style = MaterialTheme.typography.bodySmall)
                                    }
                                    TextButton(onClick = { mutate { store.moveMember(selected.cloudId, fingerprint, -1) } }, enabled = selected.members.firstOrNull() != fingerprint) { Text("Up") }
                                    TextButton(onClick = { mutate { store.moveMember(selected.cloudId, fingerprint, 1) } }, enabled = selected.members.lastOrNull() != fingerprint) { Text("Down") }
                                    TextButton(onClick = { mutate { store.removeMember(selected.cloudId, fingerprint) } }) { Text("Remove") }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    if (nameDialog) AlertDialog(
        onDismissRequest = { nameDialog = false },
        title = { Text(if (editing == null) "Create group" else "Rename group") },
        text = { OutlinedTextField(name, { name = it.take(240) }, label = { Text("Group name (120 characters)") }, singleLine = true) },
        confirmButton = { TextButton(enabled = LocalCustomGroups.validName(name), onClick = {
            val target = editing
            mutate { if (target == null) selectedId = store.create(name) else store.rename(target.cloudId, name) }
            nameDialog = false
        }) { Text("Save") } },
        dismissButton = { TextButton(onClick = { nameDialog = false }) { Text("Cancel") } },
    )
}

/** Only unambiguous, enabled-provider channels passing the guide's visibility rules may play. */
@Composable
fun CustomGroupShelves(onPlayChannel: (Channel) -> Unit, programmes: List<Programme>) {
    val context = LocalContext.current
    val graph = remember { ServiceLocator.get(context) }
    val groups by remember { graph.database.customGroups().observeActive() }.collectAsState(initial = emptyList())
    // Opening Home without groups does not materialize a provider's complete catalogue.
    val channelFlow = remember(groups.isNotEmpty()) {
        if (groups.isEmpty()) flowOf(emptyList()) else graph.database.customGroups().observeAuthorizedChannels()
    }
    val channels by channelFlow.collectAsState(initial = emptyList())
    val categories by remember { graph.database.categories().observe(StreamKind.LIVE) }.collectAsState(initial = emptyList())
    val hidden by graph.settings.hiddenCategories.collectAsState()
    val unlocked by graph.settings.hiddenUnlocked.collectAsState()
    val resolved = remember(groups, channels, categories, hidden, unlocked) {
        groups.map { it to LocalCustomGroups.resolve(it, channels, categories, hidden, unlocked) }
    }
    Column(verticalArrangement = Arrangement.spacedBy(22.dp)) {
        resolved.forEach { (group, playable) ->
            if (playable.isNotEmpty()) ChannelShelf(group.name, playable, programmes, onPlayChannel)
        }
    }
}
