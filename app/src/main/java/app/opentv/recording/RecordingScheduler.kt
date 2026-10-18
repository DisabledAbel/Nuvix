/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.recording

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.net.Uri
import app.opentv.core.ServiceLocator
import app.opentv.data.model.RecordingStatus
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Books a recording to start at a wall-clock time. Uses an exact alarm so a programme that starts
 * in three hours records on the dot even if the box has dozed off in the meantime — and exact
 * alarms are on Android's short allow-list for starting a foreground service from the background,
 * which is exactly what firing a scheduled capture needs.
 */
object RecordingScheduler {

    const val ACTION_START = "app.opentv.recording.ALARM_START"
    const val ACTION_WARN = "app.opentv.recording.ALARM_WARN"
    private const val EXTRA_ID = "recordingId"
    private val warningRearm = Mutex()
    private var warningIds = emptySet<Long>()

    /** How long before a booking starts to warn the viewer that the screen is about to switch. */
    const val WARN_LEAD_MILLIS = MultiViewRecordingConflictPolicy.DEFAULT_LEAD_MILLIS

    /** Schedules the recording start alarm at the supplied trigger time. */
    fun set(context: Context, recordingId: Long, triggerAtMillis: Long) =
        arm(context, pendingIntent(context, recordingId, ACTION_START), triggerAtMillis)

    /** Cancels the recording's scheduled alarms. */
    fun cancel(context: Context, recordingId: Long) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        am.cancel(pendingIntent(context, recordingId, ACTION_START))
        cancelWarning(context, recordingId)
    }

    /** Replaces the alarm for an effective planned start without removing a delivered warning.
     * Late warning setup fires promptly while the deadline is still future. */
    fun setWarning(context: Context, recordingId: Long, startAtMillis: Long) {
        val now = System.currentTimeMillis()
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        am.cancel(pendingIntent(context, recordingId, ACTION_WARN))
        if (startAtMillis <= now) { RecordingConflictNotifications.cancel(context, recordingId); return }
        val lead = app.opentv.core.ServiceLocator.get(context).settings.recordConflictLeadMinutes.value * 60_000L
        val at = (startAtMillis - lead).coerceAtLeast(now + 1)
        arm(context, pendingIntent(context, recordingId, ACTION_WARN), at)
    }

    /** Replans warning alarms only; capture starts and padding remain owned by the DVR engine. */
    suspend fun rearmWarnings(context: Context) = warningRearm.withLock {
        val graph = ServiceLocator.get(context)
        val sources = graph.sourceRepository.enabled().associateBy { it.id }
        sources.values.forEach { graph.connections.registerSource(it) }
        val rows = (graph.recordingRepository.scheduled() + graph.recordingRepository.active()).filter { it.sourceId in sources }
        val plan = MultiViewRecordingConflictPolicy.plan(rows, graph.connections.snapshots.value, System.currentTimeMillis())
        val scheduled = rows.filter { it.status == RecordingStatus.SCHEDULED }
        val ids = scheduled.map { it.id }.toSet()
        (warningIds - ids).forEach { cancelWarning(context, it) }
        scheduled.forEach { rec ->
            val start = plan.windows[rec.id]?.startMillis
            if (start == null) cancelWarning(context, rec.id) else setWarning(context, rec.id, start)
        }
        warningIds = ids
    }

    /** Plan inputs exclude reservation churn and captured-byte updates, which cannot move starts. */
    suspend fun observeWarningPlans(context: Context) {
        val graph = ServiceLocator.get(context)
        combine(
            graph.recordingRepository.observeAll().map { rows -> rows.filter { it.status in setOf(
                RecordingStatus.SCHEDULED, RecordingStatus.PREPARING, RecordingStatus.RECORDING, RecordingStatus.FINALIZING,
            ) }.map { it.copy(sizeBytes = 0) } }.distinctUntilChanged(),
            graph.sourceRepository.observeAll(),
            graph.connections.snapshots.map { accounts -> accounts.map { Triple(it.accountKey, it.sourceIds, it.policy) } }.distinctUntilChanged(),
            graph.settings.recordConflictLeadMinutes,
        ) { _, _, _, _ -> Unit }.collect { rearmWarnings(context) }
    }

    /** Cancels the imminent-start warning alarm for a recording. */
    fun cancelWarning(context: Context, recordingId: Long) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        am.cancel(pendingIntent(context, recordingId, ACTION_WARN))
        RecordingConflictNotifications.cancel(context, recordingId)
    }

    /** Registers an alarm using the available exact or fallback scheduling capability. */
    private fun arm(context: Context, pending: PendingIntent, triggerAtMillis: Long) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val canExact = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || am.canScheduleExactAlarms()
        try {
            if (canExact) {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pending)
            } else {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pending)
            }
        } catch (_: SecurityException) {
            // Exact-alarm permission was revoked — fall back to an inexact wake, still better
            // than nothing (it may fire a little late but the capture still runs).
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pending)
        }
    }

    /** Reads the recording ID from the alarm intent, returning -1 when absent. */
    fun recordingIdFrom(intent: Intent): Long = intent.getLongExtra(EXTRA_ID, -1L)

    /** Builds an explicit recording alarm intent with a stable recording identity. */
    private fun intentFor(context: Context, recordingId: Long, action: String): Intent =
        Intent(context, RecordingAlarmReceiver::class.java).apply {
            this.action = action
            // Intent identity includes the full Long ID; requestCode alone truncates to 32 bits.
            data = Uri.parse("nuvix://recording/$recordingId/$action")
            putExtra(EXTRA_ID, recordingId)
        }

    /** Builds the immutable PendingIntent used to schedule or cancel a recording alarm. */
    private fun pendingIntent(context: Context, recordingId: Long, action: String): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            recordingId.toInt(),
            intentFor(context, recordingId, action),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
}
