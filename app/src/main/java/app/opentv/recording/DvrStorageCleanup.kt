/* Copyright (C) 2026 Nuvix Contributors. GPL-3.0-or-later. */
package app.opentv.recording

import app.opentv.data.model.Recording

data class StorageDeletionSummary(val deleted: Int, val changed: Int, val storageError: Boolean)

/** A confirmed batch is frozen. A failed/uncertain operation stops further deletions. */
suspend fun deleteReviewedRecordings(review: List<Recording>, delete: suspend (Recording) -> StorageDeleteResult): StorageDeletionSummary {
    val frozen = review.toList()
    var deleted = 0
    var changed = 0
    for (row in frozen) when (delete(row)) {
        StorageDeleteResult.DELETED -> deleted++
        StorageDeleteResult.CHANGED -> changed++
        StorageDeleteResult.STORAGE_ERROR -> return StorageDeletionSummary(deleted, changed, true)
    }
    return StorageDeletionSummary(deleted, changed, false)
}

/** Bounded work; actual capacity is reread after every deletion, never incremented from row sizes. */
class DvrStorageCleanup(
    private val policy: () -> DvrStoragePolicy,
    private val capacity: suspend () -> StorageCapacity,
    private val candidates: suspend () -> List<Recording>,
    private val owned: (Recording) -> Boolean,
    private val delete: suspend (Recording) -> StorageDeleteResult,
    private val now: () -> Long = System::currentTimeMillis,
) {
    suspend fun run(): String {
        if (!policy().cleanupEnabled) return "Automatic cleanup is off."
        var observed = capacity()
        var removed = 0
        if (!observed.reliable(now())) return "Cleanup stopped: capacity is unknown, stale or unavailable. No further files deleted."
        val rows = DvrStorageRules.oldestFirst(candidates().take(MAX_CANDIDATES)).filter {
            DvrStorageRules.eligible(it, owned(it), policy(), now())
        }
        for (row in rows.take(MAX_DELETIONS)) {
            val current = policy()
            if (!current.cleanupEnabled) return "Cleanup disabled; no further files deleted."
            if (!observed.reliable(now())) return "Cleanup stopped: capacity is uncertain. $removed recording(s) deleted."
            if (observed.availableBytes!! >= current.targetBytes) return "Free-space target met. $removed recording(s) deleted."
            when (delete(row)) {
                StorageDeleteResult.STORAGE_ERROR -> return "Cleanup stopped on a storage error. $removed recording(s) deleted; remaining history preserved."
                StorageDeleteResult.DELETED -> removed++
                StorageDeleteResult.CHANGED -> Unit // A fresh engine check rejected a changed/protected/active row.
            }
            observed = capacity()
        }
        return if (!observed.reliable(now())) "Cleanup stopped: capacity is uncertain. $removed recording(s) deleted."
        else if (observed.availableBytes!! >= policy().targetBytes) "Free-space target met. $removed recording(s) deleted."
        else "Warning: eligible cleanup could not meet the free-space target. $removed recording(s) deleted. Review storage or protected recordings."
    }

    companion object { const val MAX_CANDIDATES = 200; const val MAX_DELETIONS = 10 }
}
