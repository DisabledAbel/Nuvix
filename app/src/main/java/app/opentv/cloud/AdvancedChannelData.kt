/* Nuvix modifications Copyright (C) 2026 Nuvix Contributors. GPL-3.0-or-later. */
package app.opentv.cloud

import androidx.room.withTransaction
import app.opentv.core.ServiceLocator
import app.opentv.data.db.OpenTvDatabase
import app.opentv.data.parser.ChannelNameNormalizer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Maps cloud fingerprints to existing, authorized catalogue rows. Projections deliberately omit
 * stream URLs, provider identifiers, credentials and all other playback configuration.
 */
internal class AdvancedChannelData(
    private val database: OpenTvDatabase,
    private val blockedCategoryKeys: () -> Set<String> = { emptySet() },
) {
    constructor(graph: ServiceLocator.Graph) : this(database = graph.database,
        blockedCategoryKeys = { graph.settings.hiddenCategories.value })

    private data class Target(
        val id: Long,
        val favourite: Boolean,
        val hidden: Boolean = false,
        val customName: String = "",
        val sortIndex: Int = 0,
        val touched: Int = 0,
    ) {
        // sortIndex is provider assigned; a nonzero value is never evidence of a user edit.
        fun exports(kind: String): Boolean = when (kind) {
            "channel_hidden" -> touched and 2 != 0
            "channel_name" -> touched and 4 != 0 || customName.isNotEmpty()
            "channel_order" -> touched and 8 != 0
            else -> touched and 1 != 0 || favourite
        }
    }

    private data class Index(
        val channels: Map<String, List<Target>>,
        val movies: Map<String, List<Target>>,
        val series: Map<String, List<Target>>,
    )

    private fun index(includeMedia: Boolean = true): Index {
        val channels = mutableMapOf<String, MutableList<Target>>()
        val blocked = blockedCategoryKeys()
        database.openHelper.readableDatabase.query("""
            SELECT c.id, c.displayName, c.epgChannelId, c.favourite, c.hidden,
                c.customName, c.sortIndex, c.categoryId, category.name, c.syncTouched
            FROM channels c INNER JOIN sources s ON s.id = c.sourceId AND s.enabled = 1
            LEFT JOIN categories category ON category.sourceId = c.sourceId
                AND category.id = c.categoryId AND category.kind = 'LIVE'
        """.trimIndent()).use { cursor ->
            while (cursor.moveToNext()) {
                val categoryId = cursor.getString(7)
                val categoryName = cursor.getString(8)
                val categoryKey = categoryName?.let { ChannelNameNormalizer.normalize(it).groupKey }
                    ?.ifEmpty { categoryId }.orEmpty()
                // A cloud update cannot override a local parental restriction. A temporary PIN
                // unlock is intentionally irrelevant to background synchronization.
                if (categoryKey in blocked || categoryId in blocked) continue
                val key = CloudIdentity.channel(cursor.getString(1), cursor.getString(2))
                channels.getOrPut(key) { mutableListOf() }.add(Target(cursor.getLong(0),
                    cursor.getInt(3) == 1, cursor.getInt(4) == 1,
                    cursor.getString(5).orEmpty(), cursor.getInt(6), cursor.getInt(9)))
            }
        }
        fun media(table: String, kind: String): Map<String, List<Target>> {
            val targets = mutableMapOf<String, MutableList<Target>>()
            // table/kind are fixed local constants; no remote value is interpolated into SQL.
            database.openHelper.readableDatabase.query("""
                SELECT m.id, m.name, m.year, m.tmdbId, m.favourite, m.favouriteTouched FROM $table m
                INNER JOIN sources s ON s.id = m.sourceId AND s.enabled = 1
            """.trimIndent()).use { cursor ->
                while (cursor.moveToNext()) {
                    val key = CloudIdentity.media(kind, cursor.getString(1),
                        if (cursor.isNull(2)) null else cursor.getInt(2), cursor.getString(3))
                    targets.getOrPut(key) { mutableListOf() }
                        .add(Target(cursor.getLong(0), cursor.getInt(4) == 1, touched = cursor.getInt(5)))
                }
            }
            return targets
        }
        return Index(channels, if (includeMedia) media("movies", "movie") else emptyMap(),
            if (includeMedia) media("series", "series") else emptyMap())
    }

    /** Missing or ambiguous catalogue entries never produce a deletion or a guessed target. */
    suspend fun snapshot(deviceId: String, nowMillis: Long, includeFavourites: Boolean = true): List<AdvancedCloudRecord> = withContext(Dispatchers.IO) {
        database.withTransaction {
            val index = index(includeMedia = includeFavourites)
            buildList {
                fun state(key: String, kind: String, value: Boolean) {
                    add(AdvancedCloudRecord(key, kind, nowMillis, deviceId, boolValue = value))
                }
                for ((key, targets) in index.channels) {
                    val target = targets.singleOrNull() ?: continue
                    if (target.exports("channel_hidden")) state(key, "channel_hidden", target.hidden)
                    if (target.exports("channel_name")) add(AdvancedCloudRecord(key, "channel_name", nowMillis, deviceId, textValue = target.customName))
                    if (target.exports("channel_order")) add(AdvancedCloudRecord(key, "channel_order", nowMillis, deviceId, intValue = target.sortIndex))
                    if (includeFavourites && target.exports("channel_favourite_state")) state(key, "channel_favourite_state", target.favourite)
                }
                for ((key, targets) in index.movies) {
                    val target = targets.singleOrNull() ?: continue
                    if (target.exports("movie_favourite_state")) state(key, "movie_favourite_state", target.favourite)
                }
                for ((key, targets) in index.series) {
                    val target = targets.singleOrNull() ?: continue
                    if (target.exports("series_favourite_state")) state(key, "series_favourite_state", target.favourite)
                }
            }.filter { it.isSafe(nowMillis) }
        }
    }

    /** Applies only individual organization fields; never inserts a playable catalogue row. */
    suspend fun apply(records: List<AdvancedCloudRecord>, expectedLocal: List<AdvancedCloudRecord>? = null): Int = withContext(Dispatchers.IO) {
        val relevant = records.filter { record -> record.kind in setOf(
            "channel_hidden", "channel_name", "channel_order", "channel_favourite_state",
            "movie_favourite_state", "series_favourite_state",
        ) }
        if (relevant.isEmpty()) return@withContext 0
        database.withTransaction {
            val index = index(includeMedia = relevant.any { it.kind == "movie_favourite_state" || it.kind == "series_favourite_state" })
            val expected = expectedLocal?.associateBy { it.identity() }
            val now = System.currentTimeMillis()
            var changed = 0
            for (record in relevant) {
                if (!record.isSafe(now) || record.deleted) continue
                val targets = when (record.kind) {
                    "channel_hidden", "channel_name", "channel_order", "channel_favourite_state" -> index.channels
                    "movie_favourite_state" -> index.movies
                    "series_favourite_state" -> index.series
                    else -> continue
                }
                val target = targets[record.recordKey]?.singleOrNull() ?: continue
                // Compare under the same Room transaction as the write. A local edit made
                // after the engine snapshot is retained and captured on the next journal pass.
                if (expected != null) {
                    val prior = expected[record.identity()]
                    // Sparse snapshots omit untouched fields. They are still safe remote targets
                    // unless a local setter has marked that field since the expected snapshot.
                    val unchanged = if (prior == null) !target.exports(record.kind) else when (record.kind) {
                        "channel_hidden" -> prior.boolValue == target.hidden
                        "channel_name" -> prior.textValue == target.customName
                        "channel_order" -> prior.intValue == target.sortIndex
                        else -> prior.boolValue == target.favourite
                    }
                    if (!unchanged) continue
                }
                val sameValue = when (record.kind) {
                    "channel_hidden" -> record.boolValue == target.hidden
                    "channel_name" -> record.textValue == target.customName
                    "channel_order" -> record.intValue == target.sortIndex
                    else -> record.boolValue == target.favourite
                }
                // Record receipt once even for an unchanged default, then avoid needless writes
                // (and Room invalidations) on every subsequent reconciliation.
                if (sameValue && target.exports(record.kind)) continue
                val updated = when (record.kind) {
                    "channel_hidden" -> (record.boolValue != target.hidden).also {
                        database.channels().setHidden(target.id, record.boolValue!!)
                    }
                    "channel_name" -> (record.textValue != target.customName).also {
                        database.channels().setCustomName(target.id, record.textValue!!.ifEmpty { null })
                    }
                    "channel_order" -> (record.intValue != target.sortIndex).also {
                        database.channels().setSortIndex(target.id, record.intValue!!)
                    }
                    "channel_favourite_state" -> (record.boolValue != target.favourite).also {
                        database.channels().setFavourite(target.id, record.boolValue!!)
                    }
                    "movie_favourite_state" -> (record.boolValue != target.favourite).also {
                        database.movies().setFavourite(target.id, record.boolValue!!)
                    }
                    "series_favourite_state" -> (record.boolValue != target.favourite).also {
                        database.series().setFavourite(target.id, record.boolValue!!)
                    }
                    else -> false
                }
                if (updated) changed++
            }
            changed
        }
    }
}
