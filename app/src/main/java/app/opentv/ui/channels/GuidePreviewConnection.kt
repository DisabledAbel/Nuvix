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

    fun beginTune(): Long {
        stop()
        return generation
    }

    fun isCurrent(attempt: Long): Boolean = attempt == generation

    fun attach(token: ReservationToken, attempt: Long): Boolean {
        if (!isCurrent(attempt)) {
            connections.release(token)
            return false
        }
        check(reservation == null)
        reservation = token
        return true
    }

    fun owns(token: ReservationToken): Boolean = reservation == token

    fun markActive() { reservation?.let(connections::markActive) }

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

    private fun releaseReservation() {
        val previous = reservation
        reservation = null
        previous?.let(connections::release)
    }
}
