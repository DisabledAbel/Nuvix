/* Copyright (C) 2026 Nuvix Contributors. Licensed under GPL-3.0-or-later. */
package app.opentv

import android.app.Application
import androidx.room.Room
import app.opentv.data.db.OpenTvDatabase
import app.opentv.data.model.Channel
import app.opentv.data.model.EpgChannelAlias
import app.opentv.data.model.EpgFeed
import app.opentv.data.model.Programme
import app.opentv.data.parser.ChannelNameNormalizer
import app.opentv.data.parser.XmltvParser
import app.opentv.data.remote.XtreamApi
import app.opentv.data.repo.AutoEpgDiscovery
import app.opentv.data.repo.DiscoveredGuide
import app.opentv.data.repo.DiscoveryResult
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class AutoEpgConfigurationTest {
    private lateinit var database: OpenTvDatabase
    private lateinit var server: MockWebServer
    private lateinit var directory: File
    private val now = XmltvParser.parseXmltvTime("20300101000000 +0000")!!
    private val suppressed = mutableSetOf<String>()

    @Before fun prepare() {
        database = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), OpenTvDatabase::class.java).build()
        server = MockWebServer().also { it.start() }
        directory = File(RuntimeEnvironment.getApplication().cacheDir, "auto-epg-test").also { it.mkdirs() }
    }

    @After fun cleanUp() {
        database.close(); server.shutdown(); directory.deleteRecursively()
    }

    private fun repository(discovery: AutoEpgDiscovery? = null, enabled: Boolean = true): EpgRepository {
        val http = OkHttpClient()
        return EpgRepository(database.programmes(), database.epgFeeds(), database.epgAliases(), database.channels(),
            database.sources(), XtreamApi(http), http, database, directory, discovery,
            automaticEnabled = { enabled }, feedSuppressed = { it in suppressed },
            suppressFeed = { url, value -> if (value) suppressed.add(url) else suppressed.remove(url) })
    }

    private fun channel(name: String, override: String? = null) = Channel(
        sourceId = 1, streamId = name, name = name, groupKey = ChannelNameNormalizer.normalize(name).groupKey,
        categoryId = null, logoUrl = null, epgChannelId = null, epgOverrideId = override,
        number = null, streamUrl = "https://example.com/stream",
    )

    private fun guide(id: String, name: String) = """
        <tv><channel id="$id"><display-name>$name</display-name></channel>
        <programme channel="$id" start="20300101000000 +0000" stop="20300101010000 +0000"><title>Current show</title></programme></tv>
    """.trimIndent()

    @Test fun `scans every unresolved channel and commits matches before the next guide`() = runBlocking {
        database.channels().upsertAll(listOf(channel("UK| BBC ONE HD"), channel("US| CNN FHD"), channel("CA| Manual", "chosen.ca")))
        var scanned: List<Channel> = emptyList()
        val discovered = listOf(DiscoveredGuide("UK", server.url("/uk.xml").toString()), DiscoveredGuide("US", server.url("/us.xml").toString()))
        val repo = repository(AutoEpgDiscovery { scanned = it; DiscoveryResult(discovered) })
        server.enqueue(MockResponse().setBody(guide("BBCOne.uk", "BBC One")))
        server.enqueue(MockResponse().setBody(guide("CNN.us", "CNN")))
        val intermediate = mutableListOf<String>()
        val summary = repo.syncAll(now, onProgress = { line ->
            if (line == "Matched 1 of 3 channels…") runBlocking {
                intermediate += database.channels().allForMatching().mapNotNull { it.matchedEpgId }
            }
        })
        assertThat(scanned.map { it.name }).containsExactly("UK| BBC ONE HD", "US| CNN FHD")
        assertThat(intermediate).contains("BBCOne.uk")
        assertThat(summary.channelsMatched).isEqualTo(2)
        assertThat(database.channels().allForMatching().first { it.epgOverrideId != null }.epgOverrideId).isEqualTo("chosen.ca")
        assertThat(server.requestCount).isEqualTo(2)
        repo.syncAll(now + 1000)
        assertThat(server.requestCount).isEqualTo(2) // Newly assigned ids and downloads are cached.
    }

    @Test fun `empty source falls back and never assigns an unpopulated id`() = runBlocking {
        database.channels().upsertAll(listOf(channel("BBC One")))
        val repo = repository(AutoEpgDiscovery { DiscoveryResult(listOf(
            DiscoveredGuide("Empty", server.url("/empty.xml").toString()),
            DiscoveredGuide("Working", server.url("/working.xml").toString()),
        )) })
        server.enqueue(MockResponse().setBody("<tv><channel id=\"wrong\"><display-name>BBC One</display-name></channel></tv>"))
        server.enqueue(MockResponse().setBody(guide("BBCOne.uk", "BBC One")))
        val summary = repo.syncAll(now)
        assertThat(summary.feedsFailed).isEqualTo(1)
        assertThat(summary.channelsMatched).isEqualTo(1)
        assertThat(database.channels().allForMatching().single().matchedEpgId).isEqualTo("BBCOne.uk")
        assertThat(database.epgAliases().all().map { it.epgId }).doesNotContain("wrong")
    }

    @Test fun `automatic off and suppressed feeds never trigger discovery downloads`() = runBlocking {
        database.channels().upsertAll(listOf(channel("BBC One")))
        var called = false
        val discovery = AutoEpgDiscovery { called = true; DiscoveryResult(listOf(DiscoveredGuide("UK", server.url("/uk.xml").toString()))) }
        repository(discovery, enabled = false).syncAll(now)
        assertThat(called).isFalse()
        suppressed += server.url("/uk.xml").toString()
        repository(discovery).syncAll(now)
        assertThat(called).isTrue()
        assertThat(server.requestCount).isEqualTo(0)
    }

    @Test fun `country scoped matches reject collisions and ids without future programmes`() = runBlocking {
        database.channels().upsertAll(listOf(channel("UK| News"), channel("US| News"), channel("News"), channel("Expired"), channel("Missing")))
        val feed = database.epgFeeds().insert(EpgFeed(name = "Cached", url = server.url("/guide.xml").toString()))
        database.epgAliases().upsertAll(listOf(
            EpgChannelAlias(feed, "News.uk", "News", "news"), EpgChannelAlias(feed, "News.us", "News", "news"),
            EpgChannelAlias(feed, "Expired.uk", "Expired", "expired"), EpgChannelAlias(feed, "Missing.uk", "Missing", "missing"),
        ))
        database.programmes().upsertAll(listOf(
            Programme(feedId = feed, epgChannelId = "News.uk", startUtcMillis = now, endUtcMillis = now + 3600000, title = "UK"),
            Programme(feedId = feed, epgChannelId = "News.us", startUtcMillis = now, endUtcMillis = now + 3600000, title = "US"),
            Programme(feedId = feed, epgChannelId = "Expired.uk", startUtcMillis = now - 7200000, endUtcMillis = now - 3600000, title = "Past"),
        ))
        val result = repository().runMatcher(now)
        val byName = database.channels().allForMatching().associateBy { it.name }
        assertThat(result.first).isEqualTo(2)
        assertThat(byName.getValue("UK| News").matchedEpgId).isEqualTo("News.uk")
        assertThat(byName.getValue("US| News").matchedEpgId).isEqualTo("News.us")
        assertThat(byName.getValue("News").matchedEpgId).isNull()
        assertThat(byName.getValue("Expired").matchedEpgId).isNull()
        assertThat(byName.getValue("Missing").matchedEpgId).isNull()
        database.epgFeeds().setEnabled(feed, false)
        assertThat(repository().runMatcher(now).first).isEqualTo(0)
        assertThat(database.channels().allForMatching().mapNotNull { it.matchedEpgId }).isEmpty()
    }

    @Test fun `turning a discovered feed off survives another automatic scan`() = runBlocking {
        database.channels().upsertAll(listOf(channel("BBC One")))
        val url = server.url("/uk.xml").toString()
        val discovery = AutoEpgDiscovery { DiscoveryResult(listOf(DiscoveredGuide("UK", url))) }
        val repo = repository(discovery)
        server.enqueue(MockResponse().setBody(guide("BBCOne.uk", "BBC One")))
        repo.syncAll(now)
        val feed = database.epgFeeds().byUrl(url)!!
        repo.setFeedEnabled(feed.id, false)
        assertThat(database.channels().allForMatching().single().matchedEpgId).isNull()
        repository(discovery).syncAll(now + 1000, force = true)
        assertThat(database.epgFeeds().byUrl(url)!!.enabled).isFalse()
        assertThat(server.requestCount).isEqualTo(1)
    }

    @Test fun `download limit resumes remaining candidates on the next refresh`() = runBlocking {
        database.channels().upsertAll(listOf(channel("Target")))
        val guides = (1..13).map { DiscoveredGuide("Guide $it", server.url("/$it.xml").toString()) }
        val repo = repository(AutoEpgDiscovery { DiscoveryResult(guides) })
        for (i in 1..12) server.enqueue(MockResponse().setBody(guide("Different$i.us", "Different $i")))
        assertThat(repo.syncAll(now).channelsMatched).isEqualTo(0)
        assertThat(server.requestCount).isEqualTo(12)
        server.enqueue(MockResponse().setBody(guide("Target.us", "Target")))
        assertThat(repo.syncAll(now + 1000).channelsMatched).isEqualTo(1)
        assertThat(server.requestCount).isEqualTo(13)
    }

}
