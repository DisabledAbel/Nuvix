/* Nuvix modifications Copyright (C) 2026 Nuvix Contributors. GPL-3.0-or-later. */
package app.opentv.metadata

import android.app.Application
import app.opentv.data.model.Series
import app.opentv.data.remote.*
import app.opentv.data.repo.TvSeriesDetail
import app.opentv.ui.vod.plainSummary
import app.opentv.ui.vod.seriesMeta
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28])
class TvMetadataPresentationTest {
    @Test fun `TVmaze and IMDb ratings have distinct labels and do not replace provider score`() {
        val series = Series(sourceId=1,seriesId="provider-id",name="Show",categoryId=null,
            posterUrl=null,rating=6.0,year=2024,plot=null)
        val tvmaze = TvmazeDetails(TvShow(1,"Show","tt1234567",2024,null,null,null,9.0),
            null,emptyList(),emptyList(),emptyList(),emptyList())
        val imdb = ImdbTvMeta("tt1234567",null,null,null,null,8.0,2024)
        val labels = seriesMeta(series,TvSeriesDetail(series,tvmaze,imdb,7.0))
        assertThat(labels).contains("TVmaze ★ 9.0")
        assertThat(labels).contains("IMDb ★ 8.0")
        assertThat(labels).contains("Provider ★ 6.0")
        assertThat(labels).contains("TMDb ★ 7.0")
        assertThat(seriesMeta(series,null)).doesNotContain("IMDb")
    }
    @Test fun `HTML summaries are displayed as text with decoded entities`() {
        assertThat(plainSummary("<p>A &amp; B</p><p><b>Episode</b></p>")).isEqualTo("A & B\nEpisode")
        assertThat(plainSummary(null)).isEmpty()
    }
}
