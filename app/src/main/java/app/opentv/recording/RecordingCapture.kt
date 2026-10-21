/* Copyright (C) 2026 Nuvix Contributors. GPL-3.0-or-later. */
package app.opentv.recording

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import okhttp3.Call
import okhttp3.Request
import java.io.IOException
import java.io.OutputStream
import java.util.concurrent.TimeUnit

/** The network copy is separate from service ownership so failures can be tested without a TV. */
internal class RecordingCapture(
    private val calls: Call.Factory,
    private val nowMillis: () -> Long = System::currentTimeMillis,
    private val retryDelayMillis: Long = 1_500L,
    private val maxEmptyConnections: Int = 5,
) {
    /** Capture byte count and optional failure information from the stream writer. */
    data class Result(val bytes: Long, val error: String? = null, val startRejected: Boolean = false, val retryable: Boolean = false)

    /** Storage failures terminate immediately; only transport failures are reconnected. */
    suspend fun copy(
        url: String,
        userAgent: String,
        output: OutputStream,
        endMillis: Long,
        onCall: (Call?) -> Unit,
        onProgress: suspend (Long) -> Unit,
        checkStorage: () -> Unit,
        onStarted: suspend () -> Unit = {},
        beforeFirstWrite: suspend () -> Boolean = { true },
        onInterruption: suspend () -> Unit = {},
    ): Result {
        var total = 0L
        var emptyConnections = 0
        var lastReport = 0L
        var interrupted = false
        try {
            while (endMillis <= 0 || nowMillis() < endMillis) {
                currentCoroutineContext().ensureActive()
                val before = total
                val call = calls.newCall(Request.Builder().url(url).header("User-Agent", userAgent).build())
                // A stalled read must not keep a scheduled recording running beyond its deadline.
                if (endMillis > 0) call.timeout().timeout((endMillis - nowMillis()).coerceAtLeast(1), TimeUnit.MILLISECONDS)
                onCall(call)
                try {
                    call.execute().use { response ->
                        if (!response.isSuccessful) {
                            if (response.code in listOf(401, 403, 404)) {
                                return Result(total, "Provider rejected recording (HTTP ${response.code})")
                            }
                            throw IOException("HTTP ${response.code}")
                        }
                        val body = response.body ?: throw IOException("Empty response")
                        val mime = body.contentType()?.toString().orEmpty()
                        if (mime.contains("mpegurl", ignoreCase = true) || mime.contains("dash+xml", ignoreCase = true) ||
                            mime.startsWith("text/", ignoreCase = true) || mime.contains("json", ignoreCase = true) || mime.contains("xml", ignoreCase = true)) {
                            return Result(total, "This stream requires a supported transport-stream recording source")
                        }
                        // Peek spans fragmented socket reads. A playlist/error page must never be
                        // called a successful recording merely because its HTTP status was 200.
                        val prefix = body.source().peek()
                        prefix.request(3L * TS_PACKET_BYTES)
                        val available = prefix.buffer.size
                        if (available > 0 && (available < TS_PACKET_BYTES || prefix.buffer[0] != 0x47.toByte() ||
                                (available >= 2L * TS_PACKET_BYTES && prefix.buffer[TS_PACKET_BYTES.toLong()] != 0x47.toByte()) ||
                                (available >= 3L * TS_PACKET_BYTES && prefix.buffer[2L * TS_PACKET_BYTES] != 0x47.toByte()))) {
                            return Result(total, "Unsupported response; recording requires a direct MPEG transport stream")
                        }
                        val input = body.byteStream()
                        val buffer = ByteArray(64 * 1024)
                        while (endMillis <= 0 || nowMillis() < endMillis) {
                            currentCoroutineContext().ensureActive()
                            val read = input.read(buffer)
                            if (read < 0) break
                            if (read == 0) continue
                            // The network may have taken minutes to yield useful MPEG-TS data.
                            // Check before creating/writing output, once per capture, never on reconnect.
                            if (total == 0L) {
                                val approved = try { beforeFirstWrite() }
                                catch (_: IOException) { return Result(total, "Recording destination is unavailable or full") }
                                if (!approved) return Result(0, startRejected = true)
                            }
                            try {
                                checkStorage()
                                output.write(buffer, 0, read)
                            } catch (failure: Exception) {
                                if (failure is CancellationException) throw failure
                                return Result(total, "Recording destination is unavailable or full")
                            }
                            val firstBytes = total == 0L
                            total += read
                            if (firstBytes) onStarted()
                            if (total - lastReport >= 4L * 1024 * 1024) {
                                lastReport = total
                                onProgress(total)
                            }
                        }
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: IOException) {
                    currentCoroutineContext().ensureActive()
                    if (total > 0 && !interrupted) { interrupted = true; onInterruption() }
                    // Detailed transport errors can contain URLs with provider credentials.
                } finally {
                    onCall(null)
                }
                if (endMillis > 0 && nowMillis() >= endMillis) break
                if (total == before) {
                    emptyConnections++
                    if (emptyConnections >= maxEmptyConnections) {
                        return Result(total, "Stream stopped before recording finished", retryable = total == 0L)
                    }
                    delay(retryDelayMillis)
                } else {
                    emptyConnections = 0
                    // Prevent a finite, immediately closing body from creating a hot reconnect loop.
                    delay(minOf(retryDelayMillis, 250L))
                }
            }
            return Result(total, if (interrupted) "Capture experienced a network interruption; available content preserved" else null)
        } finally {
            // Report bytes even on user cancellation or a storage failure, for partial recovery.
            onProgress(total)
        }
    }

    private companion object { const val TS_PACKET_BYTES = 188 }
}
