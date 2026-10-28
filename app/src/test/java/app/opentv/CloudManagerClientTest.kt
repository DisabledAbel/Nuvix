/* Nuvix Contributors. GPL-3.0-or-later. */
package app.opentv

import app.opentv.pairing.CloudManagerClient
import app.opentv.pairing.CloudPairingDeadline
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

/** Exercises the production lifecycle with manual transport callbacks and virtual elapsed time. */
@OptIn(ExperimentalCoroutinesApi::class)
class CloudManagerClientTest {
    @Test fun olderWorkerRequiresExplicitUpgradeBeforeConnecting() = runTest {
        val f = Fixture(this)
        f.client.start(ORIGIN)
        f.transport.calls.first().respond(body = "{\"ok\":true,\"pairUntil\":1800000300000,\"serverNow\":1800000000000}")
        assertTrue(f.client.state.value is CloudManagerClient.State.Failed)
        assertTrue((f.client.state.value as CloudManagerClient.State.Failed).reason.contains("Worker"))
        assertTrue(f.transport.sockets.isEmpty())
    }

    @Test fun countdownRoundsUpAtSecondBoundariesAndClampsAtZero() {
        for ((millis, expected) in listOf(
            -1L to "0:00", 0L to "0:00", 1L to "0:01", 999L to "0:01",
            1000L to "0:01", 1001L to "0:02", 59_000L to "0:59",
            59_001L to "1:00", 60_000L to "1:00", 60_001L to "1:01",
            299_000L to "4:59", 299_001L to "5:00", 300_000L to "5:00",
        )) {
            assertEquals("$millis ms", expected,
                CloudPairingDeadline.format(CloudPairingDeadline.secondsRemaining(millis)))
        }
    }

    @Test fun requestAndConnectionDelaysReduceTheServerBudgetWithoutDeviceWallTime() = runTest {
        val f = Fixture(this)
        f.client.start(ORIGIN)
        advanceTimeBy(2000)
        // The server clock is unrelated to the Android elapsed clock; 1s was spent on the server.
        f.transport.calls.first().respond(pairUntil = SERVER + 300_000, serverNow = SERVER + 1000)
        advanceTimeBy(3000)
        f.transport.sockets.last().open()
        assertEquals(294L, f.ready().remainingSeconds)
        advanceTimeBy(1000)
        runCurrent()
        assertEquals(293L, f.ready().remainingSeconds)
    }

    @Test fun collectingAgainDoesNotResetTheDeadlineOrNeedAnyCollectorsToExpire() = runTest {
        val f = Fixture(this).apply { startAndOpen() }
        val firstCollector = backgroundScope.launch { f.client.state.collect() }
        advanceTimeBy(100_000)
        runCurrent()
        assertEquals(200L, f.ready().remainingSeconds)
        firstCollector.cancel()
        val recomposedCollector = backgroundScope.launch { f.client.state.collect() }
        advanceTimeBy(100_000)
        runCurrent()
        assertEquals(100L, f.ready().remainingSeconds)
        recomposedCollector.cancel()
        advanceTimeBy(99_999)
        runCurrent()
        assertEquals(1L, f.ready().remainingSeconds)
        advanceTimeBy(1)
        runCurrent()
        assertEquals(CloudManagerClient.State.Expired, f.client.state.value)
        assertFalse(f.transport.sockets.last().closed)
        assertEquals(1, f.transport.calls.count { it.request().url.encodedPath == "/api/create" })
        assertFalse(f.transport.calls.any { it.request().url.encodedPath == "/api/end" })
    }

    @Test fun zeroOrAlreadyExpiredCreationNeverShowsACodeOrConnects() = runTest {
        for (pairUntil in listOf(0L, SERVER - 1, SERVER)) {
            val f = Fixture(this)
            f.client.start(ORIGIN)
            f.transport.calls.first().respond(pairUntil = pairUntil)
            runCurrent()
            assertEquals(CloudManagerClient.State.Expired, f.client.state.value)
            assertTrue(f.transport.sockets.isEmpty())
        }
    }

    @Test fun expiryDuringConnectionHidesTheCodeWithoutRevokingTheWorkerSession() = runTest {
        val f = Fixture(this)
        f.client.start(ORIGIN)
        f.transport.calls.first().respond()
        val pendingSocket = f.transport.sockets.last()
        advanceTimeBy(300_000)
        runCurrent()
        assertEquals(CloudManagerClient.State.Expired, f.client.state.value)
        pendingSocket.open() // A late onOpen cannot restore the expired code.
        assertEquals(CloudManagerClient.State.Expired, f.client.state.value)
        assertFalse(pendingSocket.closed)
        assertFalse(f.transport.calls.any { it.request().url.encodedPath == "/api/end" })
    }

    @Test fun delayedOpenChecksTheDeadlineBeforePublishingReadyEvenIfTimerHasNotRun() = runTest {
        val f = Fixture(this)
        f.client.start(ORIGIN)
        f.transport.calls.first().respond()
        f.slept = 300_000
        f.transport.sockets.last().open()
        assertEquals(CloudManagerClient.State.Expired, f.client.state.value)
        assertFalse(f.transport.sockets.last().closed)
        runCurrent()
        assertEquals(CloudManagerClient.State.Expired, f.client.state.value)
    }

    @Test fun pairingImmediatelyHidesCountdownAndCancelsPairingExpiryUntilServerEndsSession() = runTest {
        val f = Fixture(this).apply { startAndOpen() }
        advanceTimeBy(299_999)
        f.transport.sockets.last().paired()
        assertTrue(f.ready().paired)
        assertNull(f.ready().remainingSeconds)
        advanceTimeBy(1_800_000)
        runCurrent()
        assertTrue(f.ready().paired)
        assertFalse(f.transport.sockets.last().closed)
        // The existing Worker hard session limit still closes the transport.
        f.transport.sockets.last().disconnect()
        assertTrue(f.client.state.value is CloudManagerClient.State.Failed)
    }

    @Test fun pairingAtDeadlineBeforeTimerRunsKeepsTheCurrentSocketOpen() = runTest {
        val f = Fixture(this).apply { startAndOpen() }
        f.slept = 300_000 // Advance elapsed time without dispatching the expiry timer.
        f.transport.sockets.last().paired()
        assertTrue(f.ready().paired)
        assertNull(f.ready().remainingSeconds)
        assertFalse(f.transport.sockets.last().closed)
        runCurrent()
        assertTrue(f.ready().paired)
        assertFalse(f.transport.calls.any { it.request().url.encodedPath == "/api/end" })
    }

    @Test fun workerPairingNotificationAfterLocalExpiryRestoresPairedStateWithoutRevocation() = runTest {
        val f = Fixture(this).apply { startAndOpen() }
        advanceTimeBy(300_000)
        runCurrent()
        assertEquals(CloudManagerClient.State.Expired, f.client.state.value)
        assertFalse(f.transport.sockets.last().closed)
        assertFalse(f.transport.calls.any { it.request().url.encodedPath == "/api/end" })
        f.transport.sockets.last().paired()
        assertTrue(f.ready().paired)
        assertNull(f.ready().remainingSeconds)
        advanceTimeBy(60_000)
        runCurrent()
        assertTrue(f.ready().paired)
        assertFalse(f.transport.sockets.last().closed)
        f.client.stop()
        assertTrue(f.transport.sockets.last().closed)
        assertEquals("/api/end", f.transport.calls.last().request().url.encodedPath)
    }

    @Test fun disconnectAtOrAfterDeadlineStillRevokesAndRejectsDelayedPairing() = runTest {
        for (runTimer in listOf(false, true)) {
            val f = Fixture(this).apply { startAndOpen() }
            f.slept = 300_000
            if (runTimer) runCurrent()
            val socket = f.transport.sockets.last()
            socket.disconnect()
            assertEquals(CloudManagerClient.State.Expired, f.client.state.value)
            assertTrue(socket.closed)
            assertEquals("/api/end", f.transport.calls.last().request().url.encodedPath)
            socket.paired()
            assertEquals(CloudManagerClient.State.Expired, f.client.state.value)
        }
    }

    @Test fun restartingAfterLocalExpiryRevokesOldSessionAndIgnoresItsLatePairing() = runTest {
        val f = Fixture(this).apply { startAndOpen() }
        advanceTimeBy(300_000)
        runCurrent()
        val oldSocket = f.transport.sockets.last()
        f.startAndOpen()
        assertTrue(oldSocket.closed)
        assertEquals(1, f.transport.calls.count { it.request().url.encodedPath == "/api/end" })
        oldSocket.paired()
        assertFalse(f.ready().paired)
        assertEquals(300L, f.ready().remainingSeconds)
    }

    @Test fun stopAndRestartCancelOldTimersAndIgnoreStaleSocketCallbacks() = runTest {
        val f = Fixture(this).apply { startAndOpen() }
        val oldSocket = f.transport.sockets.last()
        advanceTimeBy(200_000)
        f.client.stop()
        assertEquals(CloudManagerClient.State.Idle, f.client.state.value)
        assertTrue(oldSocket.closed)
        f.startAndOpen()
        val newSocket = f.transport.sockets.last()
        oldSocket.open()
        oldSocket.paired()
        oldSocket.disconnect()
        advanceTimeBy(100_000) // Original session's deadline.
        runCurrent()
        assertFalse(f.ready().paired)
        assertEquals(200L, f.ready().remainingSeconds)
        assertFalse(newSocket.closed)
        advanceTimeBy(200_000)
        runCurrent()
        assertEquals(CloudManagerClient.State.Expired, f.client.state.value)
    }

    @Test fun replacedCreationAndDisconnectedSessionCannotChangeANewerSession() = runTest {
        val f = Fixture(this)
        f.client.start(ORIGIN)
        val obsolete = f.transport.calls.first()
        f.client.start(ORIGIN)
        assertTrue(obsolete.isCanceled())
        obsolete.respond()
        assertTrue(f.transport.sockets.isEmpty())
        f.transport.calls.last().respond()
        val oldSocket = f.transport.sockets.last().apply { open() }
        oldSocket.disconnect()
        assertTrue(f.client.state.value is CloudManagerClient.State.Failed)
        advanceTimeBy(10_000)
        f.startAndOpen()
        advanceTimeBy(290_000)
        runCurrent()
        assertEquals(10L, f.ready().remainingSeconds)
        oldSocket.paired()
        assertFalse(f.ready().paired)
        f.client.stop()
        advanceTimeBy(600_000)
        runCurrent()
        assertEquals(CloudManagerClient.State.Idle, f.client.state.value)
    }

    @Test fun delayedExecutionAfterSleepRecalculatesElapsedTimeInsteadOfCountingTicks() = runTest {
        val f = Fixture(this).apply { startAndOpen() }
        runCurrent()
        // elapsedRealtime advances during deep sleep, while coroutine delays may not execute.
        f.slept = 250_500
        advanceTimeBy(1000)
        runCurrent()
        assertEquals(49L, f.ready().remainingSeconds)
        f.slept += 60_000
        advanceTimeBy(1000)
        runCurrent()
        assertEquals(CloudManagerClient.State.Expired, f.client.state.value)
    }

    @Test fun missingOrInvalidTimingFailsClosedAndDoesNotInventAFreshFiveMinutes() = runTest {
        for (body in listOf("{\"ok\":true}", "{}", "invalid", "{\"pairUntil\":-1,\"serverNow\":0}")) {
            val f = Fixture(this)
            f.client.start(ORIGIN)
            f.transport.calls.first().respond(body = body)
            assertTrue(f.client.state.value is CloudManagerClient.State.Failed)
            assertTrue(f.transport.sockets.isEmpty())
        }
    }

    private class Fixture(scope: TestScope) {
        var slept = 0L
        val transport = ManualCloudManagerTransport()
        val client = CloudManagerClient(scope.backgroundScope, { error("No commands expected") },
            transport, transport, { scope.testScheduler.currentTime + slept })
        fun startAndOpen() {
            client.start(ORIGIN)
            transport.calls.last().respond()
            transport.sockets.last().open()
        }
        fun ready() = client.state.value as CloudManagerClient.State.Ready
    }

    private companion object {
        const val ORIGIN = "https://manager.example"
        const val SERVER = 1_800_000_000_000L
    }
}
