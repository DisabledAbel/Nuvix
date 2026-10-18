/* Nuvix modifications Copyright (C) 2026 Nuvix Contributors. GPL-3.0-or-later. */
package app.opentv.recording

import app.opentv.data.model.Recording
import app.opentv.data.model.RecordingStatus

/** Recording capture interval expressed as UTC epoch milliseconds. */
data class DvrWindow(val startMillis: Long, val endMillis: Long)

/** Proposed change from a recording's original capture window to its trimmed window. */
data class DvrPaddingAdjustment(val recordingId: Long, val before: DvrWindow, val after: DvrWindow)

/** Resolved recording windows, rejected bookings and proposed padding adjustments. */
data class DvrConflictPlan(
    val windows: Map<Long, DvrWindow>,
    val rejectedIds: Set<Long>,
    /** Unknown capacity needs the connection manager's request-specific approval. */
    val unknownCapacityIds: Set<Long>,
    val adjustments: List<DvrPaddingAdjustment>,
)

/**
 * Pure unattended fallback, also used to preview an explicit conflict decision. Programme airtime
 * is allocated before optional padding. A padding lock makes the entire window required; it never
 * silently changes. Source aliases must resolve to the same account key before calling this policy.
 * A plan does not reserve connections or authorize an unknown account allowance.
 */
object DvrConflictPolicy {
    val priority: Comparator<Recording> =
        compareByDescending<Recording> { it.protectedRecording }
            .thenByDescending { it.seriesRuleId == null }
            .thenByDescending { it.status in activeStatuses }
            .thenBy { it.scheduledStartMillis }
            .thenBy { it.id }

    /** Plans account-capacity conflicts using recording priority and permitted padding trims. */
    fun resolve(
        recordings: List<Recording>,
        nowMillis: Long,
        accountForSource: (Long) -> String,
        capacityForAccount: (String) -> Int?,
    ): DvrConflictPlan {
        val windows = linkedMapOf<Long, DvrWindow>()
        val rejected = linkedSetOf<Long>()
        val unknown = linkedSetOf<Long>()
        val adjustments = mutableListOf<DvrPaddingAdjustment>()
        recordings.filter { it.status in pendingStatuses }.groupBy { accountForSource(it.sourceId) }
            .toSortedMap().forEach { (account, members) ->
                val ordered = members.sortedWith(priority)
                val capacity = capacityForAccount(account)
                if (capacity == null) {
                    ordered.forEach { rec -> windows[rec.id] = requestedWindow(rec, nowMillis); unknown += rec.id }
                    return@forEach
                }

                val accepted = mutableListOf<Recording>()
                val accountWindows = linkedMapOf<Long, DvrWindow>()
                // Allocate required airtime first. Legacy rows have no core metadata, so their
                // existing windows are preserved rather than guessing which minutes are padding.
                ordered.forEach { rec ->
                    val required = requiredWindow(rec, nowMillis)
                    if (capacity <= 0 || saturated(accountWindows.values, capacity, nowMillis).any { overlaps(it, required, nowMillis) }) {
                        rejected += rec.id
                    } else {
                        accountWindows[rec.id] = required
                        accepted += rec
                    }
                }
                // Extend into optional padding only as far as the other required windows permit.
                accepted.forEach { rec ->
                    val requested = requestedWindow(rec, nowMillis)
                    val required = accountWindows.getValue(rec.id)
                    if (!rec.lockPadding) {
                        val blocked = saturated(accountWindows.filterKeys { it != rec.id }.values, capacity, nowMillis)
                        val start = blocked.filter { it.endMillis > requested.startMillis && it.startMillis < required.startMillis }
                            .maxOfOrNull { it.endMillis.coerceAtMost(required.startMillis) }
                            ?.coerceAtLeast(requested.startMillis) ?: requested.startMillis
                        val end = blocked.filter { it.startMillis < requested.endMillis && it.endMillis > required.endMillis }
                            .minOfOrNull { it.startMillis.coerceAtLeast(required.endMillis) }
                            ?.coerceAtMost(requested.endMillis) ?: requested.endMillis
                        accountWindows[rec.id] = DvrWindow(start, end)
                    }
                    val final = accountWindows.getValue(rec.id)
                    if (final != requested) adjustments += DvrPaddingAdjustment(rec.id, requested, final)
                }
                windows.putAll(accountWindows)
            }
        return DvrConflictPlan(windows, rejected, unknown, adjustments)
    }

    /** Returns the recording's requested capture interval including selected padding. */
    fun requestedWindow(recording: Recording, nowMillis: Long): DvrWindow = DvrWindow(
        recording.scheduledStartMillis.takeIf { it > 0 } ?: recording.startedAtMillis.takeIf { it > 0 } ?: nowMillis,
        recording.scheduledEndMillis.takeIf { it > 0 } ?: Long.MAX_VALUE,
    )

    /** Returns the capture interval that conflict resolution must preserve. */
    private fun requiredWindow(recording: Recording, nowMillis: Long): DvrWindow {
        val requested = requestedWindow(recording, nowMillis)
        // A running sink owns an already committed end time. Only scheduled windows can be
        // adjusted until the capture loop supports a durable, synchronized end-time handoff.
        if (recording.status in activeStatuses || recording.lockPadding || recording.programmeStartMillis <= 0 ||
            recording.programmeEndMillis <= recording.programmeStartMillis) return requested
        return DvrWindow(
            recording.programmeStartMillis.coerceIn(requested.startMillis, requested.endMillis),
            recording.programmeEndMillis.coerceIn(requested.startMillis, requested.endMillis),
        )
    }

    /** Checks whether two recording capture intervals overlap. */
    private fun overlaps(a: DvrWindow, b: DvrWindow, nowMillis: Long): Boolean =
        maxOf(a.startMillis, b.startMillis, nowMillis) < minOf(a.endMillis, b.endMillis)

    /** Half-open intervals ensure back-to-back broadcasts never conflict at a shared endpoint. */
    private fun saturated(windows: Collection<DvrWindow>, capacity: Int, nowMillis: Long): List<DvrWindow> {
        if (capacity <= 0) return listOf(DvrWindow(nowMillis, Long.MAX_VALUE))
        val changes = sortedMapOf<Long, Int>()
        windows.forEach { window ->
            val start = maxOf(window.startMillis, nowMillis)
            if (window.endMillis > start) {
                changes[start] = (changes[start] ?: 0) + 1
                changes[window.endMillis] = (changes[window.endMillis] ?: 0) - 1
            }
        }
        var count = 0
        var previous: Long? = null
        val result = mutableListOf<DvrWindow>()
        changes.forEach { (at, change) ->
            previous?.let { from -> if (count >= capacity && from < at) result += DvrWindow(from, at) }
            count += change
            previous = at
        }
        return result
    }

    private val activeStatuses = setOf(RecordingStatus.PREPARING, RecordingStatus.RECORDING, RecordingStatus.FINALIZING)
    private val pendingStatuses = activeStatuses + RecordingStatus.SCHEDULED
}
