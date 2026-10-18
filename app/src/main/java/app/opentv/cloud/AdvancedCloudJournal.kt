/* Nuvix modifications Copyright (C) 2026 Nuvix Contributors. GPL-3.0-or-later. */
package app.opentv.cloud

import kotlinx.serialization.Serializable

/** One encrypted, atomically replaced value contains the owner, baseline, inbox and outbox. */
@Serializable
private data class AdvancedJournalState(
    val version: Int = 1,
    val owner: String,
    val known: List<AdvancedCloudRecord> = emptyList(),
    val observed: List<AdvancedCloudRecord> = emptyList(),
    val pending: List<AdvancedCloudRecord> = emptyList(),
)

/**
 * Persistence is supplied by CloudVault; pure storage lambdas also permit interrupted-sync tests.
 * Callers serialize local application and capture through the existing CloudSync mutex. No absent
 * channel, unavailable app or missing local record is ever interpreted as an explicit deletion.
 */
internal class AdvancedCloudJournal(
    private val read: () -> String?,
    private val write: (String) -> Unit,
) {
    companion object {
        const val VAULT_NAME = "advanced_journal"
        private const val MAX_IDENTITIES = 100_000
        private const val MAX_STATE_BYTES = 64 * 1024 * 1024
    }

    /** An account switch cannot replay the previous account's encrypted pending records. */
    @Synchronized fun bindOwner(owner: String) {
        require(canonicalUuid(owner)) { "Invalid cloud account identifier." }
        val current = load()
        if (current?.owner != owner) save(AdvancedJournalState(owner = owner))
    }

    /**
     * Capture only locally changed payloads, assigning one monotonically increasing revision per
     * identity. Disabled categories leave both their existing queue and baseline unchanged.
     */
    @Synchronized fun capture(
        owner: String,
        local: Collection<AdvancedCloudRecord>,
        enabled: Set<AdvancedSyncCategory>,
        deviceId: String,
        nowMillis: Long,
    ): Int {
        require(canonicalUuid(deviceId)) { "Invalid cloud device identifier." }
        val current = owned(owner)
        val known = current.known.associateBy { it.identity() }
        val observed = current.observed.associateBy { it.identity() }.toMutableMap()
        val pending = current.pending.associateBy { it.identity() }.toMutableMap()
        // Content snapshots are sparse: untouched provider defaults and unavailable rows are
        // omitted. Keep the last observed intent through absence so a later explicit reset is
        // still compared with the user value, rather than mistaken for an initial seed.
        var captured = 0
        for (record in local) {
            // Validate before inspecting the category so unsupported input cannot throw or upload.
            if (!record.isSafe(Long.MAX_VALUE) || record.category !in enabled) continue
            val identity = record.identity()
            val previous = observed[identity]
            if (previous?.samePayload(record) == true) continue
            observed[identity] = record
            val accepted = known[identity]
            val queued = pending[identity]
            val newest = listOfNotNull(accepted, queued).reduceOrNull(::newestAdvancedRecord)
            // Non-content adapters still emit full defaults. Sparse content records carry intent.
            if (previous == null && (accepted != null || queued != null) && !isContentOrganization(record)) continue
            if (newest?.samePayload(record) == true) continue
            // A retired group UUID can never be reused, even by a later local edit.
            if (newest?.kind == "group_name" && newest.deleted && !record.deleted) continue
            val previousTime = newest?.updatedAtMillis ?: 0L
            val nextTime = if (previousTime == Long.MAX_VALUE) Long.MAX_VALUE else previousTime + 1
            // Initial local values are baseline seeds. An older real edit from another TV must
            // beat a freshly enabled device's defaults; subsequent deliberate changes use now.
            val authoredTime = if (previous == null && newest == null) 1L else maxOf(1L, nowMillis, nextTime)
            val authored = record.copy(updatedAtMillis = authoredTime, deviceId = deviceId)
            pending[identity] = queued?.let { newestAdvancedRecord(it, authored) } ?: authored
            captured++
        }
        val next = current.copy(observed = observed.values.toList(), pending = pending.values.toList())
        if (next != current) save(next)
        return captured
    }

    /** Future-clock edits remain durable until safe; disabling a category does not discard them. */
    @Synchronized fun pending(
        owner: String, enabled: Set<AdvancedSyncCategory>, nowMillis: Long,
    ): List<AdvancedCloudRecord> = owned(owner).pending.filter { it.isSafe(nowMillis) && it.category in enabled }

    /** Counts queued mutations belonging to the specified cloud owner. */
    @Synchronized fun pendingCount(owner: String): Int = owned(owner).pending.size

    /**
     * Persist remote winners before application. Return all known enabled winners, including
     * references previously unavailable on this device so they can reconcile after a later import.
     * Pending local revisions participate in conflict resolution and cannot be lost on a pull.
     */
    @Synchronized fun mergeRemote(
        owner: String,
        remote: Collection<AdvancedCloudRecord>,
        enabled: Set<AdvancedSyncCategory>,
        nowMillis: Long,
    ): List<AdvancedCloudRecord> {
        val current = owned(owner)
        val known = current.known.associateBy { it.identity() }.toMutableMap()
        val pending = current.pending.associateBy { it.identity() }.toMutableMap()
        for (record in remote) {
            if (!record.isSafe(nowMillis) || record.category !in enabled) continue
            val identity = record.identity()
            known[identity] = known[identity]?.let { newestAdvancedRecord(it, record) } ?: record
            // Pull precedes the first upload: another TV's persisted baseline wins over this
            // device's initial defaults, including an equal timestamp seed from that TV.
            val seed = pending[identity]
            if (seed?.updatedAtMillis == 1L && !seed.deleted) pending.remove(identity)
        }
        val next = current.copy(known = known.values.toList(), pending = pending.values.toList())
        if (next != current) save(next)
        return winners(next).filter { it.isSafe(nowMillis) && it.category in enabled }
    }

    /**
     * Call after applying remote records, with an actual fresh local snapshot. Persisting the
     * observed payloads prevents remote application from becoming a fresh local upload. Records
     * that could not be applied stay in known and are retried by mergeRemote on the next sync.
     */
    @Synchronized fun markApplied(
        owner: String, local: Collection<AdvancedCloudRecord>, enabled: Set<AdvancedSyncCategory>,
    ) {
        val current = owned(owner)
        val observed = current.observed.associateBy { it.identity() }.toMutableMap()
        for (record in local) {
            if (record.isSafe(Long.MAX_VALUE) && record.category in enabled) observed[record.identity()] = record
        }
        val next = current.copy(observed = observed.values.toList())
        if (next != current) save(next)
    }

    /**
     * Acknowledge only the exact immutable versions sent successfully. A newer edit captured
     * while a request was running remains queued. A crash before this write safely replays it.
     */
    @Synchronized fun acknowledge(owner: String, uploaded: Collection<AdvancedCloudRecord>) {
        val current = owned(owner)
        val pending = current.pending.associateBy { it.identity() }.toMutableMap()
        val known = current.known.associateBy { it.identity() }.toMutableMap()
        for (record in uploaded) {
            if (!record.isSafe(Long.MAX_VALUE)) continue
            val identity = record.identity()
            known[identity] = known[identity]?.let { newestAdvancedRecord(it, record) } ?: record
            if (pending[identity] == record) pending.remove(identity)
        }
        val next = current.copy(known = known.values.toList(), pending = pending.values.toList())
        if (next != current) save(next)
    }

    /** Identifies records that change channel organization fields. */
    private fun isContentOrganization(record: AdvancedCloudRecord): Boolean =
        record.category == AdvancedSyncCategory.CHANNEL_ORGANIZATION || record.category == AdvancedSyncCategory.FAVOURITE_REMOVALS

    /** Combines baseline and pending records using deterministic conflict resolution. */
    private fun winners(state: AdvancedJournalState): List<AdvancedCloudRecord> =
        (state.known + state.pending).groupBy { it.identity() }.values.map { it.reduce(::newestAdvancedRecord) }

    /** Returns journal state for the requested owner, isolating other accounts. */
    private fun owned(owner: String): AdvancedJournalState {
        require(canonicalUuid(owner)) { "Invalid cloud account identifier." }
        val current = load()
        check(current?.owner == owner) { "Cloud journal belongs to another account. Sign in again." }
        return current
    }

    /** Decodes the stored journal, returning null when it cannot be read. */
    private fun load(): AdvancedJournalState? {
        val encoded = read() ?: return null
        require(encoded.toByteArray(Charsets.UTF_8).size <= MAX_STATE_BYTES) { "Encrypted cloud journal is too large." }
        val state = runCatching { advancedCloudJson.decodeFromString(AdvancedJournalState.serializer(), encoded) }
            .getOrElse { throw IllegalStateException("Encrypted cloud journal is invalid; remove cloud setup on this TV.") }
        require(state.version == 1 && canonicalUuid(state.owner)) { "Unsupported encrypted cloud journal." }
        for (records in listOf(state.known, state.observed, state.pending)) {
            require(records.size <= MAX_IDENTITIES && records.all { it.isSafe(Long.MAX_VALUE) } &&
                records.map { it.identity() }.toSet().size == records.size) { "Encrypted cloud journal is invalid." }
        }
        return state
    }

    /** Persists the journal state in the encrypted cloud vault. */
    private fun save(state: AdvancedJournalState) {
        require(listOf(state.known, state.observed, state.pending).all { it.size <= MAX_IDENTITIES }) {
            "Cloud journal has too many pending records."
        }
        val encoded = advancedCloudJson.encodeToString(AdvancedJournalState.serializer(), state)
        require(encoded.toByteArray(Charsets.UTF_8).size <= MAX_STATE_BYTES) { "Encrypted cloud journal is too large." }
        write(encoded)
    }
}
