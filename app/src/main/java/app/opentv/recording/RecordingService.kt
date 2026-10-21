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
import app.opentv.data.model.Recording
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

    /** In-process capture job and resources tracked for one recording. */
    private class Capture {
        @Volatile var call: Call? = null
        @Volatile var userStopped = false
        @Volatile var stopReason: String? = null
        @Volatile var deadlineReached: Boolean = false
        lateinit var job: Job
    }

    /** Declines binding because capture is controlled through explicit service commands. */
    override fun onBind(intent: Intent?): IBinder? = null

    /** Initializes the foreground recording service and its notification channel. */
    override fun onCreate() {
        super.onCreate()
        createChannel()
        runningService = this
        ServiceCompat.startForeground(this, NOTIFICATION_ID, buildNotification(0),
            if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0)
    }

    /** Dispatches explicit recording start and stop commands. */
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

    /** Claims a scheduled recording and starts its tracked capture job. */
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

    /** Reserves account capacity, opens storage and finalizes capture status and resources. */
    private suspend fun runCapture(id: Long, capture: Capture) {
        val graph = ServiceLocator.get(applicationContext)
        val repo = graph.recordingRepository
        var claimed = false
        var total = 0L
        var error: String? = null
        var sink: RecordingStorage.Sink? = null
        var token: ReservationToken? = null
        var late = false
        var interrupted = false
        var programmeEnd = 0L
        var deadlineWatcher: Job? = null
        var actualPath: String? = null
        var gateRow: Recording? = null
        var gateDecision: RecordingLateStart.Decision? = null
        var mediaReadyAt = 0L
        var storageMonitor: () -> Unit = {}
        var writeAttempted = false
        val connections = AccountConnectionManager.get(applicationContext)
        try {
            repo.failInterrupted()
            if (!graph.recordingEngine.prepareDue(id)) return
            claimed = repo.claimScheduled(id, System.currentTimeMillis())
            if (!claimed) return // Duplicate alarm, canceled booking, or already finished capture.
            val recording = repo.byId(id) ?: return
            if (!graph.recordingEngine.allowLateStart(recording)) { claimed = false; return }
            programmeEnd = recording.programmeEndMillis.takeIf { it > 0 } ?: recording.scheduledEndMillis
            val source = graph.sourceRepository.byId(recording.sourceId)?.takeIf { it.enabled }
                ?: run { error = "Recording provider was removed or disabled"; return }
            connections.registerSource(source)
            val request = ConnectionRequest("recording:$id", generations.incrementAndGet(), recording.sourceId, ConnectionPurpose.RECORDING)
            val beforeViewing = connections.snapshotForSource(recording.sourceId)?.reservations
                .orEmpty().filter { it.token.purpose != ConnectionPurpose.RECORDING }.map { it.token }
            val beforeAdmission = repo.byId(id) ?: return
            if (!graph.recordingEngine.allowLateStart(beforeAdmission)) { claimed = false; return }
            when (val reservation = connections.reserveForRecording(request, allowInterruptLive = true,
                canStart = { repo.byId(id)?.let { graph.recordingEngine.allowLateStart(it) } == true })) {
                is ReservationResult.Reserved -> token = reservation.token
                is ReservationResult.ApprovalRequired -> {
                    connections.discardChallenge(reservation.challenge)
                    error = "Account allowance is unknown; an additional recording connection needs confirmation"
                    return
                }
                is ReservationResult.Denied -> {
                    // A concurrent capture can win admission after the conflict preview. Retry
                    // without retaining a reservation, creating a file or canceling its owner.
                    if (repo.byId(id)?.status != RecordingStatus.PREPARING) { claimed = false; return }
                    if (repo.deferPreparing(id)) {
                        RecordingScheduler.set(applicationContext, id, System.currentTimeMillis() + 5_000L)
                        claimed = false
                    } else error = "Provider connection is unavailable"
                    return
                }
            }
            repo.event(id, "PROVIDER_SELECTED", "Authorized provider selected; account connection reserved")
            val afterViewing = connections.snapshotForSource(recording.sourceId)?.reservations.orEmpty().map { it.token }.toSet()
            val reclaimed = beforeViewing.filter { it !in afterViewing }
            if (reclaimed.isNotEmpty()) repo.event(id, "VIEWING_RECLAIMED", "Unattended recording priority released ${reclaimed.size} conflicting viewing connection(s); unrelated accounts retained")
            // Storage is deliberately lazy. An HTTP response alone is not captured content.
            val output = object : java.io.OutputStream() {
                override fun write(value: Int) { writeAttempted = true; requireNotNull(sink).output.write(value) }
                override fun write(bytes: ByteArray, offset: Int, length: Int) {
                    writeAttempted = true
                    requireNotNull(sink).output.write(bytes, offset, length)
                }
            }
            // This job owns no socket or reservation. An edit only changes the durable row.
            // Claiming FINALIZING and editing the deadline serialize in SQLite.
            deadlineWatcher = scope.launch {
                try { RecordingDeadlineWatcher(
                    read = { repo.byId(id) },
                    claim = { revision, at -> repo.claimDurationDeadline(id, revision, at) },
                    elapsed = {
                        capture.deadlineReached = true
                        capture.call?.cancel()
                        capture.job.cancel()
                    },
                ).run() } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    capture.stopReason = "Recording deadline could not be verified; available content preserved"
                    capture.call?.cancel()
                    capture.job.cancel()
                }
            }
            val result = RecordingCapture(graph.streamingHttpClient).copy(
                recording.streamUrl, recording.userAgent, output, 0,
                onCall = { call -> capture.call = call; if (!capture.job.isActive) call?.cancel() },
                onProgress = { bytes ->
                    total = bytes
                    withContext(NonCancellable) { repo.setSize(id, bytes) }
                },
                checkStorage = { storageMonitor() },
                beforeFirstWrite = {
                    val fresh = repo.byId(id)
                    if (fresh == null || !graph.recordingEngine.allowLateStart(fresh)) false
                    else {
                        RecordingStorage.checkHeadroom(fresh.filePath,
                            RecordingStorage.MIN_FREE_BYTES * captures.size.coerceAtLeast(1))
                        sink = RecordingStorage.openSink(applicationContext, graph.settings, fresh.filePath)
                        actualPath = sink?.resolvedLocator ?: fresh.filePath
                        storageMonitor = RecordingStorage.storageMonitor(requireNotNull(actualPath))
                        if (actualPath != fresh.filePath) repo.setFilePath(id, requireNotNull(actualPath))
                        // SAF/NAS opening can itself be slow. Recheck with fresh bounds/defaults.
                        val ready = repo.byId(id)
                        mediaReadyAt = System.currentTimeMillis()
                        if (ready == null || !graph.recordingEngine.allowLateStart(ready, mediaReadyAt)) false
                        else {
                            gateRow = ready
                            gateDecision = graph.recordingEngine.lateStartDecision(ready, mediaReadyAt)
                            gateDecision?.action == RecordingLateStart.Decision.Action.CAPTURE
                        }
                    }
                },
                onInterruption = {
                    interrupted = true
                    repo.markContentMissed(id)
                    repo.event(id, "CAPTURE_INTERRUPTED", "Network interruption after useful capture; existing recovery retained and result will be Partial")
                },
                onStarted = {
                    val ready = requireNotNull(gateRow)
                    val decision = requireNotNull(gateDecision)
                    late = decision.contentMissed
                    if (!repo.commitLateStart(ready, mediaReadyAt, decision))
                        throw CancellationException("Recording changed before useful capture was committed")
                    RecordingLiveState.markActive(id, requireNotNull(actualPath))
                    connections.markActive(requireNotNull(token))
                    // A disabled notification or an unavailable Activity must not stop capture.
                    runCatching { RecordingAlarmReceiver.notifyCaptureStarted(applicationContext, recording) }
                },
            )
            total = result.bytes
            error = result.error
            if (result.bytes == 0L && result.retryable && recording.scheduledEndMillis > System.currentTimeMillis() && repo.deferPreparing(id)) {
                repo.event(id, "WAITING_FOR_NETWORK", "Useful stream data unavailable; policy will be rechecked when retrying")
                RecordingScheduler.set(applicationContext, id, System.currentTimeMillis() + 30_000L)
                claimed = false
            }
            if (result.startRejected) {
                val current = repo.byId(id)
                if (current?.status == RecordingStatus.PREPARING && repo.deferPreparing(id))
                    RecordingScheduler.set(applicationContext, id, maxOf(current.scheduledStartMillis, System.currentTimeMillis() + 1_000L))
                claimed = false // Shared gate already persisted MISSED, or the clock moved before start.
            }
        } catch (_: CancellationException) {
            error = capture.stopReason ?: if ((capture.userStopped || capture.deadlineReached) && programmeEnd > System.currentTimeMillis())
                "Recording stopped before the programme finished"
            else if (!capture.userStopped && !capture.deadlineReached) "Recording service interrupted" else null
        } catch (_: Exception) {
            // Never persist exception text: network/SAF/SMB exceptions may include secrets.
            error = "Recording could not continue; check provider access and recording storage"
            Log.w(TAG, "Recording $id could not continue")
        } finally {
            withContext(NonCancellable) {
                deadlineWatcher?.cancel()
                if (claimed) runCatching { repo.beginFinalizing(id) }
                capture.call?.cancel()
                capture.call = null
                try { sink?.close() }
                catch (_: Exception) { error = "Recording destination could not finalize captured content" }
                // Keep tail-following active until all output has actually been closed.
                RecordingLiveState.markInactive(id)
                token?.let { connections.release(it) }
                // Only remove a destination created by this job, and only if it contains no media.
                if (!writeAttempted && sink != null) actualPath?.let {
                    RecordingStorage.delete(applicationContext, graph.settings, it)
                }
                if (claimed) {
                    // A destination can throw after accepting some bytes. Never delete that file.
                    if (writeAttempted && total == 0L) actualPath?.let { path ->
                        total = RecordingStorage.sizeOf(path).coerceAtLeast(0)
                    }
                    val reason = error ?: if (late) "Recording started after the programme began"
                        else if (interrupted) "Capture experienced a network interruption; available content preserved" else null
                    val ok = total > 0 && reason == null
                    runCatching {
                        val at = System.currentTimeMillis()
                        val end = repo.byId(id)?.scheduledEndMillis ?: 0
                        val expiredEmpty = total == 0L && (capture.deadlineReached || end in 1..at)
                        val finalReason = if (expiredEmpty) "Recording window elapsed before useful media arrived"
                            else reason ?: if (total == 0L) "No content captured" else null
                        if (expiredEmpty) repo.markMissed(id, at, requireNotNull(finalReason))
                        else repo.markFinished(id, ok, at, total, finalReason)
                        if (capture.userStopped && total == 0L) repo.setStatus(id, RecordingStatus.CANCELED, "Stopped before content arrived")
                        repo.event(id, if (capture.userStopped && total == 0L) "CANCELED" else if (ok) "COMPLETED"
                            else if (total > 0) "PARTIAL" else if (expiredEmpty) "MISSED" else "FAILED",
                            finalReason ?: "Capture completed")
                    }.onFailure { Log.w(TAG, "Recording $id final status could not be persisted; recovery will reconcile it") }
                }
            }
        }
    }

    /** Requests capture termination for the specified recording. */
    private fun stopCapture(id: Long) {
        val capture = captures[id] ?: return
        scope.launch {
            if (captures[id] !== capture) return@launch
            val repo = ServiceLocator.get(applicationContext).recordingRepository
            runCatching {
                if (repo.beginFinalizing(id) == 1) repo.event(id, "STOPPED_MANUALLY", "User stopped recording; captured content preserved")
            }
            capture.userStopped = true
            capture.call?.cancel()
            capture.job.cancel()
        }
    }

    /** Completion also runs for a lazy coroutine canceled before its body was dispatched. */
    @Synchronized
    private fun finish(id: Long, capture: Capture) {
        captures.remove(id, capture)
        if (captures.isEmpty()) stopSelfSafely() else updateNotification()
    }

    /** Stops the service when no capture jobs remain. */
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

    /** Cancels active jobs and releases service-owned resources. */
    override fun onDestroy() {
        runningService = null
        captures.values.forEach { it.call?.cancel() }
        scope.coroutineContext[Job]?.cancel()
        releaseWakeLock()
        super.onDestroy()
    }

    // ---- Wake lock ---------------------------------------------------------------------------

    /** Keeps the CPU awake while recording capture requires background work. */
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

    /** Releases the recording service's CPU wake lock. */
    private fun releaseWakeLock() {
        runCatching { if (wakeLock?.isHeld == true) wakeLock?.release() }
        wakeLock = null
    }

    // ---- Notification ------------------------------------------------------------------------

    /** Refreshes the foreground notification to reflect current recording activity. */
    private fun updateNotification() {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, buildNotification(captures.size))
    }

    /** Builds the ongoing recording notification and its supported actions. */
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

    /** Creates the foreground recording notification channel on supported Android versions. */
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
        /** Checks whether capture begins beyond the grace period after a known program start. */
        internal fun isLateStart(recording: Recording, atMillis: Long): Boolean {
            return RecordingLateStart.isLate(recording, atMillis)
        }

        private const val TAG = "Nuvix"
        private const val CHANNEL_ID = "recordings"
        private const val NOTIFICATION_ID = 4210
        @Volatile private var runningService: RecordingService? = null
        private val generations = AtomicLong()

        private const val ACTION_START = "app.opentv.recording.START"
        private const val ACTION_STOP = "app.opentv.recording.STOP"
        private const val ACTION_STOP_ALL = "app.opentv.recording.STOP_ALL"
        private const val EXTRA_ID = "recordingId"

        /** Sends an explicit request to start a recording capture service. */
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

        /** Stops an existing capture without creating an idle service from the background. */
        fun stop(context: Context, recordingId: Long) {
            // A stop must never create an idle service from the background.
            runningService?.stopCapture(recordingId)
        }

        /** Stops a recording selected by account-capacity conflict policy. */
        suspend fun stopForConflict(recordingId: Long) {
            val service = runningService ?: return
            val capture = service.captures[recordingId] ?: return
            runCatching { ServiceLocator.get(service.applicationContext).recordingRepository.beginFinalizing(recordingId) }
            capture.stopReason = "Higher-priority recording required this account's capacity"
            capture.call?.cancel()
            capture.job.cancel()
            capture.job.join() // Capacity is reusable only after actual I/O and sink closure.
        }

        /** Waits for the tracked capture job to finish closing its resources. */
        suspend fun awaitCaptureStopped(recordingId: Long) {
            runningService?.captures?.get(recordingId)?.job?.join()
        }
    }
}
