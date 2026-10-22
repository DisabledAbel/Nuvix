/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Nuvix modifications Copyright (C) 2026 Nuvix Contributors.
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.update

import android.Manifest
import android.app.Application
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.viewmodel.compose.viewModel
import com.disabledabel.nuvix.BuildConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Only user-initiated checks/downloads raise dialogs. Scheduled work remains unobtrusive. */
@Composable
fun UpdateGate(viewModel: UpdateViewModel = viewModel()) {
    val state by viewModel.state.collectAsState()
    when (val current = state) {
        UpdateUiState.Idle -> Unit
        UpdateUiState.Checking -> AlertDialog(
            onDismissRequest = viewModel::cancel,
            confirmButton = { TextButton(onClick = viewModel::cancel) { Text("Cancel") } },
            title = { Text("Checking for updates…") },
            text = { LinearProgressIndicator(Modifier.fillMaxWidth()) },
        )
        UpdateUiState.Current -> AlertDialog(
            onDismissRequest = viewModel::dismiss,
            confirmButton = { TextButton(onClick = viewModel::dismiss) { Text("Close") } },
            title = { Text("Nuvix is up to date") },
            text = { Text("Installed version ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}).") },
        )
        is UpdateUiState.Available -> AlertDialog(
            onDismissRequest = viewModel::dismiss,
            confirmButton = { TextButton(onClick = viewModel::install) { Text("Download and install") } },
            dismissButton = { TextButton(onClick = viewModel::dismiss) { Text("Later") } },
            title = { Text("Nuvix ${current.update.versionName} available") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    Text("Installed: ${BuildConfig.VERSION_NAME}. Update: ${(current.update.apkSizeBytes / 1048576f).toInt()} MB. Android will ask before installing.")
                    if (current.update.notes.isNotBlank()) Text(current.update.notes, Modifier.padding(top = 12.dp))
                }
            },
        )
        is UpdateUiState.Downloading -> AlertDialog(
            onDismissRequest = viewModel::cancel,
            confirmButton = { TextButton(onClick = viewModel::cancel) { Text("Cancel download") } },
            title = { Text("Downloading and verifying update…") },
            text = {
                Column {
                    LinearProgressIndicator(progress = { current.fraction }, modifier = Modifier.fillMaxWidth())
                    Text("${(current.fraction * 100).toInt()}%", Modifier.padding(top = 8.dp))
                }
            },
        )
        is UpdateUiState.PermissionRequired -> AlertDialog(
            onDismissRequest = viewModel::dismiss,
            confirmButton = { TextButton(onClick = viewModel::openPermission) { Text("Allow app installs") } },
            dismissButton = { TextButton(onClick = viewModel::install) { Text("Continue installation") } },
            title = { Text("Android install permission needed") },
            text = { Text("Allow Nuvix to install apps in Android settings, return here, then choose Continue installation. The downloaded APK has been verified.") },
        )
        is UpdateUiState.Installing -> AlertDialog(
            onDismissRequest = viewModel::dismiss,
            confirmButton = { TextButton(onClick = viewModel::dismiss) { Text("Close") } },
            dismissButton = { TextButton(onClick = viewModel::cancel) { Text("Cancel installation") } },
            title = { Text("Waiting for Android installer") },
            text = { Text("Approve or cancel in Android's installation screen. The update is complete only when Android reports success.") },
        )
        UpdateUiState.Completed -> AlertDialog(
            onDismissRequest = viewModel::dismiss,
            confirmButton = { TextButton(onClick = viewModel::dismiss) { Text("Close") } },
            title = { Text("Update installed") },
            text = { Text("Android confirmed the installation.") },
        )
        is UpdateUiState.Failed -> AlertDialog(
            onDismissRequest = viewModel::dismiss,
            confirmButton = {
                TextButton(onClick = { if (current.update == null) viewModel.checkNow() else viewModel.install() }) { Text("Retry") }
            },
            dismissButton = { TextButton(onClick = viewModel::dismiss) { Text("Close") } },
            title = { Text("Update could not complete") },
            text = { Text(current.message) },
        )
    }
}

/** Reusable on About/settings; all toggles can be operated with a remote or touch. */
@Composable
fun UpdateSettingsControls(viewModel: UpdateViewModel = viewModel()) {
    val context = LocalContext.current
    val prefs = remember(context) { UpdatePreferences(context) }
    var automatic by remember { mutableStateOf(prefs.automaticChecks) }
    var downloads by remember { mutableStateOf(prefs.backgroundDownloads) }
    var notifications by remember { mutableStateOf(prefs.notifications) }
    var canNotify by remember { mutableStateOf(UpdateScheduler.canNotify(context)) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(context, lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) canNotify = UpdateScheduler.canNotify(context)
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        canNotify = UpdateScheduler.canNotify(context)
    }
    val state by viewModel.state.collectAsState()
    val busy = state is UpdateUiState.Downloading || state is UpdateUiState.Installing || state == UpdateUiState.Checking
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Updates", style = MaterialTheme.typography.titleMedium)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Check automatically every 6 hours", Modifier.weight(1f))
            Switch(checked = automatic, onCheckedChange = {
                automatic = it; prefs.automaticChecks = it; UpdateScheduler.initialize(context)
            })
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Download automatically on unmetered networks", Modifier.weight(1f))
            Switch(checked = downloads, enabled = automatic, onCheckedChange = {
                downloads = it; prefs.backgroundDownloads = it; UpdateScheduler.initialize(context)
            })
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Notify when an update is available", Modifier.weight(1f))
            Switch(checked = notifications, onCheckedChange = { notifications = it; prefs.notifications = it })
        }
        if (notifications && !canNotify) {
            Text("Android notifications are disabled. Updates remain available through a manual check.", style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = {
                if (Build.VERSION.SDK_INT >= 33) permission.launch(Manifest.permission.POST_NOTIFICATIONS)
                else if (Build.VERSION.SDK_INT >= 26) context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                else context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    android.net.Uri.parse("package:${context.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }) { Text("Enable Android notifications") }
        }
        OutlinedButton(onClick = viewModel::checkNow, enabled = !busy) { Text("Check for updates now") }
        Text("Stable releases from ${UpdateChecker.REPO_SLUG}. Downloads never install automatically.", style = MaterialTheme.typography.bodySmall)
        prefs.lastFailure?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
    }
}

/** Foreground update-check, download and installation state shown by the shared prompt. */
sealed interface UpdateUiState {
    /** Update state with no foreground operation or prompt. */
    data object Idle : UpdateUiState
    /** Update state while stable release metadata is being checked. */
    data object Checking : UpdateUiState
    /** Update state indicating no eligible newer stable release was found. */
    data object Current : UpdateUiState
    /** Update state presenting an eligible release for explicit download. */
    data class Available(val update: UpdateChecker.Update) : UpdateUiState
    /** Update state containing the selected release and current download fraction. */
    data class Downloading(val update: UpdateChecker.Update, val fraction: Float) : UpdateUiState
    /** Update state awaiting the platform's install-source permission. */
    data class PermissionRequired(val update: UpdateChecker.Update) : UpdateUiState
    /** Update state tracking the release and submitted Android installer session. */
    data class Installing(val update: UpdateChecker.Update, val sessionId: Int) : UpdateUiState
    /** Update state after Android reports successful installation. */
    data object Completed : UpdateUiState
    /** Update failure with display-safe text and optional release context for retry. */
    data class Failed(val update: UpdateChecker.Update?, val message: String) : UpdateUiState
}

/** Shares update prompt requests and installer outcomes across application entry points. */
object UpdateHub {
    val state = MutableStateFlow<UpdateUiState>(UpdateUiState.Idle)
    // The gate and each settings destination have distinct ViewModel owners. Share the operation
    // as well as its state, so Cancel works from the gate and navigation cannot strand a download.
    internal val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    internal var operation: Job? = null
}

/** Coordinates foreground release checks, downloads and Android-approved installation. */
class UpdateViewModel(app: Application) : AndroidViewModel(app) {
    private val checker = UpdateChecker.forInstalledApplication(app)
    private val installer = ApkInstaller()
    private val prefs = UpdatePreferences(app)
    val state = UpdateHub.state.asStateFlow()

    /** Starts a user-initiated stable release check and publishes its result. */
    fun checkNow() {
        if (busy()) return
        UpdateHub.operation = UpdateHub.scope.launch {
            UpdateHub.state.value = UpdateUiState.Checking
            try {
                val update = checker.check()
                prefs.saveAvailable(update)
                prefs.lastCheckTime = System.currentTimeMillis()
                prefs.lastFailure = null
                UpdateHub.state.value = update?.let { UpdateUiState.Available(it) } ?: UpdateUiState.Current
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                val message = if (failure is UpdateValidationException) failure.message ?: "Release validation failed."
                    else "Could not check for updates. Check the connection and try again."
                UpdateHub.state.value = UpdateUiState.Failed(null, message)
            }
        }
    }

    /** Downloads, verifies and submits the selected release for Android installation approval. */
    fun install() {
        if (busy()) return
        val update = when (val current = UpdateHub.state.value) {
            is UpdateUiState.Available -> current.update
            is UpdateUiState.Failed -> current.update
            is UpdateUiState.PermissionRequired -> current.update
            else -> null
        } ?: return
        UpdateHub.operation = UpdateHub.scope.launch {
            UpdateHub.state.value = UpdateUiState.Downloading(update, 0f)
            try {
                val apk = installer.download(getApplication(), update) { fraction ->
                    UpdateHub.state.value = UpdateUiState.Downloading(update, fraction)
                }
                when (val result = installer.install(getApplication(), apk, update)) {
                    ApkInstaller.InstallResult.PermissionRequired -> UpdateHub.state.value = UpdateUiState.PermissionRequired(update)
                    is ApkInstaller.InstallResult.Submitted -> UpdateHub.state.value = UpdateUiState.Installing(update, result.sessionId)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                UpdateHub.state.value = UpdateUiState.Failed(update,
                    if (failure is UpdateValidationException) failure.message ?: "APK validation failed. No update was installed."
                    else "Could not download or submit the update. Check the connection, device storage and app install permission.")
            }
        }
    }

    /** Opens install-source settings for a release awaiting platform permission. */
    fun openPermission() {
        runCatching { installer.openInstallPermission(getApplication()) }.onFailure {
            val update = (UpdateHub.state.value as? UpdateUiState.PermissionRequired)?.update
            UpdateHub.state.value = UpdateUiState.Failed(update, "This device could not open app install settings. Enable the permission in Android settings.")
        }
    }
    /** Cancels the active foreground update operation. */
    fun cancel() {
        UpdateHub.operation?.cancel()
        val id = prefs.pendingSessionId()
        if (id >= 0) runCatching { getApplication<Application>().packageManager.packageInstaller.abandonSession(id) }
        prefs.clearPendingSession()
        UpdateHub.state.value = UpdateUiState.Idle
    }
    /** Dismisses the current update prompt when its operation permits it. */
    fun dismiss() { UpdateHub.state.value = UpdateUiState.Idle }
    /** Checks whether a foreground check, download or installation is in progress. */
    private fun busy(): Boolean = UpdateHub.state.value.let {
        it == UpdateUiState.Checking || it is UpdateUiState.Downloading || it is UpdateUiState.Installing
    }
}
