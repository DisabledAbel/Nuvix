/* Copyright (C) 2026 Nuvix Contributors. Licensed under GPL-3.0-or-later. */
package app.opentv.ui.channels

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.opentv.core.ServiceLocator
import app.opentv.data.db.ProgrammeSearchResult
import app.opentv.data.model.StreamKind
import app.opentv.data.parser.ChannelNameNormalizer
import app.opentv.data.repo.EpgSearchFilter
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn

/** Uses the same repository and remote keyboard as catalogue search, with cancellable queries. */
class EpgSearchViewModel(app: Application) : AndroidViewModel(app) {
    private val graph = ServiceLocator.get(app)
    private val input = MutableStateFlow("")
    private val filter = MutableStateFlow(EpgSearchFilter.ALL)

    /** Bounded program search results with loading and query feedback state. */
    data class State(val results: List<ProgrammeSearchResult> = emptyList(), val loading: Boolean = false)

    /** Updates the program search request and its selected filters. */
    fun search(query: String, selectedFilter: EpgSearchFilter) {
        input.value = query
        filter.value = selectedFilter
    }

    // Respect the same locked category groups used by the guide, even when searching all sources.
    private val hiddenCategories = combine(
        graph.catalogRepository.observeCategories(StreamKind.LIVE),
        graph.settings.hiddenCategories,
        graph.settings.hiddenUnlocked,
    ) { categories, hidden, unlocked ->
        if (unlocked) emptySet() else categories.filter {
            ChannelNameNormalizer.normalize(it.name).groupKey.ifEmpty { it.id } in hidden
        }.map { it.sourceId to it.id }.toSet()
    }

    @OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
    private val searchInput = combine(input.map { it.trim() }.debounce(200).distinctUntilChanged(), filter, flow {
        while (true) {
            emit(System.currentTimeMillis())
            delay(60_000)
        }
    }) { q, f, now -> Triple(q, f, now) }

    @OptIn(ExperimentalCoroutinesApi::class)
    val state = combine(searchInput, hiddenCategories) { input, hidden -> input to hidden }
        .flatMapLatest { (input, hidden) ->
            val (q, f, now) = input
            graph.epgRepository.programmeSearch(q, f, now, excludedCategories = hidden)
                .map { State(it) }
                .onStart { if (q.length >= 2) emit(State(loading = true)) }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), State())
}
