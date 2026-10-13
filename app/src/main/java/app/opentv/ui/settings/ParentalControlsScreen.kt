/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import app.opentv.R
import app.opentv.core.ServiceLocator
import app.opentv.ui.ChannelsViewModel

/**
 * Parental controls: a PIN, and a list of categories to keep out of the guide.
 *
 * Hidden categories vanish from Live TV — rail, All channels, and search — until the session is
 * unlocked here. If a PIN is set, this screen is itself behind the PIN, so a child cannot simply
 * come here and unlock. The PIN is a family lock, not a vault: it's a four-digit code stored as a
 * salted hash, enough to stop casual access, not a serious attacker.
 */
@Composable
fun ParentalControlsScreen(
    onBack: () -> Unit,
    channelsViewModel: ChannelsViewModel = viewModel(),
) {
    val context = LocalContext.current
    val settings = remember { ServiceLocator.get(context).settings }
    val pinIsSet by settings.pinIsSet.collectAsState()
    val hidden by settings.hiddenCategories.collectAsState()
    val unlocked by settings.hiddenUnlocked.collectAsState()
    val categories by channelsViewModel.categoryGroups.collectAsState()

    var authed by remember { mutableStateOf(false) }

    if (pinIsSet && !authed) {
        PinGate(
            onCancel = onBack,
            onSubmit = { entered -> settings.verifyPin(entered).also { if (it) authed = true } },
        )
        return
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 32.dp, vertical = 24.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.settings_parental_title), style = MaterialTheme.typography.headlineMedium)
            Spacer(Modifier.weight(1f))
            OutlinedButton(onClick = onBack) { Text(stringResource(R.string.common_done)) }
        }

        Spacer(Modifier.height(20.dp))

        PinSection(pinIsSet = pinIsSet, onSetPin = settings::setPin, onClearPin = settings::clearPin)

        Spacer(Modifier.height(16.dp))

        Text(stringResource(R.string.parental_hidden_categories), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(6.dp))
        Text(
            stringResource(R.string.parental_hidden_desc),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.widthIn(max = 720.dp),
        )
        Spacer(Modifier.height(12.dp))

        Card {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.parental_show_hidden_title), style = MaterialTheme.typography.titleMedium)
                    Text(
                        stringResource(R.string.parental_show_hidden_subtitle),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = unlocked, onCheckedChange = settings::setHiddenUnlocked)
            }
        }

        Spacer(Modifier.height(12.dp))

        if (categories.isEmpty()) {
            Text(
                stringResource(R.string.parental_no_categories),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            // A plain Column (not LazyColumn) because the whole screen is now inside a
            // verticalScroll — nesting a second scroll container here is what left the lower
            // categories unreachable on the remote. Provider categories number in the dozens,
            // so rendering them all is fine.
            Column(
                verticalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.fillMaxWidth().widthIn(max = 720.dp),
            ) {
                categories.forEach { group ->
                    Card {
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(group.label, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                            Switch(
                                checked = group.key in hidden,
                                onCheckedChange = { on ->
                                    val next = hidden.toMutableSet().apply { if (on) add(group.key) else remove(group.key) }
                                    settings.setHiddenCategories(next)
                                },
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun PinSection(
    pinIsSet: Boolean,
    onSetPin: (String) -> Unit,
    onClearPin: () -> Unit,
) {
    var editing by remember { mutableStateOf(false) }
    var pin by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    val pinLenError = stringResource(R.string.parental_pin_len_error)
    val pinMismatchError = stringResource(R.string.parental_pin_mismatch)

    Text(stringResource(R.string.parental_pin), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
    Spacer(Modifier.height(8.dp))
    Card {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            if (pinIsSet && !editing) {
                Text(stringResource(R.string.parental_pin_is_set), style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedButton(onClick = { editing = true; pin = ""; confirm = ""; error = null }) {
                        Text(stringResource(R.string.parental_change_pin))
                    }
                    OutlinedButton(onClick = onClearPin) { Text(stringResource(R.string.parental_remove_pin)) }
                }
            } else {
                Text(
                    if (pinIsSet) stringResource(R.string.parental_pin_enter_new) else stringResource(R.string.parental_pin_set),
                    style = MaterialTheme.typography.titleMedium,
                )
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    PinField(stringResource(R.string.parental_pin_new), pin) { if (it.length <= 4) pin = it.filter(Char::isDigit) }
                    PinField(stringResource(R.string.parental_pin_confirm), confirm) { if (it.length <= 4) confirm = it.filter(Char::isDigit) }
                }
                error?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                }
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(
                        enabled = pin.length == 4 && confirm.length == 4,
                        onClick = {
                            when {
                                pin.length != 4 -> error = pinLenError
                                pin != confirm -> error = pinMismatchError
                                else -> {
                                    onSetPin(pin); editing = false; pin = ""; confirm = ""; error = null
                                }
                            }
                        },
                    ) { Text(stringResource(R.string.parental_save_pin)) }
                    if (editing) {
                        OutlinedButton(onClick = { editing = false; error = null }) { Text(stringResource(R.string.common_cancel)) }
                    }
                }
            }
        }
    }
}

@Composable
private fun PinField(label: String, value: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
        modifier = Modifier.width(160.dp),
    )
}

@Composable
private fun PinGate(onCancel: () -> Unit, onSubmit: (String) -> Boolean) {
    var pin by remember { mutableStateOf("") }
    var error by remember { mutableStateOf(false) }

    Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(stringResource(R.string.parental_enter_pin), style = MaterialTheme.typography.headlineMedium)
            Spacer(Modifier.height(16.dp))
            PinField(stringResource(R.string.parental_pin), pin) { if (it.length <= 4) pin = it.filter(Char::isDigit) }
            if (error) {
                Spacer(Modifier.height(8.dp))
                Text(stringResource(R.string.parental_wrong_pin), color = MaterialTheme.colorScheme.error)
            }
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(
                    enabled = pin.length == 4,
                    onClick = { if (!onSubmit(pin)) { error = true; pin = "" } },
                ) { Text(stringResource(R.string.parental_unlock)) }
                OutlinedButton(onClick = onCancel) { Text(stringResource(R.string.common_back)) }
            }
        }
    }
}
