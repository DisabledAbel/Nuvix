/* Copyright (C) 2026 Nuvix Contributors. GPL-3.0-or-later. */
package app.opentv.stalker

import android.app.Application
import android.content.Context
import androidx.room.Room
import app.opentv.cloud.CloudSettings
import app.opentv.connections.*
import app.opentv.data.db.OpenTvDatabase
import app.opentv.data.model.*
import app.opentv.multiview.*
import app.opentv.data.remote.*
import app.opentv.data.repo.SourceRepository
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.MockResponse
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Uses framework Room for host tests; production retains the SQLCipher factory. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28])
class StalkerPersistenceTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private val db = Room.inMemoryDatabaseBuilder(context, OpenTvDatabase::class.java).allowMainThreadQueries().build()
    private val server = MockWebServer()
    private val http = OkHttpClient()
    private val api = StalkerApi(http)
    private val repo = SourceRepository(db.sources(), XtreamApi(http), api)
    private val original = Source(id = 7, name = "Existing provider", kind = SourceKind.STALKER,
        url = "https://portal.example.invalid/c", macAddress = "00:1a:79:aa:bb:cc", username = "retained-user",
        password = "retained-password", epgUrl = "https://guide.example.invalid/epg.xml", userAgent = "Custom Agent",
        lastCatalogSyncMillis = 123, enabled = true)
    @Before fun setup() = runBlocking<Unit> {
        server.start()
        db.sources().insert(original)
        context.getSharedPreferences("nuvix_connection_settings", Context.MODE_PRIVATE).edit().clear().commit()
    }
    @After fun close() { db.close(); server.shutdown() }

    @Test fun editPersistsAllFieldsAtomicallyAndPreservesUnrelatedSettings() = runBlocking {
        val saved = repo.saveStalkerConfiguration(original.copy(macAddress = "00:1a:79:11:22:33",
            name = "ignored stale name", lastCatalogSyncMillis = 0, stalkerSerialNumber = "ProviderSerial",
            stalkerDeviceId1 = "Mixed-ID1", stalkerDeviceId2 = "Mixed-ID2", stalkerSignature = "Mixed-Signature"))
        val expected = original.copy(macAddress = "00:1A:79:11:22:33", stalkerSerialNumber = "ProviderSerial",
            stalkerDeviceId1 = "Mixed-ID1", stalkerDeviceId2 = "Mixed-ID2", stalkerSignature = "Mixed-Signature",
            stalkerAccountKey = AccountConnectionManager.accountIdentity(original), stalkerRevision = 1)
        assertEquals(expected, saved)
        assertEquals(expected, db.sources().byId(7))
        assertEquals(0, server.requestCount)
    }

    @Test fun authenticationUsesConfigurationReloadedFromRoom() = runBlocking {
        db.sources().update(original.copy(url = server.url("/c").toString()))
        val saved = repo.saveStalkerConfiguration(original.copy(stalkerSerialNumber = "SavedSerial",
            stalkerDeviceId1 = "SavedDevice1", stalkerDeviceId2 = "SavedDevice2", stalkerSignature = "SavedSignature"))
        assertEquals(0, server.requestCount)
        server.enqueue(MockResponse().setBody("""{"js":{"token":"synthetic-token","random":"challenge"}}"""))
        server.enqueue(MockResponse().setBody("""{"js":{"blocked":"0"}}"""))
        server.enqueue(MockResponse().setBody("""{"js":[]}"""))
        api.liveCategories(repo.byId(saved.id)!!)
        server.takeRequest()
        val profile = server.takeRequest().requestUrl!!
        assertEquals("SavedSerial", profile.queryParameter("sn"))
        assertEquals("SavedDevice1", profile.queryParameter("device_id"))
        assertEquals("SavedDevice2", profile.queryParameter("device_id2"))
        assertEquals("SavedSignature", profile.queryParameter("signature"))
    }

    @Test fun ordinaryEditPreservesExistingOverridesAndLegacyMacWhenUnedited() = runBlocking {
        val custom = repo.saveStalkerConfiguration(original.copy(stalkerDeviceId2 = "Provider-ID2"))
        assertEquals(original.macAddress, custom.macAddress)
        repo.save(custom.copy(name = "Updated name"))
        val saved = repo.byId(7)!!
        assertEquals("Provider-ID2", saved.stalkerDeviceId2)
        assertEquals(original.macAddress, saved.macAddress)
        assertEquals("Updated name", saved.name)
    }

    @Test fun staleAndInvalidEditsNeverOverwriteSavedConfiguration() = runBlocking {
        val saved = repo.saveStalkerConfiguration(original.copy(stalkerSignature = "Keep-this-value"))
        assertTrue(runCatching { repo.saveStalkerConfiguration(original.copy(stalkerDeviceId1 = "stale")) }.isFailure)
        assertTrue(runCatching { repo.saveStalkerConfiguration(saved.copy(macAddress = "invalid")) }.isFailure)
        assertTrue(runCatching { repo.saveStalkerConfiguration(saved.copy(stalkerDeviceId2 = "bad\r\nvalue")) }.isFailure)
        assertEquals(saved, repo.byId(7))
        assertEquals(0, server.requestCount)
    }

    @Test fun editingIdentityPreservesActiveRecordingMultiViewCapacityAndAccountAssociationsAfterRestart() = runBlocking {
        val manager = AccountConnectionManager.createForTest(context)
        val other = original.copy(id = 8, macAddress = "00:1A:79:44:55:66")
        manager.registerSource(original)
        manager.registerSource(other)
        manager.setMaximumConnections(8, 2)
        assertTrue(manager.associateSources(7, 8))
        val key = manager.accountKeyForSource(7)
        val recording = (manager.reserve(ConnectionRequest("dvr", 1, 7, ConnectionPurpose.RECORDING)) as ReservationResult.Reserved).token
        val multiView = (manager.reserve(ConnectionRequest("multi-view", 1, 8, ConnectionPurpose.MULTIVIEW)) as ReservationResult.Reserved).token
        manager.markActive(recording); manager.markActive(multiView)
        var stopped = false
        manager.registerOwner(recording) { stopped = true }
        val saved = repo.saveStalkerConfiguration(original.copy(macAddress = "00:1A:79:11:22:33", stalkerSignature = "Updated"))
        assertEquals(key, manager.registerSource(saved))
        assertEquals(ReservationState.ACTIVE, manager.reservationState(recording))
        assertEquals(ReservationState.ACTIVE, manager.reservationState(multiView))
        assertFalse(stopped)
        assertEquals(2, manager.snapshotForSource(7)!!.locallyReserved)
        assertTrue(manager.reserve(ConnectionRequest("extra", 1, 7, ConnectionPurpose.LIVE)) is ReservationResult.Denied)
        val reloaded = AccountConnectionManager.createForTest(context)
        reloaded.registerSource(saved); reloaded.registerSource(other)
        assertEquals(key, reloaded.accountKeyForSource(7))
        assertEquals(2, reloaded.snapshotForSource(7)!!.effectiveMaximum)
        assertTrue(reloaded.isManuallyAssociated(7))
    }

    @Test fun savingRetainsFavoritesRecordingPoliciesAndSavedMultiViewReferences() = runBlocking {
        val channel = Channel(id = 21, sourceId = 7, streamId = "synthetic", name = "News", categoryId = "news",
            logoUrl = null, epgChannelId = "news.example", number = 1, streamUrl = "stalker://7/synthetic",
            cmd = "synthetic-command", favourite = true, sortIndex = 42, customName = "My News", epgOverrideId = "my-guide")
        db.channels().upsertAll(listOf(channel))
        val recording = Recording(id = 91, channelId = 21, sourceId = 7, channelName = "News", title = "Keep capture",
            filePath = "/synthetic/recording.ts", streamUrl = "https://stream.example.invalid/synthetic",
            status = RecordingStatus.RECORDING, scheduledStartMillis = 1000, scheduledEndMillis = 90000,
            protectedRecording = true, lockPadding = true, durationRevision = 3, lateStartPolicy = "SKIP_IF_LATE")
        db.recordings().insert(recording)
        val store = MultiViewPresetStore(context)
        val presentation = MultiViewPresentation(listOf(
            MultiViewSavedPanel("first", MultiViewChannelReference(21, 7)),
            MultiViewSavedPanel("second", MultiViewChannelReference(22, 7))), "first", "first", MultiViewLayout.TWO)
        val preset = store.upsert("Synthetic Stalker", presentation)!!
        try {
            repo.saveStalkerConfiguration(original.copy(macAddress = "00:1A:79:11:22:33", stalkerDeviceId2 = "Keep-ID2"))
            assertEquals(channel, db.channels().byId(21))
            assertEquals(recording, db.recordings().byId(91))
            assertEquals(preset, store.find(preset.id))
        } finally { store.delete(preset.id); store.close() }
    }

    @Test fun cloudFlagsAndPrivatePreferencesDoNotReceiveIdentityOverrides() = runBlocking {
        val cloud = CloudSettings(context)
        val before = cloud.categories
        val wasEnabled = cloud.enabled
        val beforeFiles = context.noBackupFilesDir.walkTopDown().filter { it.isFile }.associate { it.path to it.readBytes().toList() }
        repo.saveStalkerConfiguration(original.copy(stalkerSignature = "Local-only-synthetic-signature"))
        assertEquals(before, cloud.categories)
        assertEquals(wasEnabled, cloud.enabled)
        val afterFiles = context.noBackupFilesDir.walkTopDown().filter { it.isFile }.associate { it.path to it.readBytes().toList() }
        assertEquals(beforeFiles, afterFiles)
        assertEquals(0, server.requestCount)
    }
}
