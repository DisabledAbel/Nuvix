/* Nuvix modifications Copyright (C) 2026 Nuvix Contributors. GPL-3.0-or-later. */
package app.opentv.metadata

import app.opentv.cloud.CloudIdentity
import app.opentv.data.model.Episode
import app.opentv.data.model.Series
import app.opentv.data.remote.*
import app.opentv.data.repo.*
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Regression tests for tv metadata repository. */
class TvMetadataRepositoryTest {
    @get:Rule val temp = TemporaryFolder()
    /** Builds the synthetic provider fixture used by the test. */
    private fun provider() = Series(42, 5, "provider-series", "Synthetic Show (2024)", "category",
        "provider poster", 6.0, 2024, "provider plot", true, 123L,
        "provider background", "provider cast", "provider genre", "123")
    /** Builds TVmaze metadata used as the primary series display source. */
    private fun primary() = TvmazeDetails(TvShow(7,"Synthetic Show","tt1234567",2024,
        "TVmaze plot","TVmaze genre","TVmaze poster",9.0), "TVmaze background", emptyList(), emptyList(),
        listOf(TvCast(TvPerson(10,"TVmaze actor",null,null,null,null), "Character", null)), emptyList())
    /** Builds IMDb metadata used as the secondary series display source. */
    private fun secondary() = ImdbTvMeta("tt1234567", "IMDb plot", "IMDb poster", "IMDb genre", "IMDb cast", 8.0, 2024)
    /** Builds TMDB metadata used when higher-priority series fields are absent. */
    private fun fallback() = TmdbMeta(tmdbId="999", posterUrl="TMDb poster", backdropUrl="TMDb background",
        overview="TMDb plot", cast="TMDb cast", genre="TMDb genre", rating=7.0)

    /** Checks: T Vmaze wins display fields while provider identity favorite rating and cloud key survive. */
    @Test fun `TVmaze wins display fields while provider identity favorite rating and cloud key survive`() {
        val provider = provider()
        val result = mergeTvMetadata(provider, primary(), secondary(), fallback())
        assertThat(result.plot).isEqualTo("TVmaze plot")
        assertThat(result.posterUrl).isEqualTo("TVmaze poster")
        assertThat(result.backdropUrl).isEqualTo("TVmaze background")
        assertThat(result.genre).isEqualTo("TVmaze genre")
        assertThat(result.cast).isEqualTo("TVmaze actor")
        assertThat(result.copy(plot=provider.plot,posterUrl=provider.posterUrl,backdropUrl=provider.backdropUrl,
            genre=provider.genre,cast=provider.cast)).isEqualTo(provider)
        assertThat(CloudIdentity.media("series", result.name,result.year,result.tmdbId))
            .isEqualTo(CloudIdentity.media("series",provider.name,provider.year,provider.tmdbId))
        // Original provider snapshot is immutable and not overwritten by enrichment.
        assertThat(provider.plot).isEqualTo("provider plot")
    }
    /** Checks: IM Db is secondary and provider fills gaps before optional TM Db. */
    @Test fun `IMDb is secondary and provider fills gaps before optional TMDb`() {
        val provider = provider()
        val result = mergeTvMetadata(provider, null, secondary(), fallback())
        assertThat(result.plot).isEqualTo("IMDb plot")
        assertThat(result.backdropUrl).isEqualTo("provider background")
        assertThat(mergeTvMetadata(provider, null, null, fallback())).isEqualTo(provider)
        assertThat(mergeTvMetadata(provider.copy(plot=" ",backdropUrl=null),null,null,fallback()).plot).isEqualTo("TMDb plot")
    }
    /** Checks: sparse T Vmaze data retains provider metadata and never relabels rating. */
    @Test fun `sparse TVmaze data retains provider metadata and never relabels rating`() {
        val sparse = primary().copy(show=primary().show.copy(summary=null,genres=null,poster=null),
            background=null,cast=emptyList())
        assertThat(mergeTvMetadata(provider(),sparse,null,null)).isEqualTo(provider())
    }
    /** Builds a synthetic provider episode with the requested sequence information. */
    private fun episode(season: Int=1, number: Int=1) = Episode(55,5,"provider-series","provider-episode",
        season,number,"Provider title","Provider plot",1200,"Provider still","https://provider.invalid/stream")
    /** Builds synthetic TVmaze episode metadata for provider-episode matching. */
    private fun meta(id: Int=100, number: Int?=1) = TvEpisode(id,1,number,"TVmaze title","TVmaze plot","2024-01-01","TVmaze still",30)
    /** Checks: metadata changes presentation but preserves exact playback object. */
    @Test fun `metadata changes presentation but preserves exact playback object`() {
        val provider = episode()
        val rows = tvEpisodeListings(listOf(provider),listOf(meta(),meta(101,2),meta(102,null)))
        assertThat(rows).hasSize(3)
        assertThat(rows.first().provider).isSameInstanceAs(provider)
        assertThat(rows.first().title).isEqualTo("TVmaze title")
        assertThat(rows.first().image).isEqualTo("TVmaze still")
        assertThat(rows.first().provider!!.streamUrl).isEqualTo("https://provider.invalid/stream")
        assertThat(rows.drop(1).all { it.provider == null }).isTrue()
    }
    /** Checks: ambiguous numbered metadata and specials never attach to a stream. */
    @Test fun `ambiguous numbered metadata and specials never attach to a stream`() {
        val rows = tvEpisodeListings(listOf(episode()),listOf(meta(),meta(101),meta(102,null)))
        assertThat(rows).hasSize(4)
        assertThat(rows.first().metadata).isNull()
        assertThat(rows.first().title).isEqualTo("Provider title")
    }
    /** Checks: season is part of the episode match and unknown provider episodes remain playable. */
    @Test fun `season is part of the episode match and unknown provider episodes remain playable`() {
        val rows = tvEpisodeListings(listOf(episode(2,1)),listOf(meta()))
        assertThat(rows).hasSize(2)
        assertThat(rows.single { it.provider != null }.metadata).isNull()
    }
    /** Checks: pipeline strips trailing year and only calls TM Db for remaining gaps. */
    @Test fun `pipeline strips trailing year and only calls TMDb for remaining gaps`() = runBlocking {
        MockWebServer().use { tv -> MockWebServer().use { imdb ->
            tv.start(); imdb.start()
            tv.enqueue(MockResponse().setBody("""[{"show":{"id":7,"name":"Synthetic Show","premiered":"2024-01-01","summary":"Plot","genres":["Drama"],"image":{"original":"poster"}}}]"""))
            tv.enqueue(MockResponse().setBody("""[{"type":"background","resolutions":{"original":{"url":"background"}}}]"""))
            repeat(4) { tv.enqueue(MockResponse().setBody("[]")) }
            imdb.enqueue(MockResponse().setResponseCode(503))
            val repo = TvMetadataRepository(
                TvmazeClient(MetadataHttp(OkHttpClient(),temp.newFolder(),spacingMillis=0),tv.url("/")),
                ImdbApiClient(MetadataHttp(OkHttpClient(),temp.newFolder(),spacingMillis=0),imdb.url("/")))
            var fallbackCalls = 0
            val result = repo.detail(provider(),"Synthetic Show (2024)",null) { fallbackCalls++; fallback() }
            assertThat(result.tvmaze?.show?.id).isEqualTo(7)
            assertThat(fallbackCalls).isEqualTo(0)
            assertThat(tv.takeRequest().path).isEqualTo("/search/shows?q=Synthetic%20Show")
        } }
    }
    /** Checks: outages preserve provider rows and invoke existing fallback. */
    @Test fun `outages preserve provider rows and invoke existing fallback`() = runBlocking {
        MockWebServer().use { tv -> MockWebServer().use { imdb ->
            tv.start(); imdb.start()
            tv.enqueue(MockResponse().setResponseCode(503)); imdb.enqueue(MockResponse().setResponseCode(503))
            val repo = TvMetadataRepository(
                TvmazeClient(MetadataHttp(OkHttpClient(),temp.newFolder()),tv.url("/")),
                ImdbApiClient(MetadataHttp(OkHttpClient(),temp.newFolder()),imdb.url("/")))
            var calls = 0
            val sparse = provider().copy(plot=null)
            val result = repo.detail(sparse,"Synthetic Show",null) { calls++; fallback() }
            assertThat(calls).isEqualTo(1)
            assertThat(result.series.plot).isEqualTo("TMDb plot")
            assertThat(result.series.seriesId).isEqualTo(sparse.seriesId)
            val failedFallback = repo.detail(sparse,"Synthetic Show",null) { error("Malformed fallback") }
            assertThat(failedFallback.series).isEqualTo(sparse)
            var cancelled = false
            try {
                repo.detail(sparse,"Synthetic Show",null) { throw kotlinx.coroutines.CancellationException() }
            } catch (_: kotlinx.coroutines.CancellationException) { cancelled = true }
            assertThat(cancelled).isTrue()
        } }
    }
    /** Checks: secondary IM Db match resolves a T Vmaze title miss by exact ID. */
    @Test fun `secondary IMDb match resolves a TVmaze title miss by exact ID`() = runBlocking {
        MockWebServer().use { tv -> MockWebServer().use { imdb ->
            tv.start(); imdb.start()
            tv.enqueue(MockResponse().setBody("[]"))
            tv.enqueue(MockResponse().setBody("""{"id":7,"name":"Synthetic Show","externals":{"imdb":"tt1234567"}}"""))
            repeat(5) { tv.enqueue(MockResponse().setBody("[]")) }
            imdb.enqueue(MockResponse().setBody("""{"titles":[{"id":"tt1234567","type":"TV_SERIES","primaryTitle":"Synthetic Show","startYear":2024}]}"""))
            imdb.enqueue(MockResponse().setBody("""{"id":"tt1234567","type":"TV_SERIES","plot":"IMDb plot","rating":{"aggregateRating":8.2}}"""))
            val repo = TvMetadataRepository(
                TvmazeClient(MetadataHttp(OkHttpClient(),temp.newFolder(),spacingMillis=0),tv.url("/")),
                ImdbApiClient(MetadataHttp(OkHttpClient(),temp.newFolder(),spacingMillis=0),imdb.url("/")))
            val result = repo.detail(provider(),"Synthetic Show",null) { null }
            assertThat(result.tvmaze?.show?.id).isEqualTo(7)
            assertThat(result.imdb?.rating).isEqualTo(8.2)
            assertThat(result.series.plot).isEqualTo("IMDb plot")
            assertThat(tv.takeRequest().path).isEqualTo("/search/shows?q=Synthetic%20Show")
            assertThat(tv.takeRequest().path).isEqualTo("/lookup/shows?imdb=tt1234567")
        } }
    }

}
