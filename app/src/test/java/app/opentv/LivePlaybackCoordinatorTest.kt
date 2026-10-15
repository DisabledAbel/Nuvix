/* Copyright (C) 2026 Nuvix Contributors. Licensed under GPL-3.0-or-later. */
package app.opentv

import app.opentv.data.model.Channel
import app.opentv.player.ExclusiveSurfaceOwner
import app.opentv.player.LivePlaybackCoordinator
import app.opentv.player.LivePlaybackEngine
import app.opentv.player.LivePresentation
import app.opentv.player.PlayerController
import app.opentv.player.ResolvedLiveChannel
import app.opentv.player.RecordingOwnsLiveSourceException
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LivePlaybackCoordinatorTest {
    private class FakeEngine : LivePlaybackEngine {
        override val state = MutableStateFlow<PlayerController.State>(PlayerController.State.Idle)
        override val paused = MutableStateFlow(false)
        val requests = mutableListOf<PlayerController.Request>()
        var muted = false
        var suspendedValue = false
        var releases = 0
        override fun play(request: PlayerController.Request) { requests += request; state.value = PlayerController.State.Playing(request.title) }
        override fun setPaused(paused: Boolean) { this.paused.value = paused }
        override fun setBrowsingMuted(muted: Boolean) { this.muted = muted }
        override fun setSuspended(suspended: Boolean) { this.suspendedValue = suspended }
        override fun release() { releases++ }
    }

    private fun channel(id: Long) = Channel(
        id = id, sourceId = 7L, streamId = id.toString(), name = "Channel $id",
        categoryId = null, logoUrl = null, epgChannelId = null, number = id.toInt(),
        streamUrl = "https://example.invalid/live/$id",
    )

    private fun resolved(channel: Channel) = ResolvedLiveChannel(
        channel, listOf(channel), PlayerController.Request(channel.streamUrl, channel.name, "Nuvix test"),
    )

    private inner class Fixture(scope: TestScope, resolver: suspend (Channel) -> ResolvedLiveChannel = { resolved(it) }) {
        val engines = mutableListOf<FakeEngine>()
        val session = LivePlaybackCoordinator(
            scope.backgroundScope, { channel(it) }, resolver,
            { FakeEngine().also(engines::add) },
        )
        val engine get() = engines.single()
    }

    @Test fun `live player is lazy and is created once across Home Guide Sports and fullscreen`() = runTest {
        val f = Fixture(this)
        assertThat(f.engines).isEmpty()
        f.session.openChannel(1)
        assertThat(f.session.active.value).isTrue() // Guide relinquishes its preview immediately.
        runCurrent()
        val engine = f.engine
        repeat(3) { // Home, Guide, Sports use the same browsing presentation.
            f.session.setPresentation(LivePresentation.BROWSING)
            f.session.setPresentation(LivePresentation.FULLSCREEN)
            f.session.openChannel(1)
        }
        runCurrent()
        assertThat(f.engines).containsExactly(engine)
        assertThat(engine.requests).hasSize(1)
        assertThat(engine.releases).isEqualTo(0)
    }

    @Test fun `explicit channel change tunes once and keeps current channel consistent`() = runTest {
        val f = Fixture(this)
        f.session.openChannel(1); runCurrent()
        f.session.openChannel(2); f.session.openChannel(2); runCurrent()
        assertThat(f.engine.requests.map { it.title }).containsExactly("Channel 1", "Channel 2").inOrder()
        assertThat(f.session.currentChannel.value?.id).isEqualTo(2)
        assertThat(f.session.previousChannelId.value).isEqualTo(1)
        assertThat(f.session.variants.value.single().id).isEqualTo(2)
    }

    @Test fun `quality and Mini Guide channel list tune reuse the same engine`() = runTest {
        val f = Fixture(this)
        f.session.openChannel(1); runCurrent()
        f.session.openChannel(2); runCurrent()
        f.session.tune(channel(3)); runCurrent()
        assertThat(f.session.previousChannelId.value).isEqualTo(1)
        assertThat(f.engine.requests).hasSize(3)
        assertThat(f.engines).hasSize(1)
        // Opening/dismissing an overlay does not call any playback method.
        assertThat(f.session.currentChannel.value?.id).isEqualTo(3)
    }

    @Test fun `rapid requests cancel obsolete resolutions before any tune`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val f = Fixture(this) { selected ->
            if (selected.id == 1L) withContext(NonCancellable) { gate.await() }
            resolved(selected)
        }
        f.session.openChannel(1); runCurrent()
        f.session.openChannel(2); runCurrent()
        gate.complete(Unit); runCurrent()
        assertThat(f.engine.requests.map { it.title }).containsExactly("Channel 2")
        assertThat(f.session.currentChannel.value?.id).isEqualTo(2)
    }

    @Test fun `changing presentation while a lookup is pending cannot start background playback`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val f = Fixture(this) { gate.await(); resolved(it) }
        f.session.openChannel(1); runCurrent()
        f.session.setForeground(false)
        gate.complete(Unit); runCurrent()
        assertThat(f.engine.suspendedValue).isTrue()
        f.session.setForeground(true)
        assertThat(f.engine.suspendedValue).isFalse()
        assertThat(f.engine.requests).hasSize(1)
    }

    @Test fun `browsing audio preference mutes without replacing or pausing the stream`() = runTest {
        val f = Fixture(this)
        f.session.openChannel(1); runCurrent()
        f.session.updatePreferences(persistent = true, continueAudio = false)
        f.session.setPresentation(LivePresentation.BROWSING)
        assertThat(f.engine.muted).isTrue()
        assertThat(f.engine.suspendedValue).isFalse()
        f.session.setPresentation(LivePresentation.FULLSCREEN)
        assertThat(f.engine.muted).isFalse()
        assertThat(f.engine.requests).hasSize(1)
    }

    @Test fun `browsing with audio enabled and preview absent retains a single stream`() = runTest {
        val f = Fixture(this)
        f.session.openChannel(1); runCurrent()
        f.session.setPresentation(LivePresentation.BROWSING)
        // Preview preference is a view-only concern: no attachment is required to retain audio.
        assertThat(f.engine.muted).isFalse()
        assertThat(f.engine.requests).hasSize(1)
        assertThat(f.session.active.value).isTrue()
    }

    @Test fun `user pause survives browsing background and return to fullscreen`() = runTest {
        val f = Fixture(this)
        f.session.openChannel(1); runCurrent()
        f.session.setPaused(true)
        f.session.setPresentation(LivePresentation.BROWSING)
        f.session.setForeground(false)
        f.session.setForeground(true)
        f.session.setPresentation(LivePresentation.FULLSCREEN)
        assertThat(f.engine.paused.value).isTrue()
        assertThat(f.session.paused.value).isTrue()
        assertThat(f.engine.requests).hasSize(1)
    }

    @Test fun `permanent audio focus loss remains paused after navigation`() = runTest {
        val f = Fixture(this)
        f.session.openChannel(1); runCurrent()
        // The Media3 adapter reports AUDIO_FOCUS_LOSS through its paused StateFlow.
        f.engine.paused.value = true
        runCurrent()
        f.session.setPresentation(LivePresentation.BROWSING)
        f.session.setPresentation(LivePresentation.FULLSCREEN)
        assertThat(f.engine.paused.value).isTrue()
        f.session.setPaused(false)
        assertThat(f.engine.paused.value).isFalse()
    }

    @Test fun `PiP continues one player while stopped Activity suspends after PiP closes`() = runTest {
        val f = Fixture(this)
        f.session.openChannel(1); runCurrent()
        f.session.setInPip(true)
        f.session.setForeground(false)
        assertThat(f.engine.suspendedValue).isFalse()
        f.session.setInPip(false)
        assertThat(f.engine.suspendedValue).isTrue()
        f.session.setForeground(true)
        assertThat(f.engine.suspendedValue).isFalse()
        assertThat(f.engine.requests).hasSize(1)
    }

    @Test fun `unsupported destinations suspend until supported browsing resumes`() = runTest {
        val f = Fixture(this)
        f.session.openChannel(1); runCurrent()
        f.session.setPresentation(LivePresentation.HIDDEN)
        assertThat(f.engine.suspendedValue).isTrue()
        f.session.setPresentation(LivePresentation.BROWSING)
        assertThat(f.engine.suspendedValue).isFalse()
        assertThat(f.engine.requests).hasSize(1)
    }

    @Test fun `persistence disabled releases only after fullscreen ends`() = runTest {
        val f = Fixture(this)
        f.session.openChannel(1); runCurrent()
        val first = f.engine
        f.session.updatePreferences(false, true)
        assertThat(first.releases).isEqualTo(0)
        f.session.setPresentation(LivePresentation.BROWSING)
        assertThat(first.releases).isEqualTo(1)
        assertThat(f.session.active.value).isFalse()
        f.session.setPresentation(LivePresentation.FULLSCREEN)
        f.session.openChannel(1); runCurrent()
        assertThat(f.engines).hasSize(2)
        assertThat(f.engines.last().requests).hasSize(1)
    }

    @Test fun `turning persistence off while browsing cancels pending lookup`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val f = Fixture(this) { gate.await(); resolved(it) }
        f.session.setPresentation(LivePresentation.BROWSING)
        f.session.openChannel(1); runCurrent()
        f.session.updatePreferences(false, true)
        gate.complete(Unit); runCurrent()
        assertThat(f.engines).isEmpty()
        assertThat(f.session.active.value).isFalse()
    }

    @Test fun `memory pressure only discards optional browsing player`() = runTest {
        val f = Fixture(this)
        f.session.openChannel(1); runCurrent()
        val engine = f.engine
        f.session.onMemoryPressure(true)
        assertThat(engine.releases).isEqualTo(0)
        f.session.setPresentation(LivePresentation.BROWSING)
        f.session.onMemoryPressure(false)
        assertThat(engine.releases).isEqualTo(0)
        f.session.onMemoryPressure(true)
        assertThat(engine.releases).isEqualTo(1)
        assertThat(f.session.active.value).isFalse()
        assertThat(f.session.guidePreviewSuppressed.value).isTrue()
        f.session.openChannel(1); runCurrent()
        assertThat(f.session.guidePreviewSuppressed.value).isFalse()
    }

    @Test fun `PiP retains decoder under memory pressure`() = runTest {
        val f = Fixture(this)
        f.session.openChannel(1); runCurrent()
        f.session.setInPip(true)
        f.session.setPresentation(LivePresentation.BROWSING)
        f.session.onMemoryPressure(true)
        assertThat(f.engine.releases).isEqualTo(0)
    }

    @Test fun `scheduled recording yields same-provider live without stopping other sources`() = runTest {
        val f = Fixture(this)
        f.session.openChannel(1); runCurrent()
        val engine = f.engine
        f.session.onRecordingStarted(8)
        assertThat(engine.releases).isEqualTo(0)
        f.session.onRecordingStarted(7)
        assertThat(engine.releases).isEqualTo(1)
        assertThat(f.session.active.value).isFalse()
    }

    @Test fun `VOD and recording ownership release live before another allocation`() = runTest {
        for (destination in listOf("VOD", "recording", "catchup")) {
            val f = Fixture(this)
            f.session.openChannel(1); runCurrent()
            val engine = f.engine
            f.session.stop() // Destination integration does this before constructing its player.
            assertThat(engine.releases).isEqualTo(1)
            assertThat(f.session.active.value).isFalse()
            assertThat(f.session.currentChannel.value).isNull()
            assertThat(destination).isNotEmpty()
        }
    }

    @Test fun `player errors remain observable across navigation and recovery reuses engine`() = runTest {
        val f = Fixture(this)
        f.session.openChannel(1); runCurrent()
        val error = PlayerController.State.Error("Channel 1", "Network unavailable", true)
        f.engine.state.value = error; runCurrent()
        f.session.setPresentation(LivePresentation.BROWSING)
        assertThat(f.session.state.value).isEqualTo(error)
        f.session.retry(); runCurrent()
        assertThat(f.engines).hasSize(1)
        assertThat(f.engine.requests).hasSize(2)
        assertThat(f.session.currentChannel.value?.id).isEqualTo(1)
    }

    @Test fun `recording-owned provider fails safely before allocating another stream`() = runTest {
        val f = Fixture(this) { throw RecordingOwnsLiveSourceException() }
        f.session.openChannel(1); runCurrent()
        assertThat(f.engines).isEmpty()
        val error = f.session.state.value as PlayerController.State.Error
        assertThat(error.message).contains("A recording is using this provider")
        assertThat(error.canRetry).isTrue()
        f.session.setPresentation(LivePresentation.BROWSING)
        assertThat(f.session.state.value).isEqualTo(error)
    }

    @Test fun `provider exceptions expose safe retry and never leak credentials`() = runTest {
        var failed = true
        val f = Fixture(this) {
            if (failed) error("https://user:secret@example.invalid/private-token")
            resolved(it)
        }
        f.session.openChannel(1); runCurrent()
        val error = f.session.state.value as PlayerController.State.Error
        assertThat(error.message).doesNotContain("secret")
        assertThat(error.canRetry).isTrue()
        failed = false
        f.session.retry(); runCurrent()
        assertThat(f.engines).hasSize(1)
        assertThat(f.engine.requests).hasSize(1)
    }

    @Test fun `non-live handoff suppresses outgoing guide preview until another explicit live request`() = runTest {
        val f = Fixture(this)
        f.session.openChannel(1); runCurrent()
        f.session.stopForOtherPlayback()
        assertThat(f.session.guidePreviewSuppressed.value).isTrue()
        assertThat(f.session.active.value).isFalse()
        assertThat(f.engine.releases).isEqualTo(1)
        f.session.openChannel(2)
        assertThat(f.session.guidePreviewSuppressed.value).isFalse()
        runCurrent()
        assertThat(f.engines).hasSize(2)
        assertThat(f.engines.last().requests).hasSize(1)
    }

    @Test fun `session destruction releases once and cannot allocate again`() = runTest {
        val f = Fixture(this)
        f.session.openChannel(1); runCurrent()
        val engine = f.engine
        f.session.destroy(); f.session.destroy()
        f.session.openChannel(2); runCurrent()
        assertThat(engine.releases).isEqualTo(1)
        assertThat(f.engines).hasSize(1)
    }

    @Test fun `new process owner starts empty and never resumes persisted credentials implicitly`() = runTest {
        val first = Fixture(this)
        first.session.openChannel(1); runCurrent(); first.session.destroy()
        val recreated = Fixture(this)
        assertThat(recreated.session.active.value).isFalse()
        assertThat(recreated.session.currentChannel.value).isNull()
        assertThat(recreated.engines).isEmpty()
    }

    @Test fun `returning to current channel cancels a pending different selection`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val f = Fixture(this) { selected ->
            if (selected.id == 2L) withContext(NonCancellable) { gate.await() }
            resolved(selected)
        }
        f.session.openChannel(1); runCurrent()
        f.session.openChannel(2); runCurrent()
        f.session.openChannel(1)
        gate.complete(Unit); runCurrent()
        assertThat(f.engine.requests.map { it.title }).containsExactly("Channel 1")
        assertThat(f.session.currentChannel.value?.id).isEqualTo(1)
    }

    @Test fun `failure resolving a different channel remains observable over old playback events`() = runTest {
        val f = Fixture(this) { selected ->
            if (selected.id == 2L) error("Provider unavailable")
            resolved(selected)
        }
        f.session.openChannel(1); runCurrent()
        f.session.openChannel(2); runCurrent()
        val error = f.session.state.value
        f.engine.state.value = PlayerController.State.Buffering("Channel 1"); runCurrent()
        assertThat(f.session.state.value).isEqualTo(error)
        assertThat(error).isInstanceOf(PlayerController.State.Error::class.java)
    }

    @Test fun `reopening a failed channel retries resolution but healthy reentry does not`() = runTest {
        var resolves = 0
        val f = Fixture(this) {
            resolves++
            if (resolves == 1) error("Unavailable")
            resolved(it)
        }
        f.session.openChannel(1); runCurrent()
        f.session.openChannel(1); runCurrent()
        f.session.openChannel(1); runCurrent()
        assertThat(resolves).isEqualTo(2)
        assertThat(f.engine.requests).hasSize(1)
    }

    @Test fun `failed outgoing surface detachment never binds a competing surface`() {
        val first = Any(); val second = Any()
        val bindings = mutableListOf<Pair<Any, Boolean>>()
        val owner = ExclusiveSurfaceOwner<Any> { view, attach ->
            bindings += view to attach
            if (view === first && !attach) error("Device rejected detachment")
        }
        owner.attach(first)
        assertThat(owner.attach(second)).isFalse()
        assertThat(bindings).containsExactly(first to true, first to false).inOrder()
        owner.clear() // Cleanup cannot crash the Activity.
    }

    @Test fun `failed explicit surface disposal blocks attachments until decoder ownership resets`() {
        val first = Any(); val second = Any()
        val bindings = mutableListOf<Pair<Any, Boolean>>()
        val owner = ExclusiveSurfaceOwner<Any> { view, attach ->
            bindings += view to attach
            if (view === first && !attach) error("Device rejected disposal")
        }
        owner.attach(first)
        owner.detach(first)
        assertThat(owner.attach(second)).isFalse()
        assertThat(owner.clear()).isFalse()
        assertThat(bindings).containsExactly(first to true, first to false).inOrder()
        owner.reset() // The owning session has released the old decoder before constructing another.
        assertThat(owner.attach(second)).isTrue()
    }

    @Test fun `surface handoff detaches first and stale disposal cannot detach replacement`() {
        val bindings = mutableListOf<Pair<Any, Boolean>>()
        val owner = ExclusiveSurfaceOwner<Any> { view, attached -> bindings += view to attached }
        val full = Any(); val preview = Any()
        owner.attach(full); owner.attach(preview); owner.detach(full)
        assertThat(bindings).containsExactly(full to true, full to false, preview to true).inOrder()
        owner.detach(preview)
        assertThat(bindings.last()).isEqualTo(preview to false)
    }

    @Test fun `surface disposal does not release or retune session`() = runTest {
        val f = Fixture(this)
        f.session.openChannel(1); runCurrent()
        val owner = ExclusiveSurfaceOwner<Any> { _, _ -> }
        val view = Any()
        owner.attach(view); owner.detach(view); owner.clear()
        assertThat(f.engine.releases).isEqualTo(0)
        assertThat(f.engine.requests).hasSize(1)
    }

    @Test fun `surface failure clears failed binding for fallback without allocating playback`() {
        val bindings = mutableListOf<Boolean>()
        val owner = ExclusiveSurfaceOwner<Any> { _, attached ->
            bindings += attached
            if (attached) error("Surface lost")
        }
        assertThat(owner.attach(Any())).isFalse()
        owner.clear()
        assertThat(bindings).containsExactly(true, false).inOrder()
    }
}
