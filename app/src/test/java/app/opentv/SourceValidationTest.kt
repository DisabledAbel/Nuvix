/* Copyright (C) 2026 Nuvix Contributors. Licensed under GPL-3.0-or-later. */
package app.opentv

import app.opentv.core.ProviderDiagnostics
import app.opentv.data.model.Source
import app.opentv.data.model.SourceKind
import app.opentv.data.repo.SourceRepository
import com.google.common.truth.Truth.assertThat
import java.io.IOException
import org.junit.Assert.assertThrows
import org.junit.Test

/** Regression tests for source validation. */
class SourceValidationTest {
    /** Checks: playlist query credentials remain supported without URL userinfo. */
    @Test fun `playlist query credentials remain supported without URL userinfo`() {
        val value = "https://provider.invalid/get.php?username=viewer&password=private"
        val source = SourceRepository.validatedSource(Source(name = "Playlist", kind = SourceKind.M3U, url = value))
        assertThat(source.url).isEqualTo(value)
    }

    /** Checks: rejects URL userinfo without echoing credentials. */
    @Test fun `rejects URL userinfo without echoing credentials`() {
        val error = assertThrows(IllegalArgumentException::class.java) {
            SourceRepository.validatedSource(Source(name = "Playlist", kind = SourceKind.M3U, url = "https://viewer:private@provider.invalid/list.m3u"))
        }
        assertThat(error.message).doesNotContain("private")
        assertThat(error.message).doesNotContain("viewer")
    }

    /** Checks: rejects non HTTP protocols and multiline header injection. */
    @Test fun `rejects non HTTP protocols and multiline header injection`() {
        assertThrows(IllegalArgumentException::class.java) { SourceRepository.validateHttpUrl("file:///etc/config") }
        assertThrows(IllegalArgumentException::class.java) { SourceRepository.validateHttpUrl("https://provider.invalid/\nsecret") }
        assertThrows(IllegalArgumentException::class.java) {
            SourceRepository.validatedSource(Source(name = "Playlist", kind = SourceKind.M3U, url = "https://provider.invalid/list", userAgent = "Nuvix\r\nAuthorization: private"))
        }
    }

    /** Checks: requires authorized MAC shape without inventing an identity. */
    @Test fun `requires authorized MAC shape without inventing an identity`() {
        assertThrows(IllegalArgumentException::class.java) {
            SourceRepository.validatedSource(Source(name = "Portal", kind = SourceKind.STALKER, url = "https://provider.invalid/c", macAddress = "invalid"))
        }
        val valid = SourceRepository.validatedSource(Source(name = "Portal", kind = SourceKind.STALKER, url = "https://provider.invalid/c", macAddress = "00:1A:79:AA:BB:CC"))
        assertThat(valid.macAddress).isEqualTo("00:1A:79:AA:BB:CC")
    }

    /** Checks: raw network errors cannot expose signed UR Ls in diagnostics. */
    @Test fun `raw network errors cannot expose signed URLs in diagnostics`() {
        val secret = "https://provider.invalid/get.php?username=viewer&password=private"
        assertThat(ProviderDiagnostics.describe(IOException("Failed request $secret"))).doesNotContain("private")
        assertThat(ProviderDiagnostics.describe(IllegalArgumentException("Invalid $secret"))).doesNotContain("viewer")
    }
}
