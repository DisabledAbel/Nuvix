/* Copyright (C) 2026 Nuvix Contributors. GPL-3.0-or-later. */
package app.opentv.sports

import app.opentv.player.PlayerController.State
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SportsAutoSwitchoverTest {
    private val failure = State.Error("Game", "Unavailable", true)

    @Test fun failuresAndEndedStreamsTryEachChoiceOnceThenStop() = runTest {
        val tunes = mutableListOf<Long>()
        val messages = mutableListOf<String>()
        val events = mutableListOf<String>()
        val recovery = SportsAutoSwitchover(backgroundScope, { event, attempted ->
            events += event
            (1L..3L).firstOrNull { it !in attempted }?.let { sportsChannel(it) }
        }, tunes::add, messages::add)
        recovery.start("game-one", 1)
        recovery.observe(failure, allowed = true, enabled = true)
        runCurrent()
        assertEquals(listOf(2L), tunes)
        recovery.observe(State.Playing("Game"), true, true)
        recovery.observe(State.Idle, true, true)
        runCurrent()
        assertEquals(listOf(2L, 3L), tunes)
        recovery.observe(failure, true, true)
        runCurrent()
        repeat(3) { recovery.observe(failure, true, true); runCurrent() }
        assertEquals(listOf(2L, 3L), tunes)
        assertEquals(listOf("game-one", "game-one", "game-one"), events)
        assertEquals(1, messages.count { it.startsWith("No more") })
    }

    @Test fun startupAllowsTenSecondCushionAndShortStallsRecoverWithoutSwitching() = runTest {
        val tunes = mutableListOf<Long>()
        val recovery = SportsAutoSwitchover(backgroundScope, { _, _ -> sportsChannel(2) }, tunes::add, {})
        recovery.start("game", 1)
        recovery.observe(State.Idle, true, true)
        runCurrent()
        assertTrue(tunes.isEmpty())
        recovery.observe(State.Buffering("Game"), true, true)
        advanceTimeBy(10_000); runCurrent()
        assertTrue(tunes.isEmpty())
        recovery.observe(State.Playing("Game"), true, true)
        recovery.observe(State.Buffering("Game"), true, true)
        advanceTimeBy(SportsAutoSwitchover.STALL_TIMEOUT_MS - 1)
        recovery.observe(State.Playing("Game"), true, true)
        advanceTimeBy(30_000); runCurrent()
        assertTrue(tunes.isEmpty())
        recovery.observe(State.Buffering("Game"), true, true)
        advanceTimeBy(SportsAutoSwitchover.STALL_TIMEOUT_MS); runCurrent()
        assertEquals(listOf(2L), tunes)
    }

    @Test fun aStreamThatNeverStartsTimesOutAndReplacementGetsItsOwnStartupWindow() = runTest {
        val tunes = mutableListOf<Long>()
        val recovery = SportsAutoSwitchover(backgroundScope, { _, attempted ->
            sportsChannel(if (2L in attempted) 3 else 2)
        }, tunes::add, {})
        recovery.start("game", 1)
        recovery.observe(State.Buffering("Game"), true, true)
        advanceTimeBy(SportsAutoSwitchover.START_TIMEOUT_MS); runCurrent()
        assertEquals(listOf(2L), tunes)
        recovery.observe(State.Buffering("Replacement"), true, true)
        advanceTimeBy(10_000); runCurrent()
        assertEquals(listOf(2L), tunes)
        advanceTimeBy(10_000); runCurrent()
        assertEquals(listOf(2L, 3L), tunes)
    }

    @Test fun disablingPausingOrBackgroundingCancelsPendingRecovery() = runTest {
        for ((allowed, enabled) in listOf(false to true, true to false)) {
            val tunes = mutableListOf<Long>()
            val recovery = SportsAutoSwitchover(backgroundScope, { _, _ -> sportsChannel(2) }, tunes::add, {})
            recovery.start("game", 1)
            recovery.observe(State.Buffering("Game"), true, true)
            advanceTimeBy(1_000)
            recovery.observe(failure, allowed, enabled)
            advanceTimeBy(30_000); runCurrent()
            assertTrue(tunes.isEmpty())
            recovery.clear()
        }
    }

    @Test fun recoveryAllowsTheConfiguredCushionToFillBeforeTimingOut() = runTest {
        val tunes = mutableListOf<Long>()
        val recovery = SportsAutoSwitchover(backgroundScope, { _, _ -> sportsChannel(2) }, tunes::add, {})
        recovery.start("game", 1)
        recovery.observe(State.Buffering("Game"), true, true, playbackBufferMs = 30_000)
        advanceTimeBy(30_000); runCurrent()
        assertTrue(tunes.isEmpty())
        recovery.observe(State.Playing("Game"), true, true)
        recovery.observe(State.Buffering("Game"), true, true, rebufferMs = 10_000)
        advanceTimeBy(10_000); runCurrent()
        assertTrue(tunes.isEmpty())
        advanceTimeBy(5_000); runCurrent()
        assertEquals(listOf(2L), tunes)
    }

    @Test fun manualChannelSelectionCancelsEvenAResolverIgnoringCancellation() = runTest {
        val result = CompletableDeferred<Unit>()
        val tunes = mutableListOf<Long>()
        val recovery = SportsAutoSwitchover(backgroundScope, { _, _ ->
            withContext(NonCancellable) { result.await() }
            sportsChannel(2)
        }, tunes::add, {})
        recovery.start("game", 1)
        recovery.observe(failure, true, true)
        runCurrent()
        recovery.clear()
        result.complete(Unit); runCurrent()
        assertTrue(tunes.isEmpty())
        assertNull(recovery.selection.value)
    }

    @Test fun normalPlaybackAndDisabledSettingNeverLookUpAlternatives() = runTest {
        var lookups = 0
        val recovery = SportsAutoSwitchover(backgroundScope, { _, _ -> lookups++; sportsChannel(2) }, {}, {})
        recovery.observe(failure, true, true)
        recovery.start("game", 1)
        recovery.observe(failure, true, false)
        advanceTimeBy(60_000); runCurrent()
        assertEquals(0, lookups)
    }
}
