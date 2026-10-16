/* Nuvix modifications Copyright (C) 2026 Nuvix Contributors. GPL-3.0-or-later. */
package app.opentv.cloud

import android.content.Context
import android.content.SharedPreferences
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
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.channels.Channel
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
    val categories: Set<AdvancedSyncCategory> = AdvancedSyncCategory.DEFAULTS, val pendingChanges: Int = 0,
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
    private val captureScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val captureSignals = Channel<Unit>(Channel.CONFLATED)
    private val reconciliationRequested = AtomicBoolean(false)
    private val preferenceData by lazy { AdvancedPreferenceData(this.context) }
    private val channelData by lazy { AdvancedChannelData(graph) }
    private val groupData by lazy { AdvancedGroupData(graph.database) }
    private val journal by lazy {
        AdvancedCloudJournal({ settings.vault.read(AdvancedCloudJournal.VAULT_NAME) },
            { settings.vault.write(AdvancedCloudJournal.VAULT_NAME, it) })
    }
    // Keep strong references: Android only retains SharedPreferences listeners weakly.
    private val preferenceListeners = mutableListOf<Pair<SharedPreferences, SharedPreferences.OnSharedPreferenceChangeListener>>()

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
                    manager.localChanged()
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
        if (enabled) { captureLocal(); observeLocalChanges(); schedule() } else cancelWork()
        updateState(if (enabled) "Cloud sync enabled. Your local data remains available offline." else "Cloud sync paused. Local data is unchanged.")
    }
    suspend fun setCategory(category: AdvancedSyncCategory, enabled: Boolean) = action {
        require(settings.session() != null) { "Sign in before choosing shared data." }
        settings.categories = if (enabled) settings.categories + category else settings.categories - category
        if (settings.enabled) { captureLocal(); enqueue() }
        updateState(if (enabled) "Category enabled. Only the described data will be shared."
            else "Category paused. Local and existing cloud data are retained.")
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
            if (settings.categories.isEmpty()) { updateState("No sync categories selected."); return@withContext true }
            mutableState.value = mutableState.value.copy(busy = true, message = "Syncing…")
            try {
                val api = api()
                var session = settings.session() ?: error("Sign in to enable cloud sync.")
                if (session.expiresAtMillis <= System.currentTimeMillis() + 60_000) {
                    val refreshed = api.refresh(session.refreshToken)
                    require(refreshed.userId == session.userId) { "Cloud account changed unexpectedly." }
                    session = refreshed
                    // Persist the rotated refresh token before making another request.
                    settings.saveSession(session)
                }
                // Capture before requests; both journals remain durable through any failure.
                captureLocal()
                var changed = 0
                if (AdvancedSyncCategory.FAVOURITES_PROGRESS in settings.categories) changed += syncLegacy(api, session)
                if (settings.categories.any { it != AdvancedSyncCategory.FAVOURITES_PROGRESS }) {
                    changed += AdvancedSyncEngine(journal, ::advancedSnapshot, ::applyAdvancedLocally,
                        { selected -> api.pullAdvanced(session.accessToken, session.userId, selected) },
                        { records -> api.pushAdvanced(session.accessToken, records) })
                        .sync(session.userId, settings.deviceId, settings.categories)
                }
                settings.lastSynced = System.currentTimeMillis()
                updateState("Sync complete. $changed local items updated.")
                if (mutableState.value.pendingChanges > 0) {
                    mutableState.value = mutableState.value.copy(message =
                        "Sync complete. Some edits remain pending; check disabled categories and the device clock.")
                }
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

    private suspend fun syncLegacy(api: SupabaseCloudApi, session: CloudSession): Int {
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
        val merged = local.apply(remote, locallyDismissed, syncFavourites = AdvancedSyncCategory.FAVOURITE_REMOVALS !in settings.categories) { identity, timestamp ->
            favouriteTimes.putIfAbsent(identity, timestamp)
        }
        settings.vault.write("favourites", settings.json.encodeToString(timesSerializer, favouriteTimes))
        // If a crash occurs before clearing, the immutable queue is safely replayed.
        val combined = (acknowledged.values + sendable + remote).groupBy { it.identity() }
            .values.map { values -> values.reduce(::newestRecord) }
        saveRecords("acknowledged", combined)
        if (waiting.isEmpty()) settings.vault.remove("outbox") else saveRecords("outbox", waiting)
        return merged
    }

    private suspend fun advancedSnapshot(): List<AdvancedCloudRecord> {
        val now = System.currentTimeMillis()
        val enabled = settings.categories
        return buildList {
            if (enabled.any { it in setOf(AdvancedSyncCategory.APP_FAVOURITES, AdvancedSyncCategory.HOME_PREFERENCES, AdvancedSyncCategory.COMPATIBLE_SETTINGS) })
                addAll(preferenceData.snapshot(settings.deviceId, now).filter { it.category in enabled })
            if (AdvancedSyncCategory.CHANNEL_ORGANIZATION in enabled || AdvancedSyncCategory.FAVOURITE_REMOVALS in enabled)
                addAll(channelData.snapshot(settings.deviceId, now, includeFavourites = AdvancedSyncCategory.FAVOURITE_REMOVALS in enabled).filter { it.category in enabled })
            if (AdvancedSyncCategory.CUSTOM_GROUPS in enabled) addAll(groupData.snapshot(settings.deviceId, now))
        }
    }

    private suspend fun captureAdvanced(owner: String): Int {
        journal.bindOwner(owner)
        return journal.capture(owner, advancedSnapshot(), settings.categories, settings.deviceId, System.currentTimeMillis())
    }

    /** This is independent of network availability, so process death does not discard offline edits. */
    private suspend fun captureLocal(): Boolean {
        if (!settings.enabled) return false
        val owner = settings.session()?.userId ?: return false
        var changed = false
        if (settings.categories.any { it != AdvancedSyncCategory.FAVOURITES_PROGRESS }) changed = captureAdvanced(owner) > 0
        if (AdvancedSyncCategory.FAVOURITES_PROGRESS in settings.categories) {
            val times = settings.vault.read("favourites")?.let {
                settings.json.decodeFromString(timesSerializer, it).toMutableMap()
            } ?: mutableMapOf()
            val snapshot = CloudLocalData(graph).gather(settings.deviceId, times)
            settings.vault.write("favourites", settings.json.encodeToString(timesSerializer, times))
            val previous = readRecords("outbox")
            val pending = buildCloudOutbox(readRecords("acknowledged"), previous, snapshot.records)
            if (pending != previous) { saveRecords("outbox", pending); changed = true }
        }
        return changed
    }

    private suspend fun applyAdvancedLocally(winners: List<AdvancedCloudRecord>, expected: List<AdvancedCloudRecord>): Int {
        var changed = groupData.apply(winners, expected)
        changed += channelData.apply(winners, expected)
        // UI preference setters run on Main, so a local UI edit cannot interleave between
        // this comparison and the synchronous remote setter/durable write.
        changed += withContext(Dispatchers.Main.immediate) {
            val current = preferenceData.snapshot(settings.deviceId, System.currentTimeMillis()).associateBy { it.identity() }
            val before = expected.associateBy { it.identity() }
            winners.count { record ->
                preferenceData.applyIfUnchanged(record, before[record.identity()], current[record.identity()])
            }
        }
        return changed
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
            message ?: mutableState.value.message, settings.lastSynced, settings.categories,
            settings.session()?.userId?.let { owner ->
                (if (settings.vault.read(AdvancedCloudJournal.VAULT_NAME) != null) journal.pendingCount(owner) else 0)
            }.orZero() + readRecords("outbox").size)
    }
    private fun Int?.orZero(): Int = this ?: 0
    private fun errorMessage(error: Exception): String = when (error) {
        is CloudHttpException -> error.message ?: "Cloud setup needs attention."
        is kotlinx.serialization.SerializationException -> "Cloud data is invalid or uses an unsupported format. Check the supplied migration."
        is IllegalArgumentException, is IllegalStateException -> "Cloud setup or encrypted data needs attention. Check the account, device clock and supplied migration."
        is java.security.GeneralSecurityException -> "Encrypted cloud storage is unavailable. Remove cloud setup on this TV, then configure it again."
        else -> "Cloud sync could not connect. Local changes are retained and will retry."
    }
    private fun observeLocalChanges() {
        if (observing) return
        observing = true
        graph.database.invalidationTracker.addObserver(object : InvalidationTracker.Observer(
            "channels", "movies", "series", "playback_positions", "custom_channel_groups", "sources",
        ) {
            override fun onInvalidated(tables: Set<String>) {
                // Catalog completion updates its source; reconcile retained references promptly
                // even when no new user mutation was authored by that import.
                if ("sources" in tables) reconciliationRequested.set(true)
                localChanged()
            }
        })
        val keys = mapOf(
            "nuvix_launcher" to setOf("favorite_packages", "startup_destination"),
            "nuvix_home" to (AdvancedCloudRecord.HOME_SHELVES + setOf("shelf_order_v1", "presentation_v1")),
            "opentv_settings" to setOf("theme_mode", "channel_layout", "subtitles_enabled",
                "guide_layout", "guide_density", "guide_default_filter", "guide_sort",
                "guide_show_artwork", "guide_show_progress", "guide_remember_position"),
        )
        for ((name, allowed) in keys) {
            val preferences = context.getSharedPreferences(name, Context.MODE_PRIVATE)
            val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
                if (key in allowed || (name == "nuvix_home" && key != null && app.opentv.ui.home.HomeShelfOrder.isGroup(key))) localChanged()
            }
            preferences.registerOnSharedPreferenceChangeListener(listener)
            preferenceListeners += preferences to listener
        }
    }
    // A burst of Room invalidations or preference edits needs one final snapshot, not an
    // unbounded coroutine backlog. Capturing does not depend on network availability.
    init {
        captureScope.launch {
            for (signal in captureSignals) mutex.withLock {
                if (!settings.enabled) return@withLock
                try {
                    val changed = captureLocal()
                    updateState()
                    val reconcile = reconciliationRequested.getAndSet(false)
                    if (changed || reconcile) enqueue()
                } catch (error: Exception) {
                    if (error is kotlinx.coroutines.CancellationException) throw error
                    mutableState.value = mutableState.value.copy(message = errorMessage(error))
                }
            }
        }
    }
    private fun localChanged() {
        if (settings.enabled) captureSignals.trySend(Unit)
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
