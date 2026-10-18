/* Nuvix modifications Copyright (C) 2026 Nuvix Contributors. GPL-3.0-or-later. */
package app.opentv.metadata

import app.opentv.data.remote.*
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Regression tests for imdb api client. */
class ImdbApiClientTest {
    @get:Rule val temp = TemporaryFolder()
    /** Checks: secondary matches TV type title year and reads IM Db rating. */
    @Test fun `secondary matches TV type title year and reads IMDb rating`() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse().setBody("""{"titles":[{"id":"tt1234567","type":"TV_SERIES","primaryTitle":"Synthetic Show","startYear":2024},{"id":"tt0000001","type":"MOVIE","primaryTitle":"Synthetic Show","startYear":2024}]}"""))
            server.enqueue(MockResponse().setBody("""{"id":"tt1234567","type":"TV_SERIES","plot":"Secondary plot","rating":{"aggregateRating":8.2},"primaryImage":{"url":"poster"},"genres":["Drama"],"stars":[{"displayName":"Actor"}]}"""))
            val client = ImdbApiClient(MetadataHttp(OkHttpClient(), temp.root, spacingMillis = 0), server.url("/"))
            val meta = client.series("Synthetic Show", 2024, null)!!
            assertThat(meta.rating).isEqualTo(8.2)
            assertThat(meta.cast).isEqualTo("Actor")
            assertThat(server.takeRequest().path).isEqualTo("/search/titles?query=Synthetic%20Show&limit=20")
            assertThat(server.takeRequest().path).isEqualTo("/titles/tt1234567")
        }
    }
    /** Checks: secondary failure is optional and rate limited across calls. */
    @Test fun `secondary failure is optional and rate limited across calls`() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse().setResponseCode(503))
            val client = ImdbApiClient(MetadataHttp(OkHttpClient(), temp.root), server.url("/"))
            assertThat(client.series("Show", null, "tt1234567")).isNull()
            assertThat(client.series("Other", null, "tt7654321")).isNull()
            assertThat(server.requestCount).isEqualTo(1)
        }
    }
}
