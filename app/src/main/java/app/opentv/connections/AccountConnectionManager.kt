/* Nuvix modifications Copyright (C) 2026 Nuvix Contributors. GPL-3.0-or-later. */
package app.opentv.connections

import android.content.Context
import app.opentv.data.model.Source
import app.opentv.data.model.SourceKind
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.StateFlow
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONObject
import java.security.MessageDigest

/**
 * The one process-wide allocator for live playback, optional previews and recorder service.
 * The preference file contains only opaque account digests, numeric limits and source IDs.
 * Actual connection ownership and approval tokens deliberately have no persistence path.
 */
class AccountConnectionManager private constructor(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("nuvix_connection_settings", Context.MODE_PRIVATE)
    private val allocator = ConnectionAllocator()
    private val baseAccounts = mutableMapOf<Long, String>()
    private val accountAliases = readAliases()
    private val policies = readPolicies()
    private val owners = mutableMapOf<String, suspend () -> Unit>()
    private val reclamationPriorities = mutableMapOf<String, () -> Int>()
    val snapshots: StateFlow<List<AccountConnectionSnapshot>> = allocator.snapshots

    /** Exact configured account identity; no fuzzy host/playlist matching is performed. */
    @Synchronized
    fun registerSource(source: Source): String {
        val base = accountIdentity(source)
        val key = canonicalAccount(base)
        if (allocator.registerSource(source.id, key, policies[key])) baseAccounts[source.id] = base
        return allocator.accountKeyForSource(source.id) ?: key
    }

    fun accountKeyForSource(sourceId: Long): String? = allocator.accountKeyForSource(sourceId)
    fun snapshotForSource(sourceId: Long): AccountConnectionSnapshot? = allocator.snapshotForSource(sourceId)
    fun reserve(request: ConnectionRequest, approval: UnknownConnectionApproval? = null): ReservationResult = allocator.reserve(request, approval)
    fun approve(challenge: UnknownConnectionChallenge): UnknownConnectionApproval? = allocator.approve(challenge)
    fun discardChallenge(challenge: UnknownConnectionChallenge) = allocator.discardChallenge(challenge)
    fun reservationState(token: ReservationToken): ReservationState? = allocator.reservationState(token)
    fun markActive(token: ReservationToken): Boolean = allocator.markActive(token)
    fun beginRelease(token: ReservationToken): Boolean = allocator.beginRelease(token)

    fun release(token: ReservationToken): Boolean {
        unregisterOwner(token)
        return allocator.release(token)
    }

    /** Set true only for a provider response that explicitly identifies its connection limit. */
    fun fail(token: ReservationToken, knownConnectionLimit: Boolean = false): Boolean {
        unregisterOwner(token)
        return allocator.fail(token, knownConnectionLimit)
    }

    @Synchronized fun registerOwner(
        token: ReservationToken,
        reclamationPriority: () -> Int = { 1 },
        close: suspend () -> Unit,
    ) {
        if (allocator.reservationState(token) in setOf(ReservationState.PENDING, ReservationState.ACTIVE)) {
            owners[token.reservationId] = close
            reclamationPriorities[token.reservationId] = reclamationPriority
        }
    }
    @Synchronized fun unregisterOwner(token: ReservationToken) {
        owners.remove(token.reservationId)
        reclamationPriorities.remove(token.reservationId)
    }
    @Synchronized private fun owner(token: ReservationToken): (suspend () -> Unit)? = owners[token.reservationId]
    @Synchronized private fun reclamationPriority(token: ReservationToken): Int =
        if (token.purpose == ConnectionPurpose.PREVIEW) Int.MIN_VALUE
        else reclamationPriorities[token.reservationId]?.invoke() ?: 1

    /**
     * Recording preference may close optional consumers, never other recordings. Owners must
     * close their real player/network I/O before releasing the token. A missing/failed owner
     * leaves its token counted and cannot manufacture spare capacity.
     */
    suspend fun reserveForRecording(
        request: ConnectionRequest,
        approval: UnknownConnectionApproval? = null,
        allowInterruptLive: Boolean = false,
    ): ReservationResult {
        require(request.purpose == ConnectionPurpose.RECORDING)
        val first = reserve(request, approval)
        if (first is ReservationResult.Reserved || !allowInterruptLive) return first
        // Admission to reclamation checks recording-only usage under the allocator's lock.
        val key = allocator.beginRecordingReclaim(request.sourceId) ?: return first
        try {
            for (token in allocator.optionalReservations(key).sortedBy(::reclamationPriority)) {
                val close = owner(token) ?: continue
                try { close() } catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { continue }
                val retried = reserve(request)
                if (retried is ReservationResult.Reserved) return retried
            }
            return reserve(request)
        } finally {
            allocator.endRecordingReclaim(key)
        }
    }

    @Synchronized
    fun setMaximumConnections(sourceId: Long, maximum: Int?): Boolean {
        require(maximum == null || maximum > 0)
        val snapshot = snapshotForSource(sourceId) ?: return false
        return updatePolicy(sourceId, snapshot.policy.copy(maximumConnections = maximum, useProviderMetadata = false))
    }

    @Synchronized
    fun setUseProviderMetadata(sourceId: Long, enabled: Boolean): Boolean {
        val snapshot = snapshotForSource(sourceId) ?: return false
        return updatePolicy(sourceId, snapshot.policy.copy(useProviderMetadata = enabled))
    }

    @Synchronized
    fun updateProviderAccount(source: Source, maxConnections: Int?, activeConnections: Int?, observedAtMillis: Long = System.currentTimeMillis()) {
        val expectedAccount = canonicalAccount(accountIdentity(source))
        if (registerSource(source) != expectedAccount) return
        allocator.observeProvider(source.id, maxConnections, activeConnections, observedAtMillis)
        snapshotForSource(source.id)?.let { policies[it.accountKey] = it.policy }
        persistPolicies()
    }

    /** Explicit user association. The target account's policy applies to the entire pool. */
    @Synchronized
    fun associateSources(sourceId: Long, targetSourceId: Long): Boolean {
        val target = accountKeyForSource(targetSourceId) ?: return false
        val previous = accountKeyForSource(sourceId) ?: return false
        if (previous == target) return true
        val updated = accountAliases.toMutableMap().apply { put(previous, target) }
        return applyAliases(updated)
    }

    @Synchronized
    fun clearAssociation(sourceId: Long): Boolean {
        val base = baseAccounts[sourceId] ?: return false
        val updated = accountAliases.toMutableMap().apply { remove(base) }
        return applyAliases(updated)
    }

    @Synchronized fun isManuallyAssociated(sourceId: Long): Boolean = baseAccounts[sourceId]?.let { canonicalAccount(it) != it } ?: false
    fun clearKnownConnectionLimitErrors(sourceId: Long) = allocator.clearKnownConnectionLimitErrors(sourceId)

    private fun updatePolicy(sourceId: Long, policy: AccountConnectionPolicy): Boolean {
        val key = accountKeyForSource(sourceId) ?: return false
        if (!allocator.configure(sourceId, policy)) return false
        policies[key] = policy
        persistPolicies()
        return true
    }

    private fun canonicalAccount(base: String, aliases: Map<String, String> = accountAliases): String {
        var current = base
        val visited = mutableSetOf<String>()
        while (visited.add(current)) current = aliases[current] ?: return current
        return base // Corrupt/cyclic saved data never grants a new account identity.
    }

    private fun applyAliases(updated: Map<String, String>): Boolean {
        val remapped = baseAccounts.mapValues { (_, base) -> canonicalAccount(base, updated) }
        if (!allocator.reassociateSources(remapped, policies)) return false
        accountAliases.clear()
        accountAliases.putAll(updated)
        persistAliases()
        return true
    }

    private fun readAliases(): MutableMap<String, String> = runCatching {
        val saved = JSONObject(prefs.getString("account_aliases_v1", "{}") ?: "{}")
        saved.keys().asSequence().mapNotNull { raw ->
            if (!isAccountKey(raw)) return@mapNotNull null
            val key = saved.optString(raw).takeIf(::isAccountKey) ?: return@mapNotNull null
            raw to key
        }.toMap().toMutableMap()
    }.getOrDefault(mutableMapOf())

    private fun readPolicies(): MutableMap<String, AccountConnectionPolicy> = runCatching {
        val saved = JSONObject(prefs.getString("policies_v1", "{}") ?: "{}")
        saved.keys().asSequence().mapNotNull { key ->
            if (!isAccountKey(key)) return@mapNotNull null
            val row = saved.optJSONObject(key) ?: return@mapNotNull null
            key to AccountConnectionPolicy(row.optInt("maximum", 0).takeIf { it > 0 },
                row.optBoolean("provider_metadata", true), row.optInt("provider_maximum", 0).takeIf { it > 0 })
        }.toMap().toMutableMap()
    }.getOrDefault(mutableMapOf())

    private fun persistAliases() {
        val saved = JSONObject()
        accountAliases.forEach { (base, key) -> saved.put(base, key) }
        prefs.edit().putString("account_aliases_v1", saved.toString()).apply()
    }
    private fun persistPolicies() {
        val saved = JSONObject()
        policies.forEach { (key, policy) -> saved.put(key, JSONObject().apply {
            put("maximum", policy.maximumConnections ?: 0)
            put("provider_metadata", policy.useProviderMetadata)
            put("provider_maximum", policy.providerMaximum ?: 0)
        }) }
        prefs.edit().putString("policies_v1", saved.toString()).apply()
    }

    companion object {
        @Volatile private var instance: AccountConnectionManager? = null
        fun get(context: Context): AccountConnectionManager = instance ?: synchronized(this) {
            instance ?: AccountConnectionManager(context.applicationContext).also { instance = it }
        }
        internal fun createForTest(context: Context): AccountConnectionManager = AccountConnectionManager(context)
        private fun isAccountKey(value: String): Boolean = value.matches(Regex("acct_[0-9a-f]{64}"))

        /** Digest avoids persisting credential-bearing endpoint/username/MAC/playlist strings. */
        internal fun accountIdentity(source: Source): String {
            val parsed = source.url.toHttpUrlOrNull()
            val endpoint = parsed?.newBuilder()?.query(null)?.fragment(null)?.build()?.toString()?.trimEnd('/') ?: source.url.trimEnd('/')
            val identity = when (source.kind) {
                SourceKind.XTREAM -> listOf("xtream", endpoint, source.username.orEmpty())
                SourceKind.STALKER -> listOf("stalker", endpoint, source.macAddress.orEmpty().uppercase())
                SourceKind.M3U -> listOf("playlist", source.url)
            }
            // Length framing prevents ambiguous delimiter-containing usernames or endpoints.
            val framed = identity.joinToString("") { "${it.length}:$it" }
            return "acct_" + MessageDigest.getInstance("SHA-256").digest(framed.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
        }
    }
}
