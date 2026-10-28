/* Nuvix Contributors. GPL-3.0-or-later. */
package app.opentv

import app.opentv.pairing.CloudManagerProtocol as Protocol
import org.junit.Assert.*
import org.junit.Test

class CloudManagerProtocolTest {
    @Test fun acceptsOnlyHttpsOriginsWithoutCredentialsOrPaths() {
        assertEquals("https://manager.example", Protocol.origin(" https://MANAGER.example/ "))
        for (bad in listOf("http://manager.example", "https://u:p@manager.example", "https://manager.example/api", "https://manager.example/?token=a", "https://manager.example/#a", "https://manager.example:8080")) {
            assertNull(bad, Protocol.origin(bad))
        }
    }

    @Test fun rejectsProxyAndUnrecognizedOperations() {
        assertTrue(Protocol.allowed("GET", "/meta"))
        assertTrue(Protocol.allowed("GET", "/channels?cat=local%20news&source=2"))
        assertTrue(Protocol.allowed("POST", "/source/test"))
        for (bad in listOf("/", "https://evil.example", "//evil.example/meta", "/meta?token=x", "/channels", "/channels?cat=x&t=secret", "/%6deta", "/meta#fragment")) {
            assertFalse(bad, Protocol.allowed("GET", bad))
        }
        assertFalse(Protocol.allowed("DELETE", "/channel"))
    }

    @Test fun paginatedCommandsHaveBoundedUnambiguousParameters() {
        assertTrue(Protocol.allowed("GET", "/channels/page?source=1&limit=100&offset=100&q=Custom&revision=42"))
        assertTrue(Protocol.allowed("POST", "/channels/bulk"))
        assertTrue(Protocol.allowed("POST", "/channels/state"))
        assertTrue(Protocol.allowed("POST", "/channels/move"))
        for (query in listOf("source=1&limit=101", "source=1&limit=0", "source=1&offset=-1", "source=1&offset=1000001",
            "source=1&source=2", "source=1&q=" + "x".repeat(121), "source=1&cat=", "source=1&revision=bad", "cat=only")) {
            assertFalse(query, Protocol.allowed("GET", "/channels/page?$query"))
        }
    }

    @Test fun pairingCodesAndCredentialsAreIndependentAndUnambiguous() {
        val codes = (1..100).map { Protocol.newCode() }
        assertEquals(100, codes.toSet().size)
        assertTrue(codes.all { it.matches(Regex("[23456789ABCDEFGHJKLMNPQRSTUVWXYZ]{12}")) })
        assertEquals("ABCD-EFGH-2345", Protocol.displayCode("ABCDEFGH2345"))
        val tokens = (1..100).map { Protocol.newToken() }
        assertEquals(100, tokens.toSet().size)
        assertTrue(tokens.all { it.matches(Regex("[a-f0-9]{64}")) })
    }
}
