/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.player

import android.content.Context
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import app.opentv.core.ServiceLocator
import app.opentv.recording.RecordingLiveState
import app.opentv.recording.RecordingStorage
import app.opentv.recording.SmbClient
import app.opentv.recording.SmbReadHandle
import kotlinx.coroutines.runBlocking
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile

/**
 * Plays a recording *while it is still being recorded*, whether it's being written to this box's
 * internal storage or straight to a NAS over SMB.
 *
 * A normal file/SMB source reads to the current end and reports end-of-stream — which for an
 * in-progress recording would stop a few seconds in, at whatever had been written when the player
 * opened it. This source tail-follows instead: when it reaches the end of what's on disk it asks
 * [RecordingLiveState] whether the capture is still running, and if so waits for the next bytes.
 * Only when the capture is genuinely done does it report the real end.
 *
 * That means zero extra load on the provider — the bytes are already being pulled by the recorder;
 * the player just reads the same growing file — which is the whole point on a single-connection
 * account, where a second live stream would get the first one cut off.
 *
 * Addressed by `optvrec://<recordingId>`. Internal recordings tail a local file; NAS recordings tail
 * the SMB file (re-reading past the last size, and reopening the handle if the server's cached
 * end-of-file hasn't caught up to what the recorder has appended).
 */
@OptIn(UnstableApi::class)
class GrowingRecordingDataSource(private val context: Context) : BaseDataSource(true) {

    private var uri: Uri? = null
    private var recordingId: Long = -1L
    private var position: Long = 0L
    private var opened: Boolean = false

    @Volatile
    private var closed: Boolean = false

    // Exactly one of these is used, decided at open() by the recording's locator.
    private var file: RandomAccessFile? = null
    private var smb: SmbReadHandle? = null
    private var smbLocator: String = ""

    override fun open(dataSpec: DataSpec): Long {
        transferInitializing(dataSpec)
        uri = dataSpec.uri
        closed = false
        recordingId = dataSpec.uri.host?.toLongOrNull()
            ?: dataSpec.uri.schemeSpecificPart?.trim('/')?.toLongOrNull()
            ?: throw IOException("Bad recording URI: ${dataSpec.uri}")

        val graph = ServiceLocator.get(context)
        // Prefer the live registry (no I/O); fall back to the row if it just finished.
        val locator = RecordingLiveState.pathOf(recordingId)
            ?: runBlocking { graph.recordingRepository.byId(recordingId)?.filePath }
            ?: throw IOException("Recording $recordingId not found")

        position = dataSpec.position

        if (locator.startsWith("smb://", ignoreCase = true)) {
            smbLocator = locator
            smb = openSmbWithGrace()
        } else {
            // Internal: the auto-switch can open this the instant capture starts, a moment before
            // the recorder has created the file. Give it a short grace to appear.
            val target = File(locator)
            var waited = 0L
            while (!target.exists() && waited < OPEN_WAIT_MILLIS) {
                if (closed) throw IOException("closed while waiting for recording $recordingId")
                Thread.sleep(POLL_MILLIS)
                waited += POLL_MILLIS
            }
            if (!target.exists()) throw IOException("Recording $recordingId file not ready: $locator")
            val f = RandomAccessFile(target, "r")
            if (position > 0) f.seek(position)
            file = f
        }

        opened = true
        transferStarted(dataSpec)
        // Growing file: length is not known up front.
        return C.LENGTH_UNSET.toLong()
    }

    /** Open the SMB file for reading, tolerating the brief window before the recorder has created it. */
    private fun openSmbWithGrace(): SmbReadHandle {
        val cfg = RecordingStorage.smbConfig(ServiceLocator.get(context).settings)
        var waited = 0L
        while (waited < OPEN_WAIT_MILLIS) {
            if (closed) throw IOException("closed while opening $smbLocator")
            val handle = runCatching { SmbClient.openForRead(cfg, smbLocator) }.getOrNull()
            if (handle != null) return handle
            Thread.sleep(POLL_MILLIS)
            waited += POLL_MILLIS
        }
        throw IOException("NAS recording $recordingId not ready: $smbLocator")
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        return if (smb != null) readSmb(buffer, offset, length) else readLocal(buffer, offset, length)
    }

    private fun readLocal(buffer: ByteArray, offset: Int, length: Int): Int {
        val f = file ?: return C.RESULT_END_OF_INPUT
        while (!closed) {
            val available = f.length() - position
            if (available > 0) {
                val toRead = minOf(length.toLong(), available).toInt()
                val read = f.read(buffer, offset, toRead)
                if (read > 0) {
                    position += read
                    bytesTransferred(read)
                    return read
                }
            }
            if (!RecordingLiveState.isActive(recordingId)) {
                if (f.length() - position > 0) continue
                return C.RESULT_END_OF_INPUT
            }
            if (sleep()) return C.RESULT_END_OF_INPUT
        }
        return C.RESULT_END_OF_INPUT
    }

    private fun readSmb(buffer: ByteArray, offset: Int, length: Int): Int {
        var stallPolls = 0
        while (!closed) {
            val handle = smb ?: return C.RESULT_END_OF_INPUT
            val read = runCatching { handle.readAt(buffer, position, offset, length) }.getOrDefault(-1)
            if (read > 0) {
                position += read
                bytesTransferred(read)
                return read
            }
            // At the growing edge. If the capture is done, this really is the end.
            if (!RecordingLiveState.isActive(recordingId)) {
                val last = runCatching { handle.readAt(buffer, position, offset, length) }.getOrDefault(-1)
                if (last > 0) {
                    position += last
                    bytesTransferred(last)
                    return last
                }
                return C.RESULT_END_OF_INPUT
            }
            if (sleep()) return C.RESULT_END_OF_INPUT
            // Some servers cache the end-of-file on an open handle, so a plain re-read never sees the
            // freshly-appended bytes. After a short stall, reopen the handle to refresh it.
            if (++stallPolls >= REOPEN_AFTER_POLLS) {
                stallPolls = 0
                runCatching { smb?.close() }
                smb = runCatching {
                    SmbClient.openForRead(RecordingStorage.smbConfig(ServiceLocator.get(context).settings), smbLocator)
                }.getOrNull()
                if (smb == null) return C.RESULT_END_OF_INPUT
            }
        }
        return C.RESULT_END_OF_INPUT
    }

    /** Sleep one poll; returns true if interrupted (caller should end). */
    private fun sleep(): Boolean = try {
        Thread.sleep(POLL_MILLIS)
        false
    } catch (e: InterruptedException) {
        Thread.currentThread().interrupt()
        true
    }

    override fun getUri(): Uri? = uri

    override fun close() {
        closed = true
        runCatching { file?.close() }
        runCatching { smb?.close() }
        file = null
        smb = null
        if (opened) {
            opened = false
            transferEnded()
        }
        uri = null
    }

    class Factory(private val context: Context) : DataSource.Factory {
        override fun createDataSource(): DataSource = GrowingRecordingDataSource(context)
    }

    private companion object {
        /** How long to wait between checks for freshly-written bytes. Small so close() is prompt. */
        const val POLL_MILLIS = 250L

        /** How long open() waits for the capture to create the file before giving up. */
        const val OPEN_WAIT_MILLIS = 8_000L

        /** Reopen an SMB read handle after this many idle polls (~2s), to refresh a cached EOF. */
        const val REOPEN_AFTER_POLLS = 8
    }
}
