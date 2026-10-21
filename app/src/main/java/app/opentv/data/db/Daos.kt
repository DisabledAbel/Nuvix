/* Nuvix modifications Copyright (C) 2026 Nuvix Contributors. GPL-3.0-or-later. */
/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.data.db

import androidx.room.Dao
import androidx.room.Embedded
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Relation
import androidx.room.Transaction
import androidx.room.Update
import androidx.room.Upsert
import app.opentv.data.model.Category
import app.opentv.data.model.Channel
import app.opentv.data.model.EpgChannelAlias
import app.opentv.data.model.EpgFeed
import app.opentv.data.model.Episode
import app.opentv.data.model.Movie
import app.opentv.data.model.PlaybackPosition
import app.opentv.data.model.Profile
import app.opentv.data.model.Programme
import app.opentv.data.model.Recording
import app.opentv.data.model.RecordingEvent
import app.opentv.data.model.RecordingStatus
import app.opentv.data.model.Reminder
import app.opentv.data.model.Series
import app.opentv.data.model.SeriesRule
import app.opentv.data.model.Source
import app.opentv.data.model.StreamKind
import kotlinx.coroutines.flow.Flow

/** Rows per upsert statement. Keeps a 40,000-channel playlist from building one huge query. */
private const val UPSERT_CHUNK = 500

/** Room queries and mutations for source records. */
@Dao
interface SourceDao {
    /** Observes the stored source rows as they change. */
    @Query("SELECT * FROM sources ORDER BY id")
    fun observeAll(): Flow<List<Source>>

    /** Returns source rows that are currently enabled. */
    @Query("SELECT * FROM sources WHERE enabled = 1 ORDER BY id")
    suspend fun enabled(): List<Source>

    /** Returns the source row with the supplied local ID, or null if absent. */
    @Query("SELECT * FROM sources WHERE id = :id")
    suspend fun byId(id: Long): Source?

    /** Inserts a source row using the declared Room conflict policy and returns its row ID. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(source: Source): Long

    /** Updates the stored source row using its primary key. */
    @Update
    suspend fun update(source: Source)

    /** Deletes the source row with the supplied local ID. */
    @Query("DELETE FROM sources WHERE id = :id")
    suspend fun delete(id: Long)

    /** Records the timestamp of the source's last successful catalog refresh. */
    @Query("UPDATE sources SET lastCatalogSyncMillis = :millis WHERE id = :id")
    suspend fun markCatalogSynced(id: Long, millis: Long)
}

/** Room queries and mutations for channel records. */
@Dao
interface ChannelDao {
    /** Bounded home shelves avoid materialising a full provider catalogue. */
    @Query("SELECT * FROM channels WHERE hidden = 0 ORDER BY favourite DESC, sortIndex, displayName LIMIT :limit")
    fun observeHomeChannels(limit: Int = 24): Flow<List<Channel>>

    /** Observes visible channels matching the supplied local row IDs. */
    @Query("SELECT * FROM channels WHERE id IN (:ids) AND hidden = 0")
    fun observeByIds(ids: List<Long>): Flow<List<Channel>>

    /** Sports discovery uses only category metadata from the user's provider. */
    @Query("""SELECT channels.* FROM channels INNER JOIN categories
        ON channels.sourceId = categories.sourceId AND channels.categoryId = categories.id
        WHERE channels.hidden = 0 AND categories.kind = 'LIVE'
        AND (LOWER(categories.name) LIKE '%sport%' OR LOWER(categories.name) LIKE '%football%'
        OR LOWER(categories.name) LIKE '%soccer%' OR LOWER(categories.name) LIKE '%cricket%'
        OR LOWER(categories.name) LIKE '%basketball%' OR LOWER(categories.name) LIKE '%hockey%')
        ORDER BY channels.sortIndex, channels.displayName LIMIT :limit""")
    fun observeSportsChannels(limit: Int = 200): Flow<List<Channel>>

    /** Observes visible channels matching the optional source and category filters. */
    @Query(
        """
        SELECT * FROM channels
        WHERE hidden = 0
          AND (:sourceId IS NULL OR sourceId = :sourceId)
          AND (:categoryId IS NULL OR categoryId = :categoryId)
        ORDER BY sortIndex, displayName
        """
    )
    fun observe(sourceId: Long?, categoryId: String?): Flow<List<Channel>>

    /** Observes visible favorite channels in user order, then display-name order. */
    @Query("SELECT * FROM channels WHERE favourite = 1 AND hidden = 0 ORDER BY sortIndex, displayName")
    fun observeFavourites(): Flow<List<Channel>>

    /**
     * Channels across a SET of provider categories. Needed because one logical category
     * ("General") is often shipped as several codec-split ones ("UK| GENERAL HD/RAW",
     * "UK| GENERAL hevc") — the UI merges them into a single rail entry. The list is a
     * handful of ids, nowhere near SQLite's bound-variable cap.
     */
    @Query(
        """
        SELECT * FROM channels
        WHERE hidden = 0 AND categoryId IN (:categoryIds)
        ORDER BY sortIndex, displayName
        """
    )
    fun observeInCategories(categoryIds: List<String>): Flow<List<Channel>>

    /**
     * Channels across a set of categories INCLUDING hidden ones, optionally scoped to one source
     * — the channel manager's browse query.
     *
     * The guide's [observe]/[observeInCategories] filter `hidden = 0`; the manager can't, because
     * its whole job is finding a hidden channel and switching it back on. Still scoped to a
     * category set (never the whole table) so the list stays a size a remote can actually scroll.
     */
    @Query(
        """
        SELECT * FROM channels
        WHERE (:sourceId IS NULL OR sourceId = :sourceId)
          AND categoryId IN (:categoryIds)
        ORDER BY sortIndex, displayName
        """
    )
    fun observeInCategoriesIncludingHidden(sourceId: Long?, categoryIds: List<String>): Flow<List<Channel>>

    /**
     * One-shot (non-Flow) read of ONE category's channels INCLUDING hidden, optionally scoped to a
     * single source — the web channel manager's per-category fetch. That server handles one HTTP
     * request at a time on a socket thread and wants a plain list, not a subscription, so this is
     * the suspend `List` sibling of [observeInCategoriesIncludingHidden], narrowed to one category.
     */
    @Query(
        """
        SELECT * FROM channels
        WHERE (:sourceId IS NULL OR sourceId = :sourceId)
          AND categoryId = :categoryId
        ORDER BY sortIndex, displayName
        """
    )
    suspend fun channelsInCategoryIncludingHidden(sourceId: Long?, categoryId: String): List<Channel>

    /**
     * Channel count per (source, category), hidden rows included — the web manager's category list
     * shows how many channels each holds. Grouped by (sourceId, categoryId) because a category id
     * is only unique within a source.
     */
    @Query(
        """
        SELECT sourceId, categoryId, COUNT(*) AS count
        FROM channels
        WHERE categoryId IS NOT NULL
        GROUP BY sourceId, categoryId
        """
    )
    suspend fun channelCountsByCategory(): List<CategoryChannelCount>

    /** Observes a bounded name search over visible channels, prioritizing favorites and user order. */
    @Query(
        """
        SELECT * FROM channels
        WHERE hidden = 0
          AND (displayName LIKE '%' || :query || '%' OR name LIKE '%' || :query || '%')
        ORDER BY favourite DESC, sortIndex, displayName
        LIMIT :limit
        """
    )
    fun search(query: String, limit: Int = 200): Flow<List<Channel>>

    /** Like [search] but keeps hidden channels in — the channel manager needs them to unhide. */
    @Query(
        """
        SELECT * FROM channels
        WHERE (displayName LIKE '%' || :query || '%' OR name LIKE '%' || :query || '%')
        ORDER BY hidden, favourite DESC, sortIndex, displayName
        LIMIT :limit
        """
    )
    fun searchIncludingHidden(query: String, limit: Int = 200): Flow<List<Channel>>

    /** Returns the channel row with the supplied local ID, or null if absent. */
    @Query("SELECT * FROM channels WHERE id = :id")
    suspend fun byId(id: Long): Channel?

    /** Every quality variant of one logical channel, best first. */
    @Query(
        """
        SELECT * FROM channels
        WHERE groupKey = :groupKey AND groupKey != '' AND hidden = 0
        ORDER BY qualityRank DESC, displayName
        """
    )
    suspend fun variantsInGroup(groupKey: String): List<Channel>

    /** Counts channel rows belonging to the source. */
    @Query("SELECT COUNT(*) FROM channels WHERE sourceId = :sourceId")
    suspend fun countForSource(sourceId: Long): Int

    /**
     * Reactive count of channels the guide can actually show (hidden rows excluded). Lets the home
     * screen tell "the guide is still building from channels on disk" apart from "nothing loaded",
     * so a failed or empty sync surfaces an error instead of spinning forever.
     */
    @Query("SELECT COUNT(*) FROM channels WHERE hidden = 0")
    fun observeVisibleCount(): Flow<Int>

    /** Updates the favorite flag and marks that field as locally edited for synchronization. */
    @Query("UPDATE channels SET favourite = :favourite, syncTouched = syncTouched | 1 WHERE id = :id")
    suspend fun setFavourite(id: Long, favourite: Boolean)

    /** Updates visibility and marks that field as locally edited for synchronization. */
    @Query("UPDATE channels SET hidden = :hidden, syncTouched = syncTouched | 2 WHERE id = :id")
    suspend fun setHidden(id: Long, hidden: Boolean)

    /** Updates the user-supplied name and marks that field as locally edited. */
    @Query("UPDATE channels SET customName = :name, syncTouched = syncTouched | 4 WHERE id = :id")
    suspend fun setCustomName(id: Long, name: String?)

    /** Updates user ordering and marks that field as locally edited. */
    @Query("UPDATE channels SET sortIndex = :sortIndex, syncTouched = syncTouched | 8 WHERE id = :id")
    suspend fun setSortIndex(id: Long, sortIndex: Int)

    /** Every channel for a source. The channels table is live-only, so these are its live channels. */
    @Query("SELECT * FROM channels WHERE sourceId = :sourceId")
    suspend fun forSource(sourceId: Long): List<Channel>

    /** Rewrites one channel's resolved playback URL — used when a source's live stream format changes. */
    @Query("UPDATE channels SET streamUrl = :url WHERE id = :id")
    suspend fun updateStreamUrl(id: Long, url: String)

    /** Returns stream URLs identifying locally favored channels for LAN synchronization. */
    // --- Sync: identify favourites/hidden by stream URL (stable across devices) ---
    @Query("SELECT streamUrl FROM channels WHERE favourite = 1")
    suspend fun favouriteUrls(): List<String>

    /** Returns stream URLs identifying hidden channels for LAN synchronization. */
    @Query("SELECT streamUrl FROM channels WHERE hidden = 1")
    suspend fun hiddenUrls(): List<String>

    /** Marks all channels with the matching stream URL as favorites. */
    @Query("UPDATE channels SET favourite = 1 WHERE streamUrl = :url")
    suspend fun markFavouriteByUrl(url: String)

    /** Marks all channels with the matching stream URL as hidden. */
    @Query("UPDATE channels SET hidden = 1 WHERE streamUrl = :url")
    suspend fun markHiddenByUrl(url: String)

    /** Updates the automatically matched guide channel ID. */
    @Query("UPDATE channels SET matchedEpgId = :epgId WHERE id = :id")
    suspend fun setMatchedEpgId(id: Long, epgId: String?)

    /** Sets or clears the user's explicit guide-channel mapping. */
    @Query("UPDATE channels SET epgOverrideId = :epgId WHERE id = :id")
    suspend fun setEpgOverride(id: Long, epgId: String?)

    /** The matcher's working set: id, group key, and what is already known. */
    @Query("SELECT * FROM channels")
    suspend fun allForMatching(): List<Channel>

    /** Inserts or updates the supplied channel rows. */
    @Upsert
    suspend fun upsertAll(channels: List<Channel>)

    /**
     * Deletes channels the provider no longer lists.
     *
     * Compares a sync stamp rather than using `streamId NOT IN (:allCurrentIds)`. The latter
     * is the obvious implementation and it breaks on exactly the providers where it matters:
     * SQLite caps bound variables (999 on older Android, 32766 on newer) and a large playlist
     * has 40,000 channels, so the query throws and the catalogue never converges.
     *
     * Note also the deliberate absence of any "delete everything then insert" method. Wiping
     * first means a sync that fails halfway leaves the user with an empty app — which is how
     * a player ends up telling people to reinstall to get their channels back.
     */
    @Query("DELETE FROM channels WHERE sourceId = :sourceId AND lastSeenMillis < :syncStamp")
    suspend fun deleteStale(sourceId: Long, syncStamp: Long)

    /** Deletes channel rows belonging to the source. */
    @Query("DELETE FROM channels WHERE sourceId = :sourceId")
    suspend fun deleteForSource(sourceId: Long)

    /**
     * Preserves user state (favourites, hidden, manual order, custom names, EPG overrides) across a
     * catalogue refresh. Upsert would otherwise overwrite them with the defaults from the
     * freshly parsed rows.
     */
    @Transaction
    suspend fun replaceCatalogue(sourceId: Long, incoming: List<Channel>, syncStamp: Long) {
        if (incoming.isEmpty()) return

        val existing = userStateForSource(sourceId).associateBy { it.streamId }
        val merged = incoming.map { channel ->
            val previous = existing[channel.streamId]
            if (previous == null) {
                channel.copy(lastSeenMillis = syncStamp)
            } else {
                channel.copy(
                    id = previous.id,
                    favourite = previous.favourite,
                    syncTouched = previous.syncTouched,
                    // Provider separator hiding is a baseline until the user explicitly
                    // chooses visibility. Preserve an explicit unhide through refresh too.
                    hidden = if (previous.syncTouched and 2 != 0) previous.hidden else previous.hidden || channel.hidden,
                    sortIndex = previous.sortIndex,
                    // A user's rename must outlive the provider's fresh name on every refresh.
                    customName = previous.customName,
                    epgOverrideId = previous.epgOverrideId,
                    matchedEpgId = previous.matchedEpgId,
                    lastSeenMillis = syncStamp,
                )
            }
        }
        // Chunked so a 40,000-channel playlist does not build one enormous statement.
        merged.chunked(UPSERT_CHUNK).forEach { upsertAll(it) }
        deleteStale(sourceId, syncStamp)
    }

    /** Returns source-scoped channel rows so user edits can survive a catalog refresh. */
    @Query("SELECT * FROM channels WHERE sourceId = :sourceId")
    suspend fun userStateForSource(sourceId: Long): List<Channel>
}

/** Room queries and mutations for category records. */
@Dao
interface CategoryDao {
    /** Observes categories of the requested stream kind in user and name order. */
    @Query("SELECT * FROM categories WHERE kind = :kind ORDER BY sortIndex, name")
    fun observe(kind: StreamKind): Flow<List<Category>>

    /** One-shot list of every category of a kind, ordered like [observe] — the web manager reads
     * the live categories once per request rather than subscribing to a Flow. */
    @Query("SELECT * FROM categories WHERE kind = :kind ORDER BY sortIndex, name")
    suspend fun allByKind(kind: StreamKind): List<Category>

    /** Minimal id+name rows for the re-normalise pass. */
    @Query("SELECT id, name FROM categories WHERE id IN (:ids)")
    suspend fun namesFor(ids: Set<String>): List<CategoryName>

    /** Inserts or updates the supplied category rows. */
    @Upsert
    suspend fun upsertAll(categories: List<Category>)

    /** Deletes category rows belonging to the source. */
    @Query("DELETE FROM categories WHERE sourceId = :sourceId")
    suspend fun deleteForSource(sourceId: Long)
}

/** Projection for [CategoryDao.namesFor]. */
data class CategoryName(val id: String, val name: String)

/** Projection for [ChannelDao.channelCountsByCategory]. */
data class CategoryChannelCount(val sourceId: Long, val categoryId: String, val count: Int)

/** Room queries and mutations for epg feed records. */
@Dao
interface EpgFeedDao {
    /** Observes feeds with built-in feeds first, then local ID order. */
    @Query("SELECT * FROM epg_feeds ORDER BY builtIn DESC, id")
    fun observeAll(): Flow<List<EpgFeed>>

    /** Returns all stored epg feed rows. */
    @Query("SELECT * FROM epg_feeds")
    suspend fun all(): List<EpgFeed>

    /** Returns epg feed rows that are currently enabled. */
    @Query("SELECT * FROM epg_feeds WHERE enabled = 1")
    suspend fun enabled(): List<EpgFeed>

    /** Returns the guide feed attached to a provider source, if present. */
    @Query("SELECT * FROM epg_feeds WHERE providerSourceId = :sourceId")
    suspend fun forProvider(sourceId: Long): EpgFeed?

    /** Returns the epg feed row matching the supplied URL, or null if absent. */
    @Query("SELECT * FROM epg_feeds WHERE url = :url")
    suspend fun byUrl(url: String): EpgFeed?

    /** Inserts a epg feed row using the declared Room conflict policy and returns its row ID. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(feed: EpgFeed): Long

    /** Updates the stored epg feed row using its primary key. */
    @Update
    suspend fun update(feed: EpgFeed)

    /** Updates whether the epg feed row is enabled. */
    @Query("UPDATE epg_feeds SET enabled = :enabled WHERE id = :id")
    suspend fun setEnabled(id: Long, enabled: Boolean)

    /** Records a feed's latest synchronization timestamp and result message. */
    @Query("UPDATE epg_feeds SET lastSyncMillis = :millis, lastResult = :result WHERE id = :id")
    suspend fun markSynced(id: Long, millis: Long, result: String)

    /** Deletes the epg feed row with the supplied local ID. */
    @Query("DELETE FROM epg_feeds WHERE id = :id")
    suspend fun delete(id: Long)
}

/** Room queries and mutations for epg channel alias records. */
@Dao
interface EpgChannelAliasDao {
    /** Returns all stored epg channel alias rows. */
    @Query("SELECT * FROM epg_channels")
    suspend fun all(): List<EpgChannelAlias>

    /** Returns the total number of stored epg channel alias rows. */
    @Query("SELECT COUNT(*) FROM epg_channels")
    suspend fun count(): Int

    /** Inserts or updates the supplied epg channel alias rows. */
    @Upsert
    suspend fun upsertAll(aliases: List<EpgChannelAlias>)

    /** Deletes epg channel alias rows belonging to the guide feed. */
    @Query("DELETE FROM epg_channels WHERE feedId = :feedId")
    suspend fun deleteForFeed(feedId: Long)
}

/** A bounded search row, with its playable channel resolved in one batched Room relation. */
data class ProgrammeSearchResult(
    @Embedded val programme: Programme,
    val channelId: Long,
    @Relation(parentColumn = "channelId", entityColumn = "id") val channel: Channel,
)

/** Room queries and mutations for programme records. */
@Dao
interface ProgrammeDao {
    /** The visible channel batch only; callers keep ids below SQLite's legacy bind limit. */
    @Query("""
        SELECT * FROM programmes
        WHERE feedId IN (SELECT id FROM epg_feeds WHERE enabled = 1)
          AND epgChannelId IN (:channelIds)
          AND endUtcMillis > :fromUtcMillis AND startUtcMillis < :toUtcMillis
        ORDER BY epgChannelId, startUtcMillis, feedId
    """)
    fun observeWindowForChannels(channelIds: List<String>, fromUtcMillis: Long, toUtcMillis: Long): Flow<List<Programme>>

    /** Lightweight metadata for full-lineup filters; never materialises programme descriptions. */
    @Query("""
        SELECT DISTINCT epgChannelId FROM programmes
        WHERE feedId IN (SELECT id FROM epg_feeds WHERE enabled = 1)
          AND endUtcMillis > :fromUtcMillis AND startUtcMillis < :toUtcMillis
    """)
    fun observeEpgChannelIds(fromUtcMillis: Long, toUtcMillis: Long): Flow<List<String>>

    /** Observes guide channel IDs with a currently airing program from an enabled feed. */
    @Query("""
        SELECT DISTINCT epgChannelId FROM programmes
        WHERE feedId IN (SELECT id FROM epg_feeds WHERE enabled = 1)
          AND startUtcMillis <= :nowUtcMillis AND endUtcMillis > :nowUtcMillis
    """)
    fun observeAiringChannelIds(nowUtcMillis: Long): Flow<List<String>>

    /**
     * Search remains in Room, bounded by time and result count. Quality variants collapse to
     * one result per logical channel; only provider metadata identifies sports/movies/series.
     * CROSS JOIN fixes the loop order so SQLite uses the programme channel-id index instead of
     * scanning the channel catalogue again for every programme in a large seven-day guide.
     */
    @Transaction
    @Query("""
        SELECT p.*, MIN(c.id) AS channelId FROM channels c
        INNER JOIN sources s ON s.id = c.sourceId AND s.enabled = 1
        CROSS JOIN programmes p
        INNER JOIN epg_feeds f ON f.id = p.feedId AND f.enabled = 1
        WHERE c.hidden = 0 AND p.epgChannelId IN (c.epgOverrideId, c.epgChannelId, c.matchedEpgId)
          AND p.endUtcMillis > :nowUtcMillis AND p.startUtcMillis < :untilUtcMillis
          AND (c.categoryId IS NULL OR instr(:excludedCategories, '|' || c.sourceId || ':' || hex(c.categoryId) || '|') = 0)
          AND (p.epgChannelId = c.epgOverrideId
            OR (p.epgChannelId = c.epgChannelId AND NOT EXISTS (
              SELECT 1 FROM programmes preferred INNER JOIN epg_feeds pf ON pf.id = preferred.feedId AND pf.enabled = 1
              WHERE preferred.epgChannelId = c.epgOverrideId AND preferred.endUtcMillis > :nowUtcMillis
                AND preferred.startUtcMillis < :untilUtcMillis))
            OR (p.epgChannelId = c.matchedEpgId AND NOT EXISTS (
              SELECT 1 FROM programmes preferred INNER JOIN epg_feeds pf ON pf.id = preferred.feedId AND pf.enabled = 1
              WHERE (preferred.epgChannelId = c.epgOverrideId OR preferred.epgChannelId = c.epgChannelId)
                AND preferred.endUtcMillis > :nowUtcMillis AND preferred.startUtcMillis < :untilUtcMillis)))
          AND (p.title LIKE :pattern ESCAPE '\' OR p.description LIKE :pattern ESCAPE '\'
            OR p.category LIKE :pattern ESCAPE '\' OR c.name LIKE :pattern ESCAPE '\'
            OR c.displayName LIKE :pattern ESCAPE '\' OR c.customName LIKE :pattern ESCAPE '\')
          AND (:filter != 'CURRENTLY_AIRING' OR p.startUtcMillis <= :nowUtcMillis)
          AND (:filter != 'UPCOMING' OR p.startUtcMillis > :nowUtcMillis)
          AND (:filter != 'MOVIES' OR LOWER(p.category) LIKE '%movie%' OR LOWER(p.category) LIKE '%film%')
          AND (:filter != 'SPORTS' OR LOWER(p.category) LIKE '%sport%' OR LOWER(p.category) LIKE '%football%'
            OR LOWER(p.category) LIKE '%soccer%' OR LOWER(p.category) LIKE '%cricket%'
            OR LOWER(p.category) LIKE '%basketball%' OR LOWER(p.category) LIKE '%baseball%'
            OR LOWER(p.category) LIKE '%tennis%' OR LOWER(p.category) LIKE '%rugby%'
            OR LOWER(p.category) LIKE '%hockey%' OR LOWER(p.category) LIKE '%athletics%')
          AND (:filter != 'SERIES' OR p.season IS NOT NULL OR p.episode IS NOT NULL
            OR LOWER(p.category) LIKE '%series%' OR LOWER(p.category) LIKE '%sitcom%')
        GROUP BY p.id, c.sourceId, CASE WHEN c.groupKey = '' THEN 'id:' || c.id ELSE 'group:' || c.groupKey END
        ORDER BY p.startUtcMillis, p.title, channelId
        LIMIT :limit
    """)
    fun search(pattern: String, filter: String, nowUtcMillis: Long, untilUtcMillis: Long,
        excludedCategories: String, limit: Int): Flow<List<ProgrammeSearchResult>>

    /**
     * The guide grid: everything overlapping the visible window.
     *
     * Filtered by time only, never by a list of channel ids. `epgChannelId IN (:ids)` is the
     * natural way to write this and it breaks on any provider large enough to matter — SQLite
     * caps bound variables at 999 on older Android, and a real catalogue has thousands of
     * channels. Callers group the result by channel in memory instead.
     */
    @Query(
        """
        SELECT * FROM programmes
        WHERE feedId IN (SELECT id FROM epg_feeds WHERE enabled = 1)
          AND endUtcMillis > :fromUtcMillis
          AND startUtcMillis < :toUtcMillis
        ORDER BY epgChannelId, startUtcMillis
        """
    )
    fun observeWindow(fromUtcMillis: Long, toUtcMillis: Long): Flow<List<Programme>>

    /** What is on right now, for the channel list's "now playing" line. */
    @Query(
        """
        SELECT * FROM programmes
        WHERE feedId IN (SELECT id FROM epg_feeds WHERE enabled = 1)
          AND startUtcMillis <= :nowUtcMillis AND endUtcMillis > :nowUtcMillis
        """
    )
    fun observeNow(nowUtcMillis: Long): Flow<List<Programme>>

    /** Observes a bounded list of current or future programs for a guide channel from enabled feeds. */
    @Query(
        """
        SELECT * FROM programmes
        WHERE feedId IN (SELECT id FROM epg_feeds WHERE enabled = 1)
          AND epgChannelId = :channelId AND endUtcMillis > :nowUtcMillis
        ORDER BY startUtcMillis LIMIT :limit
        """
    )
    suspend fun upcoming(channelId: String, nowUtcMillis: Long, limit: Int): List<Programme>

    /** Counts programme rows belonging to the guide feed. */
    @Query("SELECT COUNT(*) FROM programmes WHERE feedId = :feedId")
    suspend fun countForFeed(feedId: Long): Int

    /** Distinct guide channels that actually have programmes — the match report's baseline. */
    @Query("SELECT DISTINCT epgChannelId FROM programmes")
    suspend fun channelIdsWithProgrammes(): List<String>

    /** An automatic TVG-id is valid only while an enabled feed has unexpired programmes. */
    @Query("SELECT DISTINCT p.epgChannelId FROM programmes p INNER JOIN epg_feeds f ON f.id = p.feedId WHERE f.enabled = 1 AND p.endUtcMillis > :nowUtcMillis")
    suspend fun activeChannelIds(nowUtcMillis: Long): List<String>

    /** Inserts or updates the supplied programme rows. */
    @Upsert
    suspend fun upsertAll(programmes: List<Programme>)

    /** Housekeeping: drop anything that finished before the retention cut-off. */
    @Query("DELETE FROM programmes WHERE endUtcMillis < :beforeUtcMillis")
    suspend fun deleteEndedBefore(beforeUtcMillis: Long)

    /** Deletes programme rows belonging to the guide feed. */
    @Query("DELETE FROM programmes WHERE feedId = :feedId")
    suspend fun deleteForFeed(feedId: Long)
}

/** Room queries and mutations for movie records. */
@Dao
interface MovieDao {
    /** Observes movies in the optional category, newest additions first. */
    @Query(
        """
        SELECT * FROM movies
        WHERE (:categoryId IS NULL OR categoryId = :categoryId)
        ORDER BY addedMillis DESC, name
        """
    )
    fun observe(categoryId: String?): Flow<List<Movie>>

    /** Observes favorite movies in name order. */
    @Query("SELECT * FROM movies WHERE favourite = 1 ORDER BY name")
    fun observeFavourites(): Flow<List<Movie>>

    /** Observes a bounded name search over movie rows. */
    @Query("SELECT * FROM movies WHERE name LIKE '%' || :query || '%' ORDER BY name LIMIT :limit")
    fun search(query: String, limit: Int = 200): Flow<List<Movie>>

    /** Newest-first, for the "Recently Added" home row. Reactive so it fills in as VOD sync lands. */
    @Query("SELECT * FROM movies ORDER BY addedMillis DESC LIMIT :limit")
    fun observeRecentlyAdded(limit: Int): Flow<List<Movie>>

    /**
     * Every movie, newest first — the working set for the Kotlin-side feeds (by-genre grouping,
     * genre-affinity recommendations, more-like-this). One pass over a few thousand rows is cheap;
     * per-genre `LIKE` queries would multiply round-trips and risk substring false positives.
     */
    @Query("SELECT * FROM movies ORDER BY addedMillis DESC")
    suspend fun all(): List<Movie>

    /** How many movies are on disk. A cheap COUNT the home feeds use to tell "the library grew"
     *  from "same as last time" without loading every row (see VodViewModel.loadHomeFeeds). */
    @Query("SELECT COUNT(*) FROM movies")
    suspend fun count(): Int

    /**
     * Fallback for More-Like-This when a movie has no genre to match on: other titles from the same
     * source (and category, when it has one), best-rated first. Never returns the movie itself.
     */
    @Query(
        """
        SELECT * FROM movies
        WHERE sourceId = :sourceId AND id != :excludeId
          AND (:categoryId IS NULL OR categoryId = :categoryId)
        ORDER BY rating DESC, addedMillis DESC
        LIMIT :limit
        """
    )
    suspend fun similarByCategory(sourceId: Long, categoryId: String?, excludeId: Long, limit: Int): List<Movie>

    /**
     * Movies a person is billed in — the Plex-style "click an actor, see everything with them".
     * A substring `LIKE` over the comma-separated `cast` string: cheap and correct enough for a few
     * thousand VOD rows, where a full credits table would be over-engineering. Blank/absent cast is
     * excluded so an empty name never matches every row. Best-rated, then newest, first.
     */
    @Query(
        """
        SELECT * FROM movies
        WHERE `cast` IS NOT NULL AND `cast` != '' AND `cast` LIKE '%' || :name || '%'
        ORDER BY rating DESC, addedMillis DESC
        LIMIT :limit
        """
    )
    suspend fun moviesWithActor(name: String, limit: Int): List<Movie>

    /** Movies a person directed. See [moviesWithActor]; same shape over the `director` field. */
    @Query(
        """
        SELECT * FROM movies
        WHERE director IS NOT NULL AND director != '' AND director LIKE '%' || :name || '%'
        ORDER BY rating DESC, addedMillis DESC
        LIMIT :limit
        """
    )
    suspend fun moviesByDirector(name: String, limit: Int): List<Movie>

    /** Returns the movie row with the supplied local ID, or null if absent. */
    @Query("SELECT * FROM movies WHERE id = :id")
    suspend fun byId(id: Long): Movie?

    /** Stream URL is the cross-device key: the same film has the same URL on the same provider. */
    @Query("SELECT * FROM movies WHERE streamUrl = :url LIMIT 1")
    suspend fun byStreamUrl(url: String): Movie?

    /** Updates the movie favorite flag and records that it was explicitly edited. */
    @Query("UPDATE movies SET favourite = :favourite, favouriteTouched = 1 WHERE id = :id")
    suspend fun setFavourite(id: Long, favourite: Boolean)

    /** Returns favorite movie URLs for LAN synchronization. */
    // --- Sync: favourites by stream URL ---
    @Query("SELECT streamUrl FROM movies WHERE favourite = 1")
    suspend fun favouriteUrls(): List<String>

    /** Marks movies with the matching stream URL as favorites. */
    @Query("UPDATE movies SET favourite = 1 WHERE streamUrl = :url")
    suspend fun markFavouriteByUrl(url: String)

    /** Inserts or updates the supplied movie rows. */
    @Upsert
    suspend fun upsertAll(movies: List<Movie>)

    /** Returns source-scoped movie rows so user edits can survive a catalog refresh. */
    @Query("SELECT * FROM movies WHERE sourceId = :sourceId")
    suspend fun userStateForSource(sourceId: Long): List<Movie>

    /** Replaces a source catalog transactionally while preserving existing favorite edits. */
    @Transaction
    suspend fun replaceCatalogue(sourceId: Long, incoming: List<Movie>) {
        val previous = userStateForSource(sourceId).associateBy { it.streamId }
        incoming.chunked(500).forEach { chunk ->
            upsertAll(chunk.map { item ->
                previous[item.streamId]?.let { old -> item.copy(id = old.id,
                    favourite = old.favourite, favouriteTouched = old.favouriteTouched) } ?: item
            })
        }
    }

    /** Deletes movie rows belonging to the source. */
    @Query("DELETE FROM movies WHERE sourceId = :sourceId")
    suspend fun deleteForSource(sourceId: Long)
}

/** Room queries and mutations for series records. */
@Dao
interface SeriesDao {
    /** Observes series in the optional category, newest additions first. */
    @Query(
        """
        SELECT * FROM series
        WHERE (:categoryId IS NULL OR categoryId = :categoryId)
        ORDER BY addedMillis DESC, name
        """
    )
    fun observe(categoryId: String?): Flow<List<Series>>

    /** Observes a bounded name search over series rows. */
    @Query("SELECT * FROM series WHERE name LIKE '%' || :query || '%' ORDER BY name LIMIT :limit")
    fun search(query: String, limit: Int = 200): Flow<List<Series>>

    /** Newest-first, for the "Recently Added" home row. Reactive so it fills in as VOD sync lands. */
    @Query("SELECT * FROM series ORDER BY addedMillis DESC LIMIT :limit")
    fun observeRecentlyAdded(limit: Int): Flow<List<Series>>

    /** Every series, newest first — working set for the Kotlin-side by-genre / more-like-this feeds. */
    @Query("SELECT * FROM series ORDER BY addedMillis DESC")
    suspend fun all(): List<Series>

    /** How many series are on disk — the cheap "did the library grow" check for the home feeds. */
    @Query("SELECT COUNT(*) FROM series")
    suspend fun count(): Int

    /** Fallback for More-Like-This when a series has no genre: same source/category, best-rated first. */
    @Query(
        """
        SELECT * FROM series
        WHERE sourceId = :sourceId AND id != :excludeId
          AND (:categoryId IS NULL OR categoryId = :categoryId)
        ORDER BY rating DESC, addedMillis DESC
        LIMIT :limit
        """
    )
    suspend fun similarByCategory(sourceId: Long, categoryId: String?, excludeId: Long, limit: Int): List<Series>

    /** Series a person is billed in — the show half of "click an actor, see everything". See
     *  [MovieDao.moviesWithActor]. */
    @Query(
        """
        SELECT * FROM series
        WHERE `cast` IS NOT NULL AND `cast` != '' AND `cast` LIKE '%' || :name || '%'
        ORDER BY rating DESC, addedMillis DESC
        LIMIT :limit
        """
    )
    suspend fun seriesWithActor(name: String, limit: Int): List<Series>

    /** Returns the series row with the supplied local ID, or null if absent. */
    @Query("SELECT * FROM series WHERE id = :id")
    suspend fun byId(id: Long): Series?

    /** Updates the series favorite flag and records that it was explicitly edited. */
    @Query("UPDATE series SET favourite = :favourite, favouriteTouched = 1 WHERE id = :id")
    suspend fun setFavourite(id: Long, favourite: Boolean)

    /** Returns provider series IDs for locally favored series. */
    // --- Sync: favourites by provider series id (series carry no stream URL) ---
    @Query("SELECT seriesId FROM series WHERE favourite = 1")
    suspend fun favouriteSeriesIds(): List<String>

    /** Marks series with the matching provider series ID as favorites. */
    @Query("UPDATE series SET favourite = 1 WHERE seriesId = :seriesId")
    suspend fun markFavouriteBySeriesId(seriesId: String)

    /** Inserts or updates the supplied series rows. */
    @Upsert
    suspend fun upsertAll(items: List<Series>)

    /** Returns source-scoped series rows so user edits can survive a catalog refresh. */
    @Query("SELECT * FROM series WHERE sourceId = :sourceId")
    suspend fun userStateForSource(sourceId: Long): List<Series>

    /** Replaces a source catalog transactionally while preserving existing favorite edits. */
    @Transaction
    suspend fun replaceCatalogue(sourceId: Long, incoming: List<Series>) {
        val previous = userStateForSource(sourceId).associateBy { it.seriesId }
        incoming.chunked(500).forEach { chunk ->
            upsertAll(chunk.map { item ->
                previous[item.seriesId]?.let { old -> item.copy(id = old.id,
                    favourite = old.favourite, favouriteTouched = old.favouriteTouched) } ?: item
            })
        }
    }

    /** Deletes series rows belonging to the source. */
    @Query("DELETE FROM series WHERE sourceId = :sourceId")
    suspend fun deleteForSource(sourceId: Long)
}

/** Room queries and mutations for episode records. */
@Dao
interface EpisodeDao {
    /** Observes a provider's series episodes ordered by season and episode number. */
    @Query("SELECT * FROM episodes WHERE sourceId = :sourceId AND seriesId = :seriesId ORDER BY season, episodeNumber")
    fun observeForSeries(sourceId: Long, seriesId: String): Flow<List<Episode>>

    /** Returns the episode row with the supplied local ID, or null if absent. */
    @Query("SELECT * FROM episodes WHERE id = :id")
    suspend fun byId(id: Long): Episode?

    /** Returns the first episode with the matching playback URL, or null if absent. */
    @Query("SELECT * FROM episodes WHERE streamUrl = :url LIMIT 1")
    suspend fun byStreamUrl(url: String): Episode?

    /** Inserts or updates the supplied episode rows. */
    @Upsert
    suspend fun upsertAll(episodes: List<Episode>)

    /** Deletes episode rows belonging to the source. */
    @Query("DELETE FROM episodes WHERE sourceId = :sourceId")
    suspend fun deleteForSource(sourceId: Long)
}

/** Room queries and mutations for playback position records. */
@Dao
interface PlaybackPositionDao {
    /** Returns the resume position for a profile and media key, or null if absent. */
    @Query("SELECT * FROM playback_positions WHERE profileId = :profileId AND mediaKey = :key")
    suspend fun get(profileId: Long, key: String): PlaybackPosition?

    /** Observes a bounded list of a profile's most recently updated resume positions. */
    @Query(
        "SELECT * FROM playback_positions WHERE profileId = :profileId " +
            "ORDER BY updatedAtMillis DESC LIMIT :limit",
    )
    fun observeRecent(profileId: Long, limit: Int = 30): Flow<List<PlaybackPosition>>

    /** Returns all stored playback position rows. */
    @Query("SELECT * FROM playback_positions")
    suspend fun all(): List<PlaybackPosition>

    /** One profile's whole watch history — the input to genre-affinity recommendations. */
    @Query("SELECT * FROM playback_positions WHERE profileId = :profileId")
    suspend fun forProfile(profileId: Long): List<PlaybackPosition>

    /** Inserts or updates the supplied playback position row. */
    @Upsert
    suspend fun upsert(position: PlaybackPosition)

    /** Deletes the resume position for the given profile and media key. */
    @Query("DELETE FROM playback_positions WHERE profileId = :profileId AND mediaKey = :key")
    suspend fun delete(profileId: Long, key: String)
}

/** Room queries and mutations for profile records. */
@Dao
interface ProfileDao {
    /** Observes the stored profile rows as they change. */
    @Query("SELECT * FROM profiles ORDER BY id")
    fun observeAll(): Flow<List<Profile>>

    /** Returns all stored profile rows. */
    @Query("SELECT * FROM profiles ORDER BY id")
    suspend fun all(): List<Profile>

    /** Returns the first profile with the exact name, or null if absent. */
    @Query("SELECT * FROM profiles WHERE name = :name LIMIT 1")
    suspend fun byName(name: String): Profile?

    /** Inserts a profile row using the declared Room conflict policy and returns its row ID. */
    @Insert
    suspend fun insert(profile: Profile): Long

    /** Updates a profile's display name. */
    @Query("UPDATE profiles SET name = :name WHERE id = :id")
    suspend fun rename(id: Long, name: String)

    /** Deletes the profile row with the supplied local ID. */
    @Query("DELETE FROM profiles WHERE id = :id")
    suspend fun delete(id: Long)

    /** Removing a profile takes its watch history with it. */
    @Query("DELETE FROM playback_positions WHERE profileId = :id")
    suspend fun deletePositions(id: Long)
}

/** Room queries and mutations for recording records. */
@Dao
interface RecordingDao {
    /** The library, newest first — scheduled and in-progress float to the top by recency. */
    @Query("SELECT * FROM recordings ORDER BY COALESCE(NULLIF(startedAtMillis, 0), scheduledStartMillis) DESC, id DESC")
    fun observeAll(): Flow<List<Recording>>

    /** Just the ones capturing right now — the player watches this to light its Record button. */
    @Query("SELECT * FROM recordings WHERE status IN ('PREPARING', 'RECORDING', 'FINALIZING')")
    fun observeActive(): Flow<List<Recording>>

    /** Returns recordings currently preparing, capturing or finalizing. */
    @Query("SELECT * FROM recordings WHERE status IN ('PREPARING', 'RECORDING', 'FINALIZING')")
    suspend fun active(): List<Recording>

    /** Returns pending scheduled recordings ordered by their padded start time. */
    @Query("SELECT * FROM recordings WHERE status = 'SCHEDULED' ORDER BY scheduledStartMillis")
    suspend fun scheduled(): List<Recording>

    /** Bounded read-only preset planning; one overflow row prevents silent truncation. */
    @Query("""SELECT * FROM recordings WHERE
        status IN ('PREPARING', 'RECORDING', 'FINALIZING') OR
        (status = 'SCHEDULED' AND scheduledStartMillis < :untilMillis
        AND (scheduledEndMillis = 0 OR scheduledEndMillis > :fromMillis))
        ORDER BY scheduledStartMillis, id LIMIT :limit""")
    suspend fun planningWindow(fromMillis: Long, untilMillis: Long, limit: Int): List<Recording>

    /** Returns the recording row with the supplied local ID, or null if absent. */
    @Query("SELECT * FROM recordings WHERE id = :id")
    suspend fun byId(id: Long): Recording?

    /** Snapshot of every recording — the NAS sync reads this to gather the smb:// ones to share. */
    @Query("SELECT * FROM recordings")
    suspend fun all(): List<Recording>

    /** De-dup for NAS sync: a recording's smb:// locator is its stable cross-device identity. */
    @Query("SELECT * FROM recordings WHERE filePath = :filePath LIMIT 1")
    suspend fun byFilePath(filePath: String): Recording?

    /** For series-link de-dup: has this rule already booked this programme window on this channel? */
    @Query(
        """
        SELECT COUNT(*) FROM recordings
        WHERE seriesRuleId = :ruleId AND channelId = :channelId
          AND (scheduledStartMillis = :startMillis OR (programmeStartMillis > 0 AND programmeStartMillis = :startMillis))
        """
    )
    suspend fun countForRuleAt(ruleId: Long, channelId: Long, startMillis: Long): Int

    /** Any live/scheduled booking for this exact airing (same channel + start) — for manual de-dup. */
    @Query(
        """
        SELECT * FROM recordings
        WHERE channelId = :channelId
          AND (scheduledStartMillis = :startMillis OR (programmeStartMillis > 0 AND programmeStartMillis = :startMillis))
          AND status IN ('SCHEDULED', 'PREPARING', 'RECORDING', 'FINALIZING') LIMIT 1
        """
    )
    suspend fun bookingAt(channelId: Long, startMillis: Long): Recording?

    /** Inserts a recording row using the declared Room conflict policy and returns its row ID. */
    @Insert
    suspend fun insert(recording: Recording): Long

    /** Updates the stored recording row using its primary key. */
    @Update
    suspend fun update(recording: Recording)

    /** Duplicate alarms and simultaneous starts can claim the same booking only once. */
    @Query("UPDATE recordings SET status = 'PREPARING', error = NULL WHERE id = :id AND status = 'SCHEDULED' AND :startedAt >= 0")
    suspend fun claimScheduled(id: Long, startedAt: Long): Int

    /** Policy edits cannot rewrite an already-admitted capture or a stale booking. */
    @Query("""UPDATE recordings SET lateStartPolicy = :policy, minimumRemainingMinutes = :minutes,
        lateStartRevision = lateStartRevision + 1 WHERE id = :id AND status = 'SCHEDULED'
        AND lateStartRevision = :revision""")
    suspend fun setLateStart(id: Long, revision: Long, policy: String, minutes: Int): Int

    @Query("""UPDATE recordings SET status = 'SCHEDULED' WHERE id = :id AND status = 'PREPARING'
        AND sizeBytes = 0 AND startedAtMillis = 0""")
    suspend fun deferPreparing(id: Long): Int

    /** Compare the complete booking snapshot and persist the decision/history together. */
    @Transaction
    suspend fun commitLateStart(expected: Recording, at: Long, policy: String, minutes: Int,
        delay: Long, missed: Boolean, skip: Boolean, reason: String): Boolean {
        val current = byId(expected.id) ?: return false
        if (current != expected || current.status !in setOf(RecordingStatus.SCHEDULED, RecordingStatus.PREPARING)) return false
        if (!skip && current.status != RecordingStatus.PREPARING) return false
        update(current.copy(status = if (skip) RecordingStatus.MISSED else RecordingStatus.RECORDING,
            startedAtMillis = if (skip) 0 else at, endedAtMillis = if (skip) at else 0,
            capturePolicy = policy, captureMinimumRemainingMinutes = minutes,
            captureDelayMillis = delay, contentMissed = missed, error = if (skip) reason else null))
        insertDurationEvent(app.opentv.data.model.RecordingEvent(recordingId = current.id, atMillis = at,
            kind = if (skip) "MISSED" else "STARTED", message = reason))
        return true
    }

    /** Process death before useful media can retry, without overwriting an uncertain external sink. */
    @Transaction
    suspend fun recoverPreparing(expected: Recording, path: String, archive: Recording?, at: Long): Boolean {
        if (byId(expected.id) != expected || expected.status != RecordingStatus.PREPARING || expected.sizeBytes > 0) return false
        archive?.let { insert(it.copy(id = 0, seriesRuleId = null)) }
        update(expected.copy(status = RecordingStatus.SCHEDULED, filePath = path, startedAtMillis = 0,
            endedAtMillis = 0, sizeBytes = 0, capturePolicy = "", captureDelayMillis = 0,
            captureMinimumRemainingMinutes = 0, contentMissed = false, lateStartRevision = expected.lateStartRevision + 1))
        insertDurationEvent(app.opentv.data.model.RecordingEvent(recordingId = expected.id, atMillis = at,
            kind = "STARTUP_RECOVERED", message = "Preparation interrupted before confirmed useful capture; late-start policy will be reevaluated"))
        return true
    }

    /** Claims a preparing or capturing recording for finalization; returns the affected row count. */
    @Query("UPDATE recordings SET status = 'FINALIZING' WHERE id = :id AND status IN ('PREPARING', 'RECORDING')")
    suspend fun beginFinalizing(id: Long): Int

    /** Deadline callbacks must win the same database race as user edits before closing I/O. */
    @Query("""UPDATE recordings SET status = 'FINALIZING' WHERE id = :id
        AND status IN ('PREPARING', 'RECORDING') AND durationRevision = :revision
        AND scheduledEndMillis > 0 AND scheduledEndMillis <= :now""")
    suspend fun claimDurationDeadline(id: Long, revision: Long, now: Long): Int

    /** Compare-and-set prevents repeated OK and stale editor confirmations applying twice. */
    @Query("""UPDATE recordings SET scheduledEndMillis = :end, originalEndMillis = :original,
        manualExtensionMillis = CASE WHEN :original > 0 THEN :extension ELSE
            manualExtensionMillis + :end - CASE WHEN scheduledEndMillis > 0 THEN scheduledEndMillis ELSE :now END END,
        durationRevision = durationRevision + 1,
        durationModifiedAtMillis = :now, status = :status
        WHERE id = :id AND durationRevision = :revision AND status = 'RECORDING'""")
    suspend fun setDuration(id: Long, revision: Long, end: Long, original: Long, extension: Long,
        now: Long, status: RecordingStatus): Int

    @Insert
    suspend fun insertDurationEvent(event: app.opentv.data.model.RecordingEvent): Long

    /** Revalidate demand and persist both the change and its history in one Room transaction. */
    @Transaction
    suspend fun commitDuration(id: Long, revision: Long, end: Long, original: Long, now: Long,
        stop: Boolean, demand: List<Recording>, kind: String): Boolean {
        val latest = (active() + scheduled()).map { it.copy(sizeBytes = 0) }.sortedBy { it.id }
        if (latest != demand) return false
        if (setDuration(id, revision, end, original, end - original, now,
                if (stop) RecordingStatus.FINALIZING else RecordingStatus.RECORDING) != 1) return false
        if (kind == "CONFLICTING_EXTENSION_CONFIRMED") {
            insertDurationEvent(app.opentv.data.model.RecordingEvent(recordingId = id, atMillis = now,
                kind = "CONFLICT_DETECTED", message = "Extension affects scheduled recording demand"))
            insertDurationEvent(app.opentv.data.model.RecordingEvent(recordingId = id, atMillis = now,
                kind = "RECORDING_EXTENDED", message = "User extended recording with priority warning"))
        }
        insertDurationEvent(app.opentv.data.model.RecordingEvent(recordingId = id, atMillis = now,
            kind = kind, message = "User changed recording end to $end (UTC epoch milliseconds)"))
        return true
    }

    /** Provider changes are allowed only for an explicitly reviewed, not-yet-started booking. */
    @Query("""UPDATE recordings SET sourceId = :sourceId, streamUrl = :url, userAgent = :agent
        WHERE id = :id AND status = 'SCHEDULED'""")
    suspend fun setScheduledProvider(id: Long, sourceId: Long, url: String, agent: String): Int

    @Transaction
    suspend fun commitScheduledProvider(id: Long, sourceId: Long, url: String, agent: String,
        demand: List<Recording>, now: Long): Boolean {
        if ((active() + scheduled()).map { it.copy(sizeBytes = 0) }.sortedBy { it.id } != demand) return false
        if (setScheduledProvider(id, sourceId, url, agent) != 1) return false
        insertDurationEvent(app.opentv.data.model.RecordingEvent(recordingId = id, atMillis = now,
            kind = "PROVIDER_ALTERNATIVE_CONFIRMED", message = "User selected an authorized independent provider for this scheduled recording"))
        return true
    }

    /** Updates recording protection and padding-lock flags independently. */
    @Query("UPDATE recordings SET protectedRecording = :protectedRecording, lockPadding = :lockPadding WHERE id = :id")
    suspend fun setProtection(id: Long, protectedRecording: Boolean, lockPadding: Boolean)

    /** Changes the capture window only for scheduled recordings with unlocked padding. */
    @Query("UPDATE recordings SET scheduledStartMillis = :startMillis, scheduledEndMillis = :endMillis WHERE id = :id AND status = 'SCHEDULED' AND lockPadding = 0")
    suspend fun setScheduledWindow(id: Long, startMillis: Long, endMillis: Long): Int

    /** Updates padding and derived capture times only while the recording is scheduled. */
    @Query("UPDATE recordings SET padStartMinutes = :startMinutes, padEndMinutes = :endMinutes, scheduledStartMillis = :startMillis, scheduledEndMillis = :endMillis WHERE id = :id AND status = 'SCHEDULED'")
    suspend fun setPadding(id: Long, startMinutes: Int, endMinutes: Int, startMillis: Long, endMillis: Long): Int

    /** Cancels a pending recording and returns the affected row count. */
    @Query("UPDATE recordings SET status = 'CANCELED', error = 'Canceled by user' WHERE id = :id AND status = 'SCHEDULED'")
    suspend fun cancelScheduled(id: Long): Int

    /** Resets an eligible failed, partial or missed recording for another capture attempt. */
    @Query("UPDATE recordings SET status = 'SCHEDULED', filePath = :filePath, sizeBytes = 0, startedAtMillis = 0, endedAtMillis = 0, error = NULL, capturePolicy = '', captureMinimumRemainingMinutes = 0, captureDelayMillis = 0, contentMissed = 0, lateStartRevision = lateStartRevision + 1 WHERE id = :id AND status IN ('FAILED', 'PARTIAL', 'MISSED', 'CANCELED')")
    suspend fun prepareRetry(id: Long, filePath: String): Int

    /** Archive partial bytes and change retry ownership as one durable operation. */
    @Transaction
    suspend fun retryPreserving(id: Long, filePath: String, archive: Recording?): Boolean {
        val current = byId(id) ?: return false
        if (current.status !in setOf(RecordingStatus.FAILED, RecordingStatus.PARTIAL, RecordingStatus.MISSED, RecordingStatus.CANCELED)) return false
        archive?.let { insert(it.copy(id = 0, status = RecordingStatus.PARTIAL)) }
        return prepareRetry(id, filePath) == 1
    }

    /** Updates the recording status and optional error text. */
    @Query("UPDATE recordings SET status = :status, error = :error WHERE id = :id")
    suspend fun setStatus(id: Long, status: RecordingStatus, error: String? = null)

    /** Updates status only while the recording remains scheduled; returns the affected count. */
    @Query("UPDATE recordings SET status = :status, error = :error WHERE id = :id AND status = 'SCHEDULED'")
    suspend fun setScheduledStatus(id: Long, status: RecordingStatus, error: String? = null): Int

    @Query("UPDATE recordings SET contentMissed = 1 WHERE id = :id AND status IN ('PREPARING', 'RECORDING', 'FINALIZING')")
    suspend fun markContentMissed(id: Long)

    /** Records the number of captured bytes. */
    @Query("UPDATE recordings SET sizeBytes = :bytes WHERE id = :id")
    suspend fun setSize(id: Long, bytes: Long)

    /** Repoint a row at its real locator — used when a USB (SAF) capture learns its content:// URI. */
    @Query("UPDATE recordings SET filePath = :filePath WHERE id = :id")
    suspend fun setFilePath(id: Long, filePath: String)

    /** Records capture start and status only after the preparing stage was claimed. */
    @Query(
        "UPDATE recordings SET status = :status, startedAtMillis = :startedAt WHERE id = :id AND status = 'PREPARING'"
    )
    suspend fun markStarted(id: Long, status: RecordingStatus, startedAt: Long)

    /** Persists terminal status, completion time, byte count and any error. */
    @Query(
        "UPDATE recordings SET status = :status, endedAtMillis = :endedAt, sizeBytes = :bytes, error = :error WHERE id = :id"
    )
    suspend fun markFinished(id: Long, status: RecordingStatus, endedAt: Long, bytes: Long, error: String?)

    /** Deletes the recording row with the supplied local ID. */
    @Query("DELETE FROM recordings WHERE id = :id")
    suspend fun delete(id: Long)

    /**
     * Cold-start fallback preserves the stored locator and bytes. Repository recovery also checks
     * actual file size before finalizing an interrupted row.
     */
    @Query("UPDATE recordings SET status = CASE WHEN sizeBytes > 0 THEN 'PARTIAL' ELSE 'FAILED' END, error = 'Interrupted' WHERE status IN ('PREPARING', 'RECORDING', 'FINALIZING')")
    suspend fun failInterrupted()
}

/** Room queries and mutations for recording event records. */
@Dao
interface RecordingEventDao {
    /** Observes a recording's activity history in timestamp and insertion order. */
    @Query("SELECT * FROM recording_events WHERE recordingId = :recordingId ORDER BY atMillis, id")
    fun observeForRecording(recordingId: Long): Flow<List<RecordingEvent>>

    /** Returns the recording's activity history in timestamp and insertion order. */
    @Query("SELECT * FROM recording_events WHERE recordingId = :recordingId ORDER BY atMillis, id")
    suspend fun forRecording(recordingId: Long): List<RecordingEvent>

    /** Inserts a recording event row using the declared Room conflict policy and returns its row ID. */
    @Insert
    suspend fun insert(event: RecordingEvent): Long

    /** Removes activity history belonging to the recording. */
    @Query("DELETE FROM recording_events WHERE recordingId = :recordingId")
    suspend fun deleteForRecording(recordingId: Long)
}

/** Room queries and mutations for series rule records. */
@Dao
interface SeriesRuleDao {
    @Query("SELECT * FROM series_rules WHERE id = :id")
    suspend fun byId(id: Long): SeriesRule?

    @Query("UPDATE series_rules SET lateStartPolicy = :policy, minimumRemainingMinutes = :minutes WHERE id = :id")
    suspend fun setLateStart(id: Long, policy: String, minutes: Int): Int

    /** Observes the stored series rule rows as they change. */
    @Query("SELECT * FROM series_rules ORDER BY createdAtMillis DESC")
    fun observeAll(): Flow<List<SeriesRule>>

    /** Returns series rule rows that are currently enabled. */
    @Query("SELECT * FROM series_rules WHERE enabled = 1")
    suspend fun enabled(): List<SeriesRule>

    /** Returns a series rule for the channel and normalized title, or null if absent. */
    @Query("SELECT * FROM series_rules WHERE channelId = :channelId AND titleKey = :titleKey LIMIT 1")
    suspend fun forChannelTitle(channelId: Long, titleKey: String): SeriesRule?

    /** Inserts a series rule row using the declared Room conflict policy and returns its row ID. */
    @Insert
    suspend fun insert(rule: SeriesRule): Long

    /** Deletes the series rule row with the supplied local ID. */
    @Query("DELETE FROM series_rules WHERE id = :id")
    suspend fun delete(id: Long)
}

/** Room queries and mutations for reminder records. */
@Dao
interface ReminderDao {
    /** Looks up the reminder associated with a stable sports event ID. */
    @Query("SELECT * FROM reminders WHERE sportsEventId=:eventId LIMIT 1")
    suspend fun forSportsEvent(eventId: String): Reminder?
    /** Updates the stored reminder row using its primary key. */
    @Update suspend fun update(reminder: Reminder)

    /** A guide action can resolve the same game on a different provider without a second alarm. */
    @Query("""SELECT DISTINCT COALESCE(d.eventId,sp.eventId) FROM sports_programmes sp
        JOIN channels c ON (c.epgOverrideId=sp.epgChannelId OR c.epgChannelId=sp.epgChannelId OR c.matchedEpgId=sp.epgChannelId)
        AND (sp.epgChannelId=c.epgOverrideId OR (sp.epgChannelId=c.epgChannelId AND NOT EXISTS (
            SELECT 1 FROM programmes preferred JOIN epg_feeds preferredf ON preferredf.id=preferred.feedId AND preferredf.enabled=1
            WHERE preferred.epgChannelId=c.epgOverrideId AND preferred.endUtcMillis>sp.startUtcMillis AND preferred.startUtcMillis<sp.endUtcMillis))
            OR (sp.epgChannelId=c.matchedEpgId AND NOT EXISTS (
            SELECT 1 FROM programmes preferred JOIN epg_feeds preferredf ON preferredf.id=preferred.feedId AND preferredf.enabled=1
            WHERE (preferred.epgChannelId=c.epgOverrideId OR preferred.epgChannelId=c.epgChannelId)
                AND preferred.endUtcMillis>sp.startUtcMillis AND preferred.startUtcMillis<sp.endUtcMillis)))
        JOIN sources s ON s.id=c.sourceId AND s.enabled=1
        JOIN epg_feeds f ON f.id=sp.feedId AND f.enabled=1
        LEFT JOIN sports_decisions d ON d.programmeKey=sp.key AND d.sourceId=c.sourceId AND d.streamId=c.streamId
        JOIN sports_events event ON event.id=COALESCE(d.eventId,sp.eventId)
            AND sp.endUtcMillis>event.startUtcMillis AND sp.startUtcMillis<event.endUtcMillis AND event.status NOT IN ('CANCELED','POSTPONED')
        WHERE c.id=:channelId AND c.hidden=0 AND sp.startUtcMillis=:startMillis
        AND (d.accepted=1 OR (d.accepted IS NULL AND sp.confidence='CONFIRMED' AND sp.startUtcMillis=event.startUtcMillis)) LIMIT 2""")
    suspend fun sportsIdentities(channelId: Long, startMillis: Long): List<String>

    /** Finds an existing reminder by program identity or shared sports event identity. */
    @Transaction
    suspend fun forProgrammeOrSports(channelId: Long, startMillis: Long): Reminder? =
        forProgramme(channelId, startMillis) ?: sportsIdentities(channelId, startMillis).singleOrNull()?.let { forSportsEvent(it) }

    /** Inserts a reminder transactionally unless a matching program or sports reminder already exists. */
    @Transaction
    suspend fun insertDeduplicated(reminder: Reminder): Long {
        forProgrammeOrSports(reminder.channelId, reminder.startUtcMillis)?.let { return it.id }
        val eventId = reminder.sportsEventId ?: sportsIdentities(reminder.channelId, reminder.startUtcMillis).singleOrNull()
        return insert(reminder.copy(sportsEventId = eventId))
    }

    /** All reminders, soonest first — the reminders list and boot re-arm both read this. */
    @Query("SELECT * FROM reminders ORDER BY startUtcMillis")
    fun observeAll(): Flow<List<Reminder>>

    /** Still-future, not-yet-fired reminders — the set to re-arm after a reboot. */
    @Query("SELECT * FROM reminders WHERE fired = 0 AND startUtcMillis > :nowMillis ORDER BY startUtcMillis")
    suspend fun upcoming(nowMillis: Long): List<Reminder>

    /** Returns the reminder row with the supplied local ID, or null if absent. */
    @Query("SELECT * FROM reminders WHERE id = :id")
    suspend fun byId(id: Long): Reminder?

    /** De-dup: is there already a reminder for this (channel, slot)? */
    @Query("SELECT * FROM reminders WHERE channelId = :channelId AND startUtcMillis = :startMillis LIMIT 1")
    suspend fun forProgramme(channelId: Long, startMillis: Long): Reminder?

    /** The guide grid asks: which upcoming slots already have a bell? Keyed by start time. */
    @Query("SELECT startUtcMillis FROM reminders WHERE channelId = :channelId AND fired = 0")
    fun observeStartsForChannel(channelId: Long): Flow<List<Long>>

    /** Inserts a reminder row using the declared Room conflict policy and returns its row ID. */
    @Insert
    suspend fun insert(reminder: Reminder): Long

    /** Marks a reminder as delivered so it is not rearmed. */
    @Query("UPDATE reminders SET fired = 1 WHERE id = :id")
    suspend fun markFired(id: Long)

    /** Deletes the reminder row with the supplied local ID. */
    @Query("DELETE FROM reminders WHERE id = :id")
    suspend fun delete(id: Long)

    /** Deletes reminders matching the channel and program start time. */
    @Query("DELETE FROM reminders WHERE channelId = :channelId AND startUtcMillis = :startMillis")
    suspend fun deleteForProgramme(channelId: Long, startMillis: Long)

    /** Housekeeping: drop reminders whose programme has already ended. */
    @Query("DELETE FROM reminders WHERE endUtcMillis < :beforeMillis")
    suspend fun deleteEndedBefore(beforeMillis: Long)
}
