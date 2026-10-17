/* Nuvix modifications Copyright (C) 2026 Nuvix Contributors. GPL-3.0-or-later. */
package app.opentv.connections

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class ConnectionAllocatorTest {
    private fun allocator(): ConnectionAllocator {
        val counter = AtomicInteger()
        return ConnectionAllocator { "id-${counter.incrementAndGet()}" }
    }
    private fun request(id: String, sourceId: Long = 1, generation: Long = 1, purpose: ConnectionPurpose = ConnectionPurpose.LIVE) =
        ConnectionRequest(id, generation, sourceId, purpose)
    private fun reserve(allocator: ConnectionAllocator, request: ConnectionRequest): ReservationToken =
        (allocator.reserve(request) as ReservationResult.Reserved).token

    @Test fun independentAccountsHaveIndependentCapacity() {
        val allocator = allocator()
        allocator.registerSource(1, "account-a", AccountConnectionPolicy(1, false))
        allocator.registerSource(2, "account-b", AccountConnectionPolicy(1, false))
        reserve(allocator, request("a"))
        reserve(allocator, request("b", 2))
        assertTrue(allocator.reserve(request("a-extra")) is ReservationResult.Denied)
        assertEquals(listOf(1, 1), allocator.snapshots.value.map { it.locallyReserved })
    }

    @Test fun playlistsLinkedToSameAccountConsumeOnePool() {
        val allocator = allocator()
        allocator.registerSource(1, "same-account", AccountConnectionPolicy(1, false))
        allocator.registerSource(2, "same-account")
        reserve(allocator, request("playlist-1"))
        assertTrue(allocator.reserve(request("playlist-2", 2)) is ReservationResult.Denied)
        assertEquals(setOf(1L, 2L), allocator.snapshots.value.single().sourceIds)
    }

    @Test fun unknownAdditionalConnectionRequiresSpecificOneShotApproval() {
        val allocator = allocator()
        allocator.registerSource(1, "unknown")
        reserve(allocator, request("existing"))
        val extra = request("extra")
        val challenge = (allocator.reserve(extra) as ReservationResult.ApprovalRequired).challenge
        val approval = allocator.approve(challenge)!!
        assertNull(allocator.approve(challenge))
        val token = (allocator.reserve(extra, approval) as ReservationResult.Reserved).token
        assertEquals(2, allocator.snapshotForSource(1)!!.locallyReserved)
        allocator.release(token)
        assertTrue(allocator.reserve(request("extra", generation = 2), approval) is ReservationResult.ApprovalRequired)
        assertNull(allocator.snapshotForSource(1)!!.effectiveMaximum)
    }

    @Test fun approvalCannotBeTransferredToAnotherRequestAndAttemptConsumesIt() {
        val allocator = allocator()
        allocator.registerSource(1, "unknown")
        reserve(allocator, request("existing"))
        val allowed = request("allowed")
        val challenge = (allocator.reserve(allowed) as ReservationResult.ApprovalRequired).challenge
        val approval = allocator.approve(challenge)!!
        assertTrue(allocator.reserve(request("different"), approval) is ReservationResult.ApprovalRequired)
        assertTrue(allocator.reserve(allowed, approval) is ReservationResult.ApprovalRequired)
    }

    @Test fun newerGenerationInvalidatesOldConfirmationAndCallbacks() {
        val allocator = allocator()
        allocator.registerSource(1, "account", AccountConnectionPolicy(2, false))
        val old = reserve(allocator, request("session", generation = 1))
        val replacement = reserve(allocator, request("session", generation = 2))
        allocator.beginRelease(old)
        assertEquals(2, allocator.snapshotForSource(1)!!.locallyReserved)
        assertTrue(allocator.release(old))
        assertFalse(allocator.release(old))
        assertFalse(allocator.markActive(old))
        assertTrue(allocator.markActive(replacement))
        assertEquals(ReservationState.RELEASED, allocator.reservationState(old))
        assertEquals(ReservationState.ACTIVE, allocator.reservationState(replacement))
        assertEquals(1, allocator.snapshotForSource(1)!!.locallyReserved)
        assertTrue(allocator.reserve(request("session", generation = 1)) is ReservationResult.Denied)
    }

    @Test fun identicalLiveReservationIsIdempotentButReleasedGenerationCannotReopen() {
        val allocator = allocator()
        allocator.registerSource(1, "account")
        val attempt = request("session")
        val token = reserve(allocator, attempt)
        assertEquals(token, reserve(allocator, attempt))
        assertEquals(1, allocator.snapshotForSource(1)!!.locallyReserved)
        allocator.release(token)
        assertTrue(allocator.reserve(attempt) is ReservationResult.Denied)
        assertTrue(allocator.reserve(attempt.copy(generation = 2)) is ReservationResult.Reserved)
    }

    @Test fun policyChangeInvalidatesPreviouslyGrantedApproval() {
        val allocator = allocator()
        allocator.registerSource(1, "account")
        reserve(allocator, request("existing"))
        val attempt = request("extra")
        val challenge = (allocator.reserve(attempt) as ReservationResult.ApprovalRequired).challenge
        val approval = allocator.approve(challenge)!!
        allocator.configure(1, AccountConnectionPolicy(1, false))
        assertTrue(allocator.reserve(attempt, approval) is ReservationResult.Denied)
        allocator.configure(1, AccountConnectionPolicy(null, false))
        assertTrue(allocator.reserve(attempt, approval) is ReservationResult.ApprovalRequired)
    }

    @Test fun providerActiveObservationDoesNotPretendToKnowOtherDeviceUsage() {
        val allocator = allocator()
        allocator.registerSource(1, "account")
        allocator.observeProvider(1, maximum = 2, active = 2, observedAt = 100)
        val snapshot = allocator.snapshotForSource(1)!!
        assertEquals(2, snapshot.effectiveMaximum)
        assertEquals(2, snapshot.providerReportedActive)
        assertEquals(100L, snapshot.providerObservationTimeMillis)
        assertEquals(0, snapshot.locallyReserved)
        reserve(allocator, request("local"))
        assertEquals(1, allocator.snapshotForSource(1)!!.locallyReserved)
    }

    @Test fun zeroOrMissingProviderMaximumIsUnknown() {
        val allocator = allocator()
        allocator.registerSource(1, "account")
        allocator.observeProvider(1, 0, -1, 10)
        assertNull(allocator.snapshotForSource(1)!!.effectiveMaximum)
        assertNull(allocator.snapshotForSource(1)!!.providerReportedActive)
    }

    @Test fun genericFailureDoesNotBecomeKnownConnectionLimitError() {
        val allocator = allocator()
        allocator.registerSource(1, "account")
        val failed = reserve(allocator, request("timeout"))
        allocator.fail(failed)
        assertEquals(ReservationState.FAILED, allocator.reservationState(failed))
        assertEquals(0, allocator.snapshotForSource(1)!!.knownConnectionLimitErrors)
        val explicit = reserve(allocator, request("explicit"))
        allocator.fail(explicit, knownConnectionLimit = true)
        allocator.fail(explicit, knownConnectionLimit = true)
        assertEquals(1, allocator.snapshotForSource(1)!!.knownConnectionLimitErrors)
    }

    @Test fun associationCannotMoveOrHideActiveReservations() {
        val allocator = allocator()
        allocator.registerSource(1, "a", AccountConnectionPolicy(1, false))
        allocator.registerSource(2, "b", AccountConnectionPolicy(1, false))
        val token = reserve(allocator, request("active"))
        assertFalse(allocator.reassociateSource(1, "b"))
        assertEquals("a", allocator.accountKeyForSource(1))
        allocator.release(token)
        assertTrue(allocator.reassociateSource(1, "b"))
        assertEquals(setOf(1L, 2L), allocator.snapshotForSource(2)!!.sourceIds)
    }

    @Test fun pendingAndReleasingReservationsKeepCapacityUntilRealClose() {
        val allocator = allocator()
        allocator.registerSource(1, "account", AccountConnectionPolicy(1, false))
        val token = reserve(allocator, request("pending"))
        assertTrue(allocator.reserve(request("other")) is ReservationResult.Denied)
        allocator.beginRelease(token)
        assertTrue(allocator.reserve(request("other")) is ReservationResult.Denied)
        allocator.release(token)
        assertTrue(allocator.reserve(request("other")) is ReservationResult.Reserved)
    }

    @Test fun atomicReservationsNeverExceedCapacityUnderConcurrentStarts() {
        val allocator = allocator()
        allocator.registerSource(1, "account", AccountConnectionPolicy(3, false))
        val executor = Executors.newFixedThreadPool(12)
        val start = CountDownLatch(1)
        val done = CountDownLatch(60)
        val winners = AtomicInteger()
        repeat(60) { index -> executor.execute {
            start.await()
            if (allocator.reserve(request("request-$index")) is ReservationResult.Reserved) winners.incrementAndGet()
            done.countDown()
        } }
        start.countDown()
        assertTrue(done.await(5, TimeUnit.SECONDS))
        executor.shutdownNow()
        assertEquals(3, winners.get())
        assertEquals(3, allocator.snapshotForSource(1)!!.locallyReserved)
    }
}
