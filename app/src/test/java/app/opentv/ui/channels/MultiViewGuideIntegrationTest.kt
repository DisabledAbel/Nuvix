/* Nuvix Copyright (C) 2026 Nuvix Contributors. GPL-3.0-or-later. */
package app.opentv.ui.channels

import android.app.Application
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import app.opentv.connections.*
import app.opentv.core.AppSettings
import app.opentv.data.model.Channel
import app.opentv.multiview.*
import app.opentv.ui.ChannelsViewModel
import app.opentv.ui.multiview.MultiViewCompactPreview
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Actual compact presentation and Guide remote navigation over two deterministic live players. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28], qualifiers = "w1280dp-h720dp-land")
@OptIn(ExperimentalCoroutinesApi::class, ExperimentalTestApi::class)
class MultiViewGuideIntegrationTest {
    @get:Rule val compose = createComposeRule()

    private class Connections : MultiViewConnections {
        val allocator = ConnectionAllocator()
        var reservations = 0
        init { allocator.registerSource(1, "synthetic-account", AccountConnectionPolicy(4, false)) }
        override fun snapshot(sourceId: Long) = allocator.snapshotForSource(sourceId)
        override fun reserve(request: ConnectionRequest, approval: UnknownConnectionApproval?): ReservationResult {
            reservations++
            return allocator.reserve(request, approval)
        }
        override fun approve(challenge: UnknownConnectionChallenge) = allocator.approve(challenge)
        override fun discard(challenge: UnknownConnectionChallenge) = allocator.discardChallenge(challenge)
        override fun markActive(token: ReservationToken) = allocator.markActive(token)
        override fun beginRelease(token: ReservationToken) = allocator.beginRelease(token)
        override fun release(token: ReservationToken) = allocator.release(token)
        override fun fail(token: ReservationToken, knownConnectionLimit: Boolean) = allocator.fail(token, knownConnectionLimit)
        override fun registerOwner(token: ReservationToken, reclamationPriority: () -> Int, close: suspend () -> Unit) {}
    }
    private class LivePlayer(private val listener: MultiViewPlayer.Listener) : MultiViewPlayer {
        var starts = 0
        var releases = 0
        var currentVolume = 0f
        override fun start(channel: ResolvedMultiViewChannel) { starts++; listener.ready() }
        override fun setVolume(volume: Float) { currentVolume = volume }
        override fun release() { releases++ }
    }
    private fun row(id: Long): ChannelsViewModel.Row {
        val channel = Channel(id = id, sourceId = 1, streamId = "$id", name = "Guide channel $id",
            categoryId = null, logoUrl = null, epgChannelId = "epg-$id", number = id.toInt(), streamUrl = "https://example.invalid/$id")
        return ChannelsViewModel.Row(channel, listOf(channel), null, null, emptyList())
    }

    @Test fun guideDpadAndFocusActionPreservePlayersConnectionsAndPreviouslySelectedAudio() {
        AppSettings.get(RuntimeEnvironment.getApplication()).setMultiViewPreferences(MultiViewPreferences())
        val scheduler = TestCoroutineScheduler()
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(scheduler))
        val connections = Connections()
        val players = mutableListOf<LivePlayer>()
        val manager = MultiViewSessionManager(scope,
            resolve = { id -> ResolvedMultiViewChannel(id, 1, "Playing $id", "Synthetic provider", "https://example.invalid/$id") },
            connections = connections,
            players = MultiViewPlayerFactory { listener -> LivePlayer(listener).also { players += it } },
            hardware = object : MultiViewHardware { override val capability = MultiViewHardwareCapability(4, "Synthetic capacity") })
        try {
            manager.add(1); manager.add(2)
            scheduler.runCurrent()
            assertEquals(2, players.size)
            val selectedAudio = manager.state.value.panels[1].id
            manager.selectAudio(selectedAudio)
            manager.setGuideOpen(true)
            var focusedChannel = 0L
            var channelMenus = 0
            var focusActions = 0
            compose.setContent { MaterialTheme {
                Column(Modifier.fillMaxSize()) {
                    MultiViewCompactPreview(manager, onFocusMultiView = { focusActions++ })
                    GuideGrid(listOf(row(10), row(20)), 1_800_000_000_000L, null,
                        onSelectRow = { channelMenus++ }, onFocusRow = { focusedChannel = it.primary.id },
                        modifier = Modifier.weight(1f), nowMillis = 1_800_000_000_000L)
                }
            } }
            compose.onNodeWithTag("multiview-compact").assertIsDisplayed()
            val firstChannel = compose.onNodeWithTag("guide-channel-10")
            firstChannel.performSemanticsAction(SemanticsActions.RequestFocus)
            firstChannel.performKeyInput { pressKey(Key.DirectionDown) }
            val secondChannel = compose.onNodeWithTag("guide-channel-20")
            secondChannel.assertIsFocused()
            secondChannel.performKeyInput { pressKey(Key.DirectionCenter) }
            scheduler.advanceTimeBy(1_000); scheduler.runCurrent()
            compose.runOnIdle {
                assertEquals(20L, focusedChannel)
                assertEquals(1, channelMenus)
                assertEquals(selectedAudio, manager.state.value.audioPanelId)
                assertEquals(listOf(0f, 1f), players.map { it.currentVolume })
                assertEquals(listOf(1, 1), players.map { it.starts })
                assertEquals(listOf(0, 0), players.map { it.releases })
                assertEquals(2, connections.reservations)
                assertEquals(2, connections.snapshot(1)?.locallyReserved)
            }
            compose.onNodeWithTag("focus-multiview").performClick()
            compose.runOnIdle { assertEquals(1, focusActions); assertEquals(selectedAudio, manager.state.value.audioPanelId) }
        } finally {
            manager.stop()
            scope.cancel()
        }
    }
}
