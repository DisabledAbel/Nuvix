package app.opentv.update

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.disabledabel.nuvix.BuildConfig
import kotlinx.coroutines.CancellationException
import java.io.IOException
import java.util.concurrent.TimeUnit

object UpdateScheduler {
    private const val WORK_NAME = "nuvix_stable_update_check"
    private const val EXTRA_SHOW_UPDATE = "nuvix.show_update"
    private const val CHANNEL = "nuvix_updates"
    private const val NOTIFICATION_ID = 405

    fun initialize(context: Context) {
        val prefs = UpdatePreferences(context)
        val manager = WorkManager.getInstance(context)
        if (!prefs.automaticChecks) {
            manager.cancelUniqueWork(WORK_NAME)
            return
        }
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(if (prefs.backgroundDownloads) NetworkType.UNMETERED else NetworkType.CONNECTED)
            .setRequiresBatteryNotLow(true)
            .build()
        val request = PeriodicWorkRequestBuilder<UpdateWorker>(6, TimeUnit.HOURS)
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        manager.enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.UPDATE, request)
    }

    /** Only an explicit tap of our notification opens the shared update prompt. */
    fun handleIntent(context: Context, intent: Intent?) {
        if (intent?.getBooleanExtra(EXTRA_SHOW_UPDATE, false) != true) return
        intent.removeExtra(EXTRA_SHOW_UPDATE)
        UpdatePreferences(context).available()?.takeIf { it.versionCode > BuildConfig.VERSION_CODE }?.let {
            UpdateHub.state.value = UpdateUiState.Available(it)
        }
    }

    fun canNotify(context: Context): Boolean =
        (Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) &&
            NotificationManagerCompat.from(context).areNotificationsEnabled()

    @SuppressLint("MissingPermission") // canNotify checks both runtime permission and app settings below.
    fun notifyAvailable(context: Context, update: UpdateChecker.Update, downloaded: Boolean) {
        if (!UpdatePreferences(context).notifications || !canNotify(context)) return
        if (Build.VERSION.SDK_INT >= 26) {
            context.getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL, "Nuvix updates", NotificationManager.IMPORTANCE_LOW),
            )
        }
        val intent = context.packageManager.getLaunchIntentForPackage(context.packageName) ?: return
        intent.putExtra(EXTRA_SHOW_UPDATE, true)
        val content = PendingIntent.getActivity(context, NOTIFICATION_ID, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle("Nuvix ${update.versionName} ${if (downloaded) "ready to install" else "available"}")
            .setContentText("Open to review the update. Android will ask before installation.")
            .setContentIntent(content)
            .setOnlyAlertOnce(true)
            .setAutoCancel(true)
            .build()
        // Permission may be revoked between the check and notify.
        runCatching { NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification) }
    }
}

/** Background checks never stop, replace or otherwise interact with a playback session. */
class UpdateWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val prefs = UpdatePreferences(applicationContext)
        if (!prefs.automaticChecks) return Result.success()
        return try {
            val update = UpdateChecker(UpdateNetwork.client, BuildConfig.VERSION_CODE, BuildConfig.APPLICATION_ID).check()
            prefs.lastCheckTime = System.currentTimeMillis()
            prefs.lastFailure = null
            val previous = prefs.available()
            prefs.saveAvailable(update)
            if (update == null) return Result.success()
            var downloaded = false
            if (prefs.backgroundDownloads) {
                ApkInstaller().download(applicationContext, update)
                downloaded = true
            }
            if (previous?.versionCode != update.versionCode || downloaded) {
                UpdateScheduler.notifyAvailable(applicationContext, update, downloaded)
            }
            Result.success()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: IOException) {
            prefs.lastFailure = "Update server could not be reached. Try a manual check later."
            if (runAttemptCount < 3) Result.retry() else Result.failure()
        } catch (failure: Exception) {
            prefs.lastFailure = "Release validation failed. No update was installed."
            Result.failure()
        }
    }
}
