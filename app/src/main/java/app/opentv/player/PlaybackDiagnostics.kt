/* Copyright (C) 2026 Nuvix Contributors. Licensed under GPL-3.0-or-later. */
package app.opentv.player

import android.os.SystemClock
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.Player
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.DecoderCounters
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Values are nullable when the renderer/source has not supplied them. No URLs or titles. */
data class DiagnosticFormat(
    val codec: String? = null,
    val resolution: String? = null,
    val frameRate: Float? = null,
    val declaredBitrate: Int? = null,
    val hdr: String? = null,
    val channels: Int? = null,
    val sampleRate: Int? = null,
    val language: String? = null,
)

/** Converts Media3 format values to bounded display-safe diagnostic fields. */
@OptIn(UnstableApi::class)
object DiagnosticFormats {
    /** Returns a bounded allowlisted technical token, or null for arbitrary labels and URLs. */
    // Only technical tokens can cross into the panel/history, never arbitrary metadata labels.
    fun token(value: String?): String? = value?.takeIf {
        it.length in 1..100 && it.matches(Regex("[A-Za-z0-9_., /+\\-]+")) && !it.contains("://")
    }
    /** Builds display-safe video format diagnostics from Media3 format data. */
    fun video(f: Format?) = DiagnosticFormat(
        codec = token(f?.sampleMimeType),
        resolution = f?.let { if (it.width > 0 && it.height > 0) "${it.width} × ${it.height}" else null },
        frameRate = f?.frameRate?.takeIf { it.isFinite() && it > 0 },
        declaredBitrate = f?.bitrate?.takeIf { it > 0 },
        hdr = if (f?.sampleMimeType == "video/dolby-vision") "Dolby Vision" else when (f?.colorInfo?.colorTransfer) {
            C.COLOR_TRANSFER_ST2084 -> "HDR10 / PQ"
            C.COLOR_TRANSFER_HLG -> "HLG"
            else -> null
        },
    )
    /** Builds display-safe audio format diagnostics from Media3 format data. */
    fun audio(f: Format?) = DiagnosticFormat(
        codec = token(f?.sampleMimeType), channels = f?.channelCount?.takeIf { it > 0 },
        sampleRate = f?.sampleRate?.takeIf { it > 0 }, language = token(f?.language),
    )
}

/** Derived playback-health category shown by the stream information UI. */
enum class PlaybackHealth(val label: String) {
    NORMAL("Playing normally"), BUFFERING("Buffering"), INTERRUPTED("Experiencing interruptions"),
    ERROR("Playback error"), UNKNOWN("Insufficient measurement data"),
}

/** Bounded event-history entry using elapsed time and sanitized playback fields. */
data class DiagnosticEvent(
    val elapsedMs: Long, val kind: String, val state: String,
    val throughputBps: Long? = null, val droppedFrames: Long = 0,
)

/** Current stream formats, buffer state, playback health and bounded diagnostic history. */
data class DiagnosticSnapshot(
    val video: DiagnosticFormat = DiagnosticFormat(), val audio: DiagnosticFormat = DiagnosticFormat(),
    val decoder: String? = null, val audioTrack: String? = null, val protocol: String? = null,
    val bufferedMs: Long? = null, val positionMs: Long? = null, val speed: Float? = null,
    val state: String = "Idle", val health: PlaybackHealth = PlaybackHealth.UNKNOWN,
    val droppedFrames: Long? = null, val renderedFrames: Long? = null,
    val throughputBps: Long? = null, val estimatedBandwidthBps: Long? = null,
    val rebuffers: Int = 0, val bufferingMs: Long = 0, val rebuffersPerMinute: Double? = null,
    val decoderErrors: Int = 0, val interruptions: Int = 0, val error: String? = null,
    val history: List<DiagnosticEvent> = emptyList(),
)

/** Main-thread event reducer; stores at most 300 entries and five minutes, using monotonic time. */
internal class DiagnosticTimeline(private val clock: () -> Long) {
    private var started = clock()
    private var bufferingSince: Long? = null
    private var accumulatedBuffering = 0L
    private var played = false
    private var stateCode = Player.STATE_IDLE
    private var playWhenReady = false
    private var activelyPlaying = false
    private var lastInterruption: Long? = null
    private var seeking = false
    var rebuffers = 0; private set
    var dropped = 0L; private set
    var decoderErrors = 0; private set
    var interruptions = 0; private set
    var error: String? = null; private set
    private val events = ArrayDeque<DiagnosticEvent>()
    var throughput: Long? = null; private set
    private var throughputAt: Long? = null

    /** Starts a fresh diagnostic timeline for a playback generation. */
    fun reset() {
        started = clock(); bufferingSince = null; accumulatedBuffering = 0; played = false
        stateCode = Player.STATE_IDLE; playWhenReady = false; activelyPlaying = false; lastInterruption = null; seeking = false
        rebuffers = 0; dropped = 0; decoderErrors = 0; interruptions = 0; error = null
        throughput = null; throughputAt = null; events.clear()
    }
    /** Records a position discontinuity without treating a user seek as a stall. */
    fun seek() { seeking = true }
    /** Records a playback state transition and updates buffering measurements. */
    fun state(code: Int, readyToPlay: Boolean, isPlaying: Boolean) {
        val now = clock()
        if (code == Player.STATE_BUFFERING && readyToPlay) {
            if (bufferingSince == null) {
                bufferingSince = now
                if (played && stateCode != Player.STATE_BUFFERING && !seeking) {
                    rebuffers++; lastInterruption = now
                }
            }
        } else {
            bufferingSince?.let { accumulatedBuffering += now - it }; bufferingSince = null
        }
        val stateChanged = code != stateCode || playWhenReady != readyToPlay || activelyPlaying != isPlaying
        stateCode = code
        playWhenReady = readyToPlay
        activelyPlaying = isPlaying
        if (stateChanged) record("State")
        if (code == Player.STATE_ENDED) events.clear()
        if (isPlaying) { played = true; error = null }
        if (code == Player.STATE_READY) seeking = false
    }
    /** Records dropped video-frame counts for recent health calculations. */
    fun dropped(count: Int) { dropped += count.coerceAtLeast(0); record("Dropped frames") }
    /** Records a bandwidth sample for recent throughput estimates. */
    fun bandwidth(elapsedMs: Int, bytes: Long) {
        if (elapsedMs > 0 && bytes > 0) {
            throughput = (bytes.toDouble() * 8_000 / elapsedMs).toLong()
            throughputAt = clock(); record("Network transfer")
        }
    }
    /** Records a terminal playback failure and its display-safe diagnostic message. */
    fun failure(message: String, decoder: Boolean) {
        error = message
        if (decoder) decoderErrors++ else interruptions++
        lastInterruption = clock(); record(if (decoder) "Decoder error" else "Stream interruption")
    }
    /** Records a recoverable codec error without declaring terminal playback failure. */
    fun recoverableDecoderError() { decoderErrors++; lastInterruption = clock(); record("Decoder error") }
    /** Derives current health from playback state, stalls, errors and recent measurements. */
    fun health(): PlaybackHealth = when {
        error != null -> PlaybackHealth.ERROR
        stateCode == Player.STATE_IDLE || stateCode == Player.STATE_ENDED -> PlaybackHealth.UNKNOWN
        stateCode == Player.STATE_BUFFERING && playWhenReady -> PlaybackHealth.BUFFERING
        lastInterruption?.let { clock() - it < 30_000 } == true -> PlaybackHealth.INTERRUPTED
        played -> PlaybackHealth.NORMAL
        else -> PlaybackHealth.UNKNOWN
    }
    /** Maps the tracked player state to its diagnostic display label. */
    fun stateLabel(): String = when (stateCode) {
        Player.STATE_BUFFERING -> if (playWhenReady) "Buffering" else "Paused (buffering)"
        Player.STATE_READY -> if (activelyPlaying) "Playing" else if (playWhenReady) "Ready (waiting)" else "Paused"
        Player.STATE_ENDED -> "Ended"
        else -> "Idle"
    }
    /** Calculates buffering time using the monotonic diagnostic clock. */
    fun bufferingMs() = accumulatedBuffering + (bufferingSince?.let { clock() - it } ?: 0)
    /** Returns rebuffers per minute after at least one minute of playback observation. */
    fun frequency(): Double? = (clock() - started).takeIf { played && it >= 60_000 }?.let { rebuffers * 60_000.0 / it }
    /** Calculates throughput from recent transfer samples. */
    fun recentThroughput(): Long? = throughput.takeIf { throughputAt?.let { clock() - it <= 10_000 } == true }
    /** Adds a bounded diagnostic event and prunes expired history. */
    fun record(kind: String) {
        events.addLast(DiagnosticEvent(clock() - started, kind, stateLabel(), recentThroughput(), dropped))
        trim()
    }
    /** Prunes diagnostic samples and events outside their retention windows. */
    private fun trim() {
        val cutoff = clock() - started - 300_000
        while (events.isNotEmpty() && (events.size > 300 || events.first().elapsedMs < cutoff)) events.removeFirst()
    }
    /** Returns the bounded diagnostic event history for display. */
    fun history(): List<DiagnosticEvent> { trim(); return events.toList() }
}

/** Observe the existing player only. No prepare, seek, playback, network, or lifecycle ownership. */
@OptIn(UnstableApi::class)
class PlaybackDiagnostics internal constructor(
    private val player: Player,
    private val exoPlayer: ExoPlayer? = player as? ExoPlayer,
    private val clock: () -> Long = SystemClock::elapsedRealtime,
) : AutoCloseable {
    private val timeline = DiagnosticTimeline(clock)
    private val _snapshot = MutableStateFlow(DiagnosticSnapshot())
    val snapshot = _snapshot.asStateFlow()
    private var closed = false
    private var mediaId: String? = null
    private val eventWindow = androidx.media3.common.Timeline.Window()
    private var decoder: String? = null
    private var renderedTotal = 0L
    private var currentCounters: DecoderCounters? = null
    private var counterBaseline = 0
    private var protocol: String? = null
    private var estimate: Long? = null
    private var estimateAt: Long? = null
    private var video: Format? = null
    private var audio: Format? = null
    private val listener = object : Player.Listener {
        /** Updates the current snapshot from accepted player events. */
        override fun onEvents(player: Player, events: Player.Events) {
            timeline.state(player.playbackState, player.playWhenReady, player.isPlaying)
        }
        /** Records accepted seek and position-discontinuity events in the timeline. */
        override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) {
            if (reason == Player.DISCONTINUITY_REASON_SEEK) timeline.seek()
        }
    }
    private val analytics = object : AnalyticsListener {
        /** Records an accepted terminal player error using sanitized diagnostics. */
        override fun onPlayerError(eventTime: AnalyticsListener.EventTime, error: PlaybackException) {
            if (!accepts(eventTime, error)) return
            timeline.failure(PlaybackErrors.describe(error), error.errorCode in 4000..5999)
        }
        /** Updates video format diagnostics for the current generation. */
        override fun onVideoInputFormatChanged(eventTime: AnalyticsListener.EventTime, format: Format, decoderReuseEvaluation: androidx.media3.exoplayer.DecoderReuseEvaluation?) { if (accepts(eventTime)) video = format }
        /** Updates audio format diagnostics for the current generation. */
        override fun onAudioInputFormatChanged(eventTime: AnalyticsListener.EventTime, format: Format, decoderReuseEvaluation: androidx.media3.exoplayer.DecoderReuseEvaluation?) { if (accepts(eventTime)) audio = format }
        /** Records the current video decoder name. */
        override fun onVideoDecoderInitialized(eventTime: AnalyticsListener.EventTime, decoderName: String, initializedTimestampMs: Long, initializationDurationMs: Long) { if (accepts(eventTime)) decoder = DiagnosticFormats.token(decoderName) }
        /** Tracks counters when the current video renderer becomes active. */
        override fun onVideoEnabled(eventTime: AnalyticsListener.EventTime, decoderCounters: DecoderCounters) {
            if (!accepts(eventTime)) return
            currentCounters = decoderCounters; counterBaseline = 0
        }
        /** Captures final counters when the current video renderer is disabled. */
        override fun onVideoDisabled(eventTime: AnalyticsListener.EventTime, decoderCounters: DecoderCounters) {
            if (!accepts(eventTime)) return
            if (currentCounters === decoderCounters) {
                decoderCounters.ensureUpdated()
                renderedTotal += (decoderCounters.renderedOutputBufferCount - counterBaseline).coerceAtLeast(0)
                currentCounters = null
            }
            decoder = null; video = null
        }
        /** Captures final counters when the current audio renderer is disabled. */
        override fun onAudioDisabled(eventTime: AnalyticsListener.EventTime, decoderCounters: DecoderCounters) { if (accepts(eventTime)) audio = null }
        /** Records dropped-frame samples from the current video renderer. */
        override fun onDroppedVideoFrames(eventTime: AnalyticsListener.EventTime, droppedFrames: Int, elapsedMs: Long) { if (accepts(eventTime)) timeline.dropped(droppedFrames) }
        /** Records transfer samples for the current playback generation. */
        override fun onBandwidthEstimate(eventTime: AnalyticsListener.EventTime, totalLoadTimeMs: Int, totalBytesLoaded: Long, bitrateEstimate: Long) {
            if (!accepts(eventTime)) return
            timeline.bandwidth(totalLoadTimeMs, totalBytesLoaded)
            estimate = bitrateEstimate.takeIf { it > 0 }; estimateAt = clock()
        }
        /** Records a recoverable video-codec error without exposing private request details. */
        override fun onVideoCodecError(eventTime: AnalyticsListener.EventTime, videoCodecError: Exception) { if (accepts(eventTime)) timeline.recoverableDecoderError() }
        /** Records a recoverable audio-codec error without exposing private request details. */
        override fun onAudioCodecError(eventTime: AnalyticsListener.EventTime, audioCodecError: Exception) { if (accepts(eventTime)) timeline.recoverableDecoderError() }
    }
    init { player.addListener(listener); exoPlayer?.addAnalyticsListener(analytics) }

    /** Rejects callbacks that no longer belong to the current playback generation. */
    // Renderer callbacks can arrive after a fast channel change. Match an opaque owner ID,
    // never a stream URL, so a late callback cannot contaminate the new session.
    private fun accepts(event: AnalyticsListener.EventTime, error: PlaybackException? = null): Boolean {
        if (closed) return false
        val expected = mediaId ?: return true
        if (event.timeline.isEmpty && error != null) {
            // Missing timeline context alone cannot identify a session. Accept only the
            // player's current error for the active item, never a delayed previous error.
            return player.playerError === error && player.currentMediaItem?.mediaId == expected
        }
        return event.windowIndex in 0 until event.timeline.windowCount &&
            event.timeline.getWindow(event.windowIndex, eventWindow).mediaItem.mediaId == expected
    }

    /** Called by the existing owner after stopping the old item and before preparing a new one. */
    fun beginSession(streamProtocol: String?, mediaId: String? = null) {
        if (closed) return
        this.mediaId = mediaId
        protocol = streamProtocol; video = null; audio = null; decoder = null
        currentCounters = null; renderedTotal = 0; counterBaseline = 0
        estimate = null; estimateAt = null; timeline.reset(); _snapshot.value = DiagnosticSnapshot()
    }
    /** Clears the active diagnostic timeline and publishes a fresh snapshot. */
    fun clear() {
        if (closed) return
        timeline.reset(); renderedTotal = 0
        currentCounters?.let { it.ensureUpdated(); counterBaseline = it.renderedOutputBufferCount }
        estimate = null; estimateAt = null
        sample()
    }
    /** The UI calls this once per second only while visible and STARTED. */
    fun sample() {
        if (closed) return
        timeline.state(player.playbackState, player.playWhenReady, player.isPlaying)
        currentCounters?.ensureUpdated()
        val selectedAudio = player.currentTracks.groups.filter { it.type == C.TRACK_TYPE_AUDIO }
            .flatMap { g -> (0 until g.length).map { g to it } }
            .indexOfFirst { (g, i) -> g.isTrackSelected(i) }.takeIf { it >= 0 }?.let { "Track ${it + 1}" }
        val vf = exoPlayer?.videoFormat ?: video
        val af = exoPlayer?.audioFormat ?: audio
        _snapshot.value = DiagnosticSnapshot(
            video = DiagnosticFormats.video(vf), audio = DiagnosticFormats.audio(af), decoder = decoder,
            audioTrack = selectedAudio, protocol = protocol,
            bufferedMs = player.totalBufferedDuration.takeIf { it >= 0 && it != C.TIME_UNSET },
            positionMs = player.currentPosition.takeIf { it >= 0 && it != C.TIME_UNSET },
            speed = player.playbackParameters.speed, state = timeline.stateLabel(), health = timeline.health(),
            droppedFrames = timeline.dropped.takeIf { vf != null || currentCounters != null || renderedTotal > 0 },
            renderedFrames = currentCounters?.let { renderedTotal + (it.renderedOutputBufferCount - counterBaseline).coerceAtLeast(0) }
                ?: renderedTotal.takeIf { it > 0 },
            throughputBps = timeline.recentThroughput(),
            estimatedBandwidthBps = estimate.takeIf { estimateAt?.let { clock() - it <= 10_000 } == true },
            rebuffers = timeline.rebuffers, bufferingMs = timeline.bufferingMs(), rebuffersPerMinute = timeline.frequency(),
            decoderErrors = timeline.decoderErrors, interruptions = timeline.interruptions, error = timeline.error,
            history = timeline.history(),
        )
    }
    /** Detaches diagnostic listeners and releases references to the player. */
    override fun close() {
        if (closed) return
        closed = true; player.removeListener(listener); exoPlayer?.removeAnalyticsListener(analytics)
        timeline.reset(); _snapshot.value = DiagnosticSnapshot()
        video = null; audio = null; decoder = null; currentCounters = null; mediaId = null
    }
}

/** Identify only known transports/containers; never retain the URI or guess from an HTTP scheme. */
@OptIn(UnstableApi::class)
object DiagnosticProtocol {
    /** Classifies the stream protocol without returning the private stream URL. */
    fun identify(item: androidx.media3.common.MediaItem): String? {
        val config = item.localConfiguration ?: return null
        return when {
            config.mimeType == androidx.media3.common.MimeTypes.APPLICATION_M3U8 -> "HLS"
            config.mimeType == androidx.media3.common.MimeTypes.APPLICATION_MPD -> "DASH"
            config.uri.scheme.equals("rtsp", true) -> "RTSP"
            config.uri.scheme in setOf("file", "content", "optvrec") -> "Local recording / file"
            config.uri.scheme.equals("smb", true) -> "SMB recording"
            config.uri.lastPathSegment?.endsWith(".m3u8", true) == true -> "HLS"
            config.uri.lastPathSegment?.endsWith(".mpd", true) == true -> "DASH"
            else -> null
        }
    }
}
