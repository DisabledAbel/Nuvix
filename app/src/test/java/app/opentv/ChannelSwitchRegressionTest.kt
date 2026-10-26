/* Copyright (C) 2026 Nuvix Contributors. Licensed under GPL-3.0-or-later. */
package app.opentv

import app.opentv.data.model.Channel
import app.opentv.player.*
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import kotlinx.coroutines.withContext
import org.junit.Test

/** No decoder or network: the provider refuses replacement resolution until its stream closes. */
@OptIn(ExperimentalCoroutinesApi::class)
class ChannelSwitchRegressionTest {
    private fun channel(id: Long, extension: String = "ts") = Channel(
        id = id, sourceId = 7, streamId = "$id", name = "Channel $id", categoryId = null,
        logoUrl = null, epgChannelId = null, number = id.toInt(), streamUrl = "https://fixture.invalid/$id.$extension",
    )
    private fun resolved(channel: Channel) = ResolvedLiveChannel(channel, listOf(channel),
        PlayerController.Request(channel.streamUrl, channel.name, "Fixture"))

    private class Engine(private val closed: () -> Unit) : LivePlaybackEngine {
        override val state = MutableStateFlow<PlayerController.State>(PlayerController.State.Idle)
        override val paused = MutableStateFlow(false)
        val requests = mutableListOf<PlayerController.Request>()
        var released = false
        override fun play(request: PlayerController.Request) {
            requests += request
            state.value = PlayerController.State.Playing(request.title)
        }
        override fun setPaused(paused: Boolean) { this.paused.value = paused }
        override fun setBrowsingMuted(muted: Boolean) = Unit
        override fun setSuspended(suspended: Boolean) = Unit
        override fun release() { released = true; closed() }
    }

    @Test fun `legacy resolve-before-close waits 60000ms while preflight prepares at 0ms`() = runTest {
        for (preflight in listOf(false, true)) {
            var streamOpen = false
            val engines = mutableListOf<Engine>()
            val events = mutableListOf<String>()
            val session = LivePlaybackCoordinator(backgroundScope, { channel(it) }, {
                if (it.id != 1L && streamOpen) delay(60_000) // controlled provider slot expiry
                events += "resolved"
                resolved(it)
            }, {
                Engine { events += "closed"; streamOpen = false }.also(engines::add)
            }, onTune = { streamOpen = true },
                beforeResolve = { selected, close -> if (preflight && selected.id != 1L) close() },
                preparePlayback = { _, close -> close() },
                afterEngineRelease = { events += "reservation freed" })
            session.openChannel(1); runCurrent()
            events.clear()
            val started = testScheduler.currentTime
            session.openChannel(2); runCurrent()
            if (!preflight) {
                assertThat(engines.single().released).isFalse()
                assertThat(engines.single().requests.single().title).isEqualTo("Channel 1")
                advanceTimeBy(60_000); runCurrent()
            }
            assertThat(testScheduler.currentTime - started).isEqualTo(if (preflight) 0L else 60_000L)
            assertThat(engines.first().released).isTrue()
            assertThat(engines.last().requests.single().title).isEqualTo("Channel 2")
            if (preflight) assertThat(events.take(3)).containsExactly("closed", "reservation freed", "resolved").inOrder()
            session.destroy()
        }
    }

    @Test fun `Up Up Down Up advances from latest selection without queuing intermediate prepares`() = runTest {
        val engines = mutableListOf<Engine>()
        val gate = CompletableDeferred<Unit>()
        val session = LivePlaybackCoordinator(backgroundScope, { channel(it) }, {
            if (it.id != 5L) gate.await()
            resolved(it)
        }, { Engine {}.also(engines::add) })
        session.openChannel(5); runCurrent()
        val queue = (1L..6L).map { PlaybackQueue.Item(it, "Channel $it", null) }
        for (delta in listOf(-1, -1, 1, -1)) {
            session.openChannel(requireNotNull(PlaybackQueue.adjacent(queue, session.requestedChannelId.value, delta)))
            runCurrent()
        }
        assertThat(session.requestedChannelId.value).isEqualTo(3)
        assertThat(session.requestedChannel.value?.name).isEqualTo("Channel 3")
        assertThat(session.preparingChannel.value?.id).isEqualTo(3)
        assertThat(session.playingChannel.value?.id).isEqualTo(5)
        assertThat(session.state.value).isEqualTo(PlayerController.State.Buffering("Channel 3"))
        gate.complete(Unit); runCurrent()
        assertThat(engines.single().requests.map { it.title }).containsExactly("Channel 5", "Channel 3").inOrder()
        assertThat(session.playingChannel.value?.id).isEqualTo(3)
        assertThat(session.preparingChannel.value).isNull()
    }

    @Test fun `Up and Down respect displayed order and queue boundaries`() {
        val queue = listOf(30L, 10L, 20L).map { PlaybackQueue.Item(it, "$it", null) }
        assertThat(PlaybackQueue.adjacent(queue, 10, -1)).isEqualTo(30)
        assertThat(PlaybackQueue.adjacent(queue, 10, 1)).isEqualTo(20)
        assertThat(PlaybackQueue.adjacent(queue, 30, -1)).isNull()
        assertThat(PlaybackQueue.adjacent(queue, 20, 1)).isNull()
        assertThat(PlaybackQueue.adjacent(emptyList(), null, 1)).isNull()
    }

    @Test fun `late preflight callback and old player state cannot release or replace newest owner`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val engines = mutableListOf<Engine>()
        val session = LivePlaybackCoordinator(backgroundScope, { channel(it) }, { resolved(it) },
            { Engine {}.also(engines::add) }, beforeResolve = { selected, close ->
                if (selected.id == 2L) withContext(NonCancellable) { gate.await(); close() }
            }, preparePlayback = { _, close -> close() })
        session.openChannel(1); runCurrent()
        session.openChannel(2); runCurrent()
        session.openChannel(3); runCurrent()
        val newest = engines.last()
        gate.complete(Unit)
        engines.first().state.value = PlayerController.State.Playing("Stale")
        runCurrent()
        assertThat(newest.released).isFalse()
        assertThat(session.playingChannel.value?.id).isEqualTo(3)
        assertThat(session.state.value).isEqualTo(PlayerController.State.Playing("Channel 3"))
        assertThat(engines.flatMap { it.requests }.map { it.title }).doesNotContain("Channel 2")
    }

    @Test fun `return to old channel after its stream closed must prepare it again`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val engines = mutableListOf<Engine>()
        val session = LivePlaybackCoordinator(backgroundScope, { channel(it) }, {
            if (it.id == 2L) gate.await()
            resolved(it)
        }, { Engine {}.also(engines::add) }, beforeResolve = { _, close -> close() })
        session.openChannel(1); runCurrent()
        session.openChannel(2); runCurrent()
        session.openChannel(1); runCurrent()
        assertThat(engines.last().requests.single().title).isEqualTo("Channel 1")
        assertThat(session.playingChannel.value?.id).isEqualTo(1)
    }

    @Test fun `failed link after constrained close is retryable without resurrecting old video`() = runTest {
        var fail = true
        val engines = mutableListOf<Engine>()
        val session = LivePlaybackCoordinator(backgroundScope, { channel(it) }, {
            if (it.id == 2L && fail) error("https://private.invalid/token")
            resolved(it)
        }, { Engine {}.also(engines::add) }, beforeResolve = { _, close -> close() })
        session.openChannel(1); runCurrent()
        session.openChannel(2); runCurrent()
        assertThat(session.playingChannel.value).isNull()
        assertThat(session.requestedChannel.value?.id).isEqualTo(2)
        assertThat((session.state.value as PlayerController.State.Error).message).doesNotContain("private")
        fail = false
        session.retry(); runCurrent()
        assertThat(engines.last().requests.single().title).isEqualTo("Channel 2")
    }

    @Test fun `HLS and MPEG TS keep request format through persistent presentation changes`() = runTest {
        for (extension in listOf("m3u8", "ts")) {
            val engines = mutableListOf<Engine>()
            val session = LivePlaybackCoordinator(backgroundScope, { channel(it, extension) }, { resolved(it) },
                { Engine {}.also(engines::add) })
            session.openChannel(1); runCurrent()
            session.setPresentation(LivePresentation.BROWSING)
            session.openChannel(2); runCurrent()
            session.setPresentation(LivePresentation.FULLSCREEN)
            assertThat(engines.single().requests.last().url).endsWith("2.$extension")
            assertThat(engines.single().requests).hasSize(2)
        }
    }
}
