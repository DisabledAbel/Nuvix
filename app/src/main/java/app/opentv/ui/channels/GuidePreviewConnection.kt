/* Nuvix modifications Copyright (C) 2026 Nuvix Contributors. GPL-3.0-or-later. */
package app.opentv.ui.channels

import app.opentv.connections.AccountConnectionManager
import app.opentv.connections.ReservationToken
import java.util.UUID

/** Main-thread ownership for the optional guide player; asynchronous resolves cannot revive it. */
internal class GuidePreviewConnection(
    private val connections: AccountConnectionManager,
    private val stopPlayback: () -> Unit,
) {
    val sessionId: String = "guide-preview-${UUID.randomUUID()}"
    private var generation = 0L
    private var reservation: ReservationToken? = null

    /** Starts a new preview generation and invalidates stale tune ownership. */
    fun beginTune(): Long {
        stop()
        return generation
    }

    /** Checks whether a callback belongs to the latest preview tune generation. */
    fun isCurrent(attempt: Long): Boolean = attempt == generation

    /** Associates a reserved connection token with the current preview generation. */
    fun attach(token: ReservationToken, attempt: Long): Boolean {
        if (!isCurrent(attempt)) {
            connections.release(token)
            return false
        }
        check(reservation == null)
        reservation = token
        return true
    }

    /** Checks whether the preview still owns the supplied connection token. */
    fun owns(token: ReservationToken): Boolean = reservation == token

    /** Marks the current preview reservation active after its connection opens. */
    fun markActive() { reservation?.let(connections::markActive) }

    /** Invalidates the preview generation and releases its connection ownership. */
    fun stop(attempt: Long? = null) {
        if (attempt != null && !isCurrent(attempt)) return
        generation++
        reservation?.let(connections::beginRelease)
        stopPlayback()
        releaseReservation()
    }

    /** Called by the live owner only after its synchronous PlayerController.release(). */
    fun onPlaybackReleased() {
        generation++
        releaseReservation()
    }

    /** Releases a tracked preview reservation after the associated connection closes. */
    private fun releaseReservation() {
        val previous = reservation
        reservation = null
        previous?.let(connections::release)
    }
}
