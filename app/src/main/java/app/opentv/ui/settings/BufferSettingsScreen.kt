/*
 * Nuvix buffer controls. Copyright (C) 2026 Nuvix Contributors.
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import app.opentv.player.BufferConfiguration
import app.opentv.player.BufferPolicy
import app.opentv.player.BufferPreset
import app.opentv.player.BufferSettings

/** Displays buffering presets and validated custom timing controls. */
@Composable
fun BufferSettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val settings = remember { BufferSettings.get(context) }
    val configuration by settings.configuration.collectAsState()
    var preset by remember(configuration) { mutableStateOf(configuration.preset) }
    var minimum by remember(configuration) { mutableStateOf(configuration.custom.minimumMs.toString()) }
    var maximum by remember(configuration) { mutableStateOf(configuration.custom.maximumMs.toString()) }
    var playback by remember(configuration) { mutableStateOf(configuration.custom.playbackMs.toString()) }
    var rebuffer by remember(configuration) { mutableStateOf(configuration.custom.rebufferMs.toString()) }
    var saved by remember { mutableStateOf(false) }
    val parsed = runCatching {
        BufferPolicy(minimum.toInt(), maximum.toInt(), playback.toInt(), rebuffer.toInt())
    }
    val error = if (preset == BufferPreset.CUSTOM) parsed.exceptionOrNull()?.let {
        if (it is NumberFormatException) "Enter whole numbers in milliseconds." else it.message
    } else null

    Column(Modifier.fillMaxSize().padding(32.dp)) {
        Row(Modifier.fillMaxWidth()) {
            Text("Playback buffering", style = MaterialTheme.typography.headlineMedium)
            Spacer(Modifier.weight(1f))
            OutlinedButton(onClick = onBack) { Text("Done") }
        }
        Spacer(Modifier.height(12.dp))
        Text("Changes apply to active playback without restarting the player. Larger buffers use more memory and can delay playback. Automatic adapts to the device's memory.", modifier = Modifier.widthIn(max = 800.dp))
        Spacer(Modifier.height(16.dp))
        LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.widthIn(max = 760.dp)) {
            BufferPreset.entries.forEach { option ->
                item {
                    OutlinedButton(onClick = { preset = option; saved = false }, modifier = Modifier.fillMaxWidth()) {
                        Text((if (preset == option) "✓  " else "") + option.label)
                    }
                }
            }
            if (preset == BufferPreset.CUSTOM) {
                item { BufferValue("Minimum buffer (1,000–60,000 ms)", minimum) { minimum = it; saved = false } }
                item { BufferValue("Maximum buffer (up to 120,000 ms)", maximum) { maximum = it; saved = false } }
                item { BufferValue("Start playback after (500–30,000 ms)", playback) { playback = it; saved = false } }
                item { BufferValue("Resume after a stall (500–30,000 ms)", rebuffer) { rebuffer = it; saved = false } }
                item { Text("Start and resume thresholds must be no greater than the minimum buffer.") }
            }
            error?.let { message -> item { Text(message, color = MaterialTheme.colorScheme.error) } }
            item {
                Button(onClick = {
                    settings.set(BufferConfiguration(preset, parsed.getOrNull() ?: configuration.custom))
                    saved = true
                }, enabled = error == null, modifier = Modifier.fillMaxWidth()) { Text("Apply buffering") }
            }
            if (saved) item { Text("Buffering preference saved.", color = MaterialTheme.colorScheme.primary) }
        }
    }
}

/** Displays an individual buffer timing value and its adjustment controls. */
@Composable
private fun BufferValue(label: String, value: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = { input -> if (input.length <= 6 && input.all(Char::isDigit)) onChange(input) },
        label = { Text(label) }, singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = Modifier.fillMaxWidth(),
    )
}
