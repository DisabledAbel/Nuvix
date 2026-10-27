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
