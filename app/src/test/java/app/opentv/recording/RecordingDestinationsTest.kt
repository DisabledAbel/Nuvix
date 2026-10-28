/* Copyright (C) 2026 Nuvix Contributors. GPL-3.0-or-later. */
package app.opentv.recording

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.opentv.core.AppSettings
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28])
class RecordingDestinationsTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private var settings = AppSettings::class.java.getDeclaredConstructor(Context::class.java).apply { isAccessible = true }.newInstance(context)
    private val store = DvrStorageStore(context)
    @Before fun reset() { context.getSharedPreferences("dvr_storage", Context.MODE_PRIVATE).edit().clear().commit() }
    @Test fun localCapacityUsbPermissionFailureAndUnsupportedCapacityRemainDistinct() {
        settings.setUsbTree("content://synthetic/tree/private-folder", "Private label")
        // Discovery needs no credentials or Android Keystore: seed only non-secret configuration.
        context.getSharedPreferences("opentv_settings", Context.MODE_PRIVATE).edit()
            .putString("smb_host", "private-nas").putString("smb_share", "private-share")
            .putString("smb_folder", "private-folder").putString("smb_user", "private-user").commit()
        settings = AppSettings::class.java.getDeclaredConstructor(Context::class.java).apply { isAccessible = true }.newInstance(context)
        val destinations = RecordingDestinations.configured(context, settings)
        assertEquals(3, destinations.size)
        assertEquals(3, destinations.map { it.id }.toSet().size)
        assertFalse(destinations.toString().contains("private"))
        val local = destinations.first { it.target == AppSettings.RecordingTarget.INTERNAL }
        assertTrue(RecordingDestinations.probe(context, settings, local).reliable(System.currentTimeMillis()))
        val usb = destinations.first { it.target == AppSettings.RecordingTarget.USB }
        val denied = RecordingDestinations.probe(context, settings, usb)
        assertEquals(StorageAvailability.PERMISSION_DENIED, denied.availability)
        assertNull(denied.availableBytes); assertNull(denied.totalBytes)
        assertFalse(denied.reliable(System.currentTimeMillis()))
        settings.setUsbTree("content://synthetic/tree/other", "Other")
        assertEquals(StorageAvailability.UNAVAILABLE, RecordingDestinations.probe(context, settings, usb).availability)
    }
    @Test fun perDestinationConsentAndOwnershipAreLocalAndCannotMoveToDifferentRootOrRecording() {
        val id = RecordingDestinations.configured(context, settings).first().id
        assertFalse(store.policy(id).cleanupEnabled)
        assertTrue(store.policy(id).warnings)
        store.save(id, DvrStoragePolicy(cleanupEnabled = true))
        assertTrue(DvrStorageStore(context).policy(id).cleanupEnabled)
        assertFalse(store.policy("another-destination").cleanupEnabled)
        store.created(1, "/private.ts", id)
        assertTrue(store.owned(1, "/private.ts", id))
        assertFalse(store.owned(2, "/private.ts", id))
        assertFalse(store.owned(1, "/private.ts", "another-destination"))
        assertFalse(store.owned(1, "/retry.ts", id))
        store.forget("/private.ts"); assertFalse(store.owned(1, "/private.ts", id))
    }
    @Test fun localUsageDoesNotClaimUnrelatedPaths() {
        val local = File(RecordingStorage.internalDir(context), "synthetic.ts")
        assertNotNull(RecordingDestinations.forLocator(context, settings, local.absolutePath))
        assertNull(RecordingDestinations.forLocator(context, settings, File(context.filesDir, "unrelated.ts").absolutePath))
        assertNull(RecordingDestinations.forLocator(context, settings, "https://example.invalid/private"))
    }
    @Test fun olderAndroidDoesNotUseNewChildDocumentApiAndResolvedReceiptsStillMapUsbRecordings() {
        settings.setUsbTree("content://synthetic/tree/private-folder", "Private folder")
        val locator = "content://synthetic/tree/private-folder/document/recording"
        assertNull(RecordingDestinations.forLocator(context, settings, locator))
        val usb = RecordingDestinations.configured(context, settings).first { it.target == AppSettings.RecordingTarget.USB }
        assertTrue(store.created(7, locator, usb.id))
        val row = app.opentv.data.model.Recording(id = 7, channelId = 1, sourceId = 1, channelName = "Synthetic",
            title = "Synthetic", filePath = locator, streamUrl = "https://example.invalid", status = app.opentv.data.model.RecordingStatus.COMPLETED)
        assertEquals(usb.id, RecordingDestinations.forRecording(context, settings, store, row))
    }
}
