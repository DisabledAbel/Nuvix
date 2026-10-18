/* Nuvix modifications Copyright (C) 2026 Nuvix Contributors. GPL-3.0-or-later. */
package app.opentv.ui.channels

import app.opentv.core.GuideLayout
import app.opentv.core.GuideTimelineDensity
import app.opentv.data.model.Programme
import java.time.Instant
import java.time.ZoneId

internal const val GUIDE_HOUR_MILLIS = 3_600_000L
internal const val GUIDE_WINDOW_MILLIS = 24 * GUIDE_HOUR_MILLIS

/** Values stay independent of Compose so layout changes and timeline geometry are testable. */
internal data class GuideLayoutMetrics(val rowHeightDp: Float, val minuteWidthDp: Float, val titleLines: Int)

/** Resolves guide row and timeline dimensions from layout and density preferences. */
internal fun guideLayoutMetrics(layout: GuideLayout, density: GuideTimelineDensity): GuideLayoutMetrics =
    GuideLayoutMetrics(
        rowHeightDp = when (layout) {
            GuideLayout.STANDARD -> 72f
            GuideLayout.COMPACT -> 54f
            GuideLayout.EXPANDED -> 112f
        },
        minuteWidthDp = when (density) {
            GuideTimelineDensity.COMFORTABLE -> 4f
            GuideTimelineDensity.DENSE -> 3f
            GuideTimelineDensity.WIDE -> 6f
        },
        titleLines = if (layout == GuideLayout.COMPACT) 1 else 2,
    )

/** Use local calendar days: a daylight-saving day is not necessarily 24 hours long. */
internal fun guideDayAt(timeMillis: Long, dayDelta: Int, zone: ZoneId = ZoneId.systemDefault()): Long =
    Instant.ofEpochMilli(timeMillis).atZone(zone).plusDays(dayDelta.toLong()).toInstant().toEpochMilli()

/** Shifts an epoch timestamp by the requested whole-hour offset, checking arithmetic overflow. */
internal fun guideHourAt(timeMillis: Long, hourDelta: Int): Long =
    Math.addExact(timeMillis, Math.multiplyExact(hourDelta.toLong(), GUIDE_HOUR_MILLIS))

/** Converts a horizontal guide offset to an epoch time using timeline scale. */
internal fun guideTimeAtOffset(windowStartMillis: Long, offsetDp: Float, minuteWidthDp: Float): Long {
    require(minuteWidthDp > 0f)
    return windowStartMillis + (offsetDp.coerceAtLeast(0f) / minuteWidthDp * 60_000.0).toLong()
}

/** Converts an epoch time to its horizontal offset within the guide timeline. */
internal fun guideOffsetDp(windowStartMillis: Long, timeMillis: Long, minuteWidthDp: Float): Float =
    (((timeMillis - windowStartMillis).coerceIn(0L, GUIDE_WINDOW_MILLIS) / 60_000.0) * minuteWidthDp).toFloat()

/** Program interval clipped to the visible timeline for guide cell layout. */
internal data class GuideProgrammeSlice(val programme: Programme, val startMillis: Long, val endMillis: Long)

/** Clip invalid/overlapping provider data without borrowing width from the next programme. */
internal fun guideProgrammeSlices(programmes: List<Programme>, windowStartMillis: Long): List<GuideProgrammeSlice> {
    val windowEnd = windowStartMillis + GUIDE_WINDOW_MILLIS
    var cursor = windowStartMillis
    return programmes.sortedWith(compareBy<Programme> { it.startUtcMillis }.thenBy { it.id }).mapNotNull { programme ->
        val start = maxOf(cursor, programme.startUtcMillis, windowStartMillis)
        val end = minOf(programme.endUtcMillis, windowEnd)
        if (end <= start) null else GuideProgrammeSlice(programme, start, end).also { cursor = end }
    }
}

/** Keep the nearest neighbours composed for focus, while omitting offscreen programme cells. */
internal fun visibleGuideSlices(
    slices: List<GuideProgrammeSlice>,
    visibleStartMillis: Long,
    visibleEndMillis: Long,
): List<GuideProgrammeSlice> {
    if (slices.isEmpty()) return emptyList()
    val first = slices.indexOfFirst { it.endMillis > visibleStartMillis }
    if (first < 0) return slices.takeLast(1)
    val last = slices.indexOfLast { it.startMillis < visibleEndMillis }.coerceAtLeast(first)
    return slices.subList((first - 1).coerceAtLeast(0), (last + 2).coerceAtMost(slices.size))
}

/** Preserve a time column when moving vertically, with deterministic fallback across EPG gaps. */
internal fun guideProgrammeAt(programmes: List<Programme>, timeMillis: Long): Programme? =
    programmes.firstOrNull { it.isLiveAt(timeMillis) }
        ?: programmes.filter { it.endUtcMillis > it.startUtcMillis }
            .minWithOrNull(compareBy<Programme> {
                when {
                    timeMillis < it.startUtcMillis -> it.startUtcMillis - timeMillis
                    else -> timeMillis - it.endUtcMillis
                }
            }.thenBy { it.startUtcMillis })

/** A bounded visible slice, including a small prefetch margin, independent of catalogue size. */
internal fun guideVisibleRange(rowCount: Int, visibleIndices: List<Int>): IntRange {
    if (rowCount <= 0) return IntRange.EMPTY
    val first = visibleIndices.minOrNull() ?: 0
    val last = visibleIndices.maxOrNull() ?: 9
    val start = (first - 10).coerceIn(0, rowCount - 1)
    return start..minOf(rowCount - 1, last + 10, start + 59)
}
