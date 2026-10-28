/* Copyright (C) 2026 Nuvix Contributors. GPL-3.0-or-later. */
package app.opentv.recording

import android.app.Application
import android.content.Context
import android.content.ContentProvider
import android.content.ContentValues
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.database.MatrixCursor
import android.net.Uri
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import org.robolectric.Robolectric
import org.robolectric.shadows.ShadowContentResolver
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
    @Test @Config(sdk = [23, 28])
    fun olderAndroidMatchesOnlyResolvedLocatorsBoundToTheCurrentUsbTree() {
        val tree = DocumentsContract.buildTreeDocumentUri("synthetic", "volume:recordings")
        settings.setUsbTree(tree.toString(), "Private folder")
        // Provider IDs are opaque: the leaf need not share a string prefix with the root ID.
        val locator = DocumentsContract.buildDocumentUriUsingTree(tree, "opaque leaf/with:punctuation").toString()
        assertNotNull(RecordingDestinations.forLocator(context, settings, locator))
        val otherTree = DocumentsContract.buildTreeDocumentUri("synthetic", "another:folder")
        val otherProvider = DocumentsContract.buildTreeDocumentUri("another", "volume:recordings")
        for (other in listOf(DocumentsContract.buildDocumentUriUsingTree(otherProvider, "leaf").toString(),
            DocumentsContract.buildDocumentUriUsingTree(otherTree, "leaf").toString(),
            DocumentsContract.buildDocumentUri("synthetic", "leaf").toString(),
            DocumentsContract.buildDocumentUriUsingTree(tree, "volume:recordings").toString(), tree.toString()))
            assertNull(RecordingDestinations.forLocator(context, settings, other))
        val usb = RecordingDestinations.configured(context, settings).first { it.target == AppSettings.RecordingTarget.USB }
        assertTrue(store.created(7, locator, usb.id))
        val row = app.opentv.data.model.Recording(id = 7, channelId = 1, sourceId = 1, channelName = "Synthetic",
            title = "Synthetic", filePath = locator, streamUrl = "https://example.invalid", status = app.opentv.data.model.RecordingStatus.COMPLETED)
        assertEquals(usb.id, RecordingDestinations.forRecording(context, settings, store, row))
        settings.setUsbTree(otherTree.toString(), "Changed folder")
        assertNull(RecordingDestinations.forLocator(context, settings, locator))
    }

    @Test fun readableWritableUsbProviderCanHaveUnknownCapacity() {
        val provider = Robolectric.buildContentProvider(WritableUsbProvider::class.java).create().get()
        ShadowContentResolver.registerProviderInternal("usb-fixture", provider)
        val tree = Uri.parse("content://usb-fixture/tree/opaque-tree")
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        context.contentResolver.takePersistableUriPermission(tree, flags)
        val granted = object : ContextWrapper(context) {
            override fun checkCallingOrSelfUriPermission(uri: Uri, modeFlags: Int) = PackageManager.PERMISSION_GRANTED
        }
        settings.setUsbTree(tree.toString(), "Synthetic USB")
        val folder = requireNotNull(DocumentFile.fromTreeUri(granted, tree))
        assertTrue(folder.canRead()); assertTrue(folder.canWrite())
        val usb = RecordingDestinations.configured(granted, settings).first { it.target == AppSettings.RecordingTarget.USB }
        val capacity = RecordingDestinations.probe(granted, settings, usb)
        assertEquals(StorageAvailability.AVAILABLE, capacity.availability)
        assertNull(capacity.availableBytes); assertNull(capacity.totalBytes)
        assertFalse(capacity.reliable(System.currentTimeMillis()))
    }
}

/** Synthetic SAF metadata provider; exposes no space query. This does not model real USB hardware. */
class WritableUsbProvider : ContentProvider() {
    override fun onCreate() = true
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?,
        selectionArgs: Array<out String>?, sortOrder: String?): android.database.Cursor {
        val values = mapOf<String, Any>(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID to "opaque-tree",
            DocumentsContract.Document.COLUMN_DISPLAY_NAME to "Synthetic USB",
            DocumentsContract.Document.COLUMN_MIME_TYPE to DocumentsContract.Document.MIME_TYPE_DIR,
            DocumentsContract.Document.COLUMN_FLAGS to DocumentsContract.Document.FLAG_DIR_SUPPORTS_CREATE,
        )
        val columns = projection?.toList() ?: values.keys.toList()
        return MatrixCursor(columns.toTypedArray()).apply { addRow(columns.map { values[it] }) }
    }
    override fun getType(uri: Uri) = DocumentsContract.Document.MIME_TYPE_DIR
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
}
