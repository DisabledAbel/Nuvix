/* Nuvix modifications Copyright (C) 2026 Nuvix Contributors. GPL-3.0-or-later. */
/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 * Modified for Nuvix on 2026-10-13: atomic, bounded guide refresh.
 */
package app.opentv.data.repo

import android.util.Log
import androidx.room.withTransaction
import app.opentv.data.db.OpenTvDatabase
import app.opentv.data.db.ChannelDao
import app.opentv.data.db.EpgChannelAliasDao
import app.opentv.data.db.EpgFeedDao
import app.opentv.data.db.ProgrammeDao
import app.opentv.data.db.ProgrammeSearchResult
import app.opentv.data.db.SourceDao
import app.opentv.data.model.Channel
import app.opentv.data.model.EpgChannelAlias
import app.opentv.data.model.EpgFeed
import app.opentv.data.model.Programme
import app.opentv.data.model.Source
import app.opentv.data.parser.ChannelNameNormalizer
import app.opentv.data.parser.XmltvParser
import app.opentv.data.remote.XtreamApi
import java.io.BufferedInputStream
import java.io.InputStream
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Owns the electronic programme guide, end to end: feeds in, matched channels out.
 *
 * ## The feed model
 *
 * Guide data can come from three places at once — the provider's own XMLTV (usually thin or
 * empty), the curated free sources shipped with the app, and URLs the user adds. Each is an
 * [EpgFeed] row; every enabled feed is downloaded and merged into one guide. After a sync,
 * [EpgMatcher] joins provider channels to guide channels by normalised name, which is what
 * makes a free national guide light up channels named `UK| BBC ONE FHD`.
 *
 * ## The failure this class exists to prevent
 *
 * The standard way to refresh an EPG is: delete everything, download the new XMLTV, insert
 * it. It is simple and it is why so many IPTV players lose their guide — one stalled
 * download and the user is left with nothing, plus advice to reinstall.
 *
 * OpenTV never deletes before it has the replacement:
 *
 * - Downloads spool to a bounded temporary file before taking a database write lock.
 * - Each feed replaces programmes and aliases in one Room transaction, using batches of 500.
 *   Failed/truncated/empty feeds roll back the entire replacement to the last valid cache.
 * - Old programmes are pruned **by age**, only after at least one feed succeeds.
 * - A failed feed records a credential-free reason and keeps its prior refresh timestamp.
 */
class EpgRepository(
    private val programmeDao: ProgrammeDao,
    private val feedDao: EpgFeedDao,
    private val aliasDao: EpgChannelAliasDao,
    private val channelDao: ChannelDao,
    private val sourceDao: SourceDao,
    private val api: XtreamApi,
    private val http: OkHttpClient,
    private val database: OpenTvDatabase,
    private val cacheDirectory: File,
    private val discovery: AutoEpgDiscovery? = null,
    private val automaticEnabled: () -> Boolean = { true },
    private val feedSuppressed: (String) -> Boolean = { false },
    private val suppressFeed: (String, Boolean) -> Unit = { _, _ -> },
) {

    data class SyncSummary(
        val feedsSucceeded: Int,
        val feedsFailed: Int,
        val programmesWritten: Int,
        val channelsMatched: Int,
        val channelsTotal: Int,
        val discoverySourcesFailed: Int = 0,
    )

    private val refreshMutex = Mutex()
    private var discoveryCache: DiscoveryResult? = null
    private var discoveredAtMillis = 0L
    private var discoveryChannelKeys: Set<String> = emptySet()

    // ---- Reads -----------------------------------------------------------------------------

    fun observeFeeds(): Flow<List<EpgFeed>> = feedDao.observeAll()

    fun observeWindow(fromUtcMillis: Long, toUtcMillis: Long): Flow<List<Programme>> =
        programmeDao.observeWindow(fromUtcMillis, toUtcMillis)

    fun observeNow(nowUtcMillis: Long): Flow<List<Programme>> =
        programmeDao.observeNow(nowUtcMillis)

    /**
     * Queries only the visible guide rows. Chunking keeps even unusual multi-variant channels
     * below SQLite's 999-variable limit without discarding channels or their programmes.
     */
    fun observeWindowForChannels(
        epgChannelIds: List<String>, fromUtcMillis: Long, toUtcMillis: Long,
    ): Flow<List<Programme>> {
        val batches = epgChannelIds.filter { it.isNotBlank() }.distinct().chunked(300)
        if (batches.isEmpty() || toUtcMillis <= fromUtcMillis) return flowOf(emptyList())
        val queries = batches.map { programmeDao.observeWindowForChannels(it, fromUtcMillis, toUtcMillis) }
        return if (queries.size == 1) queries.first() else combine(queries) { results -> results.flatMap { it } }
    }

    fun observeEpgChannelIds(fromUtcMillis: Long, toUtcMillis: Long): Flow<Set<String>> =
        programmeDao.observeEpgChannelIds(fromUtcMillis, toUtcMillis).map { it.toSet() }

    fun observeAiringChannelIds(nowUtcMillis: Long): Flow<Set<String>> =
        programmeDao.observeAiringChannelIds(nowUtcMillis).map { it.toSet() }

    /** Search the current/upcoming week without bringing the full guide into the UI heap. */
    fun programmeSearch(
        query: String,
        filter: EpgSearchFilter,
        nowUtcMillis: Long,
        limit: Int = 200,
        excludedCategories: Set<Pair<Long, String>> = emptySet(),
    ): Flow<List<ProgrammeSearchResult>> {
        if (query.trim().length < 2) return flowOf(emptyList())
        return programmeDao.search(
            epgSearchPattern(query), filter.name, nowUtcMillis,
            nowUtcMillis + TimeUnit.DAYS.toMillis(7), epgExcludedCategories(excludedCategories), limit.coerceIn(1, 200),
        )
    }

    suspend fun upcoming(epgChannelId: String, nowUtcMillis: Long, limit: Int = 12): List<Programme> =
        programmeDao.upcoming(epgChannelId, nowUtcMillis, limit)

    // ---- Feed management -------------------------------------------------------------------

    suspend fun addCustomFeed(name: String, url: String) {
        val trimmed = url.trim()
        if (trimmed.isEmpty() || feedDao.byUrl(trimmed) != null) return
        feedDao.insert(
            EpgFeed(name = name.trim().ifBlank { trimmed }, url = trimmed, enabled = true),
        )
    }

    suspend fun setFeedEnabled(id: Long, enabled: Boolean) = withContext(Dispatchers.IO) { refreshMutex.withLock {
        feedDao.all().firstOrNull { it.id == id }?.url?.let { suppressFeed(it, !enabled) }
        feedDao.setEnabled(id, enabled)
        matchCached(System.currentTimeMillis())
    } }

    suspend fun removeFeed(feed: EpgFeed) = withContext(Dispatchers.IO) { refreshMutex.withLock {
        // Serialize deletion with in-flight downloads so a removed feed cannot reappear.
        database.withTransaction {
            programmeDao.deleteForFeed(feed.id)
            aliasDao.deleteForFeed(feed.id)
            feedDao.delete(feed.id)
        }
        feed.url?.let { suppressFeed(it, true) }
        matchCached(System.currentTimeMillis())
    } }

    /**
     * Makes sure the standing feed rows exist: one per provider source, plus the curated
     * built-in list. Insert-if-absent, so user toggles survive every call.
     */
    suspend fun ensureFeeds() = refreshMutex.withLock { ensureFeedsLocked() }

    private suspend fun ensureFeedsLocked() {
        for (source in sourceDao.enabled()) {
            if (feedDao.forProvider(source.id) == null) {
                feedDao.insert(
                    EpgFeed(
                        name = "${source.name} (provider guide)",
                        providerSourceId = source.id,
                        enabled = true,
                    ),
                )
            }
        }
        for ((name, url) in BUILT_IN_FEEDS) {
            if (feedDao.byUrl(url) == null) {
                // Discovery enables only regions represented in the playlist. Other feeds
                // stay available for manual selection without downloading irrelevant guides.
                feedDao.insert(EpgFeed(name = name, url = url, builtIn = true, enabled = false))
            }
        }
    }

    // ---- Sync ------------------------------------------------------------------------------

    /** Downloads every enabled feed, merges, prunes, and re-runs the matcher. */
    suspend fun syncAll(
        nowUtcMillis: Long,
        force: Boolean = false,
        onProgress: (String) -> Unit = {},
    ): SyncSummary =
        withContext(Dispatchers.IO) { refreshMutex.withLock {
            ensureFeedsLocked()
            val initial = matchCached(nowUtcMillis)
            onProgress("Scanning ${initial.second} channels — ${initial.first} have a working guide…")

            var succeeded = 0
            var failed = 0
            var written = 0

            for (feed in feedDao.enabled()) {
                if (!force && nowUtcMillis - feed.lastSyncMillis < REFRESH_INTERVAL_MILLIS) {
                    succeeded++
                    continue
                }
                when (val result = syncFeed(feed, nowUtcMillis)) {
                    is FeedResult.Success -> {
                        succeeded++
                        written += result.programmes
                        val progress = matchCached(nowUtcMillis)
                        onProgress("Matched ${progress.first} of ${progress.second} channels…")
                    }
                    is FeedResult.Failed -> {
                        failed++
                        // The stamp is NOT advanced on failure, so the next sync retries
                        // rather than waiting out the interval on a feed that never landed.
                        feedDao.markSynced(feed.id, feed.lastSyncMillis, result.reason)
                        Log.w(TAG, "Guide feed ${feed.id} failed: ${result.reason}")
                    }
                }
            }

            var discoveryFailures = 0
            if (automaticEnabled() && discovery != null) {
                val unresolved = unresolvedChannels(nowUtcMillis)
                if (unresolved.isNotEmpty()) {
                    val keys = unresolved.map { "${it.name}|${it.epgChannelId.orEmpty()}" }.toSet()
                    if (force || discoveryCache == null || keys != discoveryChannelKeys ||
                        nowUtcMillis - discoveredAtMillis >= REFRESH_INTERVAL_MILLIS) {
                        onProgress("Searching public guides for ${unresolved.size} channels…")
                        discoveryCache = discovery.discover(unresolved)
                        discoveryChannelKeys = keys
                        discoveredAtMillis = nowUtcMillis
                    }
                    val found = checkNotNull(discoveryCache)
                    discoveryFailures = found.sourcesFailed
                    var attempted = 0
                    for (guide in found.guides) {
                        currentCoroutineContext().ensureActive()
                        if (!automaticEnabled() || attempted >= MAX_DISCOVERED_FEEDS_PER_PASS) break
                        if (feedSuppressed(guide.url)) continue
                        val existing = feedDao.byUrl(guide.url)
                        // Enabled feeds were already handled above. Disabled feeds that the
                        // user turned off are suppressed, including never-synced guides.
                        if (existing?.enabled == true) continue
                        if (unresolvedChannels(nowUtcMillis).isEmpty()) break
                        val feed = if (existing != null) {
                            feedDao.setEnabled(existing.id, true)
                            existing.copy(enabled = true)
                        } else {
                            val row = EpgFeed(name = guide.name, url = guide.url, builtIn = true)
                            val id = feedDao.insert(row)
                            row.copy(id = id)
                        }
                        attempted++
                        when (val result = syncFeed(feed, nowUtcMillis)) {
                            is FeedResult.Success -> {
                                succeeded++
                                written += result.programmes
                                val progress = matchCached(nowUtcMillis)
                                onProgress("Matched ${progress.first} of ${progress.second} channels…")
                            }
                            is FeedResult.Failed -> {
                                failed++
                                feedDao.markSynced(feed.id, feed.lastSyncMillis, result.reason)
                            }
                        }
                    }
                }
            }
            if (succeeded > 0) programmeDao.deleteEndedBefore(nowUtcMillis - RETENTION_PAST_MILLIS)
            val (matched, total) = matchCached(nowUtcMillis)
            SyncSummary(succeeded, failed, written, matched, total, discoveryFailures)
        } }

    private class GuideHttpException(val status: Int) : Exception()

    private sealed interface FeedResult {
        data class Success(val programmes: Int, val channels: Int) : FeedResult
        data class Failed(val reason: String) : FeedResult
    }

    private suspend fun syncFeed(feed: EpgFeed, nowUtcMillis: Long): FeedResult {
        var downloaded: File? = null
        return try {
            // Network IO finishes before taking a database write transaction. The bounded disk
            // spool keeps large guides off the heap and prevents a slow provider blocking writes.
            cacheDirectory.mkdirs()
            val file = File.createTempFile("nuvix-guide-", ".encrypted", cacheDirectory)
            downloaded = file
            // Frames keep decryption bounded even on GCM providers that buffer until a tag.
            val temporaryKey = openFeedStream(feed).use { stream ->
                EncryptedGuideSpool.write(stream, file, MAX_GUIDE_BYTES)
            }

            database.withTransaction {
                val batch = ArrayList<Programme>(BATCH_SIZE)
                val aliases = ArrayList<EpgChannelAlias>(BATCH_SIZE)
                var written = 0
                // All replacement writes and deletes commit together. Parser failures, empty
                // feeds, cancelled work, or database errors roll back to the previous guide.
                programmeDao.deleteForFeed(feed.id)
                aliasDao.deleteForFeed(feed.id)
                val stats = EncryptedGuideSpool.open(file, temporaryKey).use { stream ->
                    XmltvParser.parse(
                        input = stream,
                        feedId = feed.id,
                        onChannelAlias = { id, displayName ->
                            aliases += EpgChannelAlias(
                                feedId = feed.id, epgId = id,
                                displayName = displayName ?: id,
                                normalizedKey = ChannelNameNormalizer.normalize(displayName ?: id).groupKey,
                            )
                            if (aliases.size >= BATCH_SIZE) {
                                currentCoroutineContext().ensureActive()
                                aliasDao.upsertAll(aliases)
                                aliases.clear()
                            }
                        },
                        onProgramme = { programme ->
                            if (programme.endUtcMillis >= nowUtcMillis - RETENTION_PAST_MILLIS) {
                                batch += programme
                                if (batch.size >= BATCH_SIZE) {
                                    currentCoroutineContext().ensureActive()
                                    programmeDao.upsertAll(batch)
                                    written += batch.size
                                    batch.clear()
                                }
                            }
                        },
                    )
                }
                if (batch.isNotEmpty()) {
                    programmeDao.upsertAll(batch)
                    written += batch.size
                }
                if (aliases.isNotEmpty()) aliasDao.upsertAll(aliases)
                check(written > 0) { "Downloaded, but contained no current or upcoming programmes. Previous guide kept." }
                currentCoroutineContext().ensureActive()
                feedDao.markSynced(feed.id, nowUtcMillis, "$written programmes, ${stats.channelCount} channels")
                FeedResult.Success(written, stats.channelCount)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // URLs (including signed query strings) may be included in parser/network exception
            // messages. Keep diagnostics useful without retaining credential-bearing text.
            val reason = when (e) {
                is GuideHttpException -> "Guide download failed (HTTP ${e.status}). Previous guide kept."
                is java.net.SocketTimeoutException -> "Guide download timed out. Previous guide kept."
                is java.io.IOException -> "Guide could not be downloaded. Previous guide kept."
                is org.xmlpull.v1.XmlPullParserException -> "Guide XML was incomplete or invalid. Previous guide kept."
                else -> if (e.message == "Guide exceeds the 256 MB download limit." ||
                    e.message == "Downloaded, but contained no current or upcoming programmes. Previous guide kept.") {
                    e.message!!
                } else "Guide refresh failed. Previous guide kept."
            }
            FeedResult.Failed(reason)
        } finally {
            downloaded?.delete()
        }
    }

    /**
     * Opens a feed as a decompressed XML stream.
     *
     * Several of the free guide sources publish `.gz` files (a national guide compresses
     * roughly 10:1), and providers occasionally gzip xmltv.php without saying so. Sniffing
     * the first two bytes for the gzip magic number handles both, regardless of what the
     * URL or the headers claim.
     */
    private suspend fun openFeedStream(feed: EpgFeed): InputStream {
        val raw: InputStream = when {
            feed.providerSourceId != null -> {
                val source: Source = sourceDao.byId(feed.providerSourceId)
                    ?: throw IllegalStateException("Provider for this guide no longer exists.")
                api.openEpgStream(source)
            }
            feed.url != null -> {
                val response = http.newCall(Request.Builder().url(feed.url).build()).execute()
                if (!response.isSuccessful) {
                    response.close()
                    throw GuideHttpException(response.code)
                }
                response.body?.byteStream()
                    ?: throw IllegalStateException("The server returned an empty guide.")
            }
            else -> throw IllegalStateException("Feed has no URL and no provider.")
        }

        val buffered = BufferedInputStream(raw, 8 * 1024)
        buffered.mark(2)
        val b1 = buffered.read()
        val b2 = buffered.read()
        buffered.reset()
        return if (b1 == 0x1f && b2 == 0x8b) GZIPInputStream(buffered) else buffered
    }

    // ---- Matching --------------------------------------------------------------------------

    /**
     * Joins every channel to the merged guide by normalised name.
     * Returns (channels with a working guide id, total channels).
     */
    suspend fun runMatcher(nowUtcMillis: Long = System.currentTimeMillis()): Pair<Int, Int> =
        withContext(Dispatchers.IO) { refreshMutex.withLock { matchCached(nowUtcMillis) } }

    private suspend fun unresolvedChannels(nowUtcMillis: Long): List<Channel> {
        val populated = programmeDao.activeChannelIds(nowUtcMillis).toHashSet()
        return channelDao.allForMatching().filter { channel ->
            channel.epgOverrideId.isNullOrBlank() && channel.epgCandidates.none { it in populated }
        }
    }

    private suspend fun matchCached(nowUtcMillis: Long): Pair<Int, Int> {
        val populated = programmeDao.activeChannelIds(nowUtcMillis).toHashSet()
        val enabledFeeds = feedDao.enabled().map { it.id }.toHashSet()
        val aliases = aliasDao.all().filter { it.feedId in enabledFeeds && it.epgId in populated }
        val index = EpgMatcher.buildIndex(aliases.map { it.epgId to it.displayName })
        fun regionOfId(id: String): String? = id.substringBefore('@').substringAfterLast('.', "")
            .takeIf { it.length == 2 && it.all(Char::isLetter) }
            ?.let { PublicEpgDiscovery.countryCode(it) }
        val unscoped = aliases.filter { regionOfId(it.epgId) == null }
        val unscopedIndex = EpgMatcher.buildIndex(unscoped.map { it.epgId to it.displayName })
        val regional = aliases.mapNotNull { regionOfId(it.epgId) }.distinct().associateWith { region ->
            EpgMatcher.buildIndex((aliases.filter { regionOfId(it.epgId) == region } + unscoped)
                .map { it.epgId to it.displayName })
        }
        val channels = channelDao.allForMatching()
        val matches = HashMap<Pair<String?, String>, String?>()
        var matched = 0
        for (channel in channels) {
            currentCoroutineContext().ensureActive()
            val newMatch = if (!channel.epgOverrideId.isNullOrBlank()) channel.matchedEpgId else {
                channel.matchedEpgId?.takeIf { it in populated } ?: run {
                    val region = PublicEpgDiscovery.regionOf(channel)
                    val key = channel.groupKey.ifBlank { ChannelNameNormalizer.normalize(channel.name).groupKey }
                    matches.getOrPut(region to key) {
                        // A known country may use unscoped provider ids, but must never pick
                        // a guide explicitly tagged for a different country.
                        val scoped = if (region == null) index else regional[region]
                            ?: unscopedIndex
                        scoped.match(key)
                    }
                }
            }
            if (newMatch != channel.matchedEpgId) channelDao.setMatchedEpgId(channel.id, newMatch)
            if (listOfNotNull(channel.epgOverrideId, channel.epgChannelId, newMatch).any { it in populated }) matched++
        }
        Log.i(TAG, "Matcher: $matched of ${channels.size} channels have a working guide")
        return matched to channels.size
    }

    suspend fun setManualOverride(channelId: Long, epgId: String?) =
        channelDao.setEpgOverride(channelId, epgId)

    companion object {
        private const val TAG = "EpgRepository"

        /** Writes per transaction. Large enough to be fast, small enough not to hold WAL open. */
        const val BATCH_SIZE = 500
        private const val MAX_GUIDE_BYTES = 256L * 1024 * 1024

        /** Keep finished programmes for a day so "what was on" still works. */
        val RETENTION_PAST_MILLIS: Long = TimeUnit.DAYS.toMillis(1)

        /** Feeds publish rolling windows; refreshing more often than this is rude. */
        val REFRESH_INTERVAL_MILLIS: Long = TimeUnit.HOURS.toMillis(6)

        /** Bound new downloads per pass; remaining candidates are picked up on later refreshes. */
        private const val MAX_DISCOVERED_FEEDS_PER_PASS = 12

        /** Every relevant regional fallback is tried, including mixed-country playlists. */
        val REGION_TO_FEEDS: Map<String, List<String>> = mapOf(
            "GB" to listOf("UK — Freeview (free-to-air)", "UK — epgshare01 (Sky lineup)"),
            "US" to listOf("USA — epgshare01"),
            "CA" to listOf("Canada — epgshare01"),
            "AU" to listOf("Australia — epgshare01"),
        )

        /** Public XMLTV sources, seeded disabled until discovery finds a relevant region. */
        val BUILT_IN_FEEDS: List<Pair<String, String>> = listOf(
            "UK — Freeview (free-to-air)" to
                "https://raw.githubusercontent.com/dp247/Freeview-EPG/master/epg.xml",
            "UK — epgshare01 (Sky lineup)" to
                "https://epgshare01.online/epgshare01/epg_ripper_UK1.xml.gz",
            "USA — epgshare01" to
                "https://epgshare01.online/epgshare01/epg_ripper_US1.xml.gz",
            "Canada — epgshare01" to
                "https://epgshare01.online/epgshare01/epg_ripper_CA1.xml.gz",
            "Australia — epgshare01" to
                "https://epgshare01.online/epgshare01/epg_ripper_AU1.xml.gz",
        )
    }
}
