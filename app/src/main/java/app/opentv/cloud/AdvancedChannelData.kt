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
    )

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
                c.customName, c.sortIndex, c.categoryId, category.name
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
                    cursor.getString(5).orEmpty(), cursor.getInt(6)))
            }
        }
        fun media(table: String, kind: String): Map<String, List<Target>> {
            val targets = mutableMapOf<String, MutableList<Target>>()
            // table/kind are fixed local constants; no remote value is interpolated into SQL.
            database.openHelper.readableDatabase.query("""
                SELECT m.id, m.name, m.year, m.tmdbId, m.favourite FROM $table m
                INNER JOIN sources s ON s.id = m.sourceId AND s.enabled = 1
            """.trimIndent()).use { cursor ->
                while (cursor.moveToNext()) {
                    val key = CloudIdentity.media(kind, cursor.getString(1),
                        if (cursor.isNull(2)) null else cursor.getInt(2), cursor.getString(3))
                    targets.getOrPut(key) { mutableListOf() }
                        .add(Target(cursor.getLong(0), cursor.getInt(4) == 1))
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
                    state(key, "channel_hidden", target.hidden)
                    add(AdvancedCloudRecord(key, "channel_name", nowMillis, deviceId, textValue = target.customName))
                    add(AdvancedCloudRecord(key, "channel_order", nowMillis, deviceId, intValue = target.sortIndex))
                    if (includeFavourites) state(key, "channel_favourite_state", target.favourite)
                }
                for ((key, targets) in index.movies) {
                    val target = targets.singleOrNull() ?: continue
                    state(key, "movie_favourite_state", target.favourite)
                }
                for ((key, targets) in index.series) {
                    val target = targets.singleOrNull() ?: continue
                    state(key, "series_favourite_state", target.favourite)
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
                    val prior = expected[record.identity()] ?: continue
                    val unchanged = when (record.kind) {
                        "channel_hidden" -> prior.boolValue == target.hidden
                        "channel_name" -> prior.textValue == target.customName
                        "channel_order" -> prior.intValue == target.sortIndex
                        else -> prior.boolValue == target.favourite
                    }
                    if (!unchanged) continue
                }
                val updated = when (record.kind) {
                    "channel_hidden" -> if (record.boolValue != target.hidden) {
                        database.channels().setHidden(target.id, record.boolValue!!); true
                    } else false
                    "channel_name" -> if (record.textValue != target.customName) {
                        database.channels().setCustomName(target.id, record.textValue!!.ifEmpty { null }); true
                    } else false
                    "channel_order" -> if (record.intValue != target.sortIndex) {
                        database.channels().setSortIndex(target.id, record.intValue!!); true
                    } else false
                    "channel_favourite_state" -> if (record.boolValue != target.favourite) {
                        database.channels().setFavourite(target.id, record.boolValue!!); true
                    } else false
                    "movie_favourite_state" -> if (record.boolValue != target.favourite) {
                        database.movies().setFavourite(target.id, record.boolValue!!); true
                    } else false
                    "series_favourite_state" -> if (record.boolValue != target.favourite) {
                        database.series().setFavourite(target.id, record.boolValue!!); true
                    } else false
                    else -> false
                }
                if (updated) changed++
            }
            changed
        }
    }
}
