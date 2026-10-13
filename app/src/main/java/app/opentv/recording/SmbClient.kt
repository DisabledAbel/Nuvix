/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.recording

import com.hierynomus.msdtyp.AccessMask
import com.hierynomus.mssmb2.SMB2CreateDisposition
import com.hierynomus.mssmb2.SMB2ShareAccess
import com.hierynomus.smbj.SMBClient
import com.hierynomus.smbj.auth.AuthenticationContext
import com.hierynomus.smbj.connection.Connection
import com.hierynomus.smbj.session.Session
import com.hierynomus.smbj.share.DiskShare
import java.io.Closeable
import java.io.OutputStream
import java.util.EnumSet

/**
 * A NAS / SMB share to record to. Stored on-device (SharedPreferences), exactly like a provider's
 * credentials — it never leaves the device except to talk to the user's own server.
 */
data class SmbConfig(
    val host: String,
    val share: String,
    /** Sub-folder within the share, e.g. `OpenTV`. Blank = the share root. */
    val folder: String,
    val username: String,
    val password: String,
) {
    val isConfigured: Boolean get() = host.isNotBlank() && share.isNotBlank()
}

/** An open SMB file being written to. Closing tears the whole SMB stack down cleanly. */
class SmbWriteHandle(
    private val client: SMBClient,
    private val connection: Connection,
    private val session: Session,
    private val share: DiskShare,
    private val file: com.hierynomus.smbj.share.File,
    val output: OutputStream,
    /** The `smb://host/share/path` locator stored on the recording row. */
    val locator: String,
) : Closeable {
    override fun close() {
        runCatching { output.flush() }
        runCatching { output.close() }
        runCatching { file.close() }
        runCatching { share.close() }
        runCatching { session.close() }
        runCatching { connection.close() }
        runCatching { client.close() }
    }
}

/**
 * A random-access reader over an SMB file, for playing a NAS recording back in-app. Media3's
 * data source seeks by asking for bytes at an offset; SMB supports exactly that.
 */
class SmbReadHandle(
    private val client: SMBClient,
    private val connection: Connection,
    private val session: Session,
    private val share: DiskShare,
    private val file: com.hierynomus.smbj.share.File,
    val length: Long,
) : Closeable {
    /** Reads up to [length] bytes at [fileOffset]; returns bytes read or -1 at EOF. */
    fun readAt(buffer: ByteArray, fileOffset: Long, bufferOffset: Int, length: Int): Int =
        file.read(buffer, fileOffset, bufferOffset, length)

    override fun close() {
        runCatching { file.close() }
        runCatching { share.close() }
        runCatching { session.close() }
        runCatching { connection.close() }
        runCatching { client.close() }
    }
}

/**
 * Thin SMB2/3 client over SMBJ. One connection per operation (recording holds it for the
 * capture; playback holds it for the play). Deliberately not pooled — an IPTV box records one or
 * two things at a time, and a fresh connection is simpler and robust against a NAS that drops idle
 * sessions.
 */
object SmbClient {

    // Bound every SMB operation. Without this a write to a NAS that has gone quiet (a Wi-Fi blip, a
    // drive spinning up, the session dropping) blocks forever — which silently freezes a recording
    // mid-capture with no error. A timeout turns that hang into a normal dropped-connection the
    // capture loop can retry.
    private val smbjConfig by lazy {
        com.hierynomus.smbj.SmbConfig.builder()
            .withSoTimeout(35, java.util.concurrent.TimeUnit.SECONDS)
            .withTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
            .build()
    }

    private fun connect(config: SmbConfig): Quad {
        val client = SMBClient(smbjConfig)
        val connection = client.connect(config.host)
        val auth = AuthenticationContext(config.username, config.password.toCharArray(), null)
        val session = connection.authenticate(auth)
        val share = session.connectShare(config.share) as DiskShare
        return Quad(client, connection, session, share)
    }

    private class Quad(
        val client: SMBClient,
        val connection: Connection,
        val session: Session,
        val share: DiskShare,
    )

    /** SMB paths use backslashes; the folder may be nested and is created if missing. */
    private fun ensureFolder(share: DiskShare, folder: String) {
        if (folder.isBlank()) return
        val parts = folder.split('/', '\\').filter { it.isNotBlank() }
        var acc = ""
        for (part in parts) {
            acc = if (acc.isEmpty()) part else "$acc\\$part"
            if (!share.folderExists(acc)) runCatching { share.mkdir(acc) }
        }
    }

    /** Build the `smb://host/share/folder/filename` locator a recording will be stored at. */
    fun locator(config: SmbConfig, filename: String): String {
        val f = config.folder.split('/', '\\').filter { it.isNotBlank() }.joinToString("/")
        val path = if (f.isEmpty()) filename else "$f/$filename"
        return "smb://${config.host}/${config.share}/$path"
    }

    /** Open the file named by [locator] for writing. Caller writes to [SmbWriteHandle.output]. */
    fun openForWrite(config: SmbConfig, locator: String): SmbWriteHandle {
        val parsed = parse(locator)
        val cfg = config.copy(host = parsed.host, share = parsed.share)
        val q = connect(cfg)
        try {
            val folder = parsed.path.substringBeforeLast('/', "")
            ensureFolder(q.share, folder)
            val file = q.share.openFile(
                parsed.path.replace('/', '\\'),
                EnumSet.of(AccessMask.GENERIC_WRITE),
                null,
                SMB2ShareAccess.ALL,
                SMB2CreateDisposition.FILE_OVERWRITE_IF,
                null,
            )
            return SmbWriteHandle(
                q.client, q.connection, q.session, q.share, file,
                file.outputStream, locator,
            )
        } catch (t: Throwable) {
            runCatching { q.share.close() }
            runCatching { q.session.close() }
            runCatching { q.connection.close() }
            runCatching { q.client.close() }
            throw t
        }
    }

    /** Open an existing `smb://host/share/path` locator for random-access reading (playback). */
    fun openForRead(config: SmbConfig, locator: String): SmbReadHandle {
        val parsed = parse(locator)
        val cfg = config.copy(host = parsed.host, share = parsed.share)
        val q = connect(cfg)
        try {
            val file = q.share.openFile(
                parsed.path.replace('/', '\\'),
                EnumSet.of(AccessMask.GENERIC_READ),
                null,
                SMB2ShareAccess.ALL,
                SMB2CreateDisposition.FILE_OPEN,
                null,
            )
            val size = file.fileInformation.standardInformation.endOfFile
            return SmbReadHandle(q.client, q.connection, q.session, q.share, file, size)
        } catch (t: Throwable) {
            runCatching { q.share.close() }
            runCatching { q.session.close() }
            runCatching { q.connection.close() }
            runCatching { q.client.close() }
            throw t
        }
    }

    /** Delete a recording from the NAS. */
    fun delete(config: SmbConfig, locator: String) {
        val parsed = parse(locator)
        val cfg = config.copy(host = parsed.host, share = parsed.share)
        val q = connect(cfg)
        try {
            runCatching { q.share.rm(parsed.path.replace('/', '\\')) }
        } finally {
            runCatching { q.share.close() }
            runCatching { q.session.close() }
            runCatching { q.connection.close() }
            runCatching { q.client.close() }
        }
    }

    /**
     * File names directly inside [folder] within the share (backslash- or slash-separated path,
     * relative to the share root). Empty when the folder does not exist yet. Used by NAS sync to
     * find every device's bundle file. Directory entries (including `.`/`..`) are left in — callers
     * filter by the extension they expect.
     */
    fun list(config: SmbConfig, folder: String): List<String> {
        val q = connect(config)
        try {
            val rel = folder.split('/', '\\').filter { it.isNotBlank() }.joinToString("\\")
            if (rel.isNotEmpty() && !q.share.folderExists(rel)) return emptyList()
            return q.share.list(rel).map { it.fileName }
        } finally {
            runCatching { q.share.close() }
            runCatching { q.session.close() }
            runCatching { q.connection.close() }
            runCatching { q.client.close() }
        }
    }

    /** Overwrite the file at [locator] with [text] (UTF-8). Creates the parent folder if missing. */
    fun writeText(config: SmbConfig, locator: String, text: String) {
        val handle = openForWrite(config, locator)
        try {
            handle.output.write(text.toByteArray(Charsets.UTF_8))
            handle.output.flush()
        } finally {
            handle.close()
        }
    }

    /** Read the whole file at [locator] as a UTF-8 string. */
    fun readText(config: SmbConfig, locator: String): String {
        val handle = openForRead(config, locator)
        try {
            val size = handle.length.toInt()
            if (size <= 0) return ""
            val buffer = ByteArray(size)
            var offset = 0
            while (offset < size) {
                val read = handle.readAt(buffer, offset.toLong(), offset, size - offset)
                if (read <= 0) break
                offset += read
            }
            return String(buffer, 0, offset, Charsets.UTF_8)
        } finally {
            handle.close()
        }
    }

    /** Prove the settings work: connect, authenticate, open the share. Throws on failure. */
    fun test(config: SmbConfig) {
        val q = connect(config)
        try {
            ensureFolder(q.share, config.folder)
        } finally {
            runCatching { q.share.close() }
            runCatching { q.session.close() }
            runCatching { q.connection.close() }
            runCatching { q.client.close() }
        }
    }

    data class Parsed(val host: String, val share: String, val path: String)

    /** smb://host/share/a/b/c.ts → (host, share, "a/b/c.ts"). */
    fun parse(locator: String): Parsed {
        val body = locator.removePrefix("smb://")
        val segments = body.split('/').filter { it.isNotEmpty() }
        val host = segments.getOrElse(0) { "" }
        val share = segments.getOrElse(1) { "" }
        val path = segments.drop(2).joinToString("/")
        return Parsed(host, share, path)
    }

    fun isSmb(locator: String): Boolean = locator.startsWith("smb://")
}
