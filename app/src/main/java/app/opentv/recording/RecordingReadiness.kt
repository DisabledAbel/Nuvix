/* Copyright (C) 2026 Nuvix Contributors. GPL-3.0-or-later. */
package app.opentv.recording

import android.app.AlarmManager
import android.content.Context
import android.os.Build
import android.os.PowerManager
import androidx.core.app.NotificationManagerCompat

/** Local capability observations, never a promise of unattended execution. */
data class RecordingReadiness(
    val exactAlarmsAllowed: Boolean,
    val notificationsAllowed: Boolean,
    val batteryExempt: Boolean,
) {
    val warnings: List<String> get() = buildList {
        if (!exactAlarmsAllowed) add("Exact alarm access is unavailable; scheduled starts can be delayed or rejected in the background.")
        if (!notificationsAllowed) add("Recording notifications are disabled in system settings.")
        if (!batteryExempt) add("Device battery policy may interrupt unattended recording.")
        add("Recording requires the device to remain powered on. Android and device policy can stop or reject background capture.")
    }

    companion object {
        /** Reports device permissions, background eligibility and storage readiness for recording. */
        fun check(context: Context): RecordingReadiness {
            val alarms = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val power = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            return RecordingReadiness(
                exactAlarmsAllowed = Build.VERSION.SDK_INT < 31 || alarms.canScheduleExactAlarms(),
                notificationsAllowed = NotificationManagerCompat.from(context).areNotificationsEnabled(),
                batteryExempt = power.isIgnoringBatteryOptimizations(context.packageName),
            )
        }
    }
}
