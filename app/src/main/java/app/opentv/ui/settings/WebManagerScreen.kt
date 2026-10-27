/* Nuvix Contributors. GPL-3.0-or-later. */
package app.opentv.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import app.opentv.pairing.CloudManagerClient
import app.opentv.pairing.CloudManagerProtocol
import app.opentv.ui.WebManagerViewModel
import com.disabledabel.nuvix.BuildConfig
import com.disabledabel.nuvix.R

/** Code-only Cloudflare pairing; opening this screen alone sends no data to the service. */
@Composable
fun WebManagerScreen(onBack: () -> Unit, viewModel: WebManagerViewModel = viewModel()) {
    val state by viewModel.state.collectAsState()
    var origin by rememberSaveable { mutableStateOf(BuildConfig.MANAGER_URL) }
    DisposableEffect(Unit) { onDispose { viewModel.stop() } }

    Column(
        Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(stringResource(R.string.webmanager_title), style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(16.dp))
        when (val current = state) {
            CloudManagerClient.State.Idle, is CloudManagerClient.State.Failed -> {
                if (current is CloudManagerClient.State.Failed) {
                    Text(current.reason, color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center)
                    Spacer(Modifier.height(12.dp))
                }
                Text(stringResource(R.string.cloudmanager_consent), textAlign = TextAlign.Center)
                if (BuildConfig.MANAGER_URL.isBlank()) {
                    Spacer(Modifier.height(12.dp))
                    Text(stringResource(R.string.cloudmanager_not_configured), textAlign = TextAlign.Center)
                    OutlinedTextField(value = origin, onValueChange = { origin = it }, singleLine = true,
                        label = { Text(stringResource(R.string.cloudmanager_address)) })
                } else {
                    Text(origin, style = MaterialTheme.typography.titleMedium)
                }
                Spacer(Modifier.height(16.dp))
                Button(onClick = { viewModel.start(origin) }, enabled = CloudManagerProtocol.origin(origin) != null) {
                    Text(stringResource(R.string.cloudmanager_start))
                }
            }
            CloudManagerClient.State.Connecting -> {
                CircularProgressIndicator()
                Text(stringResource(R.string.cloudmanager_connecting))
            }
            is CloudManagerClient.State.Ready -> {
                Text(current.url, style = MaterialTheme.typography.headlineSmall)
                Spacer(Modifier.height(12.dp))
                if (current.paired) {
                    Text(stringResource(R.string.cloudmanager_paired), textAlign = TextAlign.Center)
                } else {
                    Text(stringResource(R.string.cloudmanager_enter_code))
                    Text(CloudManagerProtocol.displayCode(current.code), style = MaterialTheme.typography.displayMedium)
                    Text(stringResource(R.string.cloudmanager_code_expiry), textAlign = TextAlign.Center)
                }
                Spacer(Modifier.height(12.dp))
                Text(stringResource(R.string.cloudmanager_keep_open), textAlign = TextAlign.Center)
            }
        }
        Spacer(Modifier.height(24.dp))
        OutlinedButton(onClick = { viewModel.stop(); onBack() }) {
            Text(stringResource(R.string.common_done))
        }
    }
}
