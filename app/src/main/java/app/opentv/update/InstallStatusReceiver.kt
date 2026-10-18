package app.opentv.update

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller

/** Explicit, non-exported target for the PackageInstaller session's PendingIntent callback. */
class InstallStatusReceiver : BroadcastReceiver() {
    /** Handles PackageInstaller callbacks and forwards any required platform confirmation action. */
    @Suppress("DEPRECATION")
    override fun onReceive(context: Context, intent: Intent) {
        val prefs = UpdatePreferences(context)
        val sessionId = intent.getIntExtra(PackageInstaller.EXTRA_SESSION_ID, -1)
        if (sessionId < 0 || sessionId != prefs.pendingSessionId()) return
        val update = prefs.pendingUpdate()
        when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirmation = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                if (confirmation == null) {
                    fail(context, update, "Android did not provide an installation confirmation screen.")
                    return
                }
                try {
                    context.startActivity(confirmation.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                } catch (_: Exception) {
                    fail(context, update, "Android could not open its installer. Try again from the update screen.")
                }
            }
            PackageInstaller.STATUS_SUCCESS -> {
                prefs.clearPendingSession()
                prefs.saveAvailable(null)
                UpdateHub.state.value = UpdateUiState.Completed
            }
            PackageInstaller.STATUS_FAILURE_ABORTED -> fail(context, update, "Installation was cancelled. The current version is unchanged.")
            PackageInstaller.STATUS_FAILURE_BLOCKED -> fail(context, update, "Android blocked installation. Check app install permissions or device policy.")
            PackageInstaller.STATUS_FAILURE_CONFLICT -> fail(context, update, "Android rejected the package or signing certificate. Keep the installed app and contact the release maintainer.")
            PackageInstaller.STATUS_FAILURE_STORAGE -> fail(context, update, "Not enough device storage to install the update.")
            else -> fail(context, update, "Android could not install the update. The current version is unchanged.")
        }
    }

    /** Publishes an installation failure and clears pending installation session state. */
    private fun fail(context: Context, update: UpdateChecker.Update?, message: String) {
        val prefs = UpdatePreferences(context)
        val id = prefs.pendingSessionId()
        if (id >= 0) runCatching { context.packageManager.packageInstaller.abandonSession(id) }
        prefs.clearPendingSession()
        prefs.lastFailure = message
        UpdateHub.state.value = UpdateUiState.Failed(update, message)
    }
}
