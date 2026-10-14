/* Copyright (C) 2026 Nuvix Contributors. Licensed under GPL-3.0-or-later. */
package app.opentv.data.repo

import android.util.JsonReader
import android.util.JsonToken
import app.opentv.data.model.Channel
import app.opentv.data.parser.ChannelNameNormalizer
import java.io.FilterInputStream
import java.io.InputStream
import java.io.InputStreamReader
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request

/** Discovery finds downloadable feeds, never invents a TVG-id from a channel name. */
fun interface AutoEpgDiscovery {
    suspend fun discover(channels: List<Channel>): DiscoveryResult
}

data class DiscoveredGuide(val name: String, val url: String)
data class DiscoveryResult(val guides: List<DiscoveredGuide>, val sourcesFailed: Int = 0)

/**
 * Reads public catalogs once per refresh. Channel names stay on the device: requests download
 * whole catalogs, rather than sending a playlist or provider credentials to a search service.
 * JSON is streamed so the large IPTV-org source catalog never becomes one in-memory document.
 */
class PublicEpgDiscovery(
    private val http: OkHttpClient,
    private val directoryUrl: String = "https://iptv-epg.org/guides",
    private val channelsUrl: String = "https://raw.githubusercontent.com/iptv-org/api/gh-pages/channels.json",
    private val guidesUrl: String = "https://raw.githubusercontent.com/iptv-org/api/gh-pages/guides.json",
) : AutoEpgDiscovery {
    internal data class CatalogChannel(val id: String, val names: List<String>, val country: String)
    internal data class CatalogGuide(val channel: String, val name: String, val urls: List<String>)

    override suspend fun discover(channels: List<Channel>): DiscoveryResult {
        val countries = channels.mapNotNull { regionOf(it) }.toMutableSet()
        val discovered = ArrayList<DiscoveredGuide>()
        var failures = 0
        try {
            val catalog = read(channelsUrl, MAX_CATALOG_BYTES) { readChannels(it) }
            val byId = catalog.associateBy { it.id }
            val global = EpgMatcher.buildIndex(catalog.flatMap { c -> c.names.map { c.id to it } })
            val regional = catalog.groupBy { canonicalRegion(it.country) }.mapValues { (_, entries) ->
                EpgMatcher.buildIndex(entries.flatMap { c -> c.names.map { c.id to it } })
            }
            val keys = HashMap<Pair<String?, String>, String?>()
            val wantedIds = channels.mapNotNull { channel ->
                val id = channel.epgChannelId?.substringBefore('@')?.takeIf { it in byId } ?: run {
                    val region = regionOf(channel)
                    val key = channel.groupKey.ifBlank { ChannelNameNormalizer.normalize(channel.name).groupKey }
                    keys.getOrPut(region to key) {
                        (if (region == null) global else regional[region])?.matchExact(key)
                    }
                }
                id?.also { byId[it]?.country?.let { country -> countries += canonicalRegion(country) } }
            }.toSet()
            if (wantedIds.isNotEmpty()) {
                for (guide in read(guidesUrl, MAX_CATALOG_BYTES) { readGuides(it) }) {
                    if (guide.channel in wantedIds) {
                        // Prefer compressed feeds; one alternative per source is enough.
                        guide.urls.sortedByDescending { it.substringBefore('?').endsWith(".gz") }
                            .firstOrNull()?.let { discovered += DiscoveredGuide("IPTV-org — ${guide.name}", it) }
                    }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            failures++ // A broken catalog must not prevent trying the other source.
        }
        try {
            val links = read(directoryUrl, MAX_DIRECTORY_BYTES) { stream ->
                parseDirectory(directoryUrl, stream.bufferedReader().readText())
            }
            check(links.isNotEmpty()) { "Public guide directory contains no XMLTV links." }
            discovered += links.filter { guide -> countries.isEmpty() || relevantTo(guide, countries) }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            failures++
        }
        for (country in countries) {
            EpgRepository.REGION_TO_FEEDS[country].orEmpty().forEach { name ->
                EpgRepository.BUILT_IN_FEEDS.firstOrNull { it.first == name }?.let { (label, url) ->
                    discovered += DiscoveredGuide(label, url)
                }
            }
        }
        return DiscoveryResult(discovered.distinctBy { it.url }, failures)
    }

    private suspend fun <T> read(url: String, limit: Long, parse: (InputStream) -> T): T {
        currentCoroutineContext().ensureActive()
        return http.newCall(Request.Builder().url(url).build()).execute().use { response ->
            check(response.isSuccessful) { "Public guide catalog unavailable." }
            val body = checkNotNull(response.body)
            check(body.contentLength() <= limit) { "Public guide catalog too large." }
            body.byteStream().use { parse(LimitedInputStream(it, limit)) }
        }.also { currentCoroutineContext().ensureActive() }
    }

    companion object {
        private const val MAX_CATALOG_BYTES = 64L * 1024 * 1024
        private const val MAX_DIRECTORY_BYTES = 4L * 1024 * 1024
        private val COUNTRY_CODES = Locale.getISOCountries().toSet()

        internal fun countryCode(region: String): String? = canonicalRegion(region).takeIf { it in COUNTRY_CODES }

        internal fun canonicalRegion(region: String): String = when (val code = region.uppercase(Locale.ROOT)) {
            "UK" -> "GB"
            "USA" -> "US"
            "AUS" -> "AU"
            else -> code
        }

        internal fun regionOf(channel: Channel): String? =
            ChannelNameNormalizer.normalize(channel.name).region?.let(::countryCode)
                ?: channel.epgChannelId?.substringBefore('@')?.substringAfterLast('.', "")
                    ?.let(::countryCode)

        internal fun parseDirectory(baseUrl: String, html: String): List<DiscoveredGuide> {
            val base = baseUrl.toHttpUrlOrNull() ?: return emptyList()
            val anchors = Regex("""<a\b[^>]*\bhref\s*=\s*["']([^"']+)["'][^>]*>(.*?)</a>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
            return anchors.findAll(html).mapNotNull { match ->
                val target = base.resolve(match.groupValues[1].replace("&amp;", "&")) ?: return@mapNotNull null
                if (target.scheme != "https" || target.username.isNotEmpty() || target.password.isNotEmpty()) return@mapNotNull null
                if (!target.encodedPath.matches(Regex(".*\\.xml(?:\\.gz)?", RegexOption.IGNORE_CASE))) return@mapNotNull null
                val label = match.groupValues[2].replace(Regex("<[^>]+>"), " ").replace("&amp;", "&").trim()
                DiscoveredGuide("IPTV-EPG — ${label.ifBlank { target.pathSegments.last() }}", target.toString())
            }.distinctBy { it.url }.toList()
        }

        internal fun relevantTo(guide: DiscoveredGuide, countries: Set<String>): Boolean {
            val text = "${guide.name} ${guide.url.substringBefore('?')}".lowercase(Locale.ROOT)
            val tokens = text.split(Regex("[^a-z]+"))
            return countries.any { country ->
                val codes = when (country) { "GB" -> listOf("gb", "uk"); "US" -> listOf("us", "usa"); else -> listOf(country.lowercase(Locale.ROOT)) }
                val name = Locale("", country).getDisplayCountry(Locale.ENGLISH).lowercase(Locale.ROOT)
                codes.any { it in tokens } || (name.isNotEmpty() && text.contains(name))
            } || tokens.any { it == "all" || it == "world" || it == "international" }
        }

        internal fun readChannels(input: InputStream): List<CatalogChannel> = JsonReader(InputStreamReader(input, Charsets.UTF_8)).use { reader ->
            val result = ArrayList<CatalogChannel>()
            reader.beginArray()
            while (reader.hasNext()) {
                var id = ""; var name = ""; var country = ""
                val names = ArrayList<String>()
                reader.beginObject()
                while (reader.hasNext()) when (reader.nextName()) {
                    "id" -> id = reader.stringOrEmpty()
                    "name" -> name = reader.stringOrEmpty()
                    "country" -> country = reader.stringOrEmpty()
                    "alt_names" -> { reader.beginArray(); while (reader.hasNext()) names += reader.stringOrEmpty(); reader.endArray() }
                    else -> reader.skipValue()
                }
                reader.endObject()
                if (id.isNotBlank()) result += CatalogChannel(id, (listOf(name) + names).filter { it.isNotBlank() }, country)
            }
            reader.endArray()
            result
        }

        internal fun readGuides(input: InputStream): List<CatalogGuide> = JsonReader(InputStreamReader(input, Charsets.UTF_8)).use { reader ->
            val result = ArrayList<CatalogGuide>()
            reader.beginArray()
            while (reader.hasNext()) {
                var channel = ""; var name = ""
                val urls = ArrayList<String>()
                reader.beginObject()
                while (reader.hasNext()) when (reader.nextName()) {
                    "channel" -> channel = reader.stringOrEmpty()
                    "site_name" -> name = reader.stringOrEmpty()
                    "sources" -> {
                        reader.beginArray()
                        while (reader.hasNext()) {
                            var url = ""; var format = ""
                            reader.beginObject()
                            while (reader.hasNext()) when (reader.nextName()) {
                                "url" -> url = reader.stringOrEmpty()
                                "format" -> format = reader.stringOrEmpty()
                                else -> reader.skipValue()
                            }
                            reader.endObject()
                            val parsed = url.toHttpUrlOrNull()
                            if (format in setOf("XML", "GZIP") && parsed?.scheme == "https" && parsed.username.isEmpty() && parsed.password.isEmpty()) urls += parsed.toString()
                        }
                        reader.endArray()
                    }
                    else -> reader.skipValue()
                }
                reader.endObject()
                if (channel.isNotBlank() && urls.isNotEmpty()) result += CatalogGuide(channel, name, urls)
            }
            reader.endArray()
            result
        }

        private fun JsonReader.stringOrEmpty(): String = if (peek() == JsonToken.NULL) { nextNull(); "" } else nextString()
    }
}

private class LimitedInputStream(input: InputStream, private val limit: Long) : FilterInputStream(input) {
    private var count = 0L
    override fun read(): Int = super.read().also { if (it >= 0) record(1) }
    override fun read(buffer: ByteArray, offset: Int, length: Int): Int = `in`.read(buffer, offset, length).also { if (it > 0) record(it) }
    private fun record(bytes: Int) { count += bytes; check(count <= limit) { "Public guide catalog too large." } }
}
