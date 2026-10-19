/* Copyright (C) 2026 Nuvix Contributors. GPL-3.0-or-later. */
package app.opentv.recording

import app.opentv.data.model.Recording
import app.opentv.data.model.RecordingStatus
import kotlinx.coroutines.delay

/** Polling uses durable UTC ends; stale callbacks must claim their revision before touching I/O. */
internal class RecordingDeadlineWatcher(
    private val read: suspend () -> Recording?,
    private val claim: suspend (Long, Long) -> Boolean,
    private val elapsed: () -> Unit,
    private val now: () -> Long = System::currentTimeMillis,
    private val wait: suspend (Long) -> Unit = { delay(it) },
) {
    suspend fun run() {
        while (true) {
            val row = read() ?: return
            if (row.status !in setOf(RecordingStatus.PREPARING, RecordingStatus.RECORDING)) return
            val at = now()
            if (row.scheduledEndMillis > 0 && row.scheduledEndMillis <= at && claim(row.durationRevision, at)) {
                elapsed()
                return
            }
            wait(250)
        }
    }
}
