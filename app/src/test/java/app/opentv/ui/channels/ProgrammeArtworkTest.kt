/* Nuvix modifications Copyright (C) 2026 Nuvix Contributors. GPL-3.0-or-later. */
package app.opentv.ui.channels

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ProgrammeArtworkTest {
    @Test fun missingOrMalformedArtworkUsesPlaceholder() {
        listOf(null, "", " ", "https://", "not an image", "file:///private/art.jpg", "data:image/png;base64,AA", "javascript:alert(1)")
            .forEach { assertThat(programmeArtworkModel(it)).isNull() }
    }
    @Test fun suppliedHttpArtworkUsesExistingImageLoader() {
        assertThat(programmeArtworkModel(" https://catalog.example/art.jpg ")).isEqualTo("https://catalog.example/art.jpg")
        assertThat(programmeArtworkModel("http://catalog.example/art.jpg")).isEqualTo("http://catalog.example/art.jpg")
    }
}
