/* Nuvix modifications Copyright (C) 2026 Nuvix Contributors. GPL-3.0-or-later. */
package app.opentv.ui.channels

import app.opentv.data.model.Channel
import app.opentv.data.model.Programme
import app.opentv.data.model.Source
import app.opentv.data.model.SourceKind
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** Temporal program state used to determine eligible viewing, reminder and recording actions. */
enum class GuideProgrammeStatus { INVALID, AIRING, UPCOMING, ENDED }

/** UI eligibility only; playback, reminders and recording remain owned by their existing services. */
data class GuideProgrammeActions(
    val status: GuideProgrammeStatus,
    val canWatchNow: Boolean,
    val canSetReminder: Boolean,
    val canRecordNow: Boolean,
    val canScheduleRecording: Boolean,
    val canRecordSeries: Boolean,
    val canCatchup: Boolean,
    val canViewChannel: Boolean,
) {
    companion object {
        /** The background recorder fetches a direct URL; Stalker requires per-play resolution. */
        fun canRecordChannel(channel: Channel, source: Source?): Boolean = source != null && source.enabled &&
            source.id == channel.sourceId && source.kind != SourceKind.STALKER && channel.streamUrl.toHttpUrlOrNull() != null

        /** Evaluate the selected recording variant separately from the guide's primary channel. */
        fun evaluate(programme: Programme, channel: Channel, source: Source?, nowMillis: Long,
            recordingSourceIds: Set<Long> = emptySet()): GuideProgrammeActions {
            val valid = programme.startUtcMillis >= 0 && programme.endUtcMillis > programme.startUtcMillis && nowMillis >= 0
            val status = when {
                !valid -> GuideProgrammeStatus.INVALID
                programme.startUtcMillis > nowMillis -> GuideProgrammeStatus.UPCOMING
                programme.endUtcMillis > nowMillis -> GuideProgrammeStatus.AIRING
                else -> GuideProgrammeStatus.ENDED
            }
            val available = source != null && source.enabled && source.id == channel.sourceId
            val recordable = canRecordChannel(channel, source)
            val sourceBusy = channel.sourceId in recordingSourceIds
            val airing = status == GuideProgrammeStatus.AIRING
            val upcoming = status == GuideProgrammeStatus.UPCOMING
            val hasEpisode = programme.season?.let { it >= 0 } == true || programme.episode?.let { it >= 0 } == true
            // Some providers advertise archive support without a day count; honor known bounds.
            val withinArchive = valid && (channel.tvArchiveDays <= 0 ||
                nowMillis - programme.startUtcMillis <= channel.tvArchiveDays.toLong() * 86_400_000L)
            val catchup = available && source != null && source.kind == SourceKind.XTREAM && channel.tvArchive &&
                source.url.toHttpUrlOrNull() != null && !source.username.isNullOrBlank() && !source.password.isNullOrBlank() &&
                channel.streamId.isNotBlank() && status == GuideProgrammeStatus.ENDED && withinArchive && !sourceBusy
            return GuideProgrammeActions(
                status = status,
                canWatchNow = available && airing,
                canSetReminder = available && upcoming,
                // Capacity belongs to the shared account pool and is checked atomically by the
                // recorder. A recording on this playlist alone does not prove the pool is full.
                canRecordNow = recordable && airing,
                canScheduleRecording = recordable && upcoming,
                canRecordSeries = recordable && (airing || upcoming) && hasEpisode,
                canCatchup = catchup,
                canViewChannel = available,
            )
        }
    }
}
