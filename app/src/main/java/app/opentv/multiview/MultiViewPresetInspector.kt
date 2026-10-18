/* Nuvix Copyright (C) 2026 Nuvix Contributors. GPL-3.0-or-later. */
package app.opentv.multiview

import app.opentv.core.ServiceLocator
import app.opentv.data.model.StreamKind
import app.opentv.data.model.shownName
import app.opentv.data.repo.LocalCustomGroups
import app.opentv.recording.MultiViewRecordingConflictPolicy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Local database adapter. Never resolve a playback URL, refresh EPG, or allocate a player. */
class MultiViewPresetInspector(
    private val graph: ServiceLocator.Graph,
    private val replacedReservations: () -> Set<String> = { emptySet() },
    private val now: () -> Long = System::currentTimeMillis,
    private val window: RecordingPlanningWindow = RecordingPlanningWindow(),
) {
    suspend fun inspect(proposal: MultiViewPresentation): MultiViewPresetInspection = inspectAll(listOf(proposal)).single()

    /** Alternatives share one schedule/catalog snapshot; database work does not grow per option. */
    suspend fun inspectAll(proposals: List<MultiViewPresentation>): List<MultiViewPresetInspection> {
        require(proposals.size <= 40 && proposals.all { it.panels.size <= 4 })
        if (proposals.isEmpty()) return emptyList()
        // Manager references belong to the main dispatcher; capture before offloading DB/CPU work.
        val replaced = replacedReservations()
        return withContext(Dispatchers.IO) {
            val at = now()
            val sources = graph.sourceRepository.enabled().associateBy { it.id }
            sources.values.forEach { graph.connections.registerSource(it) }
            val categories = graph.database.categories().allByKind(StreamKind.LIVE)
            val channels = proposals.flatMap { it.panels }.map { it.channel }.distinctBy { it.channelId to it.sourceId }.mapNotNull { ref ->
                val channel = graph.database.channels().byId(ref.channelId) ?: return@mapNotNull null
                val source = sources[ref.sourceId] ?: return@mapNotNull null
                if (channel.sourceId != source.id || channel.hidden || !LocalCustomGroups.visible(channel, categories,
                        graph.settings.hiddenCategories.value, graph.settings.hiddenUnlocked.value)) return@mapNotNull null
                MultiViewPreviewChannel(channel.id, source.id, channel.shownName, source.name)
            }
            val rows = graph.recordingRepository.planningWindow(at, window.end(at))
            check(rows.size <= 2000) { "Planning window overflow" }
            val accounts = graph.connections.snapshots.value
            val availableRows = rows.filter { it.sourceId in sources }
            val effectivePlan = MultiViewRecordingConflictPolicy.plan(availableRows, accounts, at)
            proposals.map { proposal ->
                val selected = channels.filter { channel -> proposal.panels.any { it.channel.channelId == channel.channelId && it.channel.sourceId == channel.sourceId } }
                val plan = MultiViewPresetRecordingPlanner.plan(proposal, selected, accounts,
                    availableRows, at, replaced, window, effectivePlan)
                // Pin freshly authorized account identities even for legacy presets without an account key.
                val pinned = proposal.copy(panels = proposal.panels.map { panel ->
                    val key = accounts.firstOrNull { panel.channel.sourceId in it.sourceIds }?.accountKey
                    panel.copy(channel = panel.channel.copy(accountId = panel.channel.accountId ?: key))
                })
                MultiViewPresetInspection(pinned, selected, plan)
            }
        }
    }
}
