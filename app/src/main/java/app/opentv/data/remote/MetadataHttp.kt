/* Nuvix modifications Copyright (C) 2026 Nuvix Contributors. GPL-3.0-or-later. */
package app.opentv.data.remote

import java.io.File
import java.security.MessageDigest
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request

/** One instance per public service: serializes cache misses and enforces a host-wide cooldown. */
class MetadataHttp(
    private val http: OkHttpClient,
    private val directory: File,
    private val clock: () -> Long = System::currentTimeMillis,
    private val pause: suspend (Long) -> Unit = { delay(it) },
    private val spacingMillis: Long = 600,
) {
    private val lock = Mutex()
    private var nextRequest = 0L
    private var blockedUntil = 0L
    private val json = Json { ignoreUnknownKeys = true }

    /** Fetches metadata JSON with local caching, bounded retries and cancellation support. */
    suspend fun get(url: HttpUrl): JsonElement? = withContext(Dispatchers.IO) {
        lock.withLock {
            currentCoroutineContext().ensureActive()
            val key = MessageDigest.getInstance("SHA-256").digest(url.toString().toByteArray())
                .joinToString("") { "%02x".format(it) }
            val file = File(directory, "$key.json")
            val cached = runCatching {
                file.takeIf { it.isFile && it.length() <= MAX_RESPONSE }?.readText()
                    ?.let(json::parseToJsonElement)
            }.getOrNull()
            val age = clock() - file.lastModified()
            if (cached != null && age in 0 until FRESH) return@withLock cached
            val stale = cached?.takeIf { age in 0 until STALE }
            if (clock() < blockedUntil) return@withLock stale
            repeat(3) { attempt ->
                val wait = (nextRequest - clock()).coerceAtLeast(0)
                if (wait > 0) pause(wait)
                currentCoroutineContext().ensureActive()
                nextRequest = clock() + spacingMillis
                try {
                    http.newCall(Request.Builder().url(url)
                        .header("User-Agent", "Nuvix/0.1 (+https://github.com/DisabledAbel/Nuvix)")
                        .build()).execute().use { response ->
                        currentCoroutineContext().ensureActive()
                        if (response.code == 429) {
                            val waitMillis = retryDelay(response.header("Retry-After"), clock(), attempt)
                            blockedUntil = clock() + waitMillis
                            // Long Retry-After values are respected without hanging the detail UI.
                            if (attempt == 2 || waitMillis > 5_000) return@withLock stale
                            nextRequest = maxOf(nextRequest, blockedUntil)
                        } else {
                            val value = when {
                                response.code == 404 -> kotlinx.serialization.json.JsonNull
                                response.isSuccessful -> {
                                    val source = response.body?.source() ?: return@withLock stale
                                    source.request(MAX_RESPONSE + 1)
                                    if (source.buffer.size > MAX_RESPONSE) return@withLock stale
                                    val body = source.buffer.readUtf8()
                                    runCatching { json.parseToJsonElement(body) }.getOrNull()
                                        ?: return@withLock stale
                                }
                                else -> {
                                    blockedUntil = clock() + 60_000
                                    return@withLock stale
                                }
                            }
                            save(file, value)
                            return@withLock value
                        }
                    }
                } catch (e: java.io.IOException) {
                    currentCoroutineContext().ensureActive()
                    blockedUntil = clock() + 60_000
                    return@withLock stale
                }
            }
            stale
        }
    }

    /** Stores a successful metadata response in the local cache. */
    private fun save(file: File, value: JsonElement) {
        // Cache failure must never prevent viewing provider data (disk full, eviction, etc.).
        runCatching {
            directory.mkdirs()
            val temp = File(directory, "${file.name}.tmp")
            temp.writeText(value.toString())
            if (!temp.renameTo(file)) temp.delete() else file.setLastModified(clock())
            directory.listFiles()?.filter { it.extension == "json" }
                ?.sortedByDescending { it.lastModified() }?.let { files ->
                    var bytes = 0L
                    files.forEachIndexed { index, entry ->
                        bytes += entry.length()
                        if (index >= 256 || bytes > 20 * 1024 * 1024) entry.delete()
                    }
                }
        }
    }

    companion object {
        private const val FRESH = 60 * 60 * 1000L
        private const val STALE = 7 * 24 * FRESH
        private const val MAX_RESPONSE = 8 * 1024 * 1024L

        /** Calculates the delay before retrying a transient metadata request failure. */
        internal fun retryDelay(header: String?, now: Long, attempt: Int): Long {
            val seconds = header?.toLongOrNull()?.takeIf { it >= 0 }
                ?.coerceAtMost(7 * 24 * 3600)?.times(1000)
            val date = runCatching {
                ZonedDateTime.parse(header, DateTimeFormatter.RFC_1123_DATE_TIME)
                    .toInstant().toEpochMilli() - now
            }.getOrNull()
            return maxOf(1_000L shl attempt, seconds ?: date ?: 0)
        }
    }
}
