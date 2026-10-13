/* Nuvix modifications Copyright (C) 2026 Nuvix Contributors. GPL-3.0-or-later. */
package app.opentv.cloud

import android.content.Context
import androidx.room.InvalidationTracker
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import app.opentv.core.ServiceLocator
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer

data class CloudUiState(
    val enabled: Boolean = false, val configured: Boolean = false, val email: String? = null,
    val projectUrl: String = "", val publishableKey: String = "", val busy: Boolean = false,
    val message: String = "Cloud sync is off. Everything works locally.", val lastSynced: Long = 0,
)

/** Optional account, separate from the existing local/NAS sync engine and provider settings. */
class CloudSync private constructor(context: Context) {
    private val context = context.applicationContext
    private val graph = ServiceLocator.get(this.context)
    private val settings = CloudSettings(this.context)
    private val mutex = Mutex()
    private val mutableState = MutableStateFlow(CloudUiState())
    val state: StateFlow<CloudUiState> = mutableState
    private val recordsSerializer = ListSerializer(CloudRecord.serializer())
    private val timesSerializer = MapSerializer(String.serializer(), Long.serializer())
    private var observing = false

    companion object {
        @Volatile private var instance: CloudSync? = null
        fun get(context: Context): CloudSync = instance ?: synchronized(this) {
            instance ?: CloudSync(context).also { instance = it }
        }

        /** Call from Application.onCreate. Disabled installs schedule no work and make no requests. */
        fun initialize(context: Context) {
            // Cloud storage/WorkManager failure must never prevent ordinary local playback.
            runCatching {
                val manager = get(context)
                if (manager.settings.enabled) {
                    manager.observeLocalChanges()
                    manager.schedule()
                }
            }
        }
    }

    suspend fun reload() = action { updateState() }
    suspend fun configure(url: String, key: String) = action {
        settings.configure(url, key)
        if (!settings.enabled) cancelWork()
        updateState("Project saved. Sign in, then enable cloud sync.")
    }
    suspend fun requestCode(email: String) = action {
        api().sendEmailCode(email.trim())
        updateState("Code requested. Check your email and enter the code on this TV.")
    }
    suspend fun verifyCode(email: String, code: String) = action {
        val session = api().verifyEmailCode(email.trim(), code.trim())
        if (settings.session()?.userId != session.userId) settings.clearSession()
        settings.saveSession(session)
        updateState("Signed in. Cloud sync stays off until you enable it.")
    }
    suspend fun setEnabled(enabled: Boolean) = action {
        if (enabled) require(settings.config() != null && settings.session() != null) { "Configure a project and sign in first." }
        settings.enabled = enabled
        if (enabled) { observeLocalChanges(); schedule() } else cancelWork()
        updateState(if (enabled) "Cloud sync enabled. Your local data remains available offline." else "Cloud sync paused. Local data is unchanged.")
    }
    suspend fun signOut() = action {
        val session = settings.session()
        val config = settings.config()
        settings.clearSession()
        cancelWork()
        // Local token removal always succeeds first; a network failure cannot keep this TV signed in.
        if (session != null && config != null) runCatching { SupabaseCloudApi(config).signOut(session.accessToken) }
        updateState("Signed out on this TV. Cloud records remain in your project.")
    }
    suspend fun reset() = action {
        settings.reset(); cancelWork(); updateState("Cloud setup removed from this TV. Local data is unchanged.")
    }

    /** Returns false for retryable errors; true means success, disabled, or a setup/auth error. */
    suspend fun syncNow(): Boolean = mutex.withLock {
        withContext(Dispatchers.IO) {
            if (!settings.enabled) { updateState("Cloud sync is paused."); return@withContext true }
            mutableState.value = mutableState.value.copy(busy = true, message = "Syncing…")
            try {
                val api = api()
                var session = settings.session() ?: error("Sign in to enable cloud sync.")
                if (session.expiresAtMillis <= System.currentTimeMillis() + 60_000) {
                    session = api.refresh(session.refreshToken)
                    // Persist the rotated refresh token before making another request.
                    settings.saveSession(session)
                }
                val local = CloudLocalData(graph)
                val favouriteTimes = settings.vault.read("favourites")?.let {
                    settings.json.decodeFromString(timesSerializer, it).toMutableMap()
                } ?: mutableMapOf()
                val snapshot = local.gather(settings.deviceId, favouriteTimes)
                val gathered = snapshot.records
                settings.vault.write("favourites", settings.json.encodeToString(timesSerializer, favouriteTimes))
                val acknowledged = readRecords("acknowledged").associateBy { it.identity() }
                val pending = buildCloudOutbox(acknowledged.values, readRecords("outbox"), gathered)
                saveRecords("outbox", pending)
                // Upload queued edits before pull. Atomic server-side comparison prevents regression.
                val sendable = pending.filter { it.isSafe(System.currentTimeMillis()) }
                val waiting = pending - sendable.toSet()
                api.push(session.accessToken, sendable)
                val remote = api.pull(session.accessToken, session.userId).filter { it.isSafe(System.currentTimeMillis()) }
                val activeFavourites = gathered.filter { it.kind.endsWith("_favourite") }
                    .map { "${it.kind}:${it.recordKey}" }.toSet()
                val locallyDismissed = locallyDismissedFavourites(favouriteTimes.keys,
                    snapshot.existingFavouriteIdentities, activeFavourites)
                val merged = local.apply(remote, locallyDismissed) { identity, timestamp ->
                    favouriteTimes.putIfAbsent(identity, timestamp)
                }
                settings.vault.write("favourites", settings.json.encodeToString(timesSerializer, favouriteTimes))
                // If a crash occurs before clearing, the immutable queue is safely replayed.
                val combined = (acknowledged.values + sendable + remote).groupBy { it.identity() }
                    .values.map { values -> values.reduce(::newestRecord) }
                saveRecords("acknowledged", combined)
                if (waiting.isEmpty()) settings.vault.remove("outbox") else saveRecords("outbox", waiting)
                settings.lastSynced = System.currentTimeMillis()
                updateState(if (waiting.isEmpty()) "Sync complete. $merged local items updated."
                    else "Synced $merged local items. Some pending items need the device clock checked.")
                true
            } catch (error: Exception) {
                if (error is kotlinx.coroutines.CancellationException) throw error
                mutableState.value = mutableState.value.copy(message = errorMessage(error))
                error is IllegalArgumentException || error is IllegalStateException ||
                    (error is CloudHttpException && error.status != 429 && error.status < 500)
            } finally {
                mutableState.value = mutableState.value.copy(busy = false)
            }
        }
    }

    private fun readRecords(name: String): List<CloudRecord> = settings.vault.read(name)?.let {
        settings.json.decodeFromString(recordsSerializer, it)
    }.orEmpty()
    private fun saveRecords(name: String, records: List<CloudRecord>) =
        settings.vault.write(name, settings.json.encodeToString(recordsSerializer, records))
    private fun api() = SupabaseCloudApi(settings.config() ?: error("Save your Supabase project URL and publishable key first."))

    private suspend fun action(block: suspend () -> Unit) = mutex.withLock {
        withContext(Dispatchers.IO) {
            mutableState.value = mutableState.value.copy(busy = true)
            try { block() } catch (error: Exception) {
                if (error is kotlinx.coroutines.CancellationException) throw error
                mutableState.value = mutableState.value.copy(message = errorMessage(error))
            } finally { mutableState.value = mutableState.value.copy(busy = false) }
        }
    }
    private fun updateState(message: String? = null) {
        val config = settings.config()
        val session = settings.session()
        mutableState.value = CloudUiState(settings.enabled, config != null, session?.email,
            config?.url.orEmpty(), config?.publishableKey.orEmpty(), mutableState.value.busy,
            message ?: mutableState.value.message, settings.lastSynced)
    }
    private fun errorMessage(error: Exception): String = when (error) {
        is CloudHttpException, is IllegalArgumentException, is IllegalStateException -> error.message ?: "Cloud setup needs attention."
        is java.security.GeneralSecurityException -> "Encrypted cloud storage is unavailable. Remove cloud setup on this TV, then configure it again."
        else -> "Cloud sync could not connect. Local changes are retained and will retry."
    }
    private fun observeLocalChanges() {
        if (observing) return
        observing = true
        graph.database.invalidationTracker.addObserver(object : InvalidationTracker.Observer("channels", "movies", "series", "playback_positions") {
            override fun onInvalidated(tables: Set<String>) { if (settings.enabled) enqueue() }
        })
    }
    private fun schedule() {
        val work = PeriodicWorkRequestBuilder<CloudSyncWorker>(15, TimeUnit.MINUTES)
            .setConstraints(networkConstraints()).setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS).build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork("nuvix_cloud_periodic", ExistingPeriodicWorkPolicy.KEEP, work)
        enqueue()
    }
    private fun enqueue() {
        val work = OneTimeWorkRequestBuilder<CloudSyncWorker>().setConstraints(networkConstraints())
            .setInitialDelay(5, TimeUnit.SECONDS).setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS).build()
        WorkManager.getInstance(context).enqueueUniqueWork("nuvix_cloud_pending", ExistingWorkPolicy.KEEP, work)
    }
    private fun cancelWork() {
        WorkManager.getInstance(context).cancelUniqueWork("nuvix_cloud_periodic")
        WorkManager.getInstance(context).cancelUniqueWork("nuvix_cloud_pending")
    }
    private fun networkConstraints() = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
}

class CloudSyncWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result = if (CloudSync.get(applicationContext).syncNow()) Result.success() else Result.retry()
}
