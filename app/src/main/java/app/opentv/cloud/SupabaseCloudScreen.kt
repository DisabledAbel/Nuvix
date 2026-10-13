/* Nuvix modifications Copyright (C) 2026 Nuvix Contributors. GPL-3.0-or-later. */
package app.opentv.cloud

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.launch

/** TV input uses the device's real keyboard and email code; no fictitious QR/device login flow. */
@Composable
fun SupabaseCloudScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val manager = remember(context) { CloudSync.get(context) }
    val state by manager.state.collectAsState()
    val scope = rememberCoroutineScope()
    var url by remember { mutableStateOf("") }
    var key by remember { mutableStateOf("") }
    var email by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    var codeRequestedFor by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(manager) { manager.reload() }
    LaunchedEffect(state.projectUrl, state.publishableKey) { url = state.projectUrl; key = state.publishableKey }
    BackHandler(onBack = onBack)

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 32.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Cloud sync", style = MaterialTheme.typography.headlineMedium)
            Spacer(Modifier.weight(1f))
            OutlinedButton(onClick = onBack) { Text("Done") }
        }
        Text("Optional sync through your own Supabase project. Watch progress and favourites stay available offline.",
            modifier = Modifier.widthIn(max = 840.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
        Card(Modifier.widthIn(max = 900.dp).fillMaxWidth()) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Your Supabase project", style = MaterialTheme.typography.titleMedium)
                Text("Install the supplied cloud migration and set the email template to send a numeric code before signing in.")
                OutlinedTextField(url, { url = it }, label = { Text("HTTPS project URL") },
                    placeholder = { Text("https://your-project.supabase.co") }, singleLine = true,
                    enabled = !state.busy, modifier = Modifier.fillMaxWidth(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri))
                OutlinedTextField(key, { key = it }, label = { Text("Publishable or anon key") }, singleLine = true,
                    enabled = !state.busy, modifier = Modifier.fillMaxWidth(), visualTransformation = PasswordVisualTransformation())
                Button(enabled = !state.busy && url.isNotBlank() && key.isNotBlank(), onClick = {
                    codeRequestedFor = null
                    scope.launch { manager.configure(url, key) }
                }) { Text("Save project") }
            }
        }
        if (state.configured && state.email == null) {
            Card(Modifier.widthIn(max = 900.dp).fillMaxWidth()) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Sign in with email", style = MaterialTheme.typography.titleMedium)
                    Text("Read the code on your phone or computer, then enter it here. The project may create an account for your email.")
                    OutlinedTextField(email, { email = it; codeRequestedFor = null; code = "" }, label = { Text("Email address") },
                        singleLine = true, enabled = !state.busy, modifier = Modifier.fillMaxWidth(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email))
                    Button(enabled = !state.busy && email.contains('@'), onClick = {
                        codeRequestedFor = email.trim()
                        scope.launch { manager.requestCode(email) }
                    }) { Text("Send email code") }
                    if (codeRequestedFor != null) {
                        OutlinedTextField(code, { code = it.filter(Char::isDigit).take(10) }, label = { Text("Email code") },
                            singleLine = true, enabled = !state.busy, modifier = Modifier.fillMaxWidth(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword))
                        Button(enabled = !state.busy && code.length in 6..10, onClick = {
                            val requestedEmail = codeRequestedFor ?: return@Button
                            scope.launch { manager.verifyCode(requestedEmail, code); code = "" }
                        }) { Text("Verify code") }
                    }
                }
            }
        }
        if (state.email != null) {
            Card(Modifier.widthIn(max = 900.dp).fillMaxWidth()) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Signed in as ${state.email}", style = MaterialTheme.typography.titleMedium)
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        Text("Enable cloud sync", style = MaterialTheme.typography.titleMedium)
                        Switch(state.enabled, onCheckedChange = { scope.launch { manager.setEnabled(it) } }, enabled = !state.busy)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Button(enabled = state.enabled && !state.busy, onClick = { scope.launch { manager.syncNow() } }) { Text("Sync now") }
                        OutlinedButton(enabled = !state.busy, onClick = { scope.launch { manager.signOut() } }) { Text("Sign out") }
                    }
                }
            }
        }
        Text(state.message, style = MaterialTheme.typography.bodyLarge)
        if (state.lastSynced > 0) Text("Last synced: ${DateFormat.getDateTimeInstance().format(Date(state.lastSynced))}",
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text("Shared: content fingerprints, favourites, profile fingerprints, resume times and a random device ID. Provider URLs, logins, playlists, recordings and settings are never uploaded. Favourites merge additively; removing one locally does not remove it from other devices. Progress sync needs matching content and profile names.",
            modifier = Modifier.widthIn(max = 900.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedButton(enabled = !state.busy, onClick = { scope.launch { manager.reset() } }) { Text("Remove cloud setup from this TV") }
        Spacer(Modifier.height(16.dp))
    }
}
