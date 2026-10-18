/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.data.repo

import app.opentv.data.db.RecordingDao
import app.opentv.data.db.SeriesRuleDao
import app.opentv.data.db.RecordingEventDao
import app.opentv.data.model.Recording
import app.opentv.data.model.RecordingEvent
import app.opentv.data.model.RecordingStatus
import app.opentv.data.model.SeriesRule
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File

/** Reads and writes over the recordings + series-rule tables. Pure data access, no capture logic. */
class RecordingRepository(
    private val dao: RecordingDao,
    private val ruleDao: SeriesRuleDao,
    private val events: RecordingEventDao? = null,
) {
    private val reconciliation = Mutex()
    private var reconciled = false
    /** Observes the recording library as stored recording rows change. */
    fun observeAll(): Flow<List<Recording>> = dao.observeAll()
    /** Observes recordings preparing, capturing or finalizing. */
    fun observeActive(): Flow<List<Recording>> = dao.observeActive()
    /** Observes saved series recording rules. */
    fun observeRules(): Flow<List<SeriesRule>> = ruleDao.observeAll()

    /** Returns a recording by local ID, or null if absent. */
    suspend fun byId(id: Long): Recording? = dao.byId(id)
    /** Returns all stored recordings. */
    suspend fun all(): List<Recording> = dao.all()
    /** Returns the recording associated with a stored output path. */
    suspend fun byFilePath(filePath: String): Recording? = dao.byFilePath(filePath)
    /** Returns recordings preparing, capturing or finalizing. */
    suspend fun active(): List<Recording> = dao.active()
    /** Returns pending recordings in scheduled start order. */
    suspend fun scheduled(): List<Recording> = dao.scheduled()

    /** Persists a new recording and returns its local row ID. */
    suspend fun insert(recording: Recording): Long = dao.insert(recording)
    /** Persists changes to an existing recording row. */
    suspend fun update(recording: Recording) = dao.update(recording)

    /** Atomically claims a scheduled recording for preparation to prevent duplicate capture starts. */
    suspend fun claimScheduled(id: Long, atMillis: Long): Boolean {
        failInterrupted()
        return dao.claimScheduled(id, atMillis) == 1
    }

    /** Claims an active recording for finalization and reports whether the claim succeeded. */
    suspend fun beginFinalizing(id: Long) = dao.beginFinalizing(id)

    /** Changes an unlocked pending recording's capture window. */
    suspend fun setScheduledWindow(id: Long, startMillis: Long, endMillis: Long) =
        dao.setScheduledWindow(id, startMillis, endMillis)

    /** Persists the recording's independent protection and padding-lock choices. */
    suspend fun setProtection(id: Long, protectedRecording: Boolean, lockPadding: Boolean) =
        dao.setProtection(id, protectedRecording, lockPadding)

    /** Updates a pending recording's padding and derived capture window. */
    suspend fun setPadding(id: Long, startMinutes: Int, endMinutes: Int, startMillis: Long, endMillis: Long) =
        dao.setPadding(id, startMinutes, endMinutes, startMillis, endMillis)

    /** Cancels a recording only while it is still scheduled. */
    suspend fun cancelScheduled(id: Long): Boolean = dao.cancelScheduled(id) == 1

    /** Updates status only while the recording remains scheduled. */
    suspend fun setScheduledStatus(id: Long, status: RecordingStatus, error: String?): Boolean =
        dao.setScheduledStatus(id, status, error) == 1

    /** Prepares an eligible recording for retry while retaining its original booking choices. */
    suspend fun retryPreserving(id: Long, filePath: String, archive: Recording?): Boolean =
        dao.retryPreserving(id, filePath, archive)

    /** Appends a durable recording activity or policy-decision event. */
    suspend fun event(id: Long, kind: String, message: String) {
        events?.insert(RecordingEvent(recordingId = id, atMillis = System.currentTimeMillis(), kind = kind, message = message))
    }

    /** Observes the recording's chronological activity history. */
    fun observeEvents(id: Long): Flow<List<RecordingEvent>>? = events?.observeForRecording(id)

    /** Records capture start after preparation has been claimed. */
    suspend fun markStarted(id: Long, atMillis: Long) =
        dao.markStarted(id, RecordingStatus.RECORDING, atMillis)

    /** Persists the captured-byte count. */
    suspend fun setSize(id: Long, bytes: Long) = dao.setSize(id, bytes)

    /** Repoint a recording at its real locator (a USB capture's content:// URI, once created). */
    suspend fun setFilePath(id: Long, filePath: String) = dao.setFilePath(id, filePath)

    /** Persists terminal status, completion time, final byte count and any error. */
    suspend fun markFinished(id: Long, ok: Boolean, atMillis: Long, bytes: Long, error: String?) =
        dao.markFinished(
            id = id,
            status = if (ok) RecordingStatus.COMPLETED else if (bytes > 0) RecordingStatus.PARTIAL else RecordingStatus.FAILED,
            endedAt = atMillis,
            bytes = bytes,
            error = error,
        )

    /** Updates recording status and optional failure text. */
    suspend fun setStatus(id: Long, status: RecordingStatus, error: String? = null) =
        dao.setStatus(id, status, error)

    /** Deletes the stored recording row. */
    suspend fun delete(id: Long) {
        dao.delete(id)
        events?.deleteForRecording(id)
    }

    /** Cold-start reconciliation: anything left "recording" was killed mid-capture. */
    suspend fun failInterrupted() = reconciliation.withLock {
        if (reconciled) return@withLock
        // Every capture waits for this barrier before claiming a row. App startup and an alarm
        // therefore cannot mark a newly started capture interrupted in a racing coroutine.
        for (recording in dao.active()) {
            val file = File(recording.filePath)
            val bytes = if (file.isAbsolute && file.isFile) file.length() else recording.sizeBytes
            dao.markFinished(recording.id, if (bytes > 0) RecordingStatus.PARTIAL else RecordingStatus.FAILED,
                System.currentTimeMillis(), bytes, "Interrupted; available captured content preserved")
            event(recording.id, "INTERRUPTED", "Capture interrupted; available content preserved")
        }
        reconciled = true
    }

    // ---- Series-link rules -------------------------------------------------------------------

    /** Persists a series recording rule and returns its local ID. */
    suspend fun addRule(rule: SeriesRule): Long = ruleDao.insert(rule)
    /** Removes a saved series recording rule. */
    suspend fun deleteRule(id: Long) = ruleDao.delete(id)
    /** Returns enabled series recording rules for scheduling. */
    suspend fun enabledRules(): List<SeriesRule> = ruleDao.enabled()
    /** Looks up a rule by channel and normalized program title. */
    suspend fun ruleForChannelTitle(channelId: Long, titleKey: String): SeriesRule? =
        ruleDao.forChannelTitle(channelId, titleKey)

    /** Checks whether the channel and program window already have a recording booking. */
    suspend fun alreadyBooked(ruleId: Long, channelId: Long, startMillis: Long): Boolean =
        dao.countForRuleAt(ruleId, channelId, startMillis) > 0

    /** An existing live/scheduled booking for this exact airing (channel + start), or null. */
    suspend fun bookingAt(channelId: Long, startMillis: Long): Recording? =
        dao.bookingAt(channelId, startMillis)
}
