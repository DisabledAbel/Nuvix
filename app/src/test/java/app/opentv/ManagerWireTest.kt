/* Nuvix Contributors. GPL-3.0-or-later. */
package app.opentv

import android.app.Application
import androidx.room.Room
import app.opentv.core.AppSettings
import app.opentv.data.db.OpenTvDatabase
import app.opentv.data.model.*
import app.opentv.data.remote.*
import app.opentv.data.repo.*
import app.opentv.pairing.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28])
class ManagerWireTest {
    private val context: Application = RuntimeEnvironment.getApplication()
    private val db = Room.inMemoryDatabaseBuilder(context, OpenTvDatabase::class.java)
        .addCallback(OpenTvDatabase.MANAGER_CALLBACK).allowMainThreadQueries().build()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val http = OkHttpClient()
    private val settings = AppSettings.get(context)
    private val api = XtreamApi(http)
    private val stalker = StalkerApi(http)
    private val catalog = CatalogRepository(db.sources(), db.channels(), db.categories(), db.movies(), db.series(),
        db.episodes(), db.positions(), api, stalker, http, settings)
    private val manager = ManagerServer(scope, catalog, settings, SourceRepository(db.sources(), api, stalker))
    private fun execute(method: String, path: String, body: String = "") =
        manager.executeRemote(CloudManagerCommand("test", method, path, body))
    @After fun close() { scope.cancel(); db.close() }

    @Test fun channelResponsesAreBoundedAndExcludeCredentialsUrlsAndLogoRequests() = runBlocking {
        db.sources().insert(Source(id = 1, name = "<provider>", kind = SourceKind.M3U,
            url = "https://provider.invalid/PRIVATE", password = "PASSWORD"))
        db.channels().upsertAll((1L..101L).map { Channel(id = it, sourceId = 1, streamId = "$it",
            name = "<channel>" + "x".repeat(2000), customName = "RENAMED", categoryId = "test", logoUrl = "https://logo.invalid/PRIVATE",
            epgChannelId = null, number = null, streamUrl = "https://stream.invalid/PRIVATE") })
        val result = execute("GET", "/channels/page?source=1&limit=100")
        assertEquals(200, result.status)
        val page = Json.parseToJsonElement(result.body).jsonObject
        assertEquals(100, page["rows"]!!.jsonArray.size)
        assertEquals(101, page["total"]!!.jsonPrimitive.int)
        assertFalse(result.body.contains("PRIVATE")); assertFalse(result.body.contains("PASSWORD"))
        assertTrue(page["rows"]!!.jsonArray.all { it.jsonObject["original"]!!.jsonPrimitive.content.length == 512 })
        assertEquals(426, execute("GET", "/channels?source=1&cat=test").status)
        assertEquals(400, execute("GET", "/channels/page?source=1&limit=101").status)
        val rev = page["revision"]!!.jsonPrimitive.content
        assertEquals(400, execute("POST", "/channels/bulk", """{"source":1,"revision":"$rev","action":"hide","ids":${(1..101).toList()}}""").status)
        assertFalse(db.channels().byId(1)!!.hidden)
        db.channels().upsertAll(listOf(db.channels().byId(1)!!.copy(id = 9_007_199_254_740_993L, streamId = "large-id")))
        assertEquals(409, execute("GET", "/channels/page?source=1&offset=101").status)
        db.sources().delete(1)
        assertEquals(409, execute("POST", "/channels/bulk", """{"source":1,"revision":"$rev","action":"hide","ids":[1]}""").status)
        assertEquals(409, execute("GET", "/channels/page?source=1").status)
    }
}
