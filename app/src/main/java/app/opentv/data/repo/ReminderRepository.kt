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
    /** Observes stored program reminders. */
    fun observeAll(): Flow<List<Reminder>> = dao.observeAll()
    /** Observes program start times with reminders on the selected channel. */
    fun observeStartsForChannel(channelId: Long): Flow<List<Long>> =
        dao.observeStartsForChannel(channelId)

    /** Returns the reminder with the supplied local ID, or null if absent. */
    suspend fun byId(id: Long): Reminder? = dao.byId(id)
    /** Returns pending reminders eligible for future alarm scheduling. */
    suspend fun upcoming(nowMillis: Long): List<Reminder> = dao.upcoming(nowMillis)
    /** Finds a reminder for the channel and program start time. */
    suspend fun forProgramme(channelId: Long, startMillis: Long): Reminder? =
        dao.forProgrammeOrSports(channelId, startMillis)

    /** Persists a reminder and returns its local ID. */
    suspend fun insert(reminder: Reminder): Long = dao.insertDeduplicated(reminder)
    /** Marks a reminder as delivered so it will not be rearmed. */
    suspend fun markFired(id: Long) = dao.markFired(id)
    /** Deletes a reminder by local ID. */
    suspend fun delete(id: Long) = dao.delete(id)
    /** Deletes reminders for the channel and program start time. */
    suspend fun deleteForProgramme(channelId: Long, startMillis: Long) {
        dao.forProgrammeOrSports(channelId, startMillis)?.let { dao.delete(it.id) }
    }

    /** Prunes reminders whose program windows ended before the cutoff. */
    suspend fun deleteEndedBefore(beforeMillis: Long) = dao.deleteEndedBefore(beforeMillis)
}
