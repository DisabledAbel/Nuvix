/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.data.repo

import app.opentv.data.db.RecordingDao
import app.opentv.data.db.SeriesRuleDao
import app.opentv.data.model.Recording
import app.opentv.data.model.RecordingStatus
import app.opentv.data.model.SeriesRule
import kotlinx.coroutines.flow.Flow

/** Reads and writes over the recordings + series-rule tables. Pure data access, no capture logic. */
class RecordingRepository(
    private val dao: RecordingDao,
    private val ruleDao: SeriesRuleDao,
) {
    fun observeAll(): Flow<List<Recording>> = dao.observeAll()
    fun observeActive(): Flow<List<Recording>> = dao.observeActive()
    fun observeRules(): Flow<List<SeriesRule>> = ruleDao.observeAll()

    suspend fun byId(id: Long): Recording? = dao.byId(id)
    suspend fun all(): List<Recording> = dao.all()
    suspend fun byFilePath(filePath: String): Recording? = dao.byFilePath(filePath)
    suspend fun active(): List<Recording> = dao.active()
    suspend fun scheduled(): List<Recording> = dao.scheduled()

    suspend fun insert(recording: Recording): Long = dao.insert(recording)
    suspend fun update(recording: Recording) = dao.update(recording)

    suspend fun markStarted(id: Long, atMillis: Long) =
        dao.markStarted(id, RecordingStatus.RECORDING, atMillis)

    suspend fun setSize(id: Long, bytes: Long) = dao.setSize(id, bytes)

    /** Repoint a recording at its real locator (a USB capture's content:// URI, once created). */
    suspend fun setFilePath(id: Long, filePath: String) = dao.setFilePath(id, filePath)

    suspend fun markFinished(id: Long, ok: Boolean, atMillis: Long, bytes: Long, error: String?) =
        dao.markFinished(
            id = id,
            status = if (ok) RecordingStatus.COMPLETED else RecordingStatus.FAILED,
            endedAt = atMillis,
            bytes = bytes,
            error = error,
        )

    suspend fun setStatus(id: Long, status: RecordingStatus, error: String? = null) =
        dao.setStatus(id, status, error)

    suspend fun delete(id: Long) = dao.delete(id)

    /** Cold-start reconciliation: anything left "recording" was killed mid-capture. */
    suspend fun failInterrupted() = dao.failInterrupted()

    // ---- Series-link rules -------------------------------------------------------------------

    suspend fun addRule(rule: SeriesRule): Long = ruleDao.insert(rule)
    suspend fun deleteRule(id: Long) = ruleDao.delete(id)
    suspend fun enabledRules(): List<SeriesRule> = ruleDao.enabled()
    suspend fun ruleForChannelTitle(channelId: Long, titleKey: String): SeriesRule? =
        ruleDao.forChannelTitle(channelId, titleKey)

    suspend fun alreadyBooked(ruleId: Long, channelId: Long, startMillis: Long): Boolean =
        dao.countForRuleAt(ruleId, channelId, startMillis) > 0

    /** An existing live/scheduled booking for this exact airing (channel + start), or null. */
    suspend fun bookingAt(channelId: Long, startMillis: Long): Recording? =
        dao.bookingAt(channelId, startMillis)
}
