/* Nuvix Contributors. GPL-3.0-or-later. */
package app.opentv

import app.opentv.pairing.CloudManagerProtocol
import app.opentv.pairing.ManagerServer
import java.io.ByteArrayInputStream
import org.junit.Assert.*
import org.junit.Test

class ManagerHttpTest {
    /** Real short reads, including boundaries inside multi-byte UTF-8 characters. */
    private class Fragmented(bytes: ByteArray, private val chunk: Int) : ByteArrayInputStream(bytes) {
        override fun read(bytes: ByteArray, offset: Int, length: Int): Int =
            super.read(bytes, offset, minOf(length, chunk))
    }

    @Test fun fragmentedUtf8BodyConsumesBytesRatherThanCharacters() {
        val body = """{"source":1,"revision":"42","ids":[1,2],"action":"hide","note":"Café 📺 東京"}"""
        val bytes = body.toByteArray(Charsets.UTF_8)
        for (chunk in listOf(1, 2, 3, 7)) {
            val input = Fragmented(bytes + "NEXT".toByteArray(), chunk)
            assertEquals(body, ManagerServer.readBody(input, mapOf("content-length" to bytes.size.toString())))
            assertEquals('N'.code, input.read())
        }
    }

    @Test fun headersAndBodyShareBufferWithoutDroppingReadAheadBytes() {
        val body = """{"name":"Café 📺"}"""
        val bytes = body.toByteArray(Charsets.UTF_8)
        val request = "POST /channel HTTP/1.1\r\nContent-Length: ${bytes.size}\r\n\r\n"
        val input = Fragmented(request.toByteArray() + bytes, 3).buffered()
        assertEquals("POST /channel HTTP/1.1", ManagerServer.readHttpLine(input))
        assertEquals("Content-Length: ${bytes.size}", ManagerServer.readHttpLine(input))
        assertEquals("", ManagerServer.readHttpLine(input))
        assertEquals(body, ManagerServer.readBody(input, mapOf("content-length" to bytes.size.toString())))
        assertNull(ManagerServer.readHttpLine(input))
    }

    @Test fun earlyEndReturnsAllReceivedBytesAndZeroLengthConsumesNothing() {
        val body = "Café"
        val input = Fragmented(body.toByteArray(), 1)
        assertEquals(body, ManagerServer.readBody(input, mapOf("content-length" to "100")))
        val untouched = ByteArrayInputStream("next".toByteArray())
        assertEquals("", ManagerServer.readBody(untouched, mapOf("content-length" to "0")))
        assertEquals('n'.code, untouched.read())
    }

    @Test fun byteLimitIsEnforcedBeforeReadingOrAllocatingTheBody() {
        for (length in listOf(-1, CloudManagerProtocol.MAX_REQUEST_BYTES + 1)) {
            val input = ByteArrayInputStream("unchanged".toByteArray())
            assertTrue(runCatching { ManagerServer.readBody(input, mapOf("content-length" to "$length")) }.exceptionOrNull() is IllegalArgumentException)
            assertEquals('u'.code, input.read())
        }
        val bytes = ByteArray(CloudManagerProtocol.MAX_REQUEST_BYTES) { 'a'.code.toByte() }
        assertEquals(bytes.size, ManagerServer.readBody(Fragmented(bytes, 17), mapOf("content-length" to bytes.size.toString())).length)
    }
}
