/* Nuvix modifications Copyright (C) 2026 Nuvix Contributors. GPL-3.0-or-later. */
package app.opentv.channels

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Provider-scoped identity; category names and folded display labels are never identities. */
data class ProviderGroupKey(val sourceId: Long, val categoryId: String)

/** Only display metadata crosses this boundary. No Source, credentials, channel or URL is retained. */
data class EnglishProviderGroup(
    val key: ProviderGroupKey,
    val providerName: String,
    val originalName: String,
    val channelCount: Int,
    val catalogRevision: Long,
)
data class EnglishGroupSuggestion(val group: EnglishProviderGroup, val classification: EnglishClassification)
data class EnglishGroupState(
    val scanning: Boolean = false,
    val scanned: Boolean = false,
    val catalogChanged: Boolean = false,
    val suggestions: List<EnglishGroupSuggestion> = emptyList(),
    val selected: Set<ProviderGroupKey> = emptySet(),
    /** null means show every group; an empty selection cannot be applied. */
    val activeFilter: Set<ProviderGroupKey>? = null,
)

/** Main-thread session owner. Draft selection is independent of the explicitly confirmed filter. */
class EnglishGroupSession(
    private val scope: CoroutineScope,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
) {
    private var catalog: List<EnglishProviderGroup> = emptyList()
    private var generation = 0L
    private var scanJob: Job? = null
    private val mutableState = MutableStateFlow(EnglishGroupState())
    val state = mutableState.asStateFlow()

    fun updateCatalog(groups: List<EnglishProviderGroup>) {
        if (catalog == groups) return
        catalog = groups.toList()
        generation++
        scanJob?.cancel()
        // A refresh can reuse an ID for a renamed group; reset conservatively, including drafts.
        val hadWork = state.value.scanned || state.value.scanning || state.value.activeFilter != null
        mutableState.value = EnglishGroupState(catalogChanged = hadWork)
    }

    fun scan() {
        if (state.value.scanning) return
        val snapshot = catalog
        val ticket = ++generation
        mutableState.value = state.value.copy(scanning = true, scanned = false, catalogChanged = false, suggestions = emptyList(), selected = emptySet())
        scanJob = scope.launch {
            val results = withContext(dispatcher) {
                snapshot.map { group ->
                    ensureActive()
                    EnglishGroupSuggestion(group, EnglishGroupDetector.classify(group.originalName))
                }
            }
            if (ticket == generation) mutableState.value = state.value.copy(scanning = false, scanned = true, suggestions = results)
        }
    }

    fun toggle(key: ProviderGroupKey) {
        if (!state.value.scanned || state.value.suggestions.none { it.group.key == key }) return
        val selected = state.value.selected
        mutableState.value = state.value.copy(selected = if (key in selected) selected - key else selected + key)
    }
    fun selectLikely() {
        mutableState.value = state.value.copy(selected = state.value.selected + state.value.suggestions
            .filter { it.classification.confidence == EnglishConfidence.LIKELY }.map { it.group.key })
    }
    fun clearSelection() { mutableState.value = state.value.copy(selected = emptySet()) }
    fun apply(): Boolean {
        val current = state.value
        if (!current.scanned || current.scanning || current.selected.isEmpty()) return false
        mutableState.value = current.copy(activeFilter = current.selected.toSet())
        return true
    }
    fun clearFilter() { mutableState.value = state.value.copy(activeFilter = null) }
    fun cancelReview() {
        generation++
        scanJob?.cancel()
        mutableState.value = state.value.copy(scanning = false, selected = state.value.activeFilter.orEmpty())
    }
    fun beginReview() { mutableState.value = state.value.copy(selected = state.value.activeFilter.orEmpty()) }
    fun close() {
        generation++
        scanJob?.cancel()
        catalog = emptyList()
        mutableState.value = EnglishGroupState()
    }
}
