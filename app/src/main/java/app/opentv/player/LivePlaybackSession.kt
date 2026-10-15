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
import app.opentv.data.model.Channel
import app.opentv.data.model.Programme
import app.opentv.data.model.shownName
import kotlinx.coroutines.CoroutineScope
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

/**
 * The Activity's ViewModel owns live playback; screens only borrow a video surface.
 * Survives configuration changes, but deliberately never serializes a player or provider URL.
 * After process death the existing startup/resume preference resolves fresh channel metadata.
 */
@OptIn(UnstableApi::class)
class LivePlaybackSession(application: Application) : AndroidViewModel(application) {
    private val graph = ServiceLocator.get(application)
    private var implementation: ControllerEngine? = null
    private var guidePreview: PlayerController? = null
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
            // Also guard requests arriving after the initial recording observer snapshot.
            if (graph.recordingRepository.active().any { it.sourceId == channel.sourceId }) {
                throw RecordingOwnsLiveSourceException()
            }
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
        onRelease = { surfaceOwner.clear(); implementation = null; _controllerOrNull.value = null },
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
            var knownRecordingIds = emptySet<Long>()
            graph.recordingRepository.observeActive().collect { recordings ->
                val newlyStarted = recordings.filter { it.id !in knownRecordingIds }
                knownRecordingIds = recordings.mapTo(mutableSetOf()) { it.id }
                newlyStarted.forEach { coordinator.onRecordingStarted(it.sourceId) }
            }
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

    // The optional highlighted-channel preview registers only its application-context controller.
    // This closes it synchronously for notification/startup tunes, before Compose can dispose it.
    fun registerGuidePreview(controller: PlayerController) {
        if (active.value || guidePreviewSuppressed.value) {
            controller.release()
        } else {
            if (guidePreview !== controller) releaseGuidePreview()
            guidePreview = controller
        }
    }
    fun unregisterGuidePreview(controller: PlayerController) {
        if (guidePreview === controller) releaseGuidePreview()
        else controller.release()
    }
    private fun releaseGuidePreview() {
        guidePreview?.release()
        guidePreview = null
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
    fun retry() = coordinator.retry()
    fun stop() { releaseGuidePreview(); coordinator.stopForOtherPlayback() }
    /** Close live's provider connection before capture; this job belongs to the Activity owner. */
    fun recordChannel(channel: Channel, programme: Programme? = null) {
        stop()
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
        init { controller.player.addListener(volumeListener) }
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
