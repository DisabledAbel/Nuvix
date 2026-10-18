/* Nuvix modifications Copyright (C) 2026 Nuvix Contributors. GPL-3.0-or-later. */
package app.opentv.metadata

import app.opentv.data.remote.MetadataHttp
import com.google.common.truth.Truth.assertThat
import java.io.File
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield
import kotlinx.serialization.json.JsonNull
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Regression tests for metadata http. */
class MetadataHttpTest {
    @get:Rule val temp = TemporaryFolder()
    private lateinit var server: MockWebServer
    private var now = 1_800_000_000_000L
    private val pauses = mutableListOf<Long>()
    /** Creates isolated fixtures before each test. */
    @Before fun start() { server = MockWebServer(); server.start() }
    /** Releases test resources and restores shared state after each test. */
    @After fun stop() { server.shutdown() }
    /** Creates a metadata HTTP client targeting the local mock server. */
    private fun client() = MetadataHttp(OkHttpClient(), File(temp.root, "cache"),
        clock = { now }, pause = { pauses.add(it); now += it })

    /** Checks: fresh cache survives a new client and negative results are cached. */
    @Test fun `fresh cache survives a new client and negative results are cached`(): Unit = runBlocking {
        server.enqueue(MockResponse().setBody("{\"id\":1}"))
        server.enqueue(MockResponse().setResponseCode(404))
        val first = client().get(server.url("/shows/1"))
        assertThat(client().get(server.url("/shows/1"))).isEqualTo(first)
        assertThat(client().get(server.url("/missing"))).isEqualTo(JsonNull)
        assertThat(client().get(server.url("/missing"))).isEqualTo(JsonNull)
        assertThat(server.requestCount).isEqualTo(2)
    }
    /** Checks: concurrent identical misses are coalesced and other requests throttled. */
    @Test fun `concurrent identical misses are coalesced and other requests throttled`(): Unit = runBlocking {
        server.enqueue(MockResponse().setBody("[]"))
        server.enqueue(MockResponse().setBody("[]"))
        val http = client()
        List(4) { async { http.get(server.url("/same")) } }.awaitAll()
        http.get(server.url("/other"))
        assertThat(server.requestCount).isEqualTo(2)
        assertThat(pauses).containsExactly(600L)
    }
    /** Checks: 429 retries honor retry after then cache success. */
    @Test fun `429 retries honor retry after then cache success`(): Unit = runBlocking {
        server.enqueue(MockResponse().setResponseCode(429).setHeader("Retry-After", "2"))
        server.enqueue(MockResponse().setBody("[]"))
        val http = client()
        assertThat(http.get(server.url("/test"))).isNotNull()
        assertThat(pauses).containsExactly(2_000L)
        assertThat(server.requestCount).isEqualTo(2)
    }
    /** Checks: long retry after applies across endpoints without blocking. */
    @Test fun `long retry after applies across endpoints without blocking`(): Unit = runBlocking {
        val date = DateTimeFormatter.RFC_1123_DATE_TIME.format(Instant.ofEpochMilli(now + 120_000).atZone(ZoneOffset.UTC))
        server.enqueue(MockResponse().setResponseCode(429).setHeader("Retry-After", date))
        val http = client()
        assertThat(http.get(server.url("/one"))).isNull()
        assertThat(http.get(server.url("/two"))).isNull()
        assertThat(pauses).isEmpty()
        assertThat(server.requestCount).isEqualTo(1)
        now += 120_000
        server.enqueue(MockResponse().setBody("[]"))
        assertThat(http.get(server.url("/two"))).isNotNull()
    }
    /** Checks: repeated 429 retries are bounded. */
    @Test fun `repeated 429 retries are bounded`(): Unit = runBlocking {
        repeat(3) { server.enqueue(MockResponse().setResponseCode(429)) }
        val http = client()
        assertThat(http.get(server.url("/test"))).isNull()
        assertThat(http.get(server.url("/other"))).isNull()
        assertThat(server.requestCount).isEqualTo(3)
        assertThat(pauses).containsExactly(1_000L, 2_000L).inOrder()
    }
    /** Checks: expired cache is served on outage but discarded after seven days. */
    @Test fun `expired cache is served on outage but discarded after seven days`(): Unit = runBlocking {
        server.enqueue(MockResponse().setBody("{\"id\":1}"))
        val http = client()
        val value = http.get(server.url("/show"))
        now += 3_600_001
        server.enqueue(MockResponse().setResponseCode(503))
        assertThat(http.get(server.url("/show"))).isEqualTo(value)
        now += 8 * 24 * 3_600_000L
        server.enqueue(MockResponse().setResponseCode(503))
        assertThat(http.get(server.url("/show"))).isNull()
    }
    /** Checks: corrupt cache and malformed response do not crash or get cached. */
    @Test fun `corrupt cache and malformed response do not crash or get cached`(): Unit = runBlocking {
        server.enqueue(MockResponse().setBody("{broken"))
        server.enqueue(MockResponse().setBody("[]"))
        val http = client()
        assertThat(http.get(server.url("/test"))).isNull()
        assertThat(http.get(server.url("/test"))).isNotNull()
        File(temp.root, "cache").listFiles()!!.forEach { it.writeText("broken") }
        server.enqueue(MockResponse().setBody("[]"))
        assertThat(http.get(server.url("/test"))).isNotNull()
        assertThat(server.requestCount).isEqualTo(3)
    }
    /** Checks: unwritable cache does not break metadata. */
    @Test fun `unwritable cache does not break metadata`(): Unit = runBlocking {
        val file = temp.newFile()
        server.enqueue(MockResponse().setBody("[]"))
        assertThat(MetadataHttp(OkHttpClient(), file).get(server.url("/test"))).isNotNull()
    }
    /** Checks: cancellation during backoff sends no retry. */
    @Test fun `cancellation during backoff sends no retry`(): Unit = runBlocking {
        server.enqueue(MockResponse().setResponseCode(429))
        val http = MetadataHttp(OkHttpClient(), temp.newFolder(), pause = { kotlinx.coroutines.awaitCancellation() })
        val job = launch { http.get(server.url("/test")) }
        while (server.requestCount == 0) yield()
        job.cancel(); job.join()
        assertThat(server.requestCount).isEqualTo(1)
    }
}
