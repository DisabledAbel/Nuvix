/* Nuvix modifications Copyright (C) 2026 Nuvix Contributors. GPL-3.0-or-later. */
package app.opentv.metadata

import android.app.Application
import androidx.room.Room
import app.opentv.core.AppSettings
import app.opentv.data.db.OpenTvDatabase
import app.opentv.data.model.Series
import app.opentv.data.model.Source
import app.opentv.data.model.SourceKind
import app.opentv.data.remote.*
import app.opentv.data.repo.CatalogRepository
import app.opentv.data.repo.TvMetadataRepository
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Regression tests for provider series detail. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28])
class ProviderSeriesDetailTest {
    @get:Rule val temp = TemporaryFolder()
    private lateinit var db: OpenTvDatabase
    private lateinit var provider: MockWebServer
    private lateinit var repository: CatalogRepository

    /** Creates isolated fixtures before each test. */
    @Before fun setup() {
        val context = RuntimeEnvironment.getApplication()
        db = Room.inMemoryDatabaseBuilder(context, OpenTvDatabase::class.java).build()
        provider = MockWebServer().apply { start() }
        val http = OkHttpClient()
        repository = CatalogRepository(db.sources(), db.channels(), db.categories(), db.movies(),
            db.series(), db.episodes(), db.positions(), XtreamApi(http), StalkerApi(http),
            http, AppSettings.get(context))
    }
    /** Releases test resources and restores shared state after each test. */
    @After fun close() { db.close(); provider.shutdown() }
    /** Provides a synthetic provider series for metadata or synchronization tests. */
    private fun series(sourceId: Long) = Series(id=42, sourceId=sourceId, seriesId="7", name="Synthetic Show",
        categoryId=null, posterUrl=null, rating=6.0, year=2024, plot=null, favourite=true)
    /** Provides a synthetic provider configuration for the test's requested source identity. */
    private suspend fun source(kind: SourceKind = SourceKind.XTREAM) = db.sources().insert(
        Source(name="Synthetic provider", kind=kind, url=provider.url("/").toString(),
            username="fixture", password="fixture"))

    /** Checks: one provider response supplies both backfill and IM Db matching without changing fallbacks. */
    @Test fun `one provider response supplies both backfill and IMDb matching without changing fallbacks`(): Unit = runBlocking {
        val original = series(source())
        db.series().upsertAll(listOf(original))
        provider.enqueue(MockResponse().setBody("""{"info":{"imdb_id":"tt1234567","plot":"Provider plot","cast":"Provider actor","genre":"Drama"}}"""))
        val detail = repository.seriesDetail(original.id)!!
        assertThat(detail.imdbId).isEqualTo("tt1234567")
        assertThat(detail.series.plot).isEqualTo("Provider plot")
        assertThat(db.series().byId(original.id)).isEqualTo(detail.series)

        MockWebServer().use { tv -> MockWebServer().use { imdb ->
            tv.start(); imdb.start()
            tv.enqueue(MockResponse().setResponseCode(404))
            tv.enqueue(MockResponse().setBody("[]"))
            imdb.enqueue(MockResponse().setResponseCode(503))
            val http = OkHttpClient()
            val metadata = TvMetadataRepository(
                TvmazeClient(MetadataHttp(http,temp.newFolder(),spacingMillis=0),tv.url("/")),
                ImdbApiClient(MetadataHttp(http,temp.newFolder(),spacingMillis=0),imdb.url("/")))
            var fallbacks = 0
            val result = metadata.detail(detail.series,detail.series.name,detail.imdbId) {
                fallbacks++
                TmdbMeta(backdropUrl="https://art.invalid/background")
            }
            assertThat(tv.takeRequest().path).isEqualTo("/lookup/shows?imdb=tt1234567")
            assertThat(imdb.takeRequest().path).isEqualTo("/titles/tt1234567")
            assertThat(fallbacks).isEqualTo(1)
            assertThat(result.series.plot).isEqualTo("Provider plot")
            assertThat(result.series.backdropUrl).isEqualTo("https://art.invalid/background")
            assertThat(result.series.seriesId).isEqualTo(original.seriesId)
        } }
        assertThat(provider.requestCount).isEqualTo(1)
    }
    /** Checks: already enriched series still returns the IM Db ID with a single request. */
    @Test fun `already enriched series still returns the IMDb ID with a single request`(): Unit = runBlocking {
        val original = series(source()).copy(cast="Existing actor",plot="Existing plot")
        db.series().upsertAll(listOf(original))
        provider.enqueue(MockResponse().setBody("""{"info":{"imdb":"tt7654321","plot":"Other plot"}}"""))
        val detail = repository.seriesDetail(original.id)!!
        assertThat(detail.imdbId).isEqualTo("tt7654321")
        assertThat(detail.series).isEqualTo(original)
        assertThat(provider.requestCount).isEqualTo(1)
    }
    /** Checks: failed provider response retains series with no IM Db ID. */
    @Test fun `failed provider response retains series with no IMDb ID`(): Unit = runBlocking {
        val original = series(source())
        db.series().upsertAll(listOf(original))
        provider.enqueue(MockResponse().setResponseCode(503))
        val detail = repository.seriesDetail(original.id)!!
        assertThat(detail.imdbId).isNull()
        assertThat(detail.series).isEqualTo(original)
        assertThat(provider.requestCount).isEqualTo(1)
    }
    /** Checks: non Xtream sources need no series info request. */
    @Test fun `non Xtream sources need no series info request`(): Unit = runBlocking {
        val original = series(source(SourceKind.M3U))
        db.series().upsertAll(listOf(original))
        val detail = repository.seriesDetail(original.id)!!
        assertThat(detail.imdbId).isNull()
        assertThat(detail.series).isEqualTo(original)
        assertThat(provider.requestCount).isEqualTo(0)
    }
}
