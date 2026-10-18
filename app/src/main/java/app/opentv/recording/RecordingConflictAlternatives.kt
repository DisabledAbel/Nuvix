/* Nuvix Copyright (C) 2026 Nuvix Contributors. GPL-3.0-or-later. */
package app.opentv.recording

import app.opentv.core.ServiceLocator
import app.opentv.connections.AccountConnectionSnapshot
import app.opentv.connections.ConnectionPurpose
import app.opentv.data.model.Channel
import app.opentv.data.model.StreamKind
import app.opentv.data.repo.LocalCustomGroups
import kotlinx.coroutines.flow.first

data class RecordingConflictAlternative(val channel: Channel, val providerName: String, val capacity: String, val verified: Boolean)

/** Inferred name matches are suggestions, not sufficient identity for a conflict replacement. */
internal fun exactGuideBroadcast(first: Channel, second: Channel): Boolean {
    fun identity(channel: Channel) = channel.epgOverrideId?.takeIf { it.isNotBlank() }
        ?: channel.epgChannelId?.takeIf { it.isNotBlank() }
    return identity(first)?.let { it == identity(second) } == true
}

internal suspend fun eligibleRecordingAlternatives(
    current: Channel,
    candidates: List<Pair<Channel, Boolean>>,
    conflictingAccount: String,
    visible: (Channel) -> Boolean,
    provider: suspend (Long) -> Pair<String, AccountConnectionSnapshot>?,
    scheduledDemand: (Long) -> Int = { 0 },
): List<RecordingConflictAlternative> = candidates.distinctBy { it.first.id }.mapNotNull { (channel, verified) ->
    if (channel.id == current.id || (!verified && !exactGuideBroadcast(current, channel)) || !visible(channel)) return@mapNotNull null
    val (providerName, account) = provider(channel.sourceId) ?: return@mapNotNull null
    if (account.accountKey == conflictingAccount) return@mapNotNull null
    val maximum = account.effectiveMaximum
    if (maximum != null && account.locallyReserved >= maximum) return@mapNotNull null
    val recordingDemand = maxOf(scheduledDemand(channel.sourceId), account.reservations.count { it.token.purpose == ConnectionPurpose.RECORDING })
    val optional = account.reservations.count { it.token.purpose != ConnectionPurpose.RECORDING }
    if (maximum?.let { optional + recordingDemand >= it } ?: (scheduledDemand(channel.sourceId) > 0)) return@mapNotNull null
    RecordingConflictAlternative(channel, providerName,
        maximum?.let { "${account.locallyReserved}/$it local connections reserved" }
            ?: "Unknown allowance · additional connection requires confirmation", verified)
}.sortedWith(compareByDescending<RecordingConflictAlternative> { it.verified }.thenBy { it.providerName }.thenBy { it.channel.id })

/** Conservative matching reuses catalog variants and Sports Hub's authorization at click time. */
class RecordingConflictAlternatives(private val graph: ServiceLocator.Graph) {
    suspend fun choices(channelId: Long, conflictingAccount: String, at: Long, deadline: Long = at,
        planningUntil: Long? = null, ignoredReservations: Set<String> = emptySet()): List<RecordingConflictAlternative> {
        val current = graph.catalogRepository.channel(channelId) ?: return emptyList()
        val candidates = linkedMapOf<Long, Pair<Channel, Boolean>>()
        // A groupKey is normalized from names and is not sufficient broadcast identity.
        graph.catalogRepository.recordSourceOptions(current).filter { candidate ->
            candidate.id != current.id && exactGuideBroadcast(current, candidate)
        }.forEach { candidates[it.id] = it to false }
        for (eventId in graph.sportsRepository.dao.currentEventIds(current.epgCandidates, at)) {
            val streams = graph.sportsRepository.streams(eventId).first()
            val original = streams.firstOrNull { it.channel.id == current.id && it.available } ?: continue
            if (graph.sportsRepository.playable(eventId, original) == null) continue
            for (stream in streams.filter { it.available && it.channel.id != current.id }) {
                graph.sportsRepository.playable(eventId, stream)?.let { candidates[it.id] = it to true }
            }
        }
        val sources = graph.sourceRepository.enabled().associateBy { it.id }
        sources.values.forEach { graph.connections.registerSource(it) }
        val rows = if (planningUntil == null) graph.recordingRepository.scheduled() + graph.recordingRepository.active()
            else graph.recordingRepository.planningWindow(at, planningUntil).also { check(it.size <= 2000) }
        val recordings = rows.filter { it.sourceId in sources }
        val plan = DvrConflictPolicy.resolve(recordings, at,
            { graph.connections.accountKeyForSource(it) ?: "unavailable:$it" },
            { key -> graph.connections.snapshots.value.firstOrNull { it.accountKey == key }?.effectiveMaximum })
        val scheduledByAccount = recordings.filter { rec ->
            plan.windows[rec.id]?.let { deadline >= it.startMillis && deadline < it.endMillis } == true
        }.groupingBy { graph.connections.accountKeyForSource(it.sourceId) }.eachCount()
        val categories = graph.database.categories().allByKind(StreamKind.LIVE)
        return eligibleRecordingAlternatives(current, candidates.values.toList(), conflictingAccount,
            visible = { LocalCustomGroups.visible(it, categories, graph.settings.hiddenCategories.value, graph.settings.hiddenUnlocked.value) },
            provider = provider@{ sourceId ->
            val source = sources[sourceId] ?: return@provider null
            graph.connections.registerSource(source)
            graph.connections.snapshotForSource(source.id)?.let { account ->
                source.name to account.copy(reservations = account.reservations.filterNot { it.token.reservationId in ignoredReservations })
            }
        }, scheduledDemand = { scheduledByAccount[graph.connections.accountKeyForSource(it)] ?: 0 })
    }
}
