/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.reminders

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build

/**
 * Books a programme reminder to fire at its start time.
 *
 * Same shape as the recording scheduler: an exact alarm so the bell rings on the dot even if the
 * box has dozed, falling back to an inexact wake if the exact-alarm permission was revoked. The
 * alarm targets [ReminderAlarmReceiver], which posts the notification (and, for an auto-tune
 * reminder, switches the channel).
 */
object ReminderScheduler {

    const val ACTION_REMIND = "app.opentv.reminder.ALARM_REMIND"
    private const val EXTRA_ID = "reminderId"

    /** Schedules the reminder using exact alarms when permitted and a fallback otherwise. */
    fun set(context: Context, reminderId: Long, triggerAtMillis: Long) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pending = pendingIntent(context, reminderId)
        val canExact = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || am.canScheduleExactAlarms()
        try {
            if (canExact) {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pending)
            } else {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pending)
            }
        } catch (_: SecurityException) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pending)
        }
    }

    /** Cancels the scheduled alarm for a reminder. */
    fun cancel(context: Context, reminderId: Long) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        am.cancel(pendingIntent(context, reminderId))
    }

    /** Reads the reminder ID from the alarm intent, returning -1 when absent. */
    fun reminderIdFrom(intent: Intent): Long = intent.getLongExtra(EXTRA_ID, -1L)

    /** Builds an explicit reminder alarm intent with a stable reminder identity. */
    private fun intentFor(context: Context, reminderId: Long): Intent =
        Intent(context, ReminderAlarmReceiver::class.java).apply {
            action = ACTION_REMIND
            putExtra(EXTRA_ID, reminderId)
        }

    /** Builds the immutable PendingIntent used to schedule or cancel a reminder alarm. */
    private fun pendingIntent(context: Context, reminderId: Long): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            // Offset so a reminder id and a recording id that happen to be equal never share a
            // PendingIntent slot (the target receiver differs too, but this makes it obvious).
            (reminderId + REQUEST_OFFSET).toInt(),
            intentFor(context, reminderId),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    private const val REQUEST_OFFSET = 1_000_000L
}
