/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.player

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import app.opentv.core.AppSettings
import app.opentv.recording.RecordingStorage
import app.opentv.recording.SmbClient
import app.opentv.recording.SmbReadHandle

/**
 * A Media3 data source that reads a recording straight off a NAS over SMB, at arbitrary offsets —
 * which is what makes a NAS recording seekable in the app's own player rather than needing a copy
 * pulled down first. Only `smb://` URIs come here; everything else routes to the default source.
 */
@OptIn(UnstableApi::class)
class SmbDataSource(private val settings: AppSettings) : BaseDataSource(true) {

    private var uri: Uri? = null
    private var handle: SmbReadHandle? = null
    private var position: Long = 0
    private var bytesRemaining: Long = 0
    private var opened: Boolean = false

    override fun open(dataSpec: DataSpec): Long {
        transferInitializing(dataSpec)
        uri = dataSpec.uri
        val handle = SmbClient.openForRead(RecordingStorage.smbConfig(settings), dataSpec.uri.toString())
        this.handle = handle
        position = dataSpec.position
        bytesRemaining =
            if (dataSpec.length != C.LENGTH_UNSET.toLong()) dataSpec.length
            else (handle.length - dataSpec.position).coerceAtLeast(0)
        opened = true
        transferStarted(dataSpec)
        return bytesRemaining
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (bytesRemaining == 0L) return C.RESULT_END_OF_INPUT
        val toRead = minOf(length.toLong(), bytesRemaining).toInt()
        val read = handle?.readAt(buffer, position, offset, toRead) ?: return C.RESULT_END_OF_INPUT
        if (read < 0) return C.RESULT_END_OF_INPUT
        position += read
        bytesRemaining -= read
        bytesTransferred(read)
        return read
    }

    override fun getUri(): Uri? = uri

    override fun close() {
        runCatching { handle?.close() }
        handle = null
        if (opened) {
            opened = false
            transferEnded()
        }
        uri = null
    }

    class Factory(private val settings: AppSettings) : DataSource.Factory {
        override fun createDataSource(): DataSource = SmbDataSource(settings)
    }
}

/**
 * Routes by URI scheme: each scheme in [bySchemes] (e.g. `smb://` for a NAS recording, `optvrec://`
 * for one that's still being written) goes to its own source; everything else (http, https, file,
 * content) to the normal source. Lets one player handle live streams, VOD, internal recordings,
 * NAS recordings and in-progress captures without the caller caring which is which.
 */
@OptIn(UnstableApi::class)
class RoutingDataSourceFactory(
    private val default: DataSource.Factory,
    private val bySchemes: Map<String, DataSource.Factory>,
) : DataSource.Factory {
    override fun createDataSource(): DataSource =
        RoutingDataSource(
            default.createDataSource(),
            bySchemes.mapValues { (_, factory) -> factory.createDataSource() },
        )
}

@OptIn(UnstableApi::class)
private class RoutingDataSource(
    private val default: DataSource,
    private val byScheme: Map<String, DataSource>,
) : DataSource {
    private var active: DataSource? = null

    override fun addTransferListener(transferListener: TransferListener) {
        default.addTransferListener(transferListener)
        byScheme.values.forEach { it.addTransferListener(transferListener) }
    }

    override fun open(dataSpec: DataSpec): Long {
        val source = byScheme[dataSpec.uri.scheme?.lowercase().orEmpty()] ?: default
        active = source
        return source.open(dataSpec)
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
        active?.read(buffer, offset, length) ?: C.RESULT_END_OF_INPUT

    override fun getUri(): Uri? = active?.uri

    override fun getResponseHeaders(): Map<String, List<String>> =
        active?.responseHeaders ?: emptyMap()

    override fun close() {
        active?.close()
        active = null
    }
}
