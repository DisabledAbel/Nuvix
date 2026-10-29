/* Nuvix modifications Copyright (C) 2026 Nuvix Contributors. GPL-3.0-or-later. */
package app.opentv.channels

import android.app.Application
import androidx.room.Room
import app.opentv.data.db.OpenTvDatabase
import app.opentv.data.model.*
import app.opentv.data.parser.M3uParser
import app.opentv.data.remote.StalkerApi
import app.opentv.data.remote.XtreamApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28])
class EnglishGroupProviderTest {
    @Test fun importedProviderNamesUseTheSameLocalClassifierWithoutAdditionalRequests() = runBlocking {
        val server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) = MockResponse().setBody(when (request.requestUrl?.queryParameter("action")) {
                "get_live_categories" -> """[{"category_id":"1","category_name":"English Movies"},{"category_id":"2"}]"""
                "handshake" -> """{"js":{"token":"synthetic","random":"fixture"}}"""
                "get_profile" -> """{"js":{"id":"1","blocked":"0","status":0}}"""
                "get_genres" -> """{"js":[{"id":"1","title":"English Movies"},{"id":"2","title":"English / French"}]}"""
                else -> "[]"
            })
        }
        server.start()
        try {
            val http = OkHttpClient()
            val xtream = Source(2, "Xtream fixture", SourceKind.XTREAM, server.url("/").toString(), username = "fixture", password = "fixture")
            val stalker = Source(3, "Stalker fixture", SourceKind.STALKER, server.url("/c").toString(), macAddress = "00:1A:79:AA:BB:CC")
            val m3u = Source(1, "Playlist fixture", SourceKind.M3U, "https://example.invalid/fixture.m3u")
            val parsed = M3uParser.parse("#EXTM3U\n#EXTINF:-1 group-title=\"English Movies\",Fixture\nhttps://example.invalid/fixture.ts", m3u.id)
            val categories = listOf(Category(parsed.channels.single().categoryId!!, m3u.id, parsed.channels.single().categoryId!!, StreamKind.LIVE)) +
                XtreamApi(http).liveCategories(xtream) + StalkerApi(http).liveCategories(stalker)
            val requestsBeforeDetection = server.requestCount
            val groups = englishProviderGroups(categories, listOf(m3u, xtream, stalker), emptyList())
            assertEquals(5, groups.size)
            assertEquals(3, groups.filter { it.originalName == "English Movies" }.map { it.key }.toSet().size)
            assertEquals(3, groups.count { EnglishGroupDetector.classify(it.originalName).confidence == EnglishConfidence.LIKELY })
            assertEquals(EnglishConfidence.UNKNOWN, EnglishGroupDetector.classify(groups.first { it.key == ProviderGroupKey(2, "2") }.originalName).confidence)
            assertEquals(EnglishConfidence.REVIEW, EnglishGroupDetector.classify(groups.first { it.key == ProviderGroupKey(3, "2") }.originalName).confidence)
            assertEquals(requestsBeforeDetection, server.requestCount)
        } finally { server.shutdown() }
    }

    @Test fun groupedProjectionHandlesLargeCatalogAndDetectionDoesNotMutateUserData() = runBlocking {
        val queries = java.util.concurrent.atomic.AtomicInteger()
        val db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), OpenTvDatabase::class.java)
            .allowMainThreadQueries().setQueryCallback({ _, _ -> queries.incrementAndGet() }, java.util.concurrent.Executor { it.run() }).build()
        try {
            val source = Source(1, "Offline", SourceKind.M3U, "https://example.invalid/fixture")
            db.sources().insert(source)
            val categories = listOf(Category("1", 1, "English", StreamKind.LIVE), Category("2", 1, "UK", StreamKind.LIVE))
            db.categories().upsertAll(categories)
            val channels = List(20_000) { index -> Channel(id = index + 1L, sourceId = 1, streamId = "$index", name = "Fixture $index", categoryId = if (index % 2 == 0) "1" else "2", logoUrl = null, epgChannelId = null, number = null, streamUrl = "https://example.invalid/$index", hidden = index % 3 == 0, favourite = index % 5 == 0) }
            channels.chunked(500).forEach { db.channels().upsertAll(it) }
            val custom = CustomChannelGroup(cloudId = "00000000-0000-0000-0000-000000000001", name = "Unchanged", members = listOf("a".repeat(64)))
            db.customGroups().upsert(custom)
            val before = db.channels().allForMatching()
            val beforeGroups = db.customGroups().observeActive().first()
            val counts = db.channels().observeChannelCountsByCategory().first()
            assertEquals(2, counts.size)
            assertEquals(listOf(10_000, 10_000), counts.map { it.count })
            val queryCountBeforeDetection = queries.get()
            val session = EnglishGroupSession(this, Dispatchers.Unconfined)
            session.updateCatalog(englishProviderGroups(categories, listOf(source), counts))
            session.scan()
            session.state.first { it.scanned }
            session.selectLikely(); assertTrue(session.apply()); session.clearFilter(); session.close()
            assertEquals(queryCountBeforeDetection, queries.get())
            assertEquals(before, db.channels().allForMatching())
            assertEquals(beforeGroups, db.customGroups().observeActive().first())
            assertEquals(categories, db.categories().allByKind(StreamKind.LIVE))
        } finally { db.close() }
    }
}
