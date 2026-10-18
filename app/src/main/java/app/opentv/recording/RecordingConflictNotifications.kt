/* Nuvix Copyright (C) 2026 Nuvix Contributors. GPL-3.0-or-later. */
package app.opentv.recording

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import app.opentv.MainActivity
import com.disabledabel.nuvix.R

/** Uses the established recording notification channel; never launches a background Activity. */
object RecordingConflictNotifications {
    fun cancel(context: Context, id: Long) = NotificationManagerCompat.from(context).cancel("recording-conflict:$id", 1)

    @Synchronized
    fun show(context: Context, conflict: MultiViewRecordingConflict): Boolean {
        if (System.currentTimeMillis() >= conflict.effectiveStartMillis) return false
        val store = RecordingWarningStore(context)
        if (store.dismissed(conflict.key) || store.notified(conflict.key)) return false
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context,
                Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return false
        val manager = NotificationManagerCompat.from(context)
        if (!manager.areNotificationsEnabled()) return false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(NotificationChannel("recording_switch", context.getString(R.string.rec_switch_channel_name), NotificationManager.IMPORTANCE_HIGH))
        }
        val rec = conflict.recording
        val launch = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            data = Uri.parse("nuvix://recording/${rec.id}/review")
            putExtra(MainActivity.EXTRA_REVIEW_RECORDING, rec.id)
        }
        val action = PendingIntent.getActivity(context, 0, launch, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = NotificationCompat.Builder(context, "recording_switch")
            .setSmallIcon(R.drawable.ic_nuvix_logo).setContentTitle("Recording Starting Soon: ${rec.title}")
            .setContentText("Multi-View may pause on this account. Tap to review; recording priority applies at the deadline.")
            .setStyle(NotificationCompat.BigTextStyle().bigText("${rec.channelName}: Multi-View connections will yield if needed at the padded recording start. Other accounts continue."))
            .setContentIntent(action).setCategory(NotificationCompat.CATEGORY_EVENT).setPriority(NotificationCompat.PRIORITY_HIGH)
            .setOnlyAlertOnce(true).setAutoCancel(true)
            .setTimeoutAfter((conflict.effectiveStartMillis - System.currentTimeMillis()).coerceAtLeast(1)).build()
        return try { manager.notify("recording-conflict:${rec.id}", 1, notification); store.markNotified(conflict.key); true }
        catch (_: SecurityException) { false /* Notification access can change after the permission check. */ }
    }
}
