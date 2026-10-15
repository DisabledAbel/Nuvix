/* Copyright (C) 2026 Nuvix Contributors. GPL-3.0-or-later. */
package app.opentv.player

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ChannelTuneRequestsTest {
    @Test fun `superseded provider resolutions never submit old playback requests`() = runTest {
        val requests = ChannelTuneRequests(this)
        val played = mutableListOf<Long>()
        requests.submit(2, 1) { delay(1_000); currentCoroutineContext().ensureActive(); played += 2 }
        runCurrent()
        requests.submit(3, 1) { delay(100); currentCoroutineContext().ensureActive(); played += 3 }
        advanceUntilIdle()
        assertThat(played).containsExactly(3L)
        assertThat(requests.pendingId).isNull()
    }

    @Test fun `repeated select and selecting the playing channel never restart it`() = runTest {
        val requests = ChannelTuneRequests(this)
        var played = 0
        requests.submit(1, 1) { played++ }
        requests.submit(2, 1) { delay(100); played++ }
        requests.submit(2, 1) { played++ }
        advanceUntilIdle()
        assertThat(played).isEqualTo(1)
    }

    @Test fun `confirming current channel cancels an older pending switch`() = runTest {
        val requests = ChannelTuneRequests(this)
        var played = 0
        requests.submit(2, 1) { delay(100); played++ }
        runCurrent()
        requests.submit(1, 1) { played++ }
        advanceUntilIdle()
        assertThat(played).isEqualTo(0)
    }

    @Test fun `returning quickly to an earlier requested id keeps newest request pending`() = runTest {
        val requests = ChannelTuneRequests(this)
        requests.submit(2, 1) { delay(1_000) }
        runCurrent()
        requests.submit(3, 1) { delay(1_000) }
        requests.submit(2, 1) { delay(1_000) }
        runCurrent()
        assertThat(requests.pendingId).isEqualTo(2)
        requests.cancel()
        advanceUntilIdle()
        assertThat(requests.pendingId).isNull()
    }
}
