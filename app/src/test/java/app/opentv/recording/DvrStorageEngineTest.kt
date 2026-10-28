/* Copyright (C) 2026 Nuvix Contributors. GPL-3.0-or-later. */
package app.opentv.recording

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.opentv.connections.AccountConnectionManager
import app.opentv.core.AppSettings
import app.opentv.data.db.OpenTvDatabase
import app.opentv.data.model.*
import app.opentv.data.remote.*
import app.opentv.data.repo.*
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28])
class DvrStorageEngineTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val db = Room.inMemoryDatabaseBuilder(context, OpenTvDatabase::class.java).allowMainThreadQueries().build()
    private val repo = RecordingRepository(db.recordings(), db.seriesRules(), db.recordingEvents())
    private val manager = AccountConnectionManager.createForTest(context)
    private val settings = AppSettings::class.java.getDeclaredConstructor(Context::class.java).apply { isAccessible = true }.newInstance(context)
    private val store = DvrStorageStore(context)
    private val now = System.currentTimeMillis()
    private val destination get() = RecordingDestinations.configured(context, settings).first()
    private var remove: (Recording) -> Boolean = { RecordingStorage.delete(context, settings, it.filePath) }
    private var probe: (RecordingDestination) -> StorageCapacity = { StorageCapacity(StorageAvailability.AVAILABLE, now, 0, 1000) }
    private lateinit var engine: RecordingEngine
    private fun row(id: Long = 1) = Recording(id = id, channelId = 1, sourceId = 1, channelName = "Synthetic", title = "Synthetic recording",
        filePath = File(RecordingStorage.internalDir(context), "synthetic-$id.ts").absolutePath,
        streamUrl = "https://private.invalid/secret", status = RecordingStatus.COMPLETED,
        startedAtMillis = now - 40 * 86_400_000L - 3_600_000, endedAtMillis = now - 40 * 86_400_000L,
        sizeBytes = 100, lockPadding = true)
    @Before fun setup() = runBlocking {
        context.getSharedPreferences("dvr_storage", Context.MODE_PRIVATE).edit().clear().commit()
        val http = OkHttpClient.Builder().addInterceptor { error("No provider traffic is permitted") }.build()
        val api = XtreamApi(http)
        engine = RecordingEngine(context, repo, SourceRepository(db.sources(), api, StalkerApi(http)), settings,
            db.channels(), EpgRepository(db.programmes(), db.epgFeeds(), db.epgAliases(), db.channels(), db.sources(), api, http, db, context.cacheDir),
            durationConnections = manager, durationClock = { now }, durationChanged = {},
            storageDelete = { remove(it) }, storageCapacity = { probe(it) })
        db.recordings().insert(row())
        File(row().filePath).writeBytes(ByteArray(100))
        repo.event(1, "COMPLETED", "Prior history", now - 10_000)
    }
    @After fun close() { RecordingLiveState.markInactive(1); db.close() }

    @Test fun successDeletesOnlyReviewedFileAndAtomicallyRetainsHistoryAndPaddingChoice() = runBlocking {
        val before = manager.snapshots.value
        assertEquals(StorageDeleteResult.DELETED, engine.deleteStoredRecording(row()))
        assertFalse(File(row().filePath).exists())
        val deleted = repo.byId(1)!!
        assertEquals(RecordingStatus.DELETED, deleted.status); assertEquals(0, deleted.sizeBytes)
        assertTrue(deleted.lockPadding); assertFalse(deleted.protectedRecording)
        assertEquals(listOf("COMPLETED", "STORAGE_DELETED"), db.recordingEvents().forRecording(1).map { it.kind })
        assertEquals(before, manager.snapshots.value)
        assertEquals(StorageDeleteResult.CHANGED, engine.deleteStoredRecording(row()))
    }
    @Test fun failedOrUncertainDeletionPreservesCompleteRowAndHistory() = runBlocking {
        for (operation in listOf<(Recording) -> Boolean>({ false }, { throw java.io.IOException("private-path secret") })) {
            remove = operation
            assertEquals(StorageDeleteResult.STORAGE_ERROR, engine.deleteStoredRecording(row()))
            assertEquals(row(), repo.byId(1)); assertTrue(File(row().filePath).exists())
            assertEquals(1, db.recordingEvents().forRecording(1).size)
        }
        assertFalse(StorageDeleteResult.STORAGE_ERROR.explanation.contains("secret"))
        assertFalse(RecordingStorage.delete(context, settings, "/missing-private-directory/secret.ts"))
        assertFalse(RecordingStorage.delete(context, settings, "usb://not-created.ts"))
    }
    @Test fun freshProtectionActiveStateAndIndependentLiveRegistryRejectStaleReview() = runBlocking {
        for (changed in listOf(row().copy(protectedRecording = true), row().copy(status = RecordingStatus.PREPARING),
            row().copy(status = RecordingStatus.RECORDING), row().copy(status = RecordingStatus.FINALIZING), row().copy(filePath = "/other.ts"))) {
            repo.update(changed)
            assertEquals(StorageDeleteResult.CHANGED, engine.deleteStoredRecording(row()))
            assertTrue(File(row().filePath).exists())
        }
        repo.update(row()); RecordingLiveState.markActive(1, row().filePath)
        assertEquals(StorageDeleteResult.CHANGED, engine.deleteStoredRecording(row()))
    }
    @Test fun automaticDeletionRequiresDestinationOptInReceiptFreshCapacityAndRetention() = runBlocking {
        assertEquals(StorageDeleteResult.CHANGED, engine.deleteStoredRecording(row(), destination))
        store.save(destination.id, DvrStoragePolicy(cleanupEnabled = true))
        assertEquals(StorageDeleteResult.CHANGED, engine.deleteStoredRecording(row(), destination))
        store.created(1, row().filePath, destination.id)
        probe = { StorageCapacity(StorageAvailability.DISCONNECTED, now) }
        assertEquals(StorageDeleteResult.CHANGED, engine.deleteStoredRecording(row(), destination))
        probe = { StorageCapacity(StorageAvailability.AVAILABLE, now - 60_001, 0, 1000) }
        assertEquals(StorageDeleteResult.CHANGED, engine.deleteStoredRecording(row(), destination))
        probe = { StorageCapacity(StorageAvailability.AVAILABLE, now, 0, 1000) }
        assertEquals(StorageDeleteResult.DELETED, engine.deleteStoredRecording(row(), destination))
        assertEquals("STORAGE_CLEANUP", db.recordingEvents().forRecording(1).last().kind)
        assertFalse(store.owned(1, row().filePath, destination.id))
    }
    @Test fun eligibilityIsRecheckedAfterSlowCapacityLookupAndConcurrentOptOut() = runBlocking {
        store.save(destination.id, DvrStoragePolicy(cleanupEnabled = true))
        store.created(1, row().filePath, destination.id)
        probe = {
            runBlocking { repo.setProtection(1, true, true) }
            StorageCapacity(StorageAvailability.AVAILABLE, now, 0, 1000)
        }
        assertEquals(StorageDeleteResult.CHANGED, engine.deleteStoredRecording(row(), destination))
        assertTrue(File(row().filePath).exists())
        repo.update(row())
        probe = {
            store.save(destination.id, DvrStoragePolicy())
            StorageCapacity(StorageAvailability.AVAILABLE, now, 0, 1000)
        }
        assertEquals(StorageDeleteResult.CHANGED, engine.deleteStoredRecording(row(), destination))
    }
    @Test fun slowStorageDeletionDoesNotTakeGlobalRecordingAdmissionOrProtectionLocks() = runBlocking {
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        remove = { entered.countDown(); release.await(5, TimeUnit.SECONDS); false }
        val deletion = async(Dispatchers.IO) { engine.deleteStoredRecording(row()) }
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        repo.insert(row(65))
        try {
            assertTrue(withTimeout(3000) { engine.updatePreferences(65, 2, 5, true, false) })
        } finally { release.countDown() }
        assertEquals(StorageDeleteResult.STORAGE_ERROR, deletion.await())
    }
    @Test fun concurrentMutationsOfTheSameRecordingRemainSerialized() = runBlocking {
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val calls = java.util.concurrent.atomic.AtomicInteger()
        remove = {
            if (calls.incrementAndGet() == 1) { entered.countDown(); release.await(5, TimeUnit.SECONDS) }
            false
        }
        val first = async(Dispatchers.IO) { engine.deleteStoredRecording(row()) }
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        try {
            val second = async(start = CoroutineStart.UNDISPATCHED) { engine.deleteStoredRecording(row()) }
            assertEquals(1, calls.get())
            assertFalse(second.isCompleted)
            release.countDown()
            assertEquals(StorageDeleteResult.STORAGE_ERROR, withTimeout(3000) { second.await() })
            assertEquals(StorageDeleteResult.STORAGE_ERROR, first.await())
            assertEquals(2, calls.get())
        } finally { release.countDown() }
    }
    @Test fun candidateLimitIsAppliedWithinDestinationCreationHistoryAndOldestFirst() = runBlocking {
        for (id in 2L..205) repo.insert(row(id).copy(endedAtMillis = id))
        repo.event(205, "STORAGE_CREATED:another-destination", "Created")
        repo.event(204, "STORAGE_CREATED:${destination.id}", "Created")
        repo.event(203, "STORAGE_CREATED:${destination.id}", "Created")
        assertEquals(listOf(203L, 204L), repo.storageCandidates(destination.id).map { it.id })
    }
    @Test fun discardingAnEmptySinkForgetsReceiptOnlyAfterConfirmedDeletion() = runBlocking {
        val file = File(RecordingStorage.internalDir(context), "empty.ts").apply { writeBytes(byteArrayOf()) }
        assertTrue(store.created(7, file.absolutePath, destination.id))
        discardEmptyRecordingSink(context, settings, file.absolutePath)
        assertFalse(file.exists())
        assertFalse(store.owned(7, file.absolutePath, destination.id))
        assertTrue(store.created(8, file.absolutePath, destination.id))
        discardEmptyRecordingSink(context, settings, file.absolutePath) // Absent file: deletion is unconfirmed.
        assertTrue(store.owned(8, file.absolutePath, destination.id))
        assertEquals(listOf("COMPLETED"), db.recordingEvents().forRecording(1).map { it.kind })
    }
}
