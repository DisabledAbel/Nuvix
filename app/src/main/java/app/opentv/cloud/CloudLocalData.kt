/* Nuvix modifications Copyright (C) 2026 Nuvix Contributors. GPL-3.0-or-later. */
package app.opentv.cloud

import app.opentv.core.ServiceLocator
import app.opentv.data.model.PlaybackPosition

/** Builds an explicit content index without using stream URLs or credential-bearing source rows. */
internal class CloudLocalData(private val graph: ServiceLocator.Graph) {
    data class Snapshot(val records: List<CloudRecord>, val existingFavouriteIdentities: Set<String>)
    private data class Target(val kind: String, val id: Long)
    private data class Index(
        val favourites: Map<String, List<Target>>, val activeFavourites: Set<String>,
        val media: Map<String, List<Target>>, val profiles: Map<String, List<Long>>,
    )

    private suspend fun index(): Index {
        val favourites = mutableMapOf<String, MutableList<Target>>()
        val selected = mutableSetOf<String>()
        val media = mutableMapOf<String, MutableList<Target>>()
        fun favourite(kind: String, key: String, id: Long, isFavourite: Boolean) {
            favourites.getOrPut("$kind:$key") { mutableListOf() }.add(Target(kind, id))
            if (isFavourite) selected += "$kind:$key"
        }
        graph.database.openHelper.readableDatabase.query("SELECT id, displayName, epgChannelId, favourite FROM channels").use { cursor ->
            while (cursor.moveToNext()) favourite("channel_favourite", CloudIdentity.channel(cursor.getString(1), cursor.getString(2)),
                cursor.getLong(0), cursor.getInt(3) == 1)
        }
        for (movie in graph.catalogRepository.allMovies()) {
            val key = CloudIdentity.media("movie", movie.name, movie.year, movie.tmdbId)
            favourite("movie_favourite", key, movie.id, movie.favourite)
            media.getOrPut("movie_progress:$key") { mutableListOf() }.add(Target("movie", movie.id))
        }
        val seriesKeys = mutableMapOf<Pair<Long, String>, String>()
        for (series in graph.catalogRepository.allSeries()) {
            val key = CloudIdentity.media("series", series.name, series.year, series.tmdbId)
            favourite("series_favourite", key, series.id, series.favourite)
            seriesKeys[series.sourceId to series.seriesId] = key
        }
        graph.database.openHelper.readableDatabase.query("SELECT id, sourceId, seriesId, season, episodeNumber FROM episodes").use { cursor ->
            while (cursor.moveToNext()) {
                val series = seriesKeys[cursor.getLong(1) to cursor.getString(2)] ?: continue
                val key = CloudIdentity.episode(series, cursor.getInt(3), cursor.getInt(4))
                media.getOrPut("episode_progress:$key") { mutableListOf() }.add(Target("ep", cursor.getLong(0)))
            }
        }
        return Index(favourites, selected, media, graph.profiles.all().groupBy { CloudIdentity.profile(it.name) }
            .mapValues { (_, profiles) -> profiles.map { it.id } })
    }

    suspend fun gather(device: String, favouriteTimes: MutableMap<String, Long>): Snapshot {
        val index = index()
        val now = System.currentTimeMillis()
        val records = index.activeFavourites.map { identity ->
            val (kind, key) = identity.split(':', limit = 2)
            CloudRecord(key, kind, updatedAtMillis = favouriteTimes.getOrPut(identity) { now }, deviceId = device)
        }.toMutableList()
        val profiles = index.profiles.filterValues { it.size == 1 }.entries.associate { it.value.single() to it.key }
        val media = index.media.filterValues { it.size == 1 }.entries.associate { entry ->
            val target = entry.value.single()
            "${target.kind}:${target.id}" to entry.key
        }
        for (position in graph.playbackPositions.all()) {
            val profile = profiles[position.profileId] ?: continue
            val content = media[position.mediaKey] ?: continue
            val (kind, key) = content.split(':', limit = 2)
            val record = CloudRecord(key, kind, profile, position.positionMillis, position.durationMillis,
                position.updatedAtMillis, device)
            if (record.isSafe(now)) records += record
        }
        return Snapshot(records, index.favourites.keys)
    }

    /** Add favourites, and apply progress only to one unambiguous local content/profile match. */
    suspend fun apply(records: List<CloudRecord>, locallyDismissed: Set<String>, syncFavourites: Boolean = true, onFavouriteApplied: (String, Long) -> Unit): Int {
        val index = index()
        var changed = 0
        for (record in records) {
            if (!record.isSafe(System.currentTimeMillis())) continue
            if (record.kind.endsWith("_favourite")) {
                if (!syncFavourites) continue
                val identity = "${record.kind}:${record.recordKey}"
                if (identity in locallyDismissed) continue
                if (identity in index.activeFavourites) continue
                for (target in index.favourites[identity].orEmpty()) {
                    when (target.kind) {
                        "channel_favourite" -> graph.database.channels().setFavourite(target.id, true)
                        "movie_favourite" -> graph.database.movies().setFavourite(target.id, true)
                        "series_favourite" -> graph.database.series().setFavourite(target.id, true)
                    }
                    onFavouriteApplied(identity, record.updatedAtMillis)
                    changed++
                }
            } else {
                val target = index.media["${record.kind}:${record.recordKey}"]?.singleOrNull() ?: continue
                val profileId = index.profiles[record.profileKey]?.singleOrNull() ?: continue
                val mediaKey = "${target.kind}:${target.id}"
                val existing = graph.playbackPositions.get(profileId, mediaKey)
                // Equal timestamps preserve local progress, including intentional rewinds.
                if (existing != null && existing.updatedAtMillis >= record.updatedAtMillis) continue
                graph.playbackPositions.upsert(PlaybackPosition(profileId, mediaKey,
                    record.positionMillis, record.durationMillis, record.updatedAtMillis))
                changed++
            }
        }
        return changed
    }
}
