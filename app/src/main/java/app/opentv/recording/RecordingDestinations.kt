/* Copyright (C) 2026 Nuvix Contributors. GPL-3.0-or-later. */
package app.opentv.recording

import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import app.opentv.core.AppSettings
import app.opentv.data.model.Recording
import java.io.File

data class RecordingDestination(val id: String, val label: String, val target: AppSettings.RecordingTarget)

/** Storage discovery stays beside the existing sinks. No UI, logs or errors expose private roots. */
object RecordingDestinations {
    fun configured(context: Context, settings: AppSettings): List<RecordingDestination> = buildList {
        val root = context.getExternalFilesDir(null) ?: context.filesDir
        add(RecordingDestination(DvrStorageStore.fingerprint("local:${File(root, "recordings").absolutePath}"),
            "This device", AppSettings.RecordingTarget.INTERNAL))
        settings.usbTreeUri.value?.let { add(RecordingDestination(DvrStorageStore.fingerprint("usb:$it"),
            "USB / external folder", AppSettings.RecordingTarget.USB)) }
        val smb = RecordingStorage.smbConfig(settings)
        if (smb.isConfigured) add(RecordingDestination(DvrStorageStore.fingerprint("smb:${SmbClient.locator(smb, "")}:${smb.username}"),
            "NAS / SMB", AppSettings.RecordingTarget.SMB))
    }

    fun forLocator(context: Context, settings: AppSettings, locator: String): RecordingDestination? =
        configured(context, settings).firstOrNull { destination ->
            when (destination.target) {
                AppSettings.RecordingTarget.INTERNAL -> runCatching {
                    val root = File(context.getExternalFilesDir(null) ?: context.filesDir, "recordings").canonicalFile
                    File(locator).isAbsolute && File(locator).canonicalFile.parentFile == root
                }.getOrDefault(false)
                AppSettings.RecordingTarget.SMB -> SmbClient.isSmb(locator) &&
                    locator.substringBeforeLast('/') + "/" == SmbClient.locator(RecordingStorage.smbConfig(settings), "")
                AppSettings.RecordingTarget.USB -> if (!RecordingStorage.isContent(locator)) false else runCatching {
                    val tree = Uri.parse(settings.usbTreeUri.value)
                    val document = Uri.parse(locator)
                    if (document.authority != tree.authority) return@runCatching false
                    val parent = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
                    if (Build.VERSION.SDK_INT >= 29) DocumentsContract.isChildDocument(context.contentResolver, parent, document)
                    else {
                        // TreeDocumentFile.createFile preserves /tree/<grant>/document/<opaque ID>.
                        // Compare the grant, never infer ancestry from a provider's document ID.
                        val parts = document.pathSegments
                        parts.size == 4 && parts[0] == "tree" && parts[2] == "document" &&
                            DocumentsContract.getTreeDocumentId(document) == DocumentsContract.getTreeDocumentId(tree) &&
                            DocumentsContract.getDocumentId(document) != DocumentsContract.getTreeDocumentId(tree)
                    }
                }.getOrDefault(false)
            }
        }

    fun forRecording(context: Context, settings: AppSettings, store: DvrStorageStore, row: Recording): String? =
        store.destination(row.id, row.filePath) ?: forLocator(context, settings, row.filePath)?.id

    /** A provider with no capacity API remains usable, with Unknown space and no cleanup. */
    fun probe(context: Context, settings: AppSettings, destination: RecordingDestination,
        now: () -> Long = System::currentTimeMillis): StorageCapacity {
        if (configured(context, settings).none { it.id == destination.id })
            return StorageCapacity(StorageAvailability.UNAVAILABLE, now())
        return try {
            when (destination.target) {
                AppSettings.RecordingTarget.INTERNAL -> {
                    val dir = RecordingStorage.internalDir(context)
                    val state = when {
                        !dir.exists() -> StorageAvailability.DISCONNECTED
                        !dir.canRead() -> StorageAvailability.PERMISSION_DENIED
                        !dir.canWrite() -> StorageAvailability.READ_ONLY
                        else -> StorageAvailability.AVAILABLE
                    }
                    StorageCapacity(state, now(), dir.usableSpace, dir.totalSpace)
                }
                AppSettings.RecordingTarget.USB -> {
                    val uri = Uri.parse(settings.usbTreeUri.value)
                    val grant = context.contentResolver.persistedUriPermissions.firstOrNull { it.uri == uri }
                    val tree = DocumentFile.fromTreeUri(context, uri)
                    val state = when {
                        grant?.isReadPermission != true -> StorageAvailability.PERMISSION_DENIED
                        tree?.exists() != true -> StorageAvailability.DISCONNECTED
                        !grant.isWritePermission || !tree.canWrite() -> StorageAvailability.READ_ONLY
                        else -> StorageAvailability.AVAILABLE
                    }
                    StorageCapacity(state, now()) // SAF does not guarantee capacity reporting.
                }
                AppSettings.RecordingTarget.SMB -> StorageCapacity(SmbClient.probe(RecordingStorage.smbConfig(settings)), now())
            }
        } catch (_: SecurityException) {
            StorageCapacity(StorageAvailability.PERMISSION_DENIED, now())
        } catch (_: Exception) {
            StorageCapacity(StorageAvailability.DISCONNECTED, now())
        }
    }
}
