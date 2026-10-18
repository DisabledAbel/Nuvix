/* Nuvix Copyright (C) 2026 Nuvix Contributors. GPL-3.0-or-later. */
package app.opentv.multiview

import android.content.Context
import androidx.media3.common.Player
import app.opentv.connections.AccountConnectionManager
import app.opentv.connections.AccountConnectionSnapshot
import app.opentv.connections.ConnectionPurpose
import app.opentv.connections.ConnectionRequest
import app.opentv.connections.ReservationResult
import app.opentv.connections.ReservationToken
import app.opentv.connections.UnknownConnectionApproval
import app.opentv.connections.UnknownConnectionChallenge
import app.opentv.core.ServiceLocator
import app.opentv.data.model.shownName
import app.opentv.player.PlayerSurfaceOwner
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID
import kotlin.coroutines.coroutineContext

enum class MultiViewLayout { TWO, THREE, GRID, FOCUSED }
enum class MultiViewAudioMode { FOLLOW_FOCUS, PRESS_OK }
enum class MultiViewRestorePolicy { ASK_FIRST, RESTORE, KEEP_FULLSCREEN }
enum class MultiViewPanelStatus { STARTING, PLAYING, BUFFERING, FAILED, PAUSED }
enum class MultiViewDuplicateChoice { FOCUS_EXISTING, REPLACE_SELECTED, CANCEL }

data class MultiViewPanel(
    val id: String,
    val channelId: Long,
    val channelName: String = "Loading channel…",
    val providerName: String = "",
    val sourceId: Long = 0,
    val accountKey: String? = null,
    val status: MultiViewPanelStatus = MultiViewPanelStatus.STARTING,
    val error: String? = null,
    val failureKind: MultiViewFailureKind? = null,
    val engineReady: Boolean = false,
    val repeatedBuffering: Boolean = false,
    val playbackGeneration: Long = 0,
)
data class MultiViewApproval(val channelName: String, val providerName: String, val challenge: UnknownConnectionChallenge)
data class MultiViewDuplicate(val channelId: Long, val existingPanelId: String, val targetPanelId: String?)
data class MultiViewState(
    val panels: List<MultiViewPanel> = emptyList(),
    val focusedPanelId: String? = null,
    val audioPanelId: String? = null,
    val primaryPanelId: String? = null,
    val layout: MultiViewLayout = MultiViewLayout.TWO,
    val guideOpen: Boolean = false,
    val fullscreenPanelId: String? = null,
    val restorationRequired: Boolean = false,
    val approval: MultiViewApproval? = null,
    val duplicate: MultiViewDuplicate? = null,
    val notice: String? = null,
    val muted: Boolean = false,
    val audioFocusGranted: Boolean = false,
)

/** Testable seam around PR #35's single process-wide account allocator. */
interface MultiViewConnections {
    fun snapshot(sourceId: Long): AccountConnectionSnapshot?
    fun reserve(request: ConnectionRequest, approval: UnknownConnectionApproval? = null): ReservationResult
    fun approve(challenge: UnknownConnectionChallenge): UnknownConnectionApproval?
    fun discard(challenge: UnknownConnectionChallenge)
    fun markActive(token: ReservationToken): Boolean
    fun beginRelease(token: ReservationToken): Boolean
    fun release(token: ReservationToken): Boolean
    fun fail(token: ReservationToken, knownConnectionLimit: Boolean = false): Boolean
    fun registerOwner(token: ReservationToken, reclamationPriority: () -> Int = { 1 }, close: suspend () -> Unit)
}

/**
 * Activity/session scoped owner. UI surfaces borrow players; layout changes only reorder IDs.
 * Call on the main dispatcher. Startup is serialized and bounded, while remove/stop cancel
 * immediately. Neither URLs nor runtime reservations escape through [state].
 */
class MultiViewSessionManager(
    private val scope: CoroutineScope,
    private val resolve: suspend (Long) -> ResolvedMultiViewChannel,
    private val connections: MultiViewConnections,
    private val players: MultiViewPlayerFactory,
    private val hardware: MultiViewHardware,
    private val audioFocus: MultiViewAudioFocus = object : MultiViewAudioFocus {
        override fun request(onChange: (Boolean) -> Unit) = true
        override fun abandon() {}
    },
    private val beforeStart: () -> Unit = {},
    private val newId: () -> String = { UUID.randomUUID().toString() },
    private val startupTimeoutMs: Long = 15_000,
    private val nowMs: () -> Long = { System.nanoTime() / 1_000_000 },
) {
    private class Runtime(val token: ReservationToken, val channel: ResolvedMultiViewChannel, val generation: Long) {
        lateinit var player: MultiViewPlayer
        val hasPlayer: Boolean get() = ::player.isInitialized
        val ready = CompletableDeferred<Boolean>()
        var released = false
        var bufferingCount = 0
        var wasReady = false
        var bufferingSince: Long? = null
        val meaningfulStalls = ArrayDeque<Long>()
        var lastSuggestionAt: Long? = null
    }
    private class PanelRecord(val id: String, var channelId: Long) {
        var generation = 0L
        var current: Runtime? = null
        var candidate: Runtime? = null
        var operation: Job? = null
        var retryCount = 0
    }
    private val records = linkedMapOf<String, PanelRecord>()
    private val admission = Mutex()
    private val sessionId = "multiview:${newId()}"
    // Presets can reuse panel IDs within this manager's lifetime. Attempts must still be newer
    // than the account allocator's completed generation for that panel/session identity.
    private var generationSequence = 0L
    private val _state = MutableStateFlow(MultiViewState())
    val state = _state.asStateFlow()
    val capability get() = hardware.capability
    private var audioMode = MultiViewAudioMode.FOLLOW_FOCUS
    private var focusDelayMs = 150L
    private var restorePolicy = MultiViewRestorePolicy.ASK_FIRST
    private var audioJob: Job? = null
    private var approvalResponse: CompletableDeferred<UnknownConnectionApproval?>? = null
    private var foreground = true
    private var started = false
    private var focusLease = false
    private var audioInterrupted = false
    private var inPip = false
    private var preferences = MultiViewPreferences()
    private var checkpointStore: MultiViewPresetStore? = null
    private val expectedReferences = mutableMapOf<String, MultiViewChannelReference>()
    private val observerJobs = mutableListOf<Job>()

    fun add(channelId: Long) {
        if (!preferences.enabled) { notice("Enable Multi-View in Display & Playback settings to add panels."); return }
        val duplicate = findChannel(channelId)
        if (duplicate != null) {
            _state.value = _state.value.copy(duplicate = MultiViewDuplicate(channelId, duplicate.id, _state.value.focusedPanelId))
            return
        }
        if (records.size >= 4) { notice("Multi-View has four panels. Replace or remove a panel to add another."); return }
        if (!started) { beforeStart(); started = true }
        val record = PanelRecord(newId(), channelId)
        records[record.id] = record
        val first = records.size == 1
        _state.value = _state.value.copy(
            panels = _state.value.panels + MultiViewPanel(record.id, channelId),
            primaryPanelId = if (first) record.id else _state.value.primaryPanelId,
            focusedPanelId = if (first) record.id else _state.value.focusedPanelId,
            audioPanelId = if (first) record.id else _state.value.audioPanelId,
            layout = when (records.size) { 3 -> MultiViewLayout.THREE; 4 -> MultiViewLayout.GRID; else -> _state.value.layout },
        )
        tune(record, channelId)
    }

    fun replace(panelId: String, channelId: Long) {
        val record = records[panelId] ?: return
        val duplicate = findChannel(channelId)
        if (duplicate != null) {
            _state.value = _state.value.copy(duplicate = MultiViewDuplicate(channelId, duplicate.id, panelId)); return
        }
        record.retryCount = 0
        expectedReferences.remove(panelId)
        tune(record, channelId)
    }

    fun resolveDuplicate(choice: MultiViewDuplicateChoice) {
        val duplicate = _state.value.duplicate ?: return
        _state.value = _state.value.copy(duplicate = null)
        if (choice != MultiViewDuplicateChoice.CANCEL) records[duplicate.existingPanelId]?.let { record ->
            val current = record.current
            if (current != null && !current.released && current.channel.channelId == duplicate.channelId &&
                record.channelId != duplicate.channelId) {
                ++record.generation
                record.operation?.cancel()
                record.candidate?.takeUnless { it === current }?.let(::close); record.candidate = null
                record.channelId = current.channel.channelId
                update(record.id) { it.copy(channelId = current.channel.channelId, channelName = current.channel.channelName,
                    providerName = current.channel.providerName, sourceId = current.channel.sourceId,
                    accountKey = current.token.accountKey, status = MultiViewPanelStatus.PLAYING, engineReady = true) }
            }
        }
        when (choice) {
            MultiViewDuplicateChoice.FOCUS_EXISTING -> focus(duplicate.existingPanelId)
            MultiViewDuplicateChoice.REPLACE_SELECTED -> {
                // Move the already-authorized session into the selected slot; never open a copy.
                val selected = duplicate.targetPanelId
                if (selected != null && selected != duplicate.existingPanelId) {
                    val order = _state.value.panels.toMutableList()
                    val from = order.indexOfFirst { it.id == duplicate.existingPanelId }
                    val to = order.indexOfFirst { it.id == selected }
                    if (from >= 0 && to >= 0) {
                        java.util.Collections.swap(order, from, to)
                        _state.value = _state.value.copy(panels = order,
                            primaryPanelId = if (_state.value.primaryPanelId == selected) duplicate.existingPanelId else _state.value.primaryPanelId)
                    }
                    remove(selected)
                }
                focus(duplicate.existingPanelId)
            }
            MultiViewDuplicateChoice.CANCEL -> Unit
        }
    }

    private fun tune(record: PanelRecord, channelId: Long) {
        record.operation?.cancel()
        record.candidate?.let { close(it) }; record.candidate = null
        if (record.current?.released == true) record.current = null
        val generation = ++generationSequence
        record.generation = generation
        record.channelId = channelId
        record.operation = scope.launch {
            admission.withLock {
                if (!valid(record, generation)) return@withLock
                var pendingToken: ReservationToken? = null
                var runtime: Runtime? = null
                try {
                    if (!foreground && !inPip) { update(record.id) { it.copy(channelId = channelId, status = MultiViewPanelStatus.PAUSED) }; return@withLock }
                    val channel = resolve(channelId)
                    coroutineContext.ensureActive()
                    if (!valid(record, generation)) return@withLock
                    val snapshot = connections.snapshot(channel.sourceId)
                    val maximum = snapshot?.effectiveMaximum
                    val activePlayers = allocatedCount()
                    val old = record.current
                    val expected = expectedReferences[record.id]
                    if (expected != null && (expected.sourceId != channel.sourceId ||
                            (expected.accountId != null && expected.accountId != snapshot?.accountKey))) {
                        throw MultiViewStartException(MultiViewFailure(MultiViewFailureKind.STREAM,
                            "This saved channel's provider account changed. Choose the channel again to authorize its current account."))
                    }
                    val usedWithoutOld = (snapshot?.locallyReserved ?: 0) -
                        if (old?.token?.accountKey == snapshot?.accountKey && old != null) 1 else 0
                    if (maximum != null && usedWithoutOld >= maximum) {
                        throw MultiViewStartException(MultiViewFailure(MultiViewFailureKind.PROVIDER_LIMIT,
                            "This account's configured connection limit is in use. Recordings keep their reserved connections."))
                    }
                    // Unknown allowance never authorizes speculative make-before-break.
                    val seamless = old != null && maximum != null && (snapshot?.locallyReserved ?: 0) < maximum &&
                        activePlayers < hardware.capability.maximumPlayers && activePlayers < 4
                    if (!seamless) { old?.let(::close); record.current = null }
                    if (allocatedCount() >= minOf(4, hardware.capability.maximumPlayers)) {
                        failPanel(record, MultiViewFailure(MultiViewFailureKind.DECODER,
                            "This device cannot start another hardware-decoded panel. Use a smaller layout.")); return@withLock
                    }
                    update(record.id) { if (seamless) it.copy(error = null, failureKind = null) else it.copy(channelId = channelId, channelName = channel.channelName,
                        providerName = channel.providerName, sourceId = channel.sourceId, accountKey = snapshot?.accountKey,
                        status = if (seamless) it.status else MultiViewPanelStatus.STARTING,
                        engineReady = seamless, error = null, failureKind = null, repeatedBuffering = false) }
                    val request = ConnectionRequest("$sessionId:${record.id}", generation, channel.sourceId, ConnectionPurpose.MULTIVIEW)
                    var reserved = connections.reserve(request)
                    if (reserved is ReservationResult.ApprovalRequired) {
                        val prompt = MultiViewApproval(channel.channelName, channel.providerName, reserved.challenge)
                        val response = CompletableDeferred<UnknownConnectionApproval?>()
                        approvalResponse = response
                        _state.value = _state.value.copy(approval = prompt)
                        val approval = try { response.await() } finally {
                            connections.discard(prompt.challenge)
                            if (approvalResponse === response) { approvalResponse = null; _state.value = _state.value.copy(approval = null) }
                        }
                        if (approval == null) throw MultiViewStartException(MultiViewFailure(MultiViewFailureKind.PROVIDER_LIMIT,
                            "The additional connection was canceled. This account's allowance is unknown."))
                        reserved = connections.reserve(request, approval)
                    }
                    val token = (reserved as? ReservationResult.Reserved)?.token ?: throw MultiViewStartException(
                        MultiViewFailure(MultiViewFailureKind.PROVIDER_LIMIT, (reserved as? ReservationResult.Denied)?.reason
                            ?: "Account settings changed. Retry this channel."))
                    pendingToken = token
                    coroutineContext.ensureActive()
                    if (!valid(record, generation)) { connections.release(token); pendingToken = null; return@withLock }
                    val attempt = Runtime(token, channel, generation)
                    runtime = attempt
                    attempt.player = players.create(object : MultiViewPlayer.Listener {
                        override fun ready() = onReady(record, attempt)
                        override fun buffering() = onBuffering(record, attempt)
                        override fun failed(failure: MultiViewFailure) = onFailure(record, attempt, failure)
                    })
                    record.candidate = attempt
                    pendingToken = null
                    connections.registerOwner(token, { if (_state.value.primaryPanelId == record.id) 2 else 1 }) {
                        withContext(Dispatchers.Main.immediate) {
                            if (owns(record, attempt)) {
                                record.operation?.cancel()
                                close(attempt)
                                if (record.current === attempt) record.current = null
                                if (record.candidate === attempt) record.candidate = null
                                val message = "Paused to protect a recording on this account. Retry when capacity is available."
                                if (record.current != null) showCurrent(record, "The replacement paused to protect a recording. Your previous channel is still playing.")
                                else update(record.id) { it.copy(status = MultiViewPanelStatus.PAUSED, engineReady = false, error = message) }
                                applyAudio()
                            }
                        }
                    }
                    if (!seamless) { record.current = attempt; update(record.id) { it.copy(engineReady = true) } }
                    attempt.player.start(channel)
                    val ready = withTimeoutOrNull(startupTimeoutMs) { attempt.ready.await() } == true
                    coroutineContext.ensureActive()
                    if (!valid(record, generation) || attempt.released) return@withLock
                    if (!ready) throw MultiViewStartException(MultiViewFailure(MultiViewFailureKind.NETWORK,
                        "This stream did not become ready. Retry or replace the channel; the other panels are still playing."))
                    if (seamless) old?.let(::close)
                    record.current = attempt; record.candidate = null
                    update(record.id) { it.copy(channelId = channel.channelId, channelName = channel.channelName,
                        providerName = channel.providerName, sourceId = channel.sourceId, accountKey = token.accountKey,
                        status = MultiViewPanelStatus.PLAYING, engineReady = true, error = null, failureKind = null,
                        playbackGeneration = generation) }
                    applyAudio()
                } catch (cancelled: CancellationException) {
                    pendingToken?.let(connections::release)
                    runtime?.takeIf { it !== record.current || it.generation == generation }?.let(::close)
                    if (record.candidate === runtime) record.candidate = null
                    if (record.current?.released == true) record.current = null
                    throw cancelled
                } catch (failure: Exception) {
                    pendingToken?.let(connections::release)
                    runtime?.let { close(it, failed = true) }
                    if (record.candidate === runtime) record.candidate = null
                    if (record.current?.released == true) record.current = null
                    if (valid(record, generation)) {
                        val detail = (failure as? MultiViewStartException)?.failure ?: MultiViewFailure(MultiViewFailureKind.STREAM,
                            "This channel could not start. Retry or replace it.")
                        if (record.current != null) {
                            showCurrent(record, detail.message, detail.kind)
                        } else failPanel(record, detail)
                    }
                    applyAudio()
                }
            }
        }
    }

    private fun valid(record: PanelRecord, generation: Long) = records[record.id] === record && record.generation == generation
    private fun findChannel(channelId: Long) = records.values.firstOrNull { record ->
        record.channelId == channelId || listOfNotNull(record.current, record.candidate).any {
            !it.released && it.channel.channelId == channelId
        }
    }
    private fun owns(record: PanelRecord, runtime: Runtime) = records[record.id] === record && !runtime.released &&
        (record.current === runtime || record.candidate === runtime)
    private fun onReady(record: PanelRecord, runtime: Runtime) {
        if (!owns(record, runtime)) return
        val now = nowMs()
        val meaningful = runtime.bufferingSince?.let { now - it >= 2_000 } == true
        runtime.bufferingSince = null
        if (meaningful) runtime.meaningfulStalls.addLast(now)
        while (runtime.meaningfulStalls.firstOrNull()?.let { now - it > 120_000 } == true) runtime.meaningfulStalls.removeFirst()
        val suggest = preferences.bufferingSuggestions && runtime.meaningfulStalls.size >= 3 &&
            runtime.lastSuggestionAt?.let { now - it >= 60_000 } != false
        if (suggest) runtime.lastSuggestionAt = now
        runtime.wasReady = true
        connections.markActive(runtime.token)
        runtime.ready.complete(true)
        if (record.current === runtime) update(record.id) { it.copy(status = MultiViewPanelStatus.PLAYING, error = null,
            repeatedBuffering = it.repeatedBuffering || suggest) }
    }
    private fun onBuffering(record: PanelRecord, runtime: Runtime) {
        if (!owns(record, runtime)) return
        if (runtime.wasReady && runtime.bufferingSince == null) runtime.bufferingSince = nowMs()
        if (record.current === runtime) update(record.id) { it.copy(status = MultiViewPanelStatus.BUFFERING) }
    }
    private fun onFailure(record: PanelRecord, runtime: Runtime, failure: MultiViewFailure) {
        if (!owns(record, runtime)) return
        runtime.ready.complete(false)
        close(runtime, failed = true, knownLimit = failure.kind == MultiViewFailureKind.PROVIDER_LIMIT)
        if (record.current === runtime) record.current = null
        if (record.candidate === runtime) record.candidate = null
        if (failure.kind == MultiViewFailureKind.DECODER) hardware.decoderFailed(allocatedCount())
        if (record.current == null) failPanel(record, failure) else {
            showCurrent(record, failure.message, failure.kind)
        }
        applyAudio()
    }
    private fun failPanel(record: PanelRecord, failure: MultiViewFailure) = update(record.id) {
        it.copy(status = MultiViewPanelStatus.FAILED, engineReady = false, error = failure.message, failureKind = failure.kind)
    }
    private fun showCurrent(record: PanelRecord, error: String? = null, kind: MultiViewFailureKind? = null) {
        val runtime = record.current?.takeUnless { it.released } ?: return
        val channel = runtime.channel
        record.channelId = channel.channelId
        update(record.id) { it.copy(channelId = channel.channelId, channelName = channel.channelName,
            providerName = channel.providerName, sourceId = channel.sourceId, accountKey = runtime.token.accountKey,
            status = MultiViewPanelStatus.PLAYING, engineReady = true, playbackGeneration = runtime.generation,
            error = error, failureKind = kind) }
    }
    private fun close(runtime: Runtime, failed: Boolean = false, knownLimit: Boolean = false) {
        if (runtime.released) return
        runtime.released = true
        runtime.ready.complete(false)
        connections.beginRelease(runtime.token)
        try { if (runtime.hasPlayer) { runtime.player.setVolume(0f); runtime.player.release() } } finally {
            if (failed) connections.fail(runtime.token, knownLimit) else connections.release(runtime.token)
        }
    }
    private fun allocatedCount() = records.values.flatMap { listOfNotNull(it.current, it.candidate) }
        .distinct().count { !it.released }

    fun retry(panelId: String) {
        val record = records[panelId] ?: return
        if (record.retryCount >= 3) { notice("Retry limit reached. Replace this channel or remove and add it again."); return }
        record.retryCount++
        tune(record, record.channelId)
    }
    fun remove(panelId: String) {
        val record = records.remove(panelId) ?: return
        expectedReferences.remove(panelId)
        ++record.generation
        record.operation?.cancel(); record.current?.let(::close); record.candidate?.let(::close)
        val remaining = _state.value.panels.filterNot { it.id == panelId }
        _state.value = _state.value.copy(panels = remaining,
            focusedPanelId = _state.value.focusedPanelId.takeUnless { it == panelId } ?: remaining.firstOrNull()?.id,
            primaryPanelId = _state.value.primaryPanelId.takeUnless { it == panelId } ?: remaining.firstOrNull()?.id,
            audioPanelId = _state.value.audioPanelId.takeUnless { it == panelId } ?: remaining.firstOrNull()?.id,
            fullscreenPanelId = _state.value.fullscreenPanelId.takeUnless { it == panelId })
        if (remaining.isEmpty()) { started = false; checkpointStore?.clearRecovery(); abandonAudio() } else applyAudio()
    }
    fun move(panelId: String, offset: Int) {
        val panels = _state.value.panels.toMutableList()
        val from = panels.indexOfFirst { it.id == panelId }
        if (from < 0) return
        val to = (from + offset).coerceIn(0, panels.lastIndex)
        if (from != to) { val panel = panels.removeAt(from); panels.add(to, panel); _state.value = _state.value.copy(panels = panels) }
    }
    fun setPrimary(panelId: String) { if (panelId in records) _state.value = _state.value.copy(primaryPanelId = panelId) }
    fun setLayout(layout: MultiViewLayout) { _state.value = _state.value.copy(layout = layout) }
    fun setGuideOpen(open: Boolean) { audioJob?.cancel(); _state.value = _state.value.copy(guideOpen = open) }
    fun focus(panelId: String) {
        if (panelId !in records) return
        _state.value = _state.value.copy(focusedPanelId = panelId)
        audioJob?.cancel()
        if (audioMode == MultiViewAudioMode.FOLLOW_FOCUS && !_state.value.guideOpen) audioJob = scope.launch {
            delay(focusDelayMs)
            if (_state.value.focusedPanelId == panelId && !_state.value.guideOpen) selectAudio(panelId)
        }
    }
    fun selectAudio(panelId: String) {
        if (panelId !in records) return
        audioJob?.cancel()
        _state.value = _state.value.copy(audioPanelId = panelId)
        // An explicit OK/select action permits requesting audio after an interruption.
        audioInterrupted = false
        applyAudio()
    }
    fun setAudioMode(mode: MultiViewAudioMode) { audioJob?.cancel(); audioMode = mode }
    fun setFocusDelay(milliseconds: Long) { focusDelayMs = milliseconds.coerceIn(0, 2_000) }
    fun setFocusDelay(milliseconds: Int) = setFocusDelay(milliseconds.toLong())
    fun setMuted(muted: Boolean) { _state.value = _state.value.copy(muted = muted); applyAudio() }
    fun setFullscreenRestorePolicy(policy: MultiViewRestorePolicy) { restorePolicy = policy }
    fun updatePreferences(preferences: MultiViewPreferences) {
        this.preferences = preferences.validated()
        setAudioMode(preferences.audioMode)
        setFocusDelay(preferences.focusDelayMs)
        setFullscreenRestorePolicy(preferences.fullscreenRestoration)
        if (!preferences.enabled && records.isNotEmpty()) stop()
        if (preferences.recovery == MultiViewRecoveryPolicy.DISABLED) checkpointStore?.clearRecovery()
    }

    private fun applyAudio() {
        // Mute the old owner before requesting focus or unmuting the new one.
        records.values.forEach { it.current?.takeUnless { r -> r.released }?.player?.setVolume(0f)
            it.candidate?.takeUnless { r -> r.released }?.player?.setVolume(0f) }
        val selected = records[_state.value.audioPanelId]?.current?.takeUnless { it.released }
        if (selected == null || _state.value.muted || (!foreground && !inPip) || audioInterrupted) {
            if (selected == null || _state.value.muted || (!foreground && !inPip)) abandonAudio()
            return
        }
        if (!focusLease) focusLease = audioFocus.request { gained ->
            focusLease = gained
            audioInterrupted = !gained
            _state.value = _state.value.copy(audioFocusGranted = gained)
            if (gained) applyAudio() else records.values.forEach { it.current?.player?.setVolume(0f); it.candidate?.player?.setVolume(0f) }
        }
        _state.value = _state.value.copy(audioFocusGranted = focusLease && !audioInterrupted)
        if (focusLease && !audioInterrupted) selected.player.setVolume(1f)
    }
    private fun abandonAudio() { audioFocus.abandon(); focusLease = false; _state.value = _state.value.copy(audioFocusGranted = false) }

    fun answerApproval(allow: Boolean) {
        val prompt = _state.value.approval ?: return
        approvalResponse?.complete(if (allow) connections.approve(prompt.challenge) else null)
    }
    /** Guide/Sports fullscreen requests keep the wall available for checked restoration. */
    fun watchFullscreen(channelId: Long) {
        val existing = findChannel(channelId)
        if (existing != null) {
            _state.value = _state.value.copy(duplicate = MultiViewDuplicate(channelId, existing.id, null))
            resolveDuplicate(MultiViewDuplicateChoice.FOCUS_EXISTING)
            enterFullscreen(existing.id)
            return
        }
        val selected = _state.value.focusedPanelId ?: records.keys.firstOrNull() ?: return
        replace(selected, channelId)
        enterFullscreen(selected)
    }
    fun enterFullscreen(panelId: String) {
        if (panelId !in records) return
        audioJob?.cancel()
        _state.value = _state.value.copy(fullscreenPanelId = panelId, primaryPanelId = panelId,
            focusedPanelId = panelId, audioPanelId = panelId, restorationRequired = false)
        records.values.filter { it.id != panelId }.forEach { pauseRecord(it) }
        applyAudio()
    }
    fun leaveFullscreen(restoreSecondaries: Boolean? = null) {
        if (_state.value.fullscreenPanelId == null) return
        if (restoreSecondaries == null && restorePolicy == MultiViewRestorePolicy.ASK_FIRST) {
            _state.value = _state.value.copy(restorationRequired = true); return
        }
        val restore = restoreSecondaries ?: (restorePolicy == MultiViewRestorePolicy.RESTORE)
        _state.value = _state.value.copy(fullscreenPanelId = null, restorationRequired = false)
        if (restore) resume()
    }
    private fun pauseRecord(record: PanelRecord) {
        ++record.generation; record.operation?.cancel()
        record.current?.let(::close); record.current = null
        record.candidate?.let(::close); record.candidate = null
        update(record.id) { it.copy(status = MultiViewPanelStatus.PAUSED, engineReady = false) }
    }
    fun setForeground(foreground: Boolean) {
        this.foreground = foreground
        if (!foreground && !inPip) {
            audioJob?.cancel(); records.values.forEach(::pauseRecord); abandonAudio()
            if (records.isNotEmpty()) notice("Multi-View paused in the background. Review and resume to revalidate account and device capacity.")
        }
    }
    fun setInPip(inPip: Boolean) {
        this.inPip = inPip
        if (inPip) (_state.value.audioPanelId ?: _state.value.focusedPanelId)?.let(::enterFullscreen)
        else if (!foreground) setForeground(false)
    }
    fun onMemoryPressure() {
        records.values.filter { it.id != _state.value.primaryPanelId }.forEach(::pauseRecord)
        notice("Secondary panels paused to reduce device memory use. Resume a smaller layout when ready.")
    }
    fun resume() {
        if (!foreground && !inPip) return
        val ordered = records.values.sortedBy { if (it.id == _state.value.primaryPanelId) 0 else 1 }
        ordered.filter { it.current == null && it.candidate == null }.forEach { tune(it, it.channelId) }
    }
    fun restorePanels(panels: List<Pair<String, Long>>, layout: MultiViewLayout,
        primaryPanelId: String? = null, audioPanelId: String? = null) {
        stop()
        if (!preferences.enabled) { notice("Enable Multi-View before restoring a layout."); return }
        if (panels.isEmpty()) return
        beforeStart(); started = true
        panels.take(4).distinctBy { it.second }.forEach { (id, channelId) ->
            val safeId = id.takeIf { it.matches(Regex("[A-Za-z0-9_-]{1,80}")) && it !in records } ?: newId()
            records[safeId] = PanelRecord(safeId, channelId)
        }
        val primary = primaryPanelId?.takeIf { it in records } ?: records.keys.first()
        _state.value = _state.value.copy(panels = records.values.map { MultiViewPanel(it.id, it.channelId,
            status = MultiViewPanelStatus.PAUSED) }, primaryPanelId = primary, focusedPanelId = primary,
            audioPanelId = audioPanelId?.takeIf { it in records } ?: primary, layout = layout)
        resume()
    }
    fun capturePresentation(): MultiViewPresentation? {
        if (_state.value.panels.any { it.sourceId <= 0 }) return null
        val panels = _state.value.panels.filter { it.sourceId > 0 }.map {
            MultiViewSavedPanel(it.id, MultiViewChannelReference(it.channelId, it.sourceId, it.accountKey))
        }
        if (panels.isEmpty()) return null
        val primary = _state.value.primaryPanelId?.takeIf { id -> panels.any { it.panelId == id } } ?: panels.first().panelId
        return MultiViewPresentation(panels, primary,
            _state.value.audioPanelId?.takeIf { id -> panels.any { it.panelId == id } }, _state.value.layout, preferences.showLabels)
    }
    fun restorePresentation(presentation: MultiViewPresentation) {
        stop()
        if (!preferences.enabled) { notice("Enable Multi-View before restoring a layout."); return }
        if (presentation.panels.isEmpty()) return
        beforeStart(); started = true
        presentation.panels.take(4).distinctBy { it.channel.channelId }.forEach { saved ->
            val id = saved.panelId.takeIf { it.matches(Regex("[A-Za-z0-9_-]{1,80}")) && it !in records } ?: newId()
            records[id] = PanelRecord(id, saved.channel.channelId)
            expectedReferences[id] = saved.channel
        }
        val primary = presentation.primaryPanelId.takeIf { it in records } ?: records.keys.first()
        _state.value = _state.value.copy(panels = records.values.map { record ->
            val ref = expectedReferences.getValue(record.id)
            MultiViewPanel(record.id, record.channelId, sourceId = ref.sourceId, accountKey = ref.accountId,
                status = MultiViewPanelStatus.PAUSED)
        }, primaryPanelId = primary, focusedPanelId = primary,
            audioPanelId = presentation.audioPanelId?.takeIf { it in records } ?: primary, layout = presentation.layout)
        resume()
    }
    fun player(panelId: String): Player? = records[panelId]?.current?.takeUnless { it.released }?.player?.mediaPlayer
    fun surfaceOwner(panelId: String): PlayerSurfaceOwner? = records[panelId]?.current?.takeUnless { it.released }?.player?.surfaceOwner
    fun clearSurfaces(): Boolean = records.values.mapNotNull { it.current?.player?.surfaceOwner }.map { it.clear() }.all { it }
    fun dismissNotice() { _state.value = _state.value.copy(notice = null) }
    fun dismissBufferingSuggestion(panelId: String) { update(panelId) { it.copy(repeatedBuffering = false) } }
    private fun notice(message: String) { _state.value = _state.value.copy(notice = message) }
    private fun update(id: String, transform: (MultiViewPanel) -> MultiViewPanel) {
        _state.value = _state.value.copy(panels = _state.value.panels.map { if (it.id == id) transform(it) else it })
    }
    fun stop() = stopInternal(clearRecovery = true)
    private fun stopInternal(clearRecovery: Boolean) {
        audioJob?.cancel()
        records.values.toList().forEach { record ->
            ++record.generation; record.operation?.cancel(); record.current?.let(::close); record.candidate?.let(::close)
        }
        records.clear(); expectedReferences.clear(); approvalResponse?.complete(null); approvalResponse = null
        _state.value.approval?.challenge?.let(connections::discard)
        started = false
        val muted = _state.value.muted
        _state.value = MultiViewState(muted = muted)
        if (clearRecovery) checkpointStore?.clearRecovery()
        abandonAudio()
    }
    fun release() {
        if (preferences.recovery == MultiViewRecoveryPolicy.ASK_BEFORE_RESTORE)
            capturePresentation()?.let { checkpointStore?.saveInterrupted(it) }
        observerJobs.forEach { it.cancel() }; observerJobs.clear()
        stopInternal(clearRecovery = false)
        checkpointStore?.close(); checkpointStore = null
    }

    companion object {
        fun create(context: Context, scope: CoroutineScope, beforeStart: () -> Unit = {}): MultiViewSessionManager {
            val application = context.applicationContext
            val graph = ServiceLocator.get(application)
            val allocator = AccountConnectionManager.get(application)
            val manager = MultiViewSessionManager(scope, resolve = { id ->
                val channel = graph.catalogRepository.channel(id)
                    ?: throw IllegalArgumentException("Channel is no longer available")
                val source = graph.sourceRepository.byId(channel.sourceId)?.takeIf { it.enabled }
                    ?: throw IllegalArgumentException("Provider is no longer available")
                val categories = graph.database.categories().allByKind(app.opentv.data.model.StreamKind.LIVE)
                require(app.opentv.data.repo.LocalCustomGroups.visible(channel, categories,
                    graph.settings.hiddenCategories.value, graph.settings.hiddenUnlocked.value)) {
                    "Channel is no longer available"
                }
                allocator.registerSource(source)
                ResolvedMultiViewChannel(channel.id, source.id, channel.shownName, source.name,
                    graph.catalogRepository.resolvePlaybackUrl(channel, source), source.userAgent)
            }, connections = object : MultiViewConnections {
                override fun snapshot(sourceId: Long) = allocator.snapshotForSource(sourceId)
                override fun reserve(request: ConnectionRequest, approval: UnknownConnectionApproval?) = allocator.reserve(request, approval)
                override fun approve(challenge: UnknownConnectionChallenge) = allocator.approve(challenge)
                override fun discard(challenge: UnknownConnectionChallenge) = allocator.discardChallenge(challenge)
                override fun markActive(token: ReservationToken) = allocator.markActive(token)
                override fun beginRelease(token: ReservationToken) = allocator.beginRelease(token)
                override fun release(token: ReservationToken) = allocator.release(token)
                override fun fail(token: ReservationToken, knownConnectionLimit: Boolean) = allocator.fail(token, knownConnectionLimit)
                override fun registerOwner(token: ReservationToken, reclamationPriority: () -> Int, close: suspend () -> Unit) =
                    allocator.registerOwner(token, reclamationPriority, close)
            }, players = MultiViewPlayerFactory { AndroidMultiViewPlayer(application, graph.streamingHttpClient, it) },
                hardware = AndroidMultiViewHardware(application), audioFocus = AndroidMultiViewAudioFocus(application), beforeStart = beforeStart)
            val checkpoints = MultiViewPresetStore(application)
            manager.checkpointStore = checkpoints
            manager.updatePreferences(graph.settings.multiViewPreferences.value)
            manager.observerJobs += scope.launch { graph.settings.multiViewPreferences.collect(manager::updatePreferences) }
            manager.observerJobs += scope.launch {
                manager.state.collect {
                    val preferences = graph.settings.multiViewPreferences.value
                    if (preferences.recovery == MultiViewRecoveryPolicy.ASK_BEFORE_RESTORE) {
                        manager.capturePresentation()?.let { presentation ->
                            if (checkpoints.recovery.value != presentation) checkpoints.saveInterrupted(presentation)
                        }
                    }
                }
            }
            return manager
        }
    }
}

private class MultiViewStartException(val failure: MultiViewFailure) : Exception(failure.message)
