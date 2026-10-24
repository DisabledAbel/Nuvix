/* Copyright (C) 2026 Nuvix Contributors. GPL-3.0-or-later. */
package app.opentv.data.model

import java.security.MessageDigest
import java.util.Locale

/** Provider identity rules. Null overrides always use the original MAG derivation. */
object StalkerIdentity {
    const val MAC_WARNING = "Changing your portal MAC may require your IPTV provider to reauthorize your device and could temporarily interrupt access."
    const val MAX_VALUE_LENGTH = 1024

    fun canonicalMac(value: String): String {
        require(value.matches(Regex("(?:[0-9a-fA-F]{2}:){5}[0-9a-fA-F]{2}"))) {
            "Enter the MAC address authorized by your portal, using six colon-separated pairs."
        }
        return value.uppercase(Locale.ROOT)
    }

    /** Values are opaque protocol strings: preserve casing, spaces and provider encoding. */
    fun validateOverride(value: String?) {
        require(value == null || (value.isNotBlank() && value.length <= MAX_VALUE_LENGTH &&
            validText(value))) {
            "Custom identity must contain 1–1024 characters without control characters."
        }
    }

    private fun validText(value: String): Boolean {
        var index = 0
        while (index < value.length) {
            val char = value[index++]
            if (Character.isISOControl(char) || char == '\u2028' || char == '\u2029' || Character.isLowSurrogate(char)) return false
            if (Character.isHighSurrogate(char)) {
                if (index == value.length || !Character.isLowSurrogate(value[index++])) return false
            }
        }
        return true
    }

    fun validate(source: Source) {
        canonicalMac(source.macAddress.orEmpty())
        listOf(source.stalkerSerialNumber, source.stalkerDeviceId1, source.stalkerDeviceId2, source.stalkerSignature)
            .forEach(::validateOverride)
    }

    class Resolved(val sn: String, val deviceId: String, val deviceId2: String, val signature: String) {
        override fun toString(): String = "StalkerIdentity(redacted)"
    }

    fun resolve(source: Source, random: String = ""): Resolved {
        validate(source)
        // Deliberately use the saved MAC's original casing for legacy providers. A user-confirmed
        // edit stores uppercase, but opening/migrating an existing provider must not change hashes.
        val mac = source.macAddress.orEmpty()
        val automaticSn = hashUpper("MD5", mac).take(13)
        val automaticId = hashUpper("SHA-256", mac.uppercase(Locale.ROOT))
        val sn = source.stalkerSerialNumber ?: automaticSn
        return Resolved(
            sn,
            source.stalkerDeviceId1 ?: automaticId,
            source.stalkerDeviceId2 ?: automaticId,
            source.stalkerSignature ?: hashUpper("SHA-256", sn + mac + random),
        )
    }

    fun hashUpper(algorithm: String, value: String): String =
        MessageDigest.getInstance(algorithm).digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }.uppercase(Locale.ROOT)
}
