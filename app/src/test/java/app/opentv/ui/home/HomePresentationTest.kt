/* Nuvix modifications Copyright (C) 2026 Nuvix Contributors. GPL-3.0-or-later. */
package app.opentv.ui.home

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class HomePresentationTest {
    @Test fun `every layout and card combination is bounded with increasing sizes`() {
        HomeLayout.entries.forEach { layout ->
            val widths = HomeCardSize.entries.map { homeCardWidth(layout, it) }
            assertThat(widths).isInOrder()
            widths.forEach { assertThat(it).isIn(120..260) }
            HomeCardSize.entries.forEach { size ->
                val preference = HomePresentation(layout = layout, movieSize = size, seriesSize = size, channelSize = size)
                assertThat(HomePresentation.parse(preference.portable())).isEqualTo(preference)
            }
        }
    }
    @Test fun `malformed version unknown values and oversized payloads are rejected`() {
        val defaults = HomePresentation().portable()
        listOf(emptyList(), defaults + "provider_url=secret", defaults.toMutableList().apply { this[0] = "v2" },
            defaults.toMutableList().apply { this[2] = "movie=HUGE" },
            defaults.toMutableList().apply { this[6] = "metadata=yes" }).forEach {
            assertThat(HomePresentation.parse(it)).isNull()
        }
    }
    @Test fun `dynamic backgrounds fall back when artwork is unavailable`() {
        assertThat(backgroundArtwork(HomeBackground.DYNAMIC_ARTWORK, null)).isNull()
        assertThat(backgroundArtwork(HomeBackground.DYNAMIC_ARTWORK, " ")).isNull()
        assertThat(backgroundArtwork(HomeBackground.DEFAULT, "art")).isNull()
        assertThat(backgroundArtwork(HomeBackground.DYNAMIC_ARTWORK, "art")).isEqualTo("art")
    }
    @Test fun `featured choices handle empty content and preserve classic automatic selection`() {
        HomeFeatured.entries.forEach { assertThat(selectHomeFeature(it, false, false, false, false)).isEqualTo(HomeFeatureSource.NONE) }
        assertThat(selectHomeFeature(HomeFeatured.AUTOMATIC, true, true, true, true)).isEqualTo(HomeFeatureSource.RECENT)
        assertThat(selectHomeFeature(HomeFeatured.CONTINUE, true, true, true, true)).isEqualTo(HomeFeatureSource.CONTINUE)
        assertThat(selectHomeFeature(HomeFeatured.FAVOURITE, true, true, true, true)).isEqualTo(HomeFeatureSource.FAVOURITE)
        assertThat(selectHomeFeature(HomeFeatured.RECENT, true, false, true, true)).isEqualTo(HomeFeatureSource.NONE)
        assertThat(selectHomeFeature(HomeFeatured.DISABLED, true, true, true, true)).isEqualTo(HomeFeatureSource.NONE)
    }
}
