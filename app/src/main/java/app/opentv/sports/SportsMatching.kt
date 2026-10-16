/* Copyright (C) 2026 Nuvix Contributors. GPL-3.0-or-later. */
package app.opentv.sports

import app.opentv.data.model.Programme
import java.text.Normalizer
import java.util.Locale
import kotlin.math.abs

data class SportsLeague(val id: String, val name: String, val sportId: String, val aliases: List<String>)

object SportsLeagues {
    // Definitions are identifiers, not assertions that a league has a schedule API.
    val all = listOf(
        SportsLeague("nfl", "NFL", "football", listOf("nfl", "national football league")),
        SportsLeague("nba", "NBA", "basketball", listOf("nba", "national basketball association")),
        SportsLeague("wnba", "WNBA", "basketball", listOf("wnba", "women s national basketball association")),
        SportsLeague("nhl", "NHL", "hockey", listOf("nhl", "national hockey league")),
        SportsLeague("mlb", "MLB", "baseball", listOf("mlb", "major league baseball")),
        SportsLeague("mls", "MLS", "soccer", listOf("mls", "major league soccer")),
        SportsLeague("ncaa-football", "NCAA football", "football", listOf("ncaa football", "ncaa fbs", "ncaa fcs")),
        SportsLeague("ncaa-basketball", "NCAA basketball", "basketball", listOf("ncaa basketball")),
        SportsLeague("epl", "Premier League", "soccer", listOf("english premier league", "epl")),
        SportsLeague("uefa-champions", "UEFA Champions League", "soccer", listOf("uefa champions league")),
        SportsLeague("la-liga", "La Liga", "soccer", listOf("la liga")),
        SportsLeague("bundesliga", "Bundesliga", "soccer", listOf("german bundesliga", "bundesliga germany")),
        SportsLeague("serie-a", "Serie A", "soccer", listOf("italian serie a", "serie a italy")),
    )
    fun byId(id: String) = all.firstOrNull { it.id == id }
    fun identify(text: String): SportsLeague? {
        val normalized = " ${SportsNames.normalize(text)} "
        // Multiple explicit competitions are ambiguous; do not choose the first.
        return all.filter { league -> league.aliases.any { " $it " in normalized } }.singleOrNull()
    }
}

object SportsNames {
    fun normalize(name: String): String = Normalizer.normalize(name.take(500), Normalizer.Form.NFKD)
        .replace(Regex("\\p{M}"), "").lowercase(Locale.ROOT)
        .replace('&', ' ').replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim().replace(Regex("\\s+"), " ")

    // Aliases are deliberately league-scoped. Never globally replace ambiguous nicknames.
    private val aliases = mapOf(
        "nhl" to mapOf("ny rangers" to "new york rangers", "nyr" to "new york rangers", "rangers" to "new york rangers",
            "boston bruins" to "boston bruins", "bruins" to "boston bruins", "bos bruins" to "boston bruins"),
        "nba" to mapOf("la lakers" to "los angeles lakers", "lakers" to "los angeles lakers",
            "boston celtics" to "boston celtics", "celtics" to "boston celtics"),
        "mlb" to mapOf("ny yankees" to "new york yankees", "yankees" to "new york yankees",
            "boston red sox" to "boston red sox", "red sox" to "boston red sox"),
        "nfl" to mapOf("kc chiefs" to "kansas city chiefs", "chiefs" to "kansas city chiefs",
            "sf 49ers" to "san francisco 49ers", "49ers" to "san francisco 49ers"),
    )
    fun teamName(leagueId: String, name: String): String {
        val normal = normalize(name)
        return aliases[leagueId]?.get(normal) ?: normal
    }
    fun teamId(leagueId: String, name: String) = "$leagueId:${sportsHash(teamName(leagueId, name))}"
}

data class ParsedSportsProgramme(val league: SportsLeague?, val sport: String, val first: String?, val second: String?)

object SportsMatching {
    const val START_TOLERANCE = 20 * 60 * 1000L
    private val versus = Regex("\\s+(?:vs\\.?|v\\.?|versus|@|at)\\s+", RegexOption.IGNORE_CASE)
    private val nonGame = Regex("\\b(replay|highlights|classic|review|preview|postgame|pregame|news|documentary)\\b", RegexOption.IGNORE_CASE)
    private val sportWords = mapOf("football" to "football", "soccer" to "soccer", "hockey" to "hockey",
        "basketball" to "basketball", "baseball" to "baseball", "cricket" to "cricket", "tennis" to "tennis",
        "rugby" to "rugby", "golf" to "golf", "sport" to "sports", "sports" to "sports")

    fun parse(p: Programme): ParsedSportsProgramme? {
        if (p.title.length !in 1..300 || p.title.any { it.isISOControl() } || p.durationMillis !in 1..24*60*60*1000L) return null
        val metadata = "${p.category.orEmpty()} ${p.title} ${p.description.orEmpty().take(2000)}"
        if (nonGame.containsMatchIn(metadata)) return null
        val league = SportsLeagues.identify(metadata)
        val words = SportsNames.normalize(metadata).split(' ').toSet()
        val sport = league?.sportId ?: sportWords.entries.firstOrNull { it.key in words }?.value ?: return null
        // A prefix explicitly naming the league is metadata, not part of the team name.
        var title = p.title.trim()
        if (':' in title && SportsLeagues.identify(title.substringBefore(':')) != null) title = title.substringAfter(':').trim()
        title = title.replace(Regex("\\s*\\((?:live)\\)\\s*$", RegexOption.IGNORE_CASE), "").trim()
        if (!versus.containsMatchIn(title)) {
            val description = p.description.orEmpty().lineSequence().firstOrNull { versus.containsMatchIn(it) }?.trim().orEmpty()
            if (description.length in 5..210 && !description.contains(Regex("[.!?]"))) title = description
        }
        val teams = versus.split(title)
        val pair = teams.takeIf { it.size == 2 && it.all { n -> n.length in 2..100 && n.none { ch -> ch in "|:;" } } }
        return ParsedSportsProgramme(league, sport, pair?.get(0), pair?.get(1))
    }

    fun inferred(p: Programme, now: Long): SportsEvent? {
        val parsed = parse(p) ?: return null
        val leagueId = parsed.league?.id.orEmpty()
        val sourceId = "${p.feedId}/${p.epgChannelId}/${p.startUtcMillis}/${sportsHash(p.title)}"
        return SportsEvent("epg:${sportsHash(sourceId)}", parsed.sport, leagueId,
            parsed.first?.let { SportsNames.teamId(leagueId, it) }, parsed.second?.let { SportsNames.teamId(leagueId, it) },
            // EPG order is retained as supplied; 'home'/'away' is not asserted in the UI.
            parsed.first, parsed.second, p.title, p.startUtcMillis, p.endUtcMillis,
            sourceEventId = sourceId, updatedAtMillis = now)
    }

    /** Strict paired-team, competition and time evidence; no substring team matching. */
    fun confidence(event: SportsEvent, p: Programme): MatchConfidence? {
        val candidate = inferred(p, event.updatedAtMillis) ?: return null
        if (abs(event.startUtcMillis - p.startUtcMillis) > START_TOLERANCE ||
            p.endUtcMillis <= event.startUtcMillis || p.startUtcMillis >= event.endUtcMillis) return null
        if (event.leagueId.isNotEmpty() && candidate.leagueId.isNotEmpty() && event.leagueId != candidate.leagueId) return null
        val expected = listOfNotNull(event.homeTeamId, event.awayTeamId).toSet()
        val actual = listOfNotNull(candidate.homeTeamId, candidate.awayTeamId).toSet()
        if (expected.size != 2 || actual.size != 2) return if (event.id == candidate.id) MatchConfidence.POSSIBLE else null
        if (event.leagueId.isEmpty() || candidate.leagueId.isEmpty()) {
            val names = listOfNotNull(event.homeName, event.awayName).map(SportsNames::normalize).toSet()
            val other = listOfNotNull(candidate.homeName, candidate.awayName).map(SportsNames::normalize).toSet()
            return if (names == other) MatchConfidence.POSSIBLE else null
        }
        if (expected != actual) return null
        return if (p.startUtcMillis == event.startUtcMillis) MatchConfidence.CONFIRMED else MatchConfidence.LIKELY
    }

    /** Verified sources are combined only via an explicit, validated source-ID mapping. */
    fun canLinkVerified(a: SportsEvent, b: SportsEvent, explicitAssociation: Boolean): Boolean =
        explicitAssociation && a.verified && b.verified && a.leagueId.isNotBlank() && a.leagueId == b.leagueId &&
            a.homeTeamId != null && a.awayTeamId != null && a.homeTeamId == b.homeTeamId && a.awayTeamId == b.awayTeamId &&
            abs(a.startUtcMillis - b.startUtcMillis) <= START_TOLERANCE
}

enum class SportsStreamSort { CONFIDENCE, FAVORITES, PROVIDER }

fun orderSportsStreams(streams: List<SportsStream>, preferred: SportsPreferred?, sort: SportsStreamSort): List<SportsStream> =
    streams.distinctBy { Triple(it.channel.sourceId, it.channel.streamId, it.programme.key) }
        .sortedWith(compareBy<SportsStream> { it.confidence.ordinal }
            .thenByDescending { it.channel.sourceId == preferred?.sourceId && it.channel.streamId == preferred?.streamId }
            .thenByDescending { sort == SportsStreamSort.FAVORITES && it.channel.favourite }
            .thenBy { if (sort == SportsStreamSort.PROVIDER) it.providerName else "" }
            .thenBy { it.channel.sourceId }.thenBy { it.channel.sortIndex }.thenBy { it.channel.displayName }.thenBy { it.channel.id })
        .distinctBy { it.channel.sourceId to it.channel.streamId }
