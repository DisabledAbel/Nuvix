/* Nuvix modifications Copyright (C) 2026 Nuvix Contributors. GPL-3.0-or-later. */
/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.recording

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import app.opentv.connections.AccountConnectionManager
import app.opentv.connections.ConnectionPurpose
import app.opentv.connections.ConnectionRequest
import app.opentv.connections.ReservationResult
import app.opentv.connections.ReservationToken
import app.opentv.data.model.RecordingStatus
import com.disabledabel.nuvix.R
import app.opentv.core.ServiceLocator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import okhttp3.Call
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * A foreground service that captures live streams to storage. Recording has to keep going when the
 * user backs out of the player, opens another app, or the screen sleeps — a foreground service
 * with a wake lock is what keeps the process (and the socket) alive to do that.
 *
 * Each recording is one coroutine copying bytes from the stream to the chosen [RecordingStorage]
 * sink. Stopping cancels the coroutine and the HTTP call so a blocking read unblocks at once.
 */
class RecordingService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val captures = ConcurrentHashMap<Long, Capture>()
    private var wakeLock: PowerManager.WakeLock? = null
    private var lastStartId = 0

    private class Capture {
        @Volatile var call: Call? = null
        @Volatile var userStopped = false
        @Volatile var stopReason: String? = null
        lateinit var job: Job
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        runningService = this
        ServiceCompat.startForeground(this, NOTIFICATION_ID, buildNotification(0),
            if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0)
    }

    @Synchronized
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        lastStartId = startId
        when (intent?.action) {
            ACTION_START -> {
                val id = intent.getLongExtra(EXTRA_ID, -1L)
                if (id > 0) startCapture(id)
            }
            ACTION_STOP -> {
                val id = intent.getLongExtra(EXTRA_ID, -1L)
                stopCapture(id)
            }
            ACTION_STOP_ALL -> captures.keys.toList().forEach { stopCapture(it) }
        }
        if (captures.isEmpty() && intent?.action != ACTION_START) stopSelfSafely()
        return START_NOT_STICKY
    }

    @Synchronized
    private fun startCapture(id: Long) {
        if (captures.containsKey(id)) return
        acquireWakeLock()
        val capture = Capture()
        capture.job = scope.launch(start = CoroutineStart.LAZY) { runCapture(id, capture) }
        captures[id] = capture
        // Registration precedes launch, and completion retires even a job cancelled before dispatch.
        capture.job.invokeOnCompletion { finish(id, capture) }
        updateNotification()
        capture.job.start()
    }

    private suspend fun runCapture(id: Long, capture: Capture) {
        val graph = ServiceLocator.get(applicationContext)
        val repo = graph.recordingRepository
        var claimed = false
        var total = 0L
        var error: String? = null
        var sink: RecordingStorage.Sink? = null
        var token: ReservationToken? = null
        var late = false
        var programmeEnd = 0L
        val connections = AccountConnectionManager.get(applicationContext)
        try {
            repo.failInterrupted()
            if (!graph.recordingEngine.prepareDue(id)) return
            claimed = repo.claimScheduled(id, System.currentTimeMillis())
            if (!claimed) return // Duplicate alarm, canceled booking, or already finished capture.
            val recording = repo.byId(id) ?: return
            val now = System.currentTimeMillis()
            if (recording.scheduledEndMillis > 0 && now >= recording.scheduledEndMillis) {
                repo.setStatus(id, RecordingStatus.MISSED, "Recording window ended before capture could start")
                repo.event(id, "MISSED", "Recording window ended before capture could start")
                claimed = false
                return
            }
            val programmeStart = recording.programmeStartMillis.takeIf { it > 0 }
                ?: recording.scheduledStartMillis.takeIf { recording.scheduledEndMillis > 0 } ?: 0L
            programmeEnd = recording.programmeEndMillis.takeIf { it > 0 } ?: recording.scheduledEndMillis
            late = programmeStart > 0 && now > programmeStart
            graph.sourceRepository.byId(recording.sourceId)?.let { connections.registerSource(it) }
            val request = ConnectionRequest("recording:$id", generations.incrementAndGet(), recording.sourceId, ConnectionPurpose.RECORDING)
            when (val reservation = connections.reserveForRecording(request, allowInterruptLive = true)) {
                is ReservationResult.Reserved -> token = reservation.token
                is ReservationResult.ApprovalRequired -> {
                    connections.discardChallenge(reservation.challenge)
                    error = "Account allowance is unknown; an additional recording connection needs confirmation"
                    return
                }
                is ReservationResult.Denied -> { error = reservation.reason; return }
            }
            repo.event(id, "PROVIDER_SELECTED", "Authorized provider selected; account connection reserved")
            RecordingStorage.checkHeadroom(recording.filePath,
                RecordingStorage.MIN_FREE_BYTES * captures.size.coerceAtLeast(1))
            sink = RecordingStorage.openSink(applicationContext, graph.settings, recording.filePath)
            var actualPath = recording.filePath
            sink.resolvedLocator?.let { actual ->
                actualPath = actual
                if (actual != recording.filePath) repo.setFilePath(id, actual)
            }
            RecordingLiveState.markActive(id, actualPath)
            val result = RecordingCapture(graph.streamingHttpClient).copy(
                recording.streamUrl, recording.userAgent, sink.output, recording.scheduledEndMillis,
                onCall = { call -> capture.call = call; if (!capture.job.isActive) call?.cancel() },
                onProgress = { bytes ->
                    total = bytes
                    withContext(NonCancellable) { repo.setSize(id, bytes) }
                },
                checkStorage = RecordingStorage.storageMonitor(actualPath),
                onStarted = {
                    val firstByteAt = System.currentTimeMillis()
                    late = programmeStart > 0 && firstByteAt > programmeStart
                    repo.markStarted(id, firstByteAt)
                    repo.event(id, "STARTED", if (late) "Capture started late; remaining content will be Partial" else "Capture started")
                    connections.markActive(requireNotNull(token))
                    // A disabled notification or an unavailable Activity must not stop capture.
                    runCatching { RecordingAlarmReceiver.notifyCaptureStarted(applicationContext, recording) }
                },
            )
            total = result.bytes
            error = result.error
        } catch (_: CancellationException) {
            error = capture.stopReason ?: if (capture.userStopped && programmeEnd > System.currentTimeMillis())
                "Recording stopped before the programme finished"
            else if (!capture.userStopped) "Recording service interrupted" else null
        } catch (_: Exception) {
            // Never persist exception text: network/SAF/SMB exceptions may include secrets.
            error = "Recording could not continue; check provider access and recording storage"
            Log.w(TAG, "Recording $id could not continue")
        } finally {
            withContext(NonCancellable) {
                if (claimed) runCatching { repo.beginFinalizing(id) }
                capture.call?.cancel()
                capture.call = null
                try { sink?.close() }
                catch (_: Exception) { error = "Recording destination could not finalize captured content" }
                // Keep tail-following active until all output has actually been closed.
                RecordingLiveState.markInactive(id)
                token?.let { connections.release(it) }
                if (claimed) {
                    val reason = error ?: if (late) "Recording started after the programme began" else null
                    val ok = total > 0 && reason == null
                    runCatching {
                        repo.markFinished(id, ok, System.currentTimeMillis(), total, reason ?: if (total == 0L) "No content captured" else null)
                        if (capture.userStopped && total == 0L) repo.setStatus(id, RecordingStatus.CANCELED, "Stopped before content arrived")
                        repo.event(id, if (ok) "COMPLETED" else if (total > 0) "PARTIAL" else "FAILED",
                            reason ?: if (ok) "Capture completed" else "No content captured")
                    }.onFailure { Log.w(TAG, "Recording $id final status could not be persisted; recovery will reconcile it") }
                }
            }
        }
    }

    private fun stopCapture(id: Long) {
        val capture = captures[id] ?: return
        capture.userStopped = true
        capture.call?.cancel()
        capture.job.cancel()
    }

    /** Completion also runs for a lazy coroutine canceled before its body was dispatched. */
    @Synchronized
    private fun finish(id: Long, capture: Capture) {
        captures.remove(id, capture)
        if (captures.isEmpty()) stopSelfSafely() else updateNotification()
    }

    @Synchronized
    private fun stopSelfSafely() {
        if (captures.isNotEmpty() || !stopSelfResult(lastStartId)) return
        releaseWakeLock()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION") stopForeground(true)
        }
    }

    /**
     * The user swiped OpenTV out of recents. That must NOT kill an in-progress recording — the
     * whole point of a foreground capture is that it keeps going when you leave the app. So while
     * any capture is still running we deliberately do not [stopSelf]; we re-assert foreground so
     * the OS keeps the process (and its wake lock) alive, and let each capture retire the service
     * itself when it finishes. Only when nothing is recording do we tidy up and stop.
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        if (captures.isEmpty()) {
            stopSelfSafely()
            return
        }
        Log.i(TAG, "Task removed with ${captures.size} active recording(s) — keeping capture alive")
        runCatching { startForeground(NOTIFICATION_ID, buildNotification(captures.size)) }
    }

    override fun onDestroy() {
        runningService = null
        captures.values.forEach { it.call?.cancel() }
        scope.coroutineContext[Job]?.cancel()
        releaseWakeLock()
        super.onDestroy()
    }

    // ---- Wake lock ---------------------------------------------------------------------------

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "OpenTV:recording").apply {
            setReferenceCounted(false)
            // Lifetime is bounded by capture ownership and onDestroy, with explicit release.
            @Suppress("WakelockTimeout")
            acquire()
        }
    }

    private fun releaseWakeLock() {
        runCatching { if (wakeLock?.isHeld == true) wakeLock?.release() }
        wakeLock = null
    }

    // ---- Notification ------------------------------------------------------------------------

    private fun updateNotification() {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, buildNotification(captures.size))
    }

    private fun buildNotification(count: Int): Notification {
        val text = when (count) {
            0, 1 -> getString(R.string.rec_notification_one)
            else -> getString(R.string.rec_notification_many, count)
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Nuvix")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_nuvix_logo)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (manager.getNotificationChannel(CHANNEL_ID) == null) {
                manager.createNotificationChannel(
                    NotificationChannel(
                        CHANNEL_ID,
                        getString(R.string.rec_channel_name),
                        NotificationManager.IMPORTANCE_LOW,
                    ).apply { description = getString(R.string.rec_channel_desc) },
                )
            }
        }
    }

    companion object {
        private const val TAG = "Nuvix"
        private const val CHANNEL_ID = "recordings"
        private const val NOTIFICATION_ID = 4210
        @Volatile private var runningService: RecordingService? = null
        private val generations = AtomicLong()

        private const val ACTION_START = "app.opentv.recording.START"
        private const val ACTION_STOP = "app.opentv.recording.STOP"
        private const val ACTION_STOP_ALL = "app.opentv.recording.STOP_ALL"
        private const val EXTRA_ID = "recordingId"

        fun start(context: Context, recordingId: Long) {
            val intent = Intent(context, RecordingService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_ID, recordingId)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context, recordingId: Long) {
            // A stop must never create an idle service from the background.
            runningService?.stopCapture(recordingId)
        }

        suspend fun stopForConflict(recordingId: Long) {
            val capture = runningService?.captures?.get(recordingId) ?: return
            capture.stopReason = "Higher-priority recording required this account's capacity"
            capture.call?.cancel()
            capture.job.cancel()
            capture.job.join() // Capacity is reusable only after actual I/O and sink closure.
        }

        suspend fun awaitCaptureStopped(recordingId: Long) {
            runningService?.captures?.get(recordingId)?.job?.join()
        }
    }
}
