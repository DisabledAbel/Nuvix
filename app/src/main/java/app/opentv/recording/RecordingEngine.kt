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
    private val durationConnections: AccountConnectionManager = AccountConnectionManager.get(appContext),
    private val durationClock: () -> Long = System::currentTimeMillis,
    private val durationChanged: suspend () -> Unit = { RecordingScheduler.rearmWarnings(appContext) },
    private val durationStop: (Long) -> Unit = { RecordingService.stop(appContext, it) },
    private val scheduleAlarm: (Long, Long) -> Unit = { id, at -> RecordingScheduler.set(appContext, id, at) },
) {

    /** Start recording [channel] now. [programme], when given, names and time-bounds the capture. */
    suspend fun startChannel(
        channel: Channel,
        programme: Programme? = null,
        ruleId: Long? = null,
        profileId: Long = settings.activeProfileId.value,
        lateStart: LateStartOptions = LateStartOptions(),
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
        val lateStartPolicy = if (ruleId == null && programme == null && lateStart.policy == LateStartPolicy.INHERIT)
            LateStartPolicy.RECORD_REMAINING else lateStart.policy

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
            scheduledStartMillis = minOf(now, programme?.startUtcMillis?.minus(settings.recordPadStartMinutes.value.coerceAtLeast(0) * 60_000L) ?: now),
            programmeStartMillis = programme?.startUtcMillis ?: 0,
            programmeEndMillis = programme?.endUtcMillis ?: 0,
            padStartMinutes = settings.recordPadStartMinutes.value.coerceAtLeast(0),
            padEndMinutes = settings.recordPadEndMinutes.value.coerceAtLeast(0),
            // Keep going past the listed end by the padding, so an overrun isn't cut off.
            scheduledEndMillis = programme?.endUtcMillis
                ?.let { it + settings.recordPadEndMinutes.value.coerceAtLeast(0) * 60_000L } ?: 0,
            status = RecordingStatus.SCHEDULED,
            lateStartPolicy = lateStartPolicy.name, minimumRemainingMinutes = lateStart.minimumMinutes,
            seriesRuleId = ruleId,
            profileId = profileId,
        )
        val id = repo.insert(recording)
        repo.event(id, "SCHEDULED", "Immediate recording requested")
        scheduleAlarm(id, now + 1_000L)
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
        lateStart: LateStartOptions = LateStartOptions(),
    ): Long {
        val now = System.currentTimeMillis()
        return if (programme.startUtcMillis > now + LEAD_MILLIS) {
            scheduleProgramme(channel, programme, ruleId, profileId, lateStart)
        } else {
            startChannel(channel, programme, ruleId, profileId, lateStart)
        }
    }

    /** Book a future programme. Creates a SCHEDULED row and arms the alarm. */
    suspend fun scheduleProgramme(
        channel: Channel,
        programme: Programme,
        ruleId: Long? = null,
        profileId: Long = settings.activeProfileId.value,
        lateStart: LateStartOptions = LateStartOptions(),
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
            lateStartPolicy = lateStart.policy.name, minimumRemainingMinutes = lateStart.minimumMinutes,
            seriesRuleId = ruleId,
            profileId = profileId,
        )
        val id = repo.insert(recording)
        repo.event(id, "SCHEDULED", "Recording scheduled with configured padding")
        val armAt = scheduledStart.coerceAtLeast(System.currentTimeMillis() + 1_000L)
        scheduleAlarm(id, armAt)
        // Warning review is independent of optional recording auto-navigation.
        durationChanged()
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
                scheduleAlarm(recording.id, start)
                true
            }
            end == 0L || end > now -> {
                if (!prepareRetry(current)) return@withLock false
                // Durable fallback covers process death between the status transition and start.
                scheduleAlarm(recording.id, now + 1_000L)
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
    suspend fun rearmScheduled(excludingId: Long? = null, releasedSourceId: Long? = null) {
        repo.failInterrupted()
        val releasedAccount = releasedSourceId?.let(durationConnections::accountKeyForSource)
        if (releasedSourceId != null && releasedAccount == null) return
        val now = durationClock()
        for (rec in repo.scheduled()) {
            if (rec.id == excludingId) continue
            if (releasedAccount != null) {
                sources.byId(rec.sourceId)?.let { durationConnections.registerSource(it) }
                if (durationConnections.accountKeyForSource(rec.sourceId) != releasedAccount) continue
            }
            if (rec.scheduledStartMillis <= now && !allowLateStart(rec, now)) continue
            val at = if (rec.scheduledStartMillis <= now) now + 1_000L else rec.scheduledStartMillis
            scheduleAlarm(rec.id, at)
        }
        durationChanged()
    }

    /** Cancel a scheduled recording: disarm the alarm (and its pre-warning) and drop the row. */
    suspend fun cancelScheduled(recordingId: Long) {
        RecordingScheduler.cancel(appContext, recordingId)
        RecordingScheduler.cancelWarning(appContext, recordingId)
        if (repo.cancelScheduled(recordingId)) {
            repo.event(recordingId, "CANCELED", "Scheduled recording canceled by user")
        }
        durationChanged()
    }

    /**
     * Create (or reuse) a series-link rule for this programme's title on this channel, and book
     * every matching future airing already in the guide. Airings beyond the loaded window get
     * picked up by [scheduleMatching] on the next guide refresh.
     */
    suspend fun recordSeries(channel: Channel, programme: Programme, windowProgrammes: List<Programme>,
        lateStart: LateStartOptions = LateStartOptions()): Long {
        val key = titleKeyOf(programme.title)
        val existing = repo.ruleForChannelTitle(channel.id, key)
        if (existing != null && lateStart.policy != LateStartPolicy.INHERIT) {
            repo.setRuleLateStart(existing.id, lateStart)
        }
        val ruleId = existing?.id ?: repo.addRule(
            SeriesRule(
                channelId = channel.id,
                channelName = channel.displayName,
                titleKey = key,
                title = programme.title,
                createdAtMillis = System.currentTimeMillis(),
                lateStartPolicy = lateStart.policy.name, minimumRemainingMinutes = lateStart.minimumMinutes,
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
        protectedRecording: Boolean, lockPadding: Boolean, lateStart: LateStartOptions? = null,
        lateRevision: Long? = null): Boolean = bookingLock.withLock { dueLock.withLock {
        val recording = repo.byId(recordingId) ?: return@withLock false
        if (lateRevision != null && lateRevision != recording.lateStartRevision) return@withLock false
        if (lateStart != null && !repo.setLateStart(recording, lateStart)) return@withLock false
        val startPad = padStartMinutes.coerceIn(0, 180)
        val endPad = padEndMinutes.coerceIn(0, 180)
        val canChangePadding = recording.status == RecordingStatus.SCHEDULED && recording.programmeStartMillis > 0 && recording.programmeEndMillis > 0
        repo.setProtection(recordingId, protectedRecording, lockPadding)
        if (canChangePadding) repo.setPadding(recordingId, startPad, endPad,
            recording.programmeStartMillis - startPad * 60_000L, recording.programmeEndMillis + endPad * 60_000L)
        repo.event(recordingId, "PREFERENCES", "User updated recording protection or padding preferences")
        if (recording.status == RecordingStatus.SCHEDULED) rearmScheduled()
        true
    } }

    suspend fun lateStartDecision(row: Recording, at: Long = durationClock()): RecordingLateStart.Decision =
        RecordingLateStart.evaluate(row, at, RecordingLateStart.resolve(row,
            row.seriesRuleId?.let { repo.ruleById(it) }, settings.recordLateStart.value))

    /** A skip is durable before any allocator reclamation or output creation. */
    suspend fun allowLateStart(row: Recording, at: Long = durationClock()): Boolean {
        val decision = lateStartDecision(row, at)
        if (decision.action == RecordingLateStart.Decision.Action.SKIP) {
            if (repo.commitLateStart(row, at, decision)) {
                RecordingScheduler.cancel(appContext, row.id)
                RecordingScheduler.cancelWarning(appContext, row.id)
                RecordingConflictNotifications.cancel(appContext, row.id)
            }
            return false
        }
        if (decision.action == RecordingLateStart.Decision.Action.WAIT && row.status == RecordingStatus.PREPARING && repo.deferPreparing(row.id))
            scheduleAlarm(row.id, row.scheduledStartMillis)
        return decision.action == RecordingLateStart.Decision.Action.CAPTURE
    }

    suspend fun updateLateStart(id: Long, options: LateStartOptions, revision: Long): Boolean = dueLock.withLock {
        val row = repo.byId(id) ?: return@withLock false
        if (row.lateStartRevision != revision || !repo.setLateStart(row, options)) return@withLock false
        repo.event(id, "LATE_START_POLICY_CHANGED", "User selected ${options.policy.name}; minimum ${options.minimumMinutes} minutes")
        rearmScheduled()
        true
    }

    suspend fun updateSeriesLateStart(id: Long, options: LateStartOptions) = dueLock.withLock {
        if (repo.setRuleLateStart(id, options)) rearmScheduled()
    }

    suspend fun updateGlobalLateStart(options: LateStartOptions) = dueLock.withLock {
        settings.setRecordLateStart(options)
        rearmScheduled()
    }

    /** Read-only review: no streams, reservations, padding or other recordings are modified. */
    suspend fun previewDuration(id: Long, choice: DurationChoice): DurationReview {
        val now = durationClock()
        val demand = (repo.active() + repo.scheduled()).map { it.copy(sizeBytes = 0) }.sortedBy { it.id }
        val row = repo.byId(id) ?: error("Recording is no longer available.")
        val end = RecordingDuration.proposedEnd(row, choice, now)
        val manager = durationConnections
        val missing = demand.map { it.sourceId }.distinct().filter { sourceId ->
            val source = sources.byId(sourceId)
            if (source != null && source.enabled) { manager.registerSource(source); false } else true
        }
        val accounts = manager.snapshots.value
        val keys = accounts.flatMap { a -> a.sourceIds.map { it to a.accountKey } }.toMap()
        fun plan(rows: List<Recording>) = DvrConflictPolicy.resolve(rows, now,
            { keys[it] ?: "unregistered:$it" }, { key -> accounts.firstOrNull { it.accountKey == key }?.effectiveMaximum })
        val before = plan(demand)
        val after = plan(demand.map { if (it.id == id) it.copy(scheduledEndMillis = end) else it })
        var affectedIds = (after.rejectedIds - before.rejectedIds) + after.adjustments.filter { a ->
            before.adjustments.none { it == a }
        }.map { it.recordingId }
        val account = accounts.firstOrNull { row.sourceId in it.sourceIds }
        if (id in after.rejectedIds && end > row.scheduledEndMillis) affectedIds = affectedIds + demand.filter {
            it.id != id && keys[it.sourceId] == keys[row.sourceId] &&
                it.scheduledStartMillis < end && (it.scheduledEndMillis <= 0 || it.scheduledEndMillis > maxOf(now, row.scheduledEndMillis))
        }.map { it.id }
        affectedIds = affectedIds - id
        val warnings = mutableListOf<String>()
        if (end <= now) warnings += DurationReview.STOP_WARNING
        if (row.programmeEndMillis > end) warnings += "This ends before the scheduled program finishes; captured content will be preserved."
        if (row.lockPadding && end != row.scheduledEndMillis) warnings += "You are explicitly editing the end while Lock Padding stays enabled."
        if (affectedIds.isNotEmpty()) warnings += "Recording priority may trim unlocked scheduled padding or prevent a lower-priority recording. Review the affected programs."
        if (account?.effectiveMaximum == null) warnings += "Account allowance is unknown. This edit keeps the existing reservation; future additional connections still require approval."
        val viewing = account?.reservations.orEmpty().count { it.token.purpose != app.opentv.connections.ConnectionPurpose.RECORDING }
        if (viewing > 0) warnings += "${viewing} viewing connection(s) share this account. Future DVR priority may reclaim affected panels at the recording deadline."
        val additional = RecordingDuration.estimatedBytes(row, (end - row.scheduledEndMillis).takeIf { row.scheduledEndMillis > 0 } ?: (end - now), now)
        var storageBlocked = false
        if (end > (row.scheduledEndMillis.takeIf { it > 0 } ?: now)) {
            val local = !RecordingStorage.isContent(row.filePath) && !RecordingStorage.isUsbPlaceholder(row.filePath) && !row.filePath.startsWith("smb://")
            if (local) {
                val parent = java.io.File(row.filePath).parentFile
                val free = parent?.usableSpace ?: 0
                val concurrent = repo.active().filter { it.id != id }.sumOf {
                    RecordingDuration.estimatedBytes(it, (it.scheduledEndMillis.takeIf { t -> t > 0 } ?: end) - now, now)
                }
                storageBlocked = parent?.isDirectory != true || free < RecordingStorage.MIN_FREE_BYTES
                val remaining = RecordingDuration.estimatedBytes(row, end - now, now)
                if (free.toDouble() < remaining.toDouble() + concurrent + RecordingStorage.MIN_FREE_BYTES)
                    warnings += "Available storage may be insufficient for this recording and concurrent captures. Estimates cannot guarantee capacity."
            } else warnings += "Free space cannot be verified for this external destination. Writes remain monitored; removal or full storage preserves usable partial content."
        }
        val longer = end > (row.scheduledEndMillis.takeIf { it > 0 } ?: now)
        val blocked = longer && (storageBlocked || row.sourceId in missing || id in after.rejectedIds)
        if (storageBlocked) warnings += "Recording storage is unavailable or below minimum headroom."
        if (row.sourceId in missing) warnings += "The recording provider is removed or disabled."
        if (id in after.rejectedIds) warnings += "This end conflicts with a higher-priority recording. Adjust the extension or review that booking."
        return DurationReview(row.copy(sizeBytes = 0), choice, end, demand,
            demand.filter { it.id in affectedIds }, sources.byId(row.sourceId)?.name ?: "Recording provider",
            account?.effectiveMaximum,
            accounts.sortedBy { it.accountKey }.map { a -> "${a.accountKey}:${a.sourceIds.sorted()}:${a.effectiveMaximum}:${a.reservations.map { it.token }.sortedBy { it.reservationId }}" },
            warnings, blocked, additional)
    }

    /** Confirmation rechecks current demand, then commits against deadline/finalization races. */
    suspend fun applyDuration(review: DurationReview, confirmStop: Boolean): String? = bookingLock.withLock { dueLock.withLock {
        suspend fun reject(message: String): String {
            repo.event(review.recording.id, "EXTENSION_REJECTED", message)
            return message
        }
        val fresh = try { previewDuration(review.recording.id,
            if (review.choice is DurationChoice.Add) DurationChoice.EndAt(review.proposedEndMillis) else review.choice)
        } catch (_: IllegalArgumentException) {
            return@withLock reject("Recording changed or finalized. Refresh before editing.")
        }
        // Stop is anchored at confirmation; all other choices retain the preview's exact end.
        val same = fresh.recording == review.recording && fresh.demand == review.demand &&
            fresh.policySignature == review.policySignature && fresh.affected == review.affected && fresh.warnings == review.warnings
        if (!same || (review.choice != DurationChoice.Stop && fresh.proposedEndMillis != review.proposedEndMillis))
            return@withLock reject("Recording or account demand changed. Review again.")
        if (fresh.blocked) return@withLock reject("Change is unavailable. Review the current warnings.")
        val now = durationClock()
        val end = fresh.proposedEndMillis
        val stop = end <= now || fresh.choice == DurationChoice.Stop
        // Validation may span the selected end (or a wall-clock correction). Consent belongs
        // to the displayed review, never to a newly elapsed deadline discovered while saving.
        if (stop && (!review.needsStopConfirmation || !confirmStop))
            return@withLock reject("The selected end has passed. Review again and explicitly confirm stopping.")
        val kind = when {
            stop -> "STOPPED_MANUALLY"
            review.choice == DurationChoice.Restore -> "DURATION_RESTORED"
            end < fresh.recording.scheduledEndMillis -> "RECORDING_SHORTENED"
            fresh.affected.isNotEmpty() -> "CONFLICTING_EXTENSION_CONFIRMED"
            else -> "RECORDING_EXTENDED"
        }
        if (!repo.commitDuration(fresh.recording.id, fresh.recording.durationRevision, end,
                RecordingDuration.originalEnd(fresh.recording).takeIf { it > 0 } ?: -1, now, stop, fresh.demand, kind))
            return@withLock reject("Recording changed or finalized. Refresh before editing.")
        if (stop) durationStop(fresh.recording.id)
        durationChanged()
        null
    } }

    /** Reuses exact-guide/verified-Sports matching and never substitutes an active capture. */
    suspend fun durationAlternatives(id: Long): List<RecordingConflictAlternative> {
        val row = repo.byId(id) ?: return emptyList()
        if (row.status != RecordingStatus.SCHEDULED) return emptyList()
        val manager = durationConnections
        sources.byId(row.sourceId)?.let { manager.registerSource(it) }
        val account = manager.accountKeyForSource(row.sourceId) ?: return emptyList()
        return RecordingConflictAlternatives(app.opentv.core.ServiceLocator.get(appContext))
            .choices(row.channelId, account, durationClock(), row.scheduledStartMillis)
            .filter {
                val source = sources.byId(it.channel.sourceId)
                source?.enabled == true && source.kind != SourceKind.STALKER &&
                    it.channel.streamUrl.toHttpUrlOrNull() != null &&
                    manager.snapshotForSource(it.channel.sourceId)?.effectiveMaximum != null
            }
    }

    /** Explicit scheduled-provider choice is checked over the full interval before persistence. */
    suspend fun applyDurationAlternative(id: Long, channelId: Long): Boolean = bookingLock.withLock { dueLock.withLock {
        val choice = durationAlternatives(id).firstOrNull { it.channel.id == channelId } ?: return@withLock false
        val source = sources.byId(choice.channel.sourceId)?.takeIf { it.enabled } ?: return@withLock false
        val demand = (repo.active() + repo.scheduled()).map { it.copy(sizeBytes = 0) }.sortedBy { it.id }
        val row = demand.firstOrNull { it.id == id && it.status == RecordingStatus.SCHEDULED } ?: return@withLock false
        val manager = durationConnections
        val accounts = manager.snapshots.value
        val keys = accounts.flatMap { a -> a.sourceIds.map { it to a.accountKey } }.toMap()
        val changed = row.copy(sourceId = source.id)
        val plan = DvrConflictPolicy.resolve(demand.map { if (it.id == id) changed else it }, durationClock(),
            { keys[it] ?: "unavailable:$it" }, { key -> accounts.firstOrNull { it.accountKey == key }?.effectiveMaximum })
        val before = DvrConflictPolicy.resolve(demand, durationClock(),
            { keys[it] ?: "unavailable:$it" }, { key -> accounts.firstOrNull { it.accountKey == key }?.effectiveMaximum })
        if (id in plan.rejectedIds || plan.windows[id] != DvrConflictPolicy.requestedWindow(changed, durationClock()) ||
            (plan.rejectedIds - before.rejectedIds).isNotEmpty() || plan.adjustments.any { it !in before.adjustments }) return@withLock false
        val applied = repo.commitScheduledProvider(id, source.id, recordUrlFor(choice.channel.streamUrl, source.kind),
            source.userAgent, demand, durationClock())
        if (applied) durationChanged()
        applied
    } }

    /** Builds an account-aware conflict preview for scheduled recordings. */
    suspend fun previewConflicts(): DvrConflictPlan {
        val manager = durationConnections
        val candidates = repo.scheduled() + repo.active()
        candidates.map { it.sourceId }.distinct().forEach { id -> sources.byId(id)?.let { manager.registerSource(it) } }
        return DvrConflictPolicy.resolve(candidates, durationClock(),
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
                scheduleAlarm(adjustment.recordingId, adjustment.after.startMillis.coerceAtLeast(durationClock() + 1_000L))
            }
        }
        durationChanged()
    }

    /** Resolve known recording-account contention at the deadline, never infer unknown capacity. */
    suspend fun prepareDue(recordingId: Long): Boolean = dueLock.withLock {
        val recording = repo.byId(recordingId) ?: return@withLock false
        if (recording.status != RecordingStatus.SCHEDULED) return@withLock false
        val now = durationClock()
        if (!allowLateStart(recording, now)) {
            if (recording.scheduledStartMillis > now) scheduleAlarm(recordingId, recording.scheduledStartMillis)
            return@withLock false
        }
        val manager = durationConnections
        val source = sources.byId(recording.sourceId)?.takeIf { it.enabled }
        if (source == null) {
            if (repo.setScheduledStatus(recordingId, RecordingStatus.FAILED, "Recording provider was removed or disabled"))
                repo.event(recordingId, "FAILED", "Recording provider was removed or disabled; no connection opened")
            return@withLock false
        }
        manager.registerSource(source)
        val plan = previewConflicts()
        // Deadline fallback changes only this due booking; other accounts and future deadlines
        // retain their pending user decisions. Explicit applyConflictFallback previews all rows.
        applyAdjustments(plan.copy(adjustments = plan.adjustments.filter { it.recordingId == recordingId }),
            "Unattended recording-priority fallback")
        if (recordingId in plan.rejectedIds) {
            val account = manager.accountKeyForSource(recording.sourceId)
            val blockers = repo.active().filter { manager.accountKeyForSource(it.sourceId) == account }
            val next = blockers.map { it.scheduledEndMillis }.filter { it > now }.minOrNull()
            if (blockers.isNotEmpty()) {
                val expected = next?.coerceAtMost(Long.MAX_VALUE - 1_000L)?.plus(1_000L) ?: (now + 5_000L)
                val projected = lateStartDecision(recording, expected)
                val current = lateStartDecision(recording, now)
                // A provider may release earlier than its expected deadline. Keep an eligible
                // booking pending until actual admission or the policy's own cutoff, whichever
                // comes first. Actual release also rearms this account's waiting bookings.
                val checkAt = minOf(expected, RecordingLateStart.recheckAt(recording, current.options, now))
                repo.event(recordingId, "WAITING_FOR_ACCOUNT",
                    "Waiting for provider capacity; expected capture start=$expected; projected policy=${projected.action}; recheck at=$checkAt", now)
                scheduleAlarm(recordingId, checkAt)
            } else if (repo.setScheduledStatus(recordingId, RecordingStatus.MISSED,
                    "Account capacity is reserved by a higher-priority recording")) {
                repo.event(recordingId, "MISSED", "Unattended conflict fallback preserved the higher-priority recording", now)
            }
            return@withLock false
        }
        val window = plan.windows[recordingId]
        if (window != null && window.startMillis > durationClock() + 1_000L) {
            scheduleAlarm(recordingId, window.startMillis)
            return@withLock false
        }
        // Existing active captures retain their reservations, regardless of a newly due booking.
        true
    }

    private companion object {
        val bookingLock = Mutex()
        val dueLock = Mutex()
        const val LEAD_MILLIS = 20_000L
    }
}
