/* Nuvix modifications Copyright (C) 2026 Nuvix Contributors. GPL-3.0-or-later. */
package app.opentv.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.opentv.update.UpdateSettingsControls

/** Displays update-channel information, background-check preferences and manual update controls. */
@Composable
fun NuvixUpdateSettingsScreen(onBack: () -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(28.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Row(Modifier.fillMaxWidth()) {
            Text("Nuvix updates", Modifier.weight(1f), style = MaterialTheme.typography.headlineMedium)
            OutlinedButton(onClick = onBack) { Text("Done") }
        }
        UpdateSettingsControls()
    }
}
