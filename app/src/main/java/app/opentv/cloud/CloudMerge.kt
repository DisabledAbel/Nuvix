/* Nuvix modifications Copyright (C) 2026 Nuvix Contributors. GPL-3.0-or-later. */
package app.opentv.cloud

/** Retain failed uploads, coalesce edits per record, and do not upload an acknowledged version twice. */
internal fun buildCloudOutbox(
    acknowledged: Collection<CloudRecord>, pending: Collection<CloudRecord>, local: Collection<CloudRecord>,
): List<CloudRecord> {
    val accepted = acknowledged.associateBy { it.identity() }
    val outbox = pending.groupBy { it.identity() }.mapValues { (_, versions) -> versions.reduce(::newestRecord) }.toMutableMap()
    for (record in local) {
        val prior = accepted[record.identity()]
        if (prior != null && (prior.updatedAtMillis > record.updatedAtMillis ||
                (prior.updatedAtMillis == record.updatedAtMillis && prior.positionMillis == record.positionMillis &&
                    prior.durationMillis == record.durationMillis))) continue
        outbox[record.identity()] = outbox[record.identity()]?.let { newestRecord(it, record) } ?: record
    }
    return outbox.values.toList()
}

/** Missing provider content is not a user removal. Only present, unselected items are dismissed. */
internal fun locallyDismissedFavourites(previouslySelected: Set<String>, present: Set<String>, selected: Set<String>): Set<String> =
    (previouslySelected intersect present) - selected
