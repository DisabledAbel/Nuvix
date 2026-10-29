/* Copyright (C) 2026 Nuvix Contributors. GPL-3.0-or-later. */
package app.opentv.player

import android.app.Application
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.lang.reflect.Proxy
import java.io.IOException

@OptIn(UnstableApi::class)
@kotlin.OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class Media3PlaybackRecoveryTest {
    private class Fake {
        val listeners = mutableSetOf<Player.Listener>()
        val operations = mutableListOf<String>()
        var state = Player.STATE_BUFFERING
        var playWhenReady = true
        var suppression = Player.PLAYBACK_SUPPRESSION_REASON_NONE
        var position = 0L
        var error: androidx.media3.exoplayer.ExoPlaybackException? = null
        val player = Proxy.newProxyInstance(ExoPlayer::class.java.classLoader, arrayOf(ExoPlayer::class.java)) { proxy, method, args ->
            when (method.name) {
                "addListener" -> { listeners += args!![0] as Player.Listener; null }
                "removeListener" -> { listeners -= args!![0] as Player.Listener; null }
                "getPlaybackState" -> state
                "getPlayWhenReady" -> playWhenReady
                "getPlaybackSuppressionReason" -> suppression
                "getCurrentPosition" -> position
                "getPlayerError" -> error
                "getVideoDecoderCounters", "getVideoFormat" -> null
                "getTotalBufferedDuration" -> 0L
                "getCurrentLiveOffset" -> C.TIME_UNSET
                "isCommandAvailable" -> true
                "stop" -> { operations += "stop"; error = null; state = Player.STATE_IDLE; null }
                "seekToDefaultPosition" -> { operations += "seek"; position = 0; null }
                "prepare" -> { operations += "prepare"; state = Player.STATE_BUFFERING; null }
                "equals" -> proxy === args!![0]
                "hashCode" -> System.identityHashCode(proxy)
                "toString" -> "RecoveryFake"
                else -> error("Unexpected recovery player operation: ${method.name}")
            }
        } as ExoPlayer
        fun fail(failure: PlaybackException = PlaybackException("private", IOException("private"), PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED)) {
            val sourceError = androidx.media3.exoplayer.ExoPlaybackException.createForSource(IOException(failure), failure.errorCode)
            error = sourceError; state = Player.STATE_IDLE
            listeners.toList().forEach { it.onPlayerError(sourceError) }
        }
    }
    private class Fixture(val scope: TestScope) {
        val fake = Fake()
        val budget = RecoveryBudget()
        var allowed = true
        var reconnects = 0
        var failures = 0
        val adapter = Media3PlaybackRecovery(fake.player, scope.backgroundScope, budget, { allowed }, { BufferPolicy.MEDIUM },
            { reconnects++ }, { failures++ }, { scope.testScheduler.currentTime })
        fun advance(ms: Long) { scope.advanceTimeBy(ms); scope.runCurrent() }
    }
    @Test fun `network failure makes one lightweight attempt on the same player and budget`() = runTest {
        val f = Fixture(this); runCurrent(); f.fake.fail(); f.fake.fail()
        f.advance(1_999); assertTrue(f.fake.operations.isEmpty())
        f.advance(1); assertEquals(listOf("stop", "seek", "prepare"), f.fake.operations)
        assertEquals(1, f.budget.snapshot.value.attempts); assertEquals(0, f.reconnects)
        f.adapter.close()
    }
    @Test fun `second failure calls owner reconnect once and stops its monitor`() = runTest {
        val f = Fixture(this); runCurrent(); f.fake.fail(); f.advance(2_000)
        f.fake.fail(); f.advance(5_000); assertEquals(1, f.reconnects)
        f.advance(600_000); assertEquals(1, f.reconnects)
        f.adapter.close(); assertTrue(f.fake.listeners.isEmpty())
    }
    @Test fun `authorization failures stop without preparing`() = runTest {
        val f = Fixture(this); runCurrent(); f.fake.fail(http(403)); f.advance(1_000)
        assertEquals(1, f.failures); assertEquals(listOf("stop"), f.fake.operations)
        f.advance(600_000); assertEquals(1, f.failures); f.adapter.close()
    }
    @Test fun `rate limit cannot prepare before retry after`() = runTest {
        val f = Fixture(this); runCurrent(); f.fake.fail(http(429, "120"))
        f.advance(119_000); assertTrue(f.fake.operations.isEmpty())
        f.advance(1_000); assertEquals(1, f.budget.snapshot.value.attempts); f.adapter.close()
    }
    @Test fun `sports owner and background suspension block recovery operations`() = runTest {
        val f = Fixture(this); runCurrent(); f.allowed = false; f.fake.fail()
        f.advance(600_000); assertTrue(f.fake.operations.isEmpty()); assertEquals(0, f.reconnects)
        f.adapter.close()
    }
    @Test fun `audio focus suppression and pause are not playback stalls`() = runTest {
        val f = Fixture(this); runCurrent()
        f.fake.suppression = Player.PLAYBACK_SUPPRESSION_REASON_TRANSIENT_AUDIO_FOCUS_LOSS
        f.advance(120_000)
        f.fake.suppression = Player.PLAYBACK_SUPPRESSION_REASON_NONE; f.fake.playWhenReady = false
        f.advance(120_000); assertTrue(f.fake.operations.isEmpty()); f.adapter.close()
    }
    @Test fun `release cancels scheduled retry removes listener and releases no account itself`() = runTest {
        val f = Fixture(this); runCurrent(); f.fake.fail(); f.adapter.close(); f.adapter.close()
        f.advance(600_000); assertTrue(f.fake.operations.isEmpty()); assertTrue(f.fake.listeners.isEmpty())
        assertEquals(0, f.reconnects)
    }
    @Test fun `explicit no-op selection rebinds its budget without disabling the retained watchdog`() = runTest {
        val f = Fixture(this); runCurrent(); f.budget.reset(); f.advance(1_000)
        f.fake.fail(); f.advance(2_000)
        assertEquals(listOf("stop", "seek", "prepare"), f.fake.operations)
        assertEquals(1, f.budget.snapshot.value.attempts); f.adapter.close()
    }

    @Test fun `stale error callback does not retry another request`() = runTest {
        val f = Fixture(this); runCurrent()
        f.fake.listeners.forEach { it.onPlayerError(http(503)) }
        f.advance(5_000); assertTrue(f.fake.operations.isEmpty()); f.adapter.close()
    }
    @Test fun `http and media failures are classified without retaining provider values`() {
        mapOf(401 to RecoveryFailure.AUTHENTICATION, 403 to RecoveryFailure.AUTHORIZATION,
            429 to RecoveryFailure.RATE_LIMIT, 404 to RecoveryFailure.MISSING_STREAM,
            451 to RecoveryFailure.AUTHORIZATION, 503 to RecoveryFailure.NETWORK).forEach { (status, category) ->
            assertEquals(category, PlaybackErrors.recoveryFailure(http(status)))
        }
        assertEquals(RecoveryFailure.DRM, PlaybackErrors.recoveryFailure(PlaybackException("private", null, 6001)))
        assertEquals(RecoveryFailure.DECODER, PlaybackErrors.recoveryFailure(PlaybackException("private", null, 4001)))
        assertEquals(RecoveryFailure.UNSUPPORTED, PlaybackErrors.recoveryFailure(PlaybackException("private", null, 3004)))
        assertEquals(0L, PlaybackErrors.retryAfterMs(http(429, "private")))
        assertEquals(5_000L, PlaybackErrors.retryAfterMs(http(429, "Thu, 01 Jan 1970 00:00:05 GMT"), 0))
    }
    private fun http(status: Int, retryAfter: String = "0"): PlaybackException {
        val response = HttpDataSource.InvalidResponseCodeException(status, "private", null,
            mapOf("Retry-After" to listOf(retryAfter)), DataSpec(Uri.parse("https://user:secret@private.invalid/token")), byteArrayOf())
        return PlaybackException("private", IOException("private", response), PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS)
    }
}
