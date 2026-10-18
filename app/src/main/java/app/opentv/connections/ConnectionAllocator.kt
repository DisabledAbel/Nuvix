/* Nuvix modifications Copyright (C) 2026 Nuvix Contributors. GPL-3.0-or-later. */
package app.opentv.connections

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

/** Purpose of a connection reservation used for admission and reclaim policy. */
enum class ConnectionPurpose { LIVE, MULTIVIEW, PREVIEW, RECORDING }
/** Lifecycle stage of a locally tracked provider connection. */
enum class ReservationState { PENDING, ACTIVE, RELEASING, RELEASED, FAILED }

/** One network connection attempt. A newer attempt always uses a new generation. */
data class ConnectionRequest(val sessionId: String, val generation: Long, val sourceId: Long, val purpose: ConnectionPurpose)

/** Opaque ownership token binding one connection to an account and session generation. */
@ConsistentCopyVisibility
data class ReservationToken internal constructor(
    val reservationId: String,
    val sessionId: String,
    val generation: Long,
    val sourceId: Long,
    val accountKey: String,
    val purpose: ConnectionPurpose,
)

/** Request-specific confirmation challenge for another connection with an unknown allowance. */
@ConsistentCopyVisibility
data class UnknownConnectionChallenge internal constructor(val challengeId: String, val request: ConnectionRequest, val accountKey: String)
/** Single-use approval minted for a confirmed unknown-capacity challenge. */
@ConsistentCopyVisibility
data class UnknownConnectionApproval internal constructor(val approvalId: String)

/** Admission outcome containing a reservation, a confirmation challenge or a denial reason. */
sealed interface ReservationResult {
    /** Successful admission with the token owning the reserved capacity. */
    data class Reserved(val token: ReservationToken) : ReservationResult
    /** Admission awaiting explicit confirmation for this particular request. */
    data class ApprovalRequired(val challenge: UnknownConnectionChallenge) : ReservationResult
    /** Rejected admission with an explanation suitable for the UI. */
    data class Denied(val reason: String) : ReservationResult
}

/** Manual and observed connection allowances; null represents an unknown limit. */
data class AccountConnectionPolicy(
    val maximumConnections: Int? = null,
    val useProviderMetadata: Boolean = true,
    val providerMaximum: Int? = null,
) {
    val effectiveMaximum: Int? get() = if (useProviderMetadata) providerMaximum else maximumConnections
}

/** Reservation token paired with its current lifecycle state. */
data class ConnectionReservation(val token: ReservationToken, val state: ReservationState)
/** Account policy, associated sources, local reservations and observed provider usage. */
data class AccountConnectionSnapshot(
    val accountKey: String,
    val sourceIds: Set<Long>,
    val policy: AccountConnectionPolicy,
    val reservations: List<ConnectionReservation>,
    /** Observational only: this may include Nuvix and other devices and can become stale. */
    val providerReportedActive: Int? = null,
    val providerObservationTimeMillis: Long? = null,
    val knownConnectionLimitErrors: Int = 0,
) {
    val effectiveMaximum: Int? get() = policy.effectiveMaximum
    val locallyReserved: Int get() = reservations.size
    val allowanceLabel: String get() = effectiveMaximum?.toString() ?: "Unknown"
}

/**
 * Process-local ownership ledger. Pending and releasing connections consume capacity too.
 * Tokens, challenges and approvals are never persisted, so a process restart starts empty.
 * All mutations, including approval consumption and reservation insertion, share one lock.
 */
class ConnectionAllocator(private val newId: () -> String = { UUID.randomUUID().toString() }) {
    /** Mutable policy and observational counters for one locally tracked account. */
    private data class Account(
        var policy: AccountConnectionPolicy = AccountConnectionPolicy(),
        var reportedActive: Int? = null,
        var observedAt: Long? = null,
        var limitErrors: Int = 0,
    )
    private val sources = linkedMapOf<Long, String>()
    private val accounts = linkedMapOf<String, Account>()
    private val reservations = linkedMapOf<String, ConnectionReservation>()
    private val latestGeneration = mutableMapOf<String, Long>()
    private val completedGeneration = mutableMapOf<String, Long>()
    private val terminalReservations = linkedMapOf<String, ConnectionReservation>()
    private val challenges = mutableMapOf<String, UnknownConnectionChallenge>()
    private val approvals = mutableMapOf<String, UnknownConnectionChallenge>()
    private val reclaiming = mutableSetOf<String>()
    private val _snapshots = MutableStateFlow<List<AccountConnectionSnapshot>>(emptyList())
    val snapshots: StateFlow<List<AccountConnectionSnapshot>> = _snapshots.asStateFlow()

    /** Registers a source and policy, refusing to move a source with outstanding reservations. */
    @Synchronized
    fun registerSource(sourceId: Long, accountKey: String, policy: AccountConnectionPolicy? = null): Boolean {
        require(sourceId > 0 && accountKey.isNotBlank())
        val previous = sources[sourceId]
        if (previous != null && previous != accountKey && reservations.values.any { it.token.sourceId == sourceId }) return false
        sources[sourceId] = accountKey
        val account = accounts.getOrPut(accountKey) { Account() }
        if (policy != null) account.policy = validPolicy(policy)
        publish()
        return true
    }

    /** Returns the opaque account key associated with a source, or null if unregistered. */
    @Synchronized fun accountKeyForSource(sourceId: Long): String? = sources[sourceId]
    /** Returns the current account snapshot for a source, or null if unregistered. */
    @Synchronized fun snapshotForSource(sourceId: Long): AccountConnectionSnapshot? =
        _snapshots.value.firstOrNull { sourceId in it.sourceIds }

    /** Updates a registered account policy and invalidates outstanding approvals. */
    @Synchronized
    fun configure(sourceId: Long, policy: AccountConnectionPolicy): Boolean {
        val key = sources[sourceId] ?: return false
        accounts.getValue(key).policy = validPolicy(policy)
        invalidateApprovals(key)
        publish()
        return true
    }

    /** Records provider allowance and usage observations and invalidates stale approvals. */
    @Synchronized
    fun observeProvider(sourceId: Long, maximum: Int?, active: Int?, observedAt: Long) {
        val key = sources[sourceId] ?: return
        val account = accounts.getValue(key)
        account.policy = account.policy.copy(providerMaximum = maximum?.takeIf { it > 0 })
        account.reportedActive = active?.takeIf { it >= 0 }
        account.observedAt = observedAt
        invalidateApprovals(key)
        publish()
    }

    /** Association changes require a quiet pool so existing connections cannot escape a limit. */
    @Synchronized
    fun canReassociate(sourceId: Long, targetKey: String): Boolean {
        val old = sources[sourceId] ?: return false
        return reservations.values.none { it.token.accountKey == old || it.token.accountKey == targetKey }
    }

    /** Moves a registered source to another account only when both pools are idle. */
    @Synchronized
    fun reassociateSource(sourceId: Long, targetKey: String, policy: AccountConnectionPolicy? = null): Boolean {
        if (!canReassociate(sourceId, targetKey)) return false
        return registerSource(sourceId, targetKey, policy)
    }

    /** One atomic account merge/split, including every playlist of an exact account identity. */
    @Synchronized
    fun reassociateSources(mapping: Map<Long, String>, policies: Map<String, AccountConnectionPolicy>): Boolean {
        if (mapping.any { (id, key) -> id !in sources || key.isBlank() }) return false
        val changes = mapping.filter { (id, key) -> sources[id] != key }
        val affected = changes.values.toSet() + changes.keys.mapNotNull(sources::get)
        if (reservations.values.any { it.token.accountKey in affected }) return false
        changes.forEach { (id, key) ->
            sources[id] = key
            accounts.getOrPut(key) { Account() }.let { account -> policies[key]?.let { account.policy = validPolicy(it) } }
        }
        affected.forEach(::invalidateApprovals)
        publish()
        return true
    }

    /**
     * Attempts generation-scoped admission, consuming any matching approval and returning the
     * reservation outcome.
     */
    @Synchronized
    fun reserve(request: ConnectionRequest, approval: UnknownConnectionApproval? = null): ReservationResult {
        if (request.sessionId.isBlank() || request.generation < 0) return ReservationResult.Denied("Invalid connection request.")
        val accountKey = sources[request.sourceId] ?: return ReservationResult.Denied("Provider connection settings are not available.")
        val consumedApproval = approval?.let { approvals.remove(it.approvalId) }
        val newest = latestGeneration[request.sessionId]
        if (newest != null && request.generation < newest) return ReservationResult.Denied("This connection request was replaced.")
        val existing = reservations.values.firstOrNull {
            it.token.sessionId == request.sessionId && it.token.generation == request.generation
        }
        if (existing != null) {
            return if (existing.token.sourceId == request.sourceId && existing.token.purpose == request.purpose && existing.state != ReservationState.RELEASING)
                ReservationResult.Reserved(existing.token)
            else ReservationResult.Denied("This session generation already belongs to another connection.")
        }
        if (completedGeneration[request.sessionId] == request.generation)
            return ReservationResult.Denied("This connection attempt has already finished. Retry with a new generation.")
        if (newest == null || request.generation > newest) {
            latestGeneration[request.sessionId] = request.generation
            challenges.entries.removeAll { it.value.request.sessionId == request.sessionId }
            approvals.entries.removeAll { it.value.request.sessionId == request.sessionId }
        }
        if (accountKey in reclaiming && request.purpose != ConnectionPurpose.RECORDING)
            return ReservationResult.Denied("A recording is reserving this account's available capacity.")
        val count = reservations.values.count { it.token.accountKey == accountKey }
        val maximum = accounts.getValue(accountKey).policy.effectiveMaximum
        if (maximum != null && count >= maximum) return ReservationResult.Denied("This account's configured connection limit is in use.")
        if (maximum == null && count > 0 && (consumedApproval?.request != request || consumedApproval.accountKey != accountKey)) {
            val challenge = challenges.values.firstOrNull { it.request == request && it.accountKey == accountKey }
                ?: UnknownConnectionChallenge(newId(), request, accountKey).also { challenges[it.challengeId] = it }
            return ReservationResult.ApprovalRequired(challenge)
        }
        challenges.entries.removeAll { it.value.request == request }
        val token = ReservationToken(newId(), request.sessionId, request.generation, request.sourceId, accountKey, request.purpose)
        reservations[token.reservationId] = ConnectionReservation(token, ReservationState.PENDING)
        publish()
        return ReservationResult.Reserved(token)
    }

    /** Only the specific UI-confirmed challenge is converted to a consumable approval. */
    @Synchronized
    fun approve(challenge: UnknownConnectionChallenge): UnknownConnectionApproval? {
        if (challenges.remove(challenge.challengeId) != challenge || sources[challenge.request.sourceId] != challenge.accountKey ||
            latestGeneration[challenge.request.sessionId] != challenge.request.generation) return null
        return UnknownConnectionApproval(newId()).also { approvals[it.approvalId] = challenge }
    }

    /** Discards a pending unknown-capacity challenge without admitting its request. */
    @Synchronized
    fun discardChallenge(challenge: UnknownConnectionChallenge) {
        if (challenges[challenge.challengeId] == challenge) challenges.remove(challenge.challengeId)
    }

    /** Returns the tracked state only when the complete reservation token matches. */
    @Synchronized fun reservationState(token: ReservationToken): ReservationState? =
        (reservations[token.reservationId] ?: terminalReservations[token.reservationId])?.takeIf { it.token == token }?.state

    /** Marks a matching pending reservation active after its connection opens. */
    @Synchronized fun markActive(token: ReservationToken): Boolean = transition(token, setOf(ReservationState.PENDING), ReservationState.ACTIVE)
    /** Marks a matching connection as releasing while retaining its capacity until close completes. */
    @Synchronized fun beginRelease(token: ReservationToken): Boolean = transition(token, setOf(ReservationState.PENDING, ReservationState.ACTIVE), ReservationState.RELEASING)

    /** Returns false after the first release; an old callback never touches a newer token. */
    @Synchronized
    fun release(token: ReservationToken): Boolean {
        if (reservations[token.reservationId]?.token != token) return false
        reservations.remove(token.reservationId)
        finish(token, ReservationState.RELEASED)
        publish()
        return true
    }

    /** Finishes a matching reservation as failed and optionally records a connection-limit error. */
    @Synchronized
    fun fail(token: ReservationToken, knownConnectionLimit: Boolean = false): Boolean {
        if (reservations[token.reservationId]?.token != token) return false
        if (knownConnectionLimit) accounts[token.accountKey]?.let { it.limitErrors++ }
        reservations.remove(token.reservationId)
        finish(token, ReservationState.FAILED)
        publish()
        return true
    }

    /** Clears locally observed connection-limit errors for the source account. */
    @Synchronized fun clearKnownConnectionLimitErrors(sourceId: Long) {
        sources[sourceId]?.let { accounts[it]?.limitErrors = 0 }; publish()
    }

    /** Temporarily blocks optional admission while a recording reclaims account capacity. */
    @Synchronized internal fun beginRecordingReclaim(sourceId: Long): String? {
        val key = sources[sourceId] ?: return null
        val recordingCount = reservations.values.count {
            it.token.accountKey == key && it.token.purpose == ConnectionPurpose.RECORDING
        }
        val maximum = accounts.getValue(key).policy.effectiveMaximum
        // Closing viewers cannot free a known recording-only limit or authorize an additional
        // unknown-allowance recording. Pending and releasing recorders still own their slots.
        if (maximum?.let { recordingCount >= it } ?: (recordingCount > 0)) return null
        return key.takeIf { reclaiming.add(it) }
    }
    /** Ends the temporary optional-admission block for an account. */
    @Synchronized internal fun endRecordingReclaim(accountKey: String) { reclaiming.remove(accountKey) }
    /** Lists active or pending preview and multiview reservations eligible for reclaim. */
    @Synchronized internal fun optionalReservations(accountKey: String): List<ReservationToken> = reservations.values
        .map { it.token }.filter { it.accountKey == accountKey && it.purpose != ConnectionPurpose.RECORDING }
        .sortedBy { if (it.purpose == ConnectionPurpose.PREVIEW) 0 else 1 }

    /** Changes a matching token's state only when its current state is allowed. */
    private fun transition(token: ReservationToken, from: Set<ReservationState>, state: ReservationState): Boolean {
        val current = reservations[token.reservationId] ?: return false
        if (current.token != token || current.state !in from) return false
        reservations[token.reservationId] = current.copy(state = state); publish(); return true
    }
    /** Discards challenges and approvals associated with an account. */
    private fun invalidateApprovals(key: String) {
        challenges.entries.removeAll { it.value.accountKey == key }
        approvals.entries.removeAll { it.value.accountKey == key }
    }
    /** Records a terminal reservation state and its completed session generation. */
    private fun finish(token: ReservationToken, state: ReservationState) {
        completedGeneration[token.sessionId] = maxOf(completedGeneration[token.sessionId] ?: -1, token.generation)
        terminalReservations[token.reservationId] = ConnectionReservation(token, state)
        while (terminalReservations.size > 64) terminalReservations.remove(terminalReservations.keys.first())
    }
    /** Normalizes nonpositive manual or provider limits to unknown capacity. */
    private fun validPolicy(policy: AccountConnectionPolicy): AccountConnectionPolicy {
        require(policy.maximumConnections == null || policy.maximumConnections > 0)
        require(policy.providerMaximum == null || policy.providerMaximum > 0)
        return policy
    }
    /** Publishes immutable account snapshots after a ledger change. */
    private fun publish() {
        _snapshots.value = accounts.mapNotNull { (key, value) ->
            val ids = sources.filterValues { it == key }.keys.toSet()
            if (ids.isEmpty()) null else AccountConnectionSnapshot(key, ids, value.policy,
                reservations.values.filter { it.token.accountKey == key }.toList(), value.reportedActive, value.observedAt, value.limitErrors)
        }
    }
}
