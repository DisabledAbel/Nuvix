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
import app.opentv.data.db.SourceDao
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
) {

    data class SyncSummary(
        val feedsSucceeded: Int,
        val feedsFailed: Int,
        val programmesWritten: Int,
        val channelsMatched: Int,
        val channelsTotal: Int,
    )

    private val refreshMutex = Mutex()

    // ---- Reads -----------------------------------------------------------------------------

    fun observeFeeds(): Flow<List<EpgFeed>> = feedDao.observeAll()

    fun observeWindow(fromUtcMillis: Long, toUtcMillis: Long): Flow<List<Programme>> =
        programmeDao.observeWindow(fromUtcMillis, toUtcMillis)

    fun observeNow(nowUtcMillis: Long): Flow<List<Programme>> =
        programmeDao.observeNow(nowUtcMillis)

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

    suspend fun setFeedEnabled(id: Long, enabled: Boolean) = feedDao.setEnabled(id, enabled)

    suspend fun removeFeed(feed: EpgFeed) = refreshMutex.withLock {
        // Serialize deletion with in-flight downloads so a removed feed cannot reappear.
        database.withTransaction {
            programmeDao.deleteForFeed(feed.id)
            aliasDao.deleteForFeed(feed.id)
            feedDao.delete(feed.id)
        }
    }

    /**
     * Makes sure the standing feed rows exist: one per provider source, plus the curated
     * built-in list. Insert-if-absent, so user toggles survive every call.
     */
    suspend fun ensureFeeds() {
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
                // Off by default: shipping a switched-on 20 MB download for a country the
                // user may not live in would be rude. The EPG settings screen makes
                // enabling one a single click.
                feedDao.insert(EpgFeed(name = name, url = url, builtIn = true, enabled = false))
            }
        }
    }

    // ---- Sync ------------------------------------------------------------------------------

    /** Downloads every enabled feed, merges, prunes, and re-runs the matcher. */
    suspend fun syncAll(nowUtcMillis: Long, force: Boolean = false): SyncSummary =
        withContext(Dispatchers.IO) { refreshMutex.withLock {
            ensureFeeds()
            maybeAutoEnableRegionalFeed()

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

            if (succeeded > 0) {
                programmeDao.deleteEndedBefore(nowUtcMillis - RETENTION_PAST_MILLIS)
            }

            val (matched, total) = runMatcher()
            SyncSummary(succeeded, failed, written, matched, total)
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
    suspend fun runMatcher(): Pair<Int, Int> {
        val aliases = aliasDao.all()
        val index = EpgMatcher.buildIndex(aliases.map { it.epgId to it.displayName })
        // Only ids that actually have programmes count as "working" — a match against a
        // channel the guide lists but never fills is a guide that looks broken.
        val populated = programmeDao.channelIdsWithProgrammes().toHashSet()

        val channels = channelDao.allForMatching()
        var matched = 0

        for (channel in channels) {
            val newMatch = index.match(channel.groupKey)
            if (newMatch != channel.matchedEpgId) {
                channelDao.setMatchedEpgId(channel.id, newMatch)
            }
            val works = channel.epgCandidates.any { it in populated } ||
                (newMatch != null && newMatch in populated)
            if (works) matched++
        }

        Log.i(
            TAG,
            "Matcher: $matched of ${channels.size} channels have a working guide " +
                "(${aliases.size} guide aliases, ${populated.size} populated guide channels)",
        )
        return matched to channels.size
    }

    suspend fun setManualOverride(channelId: Long, epgId: String?) =
        channelDao.setEpgOverride(channelId, epgId)

    /**
     * Turns on the free guide for the user's region, once, on a pristine install.
     *
     * The reasoning: a provider guide is usually empty, and "go and find Guide settings"
     * is a hurdle most people meet as a blank guide and give up on. The app already knows
     * the region — the normaliser reads it off the `UK|` prefixes the provider itself
     * ships — so when a clear majority of channels agree, enable that region's built-in.
     *
     * Guard rails: only when every built-in is untouched (never enabled, never synced)
     * and no custom feed exists. Switch it off and it stays off — a choice the user has
     * made is never overridden.
     */
    private suspend fun maybeAutoEnableRegionalFeed() {
        val all = feedDao.all()
        val builtIns = all.filter { it.builtIn }
        if (builtIns.isEmpty()) return
        val pristine = builtIns.all { !it.enabled && it.lastSyncMillis == 0L } &&
            all.none { !it.builtIn && it.providerSourceId == null }
        if (!pristine) return

        val regionCounts = HashMap<String, Int>()
        for (channel in channelDao.allForMatching()) {
            val region = ChannelNameNormalizer.normalize(channel.name).region ?: continue
            regionCounts.merge(region, 1, Int::plus)
        }
        val top = regionCounts.maxByOrNull { it.value } ?: return
        if (top.value < MIN_CHANNELS_FOR_AUTO_REGION) return

        val feedName = REGION_TO_FEED[top.key] ?: return
        val feed = builtIns.firstOrNull { it.name == feedName } ?: return
        feedDao.setEnabled(feed.id, true)
        Log.i(TAG, "Auto-enabled '${feed.name}' — ${top.value} channels tagged ${top.key}")
    }

    companion object {
        private const val TAG = "EpgRepository"

        /** Writes per transaction. Large enough to be fast, small enough not to hold WAL open. */
        const val BATCH_SIZE = 500
        private const val MAX_GUIDE_BYTES = 256L * 1024 * 1024

        /** Keep finished programmes for a day so "what was on" still works. */
        val RETENTION_PAST_MILLIS: Long = TimeUnit.DAYS.toMillis(1)

        /** Feeds publish rolling windows; refreshing more often than this is rude. */
        val REFRESH_INTERVAL_MILLIS: Long = TimeUnit.HOURS.toMillis(6)

        /**
         * The curated built-in list. Criteria for being here: free, no key or signup,
         * openly licensed or explicitly public, and reachable as plain XMLTV(.gz).
         * All ship disabled; the user turns on the one for where they live.
         *
         * The UK Freeview entry is GPL-3.0 (same licence as OpenTV), regenerated every
         * 12 h, ~270 channels with logos — verified working before it earned this slot.
         */
        /** A region needs at least this many prefixed channels before we act on it. */
        const val MIN_CHANNELS_FOR_AUTO_REGION = 5

        /** Region tag (as providers write it) → built-in feed name to auto-enable. */
        val REGION_TO_FEED: Map<String, String> = mapOf(
            "UK" to "UK — Freeview (free-to-air)",
            "GB" to "UK — Freeview (free-to-air)",
            "US" to "USA — epgshare01",
            "USA" to "USA — epgshare01",
            "CA" to "Canada — epgshare01",
            "AU" to "Australia — epgshare01",
            "AUS" to "Australia — epgshare01",
        )

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
