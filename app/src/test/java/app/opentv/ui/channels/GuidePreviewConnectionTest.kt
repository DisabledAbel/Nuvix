/* Nuvix modifications Copyright (C) 2026 Nuvix Contributors. GPL-3.0-or-later. */
package app.opentv.ui.channels

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.opentv.connections.AccountConnectionManager
import app.opentv.connections.ConnectionPurpose
import app.opentv.connections.ConnectionRequest
import app.opentv.connections.ReservationResult
import app.opentv.connections.ReservationState
import app.opentv.connections.ReservationToken
import app.opentv.data.model.Source
import app.opentv.data.model.SourceKind
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Regression tests for guide preview connection. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class GuidePreviewConnectionTest {
    private lateinit var connections: AccountConnectionManager

    /** Creates isolated fixtures before each test. */
    @Before fun prepare() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences("nuvix_connection_settings", Context.MODE_PRIVATE).edit().clear().commit()
        connections = AccountConnectionManager.createForTest(context)
        connections.registerSource(Source(id = 2, name = "Provider", kind = SourceKind.M3U,
            url = "https://provider.invalid/authorized-playlist.m3u"))
        connections.setMaximumConnections(2, 1)
    }

    /** Reserves fixture connection capacity and returns the successfully admitted ownership token. */
    private fun reserve(owner: GuidePreviewConnection, generation: Long): ReservationToken =
        (connections.reserve(ConnectionRequest(owner.sessionId, generation, 2, ConnectionPurpose.PREVIEW))
            as ReservationResult.Reserved).token

    /** Checks: replacement keeps old reservation counted until playback has stopped. */
    @Test fun `replacement keeps old reservation counted until playback has stopped`() {
        var firstToken: ReservationToken? = null
        var observedStateDuringStop: ReservationState? = null
        val owner = GuidePreviewConnection(connections) {
            firstToken?.let { observedStateDuringStop = connections.reservationState(it) }
        }
        val firstGeneration = owner.beginTune()
        val first = reserve(owner, firstGeneration)
        firstToken = first
        assertTrue(owner.attach(first, firstGeneration))
        owner.markActive()

        val replacementGeneration = owner.beginTune()

        assertEquals(ReservationState.RELEASING, observedStateDuringStop)
        assertEquals(ReservationState.RELEASED, connections.reservationState(first))
        assertEquals(0, connections.snapshotForSource(2)!!.locallyReserved)
        val replacement = reserve(owner, replacementGeneration)
        assertTrue(owner.attach(replacement, replacementGeneration))
        assertEquals(1, connections.snapshotForSource(2)!!.locallyReserved)
        owner.stop()
    }

    /** Checks: canceled old resolve cannot stop or release a replacement. */
    @Test fun `canceled old resolve cannot stop or release a replacement`() {
        var stops = 0
        val owner = GuidePreviewConnection(connections) { stops++ }
        val firstGeneration = owner.beginTune()
        val first = reserve(owner, firstGeneration)
        owner.attach(first, firstGeneration)
        val secondGeneration = owner.beginTune()
        val replacement = reserve(owner, secondGeneration)
        owner.attach(replacement, secondGeneration)
        val stopsBeforeStaleCallbacks = stops

        owner.stop(firstGeneration)
        assertFalse(owner.attach(first, firstGeneration))

        assertEquals(stopsBeforeStaleCallbacks, stops)
        assertTrue(owner.owns(replacement))
        assertEquals(ReservationState.PENDING, connections.reservationState(replacement))
        assertEquals(1, connections.snapshotForSource(2)!!.locallyReserved)
        owner.stop()
    }

    /** Checks: external player release invalidates in-flight resolve and releases once. */
    @Test fun `external player release invalidates in-flight resolve and releases once`() {
        val owner = GuidePreviewConnection(connections) { fail("Player was already released by its owner") }
        // beginTune calls stopPlayback; there is no player to stop in this externally-owned case.
        val generation = 0L
        val token = reserve(owner, generation)
        assertTrue(owner.attach(token, generation))

        owner.onPlaybackReleased()
        owner.onPlaybackReleased()

        assertFalse(owner.isCurrent(generation))
        assertFalse(owner.owns(token))
        assertEquals(ReservationState.RELEASED, connections.reservationState(token))
        assertEquals(0, connections.snapshotForSource(2)!!.locallyReserved)
    }
}
