/* Nuvix modifications Copyright (C) 2026 Nuvix Contributors. GPL-3.0-or-later. */
/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.recording

import android.Manifest
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat

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
import com.disabledabel.nuvix.R
import app.opentv.core.RecordingSignals
import app.opentv.core.ServiceLocator
import app.opentv.data.model.Recording
import app.opentv.data.model.RecordingStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Fires when a booked recording is due, when its "about to switch" pre-alarm lands, or after a
 * reboot. On the due alarm it starts the capture service and — if auto-switch is on — moves the
 * live view onto the recording so a single-connection account doesn't get its live stream cut. On
 * the pre-alarm it reevaluates viewing conflicts at the configured lead time. On boot it re-arms every still-future booking, since
 * alarms don't survive a power cycle.
 */
class RecordingAlarmReceiver : BroadcastReceiver() {

    /** Dispatches recording alarms and system rearm events to background recording work. */
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED -> rescheduleAll(context)
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
        val pending = goAsync()
        val app = context.applicationContext
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val graph = ServiceLocator.get(app)
                val rec = graph.recordingRepository.byId(id) ?: return@launch
                if (rec.status != RecordingStatus.SCHEDULED) return@launch
                RecordingConflictNotifications.cancel(app, id)
                val now = System.currentTimeMillis()
                if (rec.scheduledEndMillis > 0 && now >= rec.scheduledEndMillis) {
                    graph.recordingRepository.setScheduledStatus(id, RecordingStatus.MISSED, "Recording window expired before alarm delivery")
                    graph.recordingRepository.event(id, "MISSED", "Recording window expired before alarm delivery")
                    return@launch
                }
                if (rec.scheduledStartMillis > now + 1_000L) {
                    RecordingScheduler.set(app, id, rec.scheduledStartMillis)
                    return@launch
                }
                try { RecordingService.start(app, id) }
                catch (_: IllegalStateException) {
                    graph.recordingRepository.setScheduledStatus(id, RecordingStatus.FAILED, "Android rejected a background recording start; review DVR readiness")
                    graph.recordingRepository.event(id, "FAILED", "Android rejected the background service start")
                    return@launch
                } catch (_: SecurityException) {
                    graph.recordingRepository.setScheduledStatus(id, RecordingStatus.FAILED, "Recording service permission is unavailable")
                    return@launch
                }
            } finally {
                pending.finish()
            }
        }
    }

    /** Revalidate the current booking/account state before notifying; never start UI in the background. */
    private fun onWarn(context: Context, id: Long) {
        val pending = goAsync()
        val app = context.applicationContext
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val graph = ServiceLocator.get(app)
                val rec = graph.recordingRepository.byId(id) ?: return@launch
                if (rec.status != RecordingStatus.SCHEDULED) return@launch
                val now = System.currentTimeMillis()
                val available = graph.sourceRepository.enabled().associateBy { it.id }
                available.values.forEach { graph.connections.registerSource(it) }
                val rows = (graph.recordingRepository.scheduled() + graph.recordingRepository.active()).filter { it.sourceId in available }
                val start = MultiViewRecordingConflictPolicy.plan(rows, graph.connections.snapshots.value, now).windows[id]?.startMillis
                if (start == null || start <= now) {
                    RecordingScheduler.cancelWarning(app, id)
                    return@launch
                }
                val lead = graph.settings.recordConflictLeadMinutes.value * 60_000L
                if (start - now > lead) {
                    RecordingScheduler.setWarning(app, id, start)
                    return@launch
                }
                MultiViewRecordingConflictPolicy.detect(rows, graph.connections.snapshots.value, now, lead)
                    .firstOrNull { it.recording.id == id }?.let { conflict ->
                        if (RecordingConflictNotifications.show(app, conflict)) {
                            graph.recordingRepository.event(id, "VIEWING_WARNING_ALARM", "Recording warning delivered; deadline fallback remains independent of notification visibility")
                        }
                        return@launch // This conflict replaces the optional auto-navigation warning.
                    }
                if (!graph.settings.recordAutoSwitch.value || !isTailWatchable(rec)) return@launch
                RecordingSignals.warn(
                    RecordingSignals.Imminent(
                        recordingId = id,
                        title = rec.title,
                        channelName = rec.channelName,
                        startAtMillis = start,
                    ),
                )
                notifyWarn(app, rec, start)
            } finally {
                pending.finish()
            }
        }
    }

    /** Rearms pending recording alarms after boot or scheduling changes. */
    private fun rescheduleAll(context: Context) {
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val graph = ServiceLocator.get(context)
                graph.recordingEngine.rearmScheduled()
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

    /** Posts the user-visible action for switching to an imminent recording. */
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
            .setSmallIcon(R.drawable.ic_nuvix_logo)
            .setCategory(NotificationCompat.CATEGORY_EVENT)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pi)
        safeNotify(context, NOTIFICATION_BASE + rec.id.toInt(), builder.build())
    }

    /** Posts an imminent recording warning when notifications are allowed. */
    private fun notifyWarn(context: Context, rec: Recording, startAtMillis: Long) {
        createChannel(context)
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle(context.getString(R.string.rec_switch_soon_title, rec.title))
            .setContentText(context.getString(R.string.rec_switch_soon_text, rec.channelName))
            .setSmallIcon(R.drawable.ic_nuvix_logo)
            .setCategory(NotificationCompat.CATEGORY_EVENT)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setTimeoutAfter((startAtMillis - System.currentTimeMillis()).coerceAtLeast(1))
        safeNotify(context, WARN_NOTIFICATION_BASE + rec.id.toInt(), builder.build())
    }

    /** Posts a recording notification while tolerating unavailable notification permission. */
    private fun safeNotify(context: Context, id: Int, notification: Notification) {
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(
                context, Manifest.permission.POST_NOTIFICATIONS,
            ) != PackageManager.PERMISSION_GRANTED) return
        try { NotificationManagerCompat.from(context).notify(id, notification) }
        catch (_: SecurityException) { /* Permission may change between checking and posting. */ }
    }

    /** Creates the recording notification channel on supported Android versions. */
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

    companion object {
        /** Called once, only after valid media bytes actually reach the capture destination. */
        internal fun notifyCaptureStarted(context: Context, recording: Recording) {
            val graph = ServiceLocator.get(context)
            val receiver = RecordingAlarmReceiver()
            if (!graph.settings.recordAutoSwitch.value || !receiver.isTailWatchable(recording)) return
            RecordingSignals.requestWatch(recording.id, System.currentTimeMillis())
            receiver.notifySwitch(context, recording)
        }

        private const val CHANNEL_ID = "recording_switch"
        private const val NOTIFICATION_BASE = 6000
        private const val WARN_NOTIFICATION_BASE = 6500
        private const val SWITCH_REQUEST_BASE = 7000
    }
}
