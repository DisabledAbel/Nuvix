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

/** Regression tests for sports repository. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28])
class SportsRepositoryTest {
    private lateinit var db: OpenTvDatabase
    private lateinit var repo: SportsRepository
    /** Creates isolated fixtures before each test. */
    @Before fun setup() = runBlocking {
        db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), OpenTvDatabase::class.java).allowMainThreadQueries().build()
        repo = SportsRepository(db)
        for (id in 1L..3L) {
            db.sources().insert(Source(id = id, name = "Provider $id", kind = SourceKind.M3U, url = "https://example.invalid/$id"))
            db.epgFeeds().insert(EpgFeed(id = id, name = "Guide $id", url = null, lastSyncMillis = SPORTS_NOW))
            db.categories().upsertAll(listOf(Category("sports", id, "Sports", StreamKind.LIVE)))
        }
    }
    /** Releases test resources and restores shared state after each test. */
    @After fun close() { db.close() }
    /** Populates the isolated database with the sports schedule fixtures needed by the test. */
    private suspend fun seed(shift: Long = 0) {
        db.channels().upsertAll(listOf(sportsChannel(1, 1, "one"), sportsChannel(2, 2, "two"), sportsChannel(3, 3, "three")))
        db.programmes().upsertAll(listOf(sportsProgramme(1, 1, "one"), sportsProgramme(2, 2, "two", start = SPORTS_NOW + 60_000 + shift), sportsProgramme(3, 3, "three")))
        repo.reconcile(SPORTS_NOW)
    }
    /** Queries sports event cards using the test's bounded search and visibility filters. */
    private suspend fun cards(pattern: String = "%", limit: Int = 200, excluded: String = "||") =
        db.sports().observeCards(SPORTS_NOW - SportsRepository.DAY, SPORTS_NOW + SportsRepository.HORIZON, pattern, limit, excluded).first()
    /** Returns the inferred event from the seeded sports schedule fixture. */
    private suspend fun game() = cards().first().event
    /** Returns eligible provider stream choices for the synthetic sports event. */
    private suspend fun streams(id: String) = repo.streams(id).first()

    @Test fun automaticRecoveryUsesOnlyCurrentConfirmedVisibleChoices() = runBlocking {
        seed(10 * 60_000)
        val event = game()
        val at = event.startUtcMillis + 15 * 60_000
        suspend fun next(attempted: Set<Long>, visible: (Channel) -> Boolean = { true }) =
            repo.nextPlaybackChannel(event.id, attempted, SportsStreamSort.CONFIDENCE, at, visible)
        // Provider 2 is only likely, so it cannot replace either confirmed broadcast.
        assertEquals(3L, next(setOf(1))?.id)
        assertNull(next(setOf(1, 3)))
        assertNull(next(setOf(1)) { it.id != 3L })
        val suggestion = streams(event.id).first { it.channel.id == 2L }
        repo.decide(event.id, suggestion.programme.key, suggestion.channel.id, true)
        assertEquals(2L, next(setOf(1, 3))?.id)
        db.sources().update(db.sources().byId(2)!!.copy(enabled = false))
        assertNull(next(setOf(1, 3)))
        assertNull(repo.nextPlaybackChannel(event.id, emptySet(), SportsStreamSort.CONFIDENCE,
            event.endUtcMillis) { true })
        assertNull(repo.nextPlaybackChannel(event.id, emptySet(), SportsStreamSort.CONFIDENCE,
            event.startUtcMillis - 1) { true })
    }

    @Test fun automaticRecoveryHonorsPreferredBroadcastAndRechecksRejections() = runBlocking {
        seed()
        val event = game()
        val preferred = streams(event.id).first { it.channel.id == 3L }
        repo.prefer(event.id, preferred)
        val at = event.startUtcMillis + 60_000
        assertEquals(3L, repo.nextPlaybackChannel(event.id, setOf(1), SportsStreamSort.CONFIDENCE, at) { true }?.id)
        repo.decide(event.id, preferred.programme.key, preferred.channel.id, false)
        assertEquals(2L, repo.nextPlaybackChannel(event.id, setOf(1), SportsStreamSort.CONFIDENCE, at) { true }?.id)
        db.sports().upsertEvents(listOf(event.copy(status = "FINAL")))
        assertNull(repo.nextPlaybackChannel(event.id, emptySet(), SportsStreamSort.CONFIDENCE, at) { true })
    }

    /** Checks: one Game Retains All Provider Choices Without Group Key Deduplication. */
    @Test fun oneGameRetainsAllProviderChoicesWithoutGroupKeyDeduplication() = runBlocking {
        seed()
        val cards = cards()
        assertEquals(1, cards.size); assertEquals(3, cards.single().streamCount)
        assertEquals(listOf(1L, 2L, 3L), streams(cards.single().event.id).map { it.channel.sourceId })
    }
    /** Checks: duplicate Guide Entries Do Not Hide Genuinely Different Providers. */
    @Test fun duplicateGuideEntriesDoNotHideGenuinelyDifferentProviders() = runBlocking {
        seed()
        db.programmes().upsertAll(listOf(sportsProgramme(4, 2, "one")))
        repo.reconcile(SPORTS_NOW)
        val all = streams(game().id)
        assertEquals(3, all.size)
        assertEquals(3, orderSportsStreams(all, null, SportsStreamSort.CONFIDENCE).size)
        assertEquals(3, cards().single().streamCount)
    }
    /** Checks: probable Match Is A Suggestion Until Confirmed And Correction Survives Reimport. */
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
    /** Checks: rejection And Undo Preserve Original Programme And Provider Catalog. */
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
    /** Checks: duplicate Feed Evidence Cannot Resurrect A Rejected Provider Choice. */
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
    /** Checks: click Time Validation Rejects Disabled Removed And Unconfirmed Entries Without Connecting. */
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
    /** Checks: reenabled Feed Rebuilds Its Evidence After Checkpoint Cleanup. */
    @Test fun reenabledFeedRebuildsItsEvidenceAfterCheckpointCleanup() = runBlocking {
        seed()
        db.epgFeeds().setEnabled(1, false); repo.reconcile(SPORTS_NOW)
        assertEquals(2, streams(game().id).size)
        db.epgFeeds().setEnabled(1, true); repo.reconcile(SPORTS_NOW)
        assertEquals(3, streams(game().id).size)
    }
    /** Checks: wrong Games Do Not Group And Missing Metadata Does Not Invent A Team. */
    @Test fun wrongGamesDoNotGroupAndMissingMetadataDoesNotInventATeam() = runBlocking {
        seed()
        db.programmes().upsertAll(listOf(sportsProgramme(4, 1, "one", start = SPORTS_NOW + 4 * 60 * 60_000,
            title = "NHL: New York Rangers vs Toronto Maple Leafs"), sportsProgramme(5, 1, "one", start = SPORTS_NOW + 8 * 60 * 60_000,
            title = "Sports tonight", category = "Sports")))
        repo.reconcile(SPORTS_NOW)
        assertEquals(3, cards().size)
        assertNull(cards().last().event.homeName)
    }
    /** Checks: manual Guide Id With Actual Programs Takes Priority Over Fallback Matches. */
    @Test fun manualGuideIdWithActualProgramsTakesPriorityOverFallbackMatches() = runBlocking {
        seed()
        db.channels().upsertAll(listOf(sportsChannel(1, 1, "one").copy(epgOverrideId = "manual")))
        db.programmes().upsertAll(listOf(sportsProgramme(100, 1, "manual", title = "Drama", category = "Drama")))
        repo.reconcile(SPORTS_NOW)
        assertEquals(listOf(2L, 3L), streams(game().id).map { it.channel.id })
        assertEquals(2, cards().single().streamCount)
    }
    /** Checks: guide Reminders Across Providers Resolve One Sports Identity. */
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
    /** Checks: catalog Presence Without Programme Evidence Does Not Create A Match. */
    @Test fun catalogPresenceWithoutProgrammeEvidenceDoesNotCreateAMatch() = runBlocking {
        db.channels().upsertAll(listOf(sportsChannel()))
        repo.reconcile(SPORTS_NOW)
        assertTrue(cards().isEmpty())
    }
    /** Checks: disabled Hidden And Locked Provider Choices Are Excluded. */
    @Test fun disabledHiddenAndLockedProviderChoicesAreExcluded() = runBlocking {
        seed()
        db.sources().update(db.sources().byId(2)!!.copy(enabled = false))
        db.channels().setHidden(3, true)
        assertEquals(listOf(1L), streams(game().id).map { it.channel.id })
        assertEquals(1, cards().single().streamCount)
        assertTrue(cards(excluded = "|1:73706F727473|").isEmpty())
    }
    /** Checks: disabling Feed Immediately Removes Its Streams And Keeps Other Providers. */
    @Test fun disablingFeedImmediatelyRemovesItsStreamsAndKeepsOtherProviders() = runBlocking {
        seed()
        db.epgFeeds().setEnabled(1, false)
        assertEquals(listOf(2L, 3L), streams(game().id).map { it.channel.id })
        repo.reconcile(SPORTS_NOW)
        assertEquals(2, cards().single().streamCount)
    }
    /** Checks: same Content After Refresh Does Not Apply Decision To A Different Game In The Slot. */
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
    /** Checks: explicit Manual Assignment Moves One Provider Entry And Its Card Count. */
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
    /** Checks: favorites Persist Without Account And Reorder Within Their Kind. */
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
    /** Checks: invalid Favorite Ids Do Not Persist. */
    @Test fun invalidFavoriteIdsDoNotPersist() = runBlocking {
        try { repo.toggleFavorite("team", "fake", "nhl", "NYR"); fail() } catch (_: IllegalArgumentException) { }
        try { repo.toggleFavorite("league", "unknown", "unknown", "Unknown"); fail() } catch (_: IllegalArgumentException) { }
        assertTrue(repo.favorites.first().isEmpty())
    }
    /** Checks: repeated And Concurrent Reminder Requests Produce One Game Reminder. */
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
    /** Checks: guide Reminder For Any Matched Provider Is Reused. */
    @Test fun guideReminderForAnyMatchedProviderIsReused() = runBlocking {
        seed()
        val e = game(); val s = streams(e.id)[1]
        val existing = db.reminders().insert(Reminder(channelId = s.channel.id, channelName = "Existing", title = e.title,
            startUtcMillis = s.programme.startUtcMillis, endUtcMillis = s.programme.endUtcMillis))
        assertEquals(existing, repo.remind(e.id, streams(e.id), SPORTS_NOW).id)
        assertEquals(1, db.reminders().observeAll().first().size)
    }
    /** Checks: guide Reconciliation Preserves Verified Timestamp And Accepts Newer Provider Update. */
    @Test fun guideReconciliationPreservesVerifiedTimestampAndAcceptsNewerProviderUpdate() = runBlocking {
        val verified = verifiedEvent().copy(updatedAtMillis = SPORTS_NOW - 60_000)
        db.sports().upsertEvents(listOf(verified))
        seed()
        assertEquals(verified, db.sports().event(verified.id))
        assertEquals(3, streams(verified.id).size)
        val update = verified.copy(status = "LIVE", updatedAtMillis = SPORTS_NOW - 30_000)
        repo.saveProviderEvents(listOf(update)) { _, _ -> }
        assertEquals(update, db.sports().event(verified.id))
    }
    /** Checks: sole Verified Candidate Wins Over Older Inferred Event Without Reducing Confidence. */
    @Test fun soleVerifiedCandidateWinsOverOlderInferredEventWithoutReducingConfidence() = runBlocking {
        seed()
        val inferred = game()
        val verified = verifiedEvent()
        db.sports().upsertEvents(listOf(verified))
        db.sports().clearFeedStates()
        repo.reconcile(SPORTS_NOW)
        assertTrue(streams(inferred.id).isEmpty())
        val matched = streams(verified.id)
        assertEquals(3, matched.size)
        assertTrue(matched.all { it.confidence == MatchConfidence.CONFIRMED })
    }
    /** Checks: multiple Verified Candidates Remain Possible Instead Of Choosing One. */
    @Test fun multipleVerifiedCandidatesRemainPossibleInsteadOfChoosingOne() = runBlocking {
        db.sports().upsertEvents(listOf(verifiedEvent(sourceId = "first"), verifiedEvent(sourceId = "second")))
        seed()
        val inferred = cards().filter { !it.event.verified }
        val suggestions = inferred.flatMap { streams(it.event.id) }
        assertEquals(3, suggestions.size)
        assertTrue(suggestions.all { it.confidence == MatchConfidence.POSSIBLE })
        assertTrue(streams(verifiedEvent(sourceId = "first").id).isEmpty())
        assertTrue(streams(verifiedEvent(sourceId = "second").id).isEmpty())
    }
    /** Checks: provider Metadata Updates Preserve Fired State Until The Start Moves. */
    @Test fun providerMetadataUpdatesPreserveFiredStateUntilTheStartMoves() = runBlocking {
        val event = verifiedEvent()
        db.sports().upsertEvents(listOf(event))
        val id = db.reminders().insert(Reminder(channelId = 1, channelName = "Existing", title = event.title,
            startUtcMillis = event.startUtcMillis, endUtcMillis = event.endUtcMillis, sportsEventId = event.id, fired = true))
        val metadataUpdate = event.copy(title = "Updated title", endUtcMillis = event.endUtcMillis + 60_000)
        repo.saveProviderEvents(listOf(metadataUpdate)) { reminder, cancel ->
            assertTrue(reminder.fired); assertFalse(cancel)
        }
        assertTrue(db.reminders().byId(id)!!.fired)
        val moved = metadataUpdate.copy(startUtcMillis = event.startUtcMillis + 60_000)
        repo.saveProviderEvents(listOf(moved)) { reminder, cancel ->
            assertFalse(reminder.fired); assertFalse(cancel)
        }
        assertFalse(db.reminders().byId(id)!!.fired)
        for (status in listOf("CANCELED", "POSTPONED", "FINAL")) {
            repo.saveProviderEvents(listOf(moved.copy(status = status))) { reminder, cancel ->
                assertTrue(reminder.fired); assertTrue(cancel)
            }
            assertTrue(db.reminders().byId(id)!!.fired)
        }
    }
    /** Checks: rescheduled Verified Event Rearms The Same Reminder And Cancellation Cancels It. */
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
    /** Checks: offline Restart And Cache Clearing Retain User Intent And No Playback Connections. */
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
    /** Checks: database Favorite And Browse Filters Apply Before Result Limit. */
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
    /** Checks: stream Pagination Includes Every Provider Entry After Deduplicating Evidence. */
    @Test fun streamPaginationIncludesEveryProviderEntryAfterDeduplicatingEvidence() = runBlocking {
        seed()
        db.channels().upsertAll((1000L..1200L).map { sportsChannel(it, 1, "one") })
        val e = game()
        assertEquals(204, cards().single().streamCount)
        val first = repo.streams(e.id, offset = 0).first()
        val second = repo.streams(e.id, offset = 200).first()
        assertEquals(200, first.size); assertEquals(4, second.size)
        assertEquals(204, (first + second).map { it.channel.id }.distinct().size)
        val onlyVisible = second.last().channel.id
        assertEquals(onlyVisible, repo.nextPlaybackChannel(e.id, emptySet(), SportsStreamSort.CONFIDENCE,
            e.startUtcMillis + 60_000) { it.id == onlyVisible }?.id)
    }
    /** Checks: rescheduling Drops Old Broadcast Evidence And Rejects Stale Playback Choices. */
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
    /** Checks: program Projection Bounds Memory Without Rewriting Raw Epg. */
    @Test fun programProjectionBoundsMemoryWithoutRewritingRawEpg() = runBlocking {
        val p = sportsProgramme().copy(description = "x".repeat(10000))
        db.programmes().upsertAll(listOf(p))
        assertEquals(2000, db.sports().programmeBatch(1, 0, SPORTS_NOW, SPORTS_NOW + SportsRepository.HORIZON).single().description!!.length)
        assertEquals(10000, db.programmes().upcoming(p.epgChannelId, SPORTS_NOW, 10).single().description!!.length)
    }
    /** Checks: older Provider Payload Cannot Revert A Cached Reschedule. */
    @Test fun olderProviderPayloadCannotRevertACachedReschedule() = runBlocking {
        val initial = verifiedEvent()
        val newer = initial.copy(startUtcMillis = initial.startUtcMillis + SportsRepository.DAY,
            endUtcMillis = initial.endUtcMillis + SportsRepository.DAY, updatedAtMillis = SPORTS_NOW + 1000)
        repo.saveProviderEvents(listOf(newer)) { _, _ -> }
        repo.saveProviderEvents(listOf(initial)) { _, _ -> fail("Old update changed reminder") }
        assertEquals(newer, db.sports().event(initial.id))
    }
    /** Checks: search By Channel And Team Is Bounded And Read Only. */
    @Test fun searchByChannelAndTeamIsBoundedAndReadOnly() = runBlocking {
        seed()
        assertEquals(1, cards("%Rangers%").size)
        assertEquals(1, cards("%Sports channel 2%").size)
        assertTrue(cards("%Missing Team%").isEmpty())
        assertEquals(1, cards(limit = 1).size)
    }
    /** Checks: unchanged Feeds Keep Checkpoint And Stale Source Update Time. */
    @Test fun unchangedFeedsKeepCheckpointAndStaleSourceUpdateTime() = runBlocking {
        seed()
        val checkpoint = db.sports().feedState(1)
        repo.reconcile(SPORTS_NOW + 60_000)
        assertEquals(checkpoint, db.sports().feedState(1))
        assertEquals(SPORTS_NOW, game().updatedAtMillis)
        val future = SPORTS_NOW + SportsEvent.CACHE_FRESH_MILLIS + 1
        assertFalse(game().isFresh(future))
    }
    @Test fun currentConflictEvidenceReusesConfirmedPlayableBroadcasts() = runBlocking {
        seed()
        val eventIds = db.sports().currentEventIds(listOf("one"), SPORTS_NOW + 120_000)
        assertEquals(listOf(game().id), eventIds)
        val options = streams(eventIds.single()).filter { it.available }
        assertEquals(setOf(1L, 2L, 3L), options.map { it.channel.id }.toSet())
        for (stream in options) assertNotNull(repo.playable(eventIds.single(), stream))
        assertTrue(db.sports().currentEventIds(listOf("one"), SPORTS_NOW).isEmpty())
    }

}
