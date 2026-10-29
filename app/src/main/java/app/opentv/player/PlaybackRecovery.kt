/* Copyright (C) 2026 Nuvix Contributors. GPL-3.0-or-later. */
package app.opentv.player

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Fixed categories only: no exception text, account identity or stream address is retained. */
enum class RecoveryFailure(val retryable: Boolean) {
    NETWORK(true), DNS(true), TIMEOUT(true), SOURCE_READ(true), LIVE_WINDOW(true), ENDED(true),
    STALL(true), RATE_LIMIT(true), AUTHENTICATION(false), AUTHORIZATION(false), CONNECTION_LIMIT(false),
    UNSUPPORTED(false), DRM(false), DECODER(false), MISSING_STREAM(false), DEVICE_RESOURCE(false),
    CANCELED(false), UNKNOWN(false),
}
enum class RecoveryState { MONITORING, HEALTHY, BUFFERING, WAITING, RECOVERING, RECOVERED, FAILED, CANCELED }
enum class RecoveryAction { LIGHTWEIGHT, RECONNECT, FAIL }
enum class RecoveryEvent { STALL_DETECTED, BUFFERING, STARTED, LIGHTWEIGHT, CONNECTION_CLOSED,
    RESERVATION_ACQUIRED, PREPARE, FIRST_FRAME, SUCCEEDED, FAILED, CANCELED }
data class RecoveryEntry(val elapsedMs: Long, val event: RecoveryEvent)
data class RecoverySnapshot(
    val state: RecoveryState = RecoveryState.MONITORING,
    val attempts: Int = 0,
    val maximum: Int = 3,
    val failure: RecoveryFailure? = null,
    val durationMs: Long = 0,
    val sinceSuccessMs: Long? = null,
    val bufferingMs: Long = 0,
    val history: List<RecoveryEntry> = emptyList(),
)
data class RecoveryPolicy(
    val backoffMs: List<Long> = listOf(2_000, 5_000, 10_000),
    val startupMs: Long = 45_000,
    val bufferingMs: Long = 30_000,
    val progressMs: Long = 30_000,
    val healthyResetMs: Long = 60_000,
    val verificationMs: Long = 2_000,
) {
    init {
        require(backoffMs.isNotEmpty() && backoffMs.size <= 10 && backoffMs.all { it in 0..300_000 })
        require(startupMs > 0 && bufferingMs > 0 && progressMs > 0 && healthyResetMs > 0 && verificationMs > 0)
    }
}

/** Kept by the viewing owner across controlled player replacement, never persisted. Main thread only. */
class RecoveryBudget(val policy: RecoveryPolicy = RecoveryPolicy(), private val diagnosticsEnabled: () -> Boolean = { true }) {
    private var account: String? = null
    private val _notBefore = MutableStateFlow(0L)
    internal val cooldown = _notBefore.asStateFlow()
    internal var notBeforeMs: Long
        get() = _notBefore.value
        private set(value) { _notBefore.value = value }
    private val _readinessDeadline = MutableStateFlow(0L)
    internal val readinessDeadline = _readinessDeadline.asStateFlow()
    internal fun beginReadinessWindow(now: Long) {
        _readinessDeadline.value = maxOf(now, notBeforeMs).let { it + MANAGED_READINESS_MS.coerceAtMost(Long.MAX_VALUE - it) }
    }
    private companion object { const val MANAGED_READINESS_MS = 240_000L }
    internal fun attachAccount(accountKey: String) {
        if (account != null && account != accountKey) notBeforeMs = 0
        account = accountKey
    }
    internal fun deferFor(delayMs: Long, now: Long) {
        notBeforeMs = maxOf(notBeforeMs, now + delayMs.coerceIn(0, Long.MAX_VALUE - now))
        if (_readinessDeadline.value > 0 && delayMs > 0) {
            _readinessDeadline.value = maxOf(_readinessDeadline.value,
                notBeforeMs + MANAGED_READINESS_MS.coerceAtMost(Long.MAX_VALUE - notBeforeMs))
        }
    }
    internal var epoch = 0L
    internal var attempts = 0
    internal var recoveringSince: Long? = null
    internal var lastSuccess: Long? = null
    internal var recoveredAt: Long? = null
    private val entries = ArrayDeque<RecoveryEntry>()
    private val _snapshot = MutableStateFlow(RecoverySnapshot(maximum = policy.backoffMs.size))
    val snapshot = _snapshot.asStateFlow()
    fun reset() {
        epoch++
        _readinessDeadline.value = 0
        attempts = 0; recoveringSince = null; lastSuccess = null; recoveredAt = null; entries.clear()
        _snapshot.value = RecoverySnapshot(maximum = policy.backoffMs.size)
    }
    fun clearHistory() { entries.clear(); _snapshot.value = _snapshot.value.copy(history = emptyList()) }
    fun record(event: RecoveryEvent, now: Long) {
        if (!diagnosticsEnabled()) { clearHistory(); return }
        entries.addLast(RecoveryEntry(now, event))
        while (entries.size > 64 || entries.firstOrNull()?.let { now - it.elapsedMs > 300_000 } == true) entries.removeFirst()
    }
    internal fun publish(state: RecoveryState, now: Long, failure: RecoveryFailure?, buffering: Long = 0) {
        if (!diagnosticsEnabled()) entries.clear()
        while (entries.firstOrNull()?.let { now - it.elapsedMs > 300_000 } == true) entries.removeFirst()
        _snapshot.value = RecoverySnapshot(state, attempts, policy.backoffMs.size, failure,
            recoveringSince?.let { (recoveredAt ?: now) - it } ?: 0, lastSuccess?.let { now - it }, buffering, entries.toList())
    }
}

data class RecoverySample(
    val expected: Boolean,
    val buffering: Boolean = false,
    val ready: Boolean = false,
    val ended: Boolean = false,
    val positionMs: Long = 0,
    val renderedFrames: Long = 0,
    val bufferedMs: Long = 0,
    val videoExpected: Boolean = false,
)

/** Pure monotonic state machine. The adapter alone executes actions on the existing playback owner. */
class PlaybackRecoveryMonitor(private val budget: RecoveryBudget, private val clock: () -> Long) {
    private val policy = budget.policy
    private val epoch = budget.epoch
    private var started = clock()
    private var lastProgress = started
    private var previous: RecoverySample? = null
    private var bufferingSince: Long? = null
    private var healthySince: Long? = null
    private var verifiedSince: Long? = null
    private var played = false
    private val recentBuffers = ArrayDeque<Pair<Long, Long>>()
    private var waitingUntil: Long? = null
    private var failure: RecoveryFailure? = null
    private var terminal = budget.snapshot.value.state == RecoveryState.FAILED
    private var canceled = false
    private var terminalReported = false

    fun cancel() {
        if (canceled || epoch != budget.epoch) return
        canceled = true; waitingUntil = null
        budget.record(RecoveryEvent.CANCELED, clock())
        budget.publish(RecoveryState.CANCELED, clock(), RecoveryFailure.CANCELED)
    }
    /** Pauses, focus loss, seeks and lifecycle gaps cannot accumulate watchdog or healthy time. */
    fun suspendMonitoring() {
        started = clock(); lastProgress = started; previous = null
        bufferingSince = null; healthySince = null; verifiedSince = null; waitingUntil = null
        recentBuffers.clear()
    }
    fun failure(category: RecoveryFailure, retryAfterMs: Long = 0) {
        if (canceled || epoch != budget.epoch || terminal || (waitingUntil != null && category.retryable)) return
        failure = category; healthySince = null; verifiedSince = null; budget.recoveredAt = null
        budget.deferFor(retryAfterMs, clock())
        if (!category.retryable || budget.attempts >= policy.backoffMs.size) {
            terminal = true; waitingUntil = null
            budget.record(RecoveryEvent.FAILED, clock()); budget.publish(RecoveryState.FAILED, clock(), category)
        } else {
            if (budget.recoveringSince == null) budget.recoveringSince = clock()
            val now = clock()
            budget.deferFor(retryAfterMs, now)
            waitingUntil = maxOf(budget.notBeforeMs, now + policy.backoffMs[budget.attempts])
            budget.publish(RecoveryState.WAITING, clock(), category)
        }
    }
    fun sample(sample: RecoverySample, startupBufferMs: Int = 0, rebufferMs: Int = 0): RecoveryAction? {
        if (canceled || epoch != budget.epoch) return null
        val now = clock()
        if (!sample.expected) { suspendMonitoring(); return null }
        if (terminal) return if (!terminalReported) { terminalReported = true; RecoveryAction.FAIL } else null
        val old = previous
        val progress = sample.ready && old?.ready == true &&
            (if (sample.videoExpected) sample.renderedFrames > old.renderedFrames
             else sample.positionMs - old.positionMs in 100..10_000 || sample.renderedFrames > old.renderedFrames)
        previous = sample
        // Position can jump backwards on a sliding live playlist: rebase without calling it a stall.
        if (sample.ready && !sample.videoExpected && old != null && sample.positionMs < old.positionMs) lastProgress = now
        if (progress) {
            lastProgress = now; played = true
            if (verifiedSince == null) verifiedSince = now
            if (healthySince == null) healthySince = now
            if (now - checkNotNull(verifiedSince) >= policy.verificationMs) {
                budget.lastSuccess = now
                waitingUntil = null
                if (budget.recoveringSince != null && budget.snapshot.value.state != RecoveryState.RECOVERED) {
                    budget.record(RecoveryEvent.SUCCEEDED, now)
                    budget.recoveredAt = now
                }
                val recovered = budget.recoveringSince != null
                if (now - checkNotNull(healthySince) >= policy.healthyResetMs) {
                    budget.attempts = 0; budget.recoveringSince = null; budget.recoveredAt = null; failure = null
                }
                budget.publish(if (recovered) RecoveryState.RECOVERED else RecoveryState.HEALTHY, now, failure)
            }
        } else if (!sample.ready || now - lastProgress > 4_000) {
            healthySince = null; verifiedSince = null
        }
        waitingUntil?.let { due ->
            if (now < maxOf(due, budget.notBeforeMs)) return null
            waitingUntil = null; budget.attempts++
            started = now; lastProgress = now; bufferingSince = null; verifiedSince = null; healthySince = null
            budget.record(RecoveryEvent.STARTED, now)
            budget.publish(RecoveryState.RECOVERING, now, failure)
            return if (budget.attempts == 1) RecoveryAction.LIGHTWEIGHT else RecoveryAction.RECONNECT
        }
        if (sample.buffering) {
            if (bufferingSince == null) { bufferingSince = now; budget.record(RecoveryEvent.BUFFERING, now) }
            val duration = now - checkNotNull(bufferingSince)
            budget.publish(if (budget.recoveringSince != null && budget.recoveredAt == null) RecoveryState.RECOVERING else RecoveryState.BUFFERING,
                now, failure, duration)
            val timeout = if (played) maxOf(policy.bufferingMs, rebufferMs + 15_000L)
                else maxOf(policy.startupMs, startupBufferMs + 30_000L)
            if (duration >= timeout) { budget.record(RecoveryEvent.STALL_DETECTED, now); failure(RecoveryFailure.STALL) }
        } else {
            bufferingSince?.let { since ->
                val duration = now - since
                if (played && duration >= 2_000) recentBuffers.addLast(now to duration)
            }
            bufferingSince = null
            while (recentBuffers.firstOrNull()?.let { now - it.first > 120_000 } == true) recentBuffers.removeFirst()
            while (recentBuffers.size > 16) recentBuffers.removeFirst()
            if (recentBuffers.size >= 3 && recentBuffers.sumOf { it.second } >= maxOf(policy.bufferingMs, rebufferMs + 15_000L)) {
                recentBuffers.clear()
                budget.record(RecoveryEvent.STALL_DETECTED, now); failure(RecoveryFailure.STALL)
            }
            if (sample.ended) failure(RecoveryFailure.ENDED)
            else if (!sample.ready && now - started >= maxOf(policy.startupMs, startupBufferMs + 30_000L)) failure(RecoveryFailure.STALL)
            else if (sample.ready && now - lastProgress >= policy.progressMs) {
                budget.record(RecoveryEvent.STALL_DETECTED, now); failure(RecoveryFailure.STALL)
            }
        }
        return null
    }
}
