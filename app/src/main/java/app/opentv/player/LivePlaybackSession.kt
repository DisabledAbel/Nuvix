/* Copyright (C) 2026 Nuvix Contributors. Licensed under GPL-3.0-or-later. */
package app.opentv.player

import android.app.Application
import android.content.ComponentCallbacks2
import androidx.annotation.OptIn
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import app.opentv.core.RecentChannels
import app.opentv.core.ServiceLocator
import app.opentv.core.SleepTimer
import app.opentv.connections.AccountConnectionManager
import app.opentv.connections.ConnectionPurpose
import app.opentv.connections.ConnectionRequest
import app.opentv.connections.ReservationResult
import app.opentv.connections.ReservationToken
import app.opentv.connections.UnknownConnectionChallenge
import app.opentv.connections.UnknownConnectionApproval
import app.opentv.data.model.Channel
import app.opentv.data.model.Programme
import app.opentv.data.model.shownName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

data class LiveConnectionPrompt(val channelName: String, val challenge: UnknownConnectionChallenge)

/**
 * The Activity's ViewModel owns live playback; screens only borrow a video surface.
 * Survives configuration changes, but deliberately never serializes a player or provider URL.
 * After process death the existing startup/resume preference resolves fresh channel metadata.
 */
@OptIn(UnstableApi::class)
class LivePlaybackSession(application: Application) : AndroidViewModel(application) {
    private val graph = ServiceLocator.get(application)
    private val connections = AccountConnectionManager.get(application)
    private val connectionSessionId = "live:${UUID.randomUUID()}"
    private var connectionGeneration = 0L
    private var connectionToken: ReservationToken? = null
    private val _connectionPrompt = MutableStateFlow<LiveConnectionPrompt?>(null)
    val connectionPrompt = _connectionPrompt.asStateFlow()
    private var approvalResponse: CompletableDeferred<UnknownConnectionApproval?>? = null
    private var implementation: ControllerEngine? = null
    private var guidePreview: PlayerController? = null
    private var guidePreviewReleased: (() -> Unit)? = null
    private var nonLivePlayback: PlayerController? = null
    private val _controllerOrNull = MutableStateFlow<PlayerController?>(null)
    val controllerOrNull = _controllerOrNull.asStateFlow()
    /** Memory-only startup guard: configuration changes must not steal a browsing destination. */
    var startupNavigationHandled: Boolean = false
    val surfaceOwner = PlayerSurfaceOwner { implementation?.controller?.player }

    private val coordinator = LivePlaybackCoordinator(
        scope = viewModelScope,
        findChannel = { graph.catalogRepository.channel(it) },
        resolve = { channel ->
            val source = graph.sourceRepository.byId(channel.sourceId)
            val url = graph.catalogRepository.resolvePlaybackUrl(channel, source)
            ResolvedLiveChannel(
                channel, graph.catalogRepository.variants(channel),
                PlayerController.Request(url, channel.shownName, source?.userAgent ?: "Nuvix/0.1 (Android)"),
            )
        },
        engineFactory = {
            surfaceOwner.reset()
            ControllerEngine(application).also { implementation = it; _controllerOrNull.value = it.controller }
        },
        onTune = { channel ->
            graph.settings.lastChannelId = channel.id
            RecentChannels.get(application).record(channel.id)
        },
        onRelease = {
            connectionToken?.let(connections::beginRelease)
            surfaceOwner.clear(); implementation = null; _controllerOrNull.value = null
        },
        preparePlayback = ::reserveLiveConnection,
        afterEngineRelease = {
            connectionToken?.let(connections::release)
            connectionToken = null
        },
    )

    val currentChannel = coordinator.currentChannel
    val previousChannelId = coordinator.previousChannelId
    val variants = coordinator.variants
    val active = coordinator.active
    val engineReady = coordinator.engineReady
    val guidePreviewSuppressed = coordinator.guidePreviewSuppressed
    val paused = coordinator.paused
    val state = coordinator.state

    /** Used only by the fullscreen controls or an active preview, never to discover activity. */
    val controller: PlayerController
        get() = (coordinator.ensureEngine() as ControllerEngine).controller

    init {
        viewModelScope.launch {
            combine(graph.settings.persistentLivePlayback, graph.settings.continueAudioWhileBrowsing) { persistent, audio -> persistent to audio }
                .collect { (persistent, audio) -> coordinator.updatePreferences(persistent, audio) }
        }
        viewModelScope.launch {
            SleepTimer.deadline.collectLatest { deadline ->
                if (deadline != null) {
                    delay((deadline - System.currentTimeMillis()).coerceAtLeast(0))
                    // Dedicated VOD/recording screens own their existing timer/back behavior.
                    if (nonLivePlayback == null) {
                        SleepTimer.clear()
                        stop()
                    }
                }
            }
        }
    }

    private suspend fun reserveLiveConnection(channel: Channel, releaseCurrent: () -> Unit) {
        val source = graph.sourceRepository.byId(channel.sourceId)?.takeIf { it.enabled }
            ?: throw LiveConnectionException("This provider is no longer available.")
        val accountKey = connections.registerSource(source)
        val snapshot = connections.snapshotForSource(source.id)
        val old = connectionToken
        val maximum = snapshot?.effectiveMaximum
        val usedWithoutOld = (snapshot?.locallyReserved ?: 0) - if (old?.accountKey == accountKey) 1 else 0
        if (maximum != null && usedWithoutOld >= maximum) {
            throw LiveConnectionException("This account's configured connection limit is in use. Recordings keep their reserved connections.")
        }
        // This foundation deliberately uses safe switching. Resolve first, then close the real
        // old player before freeing its slot; a pending reservation never opens a stream.
        if (old?.accountKey == accountKey && (maximum == null || (snapshot?.locallyReserved ?: 0) >= maximum)) {
            releaseCurrent()
        }
        val request = ConnectionRequest(connectionSessionId, ++connectionGeneration, source.id, ConnectionPurpose.LIVE)
        var result = connections.reserve(request)
        if (result is ReservationResult.ApprovalRequired) {
            val response = CompletableDeferred<UnknownConnectionApproval?>()
            approvalResponse = response
            val prompt = LiveConnectionPrompt(channel.shownName, result.challenge)
            _connectionPrompt.value = prompt
            val approval = try { response.await() } finally {
                connections.discardChallenge(prompt.challenge)
                if (approvalResponse === response) {
                    approvalResponse = null
                    _connectionPrompt.value = null
                }
            }
            if (approval == null) throw LiveConnectionException("The additional connection was canceled. Your provider's allowance is unknown.")
            result = connections.reserve(request, approval)
        }
        val token = (result as? ReservationResult.Reserved)?.token ?: throw LiveConnectionException(
            (result as? ReservationResult.Denied)?.reason ?: "Connection settings changed. Please retry.",
        )
        try {
            releaseCurrent()
            connectionToken = token
            connections.registerOwner(token) {
                withContext(Dispatchers.Main.immediate) {
                    if (connectionToken == token) {
                        stop()
                        app.opentv.core.StatusBus.set("Live TV paused to give a recording account capacity. Resume it when you are ready.")
                    }
                }
            }
        } catch (failure: Exception) {
            connections.release(token)
            if (connectionToken == token) connectionToken = null
            throw failure
        }
    }

    fun answerConnectionPrompt(allow: Boolean) {
        val prompt = _connectionPrompt.value ?: return
        approvalResponse?.complete(if (allow) connections.approve(prompt.challenge) else null)
    }

    // The optional highlighted-channel preview registers only its application-context controller.
    // This closes it synchronously for notification/startup tunes, before Compose can dispose it.
    fun registerGuidePreview(controller: PlayerController, onReleased: () -> Unit = {}) {
        if (active.value || guidePreviewSuppressed.value) {
            controller.release()
            onReleased()
        } else {
            if (guidePreview !== controller) releaseGuidePreview()
            guidePreview = controller
            guidePreviewReleased = onReleased
        }
    }
    fun unregisterGuidePreview(controller: PlayerController) {
        if (guidePreview === controller) releaseGuidePreview()
        else controller.release()
    }
    private fun releaseGuidePreview() {
        guidePreview?.release()
        guidePreview = null
        guidePreviewReleased?.invoke()
        guidePreviewReleased = null
    }

    /** Dedicated VOD/recording screens retain their implementation but relinquish synchronously. */
    fun registerNonLivePlayback(controller: PlayerController) {
        releaseGuidePreview()
        coordinator.stopForOtherPlayback()
        if (nonLivePlayback !== controller) releaseNonLivePlayback()
        nonLivePlayback = controller
    }
    fun unregisterNonLivePlayback(controller: PlayerController) {
        if (nonLivePlayback === controller) releaseNonLivePlayback()
        else controller.release()
    }
    private fun releaseNonLivePlayback() {
        nonLivePlayback?.release()
        nonLivePlayback = null
    }

    fun openChannel(id: Long) { releaseGuidePreview(); releaseNonLivePlayback(); coordinator.openChannel(id) }
    fun tune(channel: Channel) { releaseGuidePreview(); releaseNonLivePlayback(); coordinator.tune(channel) }
    fun clearDiagnostics() {
        implementation?.controller?.diagnostics?.clear()
        nonLivePlayback?.diagnostics?.clear()
    }
    fun retry() = coordinator.retry()
    fun stop() { releaseGuidePreview(); coordinator.stopForOtherPlayback() }
    /** Capture admission closes only viewing sessions whose account capacity is needed. */
    fun recordChannel(channel: Channel, programme: Programme? = null) {
        viewModelScope.launch { graph.recordingEngine.startChannel(channel, programme) }
    }
    fun setPaused(paused: Boolean) = coordinator.setPaused(paused)
    fun setPresentation(presentation: LivePresentation) {
        if (presentation != LivePresentation.BROWSING) releaseGuidePreview()
        coordinator.setPresentation(presentation)
    }
    fun setForeground(foreground: Boolean) {
        if (!foreground) releaseGuidePreview()
        coordinator.setForeground(foreground)
    }
    fun setInPip(inPip: Boolean) = coordinator.setInPip(inPip)

    fun onMemoryPressure(level: Int) {
        // RUNNING_LOW/CRITICAL concern the foreground; UI_HIDDEN alone is routine backgrounding.
        val critical = level == ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW ||
            level == ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL ||
            level >= ComponentCallbacks2.TRIM_MEMORY_BACKGROUND
        if (critical) releaseGuidePreview()
        coordinator.onMemoryPressure(critical)
    }

    override fun onCleared() {
        releaseGuidePreview()
        releaseNonLivePlayback()
        coordinator.destroy()
        super.onCleared()
    }

    private inner class ControllerEngine(application: Application) : LivePlaybackEngine {
        private val scope = CoroutineScope(SupervisorJob(viewModelScope.coroutineContext[Job]) + Dispatchers.Main.immediate)
        val controller = PlayerController(
            application, scope, graph.streamingHttpClient,
            subtitlesEnabled = graph.settings.subtitlesEnabled.value,
            dvr = graph.settings.livePauseEnabled.value,
        )
        override val state = controller.state
        override val paused = controller.userPaused
        private var browsingMuted = false
        private var savedVolume = controller.player.volume
        private var applyingVolume = false
        private val volumeListener = object : Player.Listener {
            override fun onVolumeChanged(volume: Float) {
                if (applyingVolume) return
                savedVolume = volume
                if (browsingMuted) applyVolume(0f)
            }
        }
        init {
            controller.player.addListener(volumeListener)
            scope.launch {
                controller.state.collect { state ->
                    if (state is PlayerController.State.Playing && implementation === this@ControllerEngine) {
                        connectionToken?.let(connections::markActive)
                    }
                }
            }
        }
        override fun play(request: PlayerController.Request) = controller.play(request, debounce = false)
        override fun setPaused(paused: Boolean) = controller.setUserPaused(paused)
        override fun setSuspended(suspended: Boolean) = controller.setSuspended(suspended)
        override fun setBrowsingMuted(muted: Boolean) {
            if (browsingMuted == muted) return
            if (muted) savedVolume = controller.player.volume
            browsingMuted = muted
            applyVolume(if (muted) 0f else savedVolume)
        }
        private fun applyVolume(volume: Float) {
            applyingVolume = true
            try { controller.player.volume = volume } finally { applyingVolume = false }
        }
        override fun release() {
            controller.player.removeListener(volumeListener)
            controller.release()
            scope.cancel()
        }
    }
}
