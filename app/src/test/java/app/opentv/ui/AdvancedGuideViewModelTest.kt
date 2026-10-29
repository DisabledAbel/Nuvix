/* Nuvix modifications Copyright (C) 2026 Nuvix Contributors. GPL-3.0-or-later. */
package app.opentv.ui

import android.app.Application
import app.opentv.channels.ProviderGroupKey
import android.content.Context
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewModelScope
import androidx.room.Room
import app.opentv.core.AppSettings
import app.opentv.core.GuideChannelFilter
import app.opentv.core.GuideChannelSort
import app.opentv.core.RecentChannels
import app.opentv.core.ServiceLocator
import app.opentv.data.db.OpenTvDatabase
import app.opentv.data.model.Category
import app.opentv.data.model.Channel
import app.opentv.data.model.CustomChannelGroup
import app.opentv.data.model.EpgFeed
import app.opentv.data.model.Programme
import app.opentv.data.model.Source
import app.opentv.data.model.SourceKind
import app.opentv.data.model.StreamKind
import app.opentv.data.parser.ChannelNameNormalizer
import app.opentv.data.repo.LocalCustomGroups
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Real Room + ViewModel flow integration, with synthetic offline sources and no player. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28])
@OptIn(ExperimentalCoroutinesApi::class)
class AdvancedGuideViewModelTest {
    private lateinit var app: Application
    private lateinit var database: OpenTvDatabase
    private lateinit var graph: ServiceLocator.Graph
    private lateinit var settings: AppSettings
    private lateinit var store: ViewModelStore
    private lateinit var collectors: CoroutineScope
    private lateinit var mainDispatcher: kotlinx.coroutines.test.TestDispatcher
    private var savedGraph: Any? = null
    private var savedRecent: Any? = null
    private var vmCount = 0
    private val viewModels = mutableListOf<ChannelsViewModel>()

    /** Creates isolated fixtures before each test. */
    @Before fun setup() {
        app = RuntimeEnvironment.getApplication()
        listOf("opentv_settings", "nuvix_private_settings", "nuvix_recent_channels").forEach {
            app.getSharedPreferences(it, Context.MODE_PRIVATE).edit().clear().commit()
        }
        mainDispatcher = UnconfinedTestDispatcher()
        Dispatchers.setMain(mainDispatcher)
        collectors = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        store = ViewModelStore()
        database = Room.inMemoryDatabaseBuilder(app, OpenTvDatabase::class.java)
            .allowMainThreadQueries().setQueryExecutor { it.run() }.setTransactionExecutor { it.run() }.build()
        settings = freshSettings()
        graph = ServiceLocator.Graph(app)
        // Exercise the production VM/repositories while replacing only Android's encrypted disk
        // opening path. Production dependency ownership and APIs remain unchanged.
        setDelegate("database", database)
        setDelegate("settings", settings)
        ServiceLocator::class.java.getDeclaredField("instance").apply {
            isAccessible = true
            savedGraph = get(null)
            set(null, graph)
        }
        RecentChannels::class.java.getDeclaredField("instance").apply {
            isAccessible = true
            savedRecent = get(null)
            set(null, null)
        }
    }

    /** Releases test resources and restores shared state after each test. */
    @After fun close(): Unit = runBlocking {
        collectors.cancel()
        store.clear()
        viewModels.forEach { it.viewModelScope.coroutineContext[Job]?.join() }
        collectors.coroutineContext[Job]?.join()
        database.close()
        ServiceLocator::class.java.getDeclaredField("instance").apply { isAccessible = true; set(null, savedGraph) }
        RecentChannels::class.java.getDeclaredField("instance").apply { isAccessible = true; set(null, savedRecent) }
        Dispatchers.resetMain()
    }

    /** Replaces a lazy application-graph dependency with the isolated guide test fixture. */
    private fun setDelegate(name: String, value: Any) {
        ServiceLocator.Graph::class.java.getDeclaredField("${name}\$delegate")
            .apply { isAccessible = true }.set(graph, lazyOf(value))
    }

    /** Constructs a fresh settings store to exercise persisted state independently of the singleton. */
    private fun freshSettings() = AppSettings::class.java.getDeclaredConstructor(Context::class.java)
        .apply { isAccessible = true }.newInstance(app)

    /** Creates a channel view model using the test application's isolated dependencies. */
    private fun viewModel(): ChannelsViewModel = ChannelsViewModel(app).also {
        viewModels += it
        store.put("guide-${vmCount++}", it)
        it.rows.launchIn(collectors)
        it.channelsPresent.launchIn(collectors)
    }

    /** Provides a synthetic provider configuration for the test's requested source identity. */
    private suspend fun source(id: Long, enabled: Boolean = true) = database.sources().insert(
        Source(id = id, name = "Offline source $id", kind = SourceKind.M3U,
            url = "https://source-$id.example.invalid/fixture.m3u", enabled = enabled),
    )

    /** Provides a synthetic channel with the identity and state required by this test. */
    private fun channel(id: Long, sourceId: Long = 1, favourite: Boolean = false, hidden: Boolean = false) = Channel(
        id = id, sourceId = sourceId, streamId = "provider-$id", name = "Channel $id",
        categoryId = null, logoUrl = null, epgChannelId = "guide-$id", number = id.toInt(),
        streamUrl = "https://source-$sourceId.example.invalid/live/$id", favourite = favourite, hidden = hidden,
    )

    /** Waits until guide rows match the expected ordered channel identities. */
    private suspend fun awaitIds(vm: ChannelsViewModel, vararg ids: Long): List<ChannelsViewModel.Row> = withTimeout(10_000) {
        combine(vm.rows, vm.guideLoaded) { rows, loaded -> rows to loaded }
            .first { (rows, loaded) -> loaded && rows.map { it.primary.id } == ids.toList() }.first
    }

    /** Checks: settings Changes Update An Existing Guide And Do Not Leave Provider Or Favourite Scope. */
    @Test fun settingsChangesUpdateAnExistingGuideAndDoNotLeaveProviderOrFavouriteScope(): Unit = runBlocking {
        source(1); source(2)
        database.channels().upsertAll(listOf(channel(1), channel(2, 2, favourite = true)))
        val vm = viewModel()
        awaitIds(vm, 1, 2)
        vm.selectSource(1)
        awaitIds(vm, 1)
        settings.setGuideDefaultFilter(GuideChannelFilter.ALL)
        awaitIds(vm, 1, 2)
        assertThat(vm.selectedSource.value).isNull()
        settings.setGuideDefaultFilter(GuideChannelFilter.FAVOURITES)
        awaitIds(vm, 2)
        assertThat(vm.favouritesOnly.value).isTrue()
        settings.setGuideDefaultFilter(GuideChannelFilter.ALL)
        awaitIds(vm, 1, 2)
        assertThat(vm.favouritesOnly.value).isFalse()
        settings.setGuideDefaultFilter(GuideChannelFilter.PROVIDER)
        awaitIds(vm, 1)
        assertThat(vm.selectedSource.value).isEqualTo(1L)
    }

    /** Checks: selected Provider And Custom Group Restore In New View Models. */
    @Test fun selectedProviderAndCustomGroupRestoreInNewViewModels(): Unit = runBlocking {
        source(1); source(2)
        val one = channel(1)
        val two = channel(2, 2)
        database.channels().upsertAll(listOf(one, two))
        val groupId = database.customGroups().insert(CustomChannelGroup(
            cloudId = "00000000-0000-0000-0000-000000000001", name = "My guide",
            members = listOf(LocalCustomGroups.fingerprint(one)),
        ))
        val original = viewModel()
        original.selectSource(2)
        awaitIds(original, 2)
        settings = freshSettings()
        setDelegate("settings", settings)
        val restoredProvider = viewModel()
        awaitIds(restoredProvider, 2)
        assertThat(restoredProvider.selectedSource.value).isEqualTo(2L)
        restoredProvider.selectGuideFilter(GuideChannelFilter.GROUP, groupId)
        awaitIds(restoredProvider, 1)
        settings = freshSettings()
        setDelegate("settings", settings)
        val restoredGroup = viewModel()
        awaitIds(restoredGroup, 1)
        assertThat(restoredGroup.guideFilter.value).isEqualTo(GuideChannelFilter.GROUP)
        assertThat(settings.guideFilterGroupId.value).isEqualTo(groupId)
        assertThat(restoredGroup.selectedSource.value).isNull()
    }

    /** Checks: changing Filter From Settings Clears The Previous Category Scope. */
    @Test fun changingFilterFromSettingsClearsThePreviousCategoryScope(): Unit = runBlocking {
        source(1)
        database.categories().upsertAll(listOf(
            Category("news", 1, "News", StreamKind.LIVE),
            Category("sports", 1, "Sports", StreamKind.LIVE),
        ))
        database.channels().upsertAll(listOf(
            channel(1).copy(categoryId = "news"),
            channel(2, favourite = true).copy(categoryId = "sports"),
        ))
        val vm = viewModel()
        awaitIds(vm, 1, 2)
        vm.selectCategory(ChannelNameNormalizer.normalize("News").groupKey)
        awaitIds(vm, 1)
        settings.setGuideDefaultFilter(GuideChannelFilter.FAVOURITES)
        awaitIds(vm, 2)
        assertThat(vm.selectedCategory.value).isNull()
    }

    /** Checks: empty Filters Complete Loading And Can Return To All Without Changing The Catalogue. */
    @Test fun emptyFiltersCompleteLoadingAndCanReturnToAllWithoutChangingTheCatalogue(): Unit = runBlocking {
        source(1)
        database.channels().upsertAll(listOf(channel(1)))
        val vm = viewModel()
        awaitIds(vm, 1)
        listOf(GuideChannelFilter.FAVOURITES, GuideChannelFilter.RECENT, GuideChannelFilter.GROUP,
            GuideChannelFilter.PROVIDER, GuideChannelFilter.WITH_EPG, GuideChannelFilter.AIRING).forEach { filter ->
            vm.selectGuideFilter(filter, groupId = 999)
            awaitIds(vm)
            assertThat(vm.guideLoaded.value).isTrue()
            assertThat(vm.channelsPresent.value).isTrue()
            vm.selectGuideFilter(GuideChannelFilter.ALL)
            awaitIds(vm, 1)
        }
        assertThat(database.channels().byId(1)).isEqualTo(channel(1))
    }

    /** Checks: hidden Ambiguous And Disabled Source Channels Cannot Appear In Groups. */
    @Test fun hiddenAmbiguousAndDisabledSourceChannelsCannotAppearInGroups(): Unit = runBlocking {
        source(1); source(2); source(3, enabled = false)
        val visibleAmbiguous = channel(1)
        val hiddenDuplicate = visibleAmbiguous.copy(id = 2, sourceId = 2, streamId = "second-provider", hidden = true)
        val unique = channel(3)
        val disabled = channel(4, 3)
        database.channels().upsertAll(listOf(visibleAmbiguous, hiddenDuplicate, unique, disabled))
        val groupId = database.customGroups().insert(CustomChannelGroup(
            cloudId = "00000000-0000-0000-0000-000000000002", name = "Safe group",
            members = listOf(visibleAmbiguous, unique, disabled).map(LocalCustomGroups::fingerprint),
        ))
        val vm = viewModel()
        vm.selectGuideFilter(GuideChannelFilter.GROUP, groupId)
        awaitIds(vm, 3)
        vm.selectGuideFilter(GuideChannelFilter.ALL)
        awaitIds(vm, 1, 3)
        assertThat(database.channels().byId(2)?.hidden).isTrue()
    }

    /**
     * Checks: focused Guide Position And Filter Changes Do Not Change Watch History Or Initialize
     * Playback.
     */
    @Test fun focusedGuidePositionAndFilterChangesDoNotChangeWatchHistoryOrInitializePlayback(): Unit = runBlocking {
        source(1)
        database.channels().upsertAll(listOf(channel(1), channel(2)))
        settings.lastChannelId = 91
        RecentChannels.get(app).record(2)
        val vm = viewModel()
        awaitIds(vm, 1, 2)
        settings.setGuidePosition(1, 1_800_000_000_000)
        vm.selectGuideFilter(GuideChannelFilter.RECENT)
        awaitIds(vm, 2)
        vm.selectGuideFilter(GuideChannelFilter.ALL)
        awaitIds(vm, 1, 2)
        settings.setGuideSort(GuideChannelSort.ALPHABETICAL)
        assertThat(settings.lastChannelId).isEqualTo(91L)
        assertThat(RecentChannels.get(app).ids.value).containsExactly(2L)
        listOf("recordingEngine", "streamingHttpClient").forEach { name ->
            val delegate = ServiceLocator.Graph::class.java.getDeclaredField("${name}\$delegate")
                .apply { isAccessible = true }.get(graph) as Lazy<*>
            assertThat(delegate.isInitialized()).isFalse()
        }
    }

    /** Checks: programme Hydration Tracks Only The Visible Slice Of A Larger Catalogue. */
    @Test fun programmeHydrationTracksOnlyTheVisibleSliceOfALargerCatalogue(): Unit = runBlocking {
        source(1)
        val channels = (1L..90L).map { channel(it) }
        database.channels().upsertAll(channels)
        database.epgFeeds().insert(EpgFeed(id = 1, name = "Offline guide", enabled = true))
        database.programmes().upsertAll(channels.map {
            Programme(feedId = 1, epgChannelId = it.epgChannelId!!, startUtcMillis = 0,
                endUtcMillis = 4_000_000_000_000, title = "Offline programme ${it.id}")
        })
        val vm = viewModel()
        val initial = withTimeout(10_000) {
            vm.rows.first { rows -> rows.size == 90 && rows.count { it.programmes.isNotEmpty() } == 60 }
        }
        vm.setVisibleGuideRows(initial.subList(70, 80))
        val moved = withTimeout(10_000) {
            vm.rows.first { rows -> rows.filter { it.programmes.isNotEmpty() }.map { it.primary.id } == (71L..80L).toList() }
        }
        assertThat(moved).hasSize(90)
        assertThat(moved.count { it.programmes.isNotEmpty() }).isEqualTo(10)
        assertThat(moved.first().programmes).isEmpty()
        assertThat(moved[70].now?.title).isEqualTo("Offline programme 71")
    }
    /** Manager-only view filtering retains source identity even when provider category IDs collide. */
    @Test fun englishGroupReviewFiltersManagerOnlyAndClearingRestoresOriginalOrder(): Unit = runBlocking {
        source(1); source(2)
        val categories = listOf(
            Category("same", 1, "English", StreamKind.LIVE, 0),
            Category("other", 1, "News", StreamKind.LIVE, 1),
            Category("same", 2, "English", StreamKind.LIVE, 0),
            Category("other", 2, "French", StreamKind.LIVE, 2),
        )
        database.categories().upsertAll(categories)
        database.channels().upsertAll(listOf(
            channel(1, favourite = true, hidden = true).copy(categoryId = "same", name = "Fixture One"),
            channel(2, 2).copy(categoryId = "same", name = "Fixture Two"),
            channel(3).copy(categoryId = "other", name = "Fixture Three"),
        ))
        val vm = viewModel()
        vm.managerCategoryGroups.launchIn(collectors)
        vm.managerRows.launchIn(collectors)
        vm.managerResults.launchIn(collectors)
        vm.managerEnglishCatalog.onEach { vm.englishGroups.updateCatalog(it) }.launchIn(collectors)
        val original = withTimeout(10_000) { vm.managerCategoryGroups.first { it.size == 3 } }
        withTimeout(10_000) { vm.managerEnglishCatalog.first { it.size == 4 } }
        val before = database.channels().allForMatching()
        vm.englishGroups.scan()
        withTimeout(10_000) { vm.englishGroups.state.first { it.scanned } }
        assertThat(vm.managerCategoryGroups.value).isEqualTo(original)
        vm.englishGroups.toggle(ProviderGroupKey(1, "same"))
        assertThat(vm.englishGroups.apply()).isTrue()
        val filtered = withTimeout(10_000) { vm.managerCategoryGroups.first { it.size == 1 } }
        vm.selectManagerCategory(filtered.single().key)
        val rows = withTimeout(10_000) { vm.managerRows.first { it.isNotEmpty() } }
        assertThat(rows.flatMap { it.variants }.map { it.id }).containsExactly(1L)
        assertThat(rows.single().primary.hidden).isTrue()
        assertThat(rows.single().primary.favourite).isTrue()
        vm.setManagerQuery("Fixture")
        mainDispatcher.scheduler.advanceTimeBy(250)
        mainDispatcher.scheduler.runCurrent()
        val searched = withTimeout(10_000) { vm.managerResults.first { it.isNotEmpty() } }
        assertThat(searched.flatMap { it.variants }.map { it.id }).containsExactly(1L)
        vm.selectManagerSource(2)
        withTimeout(10_000) { vm.managerCategoryGroups.first { it.isEmpty() } }
        vm.englishGroups.clearFilter()
        withTimeout(10_000) { vm.managerCategoryGroups.first { it.size == 2 } }
        vm.selectManagerSource(null)
        assertThat(withTimeout(10_000) { vm.managerCategoryGroups.first { it.size == 3 } }).isEqualTo(original)
        assertThat(database.channels().allForMatching()).isEqualTo(before)
        assertThat(database.categories().allByKind(StreamKind.LIVE)).containsExactlyElementsIn(categories)
        assertThat(vm.selectedSource.value).isNull()
        assertThat(vm.selectedCategory.value).isNull()
    }

    @Test fun englishFilterPreservesFoldedOrderAndDisambiguatesSelectedGroupsWithTheSameId(): Unit = runBlocking {
        source(1); source(2)
        database.categories().upsertAll(listOf(
            Category("early", 1, "English Movies SD", StreamKind.LIVE, 0),
            Category("news", 1, "English News", StreamKind.LIVE, 1),
            Category("late", 1, "English Movies HD", StreamKind.LIVE, 2),
            Category("late", 2, "French", StreamKind.LIVE, 3),
        ))
        database.channels().upsertAll(listOf(
            channel(1).copy(categoryId = "late"),
            channel(2).copy(categoryId = "news"),
            channel(3, 2).copy(categoryId = "late"),
        ))
        val vm = viewModel()
        vm.managerCategoryGroups.launchIn(collectors)
        vm.managerRows.launchIn(collectors)
        val original = withTimeout(10_000) { vm.managerCategoryGroups.first { it.size == 3 } }
        vm.englishGroups.updateCatalog(withTimeout(10_000) { vm.managerEnglishCatalog.first { it.size == 4 } })
        vm.englishGroups.scan()
        withTimeout(10_000) { vm.englishGroups.state.first { it.scanned } }
        listOf(1L to "late", 1L to "news", 2L to "late").forEach { (source, id) ->
            vm.englishGroups.toggle(ProviderGroupKey(source, id))
        }
        assertThat(vm.englishGroups.apply()).isTrue()
        val filtered = withTimeout(10_000) { vm.managerCategoryGroups.first { it.firstOrNull()?.ids == listOf("late") } }
        assertThat(filtered.map { it.key }).containsExactlyElementsIn(original.map { it.key }).inOrder()
        assertThat(filtered.map { it.label }).containsExactlyElementsIn(original.map { it.label }).inOrder()
        vm.selectManagerCategory(filtered.first().key)
        val rows = withTimeout(10_000) { vm.managerRows.first { it.isNotEmpty() } }
        assertThat(rows.flatMap { it.variants }.map { it.id }).containsExactly(1L)
    }

}
