/* Copyright (C) 2026 Nuvix Contributors. GPL-3.0-or-later. */
package app.opentv

import app.opentv.player.*
import org.junit.Assert.*
import org.junit.Test

/** No network, scheduler, decoder or wall clock: deterministic watchdog and budget tests. */
class PlaybackRecoveryTest {
    private class Fixture(val budget: RecoveryBudget = RecoveryBudget()) {
        var now = 0L
        val monitor = PlaybackRecoveryMonitor(budget) { now }
        fun tick(ms: Long = 1_000, sample: RecoverySample = RecoverySample(true, ready = true, positionMs = now + ms)) : RecoveryAction? {
            now += ms
            return monitor.sample(sample)
        }
        fun buffering(ms: Long = 1_000) = tick(ms, RecoverySample(true, buffering = true))
        fun failure(category: RecoveryFailure = RecoveryFailure.NETWORK, retryAfter: Long = 0) = monitor.failure(category, retryAfter)
    }

    @Test fun `healthy playback produces no action`() {
        val f = Fixture()
        repeat(180) { assertNull(f.tick()) }
        assertEquals(RecoveryState.HEALTHY, f.budget.snapshot.value.state)
        assertEquals(0, f.budget.snapshot.value.attempts)
    }
    @Test fun `short buffering returns naturally`() {
        val f = Fixture()
        repeat(4) { f.tick() }; repeat(10) { assertNull(f.buffering()) }; repeat(5) { assertNull(f.tick()) }
        assertEquals(0, f.budget.snapshot.value.attempts)
    }
    @Test fun `slow initial startup receives forty five seconds plus backoff`() {
        val f = Fixture()
        f.buffering(0); assertNull(f.buffering(44_999)); assertNull(f.buffering(1))
        assertEquals(RecoveryState.WAITING, f.budget.snapshot.value.state)
        assertNull(f.buffering(1_999)); assertEquals(RecoveryAction.LIGHTWEIGHT, f.buffering(1))
    }
    @Test fun `configured startup reservoir extends timeout`() {
        val f = Fixture()
        f.monitor.sample(RecoverySample(true, buffering = true), 30_000, 30_000)
        f.now = 45_000; f.monitor.sample(RecoverySample(true, buffering = true), 30_000, 30_000)
        assertEquals(0, f.budget.snapshot.value.attempts)
        assertEquals(RecoveryState.BUFFERING, f.budget.snapshot.value.state)
        f.now = 60_000; f.monitor.sample(RecoverySample(true, buffering = true), 30_000, 30_000)
        assertEquals(RecoveryState.WAITING, f.budget.snapshot.value.state)
    }
    @Test fun `sustained rebuffer triggers lightweight recovery after natural grace`() {
        val f = Fixture(); repeat(5) { f.tick() }
        f.buffering(0); f.buffering(30_000)
        assertEquals(RecoveryAction.LIGHTWEIGHT, f.buffering(2_000))
        assertEquals(1, f.budget.snapshot.value.attempts)
    }
    @Test fun `ready without media progress is a stall`() {
        val f = Fixture()
        f.tick(0, RecoverySample(true, ready = true))
        assertNull(f.tick(30_000, RecoverySample(true, ready = true)))
        assertEquals(RecoveryAction.LIGHTWEIGHT, f.tick(2_000, RecoverySample(true, ready = true)))
    }
    @Test fun `rendered frames are evidence when live position does not advance`() {
        val f = Fixture()
        repeat(60) { frame -> assertNull(f.tick(sample = RecoverySample(true, ready = true, renderedFrames = frame.toLong()))) }
        assertEquals(0, f.budget.snapshot.value.attempts)
    }
    @Test fun `moving player clock cannot mask a frozen video renderer`() {
        val f = Fixture()
        repeat(31) { f.tick(sample = RecoverySample(true, ready = true, positionMs = f.now + 1_000,
            renderedFrames = 5, videoExpected = true)) }
        assertEquals(RecoveryAction.LIGHTWEIGHT, f.tick(2_000, RecoverySample(true, ready = true, positionMs = f.now + 2_000,
            renderedFrames = 5, videoExpected = true)))
    }
    @Test fun `repeated meaningful rebuffers trigger recovery without waiting for one long stall`() {
        val f = Fixture(); repeat(5) { f.tick() }
        repeat(3) { f.buffering(0); f.buffering(10_000); f.tick() }
        assertEquals(RecoveryState.WAITING, f.budget.snapshot.value.state)
        assertEquals(RecoveryAction.LIGHTWEIGHT, f.tick(2_000, RecoverySample(true, buffering = true)))
    }

    @Test fun `live window position rollback rebases watchdog`() {
        val f = Fixture()
        f.tick(0, RecoverySample(true, ready = true, positionMs = 100_000))
        assertNull(f.tick(29_000, RecoverySample(true, ready = true, positionMs = 10)))
        assertNull(f.tick(29_000, RecoverySample(true, ready = true, positionMs = 10)))
        assertEquals(0, f.budget.snapshot.value.attempts)
    }
    @Test fun `network disconnect uses first backoff exactly once`() {
        val f = Fixture(); f.failure(); f.failure()
        assertNull(f.tick(1_999)); assertEquals(RecoveryAction.LIGHTWEIGHT, f.tick(1))
        assertNull(f.tick(1)); assertEquals(1, f.budget.snapshot.value.attempts)
    }
    @Test fun `lightweight recovery requires progress rather than READY alone`() {
        val f = Fixture(); f.failure(); f.tick(2_000)
        f.tick(1_000, RecoverySample(true, ready = true)); f.tick(1_000, RecoverySample(true, ready = true))
        assertNotEquals(RecoveryState.RECOVERED, f.budget.snapshot.value.state)
        repeat(5) { f.tick() }
        assertEquals(RecoveryState.RECOVERED, f.budget.snapshot.value.state)
        assertEquals(1, f.budget.snapshot.value.attempts)
    }
    @Test fun `second and third retries reconnect after five and ten seconds`() {
        val f = Fixture(); f.failure(); assertEquals(RecoveryAction.LIGHTWEIGHT, f.tick(2_000))
        f.failure(); assertNull(f.tick(4_999)); assertEquals(RecoveryAction.RECONNECT, f.tick(1))
        f.failure(); assertNull(f.tick(9_999)); assertEquals(RecoveryAction.RECONNECT, f.tick(1))
        f.failure(); assertEquals(RecoveryAction.FAIL, f.tick(1)); assertNull(f.tick(60_000))
        assertEquals(3, f.budget.snapshot.value.attempts)
    }
    @Test fun `controlled player replacement retains budget`() {
        val f = Fixture(); f.failure(); f.tick(2_000)
        val replacement = PlaybackRecoveryMonitor(f.budget) { f.now }
        replacement.failure(RecoveryFailure.NETWORK)
        f.now += 5_000
        assertEquals(RecoveryAction.RECONNECT, replacement.sample(RecoverySample(true)))
        assertEquals(2, f.budget.snapshot.value.attempts)
    }
    @Test fun `brief success does not refill retry allowance`() {
        val f = Fixture(); f.failure(); f.tick(2_000); repeat(10) { f.tick() }
        f.failure(); assertEquals(RecoveryAction.RECONNECT, f.tick(5_000))
    }
    @Test fun `sustained progress resets retry allowance`() {
        val f = Fixture(); f.failure(); f.tick(2_000); repeat(65) { f.tick() }
        assertEquals(0, f.budget.snapshot.value.attempts)
        f.failure(); assertEquals(RecoveryAction.LIGHTWEIGHT, f.tick(2_000))
    }
    @Test fun `replacing an observer cannot revive a terminal failure`() {
        val f = Fixture(); f.failure(RecoveryFailure.AUTHORIZATION); f.tick()
        val replacement = PlaybackRecoveryMonitor(f.budget) { f.now }
        assertEquals(RecoveryAction.FAIL, replacement.sample(RecoverySample(true, ready = true)))
        assertEquals(0, f.budget.snapshot.value.attempts)
    }

    @Test fun `manual retry starts a fresh bounded budget`() {
        val f = Fixture(); f.failure(RecoveryFailure.AUTHENTICATION); f.tick()
        f.budget.reset(); val fresh = PlaybackRecoveryMonitor(f.budget) { f.now }
        fresh.failure(RecoveryFailure.NETWORK); f.now += 2_000
        assertEquals(RecoveryAction.LIGHTWEIGHT, fresh.sample(RecoverySample(true)))
    }
    @Test fun `pause lifecycle focus loss approval and seeking do not accumulate time`() {
        val f = Fixture(); f.buffering(0); f.buffering(20_000)
        repeat(10) { assertNull(f.tick(60_000, RecoverySample(false, buffering = true))) }
        assertNull(f.buffering(1)); assertNull(f.buffering(20_000))
        assertEquals(0, f.budget.snapshot.value.attempts)
    }
    @Test fun `reset budget rejects callbacks and cancellation from the previous generation`() {
        val f = Fixture(); f.failure(); f.budget.reset()
        assertNull(f.tick(10_000)); f.monitor.cancel()
        assertEquals(RecoveryState.MONITORING, f.budget.snapshot.value.state)
        assertEquals(0, f.budget.snapshot.value.attempts)
        assertTrue(f.budget.snapshot.value.history.isEmpty())
    }

    @Test fun `channel switch and release cancel every later action`() {
        val f = Fixture(); f.failure(); f.monitor.cancel(); f.monitor.cancel()
        repeat(10) { assertNull(f.tick(100_000)) }
        assertEquals(RecoveryState.CANCELED, f.budget.snapshot.value.state)
    }
    @Test fun `permanent failures never automatically retry`() {
        RecoveryFailure.entries.filterNot { it.retryable }.forEach { category ->
            val f = Fixture(); f.failure(category)
            assertEquals(category.name, RecoveryAction.FAIL, f.tick())
            assertNull(f.tick(600_000)); assertEquals(0, f.budget.snapshot.value.attempts)
        }
    }
    @Test fun `rate limiting honors provider retry after`() {
        val f = Fixture(); f.failure(RecoveryFailure.RATE_LIMIT, 120_000)
        assertNull(f.tick(119_999)); assertEquals(RecoveryAction.LIGHTWEIGHT, f.tick(1))
    }
    @Test fun `provider retry after survives explicit retry and observer replacement on the same account`() {
        val f = Fixture(); f.budget.attachAccount("account")
        f.failure(RecoveryFailure.RATE_LIMIT, 120_000)
        f.budget.reset(); f.budget.attachAccount("account")
        val replacement = PlaybackRecoveryMonitor(f.budget) { f.now }
        replacement.failure(RecoveryFailure.NETWORK)
        f.now = 2_000; assertNull(replacement.sample(RecoverySample(true)))
        f.now = 120_000; assertEquals(RecoveryAction.LIGHTWEIGHT, replacement.sample(RecoverySample(true)))
        f.budget.attachAccount("different-authorized-account")
        assertEquals(0L, f.budget.notBeforeMs)
    }

    @Test fun `exhaustion retains the final provider cooldown for an explicit retry`() {
        val f = Fixture(); f.budget.attempts = 3
        f.failure(RecoveryFailure.RATE_LIMIT, 120_000)
        assertEquals(RecoveryAction.FAIL, f.tick())
        f.budget.reset()
        assertEquals(120_000L, f.budget.notBeforeMs)
    }

    @Test fun `unexpected ended stream gets bounded recovery`() {
        val f = Fixture(); f.tick(sample = RecoverySample(true, ended = true))
        assertEquals(RecoveryAction.LIGHTWEIGHT, f.tick(2_000, RecoverySample(true, ended = true)))
    }
    @Test fun `one failed panel cannot change another panels budget`() {
        val first = Fixture(); val second = Fixture()
        first.failure(); first.tick(2_000); repeat(5) { second.tick() }
        assertEquals(1, first.budget.snapshot.value.attempts); assertEquals(0, second.budget.snapshot.value.attempts)
    }
    @Test fun `history is fixed vocabulary memory only bounded and clearable`() {
        val budget = RecoveryBudget()
        repeat(500) { budget.record(RecoveryEvent.BUFFERING, it.toLong()) }
        val f = Fixture(budget); f.buffering()
        assertTrue(budget.snapshot.value.history.size <= 64)
        assertFalse(budget.snapshot.value.toString().contains("http"))
        budget.clearHistory(); assertTrue(budget.snapshot.value.history.isEmpty())
    }
    @Test fun `disabled diagnostics retain no recovery history`() {
        val f = Fixture(RecoveryBudget(diagnosticsEnabled = { false }))
        f.failure(); f.tick(2_000)
        assertTrue(f.budget.snapshot.value.history.isEmpty())
    }
}
