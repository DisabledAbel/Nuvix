/* Nuvix Copyright (C) 2026 Nuvix Contributors. GPL-3.0-or-later. */
package app.opentv.multiview

import android.content.Context
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import app.opentv.player.DiagnosticProtocol
import app.opentv.player.PlaybackDiagnostics
import app.opentv.player.PlaybackErrors
import app.opentv.player.PlayerSurfaceOwner
import app.opentv.player.RecoveryBudget
import app.opentv.player.Media3PlaybackRecovery
import app.opentv.player.BufferPolicy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient

/** Resolved URLs remain in memory for this attempt only, never in presentation/preset state. */
data class ResolvedMultiViewChannel(
    val channelId: Long,
    val sourceId: Long,
    val channelName: String,
    val providerName: String,
    val url: String,
    val userAgent: String = "Nuvix/0.1 (Android)",
)

enum class MultiViewFailureKind { NETWORK, STREAM, DECODER, UNSUPPORTED, PROVIDER_LIMIT }
data class MultiViewFailure(val kind: MultiViewFailureKind, val message: String)

/** Every instance represents one generation. It is never retuned or reused for a replacement. */
interface MultiViewPlayer {
    interface Listener {
        fun ready()
        fun buffering()
        fun failed(failure: MultiViewFailure)
    }
    val managesRecovery: Boolean get() = false
    fun configureRecovery(scope: CoroutineScope, budget: RecoveryBudget, allowed: () -> Boolean, reconnect: () -> Unit) {}
    fun start(channel: ResolvedMultiViewChannel)
    fun setVolume(volume: Float)
    fun release()
    val mediaPlayer: Player? get() = null
    val surfaceOwner: PlayerSurfaceOwner? get() = null
    val diagnostics: PlaybackDiagnostics? get() = null
}

fun interface MultiViewPlayerFactory { fun create(listener: MultiViewPlayer.Listener): MultiViewPlayer }

/** A single focus lease for the whole wall. Individual Media3 instances never request focus. */
interface MultiViewAudioFocus {
    fun request(onChange: (Boolean) -> Unit): Boolean
    fun abandon()
}

@OptIn(UnstableApi::class)
class AndroidMultiViewPlayer(
    context: Context,
    httpClient: OkHttpClient,
    private val listener: MultiViewPlayer.Listener,
) : MultiViewPlayer {
    private var released = false
    private var recovery: Media3PlaybackRecovery? = null
    private var beginRecovery: (() -> Unit)? = null
    private var prepareJob: Job? = null
    private val http = OkHttpDataSource.Factory(httpClient)
    private val selector = DefaultTrackSelector(context).apply {
        parameters = buildUponParameters().setMaxVideoSize(1920, 1080)
            .setExceedVideoConstraintsIfNecessary(false).build()
    }
    // No bundled extensions and no software video decoders: extra panels must not saturate a TV CPU.
    private val renderers = DefaultRenderersFactory(context)
        .setEnableDecoderFallback(false)
        .setMediaCodecSelector { mime, secure, tunneling ->
            MediaCodecSelector.DEFAULT.getDecoderInfos(mime, secure, tunneling).filter {
                !mime.startsWith("video/") || it.hardwareAccelerated
            }
        }
    private val player = ExoPlayer.Builder(context, renderers)
        .setMediaSourceFactory(DefaultMediaSourceFactory(DefaultDataSource.Factory(context, http))
            .setLoadErrorHandlingPolicy(object : androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy() {
                override fun getRetryDelayMsFor(info: androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy.LoadErrorInfo) = C.TIME_UNSET
            }))
        .setTrackSelector(selector)
        .setLoadControl(DefaultLoadControl.Builder().setBufferDurationsMs(5_000, 15_000, 1_000, 2_000)
            .setTargetBufferBytes(12 * 1024 * 1024).setPrioritizeTimeOverSizeThresholds(false).build())
        .setAudioAttributes(androidx.media3.common.AudioAttributes.Builder().setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(), false)
        .setHandleAudioBecomingNoisy(false)
        .build().apply {
            volume = 0f
            addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(playbackState: Int) {
                    if (released) return
                    when (playbackState) {
                        Player.STATE_READY -> listener.ready()
                        Player.STATE_BUFFERING -> listener.buffering()
                        Player.STATE_ENDED -> if (recovery == null) listener.failed(MultiViewFailure(MultiViewFailureKind.STREAM,
                            "The live stream ended. Retry or replace this channel."))
                    }
                }
                override fun onPlayerError(error: PlaybackException) {
                    if (released || recovery != null) return
                    val kind = when (error.errorCode) {
                        PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
                        PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED,
                        PlaybackException.ERROR_CODE_DECODING_FAILED -> MultiViewFailureKind.DECODER
                        PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
                        PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES,
                        PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
                        PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED -> MultiViewFailureKind.UNSUPPORTED
                        in 2000..2999 -> MultiViewFailureKind.NETWORK
                        else -> MultiViewFailureKind.STREAM
                    }
                    listener.failed(MultiViewFailure(kind, if (kind == MultiViewFailureKind.DECODER)
                        "This device could not allocate another hardware decoder. Keep the working panels and use a smaller layout."
                    else PlaybackErrors.describe(error)))
                }
            })
        }
    override val mediaPlayer: Player get() = player
    override val surfaceOwner = PlayerSurfaceOwner { if (released) null else player }
    override val diagnostics = PlaybackDiagnostics(player)

    override val managesRecovery = true
    override fun configureRecovery(scope: CoroutineScope, budget: RecoveryBudget, allowed: () -> Boolean, reconnect: () -> Unit) {
        diagnostics.recoveryBudget = budget
        beginRecovery = {
            prepareJob = scope.launch {
                val now = android.os.SystemClock.elapsedRealtime()
                val remaining = budget.notBeforeMs - now
                if (remaining > 0) {
                    budget.publish(app.opentv.player.RecoveryState.WAITING, now, app.opentv.player.RecoveryFailure.RATE_LIMIT)
                    delay(remaining)
                }
                if (released || !allowed()) return@launch
                recovery = Media3PlaybackRecovery(player, scope, budget, allowed,
                    { BufferPolicy(5_000, 15_000, 1_000, 2_000) }, reconnect,
                    failed = { failure -> listener.failed(MultiViewFailure(
                        when (failure) {
                            app.opentv.player.RecoveryFailure.DECODER -> MultiViewFailureKind.DECODER
                            app.opentv.player.RecoveryFailure.UNSUPPORTED -> MultiViewFailureKind.UNSUPPORTED
                            app.opentv.player.RecoveryFailure.CONNECTION_LIMIT -> MultiViewFailureKind.PROVIDER_LIMIT
                            else -> MultiViewFailureKind.STREAM
                        }, PlaybackErrors.describeRecovery(failure))) })
                player.prepare()
                player.playWhenReady = true
            }
        }
    }

    override fun start(channel: ResolvedMultiViewChannel) {
        check(!released)
        http.setUserAgent(channel.userAgent)
        val item = MediaItem.Builder().setMediaId(java.util.UUID.randomUUID().toString()).setUri(channel.url).build()
        diagnostics.beginSession(DiagnosticProtocol.identify(item), item.mediaId)
        player.setMediaItem(item)
        val start = beginRecovery
        if (start != null) start() else {
            player.prepare()
            player.playWhenReady = true
        }
    }
    override fun setVolume(volume: Float) { if (!released) player.volume = volume.coerceIn(0f, 1f) }
    override fun release() {
        if (released) return
        released = true
        prepareJob?.cancel(); prepareJob = null
        recovery?.close(); recovery = null; beginRecovery = null
        surfaceOwner.clear()
        diagnostics.close()
        player.volume = 0f
        player.stop()
        player.release()
    }
}

@Suppress("DEPRECATION")
class AndroidMultiViewAudioFocus(context: Context) : MultiViewAudioFocus {
    private val audio = context.applicationContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var callback: ((Boolean) -> Unit)? = null
    private var held = false
    private var registered = false
    private val handler = Handler(Looper.getMainLooper())
    private val listener = AudioManager.OnAudioFocusChangeListener { change ->
        handler.post {
            held = change == AudioManager.AUDIOFOCUS_GAIN
            callback?.invoke(change == AudioManager.AUDIOFOCUS_GAIN)
        }
    }
    private val request = if (Build.VERSION.SDK_INT >= 26) AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
        .setAudioAttributes(android.media.AudioAttributes.Builder().setUsage(android.media.AudioAttributes.USAGE_MEDIA)
            .setContentType(android.media.AudioAttributes.CONTENT_TYPE_MOVIE).build())
        .setOnAudioFocusChangeListener(listener, handler).build() else null

    override fun request(onChange: (Boolean) -> Unit): Boolean {
        callback = onChange
        if (held) return true
        registered = true
        held = (if (Build.VERSION.SDK_INT >= 26) audio.requestAudioFocus(checkNotNull(request))
        else audio.requestAudioFocus(listener, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN)) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        return held
    }
    override fun abandon() {
        callback = null
        if (registered) {
            if (Build.VERSION.SDK_INT >= 26) audio.abandonAudioFocusRequest(checkNotNull(request))
            else audio.abandonAudioFocus(listener)
        }
        held = false
        registered = false
    }
}
