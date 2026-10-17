/* Copyright (C) 2026 Nuvix Contributors. GPL-3.0-or-later. */
package app.opentv.recording

import app.opentv.data.model.Recording

/** Only a due recording can preempt a lower-priority owner of capacity it needs right now. */
internal object DvrStartPolicy {
    fun victims(
        due: Recording,
        active: List<Recording>,
        rejectedIds: Set<Long>,
        nowMillis: Long,
        capacity: Int?,
        accountForSource: (Long) -> String?,
    ): List<Recording> {
        val account = accountForSource(due.sourceId) ?: return emptyList()
        if (capacity == null) return emptyList()
        val owners = active.filter {
            accountForSource(it.sourceId) == account && it.scheduledStartMillis <= nowMillis &&
                (it.scheduledEndMillis <= 0 || it.scheduledEndMillis > nowMillis)
        }
        val needed = (owners.size - capacity + 1).coerceAtLeast(0)
        return owners.filter { it.id in rejectedIds && DvrConflictPolicy.priority.compare(due, it) < 0 }
            .sortedWith(DvrConflictPolicy.priority.reversed()).take(needed)
    }
}
