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

    private open class FakePlayer(
        val listener: MultiViewPlayer.Listener,
        private val onRelease: () -> Unit,
        private val onVolumeChange: () -> Unit = {},
    ) : MultiViewPlayer {
        var recoveryReconnect: (() -> Unit)? = null
        var recoveryAllowed: (() -> Boolean)? = null
        var recoveryBudget: app.opentv.player.RecoveryBudget? = null
        override fun configureRecovery(scope: kotlinx.coroutines.CoroutineScope, budget: app.opentv.player.RecoveryBudget,
            allowed: () -> Boolean, reconnect: () -> Unit) {
            recoveryBudget = budget; recoveryAllowed = allowed; recoveryReconnect = reconnect
        }
        val starts = mutableListOf<ResolvedMultiViewChannel>()
        val volumes = mutableListOf<Float>()
        var failRelease = false
        var releases = 0
            private set
        val currentVolume: Float get() = volumes.lastOrNull() ?: 0f
        override fun start(channel: ResolvedMultiViewChannel) { starts += channel }
        override fun setVolume(volume: Float) { volumes += volume; onVolumeChange() }
        override fun release() { if (failRelease) error("Synthetic device cleanup failure"); releases++; onRelease() }
        fun ready() = listener.ready()
        fun fail(kind: MultiViewFailureKind = MultiViewFailureKind.NETWORK) =
            listener.failed(MultiViewFailure(kind, "Test playback failure"))
    }

    /** Only managed-path tests use this fake; legacy startup timeout coverage stays unchanged. */
    private class ManagedPlayer(listener: MultiViewPlayer.Listener, onRelease: () -> Unit,
        onVolumeChange: () -> Unit, private val clock: () -> Long, private val initialDelayMs: Long = 0
    ) : FakePlayer(listener, onRelease, onVolumeChange) {
        override val managesRecovery = true
        var preparedAt: Long? = null
        private var preparation: DeferredPanelPreparation? = null
        override fun configureRecovery(scope: kotlinx.coroutines.CoroutineScope, budget: app.opentv.player.RecoveryBudget,
            allowed: () -> Boolean, reconnect: () -> Unit) {
            super.configureRecovery(scope, budget, allowed, reconnect)
            preparation = DeferredPanelPreparation(scope, budget, clock, allowed) { preparedAt = clock() }
        }
        override fun start(channel: ResolvedMultiViewChannel) {
            super.start(channel)
            if (initialDelayMs > 0) recoveryBudget!!.deferFor(initialDelayMs, clock())
            preparation!!.resume()
        }
        override fun onRecoveryAllowedChanged() { preparation?.resume() }
        override fun release() { preparation?.close(); super.release() }
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
        managed: Boolean = false,
        initialManagedDelayMs: Long = 0,
        val sourceForChannel: (Long) -> Long = { 1 },
        resolver: (suspend (Long) -> ResolvedMultiViewChannel)? = null,
        validateAdmission: suspend (ResolvedMultiViewChannel, ReservationToken?) -> Unit = { _, _ -> },
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
                val onRelease = {
                    reservationsAtPlayerRelease += connections.allocator.snapshots.value.sumOf { it.locallyReserved }
                    Unit
                }
                val onVolumeChange = {
                    maximumAudiblePlayers = maxOf(maximumAudiblePlayers, players.count { it.currentVolume > 0f })
                }
                (if (managed) ManagedPlayer(listener, onRelease, onVolumeChange, { scope.testScheduler.currentTime },
                    if (players.isEmpty()) initialManagedDelayMs else 0) else FakePlayer(listener, onRelease, onVolumeChange)).also {
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
            validateAdmission = validateAdmission,
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

    @Test fun focusRestorationResumesManagedPreparationAfterTheAllowedCheckWasInterrupted() = runTest {
        val f = Fixture(this, managed = true)
        f.addReady(1)
        f.audio.callback!!.invoke(false)
        f.manager.add(2); runCurrent()
        val waiting = f.player(2) as ManagedPlayer
        assertNull(waiting.preparedAt)
        f.audio.callback!!.invoke(true); runCurrent()
        assertEquals(0L, waiting.preparedAt)
        waiting.ready(); runCurrent()
        assertEquals(MultiViewPanelStatus.PLAYING, f.panel(2).status)
        f.manager.release()
    }

    @Test fun managedStartupRetainsTheFull240SecondReadinessWindow() = runTest {
        val f = Fixture(this, managed = true)
        f.manager.add(1); runCurrent()
        val player = f.player(1) as ManagedPlayer
        assertEquals(0L, player.preparedAt)
        advanceTimeBy(1_000); runCurrent()
        assertEquals(0, player.releases)
        advanceTimeBy(238_999); runCurrent()
        assertEquals(0, player.releases)
        advanceTimeBy(1); runCurrent()
        assertEquals(MultiViewPanelStatus.FAILED, f.panel(1).status)
        assertEquals(1, player.releases)
        assertEquals(0, f.connections.snapshot(1)!!.locallyReserved)
        f.manager.release()
    }

    @Test fun managedDeferredPreparationOutlasts240SecondsWithoutBlockingAnotherPanel() = runTest {
        val f = Fixture(this, managed = true, initialManagedDelayMs = 300_000, sourceForChannel = { it })
        f.connections.register(2)
        f.manager.add(1); runCurrent()
        val delayed = f.player(1) as ManagedPlayer
        assertNull(delayed.preparedAt)
        f.addReady(2)
        assertEquals(MultiViewPanelStatus.PLAYING, f.panel(2).status)
        advanceTimeBy(240_001); runCurrent()
        assertEquals(0, delayed.releases); assertNull(delayed.preparedAt)
        advanceTimeBy(59_999); runCurrent()
        assertEquals(300_000L, delayed.preparedAt)
        delayed.ready(); runCurrent()
        assertEquals(MultiViewPanelStatus.PLAYING, f.panel(1).status)
        assertEquals(0, f.player(2).releases)
        f.manager.release()
    }

    @Test fun lateRetryAfterExtendsTheReadinessDeadlineAndReleasesAdmission() = runTest {
        val f = Fixture(this, managed = true, sourceForChannel = { it })
        f.connections.register(2)
        f.manager.add(1); runCurrent()
        advanceTimeBy(239_000); runCurrent()
        val first = f.player(1)
        first.recoveryBudget!!.deferFor(600_000, testScheduler.currentTime); runCurrent()
        f.addReady(2)
        advanceTimeBy(2_000); runCurrent()
        assertEquals(0, first.releases)
        advanceTimeBy(598_000); runCurrent()
        first.ready(); runCurrent()
        assertEquals(MultiViewPanelStatus.PLAYING, f.panel(1).status)
        assertEquals(0, f.player(2).releases)
        f.manager.release()
    }

    @Test fun cooldownBeforeAdmissionRechecksCapacityAndKeepsRecordingsReserved() = runTest {
        val f = Fixture(this, maximum = 2, managed = true, sourceForChannel = { it })
        f.connections.register(2)
        val id = f.addReady(1)
        f.player(1).recoveryBudget!!.deferFor(300_000, testScheduler.currentTime)
        f.manager.retry(id); runCurrent()
        f.addReady(2) // Admission cannot remain held by the first panel's cooldown.
        val recording = (f.connections.reserve(ConnectionRequest("dvr", 1, 1, ConnectionPurpose.RECORDING)) as ReservationResult.Reserved).token
        f.connections.register(1, maximum = 1)
        advanceTimeBy(300_000); runCurrent()
        assertEquals(2, f.players.size)
        assertNotNull(f.connections.allocator.reservationState(recording))
        assertEquals(MultiViewFailureKind.PROVIDER_LIMIT, f.panel(1).failureKind)
        assertEquals(0, f.player(2).releases)
        f.manager.release(); f.connections.release(recording)
    }

    @Test fun aNewGenerationCancelsAnUnadmittedCooldownBeforeItCanRestartTheOldChannel() = runTest {
        val f = Fixture(this, managed = true, sourceForChannel = { it })
        f.connections.register(3)
        val id = f.addReady(1)
        f.player(1).recoveryBudget!!.deferFor(300_000, testScheduler.currentTime)
        f.manager.retry(id); runCurrent()
        f.manager.replace(id, 3); runCurrent()
        f.player(3).ready(); runCurrent()
        advanceTimeBy(300_000); runCurrent()
        assertEquals(3L, f.state.panels.single().channelId)
        assertEquals(2, f.players.size)
        f.manager.release()
    }

    @Test fun failedCloseRetainsAccountAndDecoderCapacityWithoutRestartingOtherPanels() = runTest {
        val f = Fixture(this, maximum = 8)
        (1L..4L).forEach { f.addReady(it) }
        val failed = f.player(2)
        failed.failRelease = true
        failed.recoveryReconnect!!.invoke(); runCurrent()
        assertEquals(4, f.players.size)
        assertEquals(4, f.connections.snapshot(1)!!.locallyReserved)
        assertEquals(MultiViewPanelStatus.FAILED, f.panel(2).status)
        assertTrue(listOf(1L, 3L, 4L).all { f.player(it).releases == 0 })
        f.manager.remove(f.panel(2).id)
        f.manager.add(5); runCurrent()
        assertEquals(4, f.players.size) // The uncertain decoder is counted even after panel removal.
        failed.failRelease = false
        f.manager.stop()
        assertEquals(1, failed.releases)
        assertEquals(0, f.connections.snapshot(1)!!.locallyReserved)
    }

    @Test fun recoveryOfOneOfFourPanelsPreservesAudioLayoutAndOtherReservations() = runTest {
        val f = Fixture(this)
        val ids = (1L..4L).map { f.addReady(it) }
        f.manager.selectAudio(ids[2])
        val layout = f.state.layout
        val old = f.player(2)
        val others = listOf(1L, 3L, 4L).map(f::player)
        old.recoveryReconnect!!.invoke(); runCurrent()
        assertEquals(1, old.releases)
        assertTrue(others.all { it.releases == 0 })
        assertEquals(4, f.maximumAllocatedPlayers)
        assertEquals(ids[2], f.state.audioPanelId); assertEquals(layout, f.state.layout)
        val replacement = f.player(2)
        assertNotSame(old, replacement); assertSame(old.recoveryBudget, replacement.recoveryBudget)
        replacement.ready(); runCurrent()
        assertEquals(4, f.connections.snapshot(1)!!.locallyReserved)
        assertEquals(ids[2], f.state.audioPanelId)
        f.manager.release()
    }

    @Test fun recoveryClosesBeforeResolutionForSingleConnectionAndNeverReleasesDvr() = runTest {
        lateinit var f: Fixture
        var recovering = false
        f = Fixture(this, maximum = 2, resolver = { id ->
            if (recovering) {
                assertEquals(1, f.player(1).releases)
                assertEquals(1, f.connections.snapshot(1)!!.locallyReserved)
            }
            Fixture.resolved(id)
        })
        f.addReady(1)
        val recording = (f.connections.reserve(ConnectionRequest("dvr", 1, 1, ConnectionPurpose.RECORDING)) as ReservationResult.Reserved).token
        recovering = true
        f.player(1).recoveryReconnect!!.invoke(); runCurrent()
        f.player(1).ready(); runCurrent()
        assertEquals(2, f.connections.snapshot(1)!!.locallyReserved)
        assertNotNull(f.connections.allocator.reservationState(recording))
        f.manager.release()
        assertEquals(1, f.connections.snapshot(1)!!.locallyReserved)
        f.connections.release(recording)
    }

    @Test fun removedPanelAndNewChannelRejectStaleRecoveryCallback() = runTest {
        val f = Fixture(this)
        val id = f.addReady(1)
        val old = f.player(1)
        f.manager.replace(id, 2); runCurrent(); f.player(2).ready(); runCurrent()
        val count = f.players.size
        old.recoveryReconnect!!.invoke(); runCurrent()
        assertEquals(count, f.players.size); assertEquals(2L, f.state.panels.single().channelId)
        val callback = f.player(2).recoveryReconnect!!
        f.manager.remove(id); callback(); runCurrent()
        assertTrue(f.state.panels.isEmpty()); assertEquals(count, f.players.size)
        f.manager.release()
    }

    @Test fun retainedPlayerCanRecoverAfterNewReplacementFails() = runTest {
        val f = Fixture(this)
        val id = f.addReady(1)
        val retained = f.player(1)
        f.manager.replace(id, 2); runCurrent()
        assertFalse(retained.recoveryAllowed!!.invoke())
        f.player(2).recoveryBudget!!.publish(app.opentv.player.RecoveryState.FAILED,
            testScheduler.currentTime, app.opentv.player.RecoveryFailure.UNSUPPORTED)
        f.player(2).fail(MultiViewFailureKind.UNSUPPORTED); runCurrent()
        assertEquals(1L, f.panel(1).channelId)
        assertNotEquals(app.opentv.player.RecoveryState.FAILED, retained.recoveryBudget!!.snapshot.value.state)
        assertTrue(retained.recoveryAllowed!!.invoke())
        retained.recoveryReconnect!!.invoke(); runCurrent()
        assertEquals(1, retained.releases)
        assertNotSame(retained, f.player(1))
        f.player(1).ready(); runCurrent(); f.manager.release()
    }

    @Test fun unknownAllowanceRecoveryNeverReusesAnotherConnectionsConsent() = runTest {
        val f = Fixture(this, maximum = null)
        f.addReady(1)
        val id = f.panel(1).id
        // Another owner now occupies this account; recovery of the viewer requires new consent.
        val challenge = (f.connections.reserve(ConnectionRequest("dvr", 1, 1, ConnectionPurpose.RECORDING)) as ReservationResult.ApprovalRequired).challenge
        val recording = (f.connections.reserve(challenge.request, f.connections.approve(challenge)) as ReservationResult.Reserved).token
        f.player(1).recoveryReconnect!!.invoke(); runCurrent()
        assertNotNull(f.state.approval); assertEquals(1, f.players.size)
        f.manager.answerApproval(false); runCurrent()
        assertEquals(MultiViewPanelStatus.FAILED, f.state.panels.single { it.id == id }.status)
        assertEquals(1, f.connections.snapshot(1)!!.locallyReserved)
        f.manager.release(); f.connections.release(recording)
    }

    @Test fun explicitRetryIsAvailableAfterAutomaticFailureAndProviderEditCancelsOnlyAffectedPanel() = runTest {
        val f = Fixture(this, sourceForChannel = { it })
        f.connections.register(2)
        val one = f.addReady(1); f.addReady(2)
        val old = f.player(1)
        f.manager.invalidateSources(setOf(1))
        assertEquals(1, old.releases); assertEquals(0, f.player(2).releases)
        old.recoveryReconnect!!.invoke(); runCurrent(); assertEquals(2, f.players.size)
        repeat(5) {
            f.manager.retry(one); runCurrent(); f.player(1).ready(); runCurrent()
        }
        assertEquals(MultiViewPanelStatus.PLAYING, f.panel(1).status)
        assertEquals(0, f.player(2).releases)
        f.manager.release()
    }

    @Test fun recordingStartingDuringUnknownApprovalRevalidatesBeforeAnySecondPlayer() = runTest {
        var recordingDue = false
        var checks = 0
        val fixture = Fixture(this, maximum = null, validateAdmission = { _, _ ->
            checks++
            check(!recordingDue) { "Recording now needs this account" }
        })
        fixture.manager.add(1); runCurrent()
        fixture.player(1).ready(); runCurrent()
        fixture.manager.add(2); runCurrent()
        assertNotNull(fixture.state.approval)
        recordingDue = true
        fixture.manager.answerApproval(true); runCurrent()
        assertEquals(1, fixture.players.size)
        assertEquals(1, fixture.connections.allocator.snapshots.value.sumOf { it.locallyReserved })
        assertEquals(3, checks)
        fixture.manager.release()
    }

    @Test fun accountChangedDuringAdmissionShowsReviewMessageAndReleasesReservation() = runTest {
        lateinit var fixture: Fixture
        fixture = Fixture(this, validateAdmission = { _, _ -> fixture.connections.register(1, account = "changed-account") })
        fixture.manager.add(1); runCurrent()
        assertTrue(fixture.players.isEmpty())
        assertEquals("Provider account changed. Review this channel again.", fixture.panel(1).error)
        assertEquals(MultiViewFailureKind.PROVIDER_LIMIT, fixture.panel(1).failureKind)
        assertEquals(0, fixture.connections.allocator.snapshots.value.sumOf { it.locallyReserved })
        fixture.manager.release()
    }

    @Test fun staleGenerationDuringAdmissionCannotAllocatePlayer() = runTest {
        val gate = CompletableDeferred<Unit>()
        val fixture = Fixture(this, validateAdmission = { _, _ -> gate.await() })
        fixture.manager.add(1); runCurrent()
        fixture.manager.stop(); gate.complete(Unit); runCurrent()
        assertTrue(fixture.players.isEmpty())
        assertEquals(0, fixture.connections.allocator.snapshots.value.sumOf { it.locallyReserved })
        fixture.manager.release()
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

    @Test fun startupTimeoutReleasesOnlyTimedOutAttemptAndManualRetryRemainsAvailable() = runTest {
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
        assertEquals(allocations + 1, f.players.size)
        f.player(2).ready(); runCurrent()
        assertEquals(2, f.connections.snapshot(1)!!.locallyReserved)
        assertEquals(0, f.player(1).releases)
        f.manager.release()
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
    @Test fun warningApprovedReplacementPreservesPositionAudioAndUnrelatedPlayers() = runTest {
        val f = Fixture(this, sourceForChannel = { if (it == 3L) 2 else 1 })
        f.connections.register(2, 1)
        val primary = f.addReady(1); f.addReady(2)
        f.manager.selectAudio(primary)
        val before = f.state
        val reference = f.manager.playbackReferences().first { it.panelId == primary }
        assertTrue(f.manager.replaceForRecording(reference, 3) { true })
        runCurrent()
        assertEquals(0, f.player(1).releases)
        assertEquals(0, f.player(2).releases)
        f.player(3).ready(); runCurrent()
        assertEquals(1, f.player(1).releases)
        assertEquals(before.panels.map { it.id }, f.state.panels.map { it.id })
        assertEquals(before.audioPanelId, f.state.audioPanelId)
        assertEquals(before.primaryPanelId, f.state.primaryPanelId)
        assertEquals(before.layout, f.state.layout)
        assertEquals(0, f.player(2).releases)
        assertEquals(1f, f.player(3).currentVolume)
    }

    @Test fun failedRecordingAlternativeRetainsOriginalAndCandidateReleasesCapacity() = runTest {
        val f = Fixture(this, sourceForChannel = { if (it == 3L) 2 else 1 })
        f.connections.register(2, 1)
        val primary = f.addReady(1); f.addReady(2)
        val reference = f.manager.playbackReferences().first { it.panelId == primary }
        f.manager.replaceForRecording(reference, 3) { true }; runCurrent()
        f.player(3).fail(); runCurrent()
        assertEquals(0, f.player(1).releases)
        assertEquals(0, f.player(2).releases)
        assertEquals(1L, f.state.panels.first().channelId)
        assertEquals(0, f.connections.snapshot(2)!!.locallyReserved)
    }

    @Test fun deadlineDuringAlternativeStartupNeverCommitsLateCandidate() = runTest {
        val f = Fixture(this, sourceForChannel = { if (it == 3L) 2 else 1 })
        f.connections.register(2, 1)
        val primary = f.addReady(1)
        val reference = f.manager.playbackReferences().first { it.panelId == primary }
        var beforeDeadline = true
        f.manager.replaceForRecording(reference, 3) { beforeDeadline }; runCurrent()
        beforeDeadline = false
        f.player(3).ready(); runCurrent()
        assertEquals(0, f.player(1).releases)
        assertEquals(1, f.player(3).releases)
        assertEquals(1L, f.state.panels.first().channelId)
    }

    @Test fun staleWarningCannotReleaseOrRetuneNewerPanel() = runTest {
        val f = Fixture(this)
        val primary = f.addReady(1)
        val stale = f.manager.playbackReferences().single()
        f.manager.replace(primary, 2); runCurrent(); f.player(2).ready(); runCurrent()
        assertFalse(f.manager.releaseForRecording(stale))
        assertFalse(f.manager.replaceForRecording(stale, 3) { true })
        assertEquals(0, f.player(2).releases)
    }

    @Test fun userReleaseLeavesPrimarySlotAndOtherAudioActive() = runTest {
        val f = Fixture(this)
        val primary = f.addReady(1); val secondary = f.addReady(2)
        f.manager.selectAudio(secondary)
        assertTrue(f.manager.releaseForRecording(f.manager.playbackReferences().first { it.panelId == primary }))
        assertEquals(primary, f.state.primaryPanelId)
        assertEquals(secondary, f.state.audioPanelId)
        assertEquals(MultiViewPanelStatus.PAUSED, f.state.panels.first().status)
        assertEquals(0, f.player(2).releases)
        assertEquals(1f, f.player(2).currentVolume)
    }

    @Test fun unknownAlternativeRequiresConsentAndRetainsOriginalWhileWaiting() = runTest {
        val f = Fixture(this, sourceForChannel = { if (it == 3L) 2 else 1 })
        f.connections.register(2, null)
        f.connections.reserve(ConnectionRequest("other-device-local", 1, 2, ConnectionPurpose.LIVE))
        f.addReady(1)
        val reference = f.manager.playbackReferences().single()
        f.manager.replaceForRecording(reference, 3) { true }; runCurrent()
        assertNotNull(f.state.approval)
        assertEquals(0, f.player(1).releases)
        f.manager.answerApproval(false); runCurrent()
        assertEquals(0, f.player(1).releases)
        assertEquals(1L, f.state.panels.first().channelId)
    }

    @Test fun coordinatorSuppressesDuplicateWarningsAndExpiresWithoutStoppingPlayers() = runTest {
        val f = Fixture(this, maximum = 1)
        f.addReady(1)
        var rows = listOf(warningBooking())
        val dismissed = mutableSetOf<String>()
        val events = mutableListOf<String>()
        val coordinator = app.opentv.recording.RecordingConflictCoordinator(backgroundScope, f.manager,
            { rows }, { f.connections.allocator.snapshots.value }, { 120_000 }, dismissed::contains,
            { dismissed.add(it); Unit }, { _, kind, _ -> events.add(kind) }, now = { testScheduler.currentTime })
        coordinator.setVisible(true); runCurrent()
        val first = coordinator.warning.value!!
        coordinator.refresh(); coordinator.refresh()
        assertEquals(1, events.count { it == "VIEWING_WARNING" })
        coordinator.keepWatching(first.conflict.key); runCurrent(); coordinator.refresh()
        assertNull(coordinator.warning.value)
        assertEquals(0, f.player(1).releases)
        rows = listOf(warningBooking().copy(scheduledStartMillis = 90_000))
        coordinator.invalidateRows()
        assertNotNull(coordinator.warning.value)
        advanceTimeBy(90_000); runCurrent(); coordinator.refresh()
        assertNull(coordinator.warning.value)
        assertEquals(0, f.player(1).releases)
        assertTrue(events.contains("VIEWING_DEFERRED"))
    }

    @Test fun coordinatorCancellationBackgroundAndProviderCapacityInvalidateDialogActions() = runTest {
        val f = Fixture(this, maximum = 1); f.addReady(1)
        var rows = listOf(warningBooking())
        val notifications = mutableListOf<Long>()
        val coordinator = app.opentv.recording.RecordingConflictCoordinator(backgroundScope, f.manager,
            { rows }, { f.connections.allocator.snapshots.value }, { 120_000 }, { false }, {}, { _, _, _ -> },
            notification = { notifications.add(it.recording.id) }, now = { testScheduler.currentTime })
        coordinator.setVisible(true); runCurrent()
        val old = coordinator.warning.value!!
        coordinator.setVisible(false); runCurrent()
        assertNull(coordinator.warning.value)
        assertTrue(notifications.isNotEmpty())
        coordinator.setVisible(true); runCurrent()
        f.connections.allocator.configure(1, AccountConnectionPolicy(2, false))
        coordinator.refresh(); assertNull(coordinator.warning.value)
        assertFalse(coordinator.release(old.conflict.key, old.panels.first()))
        f.connections.allocator.configure(1, AccountConnectionPolicy(1, false)); coordinator.refresh()
        rows = emptyList(); coordinator.invalidateRows()
        assertNull(coordinator.warning.value)
        assertFalse(coordinator.release(old.conflict.key, old.panels.first()))
        assertEquals(0, f.player(1).releases)
    }

    private fun warningBooking() = app.opentv.data.model.Recording(id = 1, channelId = 99, sourceId = 1,
        channelName = "Record me", title = "Game", filePath = "/fake.ts", streamUrl = "https://example.invalid/capture",
        scheduledStartMillis = 60_000, scheduledEndMillis = 600_000, status = app.opentv.data.model.RecordingStatus.SCHEDULED)

    @Test fun fullDecoderCapacityRejectsConflictSwitchWithoutBreakingOriginal() = runTest {
        val f = Fixture(this, hardwareMaximum = 2, sourceForChannel = { if (it == 3L) 2 else 1 })
        f.connections.register(2, 1)
        f.addReady(1); f.addReady(2)
        f.manager.replaceForRecording(f.manager.playbackReferences().first(), 3) { true }; runCurrent()
        assertEquals(2, f.players.size)
        assertEquals(0, f.player(1).releases)
        assertEquals(0, f.player(2).releases)
        assertEquals(1L, f.state.panels.first().channelId)
    }

    @Test fun canceledWarningDuringResolutionKeepsOriginalRetryIdentity() = runTest {
        val channel = CompletableDeferred<ResolvedMultiViewChannel>()
        val f = Fixture(this, resolver = { if (it == 3L) channel.await() else Fixture.resolved(it) })
        f.connections.register(2, 1)
        f.addReady(1)
        var allowed = true
        f.manager.replaceForRecording(f.manager.playbackReferences().single(), 3) { allowed }; runCurrent()
        allowed = false
        channel.complete(Fixture.resolved(3, 2)); runCurrent()
        assertEquals(0, f.player(1).releases)
        assertEquals(1, f.players.size)
        assertEquals(1L, f.state.panels.first().channelId)
        f.manager.retry(f.state.panels.first().id); runCurrent()
        assertEquals(1L, f.resolvedIds.last())
    }

    @Test fun coordinatorCountdownAndCapacityUseCachedRowsUntilExplicitInvalidation() = runTest {
        val f = Fixture(this, maximum = 1); f.addReady(1)
        var rows = listOf(warningBooking())
        var reads = 0
        val coordinator = app.opentv.recording.RecordingConflictCoordinator(backgroundScope, f.manager,
            { reads++; rows }, { f.connections.allocator.snapshots.value }, { 120_000 }, { false }, {}, { _, _, _ -> },
            now = { testScheduler.currentTime })
        coordinator.setVisible(true); runCurrent()
        assertEquals(1, reads)
        val originalTime = coordinator.warning.value!!.nowMillis
        advanceTimeBy(5_000); runCurrent()
        assertEquals(1, reads)
        assertTrue(coordinator.warning.value!!.nowMillis > originalTime)
        f.connections.allocator.configure(1, AccountConnectionPolicy(2, false)); coordinator.refresh()
        assertNull(coordinator.warning.value)
        assertEquals(1, reads)
        rows = emptyList(); coordinator.invalidateRows()
        assertEquals(2, reads)
        assertNull(coordinator.warning.value)
    }

}
