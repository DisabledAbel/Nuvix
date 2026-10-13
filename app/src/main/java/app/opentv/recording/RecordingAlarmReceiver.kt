/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.recording

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import app.opentv.MainActivity
import app.opentv.R
import app.opentv.core.RecordingSignals
import app.opentv.core.ServiceLocator
import app.opentv.data.model.Recording
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Fires when a booked recording is due, when its "about to switch" pre-alarm lands, or after a
 * reboot. On the due alarm it starts the capture service and — if auto-switch is on — moves the
 * live view onto the recording so a single-connection account doesn't get its live stream cut. On
 * the pre-alarm it warns the viewer 30s ahead. On boot it re-arms every still-future booking, since
 * alarms don't survive a power cycle.
 */
class RecordingAlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_LOCKED_BOOT_COMPLETED -> rescheduleAll(context)
            RecordingScheduler.ACTION_START -> {
                val id = RecordingScheduler.recordingIdFrom(intent)
                if (id > 0) onDue(context, id)
            }
            RecordingScheduler.ACTION_WARN -> {
                val id = RecordingScheduler.recordingIdFrom(intent)
                if (id > 0) onWarn(context, id)
            }
        }
    }

    /** The booking is due: start capturing, then switch the live view to it if auto-switch is on. */
    private fun onDue(context: Context, id: Long) {
        RecordingService.start(context, id)
        val pending = goAsync()
        val app = context.applicationContext
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val graph = ServiceLocator.get(app)
                if (!graph.settings.recordAutoSwitch.value) return@launch
                val rec = graph.recordingRepository.byId(id) ?: return@launch
                // Only internal recordings can be tail-watched; a NAS/USB one just records.
                if (!isTailWatchable(rec)) return@launch
                // In-app (foreground) switch: the nav graph is watching this.
                RecordingSignals.requestWatch(id, System.currentTimeMillis())
                // Screen-off / another-app switch: a full-screen intent brings OpenTV forward.
                notifySwitch(app, rec)
            } finally {
                pending.finish()
            }
        }
    }

    /** 30s out: tell the viewer the screen is about to switch (banner in-app + a heads-up card). */
    private fun onWarn(context: Context, id: Long) {
        val pending = goAsync()
        val app = context.applicationContext
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val graph = ServiceLocator.get(app)
                if (!graph.settings.recordAutoSwitch.value) return@launch
                val rec = graph.recordingRepository.byId(id) ?: return@launch
                if (!isTailWatchable(rec)) return@launch
                RecordingSignals.warn(
                    RecordingSignals.Imminent(
                        recordingId = id,
                        title = rec.title,
                        channelName = rec.channelName,
                        startAtMillis = rec.scheduledStartMillis,
                    ),
                )
                notifyWarn(app, rec)
            } finally {
                pending.finish()
            }
        }
    }

    private fun rescheduleAll(context: Context) {
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val graph = ServiceLocator.get(context)
                val autoSwitch = graph.settings.recordAutoSwitch.value
                val now = System.currentTimeMillis()
                graph.recordingRepository.scheduled().forEach { rec ->
                    if (rec.scheduledStartMillis > now) {
                        RecordingScheduler.set(context, rec.id, rec.scheduledStartMillis)
                        if (autoSwitch) RecordingScheduler.setWarning(context, rec.id, rec.scheduledStartMillis)
                    }
                }
            } finally {
                pending.finish()
            }
        }
    }

    /** A plain-file (internal) recording is the only kind the growing-file player can follow. */
    private fun isTailWatchable(rec: Recording): Boolean =
        !SmbClient.isSmb(rec.filePath) &&
            !RecordingStorage.isContent(rec.filePath) &&
            !RecordingStorage.isUsbPlaceholder(rec.filePath)

    private fun notifySwitch(context: Context, rec: Recording) {
        createChannel(context)
        val launch = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(MainActivity.EXTRA_WATCH_RECORDING, rec.id)
        }
        val pi = PendingIntent.getActivity(
            context,
            SWITCH_REQUEST_BASE + rec.id.toInt(),
            launch,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle(context.getString(R.string.rec_switch_now_title, rec.title))
            .setContentText(context.getString(R.string.rec_switch_now_text, rec.channelName))
            .setSmallIcon(R.drawable.ic_opentv_logo)
            .setCategory(NotificationCompat.CATEGORY_EVENT)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pi)
            .setFullScreenIntent(pi, true)
        safeNotify(context, NOTIFICATION_BASE + rec.id.toInt(), builder.build())
    }

    private fun notifyWarn(context: Context, rec: Recording) {
        createChannel(context)
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle(context.getString(R.string.rec_switch_soon_title, rec.title))
            .setContentText(context.getString(R.string.rec_switch_soon_text, rec.channelName))
            .setSmallIcon(R.drawable.ic_opentv_logo)
            .setCategory(NotificationCompat.CATEGORY_EVENT)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setTimeoutAfter(RecordingScheduler.WARN_LEAD_MILLIS)
        safeNotify(context, WARN_NOTIFICATION_BASE + rec.id.toInt(), builder.build())
    }

    private fun safeNotify(context: Context, id: Int, notification: Notification) {
        runCatching { NotificationManagerCompat.from(context).notify(id, notification) }
    }

    private fun createChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (manager.getNotificationChannel(CHANNEL_ID) == null) {
                manager.createNotificationChannel(
                    NotificationChannel(
                        CHANNEL_ID,
                        context.getString(R.string.rec_switch_channel_name),
                        NotificationManager.IMPORTANCE_HIGH,
                    ).apply { description = context.getString(R.string.rec_switch_channel_desc) },
                )
            }
        }
    }

    private companion object {
        const val CHANNEL_ID = "recording_switch"
        const val NOTIFICATION_BASE = 6000
        const val WARN_NOTIFICATION_BASE = 6500
        const val SWITCH_REQUEST_BASE = 7000
    }
}
