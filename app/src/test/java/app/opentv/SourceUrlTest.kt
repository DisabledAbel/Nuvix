/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import app.opentv.data.model.SourceKind
import app.opentv.data.repo.SourceRepository
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * URL normalisation, tested against what people actually paste.
 *
 * Providers send a full `get.php` line in a welcome email; users paste that whole line into
 * a box labelled "server address". Rejecting it teaches them nothing — accept it.
 */
class SourceUrlTest {

    @Test
    fun `adds a scheme when missing`() {
        assertThat(SourceRepository.normaliseUrl("example.com:8080", SourceKind.XTREAM))
            .isEqualTo("http://example.com:8080")
    }

    @Test
    fun `strips trailing slashes`() {
        assertThat(SourceRepository.normaliseUrl("http://example.com:8080/", SourceKind.XTREAM))
            .isEqualTo("http://example.com:8080")
    }

    @Test
    fun `strips api paths pasted from a provider email`() {
        val pasted = "http://example.com:8080/get.php?username=u&password=p&type=m3u_plus"

        assertThat(SourceRepository.normaliseUrl(pasted, SourceKind.XTREAM))
            .isEqualTo("http://example.com:8080")
    }

    @Test
    fun `strips player_api paths`() {
        val pasted = "http://example.com:8080/player_api.php?username=u&password=p"

        assertThat(SourceRepository.normaliseUrl(pasted, SourceKind.XTREAM))
            .isEqualTo("http://example.com:8080")
    }

    @Test
    fun `leaves an M3U url completely alone`() {
        // Regression guard. Stripping the path off a playlist URL destroys it — the very
        // thing that makes the URL useful for an Xtream source breaks an M3U one.
        val playlist = "http://example.com:8080/get.php?username=u&password=p&type=m3u_plus"

        assertThat(SourceRepository.normaliseUrl(playlist, SourceKind.M3U)).isEqualTo(playlist)
    }

    @Test
    fun `preserves https`() {
        assertThat(SourceRepository.normaliseUrl("https://example.com", SourceKind.XTREAM))
            .isEqualTo("https://example.com")
    }

    @Test
    fun `an empty string stays empty`() {
        assertThat(SourceRepository.normaliseUrl("   ", SourceKind.XTREAM)).isEmpty()
    }
}
