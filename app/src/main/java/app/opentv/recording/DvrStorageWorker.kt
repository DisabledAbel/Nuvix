/* Copyright (C) 2026 Nuvix Contributors. GPL-3.0-or-later. */
package app.opentv.recording

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import app.opentv.core.ServiceLocator
import java.util.concurrent.TimeUnit

/** Best-effort maintenance. Disabled destinations perform no storage I/O; no exact alarm/capture changes. */
class DvrStorageWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        return try {
            ServiceLocator.get(applicationContext).recordingEngine.cleanupStorage()
            Result.success()
        } catch (cancel: kotlinx.coroutines.CancellationException) { throw cancel }
        catch (_: Exception) { Result.failure() } // Never log backend exception text.
    }

    companion object {
        fun schedule(context: Context) {
            WorkManager.getInstance(context).enqueueUniquePeriodicWork("dvr-storage-maintenance",
                ExistingPeriodicWorkPolicy.KEEP, PeriodicWorkRequestBuilder<DvrStorageWorker>(1, TimeUnit.HOURS).build())
        }
    }
}
