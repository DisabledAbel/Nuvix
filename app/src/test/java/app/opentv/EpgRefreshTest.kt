/* Copyright (C) 2026 Nuvix Contributors. Licensed under GPL-3.0-or-later. */
package app.opentv

import android.app.Application
import androidx.room.Room
import app.opentv.data.db.OpenTvDatabase
import app.opentv.data.model.EpgChannelAlias
import app.opentv.data.model.EpgFeed
import app.opentv.data.model.Programme
import app.opentv.data.parser.XmltvParser
import app.opentv.data.remote.XtreamApi
import app.opentv.data.repo.EpgRepository
import com.google.common.truth.Truth.assertThat
import java.io.File
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Regression tests for epg refresh. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class EpgRefreshTest {
    private lateinit var database: OpenTvDatabase
    private lateinit var server: MockWebServer
    private lateinit var directory: File
    private lateinit var repository: EpgRepository
    private var feedId = 0L
    private val now = XmltvParser.parseXmltvTime("20300101000000 +0000")!!

    /** Creates isolated fixtures before each test. */
    @Before fun prepare() = runBlocking {
        database = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), OpenTvDatabase::class.java).build()
        server = MockWebServer().also { it.start() }
        directory = File(RuntimeEnvironment.getApplication().cacheDir, "epg-refresh-test").also { it.mkdirs() }
        val http = OkHttpClient()
        repository = EpgRepository(
            database.programmes(), database.epgFeeds(), database.epgAliases(), database.channels(),
            database.sources(), XtreamApi(http), http, database, directory,
        )
        feedId = database.epgFeeds().insert(EpgFeed(name = "Test guide", url = server.url("/guide.xml").toString()))
        database.programmes().upsertAll(listOf(Programme(
            feedId = feedId, epgChannelId = "old", startUtcMillis = now,
            endUtcMillis = now + 3_600_000, title = "Last valid programme",
        )))
        database.epgAliases().upsertAll(listOf(EpgChannelAlias(feedId, "old", "Old channel", "oldchannel")))
    }

    /** Releases test resources and restores shared state after each test. */
    @After fun cleanUp() {
        database.close()
        server.shutdown()
        directory.deleteRecursively()
    }

    /** Checks: HTTP failure keeps programmes aliases and refresh stamp. */
    @Test fun `HTTP failure keeps programmes aliases and refresh stamp`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(503))
        val result = repository.syncAll(now, force = true)
        assertThat(result.feedsFailed).isEqualTo(1)
        assertThat(database.epgFeeds().all().first { it.id == feedId }.lastResult).contains("HTTP 503")
        assertOldGuidePreserved()
    }

    /** Checks: malformed XML after a flushed batch rolls back the entire replacement. */
    @Test fun `malformed XML after a flushed batch rolls back the entire replacement`() = runBlocking {
        val entries = (0..550).joinToString("") {
            "<programme channel=\"new-$it\" start=\"20300101000000 +0000\" stop=\"20300101010000 +0000\"><title>New $it</title></programme>"
        }
        server.enqueue(MockResponse().setBody("<tv><channel id=\"new\"><display-name>New</display-name></channel>$entries<programme"))
        val result = repository.syncAll(now, force = true)
        assertThat(result.feedsFailed).isEqualTo(1)
        assertOldGuidePreserved()
    }

    /** Checks: empty guide cannot erase the last valid cache. */
    @Test fun `empty guide cannot erase the last valid cache`() = runBlocking {
        server.enqueue(MockResponse().setBody("<tv><channel id=\"new\"><display-name>New</display-name></channel></tv>"))
        assertThat(repository.syncAll(now, force = true).feedsFailed).isEqualTo(1)
        assertOldGuidePreserved()
    }

    /** Checks: complete guide commits programmes aliases and refresh stamp together. */
    @Test fun `complete guide commits programmes aliases and refresh stamp together`() = runBlocking {
        server.enqueue(MockResponse().setBody("""
            <tv>
              <channel id="new"><display-name>New Channel</display-name></channel>
              <programme channel="new" start="20300101000000 +0000" stop="20300101010000 +0000"><title>Fresh programme</title></programme>
            </tv>
        """.trimIndent()))
        val result = repository.syncAll(now, force = true)
        assertThat(result.feedsSucceeded).isEqualTo(1)
        assertThat(result.feedsFailed).isEqualTo(0)
        assertThat(database.programmes().countForFeed(feedId)).isEqualTo(1)
        assertThat(database.programmes().upcoming("old", now, 10)).isEmpty()
        assertThat(database.programmes().upcoming("new", now, 10).single().title).isEqualTo("Fresh programme")
        assertThat(database.epgAliases().all().single().epgId).isEqualTo("new")
        assertThat(database.epgFeeds().all().first { it.id == feedId }.lastSyncMillis).isEqualTo(now)
        assertThat(directory.listFiles().orEmpty().toList()).isEmpty()
    }

    /** Asserts that the previously stored guide remains intact after a failed refresh. */
    private suspend fun assertOldGuidePreserved() {
        assertThat(database.programmes().countForFeed(feedId)).isEqualTo(1)
        assertThat(database.programmes().upcoming("old", now, 10).single().title).isEqualTo("Last valid programme")
        assertThat(database.epgAliases().all().single().epgId).isEqualTo("old")
        assertThat(database.epgFeeds().all().first { it.id == feedId }.lastSyncMillis).isEqualTo(0)
        assertThat(directory.listFiles().orEmpty().toList()).isEmpty()
    }
}
