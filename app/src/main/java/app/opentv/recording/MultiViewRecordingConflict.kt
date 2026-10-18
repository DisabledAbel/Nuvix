/* Nuvix Copyright (C) 2026 Nuvix Contributors. GPL-3.0-or-later. */
package app.opentv.recording

import app.opentv.connections.AccountConnectionSnapshot
import app.opentv.connections.ConnectionPurpose
import app.opentv.connections.ReservationToken
import app.opentv.data.model.Recording
import app.opentv.data.model.RecordingStatus

/** A warning identifies a booking window, never a channel name or a truncated alarm ID. */
fun Recording.warningKey(): String = "$id:$sourceId:$scheduledStartMillis:$scheduledEndMillis:$padStartMinutes:$padEndMinutes"

data class MultiViewRecordingConflict(
    val recording: Recording,
    val account: AccountConnectionSnapshot,
    val effectiveStartMillis: Long,
    val victims: List<ReservationToken>,
) {
    val key get() = "${recording.warningKey()}:$effectiveStartMillis"
    val unknownAllowance get() = account.effectiveMaximum == null
}

/** Read-only projection of the existing DVR plan and allocator; it never reserves or trims. */
object MultiViewRecordingConflictPolicy {
    const val DEFAULT_LEAD_MILLIS = 120_000L

    /** Shared read-only plan for alarm timing and visible conflict detection. */
    fun plan(recordings: List<Recording>, accounts: List<AccountConnectionSnapshot>, now: Long): DvrConflictPlan {
        val bySource = accounts.flatMap { account -> account.sourceIds.map { it to account } }.toMap()
        return DvrConflictPolicy.resolve(recordings, now,
            { bySource[it]?.accountKey ?: "unavailable:$it" },
            { key -> accounts.firstOrNull { it.accountKey == key }?.effectiveMaximum })
    }

    fun detect(
        recordings: List<Recording>,
        accounts: List<AccountConnectionSnapshot>,
        now: Long,
        leadMillis: Long = DEFAULT_LEAD_MILLIS,
        primarySessionId: String? = null,
    ): List<MultiViewRecordingConflict> {
        val bySource = accounts.flatMap { account -> account.sourceIds.map { it to account } }.toMap()
        val plan = plan(recordings, accounts, now)
        return recordings.filter { it.status == RecordingStatus.SCHEDULED && it.id !in plan.rejectedIds }
            .sortedWith(compareBy<Recording> { plan.windows[it.id]?.startMillis }.then(DvrConflictPolicy.priority))
            .mapNotNull { rec ->
                val start = plan.windows[rec.id]?.startMillis ?: return@mapNotNull null
                if (start <= now || start - now > leadMillis) return@mapNotNull null
                val account = bySource[rec.sourceId] ?: return@mapNotNull null
                val demand = recordings.count { other ->
                    bySource[other.sourceId]?.accountKey == account.accountKey &&
                        plan.windows[other.id]?.let { start >= it.startMillis && start < it.endMillis } == true
                }.coerceAtLeast(account.reservations.count { it.token.purpose == ConnectionPurpose.RECORDING })
                val optional = account.reservations.map { it.token }.filter { it.purpose != ConnectionPurpose.RECORDING }
                    .sortedWith(compareBy<ReservationToken> {
                        when { it.purpose == ConnectionPurpose.PREVIEW -> 0; it.sessionId == primarySessionId -> 2; else -> 1 }
                    }.thenBy { it.sessionId })
                // An unknown pool admits one unattended connection only. Closing viewers cannot
                // authorize a second recorder, so do not promise that it would resolve that case.
                val needed = account.effectiveMaximum?.let { optional.size + demand - it }
                    ?: if (demand == 1) optional.size else 0
                val victims = optional.take(needed.coerceAtLeast(0))
                if (victims.none { it.purpose == ConnectionPurpose.MULTIVIEW }) null
                else MultiViewRecordingConflict(rec, account, start, victims)
            }
    }
}
