/* Nuvix modifications Copyright (C) 2026 Nuvix Contributors. GPL-3.0-or-later. */
package app.opentv.cloud

/** The existing worker supplies authentication, network and local adapters; tests use isolated peers. */
internal class AdvancedSyncEngine(
    private val journal: AdvancedCloudJournal,
    private val snapshot: suspend () -> List<AdvancedCloudRecord>,
    private val apply: suspend (List<AdvancedCloudRecord>, List<AdvancedCloudRecord>) -> Int,
    private val pull: suspend (Set<AdvancedSyncCategory>) -> List<AdvancedCloudRecord>,
    private val push: suspend (List<AdvancedCloudRecord>) -> Unit,
    private val now: () -> Long = System::currentTimeMillis,
) {
    /** Captures local changes and exchanges enabled categories for one owner, returning the applied count. */
    suspend fun sync(owner: String, device: String, categories: Set<AdvancedSyncCategory>, enabled: Boolean = true): Int {
        val selected = categories - AdvancedSyncCategory.FAVOURITES_PROGRESS
        if (!enabled || selected.isEmpty()) return 0
        journal.bindOwner(owner)
        capture(owner, device, selected)
        // Initial local defaults yield to existing cloud values before the first upload.
        val initial = pull(selected)
        capture(owner, device, selected)
        var changed = reconcile(owner, device, selected, initial)
        val uploaded = journal.pending(owner, selected, now())
        if (uploaded.isNotEmpty()) push(uploaded)
        val remote = pull(selected)
        capture(owner, device, selected)
        changed += reconcile(owner, device, selected, remote)
        // The durable inbox and local adapter have both completed. Exact acknowledgements
        // leave a newer edit queued if it was authored while network requests were running.
        journal.acknowledge(owner, uploaded)
        return changed
    }

    /** Adds the current enabled local categories to the owner's mutation journal. */
    private suspend fun capture(owner: String, device: String, categories: Set<AdvancedSyncCategory>) {
        journal.capture(owner, snapshot(), categories, device, now())
    }

    /** Merges remote and pending records, then applies winners without overwriting newer local edits. */
    private suspend fun reconcile(
        owner: String, device: String, categories: Set<AdvancedSyncCategory>, remote: List<AdvancedCloudRecord>,
    ): Int {
        val expected = snapshot()
        journal.capture(owner, expected, categories, device, now())
        val winners = journal.mergeRemote(owner, remote, categories, now())
        val changed = apply(winners, expected)
        val actual = snapshot()
        val byIdentity = winners.associateBy { it.identity() }
        journal.markApplied(owner, actual.filter { local -> byIdentity[local.identity()]?.samePayload(local) == true }, categories)
        journal.capture(owner, actual, categories, device, now())
        return changed
    }
}
