/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.recording

import android.content.Context
import app.opentv.core.AppSettings
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
    ): Long {
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
            streamUrl = recordUrlFor(channel.streamUrl),
            userAgent = ua,
            scheduledStartMillis = programme?.startUtcMillis ?: 0,
            // Keep going past the listed end by the padding, so an overrun isn't cut off.
            scheduledEndMillis = programme?.endUtcMillis
                ?.let { it + settings.recordPadEndMinutes.value.coerceAtLeast(0) * 60_000L } ?: 0,
            startedAtMillis = now,
            status = RecordingStatus.RECORDING,
            seriesRuleId = ruleId,
            profileId = profileId,
        )
        val id = repo.insert(recording)
        RecordingService.start(appContext, id)
        return id
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
    ): Long {
        // Padding: start early and keep going past the listed end, so a late start or an overrun
        // (sport, news) isn't clipped — the Sky-Q behaviour.
        val padStart = settings.recordPadStartMinutes.value.coerceAtLeast(0) * 60_000L
        val padEnd = settings.recordPadEndMinutes.value.coerceAtLeast(0) * 60_000L
        val scheduledStart = programme.startUtcMillis - padStart
        val scheduledEnd = programme.endUtcMillis + padEnd
        // De-dup on the (padded) start, deterministic per airing: a series rule that re-scans, or a
        // double-press of Schedule, mustn't stack a second identical booking. Different quality
        // variants have different channel ids, so recording HD *and* SD of the same show is allowed.
        if (ruleId != null && repo.alreadyBooked(ruleId, channel.id, scheduledStart)) return -1L
        repo.bookingAt(channel.id, scheduledStart)?.let { return it.id }

        // Multi-provider clash fallback: if another recording already owns this window on this
        // channel's provider, record from a *different* provider that has the same channel — two
        // connections, no cut. Falls back to the original channel when there's no free alternate.
        val recordFrom = alternateProviderForClash(channel, scheduledStart, scheduledEnd)
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
            streamUrl = recordUrlFor(recordFrom.streamUrl),
            userAgent = ua,
            scheduledStartMillis = scheduledStart,
            scheduledEndMillis = scheduledEnd,
            status = RecordingStatus.SCHEDULED,
            seriesRuleId = ruleId,
            profileId = profileId,
        )
        val id = repo.insert(recording)
        val armAt = scheduledStart.coerceAtLeast(System.currentTimeMillis() + 1_000L)
        RecordingScheduler.set(appContext, id, armAt)
        // Warn the viewer just before we switch their screen to the recording (single-connection
        // auto-switch); only meaningful when auto-switch is on.
        if (settings.recordAutoSwitch.value) RecordingScheduler.setWarning(appContext, id, armAt)
        return id
    }

    /**
     * Re-attempt a recording the user tapped Retry on. A booking still in the future is simply
     * re-armed; one whose window is on now (or unbounded) starts capturing immediately for what is
     * left; one whose window is entirely in the past cannot be recovered — the broadcast is gone —
     * and stays failed. Returns true when a retry was actually kicked off.
     */
    suspend fun retry(recording: Recording): Boolean {
        val now = System.currentTimeMillis()
        val start = recording.scheduledStartMillis
        val end = recording.scheduledEndMillis
        return when {
            start > now + LEAD_MILLIS -> {
                repo.setStatus(recording.id, RecordingStatus.SCHEDULED, null)
                RecordingScheduler.set(appContext, recording.id, start)
                true
            }
            end == 0L || end > now -> {
                repo.setStatus(recording.id, RecordingStatus.SCHEDULED, null)
                RecordingService.start(appContext, recording.id)
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
            val at = if (rec.scheduledStartMillis <= now) now + 1_000L else rec.scheduledStartMillis
            RecordingScheduler.set(appContext, rec.id, at)
            if (autoSwitch) RecordingScheduler.setWarning(appContext, rec.id, at)
        }
    }

    /** Cancel a scheduled recording: disarm the alarm (and its pre-warning) and drop the row. */
    suspend fun cancelScheduled(recordingId: Long) {
        RecordingScheduler.cancel(appContext, recordingId)
        RecordingScheduler.cancelWarning(appContext, recordingId)
        repo.delete(recordingId)
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

    /**
     * If [channel]'s own provider is already busy recording during [startMillis]..[endMillis],
     * return a copy of the same channel on a *free* provider so the two recordings don't fight over
     * one connection. Returns [channel] unchanged when its provider is free, when there's only one
     * provider, or when no alternate is directly recordable (a Stalker cmd can't be recorded as-is).
     */
    private suspend fun alternateProviderForClash(channel: Channel, startMillis: Long, endMillis: Long): Channel {
        val busy = busyProviderIds(startMillis, endMillis)
        // Our provider is free this window, or nothing else is booked — no need to move.
        if (channel.sourceId !in busy) return channel
        if (channel.groupKey.isEmpty()) return channel

        val variants = channelDao.variantsInGroup(channel.groupKey)
            .groupBy { it.sourceId }
            .mapNotNull { (_, chans) -> chans.maxByOrNull { it.qualityRank } }

        for (variant in variants) {
            if (variant.sourceId == channel.sourceId || variant.sourceId in busy) continue
            if (variant.streamUrl.isBlank()) continue
            // Only a provider whose stream URL is directly fetchable is usable for a background
            // capture; a Stalker portal needs a per-play create_link the recorder can't do here.
            if (sources.byId(variant.sourceId)?.kind == app.opentv.data.model.SourceKind.STALKER) continue
            return variant
        }
        return channel
    }

    /** Provider ids with a recording scheduled or running that overlaps [startMillis]..[endMillis]. */
    private suspend fun busyProviderIds(startMillis: Long, endMillis: Long): Set<Long> =
        (repo.scheduled() + repo.active())
            .filter { rec ->
                val recEnd = if (rec.scheduledEndMillis > 0) rec.scheduledEndMillis else Long.MAX_VALUE
                rec.scheduledStartMillis < endMillis && startMillis < recEnd
            }
            .map { it.sourceId }
            .toSet()

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
    private fun recordUrlFor(streamUrl: String): String =
        if (streamUrl.endsWith(".m3u8")) streamUrl.removeSuffix(".m3u8") + ".ts" else streamUrl

    private companion object {
        /** A programme starting within this window counts as "on now" and records immediately. */
        const val LEAD_MILLIS = 20_000L
    }
}
