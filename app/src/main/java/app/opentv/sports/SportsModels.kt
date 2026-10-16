/* Copyright (C) 2026 Nuvix Contributors. GPL-3.0-or-later. */
package app.opentv.sports

import androidx.room.Entity
import androidx.room.Index
import app.opentv.data.model.Channel
import app.opentv.data.model.Programme
import java.security.MessageDigest

enum class SportsStatus { SCHEDULED, LIVE, INTERMISSION, FINAL, POSTPONED, CANCELED }
enum class MatchConfidence(val label: String) {
    CONFIRMED("Confirmed Match"), LIKELY("Likely Match"), POSSIBLE("Possible Match")
}

/** No URL or broadcaster claims belong in event identity. UTC is used throughout persistence. */
@Entity(tableName = "sports_events", primaryKeys = ["id"], indices = [
    Index(value = ["leagueId", "homeTeamId", "awayTeamId", "startUtcMillis"]),
    Index(value = ["startUtcMillis"]), Index(value = ["updatedAtMillis"]),
])
data class SportsEvent(
    val id: String,
    val sportId: String,
    val leagueId: String,
    val homeTeamId: String?,
    val awayTeamId: String?,
    val homeName: String?,
    val awayName: String?,
    val title: String,
    val startUtcMillis: Long,
    val endUtcMillis: Long,
    val status: String = SportsStatus.SCHEDULED.name,
    val source: String = "epg",
    val sourceEventId: String,
    val verified: Boolean = false,
    val updatedAtMillis: Long,
    val homeLogo: String? = null,
    val awayLogo: String? = null,
    val homeScore: Int? = null,
    val awayScore: Int? = null,
    val period: String? = null,
) {
    fun isFresh(now: Long) = now >= updatedAtMillis && now - updatedAtMillis <= CACHE_FRESH_MILLIS
    fun displayStatus(now: Long): String = when {
        status == SportsStatus.CANCELED.name -> "Canceled"
        status == SportsStatus.POSTPONED.name -> "Postponed"
        status == SportsStatus.FINAL.name -> "Final"
        !isFresh(now) -> "Cached schedule"
        !verified && now in startUtcMillis until endUtcMillis -> "Airing in EPG"
        verified && status == SportsStatus.INTERMISSION.name -> "Halftime / Intermission"
        verified && status == SportsStatus.LIVE.name -> "Live"
        now >= endUtcMillis -> "EPG window ended"
        else -> "Not started"
    }
    companion object { const val CACHE_FRESH_MILLIS = 6 * 60 * 60 * 1000L }
}

/** Programme identity survives Room row replacement; the content digest prevents slot reuse. */
@Entity(tableName = "sports_programmes", primaryKeys = ["key"], indices = [
    Index(value = ["eventId"]), Index(value = ["feedId"]), Index(value = ["epgChannelId", "startUtcMillis"]),
])
data class SportsProgramme(
    val key: String, val eventId: String, val feedId: Long, val epgChannelId: String,
    val startUtcMillis: Long, val endUtcMillis: Long, val title: String, val confidence: String,
)

/** Corrections affect this local programme and provider entry only, never the provider catalogue. */
@Entity(tableName = "sports_decisions", primaryKeys = ["programmeKey", "sourceId", "streamId"], indices = [Index(value = ["eventId"])])
data class SportsDecision(
    val programmeKey: String, val sourceId: Long, val streamId: String,
    val eventId: String, val accepted: Boolean,
)

@Entity(tableName = "sports_favorites", primaryKeys = ["kind", "id"], indices = [Index(value = ["kind", "rank"])])
data class SportsFavorite(val kind: String, val id: String, val leagueId: String, val name: String, val rank: Int)

@Entity(tableName = "sports_preferred", primaryKeys = ["eventId"])
data class SportsPreferred(val eventId: String, val sourceId: Long, val streamId: String)

@Entity(tableName = "sports_feed_state", primaryKeys = ["feedId"])
data class SportsFeedState(val feedId: Long, val revision: String, val indexedUntil: Long, val partial: Boolean = false)

data class SportsStream(
    val channel: Channel, val programme: SportsProgramme, val providerName: String,
    val confidence: MatchConfidence, val userConfirmed: Boolean = false, val rejected: Boolean = false,
    val currentProgramme: String? = null,
) { val available: Boolean get() = confidence == MatchConfidence.CONFIRMED && !rejected }

internal fun sportsHash(value: String): String = MessageDigest.getInstance("SHA-256")
    .digest(value.toByteArray()).joinToString("") { "%02x".format(it) }
internal fun programmeKey(p: Programme): String = sportsHash(
    "${p.feedId}\u0000${p.epgChannelId}\u0000${p.startUtcMillis}\u0000${p.endUtcMillis}\u0000${p.title}",
)

/** Replaceable schedule interface. No network provider is enabled or contacted by default. */
interface SportsDataProvider {
    val sourceId: String
    suspend fun schedule(fromUtcMillis: Long, untilUtcMillis: Long): List<SportsEvent>
}

data class SportsProviderResult(val events: List<SportsEvent>, val failedSources: Set<String>)

/** One provider failure must not discard the other providers or the local EPG cache. */
internal suspend fun loadSportsProviders(providers: List<SportsDataProvider>, from: Long, until: Long): SportsProviderResult {
    val result = mutableListOf<SportsEvent>()
    val failed = mutableSetOf<String>()
    for (provider in providers) {
        try {
            result += provider.schedule(from, until).take(5000).filter {
                validSportsEvent(it) && it.source == provider.sourceId && it.startUtcMillis in from until until
            }
        } catch (cancel: kotlinx.coroutines.CancellationException) { throw cancel
        } catch (_: Exception) { failed += provider.sourceId }
    }
    return SportsProviderResult(result, failed)
}

internal fun validSportsEvent(e: SportsEvent): Boolean =
    e.source != "epg" && e.source.matches(Regex("[a-z][a-z0-9_-]{0,47}")) && e.sourceEventId.length in 1..180 &&
        e.id == "${e.source}:${sportsHash(e.sourceEventId)}" && e.title.length in 1..300 &&
        e.title.none { it.isISOControl() } && e.endUtcMillis > e.startUtcMillis &&
        e.endUtcMillis - e.startUtcMillis <= 24 * 60 * 60 * 1000L &&
        SportsLeagues.byId(e.leagueId)?.sportId == e.sportId &&
        e.status in SportsStatus.entries.map { it.name } && e.startUtcMillis >= 0 && e.updatedAtMillis >= 0 &&
        e.homeName != null && e.awayName != null && e.homeName.length in 2..100 && e.awayName.length in 2..100 &&
        e.homeName.none { it.isISOControl() } && e.awayName.none { it.isISOControl() } &&
        e.homeTeamId == SportsNames.teamId(e.leagueId, e.homeName) && e.awayTeamId == SportsNames.teamId(e.leagueId, e.awayName) &&
        e.homeTeamId != e.awayTeamId &&
        listOfNotNull(e.homeScore, e.awayScore).all { it in 0..1000 } &&
        ((e.homeScore == null) == (e.awayScore == null)) &&
        (e.period == null || (e.period.length <= 120 && e.period.none { it.isISOControl() })) &&
        listOfNotNull(e.homeLogo, e.awayLogo).all { logo -> logo.length <= 2048 && runCatching {
            val uri = java.net.URI(logo)
            uri.scheme == "https" && !uri.host.isNullOrBlank() && uri.rawUserInfo == null
        }.getOrDefault(false) }
