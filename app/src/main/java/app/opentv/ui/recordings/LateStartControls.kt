/* Copyright (C) 2026 Nuvix Contributors. GPL-3.0-or-later. */
package app.opentv.ui.recordings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.unit.dp
import app.opentv.recording.LateStartOptions
import app.opentv.recording.LateStartPolicy

/** Shared TV controls: every choice and custom minute is reachable with D-pad/OK. */
@Composable
fun LateStartControls(value: LateStartOptions, inherit: Boolean = true, onChange: (LateStartOptions) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Late Start", style = MaterialTheme.typography.titleLarge)
        LateStartPolicy.entries.filter { inherit || it != LateStartPolicy.INHERIT }.forEach { policy ->
            LateStartButton("${if (value.policy == policy) "● " else ""}${policy.label}", { onChange(value.copy(policy = policy)) })
            if (value.policy == policy) Text(policy.description, style = MaterialTheme.typography.bodyMedium)
        }
        if (value.policy == LateStartPolicy.MINIMUM_REMAINING) {
            Text("Minimum remaining: ${value.minimumMinutes} minutes", style = MaterialTheme.typography.titleMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for (minutes in listOf(5, 10, 15, 30))
                    LateStartButton("$minutes min", { onChange(value.copy(minimumMinutes = minutes)) })
            }
            Text("Custom duration (1–1440 minutes)")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                LateStartButton("−1 minute", { onChange(value.copy(minimumMinutes = value.minimumMinutes - 1)) }, value.minimumMinutes > 1)
                LateStartButton("+1 minute", { onChange(value.copy(minimumMinutes = value.minimumMinutes + 1)) }, value.minimumMinutes < 1440)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                LateStartButton("−60 minutes", { onChange(value.copy(minimumMinutes = (value.minimumMinutes - 60).coerceAtLeast(1))) }, value.minimumMinutes > 1)
                LateStartButton("+60 minutes", { onChange(value.copy(minimumMinutes = (value.minimumMinutes + 60).coerceAtMost(1440))) }, value.minimumMinutes < 1440)
            }
            Text("Exactly the minimum is allowed. Scheduled program time counts; end padding and manual extensions do not.", style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun LateStartButton(label: String, onClick: () -> Unit, enabled: Boolean = true) {
    var focused by remember { mutableStateOf(false) }
    OutlinedButton(onClick = onClick, enabled = enabled,
        modifier = Modifier.onFocusChanged { focused = it.isFocused },
        border = BorderStroke(if (focused) 3.dp else 1.dp,
            if (focused) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline)) {
        Text(label, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
fun LateStartDialog(title: String, initial: LateStartOptions, busy: Boolean = false, error: String? = null,
    onDismiss: () -> Unit, onSave: (LateStartOptions) -> Unit) {
    var value by remember(initial) { mutableStateOf(initial) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(title) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                LateStartControls(value, onChange = { value = it })
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = { OutlinedButton(onClick = { onSave(value) }, enabled = !busy) { Text("Save Late Start") } },
        dismissButton = { OutlinedButton(onClick = onDismiss) { Text("Cancel") } })
}
