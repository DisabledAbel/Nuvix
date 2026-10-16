/* Copyright (C) 2026 Nuvix Contributors. GPL-3.0-or-later. */
package app.opentv.sports

import androidx.room.*
import app.opentv.data.model.Channel
import app.opentv.data.model.Programme
import kotlinx.coroutines.flow.Flow

data class SportsFeedRevision(val feedId: Long, val lastSyncMillis: Long, val rowCount: Long, val lastId: Long) {
    val revision: String get() = "$lastSyncMillis:$rowCount:$lastId"
}
data class SportsStreamRow(
    @Embedded val programme: SportsProgramme,
    val channelId: Long,
    val providerName: String,
    val accepted: Boolean?,
    val decisionEventId: String?,
    val matchOrder: String,
    @Relation(parentColumn = "channelId", entityColumn = "id") val channel: Channel,
)
data class SportsEventCard(@Embedded val event: SportsEvent, val streamCount: Int)

@Dao
interface SportsDao {
    /** Invalidation is coalesced with distinctUntilChanged; favorite edits do not reindex EPG. */
    @Query("""SELECT f.id AS feedId, f.lastSyncMillis, COUNT(p.id) AS rowCount, COALESCE(MAX(p.id),0) AS lastId
        FROM epg_feeds f LEFT JOIN programmes p ON p.feedId=f.id WHERE f.enabled=1 GROUP BY f.id""")
    fun observeRevisions(): Flow<List<SportsFeedRevision>>

    @Query("""SELECT id,feedId,epgChannelId,startUtcMillis,endUtcMillis,SUBSTR(title,1,301) AS title,
        SUBSTR(description,1,2000) AS description,SUBSTR(category,1,500) AS category,season,episode,NULL AS iconUrl
        FROM programmes WHERE feedId=:feedId AND id>:afterId
        AND endUtcMillis>:from AND startUtcMillis<:until ORDER BY id LIMIT 500""")
    suspend fun programmeBatch(feedId: Long, afterId: Long, from: Long, until: Long): List<Programme>

    @Query("SELECT * FROM sports_feed_state WHERE feedId=:id")
    suspend fun feedState(id: Long): SportsFeedState?
    @Upsert suspend fun setFeedState(state: SportsFeedState)
    @Query("DELETE FROM sports_feed_state") suspend fun clearFeedStates()
    @Query("DELETE FROM sports_feed_state WHERE feedId NOT IN (:enabled)") suspend fun removeDisabledFeedStates(enabled: List<Long>)
    @Query("SELECT EXISTS(SELECT 1 FROM epg_feeds WHERE id=:id AND enabled=1)") suspend fun enabledFeed(id: Long): Boolean

    @Query("SELECT * FROM sports_events WHERE id=:id") suspend fun event(id: String): SportsEvent?
    @Query("SELECT * FROM sports_events WHERE id=:id") fun observeEvent(id: String): Flow<SportsEvent?>
    @Query("""SELECT * FROM sports_events WHERE leagueId=:league AND
        ((homeTeamId=:first AND awayTeamId=:second) OR (homeTeamId=:second AND awayTeamId=:first))
        AND startUtcMillis BETWEEN :from AND :until ORDER BY startUtcMillis, id LIMIT 10""")
    suspend fun candidates(league: String, first: String, second: String, from: Long, until: Long): List<SportsEvent>
    @Upsert suspend fun upsertEvents(events: List<SportsEvent>)
    @Upsert suspend fun upsertProgrammes(programmes: List<SportsProgramme>)
    @Query("DELETE FROM sports_programmes WHERE feedId=:feedId") suspend fun clearFeedProgrammes(feedId: Long)
    @Query("DELETE FROM sports_programmes WHERE feedId NOT IN (:enabled)") suspend fun removeDisabledFeeds(enabled: List<Long>)
    @Query("DELETE FROM sports_events WHERE endUtcMillis<:before AND id NOT IN (SELECT eventId FROM sports_decisions)")
    suspend fun pruneEvents(before: Long)
    @Query("DELETE FROM sports_programmes WHERE endUtcMillis<:before") suspend fun pruneProgrammes(before: Long)

    /**
     * All provider variants are retained. SQLite's single MIN aggregate selects its evidence
     * row's bare columns, with a content-key tie break, before paging distinct channel entries.
     */
    @Transaction
    @Query("""SELECT sp.*, c.id AS channelId, s.name AS providerName, d.accepted, d.eventId AS decisionEventId,
        MIN((CASE WHEN d.accepted=1 THEN '0' WHEN sp.confidence='CONFIRMED' AND sp.startUtcMillis=target.startUtcMillis THEN '1'
            WHEN sp.confidence='LIKELY' THEN '2' ELSE '3' END) || sp.key) AS matchOrder
        FROM sports_programmes sp JOIN epg_feeds f ON f.id=sp.feedId AND f.enabled=1
        JOIN sports_events target ON target.id=:eventId AND sp.endUtcMillis>target.startUtcMillis AND sp.startUtcMillis<target.endUtcMillis
            AND target.status NOT IN ('CANCELED','POSTPONED')
        CROSS JOIN channels c ON (c.epgOverrideId=sp.epgChannelId OR c.epgChannelId=sp.epgChannelId OR c.matchedEpgId=sp.epgChannelId)
        AND (sp.epgChannelId=c.epgOverrideId OR (sp.epgChannelId=c.epgChannelId AND NOT EXISTS (
            SELECT 1 FROM programmes preferred JOIN epg_feeds preferredf ON preferredf.id=preferred.feedId AND preferredf.enabled=1
            WHERE preferred.epgChannelId=c.epgOverrideId AND preferred.endUtcMillis>sp.startUtcMillis AND preferred.startUtcMillis<sp.endUtcMillis))
            OR (sp.epgChannelId=c.matchedEpgId AND NOT EXISTS (
            SELECT 1 FROM programmes preferred JOIN epg_feeds preferredf ON preferredf.id=preferred.feedId AND preferredf.enabled=1
            WHERE (preferred.epgChannelId=c.epgOverrideId OR preferred.epgChannelId=c.epgChannelId)
                AND preferred.endUtcMillis>sp.startUtcMillis AND preferred.startUtcMillis<sp.endUtcMillis))) AND c.hidden=0
        JOIN sources s ON s.id=c.sourceId AND s.enabled=1
        LEFT JOIN sports_decisions d ON d.programmeKey=sp.key AND d.sourceId=c.sourceId AND d.streamId=c.streamId
        WHERE ((sp.eventId=:eventId AND (d.eventId IS NULL OR d.eventId=:eventId)) OR d.eventId=:eventId)
        AND sp.key IN (SELECT `key` FROM sports_programmes WHERE eventId=:eventId
            UNION SELECT programmeKey FROM sports_decisions WHERE eventId=:eventId)
        AND (c.categoryId IS NULL OR instr(:excludedCategories, '|' || c.sourceId || ':' || hex(c.categoryId) || '|')=0)
        GROUP BY c.id
        ORDER BY CASE WHEN d.accepted=1 THEN 0 WHEN sp.confidence='CONFIRMED' AND sp.startUtcMillis=target.startUtcMillis THEN 0 WHEN sp.confidence IN ('LIKELY','CONFIRMED') THEN 1 ELSE 2 END,
        CASE WHEN EXISTS(SELECT 1 FROM sports_preferred preferred WHERE preferred.eventId=:eventId
            AND preferred.sourceId=c.sourceId AND preferred.streamId=c.streamId) THEN 0 ELSE 1 END,
        c.sourceId, c.sortIndex, c.id, sp.key LIMIT 200 OFFSET :offset""")
    fun observeStreams(eventId: String, excludedCategories: String = "||", offset: Int = 0): Flow<List<SportsStreamRow>>

    @Query("""SELECT e.*, COUNT(DISTINCT CASE WHEN c.id IS NOT NULL AND s.id IS NOT NULL AND f.id IS NOT NULL
        AND ((d.accepted=1 AND d.eventId=e.id) OR (d.accepted IS NULL AND sp.eventId=e.id AND sp.confidence='CONFIRMED' AND sp.startUtcMillis=e.startUtcMillis)) THEN c.id END) AS streamCount
        FROM sports_events e LEFT JOIN (
            SELECT eventId,`key` FROM sports_programmes UNION SELECT eventId,programmeKey AS `key` FROM sports_decisions WHERE accepted=1
        ) evidence ON evidence.eventId=e.id LEFT JOIN sports_programmes sp ON sp.key=evidence.key
            AND sp.endUtcMillis>e.startUtcMillis AND sp.startUtcMillis<e.endUtcMillis AND e.status NOT IN ('CANCELED','POSTPONED')
        LEFT JOIN epg_feeds f ON f.id=sp.feedId AND f.enabled=1
        LEFT JOIN channels c ON (c.epgOverrideId=sp.epgChannelId OR c.epgChannelId=sp.epgChannelId OR c.matchedEpgId=sp.epgChannelId)
        AND (sp.epgChannelId=c.epgOverrideId OR (sp.epgChannelId=c.epgChannelId AND NOT EXISTS (
            SELECT 1 FROM programmes preferred JOIN epg_feeds preferredf ON preferredf.id=preferred.feedId AND preferredf.enabled=1
            WHERE preferred.epgChannelId=c.epgOverrideId AND preferred.endUtcMillis>sp.startUtcMillis AND preferred.startUtcMillis<sp.endUtcMillis))
            OR (sp.epgChannelId=c.matchedEpgId AND NOT EXISTS (
            SELECT 1 FROM programmes preferred JOIN epg_feeds preferredf ON preferredf.id=preferred.feedId AND preferredf.enabled=1
            WHERE (preferred.epgChannelId=c.epgOverrideId OR preferred.epgChannelId=c.epgChannelId)
                AND preferred.endUtcMillis>sp.startUtcMillis AND preferred.startUtcMillis<sp.endUtcMillis))) AND c.hidden=0
            AND (c.categoryId IS NULL OR instr(:excludedCategories, '|' || c.sourceId || ':' || hex(c.categoryId) || '|')=0)
        LEFT JOIN sources s ON s.id=c.sourceId AND s.enabled=1
        LEFT JOIN sports_decisions d ON d.programmeKey=sp.key AND d.sourceId=c.sourceId AND d.streamId=c.streamId
        WHERE e.endUtcMillis>:from AND e.startUtcMillis<:until
        AND (:league IS NULL OR e.leagueId=:league) AND (:team IS NULL OR e.homeTeamId=:team OR e.awayTeamId=:team)
        AND (:sport IS NULL OR e.sportId=:sport)
        AND (:filter!='FAVORITE_TEAMS' OR EXISTS(SELECT 1 FROM sports_favorites favorite
            WHERE favorite.kind='team' AND favorite.id IN (e.homeTeamId,e.awayTeamId)))
        AND (:filter!='FAVORITE_LEAGUES' OR EXISTS(SELECT 1 FROM sports_favorites favorite WHERE favorite.kind='league' AND favorite.id=e.leagueId))
        AND (:filter!='UPCOMING' OR e.startUtcMillis>:now)
        AND (:filter NOT IN ('TODAY','TOMORROW') OR (e.startUtcMillis>=:dayFrom AND e.startUtcMillis<:dayUntil))
        AND (:filter!='LIVE' OR (e.startUtcMillis<=:now AND e.endUtcMillis>:now AND e.updatedAtMillis<=:now
            AND e.updatedAtMillis>=:now-21600000 AND e.status NOT IN ('CANCELED','POSTPONED','FINAL')))
        AND (e.title LIKE :pattern ESCAPE '\' OR e.leagueId LIKE :pattern ESCAPE '\' OR e.sportId LIKE :pattern ESCAPE '\'
            OR e.homeName LIKE :pattern ESCAPE '\' OR e.awayName LIKE :pattern ESCAPE '\'
            OR EXISTS(SELECT 1 FROM sports_programmes searchsp JOIN channels searchc
                ON (searchc.epgOverrideId=searchsp.epgChannelId OR searchc.epgChannelId=searchsp.epgChannelId OR searchc.matchedEpgId=searchsp.epgChannelId)
                JOIN sources searchs ON searchs.id=searchc.sourceId AND searchs.enabled=1
                WHERE searchsp.eventId=e.id AND searchc.hidden=0 AND
                (searchc.displayName LIKE :pattern ESCAPE '\' OR searchc.customName LIKE :pattern ESCAPE '\')))
        GROUP BY e.id HAVING (e.verified=1 OR COUNT(CASE WHEN c.id IS NOT NULL AND s.id IS NOT NULL AND f.id IS NOT NULL THEN c.id END)>0)
        AND (:filter!='WITH_STREAMS' OR streamCount>0)
        ORDER BY CASE WHEN EXISTS(SELECT 1 FROM sports_favorites favorite
            WHERE (favorite.kind='team' AND favorite.id IN (e.homeTeamId,e.awayTeamId)) OR
            (favorite.kind='league' AND favorite.id=e.leagueId) OR (favorite.kind='sport' AND favorite.id=e.sportId)) THEN 0 ELSE 1 END,
        e.startUtcMillis,e.id LIMIT :limit""")
    fun observeCards(from: Long, until: Long, pattern: String, limit: Int, excludedCategories: String = "||",
        filter: String = "ALL", now: Long = from, dayFrom: Long = from, dayUntil: Long = until,
        league: String? = null, team: String? = null, sport: String? = null): Flow<List<SportsEventCard>>

    @Query("SELECT * FROM sports_programmes WHERE key=:key") suspend fun programme(key: String): SportsProgramme?
    @Query("""SELECT * FROM sports_programmes WHERE eventId=:eventId AND epgChannelId IN (:epgIds)
        AND endUtcMillis>:from AND startUtcMillis<:until ORDER BY `key`""")
    suspend fun programmeEvidence(eventId: String, epgIds: List<String>, from: Long, until: Long): List<SportsProgramme>
    @Upsert suspend fun decide(decision: SportsDecision)
    @Query("SELECT * FROM sports_decisions WHERE programmeKey=:key AND sourceId=:sourceId AND streamId=:streamId")
    suspend fun decision(key: String, sourceId: Long, streamId: String): SportsDecision?
    @Query("DELETE FROM sports_decisions WHERE programmeKey=:key AND sourceId=:sourceId AND streamId=:streamId")
    suspend fun undoDecision(key: String, sourceId: Long, streamId: String)
    @Query("DELETE FROM sports_decisions WHERE eventId=:eventId AND sourceId=:sourceId AND streamId=:streamId")
    suspend fun undoEventDecision(eventId: String, sourceId: Long, streamId: String)

    @Query("SELECT * FROM sports_favorites ORDER BY kind,rank,id") fun observeFavorites(): Flow<List<SportsFavorite>>
    @Query("SELECT * FROM sports_favorites WHERE kind=:kind ORDER BY rank,id") suspend fun favorites(kind: String): List<SportsFavorite>
    @Upsert suspend fun favorite(favorite: SportsFavorite)
    @Query("DELETE FROM sports_favorites WHERE kind=:kind AND id=:id") suspend fun unfavorite(kind: String, id: String)
    @Query("SELECT * FROM sports_preferred WHERE eventId=:id") fun observePreferred(id: String): Flow<SportsPreferred?>
    @Upsert suspend fun prefer(preferred: SportsPreferred)

    @Query("DELETE FROM sports_events WHERE source='epg' AND id NOT IN (SELECT eventId FROM sports_decisions)")
    suspend fun clearInferredEvents()
    @Query("DELETE FROM sports_programmes") suspend fun clearProgrammes()
}
