/* Nuvix modifications, Copyright (C) 2026 Nuvix Contributors. GPL-3.0-or-later. */
package app.opentv.launcher

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver

@Composable
fun LauncherSettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val controller = remember(context) { LauncherController(context) }
    val settings = remember(context) { LauncherSettingsStore(context) }
    val preferences by settings.preferences.collectAsState()
    var status by remember { mutableStateOf(controller.status()) }
    var message by remember { mutableStateOf<String?>(null) }
    val systemSelector = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        status = controller.status()
    }
    DisposableEffect(lifecycleOwner, settings) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) status = controller.status()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer); settings.close() }
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 32.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Launcher settings", style = MaterialTheme.typography.headlineMedium)
            Spacer(Modifier.weight(1f))
            LauncherAction("Done", onBack)
        }
        Text(
            when {
                status.isDefault -> "Nuvix is your default Home app."
                status.enabled -> "Launcher mode is enabled. Android has not selected Nuvix as the default Home app."
                else -> "Nuvix is running as a normal app. Launcher mode is off."
            },
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.primary,
        )
        status.defaultLabel?.let { Text("Current Home selection: $it") }
        Text(status.compatibilityMessage, color = MaterialTheme.colorScheme.onSurfaceVariant)
        message?.let { Text(it, color = MaterialTheme.colorScheme.error) }

        LauncherAction(
            if (status.enabled) "Disable launcher mode" else "Enable launcher mode",
            {
                runCatching { controller.setEnabled(!status.enabled) }
                    .onFailure { message = "Launcher mode could not be changed on this device." }
                    .onSuccess { message = null }
                status = controller.status()
            },
            Modifier.fillMaxWidth(),
        )
        Text("Enabling makes Nuvix available in Android's Home selector. It does not change the original launcher or select a default automatically.")
        LauncherAction(
            "Choose default Home app",
            {
                val intent = controller.defaultHomeRequest()
                if (intent == null) message = "The device provides no supported default-Home selector."
                else runCatching { systemSelector.launch(intent) }
                    .onFailure { message = "The device did not allow the Home selector to open. Normal app mode remains available." }
            },
            Modifier.fillMaxWidth(),
            enabled = status.enabled && status.canRequestDefault,
        )
        LauncherAction("Check device compatibility", { status = controller.status(); message = null }, Modifier.fillMaxWidth())

        Text("Startup destination", style = MaterialTheme.typography.titleLarge)
        Text("Applied when Nuvix starts or receives Home. Last watched channel opens the saved channel when it is still available; otherwise the Live TV screen opens.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        StartupDestination.entries.forEach { destination ->
            LauncherAction(
                if (preferences.startupDestination == destination) "✓ ${destination.title}" else destination.title,
                { settings.setStartupDestination(destination) },
                Modifier.fillMaxWidth(),
            )
        }

        Text("Restore your original launcher", style = MaterialTheme.typography.titleLarge)
        Text("1. Open Device settings and locate Apps → Default apps → Home app (wording varies by device).\n2. Select the device's original Home app.\n3. Return to Nuvix and disable launcher mode.\n\nIf your device has no Home selector, disable launcher mode and press Home. Android chooses an available system launcher. Nuvix never disables or uninstalls that launcher.")
        LauncherAction("Open Home settings", {
            val intent = controller.homeSettingsIntent()
            if (intent == null || runCatching { context.startActivity(intent) }.isFailure) message = "Home settings are unavailable. Use Device settings to choose your original launcher."
        }, Modifier.fillMaxWidth())
        LauncherAction("Open device settings", {
            val intent = controller.deviceSettingsIntent()
            if (intent == null || runCatching { context.startActivity(intent) }.isFailure) message = "Android device settings could not be opened. Use your remote's device settings shortcut."
        }, Modifier.fillMaxWidth())
    }
}
