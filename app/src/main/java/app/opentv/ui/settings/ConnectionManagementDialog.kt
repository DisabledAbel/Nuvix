/* Nuvix Copyright (C) 2026 Nuvix Contributors. GPL-3.0-or-later. */
package app.opentv.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import app.opentv.connections.AccountConnectionManager
import app.opentv.data.model.Source

/** Account settings use provider labels only; opaque identity digests never need to be shown. */
@Composable
internal fun ConnectionManagementDialog(source: Source, sources: List<Source>, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val manager = remember { AccountConnectionManager.get(context) }
    LaunchedEffect(sources) { sources.forEach(manager::registerSource) }
    val snapshots by manager.snapshots.collectAsState()
    val snapshot = snapshots.firstOrNull { source.id in it.sourceIds }
    var limit by remember(source.id, snapshot?.effectiveMaximum) { mutableStateOf(snapshot?.effectiveMaximum?.toString().orEmpty()) }
    var error by remember { mutableStateOf<String?>(null) }
    var linking by remember { mutableStateOf<Source?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Connection Management · ${source.name}") },
        text = {
            Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Allowance: ${snapshot?.allowanceLabel ?: "Unknown"} · Locally reserved: ${snapshot?.locallyReserved ?: 0}")
                Text("This limit controls Nuvix's allocation. It does not change your provider's permissions. Usage on other devices is unknown.")
                snapshot?.providerReportedActive?.let { Text("Provider last reported $it active connection(s). This observation can be stale and may include other devices.") }
                Text("Known provider connection-limit rejections: ${snapshot?.knownConnectionLimitErrors ?: 0}")
                OutlinedTextField(
                    value = limit, onValueChange = { if (it.length <= 4 && it.all(Char::isDigit)) limit = it },
                    label = { Text("Maximum simultaneous connections") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = {
                        val maximum = limit.toIntOrNull()?.takeIf { it > 0 }
                        if (maximum == null) error = "Enter a positive connection limit, or choose Unknown."
                        else { manager.setMaximumConnections(source.id, maximum); error = null }
                    }) { Text("Set limit") }
                    OutlinedButton(onClick = { manager.setMaximumConnections(source.id, null); error = null }) { Text("Unknown") }
                }
                OutlinedButton(onClick = { manager.setUseProviderMetadata(source.id, true); error = null }) { Text("Use provider-reported allowance") }
                Text("Unknown allowance: Nuvix asks before an additional live connection. Each approval is for one request. Unattended recording cannot approve extra connections for you.")
                Text("Recording priority: when capacity is needed, Nuvix closes optional previews and conflicting live viewing on this account. It keeps unrelated accounts playing and does not reopen viewing automatically.")
                Text("Shared account playlists", style = MaterialTheme.typography.titleSmall)
                val linkedNames = sources.filter { it.id in snapshot?.sourceIds.orEmpty() }.joinToString { it.name }
                Text(linkedNames.ifBlank { source.name })
                Text("Link playlists only when you know they use the same IPTV account. Linked playlists share one limit. Stop their connections before changing links.")
                sources.filter { it.id != source.id && it.id !in snapshot?.sourceIds.orEmpty() }.forEach { target ->
                    TextButton(onClick = { linking = target }) { Text("Share account with ${target.name}") }
                }
                if (manager.isManuallyAssociated(source.id)) TextButton(onClick = {
                    error = if (manager.clearAssociation(source.id)) null else "Stop this account's connections before removing the link."
                }) { Text("Remove manual association") }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
    )
    linking?.let { target ->
        AlertDialog(
            onDismissRequest = { linking = null },
            title = { Text("Use one account pool?") },
            text = { Text("Confirm that ${source.name} and ${target.name} use the same IPTV account. ${target.name}'s account allowance will apply to both playlists.") },
            confirmButton = { TextButton(onClick = {
                error = if (manager.associateSources(source.id, target.id)) null else "Stop both accounts' connections before linking their playlists."
                linking = null
            }) { Text("Confirm shared account") } },
            dismissButton = { TextButton(onClick = { linking = null }) { Text("Cancel") } },
        )
    }
}
