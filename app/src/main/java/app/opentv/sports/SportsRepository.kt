/* Copyright (C) 2026 Nuvix Contributors. GPL-3.0-or-later. */
package app.opentv.sports

import androidx.room.withTransaction
import app.opentv.data.db.OpenTvDatabase
import app.opentv.data.model.Reminder
import app.opentv.data.model.shownName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Guide reminders use the same channel/start slot that [SportsRepository.remind] reuses. */
fun sportsReminderForEvent(reminders: List<Reminder>, eventId: String, streams: List<SportsStream>): Reminder? =
    reminders.firstOrNull { it.sportsEventId == eventId }
        ?: streams.filter { it.available }.firstNotNullOfOrNull { stream ->
            reminders.firstOrNull { it.channelId == stream.channel.id && it.startUtcMillis == stream.programme.startUtcMillis }
        }

/** All matching is local. This class never resolves playback URLs or opens a provider connection. */
class SportsRepository(private val db: OpenTvDatabase) {
    val dao get() = db.sports()
    private val reconcileMutex = Mutex()
    val favorites = dao.observeFavorites()

    /** Per-feed revision checkpoints avoid rescanning unchanged guide data on screen entry. */
    suspend fun reconcile(now: Long): Boolean = withContext(Dispatchers.IO) { reconcileMutex.withLock {
        val feeds = dao.observeRevisions().first()
        var partial = false
        for (feed in feeds) {
            val state = dao.feedState(feed.feedId)
            val until = now + HORIZON
            if (state?.revision == feed.revision && state.indexedUntil >= until - REINDEX_INTERVAL) {
                partial = partial || state.partial
                continue
            }
            db.withTransaction {
                dao.clearFeedProgrammes(feed.feedId)
                var after = 0L
                var processed = 0
                var feedPartial = false
                while (true) {
                    val batch = dao.programmeBatch(feed.feedId, after, now - DAY, until)
                    if (batch.isEmpty()) break
                    val associations = mutableListOf<SportsProgramme>()
                    for (p in batch) {
                        val event = SportsMatching.inferred(p, feed.lastSyncMillis) ?: continue
                        val candidates = if (event.leagueId.isNotBlank() && event.homeTeamId != null && event.awayTeamId != null)
                            dao.candidates(event.leagueId, event.homeTeamId, event.awayTeamId,
                                event.startUtcMillis - SportsMatching.START_TOLERANCE,
                                event.startUtcMillis + SportsMatching.START_TOLERANCE)
                                .filter { SportsMatching.confidence(it, p) != null }
                        else emptyList()
                        val match = candidates.filter { it.verified }.singleOrNull() ?: candidates.singleOrNull()
                        val selected = match ?: dao.event(event.id) ?: event
                        // Never promote EPG timing to a verified live score/status.
                        if (!selected.verified) {
                            dao.upsertEvents(listOf(selected.copy(updatedAtMillis = maxOf(selected.updatedAtMillis, feed.lastSyncMillis))))
                        }
                        val confidence = if (match == null && candidates.size > 1) MatchConfidence.POSSIBLE
                            else SportsMatching.confidence(selected, p) ?: MatchConfidence.POSSIBLE
                        associations += SportsProgramme(programmeKey(p), selected.id, p.feedId, p.epgChannelId,
                            p.startUtcMillis, p.endUtcMillis, p.title, confidence.name)
                    }
                    dao.upsertProgrammes(associations)
                    after = batch.last().id
                    processed += batch.size
                    // Explicit bound for pathological feeds. The UI reports partial indexing.
                    if (processed >= MAX_FEED_PROGRAMMES) { partial = true; feedPartial = true; break }
                }
                dao.setFeedState(SportsFeedState(feed.feedId, feed.revision, until, feedPartial))
            }
        }
        if (feeds.isEmpty()) { dao.clearProgrammes(); dao.clearFeedStates() }
        else { dao.removeDisabledFeeds(feeds.map { it.feedId }); dao.removeDisabledFeedStates(feeds.map { it.feedId }) }
        dao.pruneProgrammes(now - 2 * DAY)
        dao.pruneEvents(now - 2 * DAY)
        partial
    } }

    /** Loads a bounded page of eligible provider streams associated with an event. */
    fun streams(eventId: String, excludedCategories: String = "||", offset: Int = 0) = combine(dao.observeStreams(eventId, excludedCategories, offset), dao.observeEvent(eventId)) { rows, event ->
        rows.map { row ->
            SportsStream(row.channel, row.programme, row.providerName,
                if (row.accepted == true) MatchConfidence.CONFIRMED
                else runCatching { MatchConfidence.valueOf(row.programme.confidence) }.getOrDefault(MatchConfidence.POSSIBLE).let { confidence ->
                    if (confidence == MatchConfidence.CONFIRMED && row.programme.startUtcMillis != event?.startUtcMillis) MatchConfidence.LIKELY else confidence
                },
                userConfirmed = row.accepted == true, rejected = row.accepted == false)
        }
    }

    /** Local-only, content-scoped decision. Disabled/hidden/deleted channels cannot be attached. */
    suspend fun decide(eventId: String, programmeKey: String, channelId: Long, accepted: Boolean) = withContext(Dispatchers.IO) {
        db.withTransaction {
            val event = requireNotNull(dao.event(eventId))
            val p = requireNotNull(dao.programme(programmeKey))
            val channel = requireNotNull(db.channels().byId(channelId))
            require(!channel.hidden && db.sources().byId(channel.sourceId)?.enabled == true)
            require(p.epgChannelId in channel.epgCandidates)
            require(event.endUtcMillis > p.startUtcMillis && p.endUtcMillis > event.startUtcMillis) { "Programme does not overlap the game" }
            // Multiple guide feeds can describe this same provider choice. Correct the whole
            // displayed association so duplicate evidence cannot resurrect a rejected broadcast.
            val evidence = dao.programmeEvidence(p.eventId, channel.epgCandidates, event.startUtcMillis, event.endUtcMillis)
            (evidence + p).distinctBy { it.key }.forEach { item ->
                dao.decide(SportsDecision(item.key, channel.sourceId, channel.streamId, eventId, accepted))
            }
        }
    }

    /** Saves the selected preferred provider-channel choice for an event. */
    suspend fun prefer(eventId: String, stream: SportsStream) {
        require(stream.available && !stream.rejected)
        dao.prefer(SportsPreferred(eventId, stream.channel.sourceId, stream.channel.streamId))
    }

    /** Revalidate the selected local entry at click time without opening or probing a stream. */
    suspend fun playable(eventId: String, stream: SportsStream): app.opentv.data.model.Channel? = withContext(Dispatchers.IO) {
        val channel = db.channels().byId(stream.channel.id) ?: return@withContext null
        if (channel.hidden || db.sources().byId(channel.sourceId)?.enabled != true ||
            channel.sourceId != stream.channel.sourceId || channel.streamId != stream.channel.streamId) return@withContext null
        val p = dao.programme(stream.programme.key) ?: return@withContext null
        val event = dao.event(eventId) ?: return@withContext null
        if (event.status in setOf("CANCELED", "POSTPONED") || p.endUtcMillis<=event.startUtcMillis || p.startUtcMillis>=event.endUtcMillis) return@withContext null
        if (p.epgChannelId !in channel.epgCandidates || !dao.enabledFeed(p.feedId)) return@withContext null
        val decision = dao.decision(p.key, channel.sourceId, channel.streamId)
        if (decision != null) {
            if (!decision.accepted || decision.eventId != eventId) return@withContext null
        } else if (p.eventId != eventId || p.confidence != MatchConfidence.CONFIRMED.name || p.startUtcMillis != event.startUtcMillis) return@withContext null
        channel
    }

    /** Fresh, bounded same-game recovery choices; this reads metadata and never probes playback. */
    suspend fun nextPlaybackChannel(eventId: String, attempted: Set<Long>, sort: SportsStreamSort, now: Long,
        visible: (app.opentv.data.model.Channel) -> Boolean): app.opentv.data.model.Channel? {
        val event = dao.event(eventId) ?: return null
        if (now !in event.startUtcMillis until event.endUtcMillis || event.status in setOf("CANCELED", "POSTPONED", "FINAL")) return null
        val preferred = dao.observePreferred(eventId).first()
        // Match the hub's bounded result scale without limiting recovery to its visible page.
        for (page in 0 until 10) {
            val rows = streams(eventId, offset = page * 200).first()
            for (stream in orderSportsStreams(rows, preferred, sort)) {
                if (!stream.available || stream.channel.id in attempted ||
                    now !in stream.programme.startUtcMillis until stream.programme.endUtcMillis) continue
                val channel = playable(eventId, stream) ?: continue
                if (visible(channel)) return channel
            }
            if (rows.size < 200) break
        }
        return null
    }

    /** Adds or removes a league or team from saved sports favorites. */
    suspend fun toggleFavorite(kind: String, id: String, leagueId: String, name: String) = withContext(Dispatchers.IO) {
        require(kind in setOf("team", "league", "sport"))
        require(name.length in 1..120 && name.none { it.isISOControl() })
        require(when (kind) {
            "league" -> SportsLeagues.byId(id) != null && id == leagueId
            "team" -> SportsLeagues.byId(leagueId) != null && id == SportsNames.teamId(leagueId, name)
            else -> id in SPORT_IDS && leagueId.isEmpty()
        })
        db.withTransaction {
            val current = dao.favorites(kind)
            if (current.any { it.id == id }) dao.unfavorite(kind, id)
            else { require(current.size < 200); dao.favorite(SportsFavorite(kind, id, leagueId, name.trim(), (current.maxOfOrNull { it.rank } ?: -1) + 1)) }
        }
    }

    /** Moves a favorite league or team within its persisted order. */
    suspend fun moveFavorite(kind: String, id: String, delta: Int) = withContext(Dispatchers.IO) { db.withTransaction {
        val ordered = dao.favorites(kind).toMutableList()
        val from = ordered.indexOfFirst { it.id == id }
        if (from >= 0) {
            ordered.add((from + delta.coerceIn(-1, 1)).coerceIn(0, ordered.lastIndex), ordered.removeAt(from))
            ordered.forEachIndexed { rank, favorite -> dao.favorite(favorite.copy(rank = rank)) }
        }
    } }

    /** Atomic game identity plus existing channel/slot reminders prevents multi-provider duplicates. */
    suspend fun remind(eventId: String, streams: List<SportsStream>, now: Long): Reminder = withContext(Dispatchers.IO) {
        db.withTransaction {
            val event = requireNotNull(dao.event(eventId))
            require(event.startUtcMillis > now && event.status !in setOf("CANCELED", "POSTPONED", "FINAL"))
            db.reminders().forSportsEvent(eventId)?.let { return@withTransaction it }
            val available = streams.filter { it.available && playable(eventId, it) != null }
            require(available.isNotEmpty()) { "Confirm an available stream first" }
            val selected = available.first()
            for (stream in available) {
                db.reminders().forProgramme(stream.channel.id, stream.programme.startUtcMillis)?.let { existing ->
                    val linked = existing.copy(sportsEventId = eventId)
                    db.reminders().update(linked)
                    return@withTransaction linked
                }
            }
            val reminder = Reminder(channelId = selected.channel.id, channelName = selected.channel.shownName,
                logoUrl = selected.channel.logoUrl, title = event.title, startUtcMillis = event.startUtcMillis,
                endUtcMillis = event.endUtcMillis, createdAtMillis = now, sportsEventId = eventId)
            reminder.copy(id = db.reminders().insert(reminder))
        }
    }

    /** Only reliable source-scoped updates may move a scheduled reminder or cancel its alarm. */
    suspend fun saveProviderEvents(events: List<SportsEvent>, onReminderChanged: (Reminder, Boolean) -> Unit) = withContext(Dispatchers.IO) {
        for (event in events) {
            require(event.verified && validSportsEvent(event))
            val change = db.withTransaction {
                val previous = dao.event(event.id)
                if (previous != null && previous.updatedAtMillis > event.updatedAtMillis) return@withTransaction null
                dao.upsertEvents(listOf(event))
                val reminder = db.reminders().forSportsEvent(event.id) ?: return@withTransaction null
                val cancel = event.status in setOf("CANCELED", "POSTPONED", "FINAL")
                val updated = reminder.copy(startUtcMillis = event.startUtcMillis, endUtcMillis = event.endUtcMillis,
                    fired = cancel || (reminder.fired && reminder.startUtcMillis == event.startUtcMillis))
                db.reminders().update(updated)
                updated to cancel
            }
            change?.let { onReminderChanged(it.first, it.second) }
        }
    }

    /** Favorites, decisions and preferred broadcasts survive clearing the derived cache. */
    suspend fun clearCache() = withContext(Dispatchers.IO) { reconcileMutex.withLock { db.withTransaction {
        dao.clearProgrammes(); dao.clearInferredEvents(); dao.clearFeedStates()
    } } }

    companion object {
        const val DAY = 24 * 60 * 60 * 1000L
        const val HORIZON = 7 * DAY
        const val REINDEX_INTERVAL = 6 * 60 * 60 * 1000L
        const val MAX_FEED_PROGRAMMES = 50_000
        val SPORT_IDS = setOf("football", "soccer", "hockey", "basketball", "baseball", "cricket", "tennis", "rugby", "golf", "sports")
    }
}
