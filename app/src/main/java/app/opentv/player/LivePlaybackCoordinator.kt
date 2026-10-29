/* Copyright (C) 2026 Nuvix Contributors. Licensed under GPL-3.0-or-later. */
package app.opentv.player

import app.opentv.data.model.Channel
import app.opentv.data.model.shownName
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.coroutines.coroutineContext

/** A presentation change transfers the surface, never the media item. */
enum class LivePresentation { FULLSCREEN, BROWSING, HIDDEN, PIP }

/** Small playback port so ownership and navigation can be exercised without a decoder or network. */
internal interface LivePlaybackEngine {
    val state: StateFlow<PlayerController.State>
    val paused: StateFlow<Boolean>
    /** Starts an explicitly requested channel on this playback engine. */
    fun play(request: PlayerController.Request)
    /** Applies the user's pause choice to the retained engine. */
    fun setPaused(paused: Boolean)
    /** Controls browsing-policy muting independently of the user's volume choice. */
    fun setBrowsingMuted(muted: Boolean)
    /** Suspends or resumes the engine according to presentation policy. */
    fun setSuspended(suspended: Boolean)
    /** Closes the playback engine and releases its resources. */
    fun release()
}

/** A safe domain failure: never expose arbitrary provider exception messages. */
internal class RecordingOwnsLiveSourceException : IllegalStateException()
/** Live admission failure carrying a message safe to show without provider details. */
internal class LiveConnectionException(val safeMessage: String) : IllegalStateException()

/** Resolved channel and variants supplied to an explicit tune request. */
internal data class ResolvedLiveChannel(
    val channel: Channel,
    val variants: List<Channel>,
    val request: PlayerController.Request,
)

/**
 * Main-thread confined session policy. Neither navigation nor surface disposal owns playback.
 * A cancelled source lookup cannot replace a newer channel, even if its source ignores cancellation.
 */
internal class LivePlaybackCoordinator(
    private val scope: CoroutineScope,
    private val findChannel: suspend (Long) -> Channel?,
    private val resolve: suspend (Channel) -> ResolvedLiveChannel,
    private val engineFactory: () -> LivePlaybackEngine,
    private val onTune: (Channel) -> Unit = {},
    private val onRelease: () -> Unit = {},
    private val preparePlayback: suspend (Channel, releaseCurrent: () -> Unit) -> Unit = { _, _ -> },
    private val afterEngineRelease: () -> Unit = {},
    private val beforeResolve: suspend (Channel, releaseCurrent: () -> Unit) -> Unit = { _, _ -> },
    private val transitionDiagnostics: ChannelTransitionDiagnostics? = null,
    private val recoveryBudget: RecoveryBudget? = null,
    private val clock: () -> Long = { System.nanoTime() / 1_000_000 },
) {
    /** Synchronous selection cursor: repeated remote events must advance even during lookup. */
    private val _requestedChannelId = MutableStateFlow<Long?>(null)
    val requestedChannelId = _requestedChannelId.asStateFlow()
    private val _requestedChannel = MutableStateFlow<Channel?>(null)
    val requestedChannel = _requestedChannel.asStateFlow()
    private val _preparingChannel = MutableStateFlow<Channel?>(null)
    val preparingChannel = _preparingChannel.asStateFlow()
    private val _playingChannel = MutableStateFlow<Channel?>(null)
    val playingChannel = _playingChannel.asStateFlow()
    private val _currentChannel = MutableStateFlow<Channel?>(null)
    val currentChannel = _currentChannel.asStateFlow()
    private val _previousChannelId = MutableStateFlow<Long?>(null)
    val previousChannelId = _previousChannelId.asStateFlow()
    private val _variants = MutableStateFlow<List<Channel>>(emptyList())
    val variants = _variants.asStateFlow()
    private val _active = MutableStateFlow(false)
    val active = _active.asStateFlow()
    private val _paused = MutableStateFlow(false)
    val paused = _paused.asStateFlow()
    private val _state = MutableStateFlow<PlayerController.State>(PlayerController.State.Idle)
    val state = _state.asStateFlow()

    private val _engineReady = MutableStateFlow(false)
    val engineReady = _engineReady.asStateFlow()
    private val _guidePreviewSuppressed = MutableStateFlow(false)
    private var policyPreviewSuppressed = false
    private var optionalPreviewSuppressed = false
    val guidePreviewSuppressed = _guidePreviewSuppressed.asStateFlow()
    private val _playbackAllowed = MutableStateFlow(true)
    val playbackAllowed = _playbackAllowed.asStateFlow()
    private var recoveringResolution = false
    private var resolving = false
    val recoveryBlocked: Boolean get() = resolving || resolutionFailed
    private var resolutionFailed = false
    private var engine: LivePlaybackEngine? = null
    private var engineStateJob: Job? = null
    private var enginePausedJob: Job? = null
    private var requestJob: Job? = null
    private var requestedId: Long? = null
    private var playingChannelId: Long? = null
    private var lastTunedChannelId: Long? = null
    private var failedChannel: Channel? = null
    private var generation = 0L
    private var persistent = true
    private var continueAudio = true
    private var presentation = LivePresentation.FULLSCREEN
    val presentationMode: LivePresentation get() = presentation
    private var foreground = true
    private var inPip = false
    private var destroyed = false

    /** Creates the shared live engine lazily and reuses it for subsequent presentation changes. */
    fun ensureEngine(): LivePlaybackEngine {
        check(!destroyed) { "Live session is already destroyed" }
        return engine ?: engineFactory().also { created ->
            engine = created
            _engineReady.value = true
            engineStateJob = scope.launch {
                created.state.collect {
                    if (engine === created && !resolving && !resolutionFailed) {
                        _state.value = it
                        if (it is PlayerController.State.Playing) {
                            _playingChannel.value = _currentChannel.value
                            _preparingChannel.value = null
                        }
                    }
                }
            }
            created.setPaused(_paused.value)
            enginePausedJob = scope.launch { created.paused.collect { _paused.value = it } }
            applyPolicy()
        }
    }

    /** Resolves and admits an explicit channel request while guarding against stale generations. */
    fun openChannel(id: Long) {
        request(id, rememberPrevious = true) { findChannel(id) }
    }

    /** Quality changes preserve the channel-list's previous-channel recall. */
    fun tune(channel: Channel) {
        request(channel.id, rememberPrevious = false) { channel }
    }

    /** User pause or disabled automatic recovery must also cancel a replacement still resolving. */
    fun cancelPendingTune() {
        if (requestedId == null && !recoveringResolution) return
        val canceledRecovery = recoveringResolution
        recoveringResolution = false
        generation++
        requestJob?.cancel()
        requestJob = null
        requestedId = null
        resolving = false
        resolutionFailed = false
        _requestedChannelId.value = playingChannelId
        _requestedChannel.value = _currentChannel.value
        _preparingChannel.value = null
        if (canceledRecovery) {
            resolutionFailed = true
            recoveryBudget?.record(RecoveryEvent.CANCELED, clock())
            recoveryBudget?.publish(RecoveryState.CANCELED, clock(), RecoveryFailure.CANCELED)
            _requestedChannelId.value = _currentChannel.value?.id
            _state.value = PlayerController.State.Error(_currentChannel.value?.shownName.orEmpty(),
                "Recovery canceled. Retry when you are ready to resume this channel.", true)
        } else _state.value = engine?.state?.value ?: PlayerController.State.Idle
    }

    /** Starts a resolved request only if it still belongs to the current tune generation. */
    private fun request(id: Long, rememberPrevious: Boolean, recovery: Boolean = false, channel: suspend () -> Channel?) {
        if (destroyed || requestedId == id) return
        _requestedChannelId.value = id
        setPolicyPreviewSuppressed(false)
        if (_active.value && !resolutionFailed && playingChannelId == id && _state.value !is PlayerController.State.Error) {
            // Returning to the current channel cancels an obsolete in-flight different selection.
            if (requestedId != null) {
                generation++
                requestJob?.cancel()
                requestedId = null
                resolving = false
                _requestedChannel.value = _currentChannel.value
                _preparingChannel.value = null
                engine?.state?.value?.let { _state.value = it }
            }
            return
        }
        recoveringResolution = recovery
        requestedId = id
        _requestedChannel.value = null
        _preparingChannel.value = null
        resolving = true
        resolutionFailed = false
        _state.value = PlayerController.State.Buffering("")
        _active.value = true
        val token = ++generation
        transitionDiagnostics?.begin(token)
        requestJob?.cancel()
        requestJob = scope.launch {
            var selected: Channel? = null
            var admitted = false
            try {
                selected = channel() ?: run {
                    if (token == generation) {
                        transitionDiagnostics?.record(token, ChannelTransitionPhase.FAILED)
                        resolutionFailed = true
                        _state.value = PlayerController.State.Error("", "Channel is no longer available", false)
                        if (recovery) recoveryBudget?.publish(RecoveryState.FAILED, clock(), RecoveryFailure.MISSING_STREAM)
                    }
                    return@launch
                }
                if (_currentChannel.value == null && token == generation) {
                    _currentChannel.value = selected
                    _variants.value = listOf(selected)
                }
                coroutineContext.ensureActive()
                if (token != generation) return@launch
                _requestedChannel.value = selected
                _state.value = PlayerController.State.Buffering(selected.shownName)
                // Some portals cannot mint a replacement link while a constrained stream is open.
                // Admission preflight closes only that account's viewer, before provider I/O.
                beforeResolve(selected) { if (token == generation) releaseEngine() }
                coroutineContext.ensureActive()
                if (token != generation) return@launch
                _preparingChannel.value = selected
                val resolved = resolve(selected)
                coroutineContext.ensureActive()
                if (token != generation) return@launch
                preparePlayback(resolved.channel) { if (token == generation) releaseEngine() }
                admitted = true
                coroutineContext.ensureActive()
                if (token != generation) return@launch
                val playback = ensureEngine()
                if (rememberPrevious) lastTunedChannelId?.takeIf { it != id }?.let { _previousChannelId.value = it }
                _currentChannel.value = resolved.channel
                playingChannelId = resolved.channel.id
                _variants.value = resolved.variants
                _paused.value = false
                failedChannel = null
                resolving = false
                playback.play(resolved.request)
                recoveringResolution = false
                lastTunedChannelId = resolved.channel.id
                playback.setPaused(false)
                _state.value = playback.state.value.let {
                    if (it == PlayerController.State.Idle) PlayerController.State.Buffering(resolved.channel.shownName) else it
                }
                _active.value = true
                applyPolicy()
                onTune(resolved.channel)
            } catch (cancelled: CancellationException) {
                if (admitted && token == generation) releaseEngine()
                throw cancelled
            } catch (failure: Exception) {
                // Provider exceptions often contain credential-bearing URLs. Keep them out of UI/logs.
                if (token == generation) {
                    transitionDiagnostics?.record(token, ChannelTransitionPhase.FAILED)
                    _preparingChannel.value = null
                    if (admitted) releaseEngine()
                    failedChannel = selected
                    resolutionFailed = true
                    val message = if (failure is RecordingOwnsLiveSourceException) {
                        "A recording is using this provider. Play the recording or stop it before watching live TV."
                    } else if (failure is LiveConnectionException) failure.safeMessage
                    else "Unable to open this channel. Please retry."
                    _state.value = PlayerController.State.Error(selected?.shownName.orEmpty(), message, true)
                    if (recovery) {
                        val budget = recoveryBudget
                        val transient = generateSequence<Throwable>(failure) { it.cause }.take(8).any {
                            it is java.net.SocketTimeoutException || it is java.net.UnknownHostException || it is java.net.ConnectException
                        }
                        if (budget != null && transient && budget.attempts < budget.policy.backoffMs.size) {
                            budget.publish(RecoveryState.WAITING, clock(), RecoveryFailure.NETWORK)
                            _state.value = PlayerController.State.Buffering(selected?.shownName.orEmpty())
                            requestJob = scope.launch {
                                kotlinx.coroutines.delay(budget.policy.backoffMs[budget.attempts])
                                if (token != generation || _requestedChannelId.value != id || !_playbackAllowed.value || _paused.value) return@launch
                                budget.attempts++
                                budget.publish(RecoveryState.RECOVERING, clock(), RecoveryFailure.NETWORK)
                                request(id, rememberPrevious = false, recovery = true) { findChannel(id) }
                            }
                        } else {
                            budget?.record(RecoveryEvent.FAILED, clock())
                            budget?.publish(RecoveryState.FAILED, clock(), if (transient) RecoveryFailure.NETWORK else if (failure is LiveConnectionException) RecoveryFailure.CONNECTION_LIMIT else RecoveryFailure.UNKNOWN)
                            recoveringResolution = false
                        }
                    }
                }
            } finally {
                if (token == generation) { requestedId = null; resolving = false }
            }
        }
    }

    /** Re-resolve expiring provider links, while normal navigation never resolves them again. */
    fun retry() {
        val channel = failedChannel ?: _currentChannel.value ?: return
        requestedId = null
        // Retry is deliberately a fresh request; same-channel navigation remains a no-op.
        _active.value = false
        request(channel.id, rememberPrevious = false) { findChannel(channel.id) }
    }

    /** Provider edits invalidate pending work without discarding the selected channel or touching DVR. */
    fun interrupt(message: String) {
        generation++; requestJob?.cancel(); requestJob = null; requestedId = null
        resolving = false; resolutionFailed = true; recoveringResolution = false
        releaseEngine()
        recoveryBudget?.record(RecoveryEvent.CANCELED, clock())
        recoveryBudget?.publish(RecoveryState.CANCELED, clock(), RecoveryFailure.CANCELED)
        _preparingChannel.value = null
        _state.value = PlayerController.State.Error(_currentChannel.value?.shownName.orEmpty(), message, true)
    }

    /** Recovery is break-before-make even when spare capacity exists. Retains the selected channel. */
    fun reconnect() {
        if (destroyed || resolving || !_active.value || _paused.value || !_playbackAllowed.value) return
        val channel = _currentChannel.value ?: return
        if (_requestedChannelId.value != channel.id) return
        releaseEngine() // onRelease → synchronous player.release → afterEngineRelease frees only live's slot.
        requestedId = null
        _active.value = false
        request(channel.id, rememberPrevious = false, recovery = true) { findChannel(channel.id) }
    }

    /** Updates the explicit pause choice and reapplies retained playback policy. */
    fun setPaused(paused: Boolean) {
        _paused.value = paused
        if (paused && recoveringResolution) {
            cancelPendingTune()
            requestJob?.cancel()
            recoveringResolution = false
        }
        engine?.setPaused(paused)
    }

    /** Applies changed persistence, preview and browsing-audio preferences. */
    fun updatePreferences(persistent: Boolean, continueAudio: Boolean) {
        this.persistent = persistent
        this.continueAudio = continueAudio
        if (!persistent && presentation != LivePresentation.FULLSCREEN && presentation != LivePresentation.PIP && !inPip) stop()
        else applyPolicy()
    }

    /** Changes the live presentation target and reapplies playback policy without retuning. */
    fun setPresentation(value: LivePresentation) {
        presentation = value
        if (!persistent && value != LivePresentation.FULLSCREEN && value != LivePresentation.PIP && !inPip) stop()
        else applyPolicy()
    }

    /** Updates foreground state used by live playback policy. */
    fun setForeground(value: Boolean) { foreground = value; applyPolicy() }
    /** Updates picture-in-picture state used by live playback policy. */
    fun setInPip(value: Boolean) { inPip = value; applyPolicy() }

    /** Reconciles engine suspension and browsing audio with the current presentation and settings. */
    private fun applyPolicy() {
        val pip = inPip || presentation == LivePresentation.PIP
        _playbackAllowed.value = (foreground && presentation != LivePresentation.HIDDEN) || pip
        if (!_playbackAllowed.value && recoveringResolution) {
            requestJob?.cancel()
            cancelPendingTune()
            recoveringResolution = false
            recoveryBudget?.publish(RecoveryState.CANCELED, clock(), RecoveryFailure.CANCELED)
        }
        engine?.setBrowsingMuted(!continueAudio && presentation == LivePresentation.BROWSING && !pip)
        engine?.setSuspended((!foreground || presentation == LivePresentation.HIDDEN) && !pip)
    }

    /** Release only this optional live decoder; recordings have entirely separate ownership. */
    fun onMemoryPressure(critical: Boolean) {
        if (critical && presentation != LivePresentation.FULLSCREEN && presentation != LivePresentation.PIP && !inPip) {
            setPolicyPreviewSuppressed(true)
            stop()
        }
    }

    /** A scheduled capture takes the provider connection; never stop the recording itself. */
    fun onRecordingStarted(sourceId: Long) {
        if (_currentChannel.value?.sourceId == sourceId) stopForOtherPlayback()
    }

    /** Recovery review is an additional gate; clearing it cannot undo recording/memory policy. */
    fun setOptionalPreviewSuppressed(suppressed: Boolean) {
        optionalPreviewSuppressed = suppressed
        _guidePreviewSuppressed.value = policyPreviewSuppressed || optionalPreviewSuppressed
    }

    private fun setPolicyPreviewSuppressed(suppressed: Boolean) {
        policyPreviewSuppressed = suppressed
        _guidePreviewSuppressed.value = policyPreviewSuppressed || optionalPreviewSuppressed
    }

    /** Releases live playback before another media session takes ownership. */
    fun stopForOtherPlayback() {
        // An outgoing guide must not replace the relinquished stream with its optional preview.
        setPolicyPreviewSuppressed(true)
        stop()
    }

    /** Invalidates pending tune requests and closes the retained live engine. */
    fun stop() {
        generation++
        requestJob?.cancel()
        requestJob = null
        requestedId = null
        playingChannelId = null
        lastTunedChannelId = null
        _requestedChannelId.value = null
        _requestedChannel.value = null
        _preparingChannel.value = null
        _playingChannel.value = null
        failedChannel = null
        resolving = false
        resolutionFailed = false
        releaseEngine()
        _previousChannelId.value = null
        _active.value = false
        _currentChannel.value = null
        _variants.value = emptyList()
        _paused.value = false
        _state.value = PlayerController.State.Idle
    }

    /** Closing the decoder/HTTP owner precedes releasing its account reservation. */
    private fun releaseEngine() {
        val closing = engine != null
        if (closing) transitionDiagnostics?.record(generation, ChannelTransitionPhase.CLOSE_STARTED)
        engineStateJob?.cancel()
        engineStateJob = null
        enginePausedJob?.cancel()
        enginePausedJob = null
        onRelease()
        engine?.release()
        engine = null
        playingChannelId = null
        _engineReady.value = false
        _playingChannel.value = null
        if (closing) transitionDiagnostics?.record(generation, ChannelTransitionPhase.CLOSE_COMPLETED)
        afterEngineRelease()
    }

    /** Closes the coordinator and releases retained playback ownership. */
    fun destroy() { if (!destroyed) { stop(); destroyed = true } }
}
