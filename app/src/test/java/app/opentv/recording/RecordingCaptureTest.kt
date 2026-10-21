/* Copyright (C) 2026 Nuvix Contributors. GPL-3.0-or-later. */
package app.opentv.recording

import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream
import okio.Buffer

/** Regression tests for recording capture. */
class RecordingCaptureTest {
    /** Checks: storage Failure Is Terminal And Does Not Open Another Provider Connection. */
    @Test fun storageFailureIsTerminalAndDoesNotOpenAnotherProviderConnection() = runBlocking {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody(Buffer().write(transportBytes(1))))
        server.start()
        try {
            var bytes = -1L
            val brokenDrive = object : OutputStream() { override fun write(value: Int) { throw IOException("unplugged USB") } }
            val result = RecordingCapture(OkHttpClient(), retryDelayMillis = 0).copy(
                server.url("/live.ts").toString(), "Nuvix", brokenDrive, 0,
                {}, { bytes = it }, {})
            assertEquals("Recording destination is unavailable or full", result.error)
            assertEquals(0L, bytes)
            assertEquals(1, server.requestCount)
        } finally { server.shutdown() }
    }

    /** Checks: exhausted Reconnects Preserve Bytes And Report Incomplete Capture. */
    @Test fun exhaustedReconnectsPreserveBytesAndReportIncompleteCapture() = runBlocking {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody(Buffer().write(transportBytes(2))))
        repeat(2) { server.enqueue(MockResponse().setBody("")) }
        server.start()
        try {
            val output = ByteArrayOutputStream()
            var starts = 0
            val result = RecordingCapture(OkHttpClient(), retryDelayMillis = 0, maxEmptyConnections = 2).copy(
                server.url("/live.ts").toString(), "Nuvix", output, 0, {}, {}, {}, onStarted = { starts++ })
            assertArrayEquals(transportBytes(2), output.toByteArray())
            assertEquals(output.size().toLong(), result.bytes)
            assertNotNull("A truncated stream must not be reported completed", result.error)
            assertEquals(3, server.requestCount)
            assertEquals(1, starts)
        } finally { server.shutdown() }
    }

    /** Checks: playlist Is Rejected Rather Than Saved As Video. */
    @Test fun playlistIsRejectedRatherThanSavedAsVideo() = runBlocking {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody("#EXTM3U\n#EXT-X-KEY:METHOD=SAMPLE-AES"))
        server.start()
        try {
            val output = ByteArrayOutputStream()
            val result = RecordingCapture(OkHttpClient()).copy(server.url("/hls").toString(), "Nuvix", output, 0, {}, {}, {})
            assertEquals(0, output.size())
            assertNotNull(result.error)
            assertEquals(1, server.requestCount)
        } finally { server.shutdown() }
    }

    /** Checks: independent Captures Keep Their Own Destination And Deadline. */
    @Test fun independentCapturesKeepTheirOwnDestinationAndDeadline() = runBlocking {
        val servers = listOf(MockWebServer(), MockWebServer())
        servers.forEachIndexed { index, server -> server.enqueue(MockResponse().setBody(Buffer().write(transportBytes(index)))); server.start() }
        try {
            val captures = servers.mapIndexed { index, server -> async(Dispatchers.IO) {
                var now = 0L
                val output = ByteArrayOutputStream()
                val result = RecordingCapture(OkHttpClient(), nowMillis = { now }).copy(
                    server.url("/live.ts").toString(), "Nuvix", output, 1_000, {}, {}, { now = 1_000 })
                assertNull(result.error)
                assertArrayEquals(transportBytes(index), output.toByteArray())
                result.bytes
            } }
            assertEquals(listOf(564L, 564L), captures.map { it.await() })
            assertTrue(servers.all { it.requestCount == 1 })
        } finally { servers.forEach { it.shutdown() } }
    }

    /** Checks: fragmented Playlist And Html Success Bodies Are Not Playable Recordings. */
    @Test fun fragmentedPlaylistAndHtmlSuccessBodiesAreNotPlayableRecordings() = runBlocking {
        val server = MockWebServer()
        server.enqueue(MockResponse().setChunkedBody("#EXTM3U\n#EXT-X-TARGETDURATION:10", 1))
        server.enqueue(MockResponse().setHeader("Content-Type", "text/html").setBody("<html>Login expired</html>"))
        server.start()
        try {
            repeat(2) {
                val output = ByteArrayOutputStream()
                val result = RecordingCapture(OkHttpClient()).copy(server.url("/live.ts").toString(), "Nuvix", output, 0, {}, {}, {},
                    onStarted = { fail("Invalid media must not announce a started recording") })
                assertEquals(0L, result.bytes)
                assertNotNull(result.error)
            }
            assertEquals(2, server.requestCount)
        } finally { server.shutdown() }
    }

    /** Creates synthetic transport-stream bytes for deterministic recording capture assertions. */
    /** A live deadline revision never replaces the already-open transport or output. */
    @Test fun extensionKeepsSameHttpRequestAndGrowingOutputPastOldEnd() = runBlocking {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody(Buffer().write(transportBytes(1))))
        server.start()
        try {
            var row = app.opentv.data.model.Recording(id = 1, channelId = 1, sourceId = 1,
                channelName = "Game", title = "Game", filePath = "/fake.ts", streamUrl = server.url("/live.ts").toString(),
                status = app.opentv.data.model.RecordingStatus.RECORDING, scheduledEndMillis = 1_000)
            var now = 900L
            val wrote = java.util.concurrent.CountDownLatch(1)
            val release = java.util.concurrent.CountDownLatch(1)
            var captureCall: okhttp3.Call? = null
            val output = object : ByteArrayOutputStream() {
                override fun write(bytes: ByteArray, offset: Int, length: Int) {
                    super.write(bytes, offset, length)
                    // Commit while this response is still open, then cross the obsolete end.
                    row = row.copy(scheduledEndMillis = 301_000, durationRevision = 1)
                    now = 1_001
                    assertEquals(1, server.requestCount)
                    assertFalse(captureCall!!.isCanceled())
                    wrote.countDown()
                    check(release.await(5, java.util.concurrent.TimeUnit.SECONDS))
                }
            }
            val copy = launch(Dispatchers.IO) {
                RecordingCapture(OkHttpClient()).copy(row.streamUrl, "Nuvix", output, 0,
                    onCall = { if (it != null) captureCall = it }, onProgress = {}, checkStorage = {})
            }
            // Polling has an injectable deterministic clock/wait, just like the service's watcher.
            withContext(Dispatchers.IO) {
                check(wrote.await(5, java.util.concurrent.TimeUnit.SECONDS))
            }
            RecordingDeadlineWatcher(read = { row }, claim = { rev, at ->
                rev == row.durationRevision && row.scheduledEndMillis <= at
            }, elapsed = { captureCall?.cancel(); copy.cancel(); release.countDown() }, now = { now },
                wait = { now = 301_000 }).run()
            copy.join()
            assertArrayEquals(transportBytes(1), output.toByteArray())
            assertEquals(1, server.requestCount)
        } finally { server.shutdown() }
    }

    @Test fun policyIsRecheckedWhenUsefulMediaArrivesBeforeAnyOutputWrite() = runBlocking {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody(Buffer().write(transportBytes(1))))
        server.start()
        try {
            var clock = 1_000_000L
            val row = app.opentv.data.model.Recording(channelId = 1, sourceId = 1, channelName = "News", title = "News",
                filePath = "/never-created.ts", streamUrl = server.url("/live.ts").toString(),
                status = app.opentv.data.model.RecordingStatus.PREPARING, programmeStartMillis = clock,
                programmeEndMillis = clock + 3_600_000, scheduledStartMillis = clock - 120_000, scheduledEndMillis = clock + 3_900_000)
            var opened = 0; var started = 0
            val out = ByteArrayOutputStream()
            val result = RecordingCapture(OkHttpClient(), nowMillis = { clock }).copy(row.streamUrl, "Nuvix", out, 0,
                onCall = { if (it != null) clock += 6_000 }, onProgress = {}, checkStorage = {}, onStarted = { started++ },
                beforeFirstWrite = {
                    val d = RecordingLateStart.evaluate(row, clock, LateStartOptions(LateStartPolicy.SKIP_IF_LATE))
                    if (d.action == RecordingLateStart.Decision.Action.CAPTURE) { opened++; true } else false
                })
            assertTrue(result.startRejected); assertEquals(0L, result.bytes)
            assertEquals(0, opened); assertEquals(0, started); assertEquals(0, out.size())
        } finally { server.shutdown() }
    }

    @Test fun reconnectAfterOnTimeUsefulCaptureDoesNotRunLateStartGateAgain() = runBlocking {
        val server = MockWebServer()
        repeat(2) { server.enqueue(MockResponse().setBody(Buffer().write(transportBytes(it)))) }
        server.start()
        try {
            var clock = 1_000_000L
            var gates = 0; var writes = 0
            val out = object : ByteArrayOutputStream() {
                override fun write(bytes: ByteArray, offset: Int, length: Int) {
                    super.write(bytes, offset, length)
                    clock = if (++writes == 1) 1_006_000L else 1_100_000L
                }
            }
            val result = RecordingCapture(OkHttpClient(), nowMillis = { clock }, retryDelayMillis = 0).copy(
                server.url("/live.ts").toString(), "Nuvix", out, 1_100_000,
                {}, {}, {}, beforeFirstWrite = { gates++; true })
            assertEquals(1, gates); assertEquals(1128L, result.bytes)
            assertFalse(result.startRejected); assertEquals(2, server.requestCount)
        } finally { server.shutdown() }
    }

    private fun transportBytes(marker: Int) = ByteArray(188 * 3) { offset -> if (offset % 188 == 0) 0x47 else marker.toByte() }

    /** Checks: already Expired Window Never Opens Http Connection. */
    @Test fun alreadyExpiredWindowNeverOpensHttpConnection() = runBlocking {
        val output = ByteArrayOutputStream()
        val client = okhttp3.Call.Factory { throw AssertionError("Expired recording opened a connection") }
        val result = RecordingCapture(client, nowMillis = { 2_000 }).copy(
            "https://example.com/live.ts", "Nuvix", output, 1_000, {}, {}, {})
        assertEquals(0L, result.bytes)
    }
}
