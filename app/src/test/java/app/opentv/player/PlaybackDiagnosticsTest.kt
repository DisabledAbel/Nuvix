/* Copyright (C) 2026 Nuvix Contributors. Licensed under GPL-3.0-or-later. */
package app.opentv.player

import android.app.Application
import androidx.annotation.OptIn
import androidx.media3.common.*
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DecoderCounters
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.lang.reflect.Proxy
import java.io.IOException
import java.net.SocketTimeoutException

/** No decoder, network, credentials, or hosted test stream. Fails on any playback mutation. */
@OptIn(UnstableApi::class)
internal class DiagnosticPlayer {
    val listeners = mutableSetOf<Player.Listener>()
    val analytics = mutableSetOf<AnalyticsListener>()
    var state = Player.STATE_IDLE
    var ready = true
    var playing = false
    var buffered = 0L
    var position = 0L
    var samples = 0
    var tracks = Tracks.EMPTY
    var video: Format? = null
    var audio: Format? = null
    var error: PlaybackException? = null
    var item: MediaItem? = null
    val player = Proxy.newProxyInstance(ExoPlayer::class.java.classLoader, arrayOf(ExoPlayer::class.java)) { proxy, method, args ->
        when (method.name) {
            "addListener" -> listeners.add(args!![0] as Player.Listener).let { null }
            "removeListener" -> listeners.remove(args!![0] as Player.Listener).let { null }
            "addAnalyticsListener" -> analytics.add(args!![0] as AnalyticsListener).let { null }
            "removeAnalyticsListener" -> analytics.remove(args!![0] as AnalyticsListener).let { null }
            "getPlaybackState" -> state
            "getPlayWhenReady" -> ready
            "isPlaying" -> playing
            "getTotalBufferedDuration" -> { samples++; buffered }
            "getCurrentPosition" -> position
            "getCurrentTracks" -> tracks
            "getPlaybackParameters" -> PlaybackParameters.DEFAULT
            "getVideoFormat" -> video
            "getAudioFormat" -> audio
            "getPlayerError" -> error
            "getCurrentMediaItem" -> item
            "equals" -> proxy === args!![0]
            "hashCode" -> System.identityHashCode(proxy)
            "toString" -> "DiagnosticPlayer"
            else -> error("Diagnostics attempted unexpected player operation: ${method.name}")
        }
    } as ExoPlayer
    fun emit() {
        val flags = FlagSet.Builder().add(Player.EVENT_PLAYBACK_STATE_CHANGED).build()
        listeners.toList().forEach { it.onEvents(player, Player.Events(flags)) }
    }
}

@OptIn(UnstableApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class PlaybackDiagnosticsTest {
    @Test fun `extracts known video audio and unavailable fields without inventing measurements`() {
        val video = DiagnosticFormats.video(Format.Builder().setWidth(1920).setHeight(1080)
            .setSampleMimeType("video/avc").setFrameRate(25f).setAverageBitrate(4_000_000).build())
        assertThat(video.resolution).isEqualTo("1920 × 1080")
        assertThat(video.codec).isEqualTo("video/avc")
        assertThat(video.frameRate).isEqualTo(25f)
        assertThat(video.declaredBitrate).isEqualTo(4_000_000)
        assertThat(video.hdr).isNull()
        val audio = DiagnosticFormats.audio(Format.Builder().setSampleMimeType("audio/mp4a-latm")
            .setChannelCount(6).setSampleRate(48000).setLanguage("en").build())
        assertThat(audio.codec).isEqualTo("audio/mp4a-latm")
        assertThat(audio.channels).isEqualTo(6)
        assertThat(audio.sampleRate).isEqualTo(48000)
        assertThat(audio.language).isEqualTo("en")
        assertThat(DiagnosticFormats.video(Format.Builder().build())).isEqualTo(DiagnosticFormat())
        assertThat(DiagnosticFormats.audio(null)).isEqualTo(DiagnosticFormat())
    }
    @Test fun `HDR transfer and Dolby Vision are extracted only when supplied`() {
        val pq = Format.Builder().setColorInfo(ColorInfo.Builder().setColorTransfer(C.COLOR_TRANSFER_ST2084).build()).build()
        assertThat(DiagnosticFormats.video(pq).hdr).isEqualTo("HDR10 / PQ")
        assertThat(DiagnosticFormats.video(Format.Builder().setSampleMimeType("video/dolby-vision").build()).hdr).isEqualTo("Dolby Vision")
    }
    @Test fun `buffer duration uses total buffered duration and unknown is unavailable`() {
        val fake = DiagnosticPlayer()
        val observer = PlaybackDiagnostics(fake.player)
        fake.buffered = 2400; fake.position = 10000
        observer.sample()
        assertThat(observer.snapshot.value.bufferedMs).isEqualTo(2400)
        fake.buffered = C.TIME_UNSET; fake.position = C.TIME_UNSET
        observer.sample()
        assertThat(observer.snapshot.value.bufferedMs).isNull()
        assertThat(observer.snapshot.value.positionMs).isNull()
        assertThat(observer.snapshot.value.throughputBps).isNull()
        assertThat(observer.snapshot.value.estimatedBandwidthBps).isNull()
        assertThat(observer.snapshot.value.renderedFrames).isNull()
        observer.close()
    }
    @Test fun `counts rebuffer once per transition excluding startup pause and seeks`() {
        var now = 0L; val t = DiagnosticTimeline { now }
        t.state(Player.STATE_BUFFERING, true, false); now = 1000
        t.state(Player.STATE_READY, true, true)
        assertThat(t.rebuffers).isEqualTo(0)
        t.state(Player.STATE_BUFFERING, true, false); t.state(Player.STATE_BUFFERING, true, false)
        now = 3500; t.state(Player.STATE_READY, true, true)
        assertThat(t.rebuffers).isEqualTo(1)
        assertThat(t.bufferingMs()).isEqualTo(3500)
        t.state(Player.STATE_READY, false, false)
        t.seek(); t.state(Player.STATE_BUFFERING, true, false)
        t.state(Player.STATE_READY, true, true)
        assertThat(t.rebuffers).isEqualTo(1)
        now = 61000; assertThat(t.frequency()).isWithin(0.001).of(60000.0 / 61000)
    }
    @Test fun `health follows documented state error and recent interruption rules`() {
        var now = 0L; val t = DiagnosticTimeline { now }
        assertThat(t.health()).isEqualTo(PlaybackHealth.UNKNOWN)
        t.state(Player.STATE_BUFFERING, true, false)
        assertThat(t.health()).isEqualTo(PlaybackHealth.BUFFERING)
        t.state(Player.STATE_READY, true, true)
        assertThat(t.health()).isEqualTo(PlaybackHealth.NORMAL)
        t.state(Player.STATE_BUFFERING, true, false); t.state(Player.STATE_READY, true, true)
        assertThat(t.health()).isEqualTo(PlaybackHealth.INTERRUPTED)
        now = 30001; assertThat(t.health()).isEqualTo(PlaybackHealth.NORMAL)
        t.failure("Safe error", false)
        assertThat(t.health()).isEqualTo(PlaybackHealth.ERROR)
        assertThat(t.interruptions).isEqualTo(1)
        t.state(Player.STATE_READY, true, true)
        t.state(Player.STATE_ENDED, true, false)
        assertThat(t.health()).isEqualTo(PlaybackHealth.UNKNOWN)
        assertThat(t.history()).isEmpty()
    }
    @Test fun `dropped frames throughput and bounded history reset for a new session`() {
        var now = 0L; val t = DiagnosticTimeline { now }
        t.dropped(2); t.dropped(3); t.dropped(-10)
        assertThat(t.dropped).isEqualTo(5)
        t.bandwidth(1000, 125000)
        assertThat(t.recentThroughput()).isEqualTo(1_000_000)
        repeat(1000) { t.record("Sample"); now += 100 }
        assertThat(t.history()).hasSize(300)
        now += 300001
        assertThat(t.history()).isEmpty()
        assertThat(t.recentThroughput()).isNull()
        t.reset()
        assertThat(t.dropped).isEqualTo(0)
        assertThat(t.rebuffers).isEqualTo(0)
        assertThat(t.bufferingMs()).isEqualTo(0)
        assertThat(t.history()).isEmpty()
    }
    @Test fun `listener ownership is balanced close idempotent and samples never control playback`() {
        val fake = DiagnosticPlayer(); val observer = PlaybackDiagnostics(fake.player)
        assertThat(fake.listeners).hasSize(1); assertThat(fake.analytics).hasSize(1)
        fake.state = Player.STATE_READY; fake.playing = true; fake.emit()
        observer.sample()
        assertThat(observer.snapshot.value.health).isEqualTo(PlaybackHealth.NORMAL)
        observer.beginSession("HLS")
        assertThat(observer.snapshot.value).isEqualTo(DiagnosticSnapshot())
        observer.sample(); assertThat(observer.snapshot.value.protocol).isEqualTo("HLS")
        observer.close(); observer.close(); observer.sample()
        assertThat(fake.listeners).isEmpty(); assertThat(fake.analytics).isEmpty()
        assertThat(fake.playing).isTrue()
        assertThat(observer.snapshot.value.history).isEmpty()
    }
    @Test fun `Media3 callbacks retain rendered totals and clear baselines without changing playback`() {
        var now = 0L
        val fake = DiagnosticPlayer()
        val observer = PlaybackDiagnostics(fake.player, clock = { now })
        val listener = fake.analytics.single()
        val event = AnalyticsListener.EventTime(0, Timeline.EMPTY, 0, null, 0, Timeline.EMPTY, 0, null, 0, 0)
        val counters = DecoderCounters().apply { renderedOutputBufferCount = 12 }
        fake.video = Format.Builder().setSampleMimeType("video/hevc").setWidth(3840).setHeight(2160).build()
        listener.onVideoEnabled(event, counters)
        listener.onVideoDecoderInitialized(event, "c2.test.hevc", 0, 1)
        listener.onDroppedVideoFrames(event, 3, 1000)
        listener.onBandwidthEstimate(event, 1000, 250000, 1800000)
        observer.sample()
        assertThat(observer.snapshot.value.video.resolution).isEqualTo("3840 × 2160")
        assertThat(observer.snapshot.value.decoder).isEqualTo("c2.test.hevc")
        assertThat(observer.snapshot.value.droppedFrames).isEqualTo(3)
        assertThat(observer.snapshot.value.renderedFrames).isEqualTo(12)
        assertThat(observer.snapshot.value.throughputBps).isEqualTo(2_000_000)
        assertThat(observer.snapshot.value.estimatedBandwidthBps).isEqualTo(1_800_000)
        observer.clear()
        assertThat(observer.snapshot.value.droppedFrames).isEqualTo(0)
        assertThat(observer.snapshot.value.renderedFrames).isEqualTo(0)
        counters.renderedOutputBufferCount = 15
        listener.onVideoDisabled(event, counters)
        val next = DecoderCounters().apply { renderedOutputBufferCount = 7 }
        listener.onVideoEnabled(event, next)
        observer.sample()
        assertThat(observer.snapshot.value.renderedFrames).isEqualTo(10)
        now = 10001; observer.sample()
        assertThat(observer.snapshot.value.throughputBps).isNull()
        observer.beginSession("DASH")
        observer.sample()
        assertThat(observer.snapshot.value.renderedFrames).isNull()
        assertThat(observer.snapshot.value.droppedFrames).isEqualTo(0)
        observer.close()
    }
    @Test fun `selected audio ordinal and active renderer format follow track changes`() {
        val fake = DiagnosticPlayer()
        val first = Format.Builder().setSampleMimeType("audio/ac3").setLanguage("en").build()
        val second = Format.Builder().setSampleMimeType("audio/aac").setLanguage("fr").setSampleRate(44100).build()
        fake.tracks = Tracks(listOf(Tracks.Group(TrackGroup(first, second), false,
            intArrayOf(C.FORMAT_HANDLED, C.FORMAT_HANDLED), booleanArrayOf(false, true))))
        fake.audio = second
        val observer = PlaybackDiagnostics(fake.player)
        observer.sample()
        assertThat(observer.snapshot.value.audioTrack).isEqualTo("Track 2")
        assertThat(observer.snapshot.value.audio.language).isEqualTo("fr")
        fake.audio = first; observer.sample()
        assertThat(observer.snapshot.value.audio.language).isEqualTo("en")
        observer.close()
    }
    @Test fun `late callbacks from a previous channel cannot contaminate a new session`() {
        val fake = DiagnosticPlayer().apply { video = Format.Builder().setSampleMimeType("video/avc").build() }
        val observer = PlaybackDiagnostics(fake.player)
        fun event(id: String): AnalyticsListener.EventTime {
            val item = MediaItem.Builder().setMediaId(id).build()
            val timeline = androidx.media3.exoplayer.source.SinglePeriodTimeline(1000, true, false, false, null, item)
            return AnalyticsListener.EventTime(0, timeline, 0, null, 0, timeline, 0, null, 0, 0)
        }
        val listener = fake.analytics.single()
        observer.beginSession("HLS", "new")
        listener.onDroppedVideoFrames(event("old"), 20, 1000)
        listener.onBandwidthEstimate(event("old"), 1000, 2000000, 1000000)
        listener.onPlayerError(event("old"), PlaybackException("secret", null, PlaybackException.ERROR_CODE_DECODING_FAILED))
        listener.onDroppedVideoFrames(event("new"), 2, 1000)
        observer.sample()
        assertThat(observer.snapshot.value.droppedFrames).isEqualTo(2)
        assertThat(observer.snapshot.value.throughputBps).isNull()
        assertThat(observer.snapshot.value.decoderErrors).isEqualTo(0)
        observer.close()
    }
    @Test fun `empty timeline retains only the active item's current playback error`() {
        val fake = DiagnosticPlayer()
        val observer = PlaybackDiagnostics(fake.player)
        val listener = fake.analytics.single()
        val event = AnalyticsListener.EventTime(0, Timeline.EMPTY, 0, null, 0, Timeline.EMPTY, 0, null, 0, 0)
        val oldError = PlaybackException("old secret", null, PlaybackException.ERROR_CODE_DECODING_FAILED)
        val currentError = PlaybackException("current secret", null, PlaybackException.ERROR_CODE_DECODING_FAILED)
        observer.beginSession("HLS", "new")
        fake.item = MediaItem.Builder().setMediaId("old").build()
        fake.error = oldError
        listener.onPlayerError(event, oldError) // Same error, but still belongs to the old item.
        fake.item = MediaItem.Builder().setMediaId("new").build()
        fake.error = currentError
        listener.onPlayerError(event, oldError) // Active item, but a delayed previous error.
        fake.item = null
        listener.onPlayerError(event, currentError) // No item to associate with the session.
        observer.sample()
        assertThat(observer.snapshot.value.decoderErrors).isEqualTo(0)
        assertThat(observer.snapshot.value.error).isNull()
        fake.item = MediaItem.Builder().setMediaId("new").build()
        listener.onPlayerError(event, currentError)
        observer.sample()
        assertThat(observer.snapshot.value.decoderErrors).isEqualTo(1)
        assertThat(observer.snapshot.value.health).isEqualTo(PlaybackHealth.ERROR)
        assertThat(observer.snapshot.value.error).isEqualTo(PlaybackErrors.describe(currentError))
        assertThat(observer.snapshot.value.error).doesNotContain("secret")
        observer.close()
    }
    @Test fun `empty timeline fallback does not relax filtering for other callbacks`() {
        val fake = DiagnosticPlayer().apply {
            item = MediaItem.Builder().setMediaId("new").build()
            error = PlaybackException("secret", null, PlaybackException.ERROR_CODE_DECODING_FAILED)
            video = Format.Builder().setSampleMimeType("video/avc").build()
        }
        val observer = PlaybackDiagnostics(fake.player)
        observer.beginSession("HLS", "new")
        val listener = fake.analytics.single()
        val event = AnalyticsListener.EventTime(0, Timeline.EMPTY, 0, null, 0, Timeline.EMPTY, 0, null, 0, 0)
        listener.onDroppedVideoFrames(event, 20, 1000)
        listener.onBandwidthEstimate(event, 1000, 2000000, 1000000)
        listener.onVideoEnabled(event, DecoderCounters().apply { renderedOutputBufferCount = 10 })
        listener.onVideoDecoderInitialized(event, "old.decoder", 0, 1)
        listener.onVideoCodecError(event, IllegalStateException("secret"))
        listener.onAudioCodecError(event, IllegalStateException("secret"))
        observer.sample()
        assertThat(observer.snapshot.value.droppedFrames).isEqualTo(0)
        assertThat(observer.snapshot.value.throughputBps).isNull()
        assertThat(observer.snapshot.value.estimatedBandwidthBps).isNull()
        assertThat(observer.snapshot.value.renderedFrames).isNull()
        assertThat(observer.snapshot.value.decoder).isNull()
        assertThat(observer.snapshot.value.decoderErrors).isEqualTo(0)
        observer.close()
    }
    @Test fun `HTTP status is classified through wrapped causes without exposing request data`() {
        val request = androidx.media3.datasource.DataSpec(android.net.Uri.parse("https://user:pass@private.invalid/token?auth=secret"))
        val cause = androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException(
            403, "secret", null, mapOf("Authorization" to listOf("secret")), request, byteArrayOf())
        val error = PlaybackException("secret", IOException("secret", cause), PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS)
        assertThat(PlaybackErrors.describe(error)).contains("403")
        assertThat(PlaybackErrors.describe(error)).doesNotContain("secret")
        assertThat(PlaybackErrors.describe(error)).doesNotContain("private.invalid")
    }
    @Test fun `nested errors and hostile messages cannot expose URLs tokens or headers`() {
        val secret = "https://user:password@private.invalid/live/token?authorization=secret"
        val error = PlaybackException(secret, IOException(secret, SocketTimeoutException(secret)), PlaybackException.ERROR_CODE_IO_UNSPECIFIED)
        assertThat(PlaybackErrors.describe(error)).contains("stopped responding")
        for (code in listOf(PlaybackException.ERROR_CODE_DECODER_INIT_FAILED, PlaybackException.ERROR_CODE_DECODING_FAILED,
            PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED, PlaybackException.ERROR_CODE_IO_UNSPECIFIED, -1)) {
            val text = PlaybackErrors.describe(PlaybackException(secret, IOException(secret), code))
            assertThat(text).doesNotContain("private.invalid"); assertThat(text).doesNotContain("secret")
        }
        assertThat(DiagnosticFormats.token(secret)).isNull()
    }
    @Test fun `known protocol detection does not mistake all HTTP links for HLS`() {
        assertThat(DiagnosticProtocol.identify(MediaItem.fromUri("https://private.invalid/live/user/pass"))).isNull()
        assertThat(DiagnosticProtocol.identify(MediaItem.fromUri("https://private.invalid/live.m3u8?token=secret"))).isEqualTo("HLS")
        assertThat(DiagnosticProtocol.identify(MediaItem.fromUri("rtsp://private.invalid/stream"))).isEqualTo("RTSP")
        assertThat(DiagnosticProtocol.identify(MediaItem.fromUri("optvrec://3"))).isEqualTo("Local recording / file")
    }
}
