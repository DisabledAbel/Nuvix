/* Copyright (C) 2026 Nuvix Contributors. GPL-3.0-or-later. */
package app.opentv.stalker

import app.opentv.core.ProviderDiagnostics
import app.opentv.data.model.*
import app.opentv.data.repo.SourceRepository
import app.opentv.data.remote.StalkerApi
import org.junit.Assert.*
import org.junit.Test

class StalkerIdentityTest {
    private val source = Source(id = 7, name = "Synthetic", kind = SourceKind.STALKER,
        url = "https://portal.example.invalid/c", macAddress = "00:1a:79:aa:bb:cc")

    @Test fun canonicalMacUsesSixOctetsAndUppercase() {
        assertEquals("00:1A:79:AA:BB:CC", StalkerIdentity.canonicalMac(source.macAddress!!))
        assertEquals("00:1A:79:AA:BB:CC", SourceRepository.validatedSource(source).macAddress)
        listOf("", "00:1A:79:AA:BB", "00-1A-79-AA-BB-CC", "00:1A:79:AA:BB:GG", "000:1A:79:AA:BB:CC",
            " 00:1A:79:AA:BB:CC", "00:1A:79:AA:BB:CC\r\n").forEach {
            assertThrows(IllegalArgumentException::class.java) { StalkerIdentity.canonicalMac(it) }
        }
    }

    @Test fun legacyAutomaticAlgorithmsAndCasingRemainExact() {
        val resolved = StalkerIdentity.resolve(source, "challenge")
        assertEquals("64D08FE7B8030", resolved.sn)
        assertEquals("21DA59C248805FDF0F36FA2C4CA4569E10D1F80268D8104C7AF8BB776D657ED8", resolved.deviceId)
        assertEquals(resolved.deviceId, resolved.deviceId2)
        assertEquals("1E9ED9CA0B3B9FDF70F88DF190007FCFE90FCADF49F7D76CB075F7E54DD576DF", resolved.signature)
    }

    @Test fun eachOverrideIsIndependentAndSurvivesMacChange() {
        val changed = source.copy(macAddress = "00:1A:79:11:22:33")
        val custom = source.copy(stalkerSerialNumber = "Serial-a /+=", stalkerDeviceId1 = "Device-1.Mixed",
            stalkerDeviceId2 = "Device-2.Mixed", stalkerSignature = "Signature/+=Mixed")
        val resolved = StalkerIdentity.resolve(custom.copy(macAddress = changed.macAddress), "new-challenge")
        assertEquals(custom.stalkerSerialNumber, resolved.sn)
        assertEquals(custom.stalkerDeviceId1, resolved.deviceId)
        assertEquals(custom.stalkerDeviceId2, resolved.deviceId2)
        assertEquals(custom.stalkerSignature, resolved.signature)
        val mixed = source.copy(stalkerDeviceId2 = "keep-custom")
        val before = StalkerIdentity.resolve(mixed, "challenge")
        val after = StalkerIdentity.resolve(mixed.copy(macAddress = changed.macAddress), "challenge")
        assertNotEquals(before.sn, after.sn)
        assertNotEquals(before.deviceId, after.deviceId)
        assertNotEquals(before.signature, after.signature)
        assertEquals(before.deviceId2, after.deviceId2)
    }

    @Test fun resettingEachOverrideOnlyRestoresItsOwnAutomaticValue() {
        val custom = source.copy(stalkerSerialNumber = "Serial", stalkerDeviceId1 = "ID1",
            stalkerDeviceId2 = "ID2", stalkerSignature = "Signature")
        val automatic = StalkerIdentity.resolve(source)
        assertEquals(automatic.sn, StalkerIdentity.resolve(custom.copy(stalkerSerialNumber = null)).sn)
        assertEquals("ID1", StalkerIdentity.resolve(custom.copy(stalkerSerialNumber = null)).deviceId)
        assertEquals(automatic.deviceId, StalkerIdentity.resolve(custom.copy(stalkerDeviceId1 = null)).deviceId)
        assertEquals(automatic.deviceId2, StalkerIdentity.resolve(custom.copy(stalkerDeviceId2 = null)).deviceId2)
        assertEquals(StalkerIdentity.resolve(source.copy(stalkerSerialNumber = "Serial")).signature,
            StalkerIdentity.resolve(custom.copy(stalkerSignature = null)).signature)
    }

    @Test fun valuesAreOpaqueBoundedAndNeverEchoedInErrors() {
        StalkerIdentity.validateOverride("Provider /+=;\"héllo😀")
        StalkerIdentity.validateOverride("a".repeat(1024))
        listOf("", "  ", "private\r\nCookie: injected", "private\u0000", "private\u007f", "private\u0085", "private\uD800", "private\uDC00", "a".repeat(1025)).forEach {
            val error = assertThrows(IllegalArgumentException::class.java) { StalkerIdentity.validateOverride(it) }
            assertFalse(error.message!!.contains("private"))
        }
        assertFalse(source.copy(stalkerSignature = "secret-value", password = "secret-password").toString().contains("secret"))
        assertEquals("StalkerIdentity(redacted)", StalkerIdentity.resolve(source).toString())
        assertFalse(ProviderDiagnostics.describe(StalkerApi.StalkerException("secret-value")).contains("secret-value"))
    }
}
