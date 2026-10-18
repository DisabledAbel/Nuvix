/* Nuvix Copyright (C) 2026 Nuvix Contributors. GPL-3.0-or-later. */
package app.opentv.multiview

import app.opentv.connections.AccountConnectionPolicy
import app.opentv.connections.AccountConnectionSnapshot
import app.opentv.connections.ConnectionAllocator
import app.opentv.connections.ConnectionPurpose
import app.opentv.connections.ConnectionRequest
import app.opentv.connections.ReservationResult
import app.opentv.connections.ReservationState
import app.opentv.connections.ReservationToken
import app.opentv.connections.UnknownConnectionApproval
import app.opentv.connections.UnknownConnectionChallenge
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test

/** Policy tests use no Android player, network, wall clock, or hardware decoder. */
@OptIn(ExperimentalCoroutinesApi::class)
class MultiViewSessionManagerTest {
    private class FakeConnections : MultiViewConnections {
        private var nextId = 0
        val allocator = ConnectionAllocator { "reservation-${++nextId}" }
        val owners = mutableMapOf<ReservationToken, suspend () -> Unit>()
        val priorities = mutableMapOf<ReservationToken, () -> Int>()
        val countsAtReserve = mutableListOf<Int>()
        override fun snapshot(sourceId: Long): AccountConnectionSnapshot? = allocator.snapshotForSource(sourceId)
        override fun reserve(request: ConnectionRequest, approval: UnknownConnectionApproval?): ReservationResult =
            allocator.reserve(request, approval).also {
                countsAtReserve += snapshot(request.sourceId)?.locallyReserved ?: 0
            }
        override fun approve(challenge: UnknownConnectionChallenge) = allocator.approve(challenge)
        override fun discard(challenge: UnknownConnectionChallenge) = allocator.discardChallenge(challenge)
        override fun markActive(token: ReservationToken) = allocator.markActive(token)
        override fun beginRelease(token: ReservationToken) = allocator.beginRelease(token)
        override fun release(token: ReservationToken): Boolean {
            owners.remove(token)
            priorities.remove(token)
            return allocator.release(token)
        }
        override fun fail(token: ReservationToken, knownConnectionLimit: Boolean): Boolean {
            owners.remove(token)
            priorities.remove(token)
            return allocator.fail(token, knownConnectionLimit)
        }
        override fun registerOwner(token: ReservationToken, reclamationPriority: () -> Int, close: suspend () -> Unit) {
            owners[token] = close
            priorities[token] = reclamationPriority
        }
        fun register(sourceId: Long, maximum: Int? = 4, account: String = "account-$sourceId") {
            allocator.registerSource(sourceId, account, AccountConnectionPolicy(maximum, false))
        }
    }

    private class FakePlayer(
        val listener: MultiViewPlayer.Listener,
        private val onRelease: () -> Unit,
        private val onVolumeChange: () -> Unit = {},
    ) : MultiViewPlayer {
        val starts = mutableListOf<ResolvedMultiViewChannel>()
        val volumes = mutableListOf<Float>()
        var releases = 0
            private set
        val currentVolume: Float get() = volumes.lastOrNull() ?: 0f
        override fun start(channel: ResolvedMultiViewChannel) { starts += channel }
        override fun setVolume(volume: Float) { volumes += volume; onVolumeChange() }
        override fun release() { releases++; onRelease() }
        fun ready() = listener.ready()
        fun fail(kind: MultiViewFailureKind = MultiViewFailureKind.NETWORK) =
            listener.failed(MultiViewFailure(kind, "Test playback failure"))
    }

    private class FakeHardware(maximum: Int = 4) : MultiViewHardware {
        var maximumPlayers = maximum
        val decoderFailures = mutableListOf<Int>()
        override val capability get() = MultiViewHardwareCapability(maximumPlayers, "Test decoder capacity")
        override fun decoderFailed(workingPlayers: Int) {
            decoderFailures += workingPlayers
            maximumPlayers = workingPlayers.coerceAtLeast(1)
        }
    }

    private class FakeAudioFocus : MultiViewAudioFocus {
        var requests = 0
        var abandons = 0
        var granted = true
        var callback: ((Boolean) -> Unit)? = null
        override fun request(onChange: (Boolean) -> Unit): Boolean {
            requests++
            callback = onChange
            return granted
        }
        override fun abandon() { abandons++; callback = null }
    }

    private class Fixture(
        val scope: TestScope,
        maximum: Int? = 4,
        hardwareMaximum: Int = 4,
        val sourceForChannel: (Long) -> Long = { 1 },
        resolver: (suspend (Long) -> ResolvedMultiViewChannel)? = null,
    ) {
        val connections = FakeConnections().apply { register(1, maximum) }
        val hardware = FakeHardware(hardwareMaximum)
        val audio = FakeAudioFocus()
        val players = mutableListOf<FakePlayer>()
        val resolvedIds = mutableListOf<Long>()
        val reservationsAtPlayerRelease = mutableListOf<Int>()
        var beforeStarts = 0
        var failNextAllocation = false
        var maximumAllocatedPlayers = 0
        var maximumAudiblePlayers = 0
        private var nextPanelId = 0
        val manager = MultiViewSessionManager(
            scope = scope.backgroundScope,
            resolve = { id ->
                resolvedIds += id
                resolver?.invoke(id) ?: resolved(id, sourceForChannel(id))
            },
            connections = connections,
            players = MultiViewPlayerFactory { listener ->
                if (failNextAllocation) {
                    failNextAllocation = false
                    error("Test allocation failed")
                }
                FakePlayer(listener, onRelease = {
                    reservationsAtPlayerRelease += connections.allocator.snapshots.value.sumOf { it.locallyReserved }
                }, onVolumeChange = {
                    maximumAudiblePlayers = maxOf(maximumAudiblePlayers, players.count { it.currentVolume > 0f })
                }).also {
                    players += it
                    maximumAllocatedPlayers = maxOf(maximumAllocatedPlayers, players.count { player -> player.releases == 0 })
                }
            },
            hardware = hardware,
            audioFocus = audio,
            beforeStart = { beforeStarts++ },
            newId = { "panel-${++nextPanelId}" },
            startupTimeoutMs = 1_000,
            nowMs = { scope.testScheduler.currentTime },
        )
        val state get() = manager.state.value
        fun panel(channelId: Long) = state.panels.single { it.channelId == channelId }
        fun player(channelId: Long) = players.last { it.starts.singleOrNull()?.channelId == channelId }
        fun addReady(channelId: Long): String {
            manager.add(channelId)
            scope.runCurrent()
            player(channelId).ready()
            scope.runCurrent()
            return panel(channelId).id
        }
        companion object {
            fun resolved(id: Long, sourceId: Long = 1) = ResolvedMultiViewChannel(
                id, sourceId, "Channel $id", "Provider $sourceId", "https://example.invalid/live/$id",
            )
        }
    }

    private fun TestScope.assertStartup(count: Int) {
        val f = Fixture(this)
        repeat(count) { f.manager.add((it + 1).toLong()) }
        runCurrent()
        assertEquals("Only one unproven decoder starts at a time", 1, f.players.size)
        repeat(count) { index ->
            assertEquals(index + 1, f.players.size)
            f.players[index].ready()
            runCurrent()
        }
        assertEquals(count, f.players.size)
        assertEquals(count, f.connections.snapshot(1)!!.locallyReserved)
        assertTrue(f.state.panels.all { it.status == MultiViewPanelStatus.PLAYING })
        assertEquals(1, f.beforeStarts)
        assertEquals(1, f.players.count { it.currentVolume > 0f })
        assertEquals(1, f.audio.requests)
        assertEquals(1, f.maximumAudiblePlayers)
        f.manager.release()
        runCurrent()
        assertTrue(f.players.all { it.releases == 1 })
        assertEquals(0, f.connections.snapshot(1)!!.locallyReserved)
    }

    @Test fun twoChannelsStartIncrementallyWithIndependentReservations() = runTest { assertStartup(2) }
    @Test fun threeChannelsStartIncrementallyWithIndependentReservations() = runTest { assertStartup(3) }
    @Test fun fourChannelsStartIncrementallyWithIndependentReservations() = runTest { assertStartup(4) }

    @Test fun fifthChannelNeverAllocatesAPlayerOrReservation() = runTest {
        val f = Fixture(this)
        repeat(4) { f.addReady((it + 1).toLong()) }
        f.manager.add(5)
        runCurrent()
        assertEquals(4, f.state.panels.size)
        assertEquals(4, f.players.size)
        assertEquals(4, f.maximumAllocatedPlayers)
        assertEquals(4, f.connections.snapshot(1)!!.locallyReserved)
        assertFalse(f.resolvedIds.contains(5L))
    }

    @Test fun hardwareEstimateRejectsOnlyExtraPanelAndKeepsWorkingPlayers() = runTest {
        val f = Fixture(this, hardwareMaximum = 2)
        f.addReady(1); f.addReady(2)
        f.manager.add(3)
        runCurrent()
        assertEquals(MultiViewPanelStatus.FAILED, f.panel(3).status)
        assertEquals(MultiViewFailureKind.DECODER, f.panel(3).failureKind)
        assertEquals(2, f.players.size)
        assertTrue(f.players.all { it.releases == 0 })
        assertEquals(2, f.connections.snapshot(1)!!.locallyReserved)
        assertEquals(0, f.connections.snapshot(1)!!.knownConnectionLimitErrors)
    }

    @Test fun decoderCallbackReleasesFailedPlayerAndLeavesHealthyPanelsRunning() = runTest {
        val f = Fixture(this)
        f.addReady(1); f.addReady(2)
        f.manager.add(3)
        runCurrent()
        f.player(3).fail(MultiViewFailureKind.DECODER)
        runCurrent()
        assertEquals(MultiViewPanelStatus.FAILED, f.panel(3).status)
        assertEquals(1, f.player(3).releases)
        assertEquals(listOf(2), f.hardware.decoderFailures)
        assertEquals(0, f.player(1).releases)
        assertEquals(0, f.player(2).releases)
        assertEquals(2, f.connections.snapshot(1)!!.locallyReserved)
        assertEquals(0, f.connections.snapshot(1)!!.knownConnectionLimitErrors)
    }

    @Test fun factoryFailureReleasesAdmittedReservationWithoutTouchingOtherPanels() = runTest {
        val f = Fixture(this)
        f.addReady(1); f.addReady(2)
        f.failNextAllocation = true
        f.manager.add(3)
        runCurrent()
        assertEquals(MultiViewPanelStatus.FAILED, f.panel(3).status)
        assertEquals(2, f.connections.snapshot(1)!!.locallyReserved)
        assertTrue(f.players.all { it.releases == 0 })
    }

    @Test fun focusAudioWaits150MillisecondsAndCancelsTheOlderSelection() = runTest {
        val f = Fixture(this)
        val first = f.addReady(1)
        val second = f.addReady(2)
        val third = f.addReady(3)
        f.manager.focus(second)
        runCurrent()
        advanceTimeBy(100)
        f.manager.focus(third)
        runCurrent()
        advanceTimeBy(149)
        runCurrent()
        assertEquals(first, f.state.audioPanelId)
        assertEquals(1f, f.player(1).currentVolume)
        advanceTimeBy(1)
        runCurrent()
        assertEquals(third, f.state.audioPanelId)
        assertEquals(listOf(0f, 0f, 1f), f.players.map { it.currentVolume })
        assertTrue(f.players.all { it.starts.size == 1 })
        assertEquals(1, f.audio.requests)
    }

    @Test fun pressOkChangesVisualFocusUntilExplicitAudioSelection() = runTest {
        val f = Fixture(this)
        val first = f.addReady(1)
        val second = f.addReady(2)
        f.manager.setAudioMode(MultiViewAudioMode.PRESS_OK)
        f.manager.focus(second)
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(second, f.state.focusedPanelId)
        assertEquals(first, f.state.audioPanelId)
        f.manager.selectAudio(second)
        assertEquals(listOf(0f, 1f), f.players.map { it.currentVolume })
        assertEquals(2, f.connections.snapshot(1)!!.locallyReserved)
    }

    @Test fun openingGuideCancelsPendingAudioChangeAndGuideFocusNeverRetunes() = runTest {
        val f = Fixture(this)
        val first = f.addReady(1)
        val second = f.addReady(2)
        f.manager.focus(second)
        runCurrent()
        advanceTimeBy(100)
        f.manager.setGuideOpen(true)
        f.manager.focus(second)
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(first, f.state.audioPanelId)
        assertEquals(2, f.players.size)
        assertEquals(listOf(1L, 2L), f.resolvedIds)
        assertTrue(f.players.all { it.starts.size == 1 && it.releases == 0 })
    }

    @Test fun muteAndAudioFocusLossNeverUnmuteOtherPlayers() = runTest {
        val f = Fixture(this)
        f.addReady(1)
        val second = f.addReady(2)
        f.manager.setMuted(true)
        f.manager.selectAudio(second)
        assertTrue(f.players.all { it.currentVolume == 0f })
        f.manager.setMuted(false)
        assertEquals(listOf(0f, 1f), f.players.map { it.currentVolume })
        f.audio.callback!!.invoke(false)
        assertTrue(f.players.all { it.currentVolume == 0f })
        assertFalse(f.state.audioFocusGranted)
        f.manager.setLayout(MultiViewLayout.FOCUSED)
        assertTrue(f.players.all { it.currentVolume == 0f })
        f.audio.callback!!.invoke(true)
        assertEquals(listOf(0f, 1f), f.players.map { it.currentVolume })
    }

    @Test fun linkedSourcesShareOneLimitWhileUnrelatedAccountStillStarts() = runTest {
        val f = Fixture(this, maximum = 1, sourceForChannel = { it })
        f.connections.register(2, 1, "account-1")
        f.connections.register(3, 1)
        f.addReady(1)
        f.manager.add(2)
        runCurrent()
        assertEquals(MultiViewPanelStatus.FAILED, f.panel(2).status)
        f.addReady(3)
        assertEquals(2, f.players.size)
        assertEquals(1, f.connections.snapshot(1)!!.locallyReserved)
        assertEquals(1, f.connections.snapshot(3)!!.locallyReserved)
        assertEquals(0, f.player(1).releases)
    }

    @Test fun unknownAllowanceRequiresFreshApprovalForEveryAdditionalAttempt() = runTest {
        val f = Fixture(this, maximum = null)
        f.addReady(1)
        f.manager.add(2)
        runCurrent()
        assertNotNull(f.state.approval)
        assertEquals(1, f.players.size)
        val challenge = f.state.approval!!.challenge
        f.manager.answerApproval(true)
        runCurrent()
        assertEquals(2, f.players.size)
        f.player(2).ready()
        runCurrent()
        assertNull(f.connections.allocator.approve(challenge))
        f.manager.add(3)
        runCurrent()
        assertNotNull(f.state.approval)
        assertEquals(2, f.players.size)
        f.manager.answerApproval(false)
        runCurrent()
        assertEquals(MultiViewPanelStatus.FAILED, f.panel(3).status)
        assertEquals(2, f.connections.snapshot(1)!!.locallyReserved)
        assertNull(f.connections.snapshot(1)!!.effectiveMaximum)
    }

    @Test fun removingAnUnknownApprovalPanelInvalidatesItsChallenge() = runTest {
        val f = Fixture(this, maximum = null)
        f.addReady(1)
        f.manager.add(2)
        runCurrent()
        val challenge = f.state.approval!!.challenge
        f.manager.remove(f.panel(2).id)
        runCurrent()
        f.manager.answerApproval(true)
        runCurrent()
        assertNull(f.state.approval)
        assertNull(f.connections.allocator.approve(challenge))
        assertEquals(1, f.players.size)
        assertEquals(1, f.connections.snapshot(1)!!.locallyReserved)
    }

    @Test fun safeReplacementClosesRealPlayerBeforeReusingLimitedCapacity() = runTest {
        val f = Fixture(this, maximum = 1)
        val panel = f.addReady(1)
        f.manager.replace(panel, 2)
        runCurrent()
        assertEquals(1, f.player(1).releases)
        assertEquals(listOf(1), f.reservationsAtPlayerRelease)
        assertEquals(1, f.maximumAllocatedPlayers)
        assertTrue(f.connections.countsAtReserve.all { it <= 1 })
        f.player(2).ready()
        runCurrent()
        assertEquals(panel, f.panel(2).id)
        assertEquals(1, f.connections.snapshot(1)!!.locallyReserved)
    }

    @Test fun seamlessReplacementRetainsOldPlayerUntilReplacementBecomesReady() = runTest {
        val f = Fixture(this, maximum = 2, hardwareMaximum = 2)
        val panel = f.addReady(1)
        f.manager.replace(panel, 2)
        runCurrent()
        assertEquals(0, f.player(1).releases)
        assertEquals(2, f.connections.snapshot(1)!!.locallyReserved)
        assertEquals(1f, f.player(1).currentVolume)
        assertEquals(0f, f.player(2).currentVolume)
        f.player(2).ready()
        runCurrent()
        assertEquals(1, f.player(1).releases)
        assertEquals(1f, f.player(2).currentVolume)
        assertEquals(1, f.connections.snapshot(1)!!.locallyReserved)
    }

    @Test fun failedSeamlessCandidateRetainsOldChannelAndItsReservation() = runTest {
        val f = Fixture(this, maximum = 2)
        val panel = f.addReady(1)
        f.manager.replace(panel, 2)
        runCurrent()
        f.player(2).fail(MultiViewFailureKind.DECODER)
        runCurrent()
        assertEquals(0, f.player(1).releases)
        assertEquals(1, f.player(2).releases)
        assertEquals(1L, f.state.panels.single().channelId)
        assertEquals("Channel 1", f.state.panels.single().channelName)
        assertEquals(MultiViewPanelStatus.PLAYING, f.state.panels.single().status)
        assertEquals(1, f.connections.snapshot(1)!!.locallyReserved)
        assertEquals(1f, f.player(1).currentVolume)
    }

    @Test fun fullUnrelatedAccountDenialPreservesTheSelectedOldChannel() = runTest {
        val f = Fixture(this, maximum = 1, sourceForChannel = { it })
        f.connections.register(2, 1)
        val recording = f.connections.allocator.reserve(ConnectionRequest("recording", 1, 2, ConnectionPurpose.RECORDING))
            as ReservationResult.Reserved
        val panel = f.addReady(1)
        f.manager.replace(panel, 2)
        runCurrent()
        assertEquals(0, f.player(1).releases)
        assertEquals(1L, f.state.panels.single().channelId)
        assertEquals(1, f.players.size)
        assertEquals(ReservationState.PENDING, f.connections.allocator.reservationState(recording.token))
    }

    @Test fun lateCallbacksFromAReplacedPlayerCannotAffectTheNewReservation() = runTest {
        val f = Fixture(this, maximum = 1)
        val panel = f.addReady(1)
        val obsolete = f.player(1)
        f.manager.replace(panel, 2)
        runCurrent()
        f.player(2).ready()
        runCurrent()
        obsolete.fail(MultiViewFailureKind.PROVIDER_LIMIT)
        obsolete.listener.buffering()
        obsolete.ready()
        runCurrent()
        assertEquals(MultiViewPanelStatus.PLAYING, f.panel(2).status)
        assertEquals(0, f.player(2).releases)
        assertEquals(1, f.connections.snapshot(1)!!.locallyReserved)
        assertEquals(0, f.connections.snapshot(1)!!.knownConnectionLimitErrors)
    }

    @Test fun obsoleteNonCancellableResolutionCannotAllocateAfterNewSelection() = runTest {
        val gate = CompletableDeferred<Unit>()
        val f = Fixture(this, resolver = { id ->
            if (id == 1L) withContext(NonCancellable) { gate.await() }
            Fixture.resolved(id)
        })
        f.manager.add(1)
        runCurrent()
        val panel = f.state.panels.single().id
        f.manager.replace(panel, 2)
        runCurrent()
        gate.complete(Unit)
        runCurrent()
        assertEquals(listOf(2L), f.players.flatMap { it.starts }.map { it.channelId })
        f.player(2).ready()
        runCurrent()
        assertEquals(2L, f.state.panels.single().channelId)
        assertEquals(1, f.connections.snapshot(1)!!.locallyReserved)
    }

    @Test fun duplicateChoicesNeverOpenAnotherConnection() = runTest {
        val f = Fixture(this)
        val first = f.addReady(1)
        val second = f.addReady(2)
        f.manager.focus(second)
        f.manager.add(1)
        assertNotNull(f.state.duplicate)
        f.manager.resolveDuplicate(MultiViewDuplicateChoice.CANCEL)
        assertEquals(2, f.state.panels.size)
        f.manager.add(1)
        f.manager.resolveDuplicate(MultiViewDuplicateChoice.FOCUS_EXISTING)
        assertEquals(first, f.state.focusedPanelId)
        f.manager.replace(second, 1)
        f.manager.resolveDuplicate(MultiViewDuplicateChoice.REPLACE_SELECTED)
        runCurrent()
        assertEquals(listOf(first), f.state.panels.map { it.id })
        assertEquals(2, f.players.size)
        assertEquals(0, f.player(1).releases)
        assertEquals(1, f.player(2).releases)
        assertEquals(1, f.connections.snapshot(1)!!.locallyReserved)
    }

    @Test fun duplicateOfPlayingChannelDuringReplacementKeepsCurrentPlayerAndCancelsCandidate() = runTest {
        val gate = CompletableDeferred<Unit>()
        val f = Fixture(this, resolver = { id ->
            if (id == 2L) withContext(NonCancellable) { gate.await() }
            Fixture.resolved(id)
        })
        val first = f.addReady(1)
        f.manager.replace(first, 2)
        runCurrent()
        f.manager.add(1)
        assertEquals(first, f.state.duplicate?.existingPanelId)
        assertEquals(1, f.state.panels.size)
        f.manager.resolveDuplicate(MultiViewDuplicateChoice.FOCUS_EXISTING)
        runCurrent()
        gate.complete(Unit)
        runCurrent()
        assertEquals(first, f.state.focusedPanelId)
        assertEquals(1L, f.state.panels.single().channelId)
        assertEquals(1, f.players.size)
        assertEquals(0, f.player(1).releases)
        assertEquals(1, f.player(1).starts.size)
        assertEquals(1, f.connections.snapshot(1)!!.locallyReserved)
    }

    @Test fun layoutAndReorderKeepPanelIdentityPlayersAndReservations() = runTest {
        val f = Fixture(this)
        val ids = (1L..4L).map(f::addReady)
        MultiViewLayout.entries.forEach(f.manager::setLayout)
        f.manager.move(ids.first(), 3)
        assertEquals(ids.drop(1) + ids.first(), f.state.panels.map { it.id })
        assertEquals(ids.first(), f.state.primaryPanelId)
        assertTrue(f.players.all { it.starts.size == 1 && it.releases == 0 })
        assertEquals(4, f.connections.snapshot(1)!!.locallyReserved)
        assertEquals(4, f.resolvedIds.size)
    }

    @Test fun removalReleasesOnlyRemovedPlayerAndReassignsAudio() = runTest {
        val f = Fixture(this)
        val first = f.addReady(1)
        val second = f.addReady(2)
        f.manager.remove(first)
        f.manager.remove(first)
        runCurrent()
        assertEquals(1, f.player(1).releases)
        assertEquals(0, f.player(2).releases)
        assertEquals(second, f.state.audioPanelId)
        assertEquals(second, f.state.primaryPanelId)
        assertEquals(1f, f.player(2).currentVolume)
        assertEquals(1, f.connections.snapshot(1)!!.locallyReserved)
    }

    @Test fun recordingOwnerClosePausesOnlyMatchingSecondaryAndClosesBeforeRelease() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val f = Fixture(this, sourceForChannel = { if (it == 3L) 2 else 1 })
            f.connections.register(2)
            val first = f.addReady(1)
            val secondary = f.addReady(2)
            val unrelated = f.addReady(3)
            val token = f.connections.owners.keys.single { it.sourceId == 1L && f.connections.priorities.getValue(it)() == 1 }
            f.connections.owners.getValue(token).invoke()
            runCurrent()
            assertEquals(MultiViewPanelStatus.PAUSED, f.state.panels.single { it.id == secondary }.status)
            assertEquals(MultiViewPanelStatus.PLAYING, f.state.panels.single { it.id == first }.status)
            assertEquals(MultiViewPanelStatus.PLAYING, f.state.panels.single { it.id == unrelated }.status)
            assertEquals(1, f.player(2).releases)
            assertEquals(0, f.player(1).releases)
            assertEquals(0, f.player(3).releases)
            assertEquals(3, f.reservationsAtPlayerRelease.single())
            assertEquals(1, f.connections.snapshot(1)!!.locallyReserved)
        } finally { Dispatchers.resetMain() }
    }

    @Test fun fullscreenPreservesSelectedPlayerAndAsksBeforeSecondaryRestore() = runTest {
        val f = Fixture(this)
        f.addReady(1)
        val selected = f.addReady(2)
        f.manager.enterFullscreen(selected)
        assertEquals(0, f.player(2).releases)
        assertEquals(1, f.player(2).starts.size)
        assertEquals(1, f.player(1).releases)
        assertEquals(1, f.connections.snapshot(1)!!.locallyReserved)
        f.manager.leaveFullscreen()
        runCurrent()
        assertTrue(f.state.restorationRequired)
        assertEquals(2, f.players.size)
        f.manager.leaveFullscreen(true)
        runCurrent()
        assertFalse(f.state.restorationRequired)
        assertNull(f.state.fullscreenPanelId)
        assertEquals(3, f.players.size)
        assertEquals(0, f.player(2).releases)
        f.player(1).ready()
        runCurrent()
        assertEquals(2, f.connections.snapshot(1)!!.locallyReserved)
    }

    @Test fun fullscreenRestoreRevalidatesChangedCapacityInsteadOfReopeningSecondary() = runTest {
        val f = Fixture(this)
        f.addReady(1)
        val selected = f.addReady(2)
        f.manager.enterFullscreen(selected)
        f.connections.allocator.configure(1, AccountConnectionPolicy(1, false))
        f.manager.leaveFullscreen(true)
        runCurrent()
        assertEquals(2, f.players.size)
        assertEquals(1, f.connections.snapshot(1)!!.locallyReserved)
        assertEquals(MultiViewPanelStatus.FAILED, f.panel(1).status)
        assertEquals(MultiViewPanelStatus.PLAYING, f.panel(2).status)
    }

    @Test fun fullscreenRestoreNeverReusesPreviousUnknownAllowanceConsent() = runTest {
        val f = Fixture(this, maximum = null)
        val primary = f.addReady(1)
        f.manager.add(2)
        runCurrent()
        f.manager.answerApproval(true)
        runCurrent()
        f.player(2).ready()
        runCurrent()
        f.manager.enterFullscreen(primary)
        f.manager.leaveFullscreen(true)
        runCurrent()
        assertNotNull(f.state.approval)
        assertEquals(2, f.players.size)
        assertEquals(1, f.connections.snapshot(1)!!.locallyReserved)
        f.manager.answerApproval(false)
        runCurrent()
        assertEquals(0, f.player(1).releases)
    }

    @Test fun lateRecordingOwnerCloseCannotPauseAReplacementSession() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val f = Fixture(this, maximum = 1)
            val panel = f.addReady(1)
            val oldOwner = f.connections.owners.values.single()
            f.manager.replace(panel, 2)
            runCurrent()
            f.player(2).ready()
            runCurrent()
            oldOwner.invoke()
            runCurrent()
            assertEquals(MultiViewPanelStatus.PLAYING, f.panel(2).status)
            assertEquals(0, f.player(2).releases)
            assertEquals(1, f.connections.snapshot(1)!!.locallyReserved)
        } finally { Dispatchers.resetMain() }
    }

    @Test fun recordingReclaimingOldPlayerDoesNotCancelAReplacementOnAnotherAccount() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val f = Fixture(this, maximum = 1, sourceForChannel = { if (it == 1L) 1 else 2 })
            f.connections.register(2, 1)
            val panel = f.addReady(1)
            val oldToken = f.connections.owners.keys.single()
            f.manager.replace(panel, 2)
            runCurrent()
            assertEquals(2, f.players.size)
            val candidate = f.player(2)
            f.connections.owners.getValue(oldToken).invoke()
            runCurrent()
            assertEquals(1, f.player(1).releases)
            assertEquals(0, candidate.releases)
            assertEquals(0, f.connections.snapshot(1)!!.locallyReserved)
            assertEquals(1, f.connections.snapshot(2)!!.locallyReserved)
            candidate.ready()
            runCurrent()
            assertEquals(2L, f.state.panels.single().channelId)
            assertEquals(MultiViewPanelStatus.PLAYING, f.state.panels.single().status)
            assertTrue(f.state.panels.single().engineReady)
            assertEquals(0, candidate.releases)
            assertEquals(1f, candidate.currentVolume)
        } finally { Dispatchers.resetMain() }
    }

    @Test fun recordingPreemptingSeamlessCandidateKeepsTheAlreadyPlayingChannel() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val f = Fixture(this, maximum = 2)
            val panel = f.addReady(1)
            val oldToken = f.connections.owners.keys.single()
            f.manager.replace(panel, 2)
            runCurrent()
            val candidateToken = f.connections.owners.keys.single { it != oldToken }
            f.connections.owners.getValue(candidateToken).invoke()
            runCurrent()
            assertEquals(1L, f.state.panels.single().channelId)
            assertEquals(MultiViewPanelStatus.PLAYING, f.state.panels.single().status)
            assertTrue(f.state.panels.single().engineReady)
            assertEquals(0, f.player(1).releases)
            assertEquals(1, f.player(2).releases)
            assertEquals(1, f.connections.snapshot(1)!!.locallyReserved)
            assertEquals(1f, f.player(1).currentVolume)
        } finally { Dispatchers.resetMain() }
    }

    @Test fun stoppingDuringStartupReleasesOnceAndRejectsLateCallbacks() = runTest {
        val f = Fixture(this)
        f.manager.add(1)
        runCurrent()
        val obsolete = f.player(1)
        f.manager.stop()
        f.manager.release()
        runCurrent()
        obsolete.ready()
        obsolete.fail(MultiViewFailureKind.PROVIDER_LIMIT)
        runCurrent()
        assertTrue(f.state.panels.isEmpty())
        assertEquals(1, obsolete.releases)
        assertEquals(0, f.connections.snapshot(1)!!.locallyReserved)
        assertEquals(0, f.connections.snapshot(1)!!.knownConnectionLimitErrors)
        assertTrue(f.connections.owners.isEmpty())
    }

    @Test fun backgroundReturnDoesNotReconnectUntilExplicitResumeAndStartsPrimaryFirst() = runTest {
        val f = Fixture(this)
        f.addReady(1)
        val primary = f.addReady(2)
        f.manager.setPrimary(primary)
        f.manager.setForeground(false)
        runCurrent()
        assertEquals(0, f.connections.snapshot(1)!!.locallyReserved)
        assertTrue(f.players.all { it.releases == 1 && it.currentVolume == 0f })
        f.manager.setForeground(true)
        runCurrent()
        assertEquals(2, f.players.size)
        f.manager.resume()
        runCurrent()
        assertEquals(2L, f.players.last().starts.single().channelId)
        f.player(2).ready()
        runCurrent()
        assertEquals(1L, f.players.last().starts.single().channelId)
        f.player(1).ready()
        runCurrent()
        assertEquals(2, f.connections.snapshot(1)!!.locallyReserved)
    }

    @Test fun startupTimeoutReleasesOnlyTimedOutAttemptAndRetryIsBounded() = runTest {
        val f = Fixture(this)
        f.addReady(1)
        f.manager.add(2)
        runCurrent()
        val panel = f.panel(2).id
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(MultiViewPanelStatus.FAILED, f.panel(2).status)
        assertEquals(1, f.player(2).releases)
        assertEquals(0, f.player(1).releases)
        repeat(3) {
            f.manager.retry(panel)
            runCurrent()
            f.player(2).fail()
            runCurrent()
        }
        val allocations = f.players.size
        f.manager.retry(panel)
        runCurrent()
        assertEquals(allocations, f.players.size)
        assertEquals(1, f.connections.snapshot(1)!!.locallyReserved)
    }

    @Test fun onlyExplicitProviderLimitFailuresIncrementAccountLimitDiagnostics() = runTest {
        val f = Fixture(this)
        f.addReady(1)
        val panel = f.addReady(2)
        f.player(2).fail(MultiViewFailureKind.NETWORK)
        runCurrent()
        assertEquals(0, f.connections.snapshot(1)!!.knownConnectionLimitErrors)
        f.manager.retry(panel)
        runCurrent()
        f.player(2).fail(MultiViewFailureKind.PROVIDER_LIMIT)
        f.player(2).fail(MultiViewFailureKind.PROVIDER_LIMIT)
        runCurrent()
        assertEquals(1, f.connections.snapshot(1)!!.knownConnectionLimitErrors)
        assertEquals(0, f.player(1).releases)
    }

    @Test fun repeatedBufferingRequiresThreeCompletedMeaningfulStalls() = runTest {
        val f = Fixture(this)
        f.manager.add(1)
        runCurrent()
        repeat(5) { f.player(1).listener.buffering() }
        f.player(1).ready()
        runCurrent()
        assertFalse(f.panel(1).repeatedBuffering)
        repeat(3) {
            f.player(1).listener.buffering()
            advanceTimeBy(1_999)
            f.player(1).ready()
        }
        assertFalse("Short transitions do not justify an alternative-provider suggestion", f.panel(1).repeatedBuffering)
        repeat(2) {
            repeat(4) { f.player(1).listener.buffering() }
            advanceTimeBy(2_000)
            f.player(1).ready()
        }
        assertFalse("Repeated callbacks during one stall count once", f.panel(1).repeatedBuffering)
        f.player(1).listener.buffering()
        advanceTimeBy(2_000)
        assertFalse("An ongoing stall has not completed yet", f.panel(1).repeatedBuffering)
        f.player(1).ready()
        assertTrue(f.panel(1).repeatedBuffering)
        assertEquals(1, f.players.size)
        assertEquals(1, f.connections.snapshot(1)!!.locallyReserved)
    }

    @Test fun dismissedBufferingSuggestionHonorsCooldownAndCanBeDisabled() = runTest {
        val f = Fixture(this)
        val panel = f.addReady(1)
        repeat(3) {
            f.player(1).listener.buffering()
            advanceTimeBy(2_000)
            f.player(1).ready()
        }
        assertTrue(f.panel(1).repeatedBuffering)
        f.manager.dismissBufferingSuggestion(panel)
        f.player(1).listener.buffering()
        advanceTimeBy(2_000)
        f.player(1).ready()
        assertFalse(f.panel(1).repeatedBuffering)
        advanceTimeBy(60_000)
        f.player(1).listener.buffering()
        advanceTimeBy(2_000)
        f.player(1).ready()
        assertTrue(f.panel(1).repeatedBuffering)
        f.manager.dismissBufferingSuggestion(panel)
        f.manager.updatePreferences(MultiViewPreferences(bufferingSuggestions = false))
        advanceTimeBy(60_000)
        repeat(3) {
            f.player(1).listener.buffering()
            advanceTimeBy(2_000)
            f.player(1).ready()
        }
        assertFalse(f.panel(1).repeatedBuffering)
    }

    @Test fun oldStallsExpireOutsideTwoMinuteObservationWindow() = runTest {
        val f = Fixture(this)
        f.addReady(1)
        repeat(2) {
            f.player(1).listener.buffering()
            advanceTimeBy(2_000)
            f.player(1).ready()
        }
        advanceTimeBy(120_001)
        f.player(1).listener.buffering()
        advanceTimeBy(2_000)
        f.player(1).ready()
        assertFalse(f.panel(1).repeatedBuffering)
    }

    @Test fun explicitSelectionRequestsAudioAgainAfterFocusLoss() = runTest {
        val f = Fixture(this)
        f.addReady(1)
        val second = f.addReady(2)
        assertEquals(1, f.audio.requests)
        f.audio.callback!!.invoke(false)
        assertTrue(f.players.all { it.currentVolume == 0f })
        f.manager.selectAudio(second)
        assertEquals(2, f.audio.requests)
        assertEquals(listOf(0f, 1f), f.players.map { it.currentVolume })
        assertTrue(f.state.audioFocusGranted)
    }

    @Test fun restoringSavedPanelIdsInTheSameManagerUsesFreshReservationGenerationsAndPrimaryFirst() = runTest {
        val f = Fixture(this)
        val first = f.addReady(1)
        val primary = f.addReady(2)
        f.manager.setPrimary(primary)
        f.manager.selectAudio(first)
        val saved = f.manager.capturePresentation()!!
        val oldRequests = f.connections.owners.keys.associateBy { it.sessionId }
        f.manager.restorePresentation(saved)
        runCurrent()
        assertEquals(2L, f.players.last().starts.single().channelId)
        f.player(2).ready()
        runCurrent()
        assertEquals(1L, f.players.last().starts.single().channelId)
        f.player(1).ready()
        runCurrent()
        assertEquals(listOf(first, primary), f.state.panels.map { it.id })
        assertEquals(primary, f.state.primaryPanelId)
        assertEquals(first, f.state.audioPanelId)
        assertTrue(f.state.panels.all { it.status == MultiViewPanelStatus.PLAYING })
        assertEquals(2, f.connections.snapshot(1)!!.locallyReserved)
        for (token in f.connections.owners.keys) assertTrue(token.generation > oldRequests.getValue(token.sessionId).generation)
    }

    @Test fun restoredMissingChannelFailsIndependentlyWhileAvailableSecondaryStillStarts() = runTest {
        val f = Fixture(this, resolver = { id ->
            if (id == 99L) error("missing private URL")
            Fixture.resolved(id)
        })
        val saved = MultiViewPresentation(listOf(
            MultiViewSavedPanel("missing", MultiViewChannelReference(99, 1)),
            MultiViewSavedPanel("available", MultiViewChannelReference(1, 1)),
        ), "missing", "available", MultiViewLayout.TWO)
        f.manager.restorePresentation(saved)
        runCurrent()
        f.player(1).ready()
        runCurrent()
        assertEquals(MultiViewPanelStatus.FAILED, f.panel(99).status)
        assertEquals(MultiViewPanelStatus.PLAYING, f.panel(1).status)
        assertEquals(1, f.connections.snapshot(1)!!.locallyReserved)
        assertFalse(f.state.toString().contains("private URL"))
    }

    @Test fun savedProviderAccountChangesAreRejectedBeforeOpeningAPlayer() = runTest {
        val f = Fixture(this)
        f.manager.restorePresentation(MultiViewPresentation(listOf(
            MultiViewSavedPanel("primary", MultiViewChannelReference(1, 77)),
        ), "primary", "primary", MultiViewLayout.TWO))
        runCurrent()
        assertTrue(f.players.isEmpty())
        assertEquals(0, f.connections.snapshot(1)!!.locallyReserved)
        assertEquals(MultiViewPanelStatus.FAILED, f.panel(1).status)
    }

    @Test fun guideFullscreenSelectionReplacesSelectedPanelAndRestoresTheOtherPanelAfterChecks() = runTest {
        val f = Fixture(this)
        val selected = f.addReady(1)
        val secondary = f.addReady(2)
        f.manager.watchFullscreen(3)
        runCurrent()
        f.player(3).ready()
        runCurrent()
        assertEquals(selected, f.state.fullscreenPanelId)
        assertEquals(3L, f.state.panels.single { it.id == selected }.channelId)
        assertEquals(1, f.player(1).releases)
        assertEquals(1, f.player(2).releases)
        assertEquals(0, f.player(3).releases)
        assertEquals(1, f.connections.snapshot(1)!!.locallyReserved)
        f.manager.leaveFullscreen(true)
        runCurrent()
        f.player(2).ready()
        runCurrent()
        assertEquals(listOf(selected, secondary), f.state.panels.map { it.id })
        assertEquals(listOf(3L, 2L), f.state.panels.map { it.channelId })
        assertEquals(1, f.player(3).starts.size)
        assertEquals(2, f.connections.snapshot(1)!!.locallyReserved)
    }

    @Test fun pictureInPictureRetainsOnlySelectedPlayerWhenActivityStops() = runTest {
        val f = Fixture(this)
        f.addReady(1)
        val second = f.addReady(2)
        f.manager.selectAudio(second)
        f.manager.setInPip(true)
        f.manager.setForeground(false)
        runCurrent()
        assertEquals(second, f.state.fullscreenPanelId)
        assertEquals(1, f.player(1).releases)
        assertEquals(0, f.player(2).releases)
        assertEquals(1, f.player(2).starts.size)
        assertEquals(1f, f.player(2).currentVolume)
        assertEquals(1, f.connections.snapshot(1)!!.locallyReserved)
    }

    @Test fun closingPictureInPictureWhileBackgroundedReleasesTheRemainingStream() = runTest {
        val f = Fixture(this)
        f.addReady(1)
        f.addReady(2)
        f.manager.setInPip(true)
        f.manager.setForeground(false)
        f.manager.setInPip(false)
        runCurrent()
        assertEquals(0, f.connections.snapshot(1)!!.locallyReserved)
        assertTrue(f.players.all { it.releases == 1 && it.currentVolume == 0f })
        f.manager.setForeground(true)
        runCurrent()
        assertEquals(2, f.players.size)
        assertTrue(f.state.panels.all { it.status == MultiViewPanelStatus.PAUSED })
    }
}
