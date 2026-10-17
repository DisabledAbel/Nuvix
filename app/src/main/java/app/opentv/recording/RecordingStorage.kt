/* Nuvix modifications Copyright (C) 2026 Nuvix Contributors. GPL-3.0-or-later. */
/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.recording

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import androidx.documentfile.provider.DocumentFile
import com.disabledabel.nuvix.R
import app.opentv.core.AppSettings
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream
import java.util.UUID

/**
 * Where a recording's bytes go — and how to read the size back and delete it — hidden behind one
 * small surface so the engine, library and playback don't care whether a recording lives on the
 * box's internal storage, on a NAS over SMB, or on a plugged-in USB / external drive.
 *
 * ## Three shapes of locator
 * A recording's `filePath` (its stored locator) is one of:
 *  - an absolute filesystem path — an INTERNAL recording, played back via a `file://` URI;
 *  - an `smb://host/share/…` locator — a NAS recording, read back over SMB;
 *  - a `content://…` document URI — a USB recording written through the Storage Access Framework.
 *
 * USB is special because SAF only assigns a document's `content://` URI when the file is actually
 * created. So a USB recording is planned with a [USB_PLACEHOLDER_PREFIX]`filename` marker; when the
 * capture opens its sink the document is created, and the sink reports the real `content://` URI
 * back (via [Sink.resolvedLocator]) for the caller to persist onto the row.
 */
object RecordingStorage {

    /** A reserve for Android and concurrent captures; shortage never deletes another recording. */
    const val MIN_FREE_BYTES = 256L * 1024 * 1024

    /** Marker locator for a USB recording that hasn't been created (and given a URI) yet. */
    private const val USB_PLACEHOLDER_PREFIX = "usb://"

    /** An opened destination the capture writes to, plus how to close it. */
    class Sink(
        val output: OutputStream,
        /**
         * The real locator the bytes are going to when it differs from the planned one — set only
         * for USB, where the `content://` document URI is known only once the file is created. The
         * capture persists this onto the recording row so playback and delete can find the file.
         * Null for INTERNAL and SMB, whose planned locator is already the final one.
         */
        val resolvedLocator: String? = null,
        private val closer: () -> Unit,
    ) {
        fun close() = closer()
    }

    fun smbConfig(settings: AppSettings): SmbConfig = SmbConfig(
        host = settings.smbHost.value,
        share = settings.smbShare.value,
        folder = settings.smbFolder.value,
        username = settings.smbUser.value,
        password = settings.smbPassword.value,
    )

    /** Internal recordings dir on the device's own (app-private) external storage — no permission needed. */
    fun internalDir(context: Context): File =
        File(context.getExternalFilesDir(null) ?: context.filesDir, "recordings").apply {
            if (!isDirectory && !mkdirs()) throw IOException("Recording directory is unavailable")
        }

    /**
     * The locator a new recording named [filename] would be stored at, given the current target.
     * Computed up front so the DB row knows where its file lives before capture starts.
     */
    fun plannedLocator(context: Context, settings: AppSettings, filename: String): String =
        when (settings.recordingTarget.value) {
            AppSettings.RecordingTarget.SMB ->
                SmbClient.locator(smbConfig(settings), filename)
            AppSettings.RecordingTarget.USB ->
                // The real content:// URI isn't known until the SAF document is created at capture
                // time; carry the intended filename in a placeholder until then.
                USB_PLACEHOLDER_PREFIX + filename
            AppSettings.RecordingTarget.INTERNAL ->
                File(internalDir(context), filename).absolutePath
        }

    /** True for a `content://` locator — a USB recording created through SAF. */
    fun isContent(locator: String): Boolean = locator.startsWith("content://")

    /** True for the pre-creation USB marker — a planned USB recording with no document yet. */
    fun isUsbPlaceholder(locator: String): Boolean = locator.startsWith(USB_PLACEHOLDER_PREFIX)

    /**
     * Open the destination named by [locator] for writing. For a USB placeholder this creates the
     * SAF document and reports its real `content://` URI through [Sink.resolvedLocator]; the caller
     * persists that so the finished recording can be found again. Throws with a human-readable
     * message when the USB drive / folder grant is missing, rather than crashing the capture.
     */
    fun openSink(context: Context, settings: AppSettings, locator: String): Sink = when {
        SmbClient.isSmb(locator) -> {
            val handle = SmbClient.openForWrite(smbConfig(settings), locator, overwrite = false)
            Sink(handle.output) { handle.close() }
        }
        isUsbPlaceholder(locator) ->
            openUsbSink(context, settings, locator.removePrefix(USB_PLACEHOLDER_PREFIX))
        isContent(locator) -> {
            // A retry receives a new planned locator; never truncate recoverable captured content.
            throw IOException("Recording destination already exists; retry into a new file")
        }
        else -> {
            val file = File(locator)
            file.parentFile?.let { if (!it.isDirectory && !it.mkdirs()) throw IOException("Recording destination is unavailable") }
            checkHeadroom(locator)
            if (!file.createNewFile()) throw IOException("Recording destination already exists")
            val out = FileOutputStream(file)
            Sink(out) { out.use { it.flush(); it.fd.sync() } }
        }
    }

    /**
     * Create the recording's file on the granted USB tree and open it for writing. The tree URI
     * carries a persisted read/write permission, so the created document stays readable for
     * playback across restarts with no storage permission.
     */
    private fun openUsbSink(context: Context, settings: AppSettings, filename: String): Sink {
        val treeUriString = settings.usbTreeUri.value
            ?: throw IOException(context.getString(R.string.rec_error_usb_no_folder))
        val tree = DocumentFile.fromTreeUri(context, Uri.parse(treeUriString))
        if (tree == null || !tree.canWrite()) {
            throw IOException(context.getString(R.string.rec_error_usb_unavailable))
        }
        val doc = tree.createFile(mimeForFilename(filename), filename)
            ?: throw IOException(context.getString(R.string.rec_error_usb_unavailable))
        val out = context.contentResolver.openOutputStream(doc.uri)
            ?: run {
                doc.delete()
                throw IOException(context.getString(R.string.rec_error_usb_unavailable))
            }
        return Sink(out, resolvedLocator = doc.uri.toString()) {
            out.use { it.flush() }
        }
    }

    /** Unknown free space on a document provider/NAS is disclosed, then write failures are fatal. */
    fun checkHeadroom(locator: String, minimumFreeBytes: Long = MIN_FREE_BYTES) {
        if (SmbClient.isSmb(locator) || isContent(locator) || isUsbPlaceholder(locator)) return
        val parent = File(locator).parentFile ?: throw IOException("Recording destination is unavailable")
        if (!parent.isDirectory || parent.usableSpace < minimumFreeBytes) {
            throw IOException("Insufficient free recording space")
        }
    }

    /** Check roughly once a second; simultaneous writers all observe the same remaining space. */
    fun storageMonitor(locator: String): () -> Unit {
        var lastChecked = Long.MIN_VALUE
        return {
            val now = SystemClock.elapsedRealtime()
            if (lastChecked == Long.MIN_VALUE || now - lastChecked >= 1_000L) {
                checkHeadroom(locator)
                lastChecked = now
            }
        }
    }

    /** Retry metadata gets a new destination so an interrupted file remains recoverable. */
    fun retryLocator(locator: String): String {
        val suffix = "retry-${UUID.randomUUID()}.ts"
        return when {
            isContent(locator) || isUsbPlaceholder(locator) -> USB_PLACEHOLDER_PREFIX + suffix
            SmbClient.isSmb(locator) -> locator.substringBeforeLast('/') + "/" + suffix
            else -> File(File(locator).parentFile, suffix).absolutePath
        }
    }

    /** Recordings are MPEG-TS `.ts`; anything else falls back to a generic video type. */
    private fun mimeForFilename(filename: String): String =
        if (filename.endsWith(".ts", ignoreCase = true)) "video/mp2t" else "video/*"

    fun delete(context: Context, settings: AppSettings, locator: String) {
        when {
            SmbClient.isSmb(locator) -> runCatching { SmbClient.delete(smbConfig(settings), locator) }
            isContent(locator) ->
                runCatching { DocumentFile.fromSingleUri(context, Uri.parse(locator))?.delete() }
            isUsbPlaceholder(locator) -> Unit // never created — nothing on disk to remove
            else -> runCatching { File(locator).delete() }
        }
    }

    /**
     * Current on-disk size, or -1 when it can't be cheaply known (SMB / USB — the DB tracks it
     * instead).
     */
    fun sizeOf(locator: String): Long =
        if (SmbClient.isSmb(locator) || isContent(locator) || isUsbPlaceholder(locator)) -1L
        else File(locator).length()

    /** A filesystem-safe recording filename: `Channel - 2026-08-02 1830.ts`. */
    fun fileNameFor(channelName: String, title: String, startMillis: Long): String {
        val stamp = android.text.format.DateFormat.format("yyyy-MM-dd HHmm", startMillis)
        val base = (if (title.isBlank() || title == channelName) channelName else "$channelName - $title")
        val safe = base.replace(Regex("[^A-Za-z0-9 _.-]"), "_").take(80).trim()
        return "$safe - $stamp - ${UUID.randomUUID()}.ts"
    }
}
