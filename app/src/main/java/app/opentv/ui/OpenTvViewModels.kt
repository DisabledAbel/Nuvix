/* Nuvix modifications Copyright (C) 2026 Nuvix Contributors. GPL-3.0-or-later. */
/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 * Modified for Nuvix on 2026-10-13.
 */
package app.opentv.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.opentv.core.GuideChannelFilter
import app.opentv.core.GuideChannelSort
import app.opentv.core.RecentChannels
import app.opentv.ui.channels.selectGuideChannels
import app.opentv.data.repo.LocalCustomGroups
import app.opentv.core.ServiceLocator
import app.opentv.data.model.Category
import app.opentv.data.model.Channel
import app.opentv.data.model.EpgFeed
import app.opentv.data.model.LiveStreamFormat
import app.opentv.data.model.Movie
import app.opentv.data.model.PlaybackPosition
import app.opentv.data.model.Profile
import app.opentv.data.model.Programme
import app.opentv.data.model.Series
import app.opentv.data.model.Source
import app.opentv.data.model.SourceKind
import app.opentv.data.model.StremioStream
import app.opentv.data.model.StreamKind
import app.opentv.data.parser.displayTitle
import app.opentv.data.parser.ChannelNameNormalizer
import app.opentv.data.repo.CatalogRepository
import app.opentv.data.repo.GenreGroup
import app.opentv.data.repo.MovieVariantGroup
import app.opentv.data.repo.PersonTitle
import app.opentv.data.repo.distinctByQuality
import com.disabledabel.nuvix.R
import app.opentv.pairing.ManagerServer
import app.opentv.sync.NasSync
import app.opentv.sync.SyncBundle
import app.opentv.sync.SyncClient
import app.opentv.sync.SyncPosition
import app.opentv.sync.SyncServer
import kotlinx.serialization.json.Json
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import android.util.Log
import app.opentv.core.StatusBus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * View models for the whole app.
 *
 * Grouped in one file on purpose: they are small, they share the same dependency graph, and
 * a contributor tracking down "where does the channel list come from" finds it in one place.
 */

class SourcesViewModel(app: Application) : AndroidViewModel(app) {
    private val graph = ServiceLocator.get(app)

    /** Provider list, draft form, connection-test progress and transient provider feedback. */
    data class UiState(
        val sources: List<Source> = emptyList(),
        /**
         * False until the saved sources have been read from the database once. Distinguishing
         * "no sources yet loaded" from "loaded, and there are none" is what stops a returning
         * user being sent to the setup screen — and asked for their provider again — during the
         * brief moment before the database responds on a cold launch.
         */
        val loaded: Boolean = false,
        val testing: Boolean = false,
        val testResult: String? = null,
        val testError: String? = null,
        val syncing: Boolean = false,
        val syncMessage: String? = null,
    )

    private val _ui = MutableStateFlow(UiState())
    val ui: StateFlow<UiState> = _ui.asStateFlow()

    init {
        viewModelScope.launch {
            graph.sourceRepository.observeAll().collect { sources ->
                _ui.value = _ui.value.copy(sources = sources, loaded = true)
            }
        }
    }

    /** Tests the current provider draft and publishes its connection result. */
    fun test(draft: Source) {
        viewModelScope.launch {
            _ui.value = _ui.value.copy(testing = true, testResult = null, testError = null)
            val result = graph.sourceRepository.test(draft)
            _ui.value = _ui.value.copy(
                testing = false,
                testResult = result.getOrNull(),
                testError = result.exceptionOrNull()?.message,
            )
        }
    }

    /** Saves, then immediately pulls the catalogue so the user sees channels, not a spinner. */
    fun saveAndSync(draft: Source, onDone: (Boolean) -> Unit = {}) {
        viewModelScope.launch {
            _ui.value = _ui.value.copy(syncing = true, syncMessage = "Saving source…")
            val id = try {
                graph.sourceRepository.save(draft)
            } catch (error: kotlinx.coroutines.CancellationException) {
                throw error
            } catch (error: Exception) {
                _ui.value = _ui.value.copy(syncing = false, syncMessage = app.opentv.core.ProviderDiagnostics.describe(error))
                onDone(false)
                return@launch
            }
            val saved = graph.sourceRepository.byId(id)
            if (saved == null) {
                _ui.value = _ui.value.copy(syncing = false, syncMessage = "Could not save source.")
                onDone(false)
                return@launch
            }

            _ui.value = _ui.value.copy(syncMessage = "Loading channels…")
            val now = System.currentTimeMillis()
            // Load live channels first and get the user watching straight away. Movies, series and
            // the guide are what make a big provider take minutes — they load in the background so
            // "loading channels" is a few seconds, not a twenty-minute blank screen.
            when (val result = graph.catalogRepository.syncLive(saved, now)) {
                is CatalogRepository.SyncResult.Failed -> {
                    _ui.value = _ui.value.copy(syncing = false, syncMessage = result.reason)
                    onDone(false)
                    return@launch
                }
                is CatalogRepository.SyncResult.Success -> {
                    _ui.value = _ui.value.copy(
                        syncing = false,
                        syncMessage = "Loaded ${result.channelCount} channels. The guide is " +
                            "loading in the background; Movies and Shows load when you open them.",
                    )
                    onDone(true)
                }
            }

            // Background: the guide. Movies/series are pulled on demand from their own tabs, so
            // nothing the user hasn't asked for ever blocks the channels they can already watch.
            runCatching {
                val summary = StatusBus.during("Building the TV guide…") {
                    graph.epgRepository.syncAll(now)
                }
                _ui.value = _ui.value.copy(
                    syncMessage = when {
                        summary.channelsMatched > 0 ->
                            "Guide ready — matched ${summary.channelsMatched} of " +
                                "${summary.channelsTotal} channels."
                        else ->
                            "Channels ready. No guide data matched yet — add a free guide " +
                                "under Guide settings."
                    },
                )
                // Book any new series-link airings the fresh guide just revealed.
                runCatching { graph.recordingEngine.rescanSeriesRules() }
            }.onFailure { Log.w("Nuvix", "Background VOD/guide load failed") }
        }
    }

    /** Identity edits are local-only and never trigger catalog sync or restart consumers. */
    fun saveStalkerConfiguration(draft: Source, onResult: (String?) -> Unit) {
        viewModelScope.launch {
            try {
                graph.sourceRepository.saveStalkerConfiguration(draft)
                onResult(null)
            } catch (error: kotlinx.coroutines.CancellationException) {
                throw error
            } catch (_: Exception) {
                onResult("Could not save provider configuration. Reopen the provider settings and verify your values.")
            }
        }
    }

    /** Deletes the selected provider and refreshes dependent catalog state. */
    fun delete(source: Source) {
        viewModelScope.launch { graph.catalogRepository.deleteSource(source.id) }
    }

    /**
     * Change an Xtream source's live-stream container. The repository rewrites the source's live
     * channel URLs in place, so the switch takes effect without a re-sync; the observed source list
     * then re-emits with the new [Source.liveFormat]. A no-op for M3U sources and unchanged values.
     */
    fun setLiveFormat(source: Source, format: LiveStreamFormat) {
        if (source.kind != SourceKind.XTREAM || source.liveFormat == format) return
        viewModelScope.launch { graph.catalogRepository.setLiveFormat(source.id, format) }
    }

    /** Starts a refresh of enabled provider catalogs and guide data. */
    fun refreshAll() {
        viewModelScope.launch {
            _ui.value = _ui.value.copy(syncing = true, syncMessage = "Refreshing…")
            val now = System.currentTimeMillis()
            var channels = 0
            var problems = 0
            for (source in graph.sourceRepository.enabled()) {
                when (val result = graph.catalogRepository.sync(source, now)) {
                    is CatalogRepository.SyncResult.Success -> channels += result.channelCount
                    is CatalogRepository.SyncResult.Failed -> problems++
                }
            }
            val summary = graph.epgRepository.syncAll(now, force = true)
            problems += summary.feedsFailed
            runCatching { graph.recordingEngine.rescanSeriesRules() }
            _ui.value = _ui.value.copy(
                syncing = false,
                syncMessage = if (problems == 0) {
                    "Refreshed $channels channels, guide matched " +
                        "${summary.channelsMatched} of ${summary.channelsTotal}."
                } else {
                    "Refreshed $channels channels, $problems problem(s) — see Guide settings."
                },
            )
        }
    }

    /** Creates the initial provider-entry draft for the selected source kind. */
    fun blankDraft() = Source(name = "", kind = SourceKind.XTREAM, url = "")
}

/** Combines catalog channels, guide preferences, filters and bounded guide-window state. */
class ChannelsViewModel(app: Application) : AndroidViewModel(app) {
    private val graph = ServiceLocator.get(app)
    private val settings = graph.settings

    /** null = All. Exposed so the sidebar can highlight the active entry. */
    val selectedCategory = MutableStateFlow<String?>(null)
    val favouritesOnly = MutableStateFlow(settings.guideDefaultFilter.value == GuideChannelFilter.FAVOURITES)
    /** Provider filter for the live guide. null = every source. Only surfaced when there's >1 source. */
    val selectedSource = MutableStateFlow<Long?>(settings.guideFilterProviderId.value.takeIf { settings.guideDefaultFilter.value == GuideChannelFilter.PROVIDER })
    private val query = MutableStateFlow("")
    private val nowTick = MutableStateFlow(System.currentTimeMillis())

    init {
        viewModelScope.launch {
            combine(settings.guideDefaultFilter, settings.guideFilterProviderId) { filter, provider -> filter to provider }
                .collect { (filter, provider) ->
                    selectedCategory.value = null
                    favouritesOnly.value = filter == GuideChannelFilter.FAVOURITES
                    selectedSource.value = provider.takeIf { filter == GuideChannelFilter.PROVIDER }
                }
        }
    }


    /**
     * One rail entry = one *logical* category.
     *
     * Providers split categories by codec — 'UK| GENERAL HD/RAW' and 'UK| GENERAL hevc'
     * are the same shelf twice. Fold them by normalised name, show the clean label, and
     * filter across every underlying id at once. This is the on-device version of what
     * Viewella did on a server it had to pay for.
     */
    data class CategoryGroup(val key: String, val label: String, val ids: List<String>)

    val categoryGroups: StateFlow<List<CategoryGroup>> =
        combine(
            graph.catalogRepository.observeCategories(StreamKind.LIVE),
            selectedSource,
        ) { raw, sourceId ->
            // Scope the category rail to the chosen provider, so a second playlist's categories show
            // on their own (cardiodoc's "keep sources separate"); null folds across every provider.
            val scoped = if (sourceId == null) raw else raw.filter { it.sourceId == sourceId }
            foldCategories(scoped)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /**
     * Folds codec-split provider categories into one logical [CategoryGroup] each — 'UK| GENERAL
     * HD/RAW' and 'UK| GENERAL hevc' become a single "General" entry that filters across every
     * underlying id at once. Shared by the guide's [categoryGroups] and the manager's
     * [managerCategoryGroups] so both group the rail identically.
     */
    private fun foldCategories(raw: List<Category>): List<CategoryGroup> {
        val groups = LinkedHashMap<String, Pair<String, MutableList<String>>>()
        for (category in raw) {
            val n = ChannelNameNormalizer.normalize(category.name)
            val key = n.groupKey.ifEmpty { category.id }
            val entry = groups.getOrPut(key) { n.baseName to mutableListOf() }
            entry.second += category.id
        }
        return groups.map { (key, value) -> CategoryGroup(key, value.first, value.second) }
    }

    /**
     * The category ids to hide from the guide *right now*: the ids behind every group the user
     * marked adult — unless the session is unlocked, in which case nothing is hidden.
     */
    private val hiddenCategoryIds: StateFlow<Set<String>> =
        combine(categoryGroups, settings.hiddenCategories, settings.hiddenUnlocked) { groups, hidden, unlocked ->
            if (unlocked) emptySet()
            else groups.filter { it.key in hidden }.flatMap { it.ids }.toSet()
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptySet())

    /** The rail's view of categories: hidden ones drop out until the session is unlocked. */
    val visibleCategoryGroups: StateFlow<List<CategoryGroup>> =
        combine(categoryGroups, settings.hiddenCategories, settings.hiddenUnlocked) { groups, hidden, unlocked ->
            if (unlocked) groups else groups.filter { it.key !in hidden }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /**
     * One row on screen = one *logical* channel.
     *
     * [primary] is the best-quality variant and what plays on click; [variants] is every
     * quality of the same channel, for the in-player switch. `UK| BBC ONE SD/HD/FHD/RAW`
     * is one row, not four — the guide has one BBC One, and so should we.
     */
    @androidx.compose.runtime.Immutable
    data class Row(
        val primary: Channel,
        val variants: List<Channel>,
        val now: Programme?,
        val next: Programme?,
        /** Every programme for this channel inside the guide window, start-ordered. */
        val programmes: List<Programme>,
    ) {
        val key: Any get() = if (primary.groupKey.isEmpty()) primary.id else primary.groupKey
    }

    /**
     * The guide's left edge: the current half-hour, rounded down. Programme block positions
     * are measured from here. Recomputed on each tick so the grid drifts with real time.
     */
    /** Which day the guide is browsing: 0 = today, 1 = tomorrow… so it pages forward like Sky Q. */
    private val restoredTime = settings.lastGuidePosition.value?.takeIf { settings.rememberGuidePosition.value }?.timeMs
    private val _guideDayOffset = MutableStateFlow(restoredTime?.let { saved ->
        val today = java.time.LocalDate.now()
        val date = java.time.Instant.ofEpochMilli(saved).atZone(java.time.ZoneId.systemDefault()).toLocalDate()
        java.time.temporal.ChronoUnit.DAYS.between(today, date).toInt().coerceIn(-1, GUIDE_MAX_DAYS)
    } ?: 0)
    val guideDayOffset: StateFlow<Int> = _guideDayOffset.asStateFlow()

    /** Moves the guide day offset within its supported history and future bounds. */
    fun nudgeGuideDay(delta: Int) {
        _guideDayOffset.value = (_guideDayOffset.value + delta).coerceIn(-1, GUIDE_MAX_DAYS)
    }

    /** Returns the guide's displayed time window to the current time. */
    fun guideToNow() { _guideDayOffset.value = 0 }

    val windowStartMillis: StateFlow<Long> =
        combine(nowTick.map { it - it % HALF_HOUR_MILLIS }, _guideDayOffset) { base, day ->
            if (day == 0) base - 6 * 60 * 60_000L else java.util.Calendar.getInstance().apply {
                timeInMillis = startOfLocalDay(base)
                add(java.util.Calendar.DAY_OF_YEAR, day)
            }.timeInMillis
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000),
            System.currentTimeMillis().let { now ->
                val base = now - now % HALF_HOUR_MILLIS
                val day = _guideDayOffset.value
                if (day == 0) base - 6 * 60 * 60_000L else java.util.Calendar.getInstance().apply {
                    timeInMillis = startOfLocalDay(base)
                    add(java.util.Calendar.DAY_OF_YEAR, day)
                }.timeInMillis
            })

    /** Finds the UTC epoch time corresponding to the start of the device's local day. */
    private fun startOfLocalDay(millis: Long): Long {
        val cal = java.util.Calendar.getInstance()
        cal.timeInMillis = millis
        cal.set(java.util.Calendar.HOUR_OF_DAY, 0)
        cal.set(java.util.Calendar.MINUTE, 0)
        cal.set(java.util.Calendar.SECOND, 0)
        cal.set(java.util.Calendar.MILLISECOND, 0)
        return cal.timeInMillis
    }

    /** Channel selection inputs used to avoid rebuilding unchanged guide row queries. */
    private data class RowsKey(
        val categoryIds: List<String>?,
        val favs: Boolean,
        val query: String,
        val hiddenIds: Set<String>,
    )

    val customGuideGroups = graph.database.customGroups().observeActive()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val guideFilter = settings.guideDefaultFilter
    val guideSort = settings.guideSort
    private val recentIds = RecentChannels.get(app).ids

    /** Selects the guide filter and updates its optional provider or custom-group target. */
    fun selectGuideFilter(filter: GuideChannelFilter, groupId: Long? = null) {
        selectedCategory.value = null
        favouritesOnly.value = filter == GuideChannelFilter.FAVOURITES
        if (filter != GuideChannelFilter.PROVIDER) selectedSource.value = null
        settings.setGuideFilter(filter, groupId, selectedSource.value)
    }

    /** Only visible channels plus a small prefetch margin hold programme objects. */
    private val visibleGuideIds = MutableStateFlow<List<Long>>(emptyList())
    /** Updates the visible guide row IDs used for bounded program hydration. */
    fun setVisibleGuideRows(rows: List<Row>) {
        visibleGuideIds.value = rows.take(60).map { it.primary.id }.distinct()
    }

    val guideLoaded = MutableStateFlow(false)

    /** Guide filter selection with its optional provider or group target. */
    private data class GuideFilterState(
        val filter: GuideChannelFilter,
        val groupId: Long?,
        val sort: GuideChannelSort,
        val recent: List<Long>,
    )
    private val filterState = combine(settings.guideDefaultFilter, settings.guideFilterGroupId,
        settings.guideSort, recentIds) { filter, groupId, sort, recent ->
        GuideFilterState(filter, groupId, sort, recent)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private val epgFilterIds = combine(settings.guideDefaultFilter, windowStartMillis, nowTick) { filter, start, now ->
        Triple(filter, start, now)
    }.flatMapLatest { (filter, start, now) ->
        when (filter) {
            GuideChannelFilter.WITH_EPG -> graph.epgRepository.observeEpgChannelIds(start, start + GUIDE_LOOKAHEAD_MILLIS)
            GuideChannelFilter.AIRING -> graph.epgRepository.observeAiringChannelIds(now)
            else -> flowOf(emptySet())
        }
    }.distinctUntilChanged()

    @OptIn(ExperimentalCoroutinesApi::class)
    private val channelRows: StateFlow<List<Row>> =
        combine(selectedCategory, favouritesOnly, query, categoryGroups, hiddenCategoryIds) {
                category, favs, q, groups, hidden ->
            RowsKey(category?.let { key -> groups.firstOrNull { it.key == key }?.ids }, favs, q, hidden)
        }.combine(combine(selectedSource, settings.guideDefaultFilter) { source, filter -> source to filter }) { key, selected -> Triple(key, selected.first, selected.second) }
        .flatMapLatest { (key, source, filterKind) ->
            val channels = when {
                filterKind == GuideChannelFilter.PROVIDER && source == null -> flowOf(emptyList())
                filterKind == GuideChannelFilter.GROUP -> graph.database.customGroups().observeAuthorizedChannels()
                key.query.isNotBlank() -> graph.catalogRepository.searchChannels(key.query)
                key.categoryIds != null -> graph.catalogRepository.observeChannelsIn(key.categoryIds)
                else -> graph.catalogRepository.observeChannels(source)
            }
            combine(channels, filterState, customGuideGroups, epgFilterIds, graph.sourceRepository.observeAll()) {
                    all, filter, groups, epgIds, sources ->
                val enabled = sources.filter { it.enabled }.map { it.id }.toSet()
                val authorized = all.filter { it.sourceId in enabled && (source == null || it.sourceId == source) }
                val group = groups.firstOrNull { it.id == filter.groupId }
                // Fingerprints resolve against the complete authorized catalogue to preserve ambiguity rules.
                val selected = selectGuideChannels(authorized, filter.filter, filter.sort,
                    filter.recent, group, epgIds).filter { it.categoryId !in key.hiddenIds }
                buildRows(selected, emptyMap(), System.currentTimeMillis())
            }
        }.flowOn(Dispatchers.Default)
        .onEach { guideLoaded.value = true }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    @OptIn(ExperimentalCoroutinesApi::class)
    private val programmeIndex = combine(channelRows, visibleGuideIds, windowStartMillis) { rows, ids, start ->
        val visible = if (ids.isEmpty()) rows.take(60) else rows.filter { it.primary.id in ids }.take(60)
        val wanted = visible.flatMap { it.variants }.flatMap { it.epgCandidates }.distinct()
        Triple(wanted, start, start + GUIDE_LOOKAHEAD_MILLIS)
    }.distinctUntilChanged().flatMapLatest { (ids, from, to) ->
        graph.epgRepository.observeWindowForChannels(ids, from, to)
    }.map { programmes -> programmes.groupBy { it.epgChannelId } }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    val rows: StateFlow<List<Row>> = combine(channelRows, programmeIndex, nowTick) { channels, index, now ->
        channels.map { row ->
            val list = row.variants.flatMap { it.epgCandidates }.firstNotNullOfOrNull { index[it] }.orEmpty()
            if (list.isEmpty()) row else row.copy(now = list.firstOrNull { it.isLiveAt(now) },
                next = list.firstOrNull { it.startUtcMillis > now }, programmes = list)
        }
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Builds channel rows for the current guide selection, sorting and visibility rules. */
    private fun buildRows(
        channels: List<Channel>,
        byEpgChannel: Map<String, List<Programme>>,
        now: Long,
        reportProgress: Boolean = false,
    ): List<Row> {
        // Fold channel variants separately from the bounded programme viewport. Search and the
        // manager reuse this projection without requesting programme objects.
        val groups = LinkedHashMap<Any, MutableList<Channel>>()
        for (channel in channels) {
            val key: Any = channel.groupKey.ifEmpty { channel.id }
            groups.getOrPut(key) { mutableListOf() } += channel
        }

        val total = groups.size.coerceAtLeast(1)
        var built = 0
        return groups.values.map { group ->
            group.sortByDescending { it.qualityRank }
            // Only genuinely different qualities are switchable; identical-quality dupes
            // (same stream in two categories) collapse to one, so no false "2 qualities".
            val variants = distinctByQuality(group)
            val primary = variants.first()

            // Walk every variant's guide-id candidates (override → provider → matched)
            // and use the first that actually has programmes. An id that never lights up
            // must not shadow a sibling variant whose id does.
            val list = variants
                .flatMap { it.epgCandidates }
                .firstNotNullOfOrNull { id -> byEpgChannel[id] }
                ?.sortedBy { it.startUtcMillis }
                .orEmpty()

            // Feed the progress bar as rows come together — this is the slow part on a big
            // provider, so it's what the percentage should actually track.
            if (reportProgress) {
                built++
                if (built % 400 == 0 || built == total) {
                    StatusBus.setProgress(built.toFloat() / total)
                }
            }

            Row(
                primary = primary,
                variants = variants,
                now = list.firstOrNull { it.isLiveAt(now) },
                next = list.firstOrNull { it.startUtcMillis > now },
                programmes = list,
            )
        }
    }

    /** A friendly, size-aware line for the load — a small provider gets a quick word, a huge one
     * gets a "bear with me". Used at start-up so the wait always says what it's doing. */
    private fun sizeMessage(count: Int): String = when {
        count <= 0 -> "Building the guide…"
        count < 2000 -> "Loading $count channels — a small one, this'll be quick."
        count < 8000 -> "Loading $count channels — a fair few, give me a moment…"
        else -> "Loading $count channels — a big one, bear with me, I'm on it…"
    }

    val favourites: StateFlow<List<Channel>> =
        graph.catalogRepository.observeFavouriteChannels()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /**
     * Whether the catalogue has any channels yet, as a tri-state that stops the home screen
     * spinning forever when a provider fails to load:
     *  - null  → the count query hasn't returned; still checking, keep showing the loader.
     *  - true  → channels are on disk, so an empty [rows] just means the guide is still building.
     *  - false → confirmed empty; if a provider is configured the last sync failed or returned
     *            nothing, so the screen shows a recoverable error instead of an endless spinner.
     */
    val channelsPresent: StateFlow<Boolean?> =
        graph.catalogRepository.observeChannelCount()
            .map { it > 0 }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** Changes the selected channel category. */
    fun selectCategory(id: String?) {
        favouritesOnly.value = false
        settings.setGuideFilter(if (selectedSource.value == null) GuideChannelFilter.ALL else GuideChannelFilter.PROVIDER,
            providerId = selectedSource.value)
        selectedCategory.value = id
    }

    /** Changes whether the channel list shows only favorites. */
    fun selectFavourites() {
        selectGuideFilter(GuideChannelFilter.FAVOURITES)
    }

    /** Switch the live guide's provider filter; the category resets since categories differ per source. */
    fun selectSource(sourceId: Long?) {
        selectedSource.value = sourceId
        selectedCategory.value = null
        favouritesOnly.value = false
        settings.setGuideFilter(if (sourceId == null) GuideChannelFilter.ALL else GuideChannelFilter.PROVIDER,
            providerId = sourceId)
    }

    /** Updates channel search through the view model's search state. */
    fun search(text: String) { query.value = text }

    // ---- Dedicated channel search --------------------------------------------------------------
    // Kept separate from the guide's `rows` on purpose: `rows` loads the entire 12-hour EPG
    // window, which is far too heavy to run on every keystroke (that was the ~30s search lag).
    // This path is channel-only — no programme window — debounced, and capped by the DAO's LIMIT.
    private val searchInput = MutableStateFlow("")

    /** Updates the channel-list search text. */
    fun setSearchQuery(text: String) { searchInput.value = text }

    @OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
    val searchResults: StateFlow<List<Row>> =
        searchInput
            .map { it.trim() }
            .debounce(200)
            .distinctUntilChanged()
            .flatMapLatest { q ->
                if (q.length < 2) flowOf(emptyList())
                else graph.catalogRepository.searchChannels(q)
                    // No EPG here — group channels into rows with no now/next. Finding and
                    // playing the channel is the job; guide detail would cost the slow query.
                    .map { channels -> buildRows(channels, emptyMap(), System.currentTimeMillis()) }
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Refreshes the current-time input used by guide and now-playing queries. */
    fun tick() { nowTick.value = System.currentTimeMillis() }

    // ---- Channel manager (search, then hide/show or favourite) ---------------------------------
    private val managerInput = MutableStateFlow("")

    /** Updates the channel manager's text filter. */
    fun setManagerQuery(text: String) { managerInput.value = text }

    @OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
    val managerResults: StateFlow<List<Row>> =
        managerInput
            .map { it.trim() }
            .debounce(200)
            .distinctUntilChanged()
            .flatMapLatest { q ->
                if (q.length < 2) flowOf(emptyList())
                else graph.catalogRepository.searchChannelsIncludingHidden(q)
                    .map { channels -> buildRows(channels, emptyMap(), System.currentTimeMillis()) }
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    // ---- Channel manager: browse by category ---------------------------------------------------
    // Deliberately independent of the guide's `selectedCategory`/`rows`: browsing here never moves
    // what Live TV is showing. Two differences from the guide feed — it can be scoped to a single
    // provider (cardiodoc's "keep sources separate"), and it includes HIDDEN channels so they can
    // be brought back. Always scoped to one category, never the whole catalogue, so the right pane
    // stays a size a remote can scroll (no flat 20k-row list).

    /** Provider filter for the manager. null = every source ("All sources"). */
    val managerSelectedSource = MutableStateFlow<Long?>(null)

    /** The [CategoryGroup.key] whose channels fill the manager's right pane; null = none picked. */
    val managerSelectedCategory = MutableStateFlow<String?>(null)

    /** Providers, so the manager can offer a source filter (shown only when there's more than one). */
    val sources: StateFlow<List<Source>> =
        graph.sourceRepository.observeAll()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /**
     * The manager's category rail: like [categoryGroups] but scoped to [managerSelectedSource], and
     * NOT filtered by the adult/hidden-category setting — the manager shows every category so a
     * hidden one's channels can still be reached and un-hidden.
     */
    val managerCategoryGroups: StateFlow<List<CategoryGroup>> =
        combine(
            graph.catalogRepository.observeCategories(StreamKind.LIVE),
            managerSelectedSource,
        ) { raw, sourceId ->
            val scoped = if (sourceId == null) raw else raw.filter { it.sourceId == sourceId }
            foldCategories(scoped)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /**
     * Channels in the selected manager category (and source), grouped into [Row]s exactly like the
     * guide — but including hidden channels. Empty until a category is picked. Reuses [buildRows]
     * with no EPG window (the manager needs logo/name/state, not now/next).
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    val managerRows: StateFlow<List<Row>> =
        combine(managerSelectedSource, managerSelectedCategory, managerCategoryGroups) { source, key, groups ->
            val ids = key?.let { k -> groups.firstOrNull { it.key == k }?.ids }
            source to ids
        }
            .distinctUntilChanged()
            .flatMapLatest { (source, ids) ->
                if (ids.isNullOrEmpty()) flowOf(emptyList())
                else graph.catalogRepository.observeChannelsInIncludingHidden(source, ids)
                    .map { channels -> buildRows(channels, emptyMap(), System.currentTimeMillis()) }
            }
            .flowOn(Dispatchers.Default)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Switch the manager's provider filter; the category resets since categories differ per source. */
    fun selectManagerSource(sourceId: Long?) {
        managerSelectedSource.value = sourceId
        managerSelectedCategory.value = null
    }

    /** Changes the category filter used by channel management. */
    fun selectManagerCategory(key: String?) { managerSelectedCategory.value = key }

    /** Hide or show a whole logical channel — every quality variant follows. */
    fun setRowHidden(row: Row, hidden: Boolean) {
        viewModelScope.launch {
            row.variants.forEach { graph.catalogRepository.setChannelHidden(it.id, hidden) }
        }
    }

    /** Toggles the selected channel's favorite flag. */
    fun toggleFavourite(row: Row) {
        viewModelScope.launch {
            // Favouriting the row favourites the logical channel: every variant follows,
            // so the choice survives switching quality.
            val target = !row.primary.favourite
            row.variants.forEach { graph.catalogRepository.setChannelFavourite(it.id, target) }
        }
    }

    /** Changes the selected channel's hidden state. */
    fun hide(row: Row) {
        viewModelScope.launch {
            row.variants.forEach { graph.catalogRepository.setChannelHidden(it.id, true) }
        }
    }

    private companion object {
        const val GUIDE_LOOKAHEAD_MILLIS = 24 * 60 * 60 * 1000L  // a full day fits the grid
        const val DAY_MILLIS = 24 * 60 * 60 * 1000L
        const val GUIDE_MAX_DAYS = 6  // browse up to a week out, matching typical XMLTV depth
        const val HALF_HOUR_MILLIS = 30 * 60 * 1000L
    }
}

/** Backs the Guide settings screen: feed list, toggles, custom URLs, match report. */
class EpgViewModel(app: Application) : AndroidViewModel(app) {
    private val graph = ServiceLocator.get(app)
    val automaticEpg: StateFlow<Boolean> = graph.settings.automaticEpg

    /** Persists the automatic guide-discovery choice. */
    fun setAutomaticEpg(enabled: Boolean) {
        graph.settings.setAutomaticEpg(enabled)
        if (enabled) refresh()
    }

    /** Configured guide feeds, refresh progress and transient guide settings feedback. */
    data class UiState(
        val feeds: List<EpgFeed> = emptyList(),
        val syncing: Boolean = false,
        val statusLine: String? = null,
    )

    private val _ui = MutableStateFlow(UiState())
    val ui: StateFlow<UiState> = _ui.asStateFlow()

    init {
        viewModelScope.launch { graph.epgRepository.ensureFeeds() }
        viewModelScope.launch {
            graph.epgRepository.observeFeeds().collect { feeds ->
                _ui.value = _ui.value.copy(feeds = feeds)
            }
        }
    }

    /** Changes whether a guide feed participates in refresh and guide queries. */
    fun setEnabled(feed: EpgFeed, enabled: Boolean) {
        viewModelScope.launch {
            graph.epgRepository.setFeedEnabled(feed.id, enabled)
            if (enabled) refresh() // turning a guide on should visibly do something
        }
    }

    /** Validates and adds a manually configured guide feed. */
    fun addCustom(name: String, url: String) {
        viewModelScope.launch {
            graph.epgRepository.addCustomFeed(name, url)
            refresh()
        }
    }

    /** Removes a guide feed and updates the configured feed list. */
    fun remove(feed: EpgFeed) {
        viewModelScope.launch { graph.epgRepository.removeFeed(feed) }
    }

    /** Starts a guide refresh and publishes progress and completion feedback. */
    fun refresh() {
        if (_ui.value.syncing) return
        viewModelScope.launch {
            _ui.value = _ui.value.copy(syncing = true, statusLine = "Downloading guides…")
            try {
                val summary = graph.epgRepository.syncAll(System.currentTimeMillis(), force = true,
                    onProgress = { line -> _ui.value = _ui.value.copy(statusLine = line) })
                _ui.value = _ui.value.copy(
                    statusLine = "Guide matched ${summary.channelsMatched} of " +
                        "${summary.channelsTotal} channels" +
                        (if (summary.feedsFailed > 0) " · ${summary.feedsFailed} feed(s) failed" else "") +
                        (if (summary.discoverySourcesFailed > 0) " · ${summary.discoverySourcesFailed} discovery source(s) unavailable" else ""),
                )
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (_: Exception) {
                _ui.value = _ui.value.copy(statusLine = "Guide configuration failed. Saved guides kept; try again.")
            } finally {
                _ui.value = _ui.value.copy(syncing = false)
            }
        }
    }
}

/** Combines cached movie and series catalogs, resume state and optional detail metadata. */
class VodViewModel(app: Application) : AndroidViewModel(app) {
    private val graph = ServiceLocator.get(app)
    private val settings = graph.settings

    /** One card in the Continue Watching shelf — a movie or episode with somewhere left to go. */
    data class ResumeItem(
        val mediaKey: String,
        val title: String,
        val posterUrl: String?,
        val streamUrl: String,
        val progress: Float,
    )

    @OptIn(ExperimentalCoroutinesApi::class)
    val continueWatching: StateFlow<List<ResumeItem>> =
        settings.activeProfileId
            .flatMapLatest { pid -> graph.playbackPositions.observeRecent(pid) }
            .mapLatest { positions -> positions.filter { !it.isFinished }.mapNotNull { resolveResume(it) } }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Resolves a saved playback position to the locally available movie or episode. */
    private suspend fun resolveResume(pos: PlaybackPosition): ResumeItem? {
        val parts = pos.mediaKey.split(":", limit = 2)
        if (parts.size != 2) return null
        val id = parts[1].toLongOrNull() ?: return null
        val progress =
            if (pos.durationMillis > 0) (pos.positionMillis.toFloat() / pos.durationMillis).coerceIn(0f, 1f) else 0f
        return when (parts[0]) {
            "movie" -> graph.catalogRepository.movie(id)?.let {
                ResumeItem(pos.mediaKey, it.displayTitle, it.posterUrl, it.streamUrl, progress)
            }
            "ep" -> graph.catalogRepository.episode(id)?.let {
                ResumeItem(
                    pos.mediaKey,
                    it.title.ifBlank { "S${it.season} E${it.episodeNumber}" },
                    it.stillUrl,
                    it.streamUrl,
                    progress,
                )
            }
            else -> null
        }
    }

    private val movieCategory = MutableStateFlow<String?>(null)
    private val seriesCategory = MutableStateFlow<String?>(null)

    /** Provider filter for Movies/Shows. null = every source. Surfaced only when there's >1 source. */
    val selectedVodSource = MutableStateFlow<Long?>(null)

    /** Providers, so Movies/Shows can offer a source filter (shown only when there's more than one). */
    val sources: StateFlow<List<Source>> =
        graph.sourceRepository.observeAll()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    // Category chips scoped to the chosen provider: pick a provider and you see only its categories,
    // and since a category id belongs to one provider, the grid you then open is already that
    // provider's titles. null folds categories across every provider ("All sources").
    val movieCategories: StateFlow<List<Category>> =
        combine(
            graph.catalogRepository.observeCategories(StreamKind.MOVIE),
            selectedVodSource,
        ) { raw, sourceId -> if (sourceId == null) raw else raw.filter { it.sourceId == sourceId } }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val seriesCategories: StateFlow<List<Category>> =
        combine(
            graph.catalogRepository.observeCategories(StreamKind.SERIES),
            selectedVodSource,
        ) { raw, sourceId -> if (sourceId == null) raw else raw.filter { it.sourceId == sourceId } }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    @OptIn(ExperimentalCoroutinesApi::class)
    val movies: StateFlow<List<Movie>> = movieCategory
        .flatMapLatest { graph.catalogRepository.observeMovies(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    @OptIn(ExperimentalCoroutinesApi::class)
    val series: StateFlow<List<Series>> = seriesCategory
        .flatMapLatest { graph.catalogRepository.observeSeries(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Changes the active movie catalog category. */
    fun selectMovieCategory(id: String?) { movieCategory.value = id }

    /** Changes the active series catalog category. */
    fun selectSeriesCategory(id: String?) { seriesCategory.value = id }

    /** True when at least one Stremio add-on is configured — gates the "add-on sources" button. */
    val hasAddons: StateFlow<Boolean> =
        settings.stremioAddons
            .map { it.isNotEmpty() }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), settings.stremioAddons.value.isNotEmpty())

    /**
     * The streams the configured add-ons offer for [movie], resolved through the user's TMDB key for
     * the IMDb id. Empty when there's no key, no add-ons, no IMDb match, or nothing came back. Each
     * add-on is queried independently so one failing doesn't sink the rest.
     */
    suspend fun addonStreams(movie: Movie): List<StremioStream> {
        val addons = settings.stremioAddons.value
        if (addons.isEmpty()) return emptyList()
        val imdb = graph.catalogRepository.imdbIdFor(movie) ?: return emptyList()
        return withContext(Dispatchers.IO) {
            addons.flatMap { addon ->
                runCatching { graph.stremioClient.streams(addon.manifestUrl, addon.name, "movie", imdb) }
                    .getOrDefault(emptyList())
            }
        }
    }

    /** Switch the Movies/Shows provider filter; category selections reset since they differ per source. */
    fun selectVodSource(sourceId: Long?) {
        selectedVodSource.value = sourceId
        movieCategory.value = null
        seriesCategory.value = null
    }

    // ---- Netflix-style home rows ----------------------------------------------------------------
    // "Recently added" is reactive: it fills in live as a VOD sync lands. The computed feeds
    // (recommended, by-genre) are held in the StateFlows below and (re)built by [loadHomeFeeds] from
    // one shared scan of the library — guarded so opening the tab twice, or recomposing, does not
    // re-scan a 20k-title catalogue; it recomputes only on a profile change or catalogue growth.

    val recentlyAddedMovies: StateFlow<List<Movie>> =
        graph.catalogRepository.recentlyAddedMovies()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val recentlyAddedSeries: StateFlow<List<Series>> =
        graph.catalogRepository.recentlyAddedSeries()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _recommendedMovies = MutableStateFlow<List<Movie>>(emptyList())
    val recommendedMovies: StateFlow<List<Movie>> = _recommendedMovies.asStateFlow()

    private val _movieGenreRows = MutableStateFlow<List<GenreGroup<Movie>>>(emptyList())
    val movieGenreRows: StateFlow<List<GenreGroup<Movie>>> = _movieGenreRows.asStateFlow()

    private val _seriesGenreRows = MutableStateFlow<List<GenreGroup<Series>>>(emptyList())
    val seriesGenreRows: StateFlow<List<GenreGroup<Series>>> = _seriesGenreRows.asStateFlow()

    /** The (profile, catalogue) the computed rows were last built for — the redundant-reload guard. */
    private data class HomeFeedsKey(val profileId: Long, val movieCount: Int, val seriesCount: Int)

    @Volatile private var loadedHomeFeeds: HomeFeedsKey? = null

    /** Serialises [loadHomeFeeds] so two near-simultaneous calls (screen open + the profile emit in
     *  `init`) can't both start the heavy scan — the second waits, sees the guard satisfied, returns.
     *  MUST be declared before the `init` block below: `activeProfileId` is a StateFlow, so its
     *  `collect` fires synchronously during construction, and Kotlin initialises properties top to
     *  bottom — declared after `init`, this mutex would still be null when the first collect runs. */
    private val homeFeedsMutex = Mutex()

    init {
        // The home feeds are per profile (Recommended) and per catalogue (the genre rows). Rebuild
        // them when the active profile changes — and once at start. Routed through the guarded
        // [loadHomeFeeds] so a profile switch triggers exactly one library scan, and re-opening the
        // tab with the same profile and an unchanged catalogue triggers none. Reads an empty result
        // until a VOD sync has populated the catalogue; [ensureVodLoaded] re-runs it once one has.
        viewModelScope.launch {
            settings.activeProfileId.collect { loadHomeFeeds() }
        }
    }

    /**
     * Recomputes the computed home rows (Recommended + by-genre) from a SINGLE scan of the library.
     *
     * The reactive rows (recently-added, continue-watching) keep themselves current, so this only
     * covers the computed feeds. On a 20k-title library the old version was the lag: it read the
     * whole movie table three times over (recommended + movie genres, plus a profile-change reload)
     * and again on every tab re-open. Now [allMovies]/[allSeries] are read once and reused for the
     * recommended row and every genre row, off the main thread, and a `(profile, movieCount,
     * seriesCount)` guard skips the work entirely unless the profile changed or the catalogue grew —
     * so re-opening Movies/Shows is free and a post-sync refresh still fills the rows in.
     */
    fun loadHomeFeeds() {
        viewModelScope.launch {
            homeFeedsMutex.withLock {
                val profileId = settings.activeProfileId.value
                val movieCount = runCatching { graph.catalogRepository.movieCount() }.getOrDefault(0)
                val seriesCount = runCatching { graph.catalogRepository.seriesCount() }.getOrDefault(0)

                // Already built for this profile and this exact catalogue? Then a tab re-open or a
                // recomposition must not trigger another full-library scan. Only a profile change or
                // a catalogue whose size moved gets past here.
                val prev = loadedHomeFeeds
                if (prev != null && prev.profileId == profileId &&
                    prev.movieCount == movieCount && prev.seriesCount == seriesCount
                ) return@withLock

                // One scan of each library (repo switches to IO), reused across every computed row.
                val allMovies = runCatching { graph.catalogRepository.allMovies() }.getOrDefault(emptyList())
                val allSeries = runCatching { graph.catalogRepository.allSeries() }.getOrDefault(emptyList())

                _recommendedMovies.value = runCatching {
                    graph.catalogRepository.recommendedMoviesFrom(allMovies, profileId)
                }.getOrDefault(emptyList())
                _movieGenreRows.value = runCatching {
                    graph.catalogRepository.moviesByGenreFrom(allMovies)
                }.getOrDefault(emptyList())
                _seriesGenreRows.value = runCatching {
                    graph.catalogRepository.seriesByGenreFrom(allSeries)
                }.getOrDefault(emptyList())

                loadedHomeFeeds = HomeFeedsKey(profileId, movieCount, seriesCount)
            }
        }
    }

    // Movies + series load on demand — the first time the user opens Movies or Shows — rather than
    // up front at login. A provider's 40,000-title VOD list is exactly what makes a first sync
    // crawl, and most sessions only ever watch live TV. Loaded once per app run.
    private val _vodLoading = MutableStateFlow(false)
    val vodLoading: StateFlow<Boolean> = _vodLoading.asStateFlow()

    @Volatile private var vodRequested = false

    /** How long a fetched VOD catalogue is trusted before a warm launch re-syncs it. */
    private val VOD_TTL_MILLIS = 12L * 60 * 60 * 1000  // 12 hours

    /** Loads provider VOD catalogs when the selected source needs them. */
    fun ensureVodLoaded() {
        if (vodRequested) return
        vodRequested = true
        viewModelScope.launch { syncVodIfStale(force = false) }
    }

    /**
     * Force a fresh download of the movies/series catalogue, ignoring the freshness cache. Wired
     * to the pull-to-refresh / refresh action so the user always has a way to pull new titles in
     * before the TTL lapses.
     */
    fun refreshVod() {
        vodRequested = true
        viewModelScope.launch { syncVodIfStale(force = true) }
    }

    /** Refreshes provider VOD data only when its cached catalog is stale. */
    private suspend fun syncVodIfStale(force: Boolean) {
        val now = System.currentTimeMillis()

        // Warm-launch fast path. The catalogue is persisted in Room, so once it has been fetched
        // there is no reason to re-download and re-upsert a 40k-title list on every launch — that
        // was both the multi-minute "Loading movies & shows…" and the bandwidth hog that starved
        // the live preview into buffering. When we synced recently and already have rows, skip the
        // network entirely and just (re)build the home shelves off what is stored.
        if (!force) {
            val haveCatalogue = runCatching {
                graph.catalogRepository.movieCount() + graph.catalogRepository.seriesCount()
            }.getOrDefault(0) > 0
            val last = settings.vodSyncedAtMillis
            val fresh = last > 0 && now - last < VOD_TTL_MILLIS
            if (haveCatalogue && fresh) {
                loadHomeFeeds()
                return
            }
        }

        _vodLoading.value = true
        val synced = StatusBus.during("Loading movies & shows…") {
            runCatching {
                for (source in graph.sourceRepository.enabled()) {
                    graph.catalogRepository.syncVod(source, now)
                }
            }.isSuccess
        }
        _vodLoading.value = false
        // Stamp the cache only when the fetch actually succeeded, so a failed sync retries on the
        // next open instead of being remembered as "fresh" and leaving the user with no catalogue.
        if (synced) settings.vodSyncedAtMillis = now
        // The catalogue may have grown — recompute the computed home rows off the fresh data.
        loadHomeFeeds()
    }

    // ---- VOD search (shared by the unified search screen) --------------------------------------
    private val vodSearchInput = MutableStateFlow("")

    /** Updates the movie and series catalog search text. */
    fun setVodSearchQuery(text: String) { vodSearchInput.value = text }

    @OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
    val movieResults: StateFlow<List<Movie>> =
        vodSearchInput.map { it.trim() }.debounce(200).distinctUntilChanged()
            .flatMapLatest { q ->
                if (q.length < 2) flowOf(emptyList()) else graph.catalogRepository.searchMovies(q)
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    @OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
    val seriesResults: StateFlow<List<Series>> =
        vodSearchInput.map { it.trim() }.debounce(200).distinctUntilChanged()
            .flatMapLatest { q ->
                if (q.length < 2) flowOf(emptyList()) else graph.catalogRepository.searchSeries(q)
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Toggles the selected movie's local favorite state. */
    fun toggleMovieFavourite(movie: Movie) {
        viewModelScope.launch {
            graph.catalogRepository.setMovieFavourite(movie.id, !movie.favourite)
        }
    }

    /**
     * Episodes are fetched on demand rather than during catalogue sync.
     *
     * Pulling every episode of every series up front is what makes a first sync take twenty
     * minutes on a large provider, and most of it is never looked at.
     */
    suspend fun loadEpisodes(series: Series) {
        val source = graph.sourceRepository.byId(series.sourceId) ?: return
        graph.catalogRepository.ensureEpisodes(source, series.seriesId)
    }

    /** Observes the selected series' provider episode list. */
    fun episodes(series: Series) =
        graph.catalogRepository.observeEpisodes(series.sourceId, series.seriesId)

    /** For the series detail screen: look the series up by row id. */
    fun seriesById(id: Long): kotlinx.coroutines.flow.Flow<Series?> =
        kotlinx.coroutines.flow.flow { emit(graph.catalogRepository.series(id)) }

    /** An always-empty episode flow, so the detail screen has something before a series loads. */
    val noEpisodes: kotlinx.coroutines.flow.Flow<List<app.opentv.data.model.Episode>> =
        kotlinx.coroutines.flow.flowOf(emptyList())

    /** Returns a movie from the local catalog by row ID. */
    suspend fun movieById(id: Long): Movie? = graph.catalogRepository.movie(id)

    // ---- Detail screens -------------------------------------------------------------------------
    // Thin wrappers over the repository's detail feeds. The screens drive them from `produceState`
    // keyed on the id, so each detail instance owns its own state and a "More like this" hop to
    // another title loads cleanly without two screens fighting over one shared StateFlow.

    /** Loads a movie, lazily enriching backdrop/cast/genre on first open. See CatalogRepository.movieDetail. */
    suspend fun movieDetail(id: Long): Movie? = graph.catalogRepository.movieDetail(id)

    /** Loads a series, lazily enriching backdrop/cast/genre on first open. See CatalogRepository.seriesDetail. */
    suspend fun seriesDetail(id: Long): app.opentv.data.repo.ProviderSeriesDetail? =
        graph.catalogRepository.seriesDetail(id)

    /** Loads series presentation details with optional external metadata. */
    suspend fun tvSeriesDetail(detail: app.opentv.data.repo.ProviderSeriesDetail): app.opentv.data.repo.TvSeriesDetail {
        val series = detail.series
        return graph.tvMetadata.detail(series, app.opentv.data.parser.VodTitleCleaner.clean(series.name), detail.imdbId) {
            graph.catalogRepository.tmdbSeriesFallback(series)
        }
    }

    /** Loads optional TV person metadata for the profile destination. */
    suspend fun tvPerson(id: Int) = graph.tvMetadata.profile(id)

    /** "More like this" for the movie detail screen. */
    suspend fun moreLikeThis(movie: Movie): List<Movie> = graph.catalogRepository.moreLikeThis(movie)

    /** "More like this" for the series detail screen. */
    suspend fun moreLikeThisSeries(series: Series): List<Series> =
        graph.catalogRepository.moreLikeThisSeries(series)

    /** Every library title (movies + series) featuring a person — the Person screen's grid. */
    suspend fun titlesWithPerson(name: String): List<PersonTitle> =
        graph.catalogRepository.titlesWithPerson(name)

    /** The active profile's saved resume position for a movie/episode, or null — drives Resume vs Watch now. */
    suspend fun resumePosition(mediaKey: String): PlaybackPosition? =
        graph.playbackPositions.get(settings.activeProfileId.value, mediaKey)

    /** Collapses quality variants of a movie list for a browse grid. See CatalogRepository.collapseVariants. */
    fun collapseVariants(movies: List<Movie>): List<MovieVariantGroup> =
        graph.catalogRepository.collapseVariants(movies)

    /** Star / un-star a whole series. Wraps the series DAO through the shared database — no data-layer change. */
    fun toggleSeriesFavourite(series: Series) {
        viewModelScope.launch {
            graph.database.series().setFavourite(series.id, !series.favourite)
        }
    }

    /** The user-agent to play a movie/episode with (per source). */
    suspend fun userAgentForSource(sourceId: Long): String =
        graph.sourceRepository.byId(sourceId)?.userAgent ?: "Nuvix/0.1 (Android)"
}

/**
 * Local viewing profiles. No accounts — just names on this device — with the active one stored in
 * [app.opentv.core.AppSettings] so the whole app agrees on whose watch history is in play.
 */
class ProfilesViewModel(app: Application) : AndroidViewModel(app) {
    private val graph = ServiceLocator.get(app)
    private val settings = graph.settings

    val profiles: StateFlow<List<Profile>> =
        graph.profiles.observeAll()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val activeProfileId: StateFlow<Long> = settings.activeProfileId

    init {
        // Fresh installs create the table empty (no migration runs), so seed the default profile.
        viewModelScope.launch {
            if (graph.profiles.all().isEmpty()) {
                graph.profiles.insert(Profile(name = "Me", createdAtMillis = 0))
            }
        }
    }

    /** Creates a local profile with the entered display name. */
    fun addProfile(name: String) {
        viewModelScope.launch {
            val id = graph.profiles.insert(
                Profile(name = name.trim().ifBlank { "New profile" }, createdAtMillis = 0),
            )
            settings.setActiveProfile(id)
        }
    }

    /** Renames the selected local profile. */
    fun rename(id: Long, name: String) {
        viewModelScope.launch { graph.profiles.rename(id, name.trim().ifBlank { "Profile" }) }
    }

    /** Persists the active local profile selection. */
    fun select(id: Long) { settings.setActiveProfile(id) }

    /** Deletes the selected local profile and updates active-profile state. */
    fun remove(id: Long) {
        // The base profile stays — there is always at least one to be.
        if (id == 1L) return
        viewModelScope.launch {
            graph.profiles.deletePositions(id)
            graph.profiles.delete(id)
            if (settings.activeProfileId.value == id) settings.setActiveProfile(1L)
        }
    }
}

/**
 * Local, device-to-device watch-history sync — no servers of ours.
 *
 * One device shares (a short-lived LAN server behind a six-digit code); the other receives by
 * typing that device's address and code. Only resume positions travel, keyed by stream URL and
 * profile name so they land on the right film and the right person on the other device. Merge is
 * newest-wins per item, so syncing both ways leaves the two devices agreeing.
 */
class SyncViewModel(app: Application) : AndroidViewModel(app) {
    private val graph = ServiceLocator.get(app)
    private val server = SyncServer(viewModelScope)
    private val client = SyncClient(graph.httpClient)

    val serverState: StateFlow<SyncServer.State> = server.state

    /** Progress and result of receiving a portable bundle from another LAN device. */
    sealed interface ReceiveState {
        /** Receive state before a LAN transfer begins. */
        data object Idle : ReceiveState
        /** Receive state while connecting to the selected sharing device. */
        data object Connecting : ReceiveState
        /** Completed receive state with the number of merged changes. */
        data class Done(val merged: Int) : ReceiveState
        /** Receive failure with an explanation suitable for display. */
        data class Failed(val message: String) : ReceiveState
    }

    private val _receiveState = MutableStateFlow<ReceiveState>(ReceiveState.Idle)
    val receiveState: StateFlow<ReceiveState> = _receiveState.asStateFlow()

    // ---- NAS ("cloud") sync ------------------------------------------------------------------

    private val nas = NasSync(graph)

    /** Whether an SMB/NAS is set up at all — the NAS pane needs one before it can do anything. */
    val smbHost: StateFlow<String> = graph.settings.smbHost
    val nasAutoSync: StateFlow<Boolean> = graph.settings.nasAutoSync

    /** Progress and result of exchanging portable state through NAS storage. */
    sealed interface NasState {
        /** NAS state before synchronization begins. */
        data object Idle : NasState
        /** NAS state while the portable bundle is being exchanged. */
        data object Syncing : NasState
        /** Completed NAS synchronization with a display message. */
        data class Done(val message: String) : NasState
        /** NAS synchronization failure with a display message. */
        data class Failed(val message: String) : NasState
    }

    private val _nasState = MutableStateFlow<NasState>(NasState.Idle)
    val nasState: StateFlow<NasState> = _nasState.asStateFlow()

    /** Persists the automatic NAS synchronization choice. */
    fun setNasAutoSync(enabled: Boolean) = graph.settings.setNasAutoSync(enabled)

    /** Starts a NAS synchronization exchange and publishes its result. */
    fun syncNas() {
        if (_nasState.value is NasState.Syncing) return
        viewModelScope.launch {
            _nasState.value = NasState.Syncing
            val ctx = getApplication<Application>()
            _nasState.value = when (val result = nas.sync()) {
                is NasSync.Result.Success ->
                    if (result.peers == 0) NasState.Done(ctx.getString(R.string.sync_nas_uploaded))
                    else NasState.Done(ctx.getString(R.string.sync_nas_done, result.merged, result.peers))
                is NasSync.Result.NotConfigured ->
                    NasState.Failed(ctx.getString(R.string.sync_nas_needs_setup))
                is NasSync.Result.Failed ->
                    NasState.Failed(ctx.getString(R.string.sync_nas_failed))
            }
        }
    }

    /** Collects portable user state and starts a temporary LAN sharing session. */
    fun startSharing() {
        viewModelScope.launch { server.start(buildBundleJson()) }
    }

    /** Closes the active LAN sharing session. */
    fun stopSharing() = server.stop()

    /** Connects to the entered LAN session and merges its portable user-state bundle. */
    fun receive(address: String, code: String) {
        viewModelScope.launch {
            _receiveState.value = ReceiveState.Connecting
            client.pull(address, code)
                .onSuccess { _receiveState.value = ReceiveState.Done(merge(it)) }
                .onFailure { _receiveState.value = ReceiveState.Failed(it.message ?: "Sync failed.") }
        }
    }

    /** Clears the previous LAN receive progress and result. */
    fun resetReceive() { _receiveState.value = ReceiveState.Idle }

    /** Collects and serializes the current portable local user state. */
    private suspend fun buildBundleJson(): String {
        val names = graph.profiles.all().associate { it.id to it.name }
        val items = graph.playbackPositions.all().mapNotNull { pos ->
            val profileName = names[pos.profileId] ?: return@mapNotNull null
            val parts = pos.mediaKey.split(":", limit = 2)
            if (parts.size != 2) return@mapNotNull null
            val id = parts[1].toLongOrNull() ?: return@mapNotNull null
            val streamUrl = when (parts[0]) {
                "movie" -> graph.catalogRepository.movie(id)?.streamUrl
                "ep" -> graph.catalogRepository.episode(id)?.streamUrl
                else -> null
            } ?: return@mapNotNull null
            SyncPosition(profileName, streamUrl, parts[0], pos.positionMillis, pos.durationMillis, pos.updatedAtMillis)
        }
        return Json.encodeToString(SyncBundle.serializer(), SyncBundle(positions = items))
    }

    /** Merges a received portable user-state bundle into matching local rows. */
    private suspend fun merge(bundle: SyncBundle): Int {
        var merged = 0
        for (item in bundle.positions) {
            val profileId = profileIdFor(item.profile) ?: continue
            val localId = when (item.kind) {
                "movie" -> graph.catalogRepository.movieByStreamUrl(item.streamUrl)?.id
                "ep" -> graph.catalogRepository.episodeByStreamUrl(item.streamUrl)?.id
                else -> null
            } ?: continue
            val mediaKey = "${item.kind}:$localId"
            val existing = graph.playbackPositions.get(profileId, mediaKey)
            if (existing == null || item.updatedAtMillis > existing.updatedAtMillis) {
                graph.playbackPositions.upsert(
                    PlaybackPosition(
                        profileId = profileId,
                        mediaKey = mediaKey,
                        positionMillis = item.positionMillis,
                        durationMillis = item.durationMillis,
                        updatedAtMillis = item.updatedAtMillis,
                    ),
                )
                merged++
            }
        }
        return merged
    }

    /** Resolves a received profile name to a local profile ID. */
    private suspend fun profileIdFor(name: String): Long? {
        graph.profiles.byName(name)?.let { return it.id }
        return runCatching { graph.profiles.insert(Profile(name = name, createdAtMillis = 0)) }.getOrNull()
    }

    /** Closes the temporary LAN sharing server when the view model is cleared. */
    override fun onCleared() {
        server.stop()
    }
}

/**
 * Owns the "manage channels from your phone or laptop" web server for the manage screen.
 *
 * Same ownership shape as [SyncViewModel]: the server is bound to [viewModelScope] (so its idle
 * watchdog is torn down with the screen) and stopped in [onCleared]. The screen also starts it on
 * open and stops it on leave, mirroring the phone-pairing lifecycle — so no socket is ever left
 * listening on the user's network once they navigate away.
 */
class WebManagerViewModel(app: Application) : AndroidViewModel(app) {
    private val graph = ServiceLocator.get(app)
    private val server = ManagerServer(viewModelScope, graph.catalogRepository, graph.settings, graph.sourceRepository)

    val state: StateFlow<ManagerServer.State> = server.state

    /** Starts the local web manager and exposes its session to the settings UI. */
    fun start() = server.start()

    /** Stops the local web manager and clears the displayed session. */
    fun stop() = server.stop()

    /** Closes the web manager when its owning view model is cleared. */
    override fun onCleared() {
        server.stop()
    }
}
