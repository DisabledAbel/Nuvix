/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.data.repo

import app.opentv.data.db.ReminderDao
import app.opentv.data.model.Reminder
import kotlinx.coroutines.flow.Flow

/** Reads and writes over the reminders table. Pure data access — the alarm wiring lives elsewhere. */
class ReminderRepository(private val dao: ReminderDao) {
    fun observeAll(): Flow<List<Reminder>> = dao.observeAll()
    fun observeStartsForChannel(channelId: Long): Flow<List<Long>> =
        dao.observeStartsForChannel(channelId)

    suspend fun byId(id: Long): Reminder? = dao.byId(id)
    suspend fun upcoming(nowMillis: Long): List<Reminder> = dao.upcoming(nowMillis)
    suspend fun forProgramme(channelId: Long, startMillis: Long): Reminder? =
        dao.forProgrammeOrSports(channelId, startMillis)

    suspend fun insert(reminder: Reminder): Long = dao.insertDeduplicated(reminder)
    suspend fun markFired(id: Long) = dao.markFired(id)
    suspend fun delete(id: Long) = dao.delete(id)
    suspend fun deleteForProgramme(channelId: Long, startMillis: Long) {
        dao.forProgrammeOrSports(channelId, startMillis)?.let { dao.delete(it.id) }
    }

    suspend fun deleteEndedBefore(beforeMillis: Long) = dao.deleteEndedBefore(beforeMillis)
}
