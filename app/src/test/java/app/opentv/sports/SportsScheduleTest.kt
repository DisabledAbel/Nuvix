/* Copyright (C) 2026 Nuvix Contributors. GPL-3.0-or-later. */
package app.opentv.sports

import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/** Regression tests for sports schedule. */
class SportsScheduleTest {
    /** Checks: timezone Uses Calendar Day Instead Of Utc Midnight. */
    @Test fun timezoneUsesCalendarDayInsteadOfUtcMidnight() {
        val now = Instant.parse("2026-10-17T01:00:00Z").toEpochMilli()
        val range = sportsDayWindow(now, ZoneId.of("America/Los_Angeles"))
        assertEquals(Instant.parse("2026-10-16T07:00:00Z").toEpochMilli(), range.first)
        assertEquals(Instant.parse("2026-10-17T07:00:00Z").toEpochMilli() - 1, range.last)
    }
    /** Checks: dst Spring Day Has23 Hours And Fall Day Has25. */
    @Test fun dstSpringDayHas23HoursAndFallDayHas25() {
        val zone = ZoneId.of("America/Los_Angeles")
        val spring = sportsDayWindow(Instant.parse("2026-03-08T15:00:00Z").toEpochMilli(), zone)
        val fall = sportsDayWindow(Instant.parse("2026-11-01T15:00:00Z").toEpochMilli(), zone)
        assertEquals(23 * 60 * 60_000L, spring.last - spring.first + 1)
        assertEquals(25 * 60 * 60_000L, fall.last - fall.first + 1)
    }
    /** Checks: tomorrow Follows Local Calendar Across Dst Boundary. */
    @Test fun tomorrowFollowsLocalCalendarAcrossDstBoundary() {
        val range = sportsDayWindow(Instant.parse("2026-03-08T01:00:00Z").toEpochMilli(), ZoneId.of("America/Los_Angeles"), true)
        assertEquals(23 * 60 * 60_000L, range.last - range.first + 1)
    }
    /** Builds the synthetic provider fixture used by the test. */
    private fun provider(id: String, block: () -> List<SportsEvent>) = object : SportsDataProvider {
        override val sourceId = id
        override suspend fun schedule(fromUtcMillis: Long, untilUtcMillis: Long) = block()
    }
    /** Checks: provider Failure Keeps Partial Results And Rejects Unsafe And Foreign Rows. */
    @Test fun providerFailureKeepsPartialResultsAndRejectsUnsafeAndForeignRows() = runBlocking {
        val event = verifiedEvent()
        val result = loadSportsProviders(listOf(provider("broken") { error("synthetic failure") },
            provider("fixture") { listOf(event, event.copy(id = "unsafe"), verifiedEvent("other")) }), SPORTS_NOW, SPORTS_NOW + SportsRepository.DAY)
        assertEquals(listOf(event), result.events)
        assertEquals(setOf("broken"), result.failedSources)
    }
    /** Checks: no External Provider Is Required For Offline Mode. */
    @Test fun noExternalProviderIsRequiredForOfflineMode() = runBlocking {
        assertEquals(SportsProviderResult(emptyList(), emptySet()), loadSportsProviders(emptyList(), SPORTS_NOW, SPORTS_NOW + 1))
    }
    /** Checks: cancellation Is Not Swallowed As Provider Failure. */
    @Test(expected = CancellationException::class) fun cancellationIsNotSwallowedAsProviderFailure() = runBlocking<Unit> {
        loadSportsProviders(listOf(provider("canceled") { throw CancellationException() }), SPORTS_NOW, SPORTS_NOW + SportsRepository.DAY)
    }
    /** Checks: favorite And Available Filters Are Independent And Deterministic. */
    @Test fun favoriteAndAvailableFiltersAreIndependentAndDeterministic() {
        val e = SportsMatching.inferred(sportsProgramme(), SPORTS_NOW)!!
        val cards = listOf(SportsEventCard(e, 2), SportsEventCard(e.copy(id = "other", leagueId = "nba", homeTeamId = "other-team"), 0))
        val fav = listOf(SportsFavorite("team", e.homeTeamId!!, e.leagueId, e.homeName!!, 0))
        assertEquals(listOf(cards[0]), filterSportsCards(cards, SportsFilter.FAVORITE_TEAMS, fav, SPORTS_NOW))
        assertEquals(listOf(cards[0]), filterSportsCards(cards, SportsFilter.WITH_STREAMS, emptyList(), SPORTS_NOW))
        assertEquals(cards, filterSportsCards(cards, SportsFilter.UPCOMING, emptyList(), SPORTS_NOW))
        assertTrue(filterSportsCards(cards, SportsFilter.LIVE, emptyList(), SPORTS_NOW).isEmpty())
        assertEquals(listOf(cards[0]), filterSportsCards(cards, SportsFilter.ALL, emptyList(), SPORTS_NOW, league = "nhl"))
    }
    /** Checks: search Escapes Sql Wildcards And Bounds User Input. */
    @Test fun searchEscapesSqlWildcardsAndBoundsUserInput() {
        assertEquals("%a\\%b\\_c\\\\d%", SportsViewModel.pattern("a%b_c\\d"))
        assertEquals(122, SportsViewModel.pattern("x".repeat(1000)).length)
    }
}
