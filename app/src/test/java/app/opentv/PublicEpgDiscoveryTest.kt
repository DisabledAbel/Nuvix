/* Copyright (C) 2026 Nuvix Contributors. Licensed under GPL-3.0-or-later. */
package app.opentv

import android.app.Application
import app.opentv.data.model.Channel
import app.opentv.data.parser.ChannelNameNormalizer
import app.opentv.data.repo.DiscoveredGuide
import app.opentv.data.repo.PublicEpgDiscovery
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Regression tests for public epg discovery. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class PublicEpgDiscoveryTest {
    /** Provides a synthetic channel with the identity and state required by this test. */
    private fun channel(name: String, id: String? = null) = Channel(sourceId = 1, streamId = name, name = name,
        groupKey = ChannelNameNormalizer.normalize(name).groupKey, categoryId = null, logoUrl = null,
        epgChannelId = id, number = null, streamUrl = "https://provider.test/private")

    /** Checks: directory resolves relative XML links and excludes non guides and credentials. */
    @Test fun `directory resolves relative XML links and excludes non guides and credentials`() {
        val parsed = PublicEpgDiscovery.parseDirectory("https://iptv-epg.org/guides", """
            <a href="/guides/gb.xml.gz">United <b>Kingdom</b></a>
            <a href='https://guides.test/us.xml?days=3&amp;format=xml'>USA</a>
            <a href="/guides/gb.xml.gz">duplicate</a>
            <a href="https://user:password@guides.test/private.xml">private</a>
            <a href="http://guides.test/unsafe.xml">unsafe</a><a href="javascript:void(0)">other</a>
            <a href="/guides/not-an-xml">not a guide</a>
        """.trimIndent())
        assertThat(parsed.map { it.url }).containsExactly("https://iptv-epg.org/guides/gb.xml.gz", "https://guides.test/us.xml?days=3&format=xml")
        assertThat(parsed.first().name).contains("Kingdom")
        assertThat(PublicEpgDiscovery.relevantTo(parsed.first(), setOf("GB"))).isTrue()
        assertThat(PublicEpgDiscovery.relevantTo(parsed.first(), setOf("US"))).isFalse()
        assertThat(PublicEpgDiscovery.relevantTo(DiscoveredGuide("Global", "https://guides.test/all.xml"), setOf("CA"))).isTrue()
    }

    /** Checks: streamed catalog accepts only advertised downloadable XML sources. */
    @Test fun `streamed catalog accepts only advertised downloadable XML sources`() {
        val entries = PublicEpgDiscovery.readGuides("""[
          {"channel":null,"site_name":"Unmapped","sources":[{"url":"https://guides.test/missing.xml","format":"XML"}]},
          {"channel":"BBCOne.uk","site_name":"BBC One","sources":[]},
          {"channel":"CNN.us","site_name":"CNN","sources":[
            {"url":"https://guides.test/us.xml","format":"XML"},
            {"url":"https://guides.test/us.xml.gz","format":"GZIP"},
            {"url":"https://guides.test/us.json","format":"JSON"},
            {"url":"https://user:secret@guides.test/private.xml","format":"XML"}
          ]}
        ]""".byteInputStream())
        assertThat(entries).hasSize(1)
        assertThat(entries.single().channel).isEqualTo("CNN.us")
        assertThat(entries.single().urls).containsExactly("https://guides.test/us.xml", "https://guides.test/us.xml.gz")
    }

    /** Checks: mixed countries and unprefixed exact names select all relevant regional fallbacks. */
    @Test fun `mixed countries and unprefixed exact names select all relevant regional fallbacks`() = runBlocking<Unit> {
        val server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.path) {
                "/channels" -> MockResponse().setBody("""[
                  {"id":"BBCOne.uk","name":"BBC One","country":"UK","alt_names":["BBC 1"]},
                  {"id":"CNN.us","name":"CNN","country":"US","alt_names":[]},
                  {"id":"CNN.ca","name":"CNN","country":"CA","alt_names":[]}
                ]""")
                "/sources" -> MockResponse().setBody("""[
                  {"channel":"CNN.us","site_name":"CNN","sources":[{"url":"https://guides.test/us.xml.gz","format":"GZIP"}]}
                ]""")
                else -> MockResponse().setBody("<a href='https://guides.test/ca.xml'>Canada</a>")
            }
        }
        server.start()
        try {
            val discovery = PublicEpgDiscovery(OkHttpClient(), server.url("/directory").toString(), server.url("/channels").toString(), server.url("/sources").toString())
            val found = discovery.discover(listOf(channel("BBC ONE HD"), channel("US| CNN"), channel("CA| Other")))
            assertThat(found.sourcesFailed).isEqualTo(0)
            assertThat(found.guides.map { it.name }).containsAtLeast("UK — Freeview (free-to-air)", "UK — epgshare01 (Sky lineup)", "USA — epgshare01", "Canada — epgshare01")
            assertThat(found.guides.map { it.url }).containsAtLeast("https://guides.test/us.xml.gz", "https://guides.test/ca.xml")
            // The requests fetch catalogs only; no channel names or provider URLs are sent.
            assertThat((1..3).map { server.takeRequest().path }).containsExactly("/channels", "/sources", "/directory")
        } finally { server.shutdown() }
    }

    /** Checks: a broken catalog still tries directory and regional fallback. */
    @Test fun `a broken catalog still tries directory and regional fallback`() = runBlocking<Unit> {
        val server = MockWebServer().also { it.start() }
        server.enqueue(MockResponse().setResponseCode(503))
        server.enqueue(MockResponse().setBody("<a href='https://guides.test/au.xml.gz'>Australia</a>"))
        try {
            val found = PublicEpgDiscovery(OkHttpClient(), server.url("/directory").toString(), server.url("/channels").toString(), server.url("/sources").toString())
                .discover(listOf(channel("AUS| ABC")))
            assertThat(found.sourcesFailed).isEqualTo(1)
            assertThat(found.guides.map { it.url }).contains("https://guides.test/au.xml.gz")
            assertThat(found.guides.map { it.name }).contains("Australia — epgshare01")
        } finally { server.shutdown() }
    }
}
