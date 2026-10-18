/* Copyright (C) 2026 Nuvix Contributors. GPL-3.0-or-later. */
package app.opentv.sports

import org.junit.Assert.*
import org.junit.Test
import java.util.Locale

/** Regression tests for sports matching. */
class SportsMatchingTest {
    private val p = sportsProgramme()
    private val event = SportsMatching.inferred(p, SPORTS_NOW)!!
    /** Checks: source Scoped Identifiers Do Not Collapse Display Titles. */
    @Test fun sourceScopedIdentifiersDoNotCollapseDisplayTitles() {
        assertNotEquals(event.id, SportsMatching.inferred(p.copy(feedId = 2), SPORTS_NOW)!!.id)
        assertNotEquals(event.id, SportsMatching.inferred(p.copy(epgChannelId = "different"), SPORTS_NOW)!!.id)
        assertEquals(event.id, SportsMatching.inferred(p.copy(id = 900), SPORTS_NOW)!!.id)
    }
    /** Checks: normalization Handles Unicode Whitespace Punctuation And Locale. */
    @Test fun normalizationHandlesUnicodeWhitespacePunctuationAndLocale() {
        val before = Locale.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("tr"))
            assertEquals("montreal canadiens", SportsNames.normalize(" Montréal  Canadiens! "))
            assertEquals("istanbul", SportsNames.normalize("ISTANBUL"))
        } finally { Locale.setDefault(before) }
    }
    /** Checks: aliases Are League Scoped And Do Not Expand Substrings. */
    @Test fun aliasesAreLeagueScopedAndDoNotExpandSubstrings() {
        assertEquals(SportsNames.teamId("nhl", "NYR"), SportsNames.teamId("nhl", "New York Rangers"))
        assertNotEquals(SportsNames.teamId("nhl", "Rangers"), SportsNames.teamId("epl", "Rangers"))
        assertNotEquals(SportsNames.teamId("nhl", "New York Rangers II"), SportsNames.teamId("nhl", "NYR"))
    }
    /** Checks: competition Aliases Are Bounded And Ambiguity Is Retained. */
    @Test fun competitionAliasesAreBoundedAndAmbiguityIsRetained() {
        assertEquals("nhl", SportsLeagues.identify("National Hockey League")!!.id)
        assertNull(SportsLeagues.identify("XNHLY"))
        assertNull(SportsLeagues.identify("College football"))
        assertNull(SportsLeagues.identify("Premier League"))
        assertEquals("epl", SportsLeagues.identify("English Premier League")!!.id)
        assertNull(SportsLeagues.identify("NBA and WNBA"))
        assertEquals("ncaa-football", SportsLeagues.identify("NCAA football")!!.id)
        assertEquals("ncaa-basketball", SportsLeagues.identify("NCAA basketball")!!.id)
    }
    /** Checks: exact Pair Competition And Time Confirm Epg Evidence. */
    @Test fun exactPairCompetitionAndTimeConfirmEpgEvidence() {
        assertEquals(MatchConfidence.CONFIRMED, SportsMatching.confidence(event, p))
        assertEquals(MatchConfidence.CONFIRMED, SportsMatching.confidence(event, p.copy(title = "NHL: NYR vs Bruins")))
        assertEquals(MatchConfidence.CONFIRMED, SportsMatching.confidence(event, p.copy(title = "NHL: Bruins @ NYR")))
        assertFalse(event.verified)
        assertNull(event.homeScore)
    }
    /** Checks: probable Timing Needs Confirmation. */
    @Test fun probableTimingNeedsConfirmation() {
        assertEquals(MatchConfidence.LIKELY, SportsMatching.confidence(event, p.copy(startUtcMillis = p.startUtcMillis + 10 * 60_000)))
        assertNull(SportsMatching.confidence(event, p.copy(startUtcMillis = p.startUtcMillis + 21 * 60_000)))
    }
    /** Checks: wrong Opponent Or Competition Is Rejected. */
    @Test fun wrongOpponentOrCompetitionIsRejected() {
        assertNull(SportsMatching.confidence(event, p.copy(title = "NHL: New York Rangers vs Toronto Maple Leafs")))
        assertNull(SportsMatching.confidence(event, p.copy(title = "NBA: New York Rangers vs Boston Bruins")))
        assertNull(SportsMatching.confidence(event, p.copy(title = "NHL: New York Rangers II vs Boston Bruins")))
    }
    /** Checks: absent Competition Does Not Authorize Automatic Grouping. */
    @Test fun absentCompetitionDoesNotAuthorizeAutomaticGrouping() {
        val unknown = p.copy(title = "New York Rangers vs Boston Bruins", category = "Sports")
        assertEquals(MatchConfidence.POSSIBLE, SportsMatching.confidence(event, unknown))
        assertEquals("", SportsMatching.inferred(unknown, SPORTS_NOW)!!.leagueId)
    }
    /** Checks: generic Sports Metadata Is Retained Without Inventing Teams. */
    @Test fun genericSportsMetadataIsRetainedWithoutInventingTeams() {
        val generic = p.copy(title = "Hockey tonight")
        val inferred = SportsMatching.inferred(generic, SPORTS_NOW)!!
        assertNull(inferred.homeTeamId); assertNull(inferred.homeName)
        assertEquals(MatchConfidence.POSSIBLE, SportsMatching.confidence(inferred, generic))
    }
    /** Checks: paired Teams Can Come From Explicit Epg Description Without Inventing Title Metadata. */
    @Test fun pairedTeamsCanComeFromExplicitEpgDescriptionWithoutInventingTitleMetadata() {
        val withDescription = p.copy(title = "NHL Hockey", description = "New York Rangers vs Boston Bruins")
        assertEquals(MatchConfidence.CONFIRMED, SportsMatching.confidence(event, withDescription))
        assertEquals(MatchConfidence.CONFIRMED, SportsMatching.confidence(event, p.copy(title = p.title + " (Live)")))
    }
    /** Checks: non Sport Versus Title Does Not Create A Game. */
    @Test fun nonSportVersusTitleDoesNotCreateAGame() {
        assertNull(SportsMatching.inferred(p.copy(title = "Alien vs Predator", category = "Movie"), SPORTS_NOW))
    }
    /** Checks: replays Highlights And Preview Shows Never Prove A Live Match. */
    @Test fun replaysHighlightsAndPreviewShowsNeverProveALiveMatch() {
        listOf("Replay", "Highlights", "Classic", "Preview", "Postgame", "News").forEach { suffix ->
            assertNull(SportsMatching.inferred(p.copy(title = p.title + " $suffix"), SPORTS_NOW))
        }
    }
    /** Checks: malformed Data And Non Overlapping Time Are Rejected. */
    @Test fun malformedDataAndNonOverlappingTimeAreRejected() {
        assertNull(SportsMatching.inferred(p.copy(title = ""), SPORTS_NOW))
        assertNull(SportsMatching.inferred(p.copy(title = "NHL\u0000vs"), SPORTS_NOW))
        assertNull(SportsMatching.inferred(p.copy(endUtcMillis = p.startUtcMillis), SPORTS_NOW))
        assertNull(SportsMatching.inferred(p.copy(endUtcMillis = p.startUtcMillis + 2 * SportsRepository.DAY), SPORTS_NOW))
    }
    /** Checks: programme Correction Identity Survives Row Replacement But Not Slot Reuse. */
    @Test fun programmeCorrectionIdentitySurvivesRowReplacementButNotSlotReuse() {
        assertEquals(programmeKey(p), programmeKey(p.copy(id = 777)))
        assertNotEquals(programmeKey(p), programmeKey(p.copy(title = "Different game")))
        assertNotEquals(programmeKey(p), programmeKey(p.copy(startUtcMillis = p.startUtcMillis + 1)))
    }
    /** Checks: cross Source Dedup Requires Explicit Mapping And Validated Teams League And Time. */
    @Test fun crossSourceDedupRequiresExplicitMappingAndValidatedTeamsLeagueAndTime() {
        val a = verifiedEvent()
        val b = verifiedEvent("other", "game-3")
        assertFalse(SportsMatching.canLinkVerified(a, b, false))
        assertTrue(SportsMatching.canLinkVerified(a, b, true))
        assertFalse(SportsMatching.canLinkVerified(a, b.copy(homeTeamId = "wrong"), true))
        assertFalse(SportsMatching.canLinkVerified(a, b.copy(leagueId = "nba"), true))
        assertFalse(SportsMatching.canLinkVerified(a, b.copy(startUtcMillis = a.startUtcMillis + SportsRepository.DAY), true))
    }
    /** Checks: provider Identifiers And Score Payloads Are Validated. */
    @Test fun providerIdentifiersAndScorePayloadsAreValidated() {
        val valid = verifiedEvent()
        assertTrue(validSportsEvent(valid))
        assertFalse(validSportsEvent(valid.copy(id = "unscoped")))
        assertFalse(validSportsEvent(valid.copy(homeScore = 4)))
        assertFalse(validSportsEvent(valid.copy(homeScore = -1, awayScore = 4)))
        assertFalse(validSportsEvent(valid.copy(homeLogo = "file:///private/key")))
        assertFalse(validSportsEvent(valid.copy(homeLogo = "https://user:secret@example.invalid/logo")))
        assertFalse(validSportsEvent(valid.copy(homeLogo = "https://")))
        assertFalse(validSportsEvent(valid.copy(period = "unsafe\u0000period")))
        assertFalse(validSportsEvent(valid.copy(status = "unknown")))
        assertFalse(validSportsEvent(valid.copy(sportId = "soccer")))
    }
    /** Checks: cached Schedules Never Claim Live Scores Or Airing Freshness. */
    @Test fun cachedSchedulesNeverClaimLiveScoresOrAiringFreshness() {
        assertEquals("Airing in EPG", event.displayStatus(event.startUtcMillis + 1))
        assertEquals("Cached schedule", event.copy(updatedAtMillis = SPORTS_NOW - SportsEvent.CACHE_FRESH_MILLIS - 1).displayStatus(SPORTS_NOW))
        assertEquals("Live", verifiedEvent().copy(status = "LIVE").displayStatus(SPORTS_NOW))
        assertEquals("Postponed", event.copy(status = "POSTPONED").displayStatus(SPORTS_NOW))
        assertEquals("Canceled", event.copy(status = "CANCELED").displayStatus(SPORTS_NOW))
        assertEquals("Final", event.copy(status = "FINAL").displayStatus(SPORTS_NOW))
    }
    /** Checks: stream Ordering Prioritizes Confidence Then Preference Without Hiding Providers. */
    @Test fun streamOrderingPrioritizesConfidenceThenPreferenceWithoutHidingProviders() {
        val first = sportsStream(sportsChannel(1, 1))
        val second = sportsStream(sportsChannel(2, 2))
        val likely = sportsStream(sportsChannel(3, 3).copy(favourite = true), MatchConfidence.LIKELY)
        val sorted = orderSportsStreams(listOf(first, first, likely, second), SportsPreferred(event.id, 2, "2"), SportsStreamSort.FAVORITES)
        assertEquals(listOf(2L, 1L, 3L), sorted.map { it.channel.id })
        assertEquals(3, sorted.size)
    }
    /** Checks: favorite Sort Never Promotes Unconfirmed Metadata Over Confirmed Streams. */
    @Test fun favoriteSortNeverPromotesUnconfirmedMetadataOverConfirmedStreams() {
        val confirmed = sportsStream()
        val possible = sportsStream(sportsChannel(2).copy(favourite = true), MatchConfidence.POSSIBLE)
        assertEquals(listOf(confirmed, possible), orderSportsStreams(listOf(possible, confirmed), null, SportsStreamSort.FAVORITES))
    }
}
