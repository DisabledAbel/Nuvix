/* Copyright (C) 2026 Nuvix Contributors. GPL-3.0-or-later. */
package app.opentv.sports

import android.app.Application
import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewModelScope
import androidx.room.Room
import app.opentv.core.AppSettings
import app.opentv.core.ServiceLocator
import app.opentv.data.db.OpenTvDatabase
import app.opentv.data.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Actual VM/Room/navigation callbacks, without replacing repositories or using any real stream. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28])
@OptIn(ExperimentalCoroutinesApi::class)
class SportsViewModelTest {
    private lateinit var app: Application
    private lateinit var db: OpenTvDatabase
    private lateinit var graph: ServiceLocator.Graph
    private lateinit var settings: AppSettings
    private val models = mutableListOf<SportsViewModel>()
    private val store = ViewModelStore()
    private var savedGraph: Any? = null
    private val now = System.currentTimeMillis()
    @Before fun setup(): Unit = runBlocking {
        app = RuntimeEnvironment.getApplication()
        listOf("opentv_settings", "nuvix_private_settings", "nuvix_sports").forEach {
            app.getSharedPreferences(it, Context.MODE_PRIVATE).edit().clear().commit()
        }
        Dispatchers.setMain(UnconfinedTestDispatcher())
        db = Room.inMemoryDatabaseBuilder(app, OpenTvDatabase::class.java).allowMainThreadQueries().build()
        settings = AppSettings::class.java.getDeclaredConstructor(Context::class.java).apply { isAccessible = true }.newInstance(app)
        graph = ServiceLocator.Graph(app)
        ServiceLocator.Graph::class.java.getDeclaredField("database\$delegate").apply { isAccessible = true }.set(graph, lazyOf(db))
        ServiceLocator.Graph::class.java.getDeclaredField("settings\$delegate").apply { isAccessible = true }.set(graph, lazyOf(settings))
        ServiceLocator::class.java.getDeclaredField("instance").apply { isAccessible = true; savedGraph = get(null); set(null, graph) }
        db.sources().insert(Source(id = 1, name = "Synthetic provider", kind = SourceKind.M3U, url = "https://example.invalid"))
        db.epgFeeds().insert(EpgFeed(id = 1, name = "Synthetic guide", url = null, lastSyncMillis = now))
        db.categories().upsertAll(listOf(Category("sports", 1, "Sports", StreamKind.LIVE)))
        db.channels().upsertAll(listOf(sportsChannel()))
    }
    @After fun close(): Unit = runBlocking {
        store.clear()
        models.forEach { it.viewModelScope.coroutineContext[Job]?.join() }
        db.close()
        ServiceLocator::class.java.getDeclaredField("instance").apply { isAccessible = true; set(null, savedGraph) }
        Dispatchers.resetMain()
    }
    private fun model(saved: SavedStateHandle = SavedStateHandle()) = SportsViewModel(app, saved).also {
        store.put("sports-${models.size}", it); models += it
    }
    private suspend fun seed() { db.programmes().upsertAll(listOf(sportsProgramme(start = now + 3_600_000))) }
    private suspend fun await(vm: SportsViewModel, test: (SportsHubState) -> Boolean) = withTimeout(10_000) { vm.state.first(test) }

    @Test fun existingCategoryChannelFallbackWorksWithoutAnyEvents(): Unit = runBlocking {
        val vm = model()
        val state = await(vm) { it.channels.size == 1 && !it.loading }
        assertTrue(state.cards.isEmpty())
        assertEquals(1L, state.channels.single().id)
        assertEquals(0, graph.httpClient.dispatcher.runningCallsCount())
    }
    @Test fun gameDetailsAndSavedIdentityRemainSelectedAfterExplicitPlaybackCallback(): Unit = runBlocking {
        seed()
        val saved = SavedStateHandle()
        val vm = model(saved)
        val event = await(vm) { it.cards.size == 1 }.cards.single().event
        vm.select(event.id)
        val detail = await(vm) { it.selectedEvent?.id == event.id && it.streams.size == 1 }
        assertEquals(0, graph.httpClient.dispatcher.runningCallsCount())
        var plays = 0
        vm.play(detail.streams.single()) { plays++ }.join()
        assertEquals(1, plays)
        assertEquals(event.id, vm.selectedId.value)
        assertEquals(event.id, saved.get<String>("sports_event"))
        val recreated = model(saved)
        await(recreated) { it.selectedEvent?.id == event.id && it.streams.size == 1 }
        assertEquals(1, plays)
        vm.select(null)
        await(vm) { it.selectedEvent == null && it.streams.isEmpty() }
    }
    @Test fun lockedCategoriesNeverBecomePlayableAndUnlockUpdatesExistingHub(): Unit = runBlocking {
        seed()
        settings.setHiddenCategories(setOf("sports"))
        val vm = model()
        await(vm) { !it.loading }
        assertTrue(vm.state.value.cards.isEmpty())
        assertTrue(vm.state.value.channels.isEmpty())
        settings.setHiddenUnlocked(true)
        val state = await(vm) { it.cards.size == 1 && it.channels.size == 1 }
        vm.select(state.cards.single().event.id)
        await(vm) { it.streams.size == 1 }
        settings.setHiddenUnlocked(false)
        await(vm) { it.cards.isEmpty() && it.channels.isEmpty() && it.streams.isEmpty() && it.selectedEvent == null }
    }
    @Test fun multiViewActionsRevalidateOneSelectedProviderBroadcastBeforeDispatch(): Unit = runBlocking {
        seed()
        val vm = model()
        val event = await(vm) { it.cards.size == 1 }.cards.single().event
        vm.select(event.id)
        val stream = await(vm) { it.streams.size == 1 }.streams.single()
        val choices = mutableListOf<Pair<SportsPlaybackAction, Channel>>()
        assertEquals(0, graph.httpClient.dispatcher.runningCallsCount())
        for (requested in SportsPlaybackAction.entries) {
            vm.chooseBroadcast(stream, requested) { action, channel -> choices += action to channel }.join()
        }
        assertEquals(SportsPlaybackAction.entries.toList(), choices.map { it.first })
        assertEquals(listOf(1L, 1L, 1L), choices.map { it.second.id })
        val source = db.sources().byId(1)!!
        db.sources().update(source.copy(enabled = false))
        await(vm) { it.streams.isEmpty() }
        for (requested in SportsPlaybackAction.entries) {
            vm.chooseBroadcast(stream, requested) { action, channel -> choices += action to channel }.join()
        }
        assertEquals(3, choices.size)
        assertEquals(0, graph.httpClient.dispatcher.runningCallsCount())
    }
    @Test fun manualAssociationCandidatesIgnoreTheCurrentSearchButRespectVisibility(): Unit = runBlocking {
        seed()
        db.channels().upsertAll(listOf(sportsChannel(2, 1, "sports-2")))
        db.programmes().upsertAll(listOf(sportsProgramme(2, 1, "sports-2", start = now + 3_600_000,
            title = "NHL: Boston Bruins vs Toronto Maple Leafs")))
        val vm = model()
        await(vm) { it.cards.size == 2 }
        vm.search("New York")
        val e = await(vm) { it.cards.size == 1 }.cards.single().event
        vm.select(e.id)
        val state = await(vm) { it.relatedEvents.any { event -> event.title.contains("Toronto") } }
        assertEquals(1, state.cards.size)
    }
    @Test fun searchAndFavoriteFiltersUpdateWithoutCreatingPlaybackOrNetworkRequests(): Unit = runBlocking {
        seed()
        val vm = model()
        await(vm) { it.cards.size == 1 }
        vm.filter(SportsFilter.FAVORITE_LEAGUES)
        await(vm) { it.cards.isEmpty() }
        vm.favorite("league", "nhl", "nhl", "NHL").join()
        await(vm) { it.cards.size == 1 && it.favorites.size == 1 }
        vm.search("Unmatched query")
        await(vm) { it.cards.isEmpty() }
        vm.search("National Hockey League")
        await(vm) { it.cards.size == 1 }
        assertNull(vm.selectedId.value)
        assertEquals(0, graph.httpClient.dispatcher.runningCallsCount())
    }
    @Test fun messagesAreConsumedAndClearedWhenTheViewChanges(): Unit = runBlocking {
        val vm = model()
        await(vm) { !it.loading }
        vm.notify("Notice")
        assertEquals("Notice", vm.state.value.message)
        vm.consumeMessage()
        assertNull(vm.state.value.message)
        val changes = listOf<() -> Unit>({ vm.search("NHL") }, { vm.filter(SportsFilter.UPCOMING) },
            { vm.select("another-event") }, { vm.browse("nhl", null, null) })
        for (change in changes) {
            vm.notify("Notice")
            change()
            assertNull(vm.state.value.message)
        }
        vm.refresh().join()
        assertEquals("Local sports schedules refreshed", vm.state.value.message)
        vm.favorite("invalid", "invalid", "", "Invalid").join()
        assertEquals("Sports action could not be completed. Cached schedules remain available.", vm.state.value.message)
    }
    @Test fun unlinkedGuideReminderIsShownAndCanceledByItsChannelAndProgrammeStart(): Unit = runBlocking {
        seed()
        val vm = model()
        val event = await(vm) { it.cards.size == 1 }.cards.single().event
        vm.select(event.id)
        val stream = await(vm) { it.streams.size == 1 }.streams.single()
        val guide = Reminder(channelId = stream.channel.id, channelName = "Guide reminder", title = event.title,
            startUtcMillis = stream.programme.startUtcMillis, endUtcMillis = stream.programme.endUtcMillis)
        val id = db.reminders().insert(guide)
        val reminders = db.reminders().observeAll().first()
        val resolved = sportsReminderForEvent(reminders, event.id, vm.state.value.streams)
        assertEquals(id, resolved?.id)
        assertEquals(false, resolved?.fired)
        assertNull(resolved?.sportsEventId)
        assertNull(sportsReminderForEvent(listOf(guide.copy(channelId = 99)), event.id, listOf(stream)))
        assertNull(sportsReminderForEvent(listOf(guide.copy(startUtcMillis = guide.startUtcMillis + 1)), event.id, listOf(stream)))
        assertNull(sportsReminderForEvent(reminders, event.id, listOf(stream.copy(confidence = MatchConfidence.POSSIBLE))))
        vm.cancelReminder(event.id).join()
        assertNull(db.reminders().byId(id))
        vm.remind(event.id).join()
        assertEquals("Game reminder set", vm.state.value.message)
        val linked = db.reminders().observeAll().first().single()
        assertEquals(event.id, linked.sportsEventId)
        assertEquals(linked, sportsReminderForEvent(listOf(linked), event.id, emptyList()))
    }
}
