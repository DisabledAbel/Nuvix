/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.recording

import android.content.Context
import app.opentv.core.AppSettings
import app.opentv.connections.AccountConnectionManager
import app.opentv.data.model.SourceKind
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import app.opentv.data.model.Channel
import app.opentv.data.model.Programme
import app.opentv.data.model.Recording
import app.opentv.data.model.RecordingStatus
import app.opentv.data.model.SeriesRule
import app.opentv.data.db.ChannelDao
import app.opentv.data.model.Source
import app.opentv.data.repo.EpgRepository
import app.opentv.data.repo.RecordingRepository
import app.opentv.data.repo.SourceRepository

/**
 * Turns "record this" into a running capture: it creates the [Recording] row, works out where the
 * file goes and which URL to pull, then hands off to [RecordingService] to do the actual byte
 * copy in the foreground so it survives the user leaving the player or the screen going dark.
 */
class RecordingEngine(
    private val appContext: Context,
    private val repo: RecordingRepository,
    private val sources: SourceRepository,
    private val settings: AppSettings,
    private val channelDao: ChannelDao,
    private val epgRepository: EpgRepository,
) {

    /** Start recording [channel] now. [programme], when given, names and time-bounds the capture. */
    suspend fun startChannel(
        channel: Channel,
        programme: Programme? = null,
        ruleId: Long? = null,
        profileId: Long = settings.activeProfileId.value,
    ): Long = bookingLock.withLock {
        val requestTime = System.currentTimeMillis()
        (repo.active() + repo.scheduled()).firstOrNull {
            it.channelId == channel.id && it.scheduledStartMillis <= requestTime &&
                (it.scheduledEndMillis <= 0 || it.scheduledEndMillis > requestTime)
        }?.let { return@withLock it.id }
        programme?.let {
            val existing = repo.bookingAt(channel.id, it.startUtcMillis)
                ?: repo.bookingAt(channel.id, it.startUtcMillis - settings.recordPadStartMinutes.value.coerceAtLeast(0) * 60_000L)
            if (existing != null) return@withLock existing.id
        }
        val source = sources.byId(channel.sourceId)
        val ua = source?.userAgent ?: Source.DEFAULT_USER_AGENT
        val now = System.currentTimeMillis()
        val title = programme?.title?.takeIf { it.isNotBlank() } ?: channel.displayName
        val filename = RecordingStorage.fileNameFor(channel.displayName, title, now)
        val locator = RecordingStorage.plannedLocator(appContext, settings, filename)

        val recording = Recording(
            channelId = channel.id,
            sourceId = channel.sourceId,
            channelName = channel.displayName,
            logoUrl = channel.logoUrl,
            title = title,
            description = programme?.description,
            filePath = locator,
            streamUrl = recordUrlFor(channel.streamUrl, source?.kind),
            userAgent = ua,
            scheduledStartMillis = programme?.startUtcMillis?.minus(settings.recordPadStartMinutes.value.coerceAtLeast(0) * 60_000L) ?: now,
            programmeStartMillis = programme?.startUtcMillis ?: 0,
            programmeEndMillis = programme?.endUtcMillis ?: 0,
            padStartMinutes = settings.recordPadStartMinutes.value.coerceAtLeast(0),
            padEndMinutes = settings.recordPadEndMinutes.value.coerceAtLeast(0),
            // Keep going past the listed end by the padding, so an overrun isn't cut off.
            scheduledEndMillis = programme?.endUtcMillis
                ?.let { it + settings.recordPadEndMinutes.value.coerceAtLeast(0) * 60_000L } ?: 0,
            status = RecordingStatus.SCHEDULED,
            seriesRuleId = ruleId,
            profileId = profileId,
        )
        val id = repo.insert(recording)
        repo.event(id, "SCHEDULED", "Immediate recording requested")
        RecordingScheduler.set(appContext, id, now + 1_000L)
        startServiceOrFail(id)
        id
    }

    /** Stop an in-progress recording. */
    fun stop(recordingId: Long) = RecordingService.stop(appContext, recordingId)

    /**
     * Record a specific programme: if it's already on, start capturing now (bounded to its end);
     * if it's in the future, book an exact alarm to start it at broadcast time.
     */
    suspend fun recordProgramme(
        channel: Channel,
        programme: Programme,
        ruleId: Long? = null,
        profileId: Long = settings.activeProfileId.value,
    ): Long {
        val now = System.currentTimeMillis()
        return if (programme.startUtcMillis > now + LEAD_MILLIS) {
            scheduleProgramme(channel, programme, ruleId, profileId)
        } else {
            startChannel(channel, programme, ruleId, profileId)
        }
    }

    /** Book a future programme. Creates a SCHEDULED row and arms the alarm. */
    suspend fun scheduleProgramme(
        channel: Channel,
        programme: Programme,
        ruleId: Long? = null,
        profileId: Long = settings.activeProfileId.value,
    ): Long = bookingLock.withLock {
        // Padding: start early and keep going past the listed end, so a late start or an overrun
        // (sport, news) isn't clipped — the Sky-Q behaviour.
        val padStart = settings.recordPadStartMinutes.value.coerceAtLeast(0) * 60_000L
        val padEnd = settings.recordPadEndMinutes.value.coerceAtLeast(0) * 60_000L
        val scheduledStart = programme.startUtcMillis - padStart
        val scheduledEnd = programme.endUtcMillis + padEnd
        // De-dup on the (padded) start, deterministic per airing: a series rule that re-scans, or a
        // double-press of Schedule, mustn't stack a second identical booking. Different quality
        // variants have different channel ids, so recording HD *and* SD of the same show is allowed.
        if (ruleId != null && (repo.alreadyBooked(ruleId, channel.id, programme.startUtcMillis) ||
                    repo.alreadyBooked(ruleId, channel.id, scheduledStart))) return@withLock -1L
        repo.bookingAt(channel.id, programme.startUtcMillis)?.let { return@withLock it.id }
        repo.bookingAt(channel.id, scheduledStart)?.let { return@withLock it.id }

        // The guide's explicit provider choice remains authoritative. A loose channel-name group
        // is insufficient evidence to silently substitute another broadcast/provider.
        val recordFrom = channel
        val source = sources.byId(recordFrom.sourceId)
        val ua = source?.userAgent ?: Source.DEFAULT_USER_AGENT
        val filename = RecordingStorage.fileNameFor(channel.displayName, programme.title, programme.startUtcMillis)
        val locator = RecordingStorage.plannedLocator(appContext, settings, filename)

        val recording = Recording(
            // channelId stays the channel the user picked (keeps de-dup/booking stable); only the
            // provider we pull bytes from may differ.
            channelId = channel.id,
            sourceId = recordFrom.sourceId,
            channelName = channel.displayName,
            logoUrl = channel.logoUrl,
            title = programme.title,
            description = programme.description,
            filePath = locator,
            streamUrl = recordUrlFor(recordFrom.streamUrl, source?.kind),
            userAgent = ua,
            scheduledStartMillis = scheduledStart,
            scheduledEndMillis = scheduledEnd,
            programmeStartMillis = programme.startUtcMillis,
            programmeEndMillis = programme.endUtcMillis,
            padStartMinutes = settings.recordPadStartMinutes.value.coerceAtLeast(0),
            padEndMinutes = settings.recordPadEndMinutes.value.coerceAtLeast(0),
            status = RecordingStatus.SCHEDULED,
            seriesRuleId = ruleId,
            profileId = profileId,
        )
        val id = repo.insert(recording)
        repo.event(id, "SCHEDULED", "Recording scheduled with configured padding")
        val armAt = scheduledStart.coerceAtLeast(System.currentTimeMillis() + 1_000L)
        RecordingScheduler.set(appContext, id, armAt)
        // Warn the viewer just before we switch their screen to the recording (single-connection
        // auto-switch); only meaningful when auto-switch is on.
        if (settings.recordAutoSwitch.value) RecordingScheduler.setWarning(appContext, id, armAt)
        id
    }

    /**
     * Re-attempt a recording the user tapped Retry on. A booking still in the future is simply
     * re-armed; one whose window is on now (or unbounded) starts capturing immediately for what is
     * left; one whose window is entirely in the past cannot be recovered — the broadcast is gone —
     * and stays failed. Returns true when a retry was actually kicked off.
     */
    suspend fun retry(recording: Recording): Boolean = bookingLock.withLock {
        val current = repo.byId(recording.id) ?: return@withLock false
        if (current.status !in setOf(RecordingStatus.FAILED, RecordingStatus.PARTIAL, RecordingStatus.MISSED, RecordingStatus.CANCELED)) return@withLock false
        RecordingService.awaitCaptureStopped(recording.id)
        val now = System.currentTimeMillis()
        val start = recording.scheduledStartMillis
        val end = recording.scheduledEndMillis
        when {
            start > now + LEAD_MILLIS -> {
                if (!prepareRetry(current)) return@withLock false
                RecordingScheduler.set(appContext, recording.id, start)
                true
            }
            end == 0L || end > now -> {
                if (!prepareRetry(current)) return@withLock false
                // Durable fallback covers process death between the status transition and start.
                RecordingScheduler.set(appContext, recording.id, now + 1_000L)
                startServiceOrFail(recording.id)
                true
            }
            else -> false
        }
    }

    /**
     * Re-arm the exact alarm for every scheduled recording. Alarms are dropped on a force-stop or
     * app update — which the boot receiver does not see — so [app.opentv.OpenTvApp] calls this on
     * each launch. A booking whose start slipped past while the app was dead fires almost at once;
     * re-setting an existing alarm is idempotent, so this is safe to run every time.
     */
    suspend fun rearmScheduled() {
        val now = System.currentTimeMillis()
        val autoSwitch = settings.recordAutoSwitch.value
        for (rec in repo.scheduled()) {
            if (rec.scheduledEndMillis > 0 && rec.scheduledEndMillis <= now) {
                repo.setScheduledStatus(rec.id, RecordingStatus.MISSED, "Recording window elapsed while capture was unavailable")
                repo.event(rec.id, "MISSED", "Recording window elapsed while capture was unavailable")
                RecordingScheduler.cancel(appContext, rec.id)
                RecordingScheduler.cancelWarning(appContext, rec.id)
                continue
            }
            val at = if (rec.scheduledStartMillis <= now) now + 1_000L else rec.scheduledStartMillis
            RecordingScheduler.set(appContext, rec.id, at)
            if (autoSwitch) RecordingScheduler.setWarning(appContext, rec.id, at)
        }
    }

    /** Cancel a scheduled recording: disarm the alarm (and its pre-warning) and drop the row. */
    suspend fun cancelScheduled(recordingId: Long) {
        RecordingScheduler.cancel(appContext, recordingId)
        RecordingScheduler.cancelWarning(appContext, recordingId)
        if (repo.cancelScheduled(recordingId)) {
            repo.event(recordingId, "CANCELED", "Scheduled recording canceled by user")
        }
    }

    /**
     * Create (or reuse) a series-link rule for this programme's title on this channel, and book
     * every matching future airing already in the guide. Airings beyond the loaded window get
     * picked up by [scheduleMatching] on the next guide refresh.
     */
    suspend fun recordSeries(channel: Channel, programme: Programme, windowProgrammes: List<Programme>): Long {
        val key = titleKeyOf(programme.title)
        val existing = repo.ruleForChannelTitle(channel.id, key)
        val ruleId = existing?.id ?: repo.addRule(
            SeriesRule(
                channelId = channel.id,
                channelName = channel.displayName,
                titleKey = key,
                title = programme.title,
                createdAtMillis = System.currentTimeMillis(),
            ),
        )
        scheduleMatching(channel, key, ruleId, windowProgrammes)
        return ruleId
    }

    /**
     * Remove a series-link rule and cancel every still-scheduled (not-yet-started) airing it booked.
     * Recordings already running or finished are left untouched — only the rule and its future
     * bookings go, so the series simply stops recording from here on (Sky Q "delete series link").
     */
    suspend fun removeSeriesRule(ruleId: Long) {
        repo.scheduled()
            .filter { it.seriesRuleId == ruleId }
            .forEach { cancelScheduled(it.id) }
        repo.deleteRule(ruleId)
    }

    /** Book every not-yet-booked airing in [programmes] whose title matches the rule. */
    suspend fun scheduleMatching(channel: Channel, titleKey: String, ruleId: Long, programmes: List<Programme>) {
        val now = System.currentTimeMillis()
        programmes
            .filter { it.endUtcMillis > now && titleKeyOf(it.title) == titleKey }
            .forEach { recordProgramme(channel, it, ruleId) }
    }

    /** Loose title match key — case- and punctuation-insensitive, so "Countryfile" == "countryfile". */
    fun titleKeyOf(title: String): String = title.lowercase().replace(Regex("[^a-z0-9]"), "")

    /**
     * Re-scan every series-link rule against the freshly-synced guide and book any new matching
     * airings. Run after each EPG refresh, so a weekly show gets its next episode booked as soon
     * as the guide reveals it — not just the airings that happened to be in the window when you
     * first set the rule.
     */
    suspend fun rescanSeriesRules() {
        val rules = repo.enabledRules()
        if (rules.isEmpty()) return
        val now = System.currentTimeMillis()
        for (rule in rules) {
            val channel = channelDao.byId(rule.channelId) ?: continue
            val upcoming = channel.epgCandidates
                .flatMap { epgId -> epgRepository.upcoming(epgId, now, 200) }
                .distinctBy { it.startUtcMillis to it.title }
            scheduleMatching(channel, rule.titleKey, rule.id, upcoming)
        }
    }

    /**
     * Live Xtream URLs are cached as `.m3u8` (HLS) for playback, but the raw `.ts` MPEG-TS variant
     * is a single continuous body that captures to a directly-playable file with no segment
     * stitching. Other URL shapes (plain M3U) are recorded verbatim.
     */
    private fun recordUrlFor(streamUrl: String, kind: SourceKind?): String {
        // Only Xtream's documented live transport variant is interchangeable with its HLS URL.
        if (kind != SourceKind.XTREAM) return streamUrl
        val url = streamUrl.toHttpUrlOrNull() ?: return streamUrl
        return if (url.encodedPath.endsWith(".m3u8"))
            url.newBuilder().encodedPath(url.encodedPath.removeSuffix(".m3u8") + ".ts").build().toString()
        else streamUrl
    }

    /** Requests foreground capture and records failure when Android rejects the service start. */
    private suspend fun startServiceOrFail(id: Long) {
        try { RecordingService.start(appContext, id) }
        catch (_: IllegalStateException) {
            repo.setScheduledStatus(id, RecordingStatus.FAILED, "Android rejected the recording start; review DVR readiness")
            repo.event(id, "FAILED", "Android rejected the recording service start")
        } catch (_: SecurityException) {
            repo.setScheduledStatus(id, RecordingStatus.FAILED, "Recording service permission is unavailable")
        }
    }

    /** Prepares a retry without discarding the recording's saved booking preferences. */
    private suspend fun prepareRetry(recording: Recording): Boolean {
        // Preserve the original partial as a separate library entry instead of overwriting it.
        val archive = if (recording.sizeBytes > 0 || RecordingStorage.sizeOf(recording.filePath) > 0)
            recording.copy(id = 0, status = RecordingStatus.PARTIAL,
                error = "Content preserved before retry", seriesRuleId = null) else null
        if (!repo.retryPreserving(recording.id, RecordingStorage.retryLocator(recording.filePath), archive)) return false
        repo.event(recording.id, "RETRY", "Retry requested into a new destination; prior content preserved")
        return true
    }

    /** Updates recording protection and padding choices subject to the current lifecycle state. */
    suspend fun updatePreferences(recordingId: Long, padStartMinutes: Int, padEndMinutes: Int,
        protectedRecording: Boolean, lockPadding: Boolean) = bookingLock.withLock { dueLock.withLock {
        val recording = repo.byId(recordingId) ?: return@withLock
        val startPad = padStartMinutes.coerceIn(0, 180)
        val endPad = padEndMinutes.coerceIn(0, 180)
        val canChangePadding = recording.status == RecordingStatus.SCHEDULED && recording.programmeStartMillis > 0 && recording.programmeEndMillis > 0
        repo.setProtection(recordingId, protectedRecording, lockPadding)
        if (canChangePadding) repo.setPadding(recordingId, startPad, endPad,
            recording.programmeStartMillis - startPad * 60_000L, recording.programmeEndMillis + endPad * 60_000L)
        repo.event(recordingId, "PREFERENCES", "User updated recording protection or padding preferences")
        if (recording.status == RecordingStatus.SCHEDULED) rearmScheduled()
    } }

    /** Builds an account-aware conflict preview for scheduled recordings. */
    suspend fun previewConflicts(): DvrConflictPlan {
        val manager = AccountConnectionManager.get(appContext)
        val candidates = repo.scheduled() + repo.active()
        candidates.map { it.sourceId }.distinct().forEach { id -> sources.byId(id)?.let { manager.registerSource(it) } }
        return DvrConflictPolicy.resolve(candidates, System.currentTimeMillis(),
            accountForSource = { manager.accountKeyForSource(it) ?: "unregistered:$it" },
            capacityForAccount = { account -> manager.snapshots.value.firstOrNull { it.accountKey == account }?.effectiveMaximum })
    }

    /** Applies the selected conflict-resolution action to eligible bookings. */
    suspend fun applyConflictFallback() = dueLock.withLock { applyAdjustments(previewConflicts(), "User confirmed conflict fallback") }

    /** Persists accepted padding adjustments and updates affected recording alarms. */
    private suspend fun applyAdjustments(plan: DvrConflictPlan, reason: String) {
        for (adjustment in plan.adjustments) {
            if (repo.setScheduledWindow(adjustment.recordingId, adjustment.after.startMillis, adjustment.after.endMillis) == 1) {
                repo.event(adjustment.recordingId, "PADDING_ADJUSTED", "$reason; minimum unlocked padding adjusted")
                RecordingScheduler.set(appContext, adjustment.recordingId, adjustment.after.startMillis.coerceAtLeast(System.currentTimeMillis() + 1_000L))
            }
        }
    }

    /** Resolve known recording-account contention at the deadline, never infer unknown capacity. */
    suspend fun prepareDue(recordingId: Long): Boolean = dueLock.withLock {
        val recording = repo.byId(recordingId) ?: return@withLock false
        if (recording.status != RecordingStatus.SCHEDULED) return@withLock false
        val now = System.currentTimeMillis()
        val manager = AccountConnectionManager.get(appContext)
        sources.byId(recording.sourceId)?.let { manager.registerSource(it) }
        val plan = previewConflicts()
        // Deadline fallback changes only this due booking; other accounts and future deadlines
        // retain their pending user decisions. Explicit applyConflictFallback previews all rows.
        applyAdjustments(plan.copy(adjustments = plan.adjustments.filter { it.recordingId == recordingId }),
            "Unattended recording-priority fallback")
        if (recordingId in plan.rejectedIds) {
            repo.setScheduledStatus(recordingId, RecordingStatus.MISSED, "Account capacity is reserved by a higher-priority recording")
            repo.event(recordingId, "MISSED", "Unattended conflict fallback preserved the higher-priority recording")
            return@withLock false
        }
        val window = plan.windows[recordingId]
        if (window != null && window.startMillis > System.currentTimeMillis() + 1_000L) {
            RecordingScheduler.set(appContext, recordingId, window.startMillis)
            return@withLock false
        }
        val victims = DvrStartPolicy.victims(recording, repo.active(), plan.rejectedIds, now,
            manager.snapshotForSource(recording.sourceId)?.effectiveMaximum, manager::accountKeyForSource)
        for (active in victims) {
            repo.event(active.id, "CONFLICT", "Higher-priority recording requires this account capacity")
            RecordingService.stopForConflict(active.id)
        }
        true
    }

    private companion object {
        val bookingLock = Mutex()
        val dueLock = Mutex()
        const val LEAD_MILLIS = 20_000L
    }
}
