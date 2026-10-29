/* Nuvix modifications Copyright (C) 2026 Nuvix Contributors. GPL-3.0-or-later. */
/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 * Modified for Nuvix on 2026-10-13: configurable live buffer policy.
 */
package app.opentv.player

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.AudioAttributes
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.collectLatest
import okhttp3.OkHttpClient

/**
 * Owns the single [ExoPlayer] instance and everything about switching what it is playing.
 *
 * ## Why one player, reused
 *
 * The obvious implementation of "change channel" is release the player and build a new one.
 * On a low-end TV box that takes long enough that a user pressing channel-up four times in a
 * row can queue four constructions and four teardowns, and the codec ends up in a state where
 * nothing plays until the app is killed. That is the "changing channels too quickly causes
 * streams to fail" class of bug, and it is entirely self-inflicted.
 *
 * Nuvix keeps one player per playback owner (the live Activity session or a non-live screen) and swaps its
 * media item. Requests are debounced, and an in-flight switch is cancelled the moment a newer
 * one arrives, so holding channel-up costs one actual tune — the one the user stopped on.
 */
@OptIn(UnstableApi::class)
class PlayerController(
    context: Context,
    private val scope: CoroutineScope,
    httpClient: OkHttpClient,
    subtitlesEnabled: Boolean = true,
    /**
     * Tunes the player for the guide's muted preview pane: a shallow start buffer so a highlighted
     * channel shows a frame quickly, and a longer switch debounce so scrolling the channel list
     * does not tune to every channel passed over. The full-screen players leave this false and keep
     * the deep buffer that rides out a twitchy IPTV source.
     */
    private val preview: Boolean = false,
    /**
     * Turns the live player into a shallow DVR: a retained back-buffer so the viewer can pause and
     * rewind live TV within the last few minutes. Off by default because holding minutes of video
     * in memory is a real cost on a cheap box; the recording player and preview never set it.
     */
    private val dvr: Boolean = false,
    /**
     * When set, `smb://` media (a recording on a NAS) is read through this source so it plays and
     * seeks in-app. Null for the live/VOD players, which never see an smb URI.
     */
    smbDataSourceFactory: androidx.media3.datasource.DataSource.Factory? = null,
    /**
     * When set, `optvrec://<id>` media (a recording still being written) is read through this
     * tail-following source so it can be watched while it records. Null everywhere except the
     * recording player.
     */
    growingDataSourceFactory: androidx.media3.datasource.DataSource.Factory? = null,
    /**
     * Playing a recording that is still being written (watch-while-recording). Playback is held a
     * cushion *behind* the file's growing edge (see the LIVE_REC_* constants) so the bursty arrival
     * of an IPTV source — worst when the recording is read back over SMB from a NAS, where each read
     * is a network round-trip — rides over the gaps instead of stalling on every one.
     */
    private val liveRecording: Boolean = false,
    private val transitionDiagnostics: ChannelTransitionDiagnostics? = null,
    private val recoveryBudget: RecoveryBudget? = null,
    private val recoveryAllowed: () -> Boolean = { true },
    private val reconnectLive: (() -> Unit)? = null,

) {

    /** Channel-surf debounce for this controller — longer for the preview so browsing is calm. */
    private val switchDebounceMillis = if (preview) PREVIEW_SWITCH_DEBOUNCE_MILLIS else SWITCH_DEBOUNCE_MILLIS

    /** Playback status shown by the player UI. */
    sealed interface State {
        /** Player state with no active media request. */
        data object Idle : State
        /** Player state waiting for media data for the selected title. */
        data class Buffering(val title: String) : State
        /** Player state actively presenting the selected title. */
        data class Playing(val title: String) : State
        /** Playback stopped and retries are exhausted. [message] is written for humans. */
        data class Error(val title: String, val message: String, val canRetry: Boolean) : State
    }

    /** Playback URL, title and request options for one explicit media start. */
    data class Request(
        val url: String,
        val title: String,
        val userAgent: String,
        /** Live streams are never resumed; VOD is. */
        val startPositionMillis: Long = 0L,
        val isLive: Boolean = true,
    )

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    /** The current stream's tracks (audio/text/video), for the in-player pickers. */
    private val _tracks = MutableStateFlow(Tracks.EMPTY)
    val tracks: StateFlow<Tracks> = _tracks.asStateFlow()

    private var switchJob: Job? = null
    private var retryJob: Job? = null
    private var recovery: Media3PlaybackRecovery? = null
    private var current: Request? = null
    private var loadedRequest: Request? = null
    private var consecutiveFailures = 0
    private var suspended = false
    private var released = false
    private var sportsAutoSwitchover = false
    private var sportsPlaybackDelay = false
    internal val isReleased: Boolean get() = released
    private var explicitTextSelection = false
    private var generation = 0L
    private var diagnosticMediaId = 0L
    private val _userPaused = MutableStateFlow(false)
    val userPaused: StateFlow<Boolean> = _userPaused.asStateFlow()

    /**
     * Held so the User-Agent can be swapped per source before each tune.
     *
     * Providers commonly 403 any client whose UA they do not recognise, and the workaround —
     * "try a different User-Agent" — is useless unless the setting actually reaches the
     * player's HTTP layer. Setting it on the factory applies it to data sources created for
     * subsequent loads, which is exactly the granularity we need.
     */
    private val httpFactory = OkHttpDataSource.Factory(httpClient).apply {
        setDefaultRequestProperties(mapOf("User-Agent" to DEFAULT_USER_AGENT))
    }

    private val dataSourceFactory: androidx.media3.datasource.DataSource.Factory =
        DefaultDataSource.Factory(context, httpFactory).let { default ->
            val custom = buildMap<String, androidx.media3.datasource.DataSource.Factory> {
                if (smbDataSourceFactory != null) put("smb", smbDataSourceFactory)
                if (growingDataSourceFactory != null) put("optvrec", growingDataSourceFactory)
            }
            if (custom.isEmpty()) default else RoutingDataSourceFactory(default, custom)
        }

    /**
     * Retry policy tuned for IPTV rather than for CDNs.
     *
     * Managed live viewers use the shared recovery budget. Other playback retains bounded
     * load retries for temporary network/server failures. Authorization, missing streams and
     * invalid requests are terminal; rate limits honor Retry-After.
     */
    private val loadErrorPolicy = object : DefaultLoadErrorHandlingPolicy() {
        /** Chooses the retry delay for a recoverable media load failure. */
        override fun getRetryDelayMsFor(info: LoadErrorHandlingPolicy.LoadErrorInfo): Long {
            recoveryBudget?.deferFor(PlaybackErrors.retryAfterMs(info.exception), android.os.SystemClock.elapsedRealtime())
            // Managed viewers have one bounded recovery owner, including HTTP failures.
            if (recoveryBudget != null && !sportsAutoSwitchover) return C_TIME_UNSET
            val statusCode =
                (info.exception as? HttpDataSource.InvalidResponseCodeException)?.responseCode

            return when (statusCode) {
                401, 403, 404, 405, 410, 451 -> C_TIME_UNSET
                429 -> if (info.errorCount > MAX_LOAD_RETRIES) C_TIME_UNSET else maxOf(backoffFor(info.errorCount), PlaybackErrors.retryAfterMs(info.exception))
                500, 502, 503, 504 -> backoffFor(info.errorCount)
                null -> backoffFor(info.errorCount) // network blips
                else -> super.getRetryDelayMsFor(info)
            }
        }

        /** Returns the retry allowance for the media data type. */
        override fun getMinimumLoadableRetryCount(dataType: Int): Int = MAX_LOAD_RETRIES

        /** Calculates bounded exponential retry backoff for repeated load failures. */
        private fun backoffFor(errorCount: Int): Long =
            if (errorCount > MAX_LOAD_RETRIES) C_TIME_UNSET
            else minOf(INITIAL_BACKOFF_MILLIS * (1L shl (errorCount - 1)), MAX_BACKOFF_MILLIS)
    }

    /**
     * Held so captions can be turned on and off at runtime. Prefer the device language when
     * captions are on; [setSubtitlesEnabled] flips the text renderer off entirely.
     */
    private val trackSelector = DefaultTrackSelector(context).apply {
        parameters = buildUponParameters()
            .setPreferredTextLanguage(java.util.Locale.getDefault().language)
            .setSelectUndeterminedTextLanguage(true)
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, !subtitlesEnabled)
            .build()
    }

    private val bufferSettings = BufferSettings.get(context)
    private val lowMemory = BufferSettings.isLowMemory(context)

    /** Resolves the current buffering preferences for this device. */
    private fun effectiveBuffer(configuration: BufferConfiguration): BufferPolicy {
        if (preview) return BufferPolicy.PREVIEW
        val selected = configuration.resolve(lowMemory)
        // Growing recordings need an eight-second cushion; DVR retains its existing forward span.
        val policy = selected.copy(
            minimumMs = if (liveRecording) maxOf(selected.minimumMs, 8_000) else selected.minimumMs,
            maximumMs = if (dvr) DVR_MAX_BUFFER_MILLIS else maxOf(selected.maximumMs, if (liveRecording) 8_000 else 0),
            playbackMs = if (liveRecording) 8_000 else selected.playbackMs,
            rebufferMs = if (liveRecording) 8_000 else selected.rebufferMs,
        )
        return if (sportsPlaybackDelay) policy.withSportsCushion() else policy
    }

    private val loadControl = ConfigurableLoadControl(
        initial = effectiveBuffer(bufferSettings.configuration.value),
        budgetBytes = if (preview) 8 * 1024 * 1024 else if (lowMemory) 16 * 1024 * 1024 else 32 * 1024 * 1024,
        backBufferMs = if (dvr) DVR_BACK_BUFFER_MILLIS else 0,
    )

    /** Recovery timeouts must allow the configured startup/rebuffer reservoir to fill first. */
    internal val bufferPolicy: BufferPolicy get() = effectiveBuffer(bufferSettings.configuration.value)

    val player: ExoPlayer = ExoPlayer.Builder(context)
        .setSeekBackIncrementMs(SEEK_INCREMENT_MILLIS)
        .setSeekForwardIncrementMs(SEEK_INCREMENT_MILLIS)
        .setMediaSourceFactory(
            DefaultMediaSourceFactory(dataSourceFactory)
                .setLoadErrorHandlingPolicy(loadErrorPolicy),
        )
        .setTrackSelector(trackSelector)
        .setLoadControl(loadControl)
        .setAudioAttributes(
            AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(),
            !preview,
        )
        .setHandleAudioBecomingNoisy(!preview)
        .build()
        .apply {
            addListener(object : Player.Listener {
                override fun onTracksChanged(tracks: Tracks) {
                    _tracks.value = tracks
                }

                override fun onPlaybackStateChanged(playbackState: Int) {
                    if (released || suspended || current != loadedRequest) return
                    val title = current?.title.orEmpty()
                    _state.value = when (playbackState) {
                        Player.STATE_BUFFERING -> State.Buffering(title)
                        Player.STATE_READY -> State.Playing(title)
                        Player.STATE_IDLE -> _state.value
                        Player.STATE_ENDED -> State.Idle
                        else -> _state.value
                    }
                }

                override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                    if (!playWhenReady && (reason == Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_FOCUS_LOSS ||
                            reason == Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_BECOMING_NOISY)) {
                        // A permanent focus loss is a user-visible pause, never undone by navigation.
                        _userPaused.value = true
                    }
                }

                override fun onPlayerError(error: PlaybackException) {
                    if (released || suspended || current != loadedRequest || player.playerError !== error) return
                    if (recoveryBudget != null && !sportsAutoSwitchover && current?.isLive == true) return
                    retryJob?.cancel()
                    consecutiveFailures++
                    val request = current
                    _state.value = State.Error(
                        title = request?.title.orEmpty(),
                        message = PlaybackErrors.describe(error),
                        canRetry = consecutiveFailures < MAX_AUTO_RESTARTS,
                    )
                    // One silent restart covers the common case of a provider dropping the
                    // connection when another device on the account starts streaming.
                    if (!sportsAutoSwitchover && PlaybackErrors.recoveryFailure(error).retryable &&
                        consecutiveFailures < MAX_AUTO_RESTARTS && request != null) {
                        val token = generation
                        retryJob = scope.launch {
                            delay(AUTO_RESTART_DELAY_MILLIS)
                            if (!released && !suspended && generation == token && current == request) {
                                start(request, debounce = false, resetFailures = false)
                            }
                        }
                    }
                }
            })
        }

    val diagnostics = PlaybackDiagnostics(player, transitionDiagnostics = transitionDiagnostics)

    private val bufferJob = scope.launch {
        bufferSettings.configuration.collectLatest {
            loadControl.update(effectiveBuffer(it), useLiveTarget = !sportsPlaybackDelay)
        }
    }

    /** Sports recovery owns retries; the cushion changes loading without replacing the player. */
    fun setSportsPlaybackOptions(autoSwitchover: Boolean, playbackDelay: Boolean) {
        if (released) return
        sportsAutoSwitchover = autoSwitchover
        sportsPlaybackDelay = playbackDelay
        if (autoSwitchover) retryJob?.cancel()
        loadControl.update(effectiveBuffer(bufferSettings.configuration.value), useLiveTarget = !playbackDelay)
    }

    /**
     * Switches playback.
     *
     * @param debounce when true (the default for channel surfing) the switch waits briefly so
     * that rapid presses collapse into a single tune. Pass false for a deliberate selection.
     */
    fun play(request: Request, debounce: Boolean = true) {
        _userPaused.value = false
        start(request, debounce, resetFailures = true)
    }

    /** Starts an explicit media request and updates controller state and diagnostics. */
    private fun start(request: Request, debounce: Boolean, resetFailures: Boolean) {
        if (released) return
        // Cancelling here is what makes fast channel-changing safe: the previous switch never
        // reaches the player, so we never stack prepares.
        switchJob?.cancel()
        retryJob?.cancel()
        recovery?.close(); recovery = null
        generation++
        val switchGeneration = generation
        val transitionGeneration = transitionDiagnostics?.generation ?: 0L
        current = request
        if (resetFailures) consecutiveFailures = 0
        if (suspended) return

        switchJob = scope.launch {
            if (debounce) delay(switchDebounceMillis)
            if (released || suspended || generation != switchGeneration) return@launch
            val providerDelay = (recoveryBudget?.notBeforeMs ?: 0) - android.os.SystemClock.elapsedRealtime()
            if (providerDelay > 0) {
                _state.value = State.Buffering(request.title)
                recoveryBudget?.publish(RecoveryState.WAITING, android.os.SystemClock.elapsedRealtime(), RecoveryFailure.RATE_LIMIT)
                delay(providerDelay)
                if (released || suspended || generation != switchGeneration) return@launch
            }

            httpFactory.setDefaultRequestProperties(mapOf("User-Agent" to request.userAgent))
            _state.value = State.Buffering(request.title)

            val newDiagnosticSession = loadedRequest?.url != request.url || loadedRequest?.title != request.title
            if (newDiagnosticSession) diagnosticMediaId++
            val mediaItem = MediaItem.Builder()
                .setMediaId("diagnostics-$diagnosticMediaId")
                .setUri(request.url)
                .apply {
                    // Only meaningful for live streams; setting it on VOD skews seeking.
                    if (request.isLive) {
                        setLiveConfiguration(
                            MediaItem.LiveConfiguration.Builder()
                                .setTargetOffsetMs(LIVE_TARGET_OFFSET_MILLIS)
                                .apply {
                                    // Do not accelerate through the optional safety cushion.
                                    if (sportsPlaybackDelay) setMinPlaybackSpeed(1f).setMaxPlaybackSpeed(1f)
                                }
                                .build(),
                        )
                    }
                }
                .build()

            with(player) {
                // stop() rather than release(): keeps the codec and the surface alive.
                stop()
                clearMediaItems()
                if (newDiagnosticSession) {
                    diagnostics.beginSession(DiagnosticProtocol.identify(mediaItem), mediaItem.mediaId)
                }
                setMediaItem(mediaItem)
                transitionDiagnostics?.record(transitionGeneration, ChannelTransitionPhase.MEDIA_SET)
                if (!request.isLive && request.startPositionMillis > 0) {
                    seekTo(request.startPositionMillis)
                }
                loadedRequest = request
                playWhenReady = !_userPaused.value
                transitionDiagnostics?.record(transitionGeneration, ChannelTransitionPhase.PREPARE_STARTED)
                prepare()
            }
            monitorLive(request, switchGeneration)
        }
    }

    private fun monitorLive(request: Request, switchGeneration: Long) {
        recovery?.close(); recovery = null
        if (request.isLive && !liveRecording && recoveryBudget != null && reconnectLive != null) {
            recovery = Media3PlaybackRecovery(player, scope, recoveryBudget,
                allowed = { !released && !suspended && !sportsAutoSwitchover && !_userPaused.value &&
                    generation == switchGeneration && current == loadedRequest && recoveryAllowed() },
                buffer = { bufferPolicy },
                reconnect = { if (generation == switchGeneration && recoveryAllowed()) reconnectLive.invoke() },
                failed = { category ->
                    _state.value = State.Error(request.title,
                        PlaybackErrors.describeRecovery(category), true)
                })
            diagnostics.recoveryBudget = recoveryBudget
        }
    }

    /** Retries the most recently selected media request. */
    fun retry() {
        current?.let { start(it, debounce = false, resetFailures = true) }
    }

    /** User intent is independent of lifecycle suspension and browsing audio suppression. */
    fun setUserPaused(paused: Boolean) {
        _userPaused.value = paused
        if (paused) recovery?.suspendMonitoring()
        if (!released) player.playWhenReady = !paused && !suspended
    }

    /** Stop loading in the background; keep the selected item and tracks for a later resume. */
    fun setSuspended(value: Boolean) {
        if (released || suspended == value) return
        suspended = value
        recovery?.suspendMonitoring()
        if (value) {
            switchJob?.cancel()
            retryJob?.cancel()
            generation++
            player.playWhenReady = false
            player.stop()
        } else {
            val request = current ?: return
            // Lifecycle resume is not explicit Retry and cannot reopen terminal/exhausted media.
            if (_state.value is State.Error && recoveryBudget != null) return
            if (loadedRequest != request || (recoveryBudget?.notBeforeMs ?: 0) > android.os.SystemClock.elapsedRealtime())
                start(request, debounce = false, resetFailures = false)
            else {
                player.playWhenReady = !_userPaused.value
                monitorLive(request, generation)
                player.prepare()
            }
        }
    }

    /**
     * The quick captions toggle. On is *not* merely "allow the text renderer" — that leaves it
     * to the selector to guess a language, which for a lot of IPTV streams guesses nothing and
     * the user sees no captions even though they turned them on. So On explicitly selects the
     * first available subtitle track; Off disables text entirely.
     */
    /** Auto-defaults may run again after a surface moves; explicit picks always win. */
    fun applySubtitleDefault(enabled: Boolean) {
        if (explicitTextSelection) return
        setSubtitlesEnabled(enabled)
        explicitTextSelection = false
    }

    /** Updates subtitle selection according to the saved playback preference. */
    fun setSubtitlesEnabled(enabled: Boolean) {
        if (!enabled) {
            disableText()
            return
        }
        val firstText = _tracks.value.groups.firstOrNull { it.type == C.TRACK_TYPE_TEXT && it.isSupported }
        if (firstText != null) {
            selectTrack(firstText, firstSupportedIndex(firstText))
        } else {
            // Nothing to select yet — allow the renderer so a track that arrives later can be
            // picked up, and re-assert once tracks change.
            player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                .clearOverridesOfType(C.TRACK_TYPE_TEXT)
                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                .build()
        }
    }

    /** Force a specific audio or text track on (used by the in-player pickers). */
    fun selectTrack(group: Tracks.Group, trackIndex: Int) {
        if (group.type == C.TRACK_TYPE_TEXT) explicitTextSelection = true
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, trackIndex))
            .setTrackTypeDisabled(group.type, false)
            .build()
    }

    /** Turn subtitles off entirely. */
    fun disableText() {
        explicitTextSelection = true
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .clearOverridesOfType(C.TRACK_TYPE_TEXT)
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
            .build()
    }

    /** Seeks backward by the controller's configured jump interval. */
    fun seekBackward() {
        if (player.isCurrentMediaItemSeekable) player.seekBack()
    }

    /** Seeks forward by the controller's configured jump interval. */
    fun seekForward() {
        if (player.isCurrentMediaItemSeekable) player.seekForward()
    }

    val isSeekable: Boolean get() = player.isCurrentMediaItemSeekable

    /** Finds the first supported track in a track group. */
    private fun firstSupportedIndex(group: Tracks.Group): Int {
        for (i in 0 until group.length) if (group.isTrackSupported(i)) return i
        return 0
    }

    /** Stops current playback and clears its active media state. */
    fun stop() {
        if (released) return
        generation++
        switchJob?.cancel()
        retryJob?.cancel()
        recovery?.close(); recovery = null
        current = null
        loadedRequest = null
        player.stop()
        player.clearMediaItems()
        diagnostics.beginSession(null, "stopped")
        _state.value = State.Idle
    }

    /** Releases the player, diagnostics and controller-owned resources. */
    fun release() {
        if (released && player.isReleased) return
        released = true
        generation++
        current = null
        recovery?.close(); recovery = null
        bufferJob.cancel()
        switchJob?.cancel()
        retryJob?.cancel()
        diagnostics.close()
        player.release()
    }

    private companion object {
        const val DEFAULT_USER_AGENT = "Nuvix/0.1 (Android)"

        /** ExoPlayer's "do not retry" sentinel. */
        const val C_TIME_UNSET = androidx.media3.common.C.TIME_UNSET

        const val SWITCH_DEBOUNCE_MILLIS = 350L
        const val SEEK_INCREMENT_MILLIS = 15_000L
        const val INITIAL_BACKOFF_MILLIS = 500L
        const val MAX_BACKOFF_MILLIS = 8_000L
        const val MAX_LOAD_RETRIES = 5
        const val MAX_AUTO_RESTARTS = 3
        const val AUTO_RESTART_DELAY_MILLIS = 1_500L

        // Opt-in DVR retains the upstream two-minute history and forward span.
        const val DVR_BACK_BUFFER_MILLIS = 120_000
        const val DVR_MAX_BUFFER_MILLIS = 120_000
        const val PREVIEW_SWITCH_DEBOUNCE_MILLIS = 700L

        const val LIVE_TARGET_OFFSET_MILLIS = 10_000L
    }
}
