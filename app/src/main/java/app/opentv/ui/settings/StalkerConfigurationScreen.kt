/* Copyright (C) 2026 Nuvix Contributors. GPL-3.0-or-later. */
package app.opentv.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.unit.dp
import app.opentv.data.model.Source
import app.opentv.data.model.StalkerIdentity

/** Local draft only. Cancel, Back and closing the screen never persist identity values. */
@Composable
internal fun StalkerConfigurationScreen(
    source: Source,
    onCancel: () -> Unit,
    onSave: (Source, (String?) -> Unit) -> Unit,
    onSaved: () -> Unit,
) {
    var mac by remember(source.id) { mutableStateOf(source.macAddress.orEmpty().uppercase(java.util.Locale.ROOT)) }
    var editingMac by remember { mutableStateOf(false) }
    var advanced by remember { mutableStateOf(false) }
    var serial by remember { mutableStateOf(source.stalkerSerialNumber) }
    var device1 by remember { mutableStateOf(source.stalkerDeviceId1) }
    var device2 by remember { mutableStateOf(source.stalkerDeviceId2) }
    var signature by remember { mutableStateOf(source.stalkerSignature) }
    var error by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    var confirming by remember { mutableStateOf(false) }
    val initialFocus = remember { FocusRequester() }
    LaunchedEffect(source.id) { initialFocus.requestFocus() }
    BackHandler { if (!saving) { if (confirming) confirming = false else onCancel() } }

    fun draft() = source.copy(macAddress = mac, stalkerSerialNumber = serial,
        stalkerDeviceId1 = device1, stalkerDeviceId2 = device2, stalkerSignature = signature)
    fun save() {
        saving = true
        onSave(draft()) { failure ->
            saving = false
            if (failure == null) onSaved() else error = failure
        }
    }
    val macChanged = !mac.equals(source.macAddress, ignoreCase = true)
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(32.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Advanced Configuration · ${source.name}", style = MaterialTheme.typography.headlineMedium)
        Text("Use the values assigned to your account by your IPTV provider. Automatic is recommended unless your provider instructs you otherwise.")
        Text("Portal MAC Address: $mac")
        Text("This is your portal subscription identity. It does not change the TV's physical network MAC.")
        IdentityAction("Edit MAC Address", enabled = !saving, modifier = Modifier.focusRequester(initialFocus)) { editingMac = !editingMac }
        if (editingMac) OutlinedTextField(value = mac, onValueChange = { mac = it; error = null },
            label = { Text("Portal MAC Address") }, singleLine = true, enabled = !saving,
            modifier = Modifier.fillMaxWidth().testTag("portal-mac"))
        if (macChanged) Text(StalkerIdentity.MAC_WARNING, color = MaterialTheme.colorScheme.error)
        IdentityAction("Advanced Device Identity", enabled = !saving) { advanced = !advanced }
        if (advanced) {
            IdentityField("Serial Number", serial, !saving) { serial = it; error = null }
            IdentityField("Device ID 1", device1, !saving) { device1 = it; error = null }
            IdentityField("Device ID 2", device2, !saving) { device2 = it; error = null }
            IdentityField("Signature", signature, !saving) { signature = it; error = null }
        }
        Text("Saving requires reconnecting on the next channel open or provider refresh. Current playback and recordings will continue; Nuvix will not restart them. Your account's connection limit remains in effect.")
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            IdentityAction(if (saving) "Saving…" else "Save", enabled = !saving) {
                try {
                    StalkerIdentity.validate(draft())
                    error = null
                    if (macChanged) confirming = true else save()
                } catch (_: IllegalArgumentException) {
                    error = if (runCatching { StalkerIdentity.canonicalMac(mac) }.isFailure)
                        "Enter six colon-separated hexadecimal pairs for the portal MAC."
                    else "Custom identity values must contain 1–1024 characters without control characters."
                }
            }
            IdentityAction("Cancel", enabled = !saving, onClick = onCancel)
        }
    }
    if (confirming) AlertDialog(
        onDismissRequest = { confirming = false },
        title = { Text("Confirm portal MAC change") },
        text = { Text(StalkerIdentity.MAC_WARNING) },
        confirmButton = { IdentityAction("Confirm and Save") { confirming = false; save() } },
        dismissButton = { IdentityAction("Cancel") { confirming = false } },
    )
}

@Composable
private fun IdentityField(label: String, value: String?, enabled: Boolean, onValue: (String?) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, style = MaterialTheme.typography.titleMedium)
        IdentityAction("Automatic (Default)", enabled, "$label: Automatic (Default)", selected = value == null) { onValue(null) }
        IdentityAction("Custom — Provider Supplied", enabled, "$label: Custom — Provider Supplied", selected = value != null) {
            if (value == null) onValue("")
        }
        if (value != null) {
            OutlinedTextField(value = value, onValueChange = onValue, label = { Text("$label custom value") },
                singleLine = true, enabled = enabled, modifier = Modifier.fillMaxWidth().testTag("identity-$label"))
            IdentityAction("Restore Automatic", enabled, "$label: Restore Automatic") { onValue(null) }
        }
    }
}

/** A visible border follows D-pad focus; accessible labels distinguish identical field controls. */
@Composable
private fun IdentityAction(label: String, enabled: Boolean = true, accessibility: String = label,
    selected: Boolean? = null, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    OutlinedButton(onClick = onClick, enabled = enabled, interactionSource = interaction,
        border = BorderStroke(if (focused) 3.dp else 1.dp,
            if (focused || selected == true) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline),
        modifier = modifier.semantics {
            contentDescription = accessibility
            if (selected != null) this.selected = selected
        }) {
        Text(if (selected == true) "✓ $label" else label)
    }
}
