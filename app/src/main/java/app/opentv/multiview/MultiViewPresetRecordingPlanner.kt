/* Nuvix Copyright (C) 2026 Nuvix Contributors. GPL-3.0-or-later. */
package app.opentv.multiview

import app.opentv.connections.*
import app.opentv.data.model.Recording
import app.opentv.recording.MultiViewRecordingConflictPolicy
import app.opentv.recording.DvrConflictPlan

/** A reusable, internally configurable horizon. Prediction never owns future capacity. */
data class RecordingPlanningWindow(val durationMillis: Long = 24 * 60 * 60_000L) {
    init { require(durationMillis in 1..7 * 24 * 60 * 60_000L) }
    fun end(now: Long): Long = now + durationMillis
}

enum class PresetConflictKind { NO_KNOWN_CONFLICT, UPCOMING, IMMEDIATE, UNKNOWN_CAPACITY, UNCERTAIN_AVAILABILITY }
enum class PresetAllowanceKind { PROVIDER_REPORTED, CONFIGURED, UNKNOWN }

/** Deliberately excludes recording URLs, output locators, credentials and reservation tokens. */
data class PresetRecordingWindow(
    val id: Long, val title: String, val channelName: String, val sourceId: Long,
    val startMillis: Long, val endMillis: Long, val protectedRecording: Boolean,
    val lockPadding: Boolean, val automaticSeries: Boolean, val paddingAdjusted: Boolean,
)
data class PresetRecordingConflict(
    val kind: PresetConflictKind, val atMillis: Long, val untilMillis: Long,
    val recordingIds: List<Long>, val panelIds: List<String>, val accountId: String,
)
data class PresetAccountForecast(
    val accountId: String, val sourceIds: Set<Long>, val currentLocal: Int,
    val retainedLocal: Int, val proposedAdditional: Int, val maximum: Int?,
    val allowance: PresetAllowanceKind, val currentCapacityExceeded: Boolean,
)
data class PresetRecordingPlan(
    val accounts: List<PresetAccountForecast>, val recordings: List<PresetRecordingWindow>,
    val conflicts: List<PresetRecordingConflict>, val unavailablePanelIds: Set<String>,
) {
    val currentCapacityExceeded get() = accounts.any { it.currentCapacityExceeded }
    val classifications: Set<PresetConflictKind> get() = buildSet {
        add(PresetConflictKind.UNCERTAIN_AVAILABILITY) // Remote usage is never a local guarantee.
        if (accounts.any { it.maximum == null }) add(PresetConflictKind.UNKNOWN_CAPACITY)
        addAll(conflicts.map { it.kind })
        if (currentCapacityExceeded) add(PresetConflictKind.IMMEDIATE)
        if (conflicts.isEmpty() && !currentCapacityExceeded && unavailablePanelIds.isEmpty()) add(PresetConflictKind.NO_KNOWN_CONFLICT)
    }
}

/**
 * Read-only projection: reuse effective DVR windows and the deadline warning's victim policy.
 * Synthetic tokens are values only; no allocator, player, stream resolver or network is called.
 * Current wall tokens are excluded only when the caller guarantees restore will close them.
 */
object MultiViewPresetRecordingPlanner {
    fun plan(
        presentation: MultiViewPresentation,
        channels: List<MultiViewPreviewChannel>,
        accounts: List<AccountConnectionSnapshot>,
        recordings: List<Recording>,
        now: Long,
        replacedReservationIds: Set<String> = emptySet(),
        window: RecordingPlanningWindow = RecordingPlanningWindow(),
        effectivePlan: DvrConflictPlan? = null,
    ): PresetRecordingPlan {
        val end = window.end(now)
        val bySource = accounts.flatMap { a -> a.sourceIds.map { it to a } }.toMap()
        val catalog = channels.associateBy { it.channelId to it.sourceId }
        val duplicates = presentation.panels.groupingBy { it.channel.channelId }.eachCount().filterValues { it > 1 }.keys
        val enabled = presentation.panels.filter { panel ->
            val ref = panel.channel
            ref.channelId !in duplicates && catalog.containsKey(ref.channelId to ref.sourceId) && bySource[ref.sourceId]?.let {
                ref.accountId == null || ref.accountId == it.accountKey
            } == true
        }
        val unavailable = presentation.panels.map { it.panelId }.toSet() - enabled.map { it.panelId }.toSet()
        val dvr = effectivePlan ?: MultiViewRecordingConflictPolicy.plan(recordings, accounts, now)
        val relevant = recordings.filter { rec -> dvr.windows[rec.id]?.let { it.startMillis < end && it.endMillis > now } == true }
        val rowsById = recordings.associateBy { it.id }
        val forecasts = mutableListOf<PresetAccountForecast>()
        val conflicts = mutableListOf<PresetRecordingConflict>()
        val relevantIds = mutableSetOf<Long>()
        enabled.groupBy { bySource.getValue(it.channel.sourceId).accountKey }.forEach { (key, panels) ->
            val account = bySource.getValue(panels.first().channel.sourceId)
            val retained = account.reservations.filterNot { it.token.reservationId in replacedReservationIds }
            val proposed = panels.map { panel -> ConnectionReservation(
                ReservationToken("preset:${panel.panelId}", "preset:${panel.panelId}", 0,
                    panel.channel.sourceId, key, ConnectionPurpose.MULTIVIEW), ReservationState.PENDING) }
            val pool = account.copy(reservations = retained + proposed)
            val members = relevant.filter { bySource[it.sourceId]?.accountKey == key }
            relevantIds += members.map { it.id }
            // Exact service session identities prevent counting a running capture twice or
            // keeping its known lease forever after its planned end. Unknown owners stay occupied.
            val untracked = retained.count { reservation ->
                reservation.token.purpose == ConnectionPurpose.RECORDING &&
                    reservation.token.sessionId.removePrefix("recording:").toLongOrNull()?.let(rowsById::get) == null
            }
            val times = (listOf(now, end) + members.flatMap { rec ->
                dvr.windows.getValue(rec.id).let { listOf(it.startMillis, it.endMillis) }
            }.filter { it > now && it < end }).distinct().sorted()
            val currentDemand = members.count { rec -> dvr.windows.getValue(rec.id).let { now >= it.startMillis && now < it.endMillis } } + untracked
            val localRecorders = retained.count { it.token.purpose == ConnectionPurpose.RECORDING }
            val currentTotal = retained.size - localRecorders + maxOf(currentDemand, localRecorders) + panels.size
            forecasts += PresetAccountForecast(key, account.sourceIds, account.locallyReserved, retained.size,
                panels.size, account.effectiveMaximum,
                when { account.effectiveMaximum == null -> PresetAllowanceKind.UNKNOWN
                    account.policy.useProviderMetadata -> PresetAllowanceKind.PROVIDER_REPORTED
                    else -> PresetAllowanceKind.CONFIGURED },
                account.effectiveMaximum?.let { currentTotal > it } ?: false)
            times.zipWithNext().forEach interval@{ (at, until) ->
                val active = members.filter { rec -> dvr.windows.getValue(rec.id).let { at >= it.startMillis && at < it.endMillis } }
                if (active.isEmpty()) return@interval
                val demand = active.size + untracked
                val victims = MultiViewRecordingConflictPolicy.viewingVictims(pool, demand, "preset:${presentation.primaryPanelId}")
                val affected = panels.filter { panel -> victims.any { it.reservationId == "preset:${panel.panelId}" } }.map { it.panelId }
                if (affected.isNotEmpty()) conflicts += PresetRecordingConflict(
                    if (at == now) PresetConflictKind.IMMEDIATE else PresetConflictKind.UPCOMING,
                    // Stable boundary during countdown, rather than a different warning every tick.
                    if (at == now) active.maxOf { dvr.windows.getValue(it.id).startMillis } else at,
                    until, active.map { it.id }.sorted(), affected, key)
            }
        }
        return PresetRecordingPlan(forecasts, relevant.filter { it.id in relevantIds }.map { rec ->
            val capture = dvr.windows.getValue(rec.id)
            PresetRecordingWindow(rec.id, rec.title, rec.channelName, rec.sourceId, capture.startMillis,
                capture.endMillis, rec.protectedRecording, rec.lockPadding, rec.seriesRuleId != null,
                dvr.adjustments.any { it.recordingId == rec.id })
        }.sortedWith(compareBy<PresetRecordingWindow> { it.startMillis }.thenBy { it.id }), conflicts, unavailable)
    }
}

/** One-time edits are immutable; persistence is an explicit, separate store operation. */
fun MultiViewPresentation.withoutPanel(id: String): MultiViewPresentation {
    val remaining = panels.filterNot { it.panelId == id }
    return copy(panels = remaining, primaryPanelId = primaryPanelId.takeIf { it != id } ?: remaining.firstOrNull()?.panelId.orEmpty(),
        audioPanelId = audioPanelId?.takeIf { it != id })
}
fun MultiViewPresentation.withChannel(id: String, channel: MultiViewChannelReference): MultiViewPresentation =
    copy(panels = panels.map { if (it.panelId == id) it.copy(channel = channel) else it })
