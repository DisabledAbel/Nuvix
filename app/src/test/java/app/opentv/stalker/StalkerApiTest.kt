/* Copyright (C) 2026 Nuvix Contributors. GPL-3.0-or-later. */
package app.opentv.stalker

import app.opentv.data.model.*
import app.opentv.data.remote.StalkerApi
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.net.URLDecoder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class StalkerApiTest {
    private val server = MockWebServer()
    private val api = StalkerApi(OkHttpClient.Builder().callTimeout(5, TimeUnit.SECONDS).build())
    private fun source() = Source(id = 1, name = "Synthetic", kind = SourceKind.STALKER,
        url = server.url("/c").toString(), macAddress = "00:1A:79:AA:BB:CC")
    @Before fun start() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = response(request)
        }
        server.start()
    }
    private fun response(request: RecordedRequest): MockResponse = MockResponse().setBody(when (request.requestUrl?.queryParameter("action")) {
        "handshake" -> """{"js":{"token":"synthetic-token","random":"challenge"}}"""
        "get_profile" -> """{"js":{"id":"1","blocked":"0","status":0}}"""
        "get_genres" -> """{"js":[{"id":"1","title":"Test"}]}"""
        "create_link" -> """{"js":{"cmd":"ffmpeg https://stream.example.invalid/synthetic"}}"""
        else -> """{"js":[]}"""
    })
    @After fun stop() = server.shutdown()
    private fun next() = server.takeRequest(5, TimeUnit.SECONDS)!!

    @Test fun savedOverridesReachProfileMetricsAndEncodedCookie() = runBlocking<Unit> {
        val saved = source().copy(stalkerSerialNumber = "Serial/+=\"Mixed", stalkerDeviceId1 = "ID1 &=Mixed",
            stalkerDeviceId2 = "ID2; injected=Mixed", stalkerSignature = "Sign/+=Mixed")
        api.configurationSaved(saved)
        assertEquals(1, api.liveCategories(saved).size)
        val handshake = next()
        assertEquals("handshake", handshake.requestUrl!!.queryParameter("action"))
        val profile = next()
        val url = profile.requestUrl!!
        assertEquals(saved.stalkerSerialNumber, url.queryParameter("sn"))
        assertEquals(saved.stalkerDeviceId1, url.queryParameter("device_id"))
        assertEquals(saved.stalkerDeviceId2, url.queryParameter("device_id2"))
        assertEquals(saved.stalkerSignature, url.queryParameter("signature"))
        val metrics = Json.parseToJsonElement(url.queryParameter("metrics")!!).jsonObject
        assertEquals(saved.stalkerSerialNumber, metrics["sn"]!!.jsonPrimitive.content)
        assertEquals(saved.stalkerDeviceId1, metrics["uid"]!!.jsonPrimitive.content)
        val cookie = profile.getHeader("Cookie")!!
        assertFalse(cookie.contains("; injected="))
        assertEquals(saved.stalkerDeviceId2, URLDecoder.decode(cookie.substringAfter("adid=").substringBefore(";"), "UTF-8"))
        assertEquals("Bearer synthetic-token", profile.getHeader("Authorization"))
        next()
        api.liveCategories(saved)
        assertEquals("get_genres", next().requestUrl!!.queryParameter("action"))
        assertEquals(4, server.requestCount)
    }

    @Test fun saveInvalidatesSessionWithoutOpeningConnectionsAndStaleSnapshotCannotReconnect() = runBlocking<Unit> {
        val old = source()
        api.liveCategories(old)
        repeat(3) { next() }
        val saved = old.copy(macAddress = "00:1A:79:11:22:33", stalkerRevision = 1)
        api.configurationSaved(saved)
        assertEquals(3, server.requestCount)
        try { api.liveCategories(old); fail("Stale identity must be rejected") } catch (_: StalkerApi.StalkerException) { }
        assertEquals(3, server.requestCount)
        assertEquals("https://stream.example.invalid/synthetic", api.createLink(saved, "synthetic-command"))
        assertEquals("handshake", next().requestUrl!!.queryParameter("action"))
        val profile = next()
        assertEquals(StalkerIdentity.resolve(saved, "challenge").signature, profile.requestUrl!!.queryParameter("signature"))
        next()
        assertEquals(6, server.requestCount)
    }

    @Test fun unexpectedProfilesKeepFirstHandshakeAndCachedSession() = runBlocking<Unit> {
        val profiles = listOf(
            MockResponse().setResponseCode(403),
            MockResponse().setResponseCode(404),
            MockResponse().setResponseCode(204),
            *listOf("", "{broken", "not-json", "null", "[]", "true", "\"unexpected\"", "{}",
                """{"js":null}""", """{"js":[]}""", """{"js":true}""", """{"js":"unexpected"}""",
                """{"js":{}}""", """{"js":{"error":"unexpected"}}""",
                """{"js":{"blocked":false}}""", """{"js":{"blocked":"0"}}""",
                """{"js":{"blocked":{"unexpected":true}}}""")
                .map { MockResponse().setBody(it) }.toTypedArray(),
        )
        profiles.forEachIndexed { index, profile ->
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest) =
                    if (request.requestUrl!!.queryParameter("action") == "get_profile") profile else response(request)
            }
            val saved = source().copy(id = index.toLong() + 100)
            assertEquals(1, api.liveCategories(saved).size)
            assertEquals("handshake", next().requestUrl!!.queryParameter("action"))
            assertEquals("get_profile", next().requestUrl!!.queryParameter("action"))
            assertEquals("get_genres", next().requestUrl!!.queryParameter("action"))
            assertEquals(1, api.liveCategories(saved).size)
            val cached = next()
            assertEquals("get_genres", cached.requestUrl!!.queryParameter("action"))
            assertEquals("Bearer synthetic-token", cached.getHeader("Authorization"))
            assertEquals("/portal.php", cached.requestUrl!!.encodedPath)
            assertEquals((index + 1) * 4, server.requestCount)
        }
    }

    @Test fun explicitBlockedProfileStopsWithoutTryingOtherEndpointsOrIdentities() = runBlocking<Unit> {
        listOf("1", "true", "\"1\"", "\"true\"").forEachIndexed { index, blocked ->
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest) =
                    if (request.requestUrl!!.queryParameter("action") == "get_profile")
                        MockResponse().setBody("""{"js":{"blocked":$blocked}}""") else response(request)
            }
            try { api.liveCategories(source().copy(id = index.toLong() + 200)); fail("Blocked identity must fail") }
            catch (_: StalkerApi.StalkerException) { }
            assertEquals((index + 1) * 2, server.requestCount)
            assertTrue(next().getHeader("Cookie")!!.contains("00%3A1A%3A79%3AAA%3ABB%3ACC"))
            next()
        }
    }

    @Test fun inFlightOldAuthenticationCannotPublishOrRetryAfterSave() = runBlocking<Unit> {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                if (request.requestUrl!!.queryParameter("action") == "get_profile") {
                    entered.countDown()
                    check(release.await(5, TimeUnit.SECONDS))
                }
                return response(request)
            }
        }
        val old = source()
        val pending = async(Dispatchers.Default) { runCatching { api.liveCategories(old) } }
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            api.configurationSaved(old.copy(stalkerRevision = 1, stalkerSignature = "ProviderSignature"))
        } finally { release.countDown() }
        assertTrue(pending.await().exceptionOrNull() is StalkerApi.StalkerException)
        assertEquals(2, server.requestCount)
    }

    @Test fun testingUnsavedDraftDoesNotReplaceSavedCachedSession() = runBlocking<Unit> {
        val saved = source()
        api.liveCategories(saved)
        repeat(3) { next() }
        api.handshakeTest(saved.copy(stalkerSignature = "UnsavedSignature"))
        repeat(2) { next() }
        api.liveCategories(saved)
        val call = next()
        assertEquals("get_genres", call.requestUrl!!.queryParameter("action"))
        assertEquals(6, server.requestCount)
    }
}
