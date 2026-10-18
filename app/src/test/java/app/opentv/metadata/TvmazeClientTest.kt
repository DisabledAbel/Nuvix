/* Nuvix modifications Copyright (C) 2026 Nuvix Contributors. GPL-3.0-or-later. */
package app.opentv.metadata

import app.opentv.data.remote.*
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

/** Regression tests for tvmaze client. */
class TvmazeClientTest {
    @get:Rule val temp = TemporaryFolder()
    private lateinit var server: MockWebServer
    private lateinit var client: TvmazeClient
    /** Creates isolated fixtures before each test. */
    @Before fun start() {
        server = MockWebServer(); server.start()
        client = TvmazeClient(MetadataHttp(OkHttpClient(), temp.root, spacingMillis = 0), server.url("/"))
    }
    /** Releases test resources and restores shared state after each test. */
    @After fun stop() { server.shutdown() }
    /** Queues a synthetic JSON response on the local mock metadata server. */
    private fun respond(body: String) { server.enqueue(MockResponse().setBody(body)) }
    /** Encodes a synthetic TVmaze show response with controlled identity and premiere year. */
    private fun show(id: Int = 7, year: Int = 2024) = """{"id":$id,"name":"Synthetic Show","premiered":"$year-01-01","externals":{"imdb":"tt1234567"}}"""

    /** Checks: IM Db lookup follows redirect and bypasses title search without authentication. */
    @Test fun `IMDb lookup follows redirect and bypasses title search without authentication`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(301).setHeader("Location", server.url("/shows/7")))
        respond(show())
        assertThat(client.match("Different provider title", null, "tt1234567")?.id).isEqualTo(7)
        assertThat(server.takeRequest().path).isEqualTo("/lookup/shows?imdb=tt1234567")
        val request = server.takeRequest()
        assertThat(request.path).isEqualTo("/shows/7")
        assertThat(request.getHeader("Authorization")).isNull()
        assertThat(request.getHeader("User-Agent")).startsWith("Nuvix/")
    }
    /** Checks: title search uses year and refuses ambiguous remakes. */
    @Test fun `title search uses year and refuses ambiguous remakes`() = runBlocking {
        respond("""[{"show":${show()}},{"show":${show(8, 2001)}}]""")
        assertThat(client.match("Synthetic Show", 2024, null)?.id).isEqualTo(7)
        assertThat(client.match("Synthetic Show", null, null)).isNull()
        assertThat(server.requestCount).isEqualTo(1)
    }
    /** Checks: failed IM Db lookup can match title but conflicting IM Db mapping is rejected. */
    @Test fun `failed IMDb lookup can match title but conflicting IMDb mapping is rejected`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(404))
        respond("""[{"show":${show()}}]""")
        assertThat(client.match("Synthetic Show", 2024, "tt9999999")).isNull()
    }
    /** Checks: unrelated search result is never chosen. */
    @Test fun `unrelated search result is never chosen`() = runBlocking {
        respond("""[{"score":99,"show":${show()}}]""")
        assertThat(client.match("Other Show", null, null)).isNull()
    }
    /** Checks: details include seasons specials null episode numbers people and background art. */
    @Test fun `details include seasons specials null episode numbers people and background art`() = runBlocking {
        respond("""[{"type":"poster","resolutions":{"original":{"url":"poster"}}},{"type":"background","main":true,"resolutions":{"original":{"url":"background"}}}]""")
        respond("""[{"id":1,"number":1,"name":"First","summary":"<p>Season</p>","premiereDate":"2024-01-01","episodeOrder":2,"image":null}]""")
        respond("""[{"id":1,"season":1,"number":1,"name":"Pilot","summary":"<p>Plot</p>","airdate":"2024-01-01","image":{"original":"still"}},{"id":2,"season":1,"number":null,"name":"Special","image":null}]""")
        respond("""[{"person":{"id":10,"name":"Synthetic Actor","image":{"original":"photo"}},"character":{"name":"Character","image":{"medium":"character photo"}}}]""")
        respond("""[{"person":{"id":11,"name":"Synthetic Director"},"type":"Director"}]""")
        val result = client.details(TvShow(7,"Synthetic Show",null,2024,null,null,null,null))
        assertThat(result.background).isEqualTo("background")
        assertThat(result.seasons.single().episodeOrder).isEqualTo(2)
        assertThat(result.episodes).hasSize(2)
        assertThat(result.episodes[1].number).isNull()
        assertThat(result.cast.single().character).isEqualTo("Character")
        assertThat(result.cast.single().person.image).isEqualTo("photo")
        assertThat(result.crew.single().job).isEqualTo("Director")
        val paths = List(5) { server.takeRequest().path }
        assertThat(paths).contains("/shows/7/episodes?specials=1")
    }
    /** Checks: missing optional data leaves show intact. */
    @Test fun `missing optional data leaves show intact`() = runBlocking {
        repeat(5) { respond("null") }
        val show = TvShow(7,"Synthetic Show",null,null,null,null,null,null)
        val result = client.details(show)
        assertThat(result.show).isEqualTo(show)
        assertThat(result.episodes).isEmpty()
        assertThat(result.cast).isEmpty()
    }
    /** Checks: actor profile has photo country cast characters and crew credits. */
    @Test fun `actor profile has photo country cast characters and crew credits`() = runBlocking {
        respond("""{"id":10,"name":"Synthetic Actor","birthday":"1980-01-01","country":{"name":"Country"},"image":{"original":"photo"}}""")
        respond("""[{"_embedded":{"show":${show()},"character":{"name":"Character"}}}]""")
        respond("""[{"type":"Producer","_embedded":{"show":${show()}}}]""")
        val profile = client.profile(10)!!
        assertThat(profile.person.country).isEqualTo("Country")
        assertThat(profile.person.image).isEqualTo("photo")
        assertThat(profile.credits.map { it.role }).containsExactly("Character", "Producer")
        assertThat(profile.person.route).isEqualTo("tvmaze:10:Synthetic Actor")
    }
}
