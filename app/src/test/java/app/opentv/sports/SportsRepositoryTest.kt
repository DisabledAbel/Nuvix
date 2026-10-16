/* Copyright (C) 2026 Nuvix Contributors. GPL-3.0-or-later. */
package app.opentv.sports

import android.app.Application
import androidx.room.Room
import app.opentv.data.db.OpenTvDatabase
import app.opentv.data.model.*
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28])
class SportsRepositoryTest {
    private lateinit var db: OpenTvDatabase
    private lateinit var repo: SportsRepository
    @Before fun setup() = runBlocking {
        db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), OpenTvDatabase::class.java).allowMainThreadQueries().build()
        repo = SportsRepository(db)
        for (id in 1L..3L) {
            db.sources().insert(Source(id = id, name = "Provider $id", kind = SourceKind.M3U, url = "https://example.invalid/$id"))
            db.epgFeeds().insert(EpgFeed(id = id, name = "Guide $id", url = null, lastSyncMillis = SPORTS_NOW))
            db.categories().upsertAll(listOf(Category("sports", id, "Sports", StreamKind.LIVE)))
        }
    }
    @After fun close() { db.close() }
    private suspend fun seed(shift: Long = 0) {
        db.channels().upsertAll(listOf(sportsChannel(1, 1, "one"), sportsChannel(2, 2, "two"), sportsChannel(3, 3, "three")))
        db.programmes().upsertAll(listOf(sportsProgramme(1, 1, "one"), sportsProgramme(2, 2, "two", start = SPORTS_NOW + 60_000 + shift), sportsProgramme(3, 3, "three")))
        repo.reconcile(SPORTS_NOW)
    }
    private suspend fun cards(pattern: String = "%", limit: Int = 200, excluded: String = "||") =
        db.sports().observeCards(SPORTS_NOW - SportsRepository.DAY, SPORTS_NOW + SportsRepository.HORIZON, pattern, limit, excluded).first()
    private suspend fun game() = cards().first().event
    private suspend fun streams(id: String) = repo.streams(id).first()

    @Test fun oneGameRetainsAllProviderChoicesWithoutGroupKeyDeduplication() = runBlocking {
        seed()
        val cards = cards()
        assertEquals(1, cards.size); assertEquals(3, cards.single().streamCount)
        assertEquals(listOf(1L, 2L, 3L), streams(cards.single().event.id).map { it.channel.sourceId })
    }
    @Test fun duplicateGuideEntriesDoNotHideGenuinelyDifferentProviders() = runBlocking {
        seed()
        db.programmes().upsertAll(listOf(sportsProgramme(4, 2, "one")))
        repo.reconcile(SPORTS_NOW)
        val all = streams(game().id)
        assertEquals(3, all.size)
        assertEquals(3, orderSportsStreams(all, null, SportsStreamSort.CONFIDENCE).size)
        assertEquals(3, cards().single().streamCount)
    }
    @Test fun probableMatchIsASuggestionUntilConfirmedAndCorrectionSurvivesReimport() = runBlocking {
        seed(10 * 60_000)
        val event = game()
        val suggestion = streams(event.id).first { it.channel.id == 2L }
        assertEquals(MatchConfidence.LIKELY, suggestion.confidence)
        assertEquals(2, cards().single().streamCount)
        repo.decide(event.id, suggestion.programme.key, suggestion.channel.id, true)
        assertEquals(3, cards().single().streamCount)
        db.programmes().deleteForFeed(2)
        db.programmes().upsertAll(listOf(sportsProgramme(99, 2, "two", start = SPORTS_NOW + 60_000 + 10 * 60_000)))
        repo.reconcile(SPORTS_NOW)
        assertTrue(streams(event.id).first { it.channel.id == 2L }.userConfirmed)
    }
    @Test fun rejectionAndUndoPreserveOriginalProgrammeAndProviderCatalog() = runBlocking {
        seed()
        val e = game(); val s = streams(e.id).first()
        repo.decide(e.id, s.programme.key, s.channel.id, false)
        assertTrue(streams(e.id).first { it.channel.id == s.channel.id }.rejected)
        assertEquals(2, cards().single().streamCount)
        assertNotNull(db.channels().byId(s.channel.id))
        assertEquals(s.programme.title, db.sports().programme(s.programme.key)!!.title)
        db.sports().undoDecision(s.programme.key, s.channel.sourceId, s.channel.streamId)
        assertEquals(3, cards().single().streamCount)
    }
    @Test fun duplicateFeedEvidenceCannotResurrectARejectedProviderChoice() = runBlocking {
        seed()
        db.programmes().upsertAll(listOf(sportsProgramme(99, 2, "one")))
        repo.reconcile(SPORTS_NOW)
        val e = game(); val stream = streams(e.id).first { it.channel.id == 1L }
        repo.decide(e.id, stream.programme.key, stream.channel.id, false)
        assertEquals(2, cards().single().streamCount)
        assertTrue(streams(e.id).first { it.channel.id == 1L }.rejected)
        db.sports().undoEventDecision(e.id, stream.channel.sourceId, stream.channel.streamId)
        assertEquals(3, cards().single().streamCount)
    }
    @Test fun clickTimeValidationRejectsDisabledRemovedAndUnconfirmedEntriesWithoutConnecting() = runBlocking {
        seed(10 * 60_000)
        val e = game(); val all = streams(e.id)
        assertNotNull(repo.playable(e.id, all.first { it.channel.id == 1L }))
        assertNull(repo.playable(e.id, all.first { it.channel.id == 2L }))
        db.sources().update(db.sources().byId(1)!!.copy(enabled = false))
        assertNull(repo.playable(e.id, all.first { it.channel.id == 1L }))
        val other = all.first { it.channel.id == 3L }
        repo.decide(e.id, other.programme.key, other.channel.id, false)
        assertNull(repo.playable(e.id, other))
    }
    @Test fun reenabledFeedRebuildsItsEvidenceAfterCheckpointCleanup() = runBlocking {
        seed()
        db.epgFeeds().setEnabled(1, false); repo.reconcile(SPORTS_NOW)
        assertEquals(2, streams(game().id).size)
        db.epgFeeds().setEnabled(1, true); repo.reconcile(SPORTS_NOW)
        assertEquals(3, streams(game().id).size)
    }
    @Test fun wrongGamesDoNotGroupAndMissingMetadataDoesNotInventATeam() = runBlocking {
        seed()
        db.programmes().upsertAll(listOf(sportsProgramme(4, 1, "one", start = SPORTS_NOW + 4 * 60 * 60_000,
            title = "NHL: New York Rangers vs Toronto Maple Leafs"), sportsProgramme(5, 1, "one", start = SPORTS_NOW + 8 * 60 * 60_000,
            title = "Sports tonight", category = "Sports")))
        repo.reconcile(SPORTS_NOW)
        assertEquals(3, cards().size)
        assertNull(cards().last().event.homeName)
    }
    @Test fun manualGuideIdWithActualProgramsTakesPriorityOverFallbackMatches() = runBlocking {
        seed()
        db.channels().upsertAll(listOf(sportsChannel(1, 1, "one").copy(epgOverrideId = "manual")))
        db.programmes().upsertAll(listOf(sportsProgramme(100, 1, "manual", title = "Drama", category = "Drama")))
        repo.reconcile(SPORTS_NOW)
        assertEquals(listOf(2L, 3L), streams(game().id).map { it.channel.id })
        assertEquals(2, cards().single().streamCount)
    }
    @Test fun guideRemindersAcrossProvidersResolveOneSportsIdentity() = runBlocking {
        seed()
        val e = game(); val all = streams(e.id)
        val reminders = app.opentv.data.repo.ReminderRepository(db.reminders())
        val first = reminders.insert(Reminder(channelId = all[0].channel.id, channelName = "First", title = e.title,
            startUtcMillis = e.startUtcMillis, endUtcMillis = e.endUtcMillis))
        val second = reminders.insert(Reminder(channelId = all[1].channel.id, channelName = "Second", title = e.title,
            startUtcMillis = e.startUtcMillis, endUtcMillis = e.endUtcMillis))
        assertEquals(first, second)
        assertEquals(first, reminders.forProgramme(all[2].channel.id, e.startUtcMillis)!!.id)
        assertEquals(first, repo.remind(e.id, all, SPORTS_NOW).id)
    }
    @Test fun catalogPresenceWithoutProgrammeEvidenceDoesNotCreateAMatch() = runBlocking {
        db.channels().upsertAll(listOf(sportsChannel()))
        repo.reconcile(SPORTS_NOW)
        assertTrue(cards().isEmpty())
    }
    @Test fun disabledHiddenAndLockedProviderChoicesAreExcluded() = runBlocking {
        seed()
        db.sources().update(db.sources().byId(2)!!.copy(enabled = false))
        db.channels().setHidden(3, true)
        assertEquals(listOf(1L), streams(game().id).map { it.channel.id })
        assertEquals(1, cards().single().streamCount)
        assertTrue(cards(excluded = "|1:73706F727473|").isEmpty())
    }
    @Test fun disablingFeedImmediatelyRemovesItsStreamsAndKeepsOtherProviders() = runBlocking {
        seed()
        db.epgFeeds().setEnabled(1, false)
        assertEquals(listOf(2L, 3L), streams(game().id).map { it.channel.id })
        repo.reconcile(SPORTS_NOW)
        assertEquals(2, cards().single().streamCount)
    }
    @Test fun sameContentAfterRefreshDoesNotApplyDecisionToADifferentGameInTheSlot() = runBlocking {
        seed()
        val e = game(); val s = streams(e.id).first()
        repo.decide(e.id, s.programme.key, s.channel.id, false)
        db.programmes().deleteForFeed(1)
        db.programmes().upsertAll(listOf(sportsProgramme(99, 1, "one", title = "NHL: Toronto Maple Leafs vs Montreal Canadiens")))
        repo.reconcile(SPORTS_NOW)
        val different = cards().first { it.event.title.contains("Toronto") }
        assertEquals(1, different.streamCount)
        assertFalse(streams(different.event.id).single().rejected)
    }
    @Test fun explicitManualAssignmentMovesOneProviderEntryAndItsCardCount() = runBlocking {
        seed()
        val original = game()
        val manual = original.copy(id = "manual-event", homeName = null, awayName = null)
        db.sports().upsertEvents(listOf(manual))
        val stream = streams(original.id).first()
        repo.decide(manual.id, stream.programme.key, stream.channel.id, true)
        assertEquals(2, cards().first { it.event.id == original.id }.streamCount)
        assertEquals(1, cards().first { it.event.id == manual.id }.streamCount)
        assertTrue(streams(manual.id).single().userConfirmed)
        assertEquals(2, streams(original.id).size)
    }
    @Test fun favoritesPersistWithoutAccountAndReorderWithinTheirKind() = runBlocking {
        val a = SportsNames.teamId("nhl", "NYR"); val b = SportsNames.teamId("nhl", "Bruins")
        repo.toggleFavorite("team", a, "nhl", "NYR"); repo.toggleFavorite("team", b, "nhl", "Bruins")
        repo.toggleFavorite("league", "nhl", "nhl", "NHL"); repo.toggleFavorite("league", "nba", "nba", "NBA")
        repo.moveFavorite("team", b, -1); repo.moveFavorite("league", "nba", -1)
        assertEquals(listOf(b, a), db.sports().favorites("team").map { it.id })
        assertEquals(listOf("nba", "nhl"), db.sports().favorites("league").map { it.id })
        assertEquals(4, SportsRepository(db).favorites.first().size)
        repo.toggleFavorite("team", a, "nhl", "NYR")
        assertEquals(listOf(b), db.sports().favorites("team").map { it.id })
    }
    @Test fun invalidFavoriteIdsDoNotPersist() = runBlocking {
        try { repo.toggleFavorite("team", "fake", "nhl", "NYR"); fail() } catch (_: IllegalArgumentException) { }
        try { repo.toggleFavorite("league", "unknown", "unknown", "Unknown"); fail() } catch (_: IllegalArgumentException) { }
        assertTrue(repo.favorites.first().isEmpty())
    }
    @Test fun repeatedAndConcurrentReminderRequestsProduceOneGameReminder() = runBlocking {
        seed()
        val e = game(); val available = streams(e.id)
        val ids = coroutineScope {
            listOf(async { repo.remind(e.id, available, SPORTS_NOW).id }, async { repo.remind(e.id, available.reversed(), SPORTS_NOW).id }).map { it.await() }
        }
        assertEquals(1, ids.distinct().size)
        assertEquals(1, db.reminders().observeAll().first().size)
        assertFalse(db.reminders().forSportsEvent(e.id)!!.autoTune)
    }
    @Test fun guideReminderForAnyMatchedProviderIsReused() = runBlocking {
        seed()
        val e = game(); val s = streams(e.id)[1]
        val existing = db.reminders().insert(Reminder(channelId = s.channel.id, channelName = "Existing", title = e.title,
            startUtcMillis = s.programme.startUtcMillis, endUtcMillis = s.programme.endUtcMillis))
        assertEquals(existing, repo.remind(e.id, streams(e.id), SPORTS_NOW).id)
        assertEquals(1, db.reminders().observeAll().first().size)
    }
    @Test fun rescheduledVerifiedEventRearmsTheSameReminderAndCancellationCancelsIt() = runBlocking {
        seed()
        val event = verifiedEvent()
        db.sports().upsertEvents(listOf(event))
        val stream = streams(game().id).first()
        repo.decide(event.id, stream.programme.key, stream.channel.id, true)
        val reminder = repo.remind(event.id, streams(event.id), SPORTS_NOW)
        var changed: Pair<Long, Boolean>? = null
        val later = event.copy(startUtcMillis = event.startUtcMillis + SportsRepository.DAY, endUtcMillis = event.endUtcMillis + SportsRepository.DAY)
        repo.saveProviderEvents(listOf(later)) { r, cancel -> changed = r.id to cancel }
        assertEquals(reminder.id to false, changed)
        assertEquals(later.startUtcMillis, db.reminders().byId(reminder.id)!!.startUtcMillis)
        repo.saveProviderEvents(listOf(later.copy(status = "CANCELED"))) { r, cancel -> changed = r.id to cancel }
        assertEquals(reminder.id to true, changed)
        assertTrue(db.reminders().byId(reminder.id)!!.fired)
    }
    @Test fun offlineRestartAndCacheClearingRetainUserIntentAndNoPlaybackConnections() = runBlocking {
        seed()
        val e = game(); val s = streams(e.id).first()
        repo.toggleFavorite("league", "nhl", "nhl", "NHL")
        repo.decide(e.id, s.programme.key, s.channel.id, true); repo.prefer(e.id, s)
        val reloaded = SportsRepository(db)
        reloaded.reconcile(SPORTS_NOW)
        assertEquals(3, streams(e.id).size)
        reloaded.clearCache(); reloaded.reconcile(SPORTS_NOW)
        assertEquals(1, reloaded.favorites.first().size)
        assertTrue(streams(e.id).first { it.channel.id == s.channel.id }.userConfirmed)
        assertEquals(s.channel.streamId, db.sports().observePreferred(e.id).first()!!.streamId)
    }
    @Test fun databaseFavoriteAndBrowseFiltersApplyBeforeResultLimit() = runBlocking {
        val events = (0..250).map { index ->
            val league = if (index == 250) "nba" else "nhl"
            val base = verifiedEvent(sourceId = "game-$index")
            base.copy(leagueId = league, sportId = if (league == "nba") "basketball" else "hockey",
                homeName = "Fixture Home $index", awayName = "Fixture Away $index",
                homeTeamId = SportsNames.teamId(league, "Fixture Home $index"), awayTeamId = SportsNames.teamId(league, "Fixture Away $index"),
                startUtcMillis = SPORTS_NOW + 60_000 + index * 60_000, endUtcMillis = SPORTS_NOW + 12 * 60 * 60_000)
        }
        db.sports().upsertEvents(events)
        repo.toggleFavorite("league", "nba", "nba", "NBA")
        val favorites = db.sports().observeCards(SPORTS_NOW, SPORTS_NOW + SportsRepository.HORIZON, "%", 1,
            filter = "FAVORITE_LEAGUES", now = SPORTS_NOW).first()
        assertEquals(events.last().id, favorites.single().event.id)
        val team = db.sports().observeCards(SPORTS_NOW, SPORTS_NOW + SportsRepository.HORIZON, "%", 1,
            team = events.last().homeTeamId).first()
        assertEquals(events.last().id, team.single().event.id)
    }
    @Test fun streamPaginationIncludesEveryProviderEntryAfterDeduplicatingEvidence() = runBlocking {
        seed()
        db.channels().upsertAll((1000L..1200L).map { sportsChannel(it, 1, "one") })
        val e = game()
        assertEquals(204, cards().single().streamCount)
        val first = repo.streams(e.id, offset = 0).first()
        val second = repo.streams(e.id, offset = 200).first()
        assertEquals(200, first.size); assertEquals(4, second.size)
        assertEquals(204, (first + second).map { it.channel.id }.distinct().size)
    }
    @Test fun reschedulingDropsOldBroadcastEvidenceAndRejectsStalePlaybackChoices() = runBlocking {
        seed()
        val original = game(); val stream = streams(original.id).first()
        val verified = verifiedEvent()
        db.sports().upsertEvents(listOf(verified))
        repo.decide(verified.id, stream.programme.key, stream.channel.id, true)
        val previous = streams(verified.id).single()
        val rescheduled = verified.copy(startUtcMillis = verified.startUtcMillis + SportsRepository.DAY,
            endUtcMillis = verified.endUtcMillis + SportsRepository.DAY, updatedAtMillis = SPORTS_NOW + 1000)
        repo.saveProviderEvents(listOf(rescheduled)) { _, _ -> }
        assertTrue(streams(verified.id).isEmpty())
        assertNull(repo.playable(verified.id, previous))
        assertEquals(0, cards().first { it.event.id == verified.id }.streamCount)
    }
    @Test fun programProjectionBoundsMemoryWithoutRewritingRawEpg() = runBlocking {
        val p = sportsProgramme().copy(description = "x".repeat(10000))
        db.programmes().upsertAll(listOf(p))
        assertEquals(2000, db.sports().programmeBatch(1, 0, SPORTS_NOW, SPORTS_NOW + SportsRepository.HORIZON).single().description!!.length)
        assertEquals(10000, db.programmes().upcoming(p.epgChannelId, SPORTS_NOW, 10).single().description!!.length)
    }
    @Test fun olderProviderPayloadCannotRevertACachedReschedule() = runBlocking {
        val initial = verifiedEvent()
        val newer = initial.copy(startUtcMillis = initial.startUtcMillis + SportsRepository.DAY,
            endUtcMillis = initial.endUtcMillis + SportsRepository.DAY, updatedAtMillis = SPORTS_NOW + 1000)
        repo.saveProviderEvents(listOf(newer)) { _, _ -> }
        repo.saveProviderEvents(listOf(initial)) { _, _ -> fail("Old update changed reminder") }
        assertEquals(newer, db.sports().event(initial.id))
    }
    @Test fun searchByChannelAndTeamIsBoundedAndReadOnly() = runBlocking {
        seed()
        assertEquals(1, cards("%Rangers%").size)
        assertEquals(1, cards("%Sports channel 2%").size)
        assertTrue(cards("%Missing Team%").isEmpty())
        assertEquals(1, cards(limit = 1).size)
    }
    @Test fun unchangedFeedsKeepCheckpointAndStaleSourceUpdateTime() = runBlocking {
        seed()
        val checkpoint = db.sports().feedState(1)
        repo.reconcile(SPORTS_NOW + 60_000)
        assertEquals(checkpoint, db.sports().feedState(1))
        assertEquals(SPORTS_NOW, game().updatedAtMillis)
        val future = SPORTS_NOW + SportsEvent.CACHE_FRESH_MILLIS + 1
        assertFalse(game().isFresh(future))
    }
}
