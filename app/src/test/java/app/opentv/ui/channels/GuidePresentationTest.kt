/* Nuvix modifications Copyright (C) 2026 Nuvix Contributors. GPL-3.0-or-later. */
package app.opentv.ui.channels

import app.opentv.core.GuideLayout
import app.opentv.core.GuideTimelineDensity
import app.opentv.data.model.Programme
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

class GuidePresentationTest {
    private val start = 1_800_000_000_000L
    private fun programme(offsetMinutes: Int, durationMinutes: Int = 30) = Programme(
        id = offsetMinutes.toLong(), feedId = 1, epgChannelId = "test", title = "Programme $offsetMinutes",
        startUtcMillis = start + offsetMinutes * 60_000L,
        endUtcMillis = start + (offsetMinutes + durationMinutes) * 60_000L,
    )

    @Test fun layoutsIncreaseReadableRowSpaceWithoutChangingTimelineAlignment() {
        val compact = guideLayoutMetrics(GuideLayout.COMPACT, GuideTimelineDensity.COMFORTABLE)
        val standard = guideLayoutMetrics(GuideLayout.STANDARD, GuideTimelineDensity.COMFORTABLE)
        val expanded = guideLayoutMetrics(GuideLayout.EXPANDED, GuideTimelineDensity.COMFORTABLE)
        assertThat(compact.rowHeightDp).isLessThan(standard.rowHeightDp)
        assertThat(expanded.rowHeightDp).isGreaterThan(standard.rowHeightDp)
        assertThat(setOf(compact.minuteWidthDp, standard.minuteWidthDp, expanded.minuteWidthDp)).hasSize(1)
        assertThat(compact.rowHeightDp).isAtLeast(48f)
    }

    @Test fun densitiesChangeVisibleTimeWhileOffsetsRoundTripAtSubMinutePrecision() {
        val widths = GuideTimelineDensity.entries.map { guideLayoutMetrics(GuideLayout.STANDARD, it).minuteWidthDp }
        assertThat(widths.toSet()).hasSize(3)
        widths.forEach { width ->
            val time = start + 90_000L
            assertThat(guideTimeAtOffset(start, guideOffsetDp(start, time, width), width)).isEqualTo(time)
        }
        assertThat(guideOffsetDp(start, start - 1, 4f)).isEqualTo(0f)
    }

    @Test fun slicesClipWindowAndOverlapsAndDiscardInvalidDataWithoutTimelineDrift() {
        val source = listOf(programme(10, 20), programme(-15, 30), programme(0, 0), programme(1430, 40), programme(1500))
        val slices = guideProgrammeSlices(source, start)
        assertThat(slices.map { (it.startMillis - start) / 60_000L to (it.endMillis - start) / 60_000L })
            .containsExactly(0L to 15L, 15L to 30L, 1430L to 1440L).inOrder()
        assertThat(source).hasSize(5)
    }

    @Test fun secondLongProgrammesKeepTheirTrueTimelinePosition() {
        val short = programme(0).copy(endUtcMillis = start + 1_000L)
        val next = programme(1)
        val slices = guideProgrammeSlices(listOf(short, next), start)
        assertThat(slices[0].endMillis - slices[0].startMillis).isEqualTo(1_000L)
        assertThat(slices[1].startMillis).isEqualTo(start + 60_000L)
    }

    @Test fun horizontalVirtualizationKeepsOnlyViewportAndAdjacentCells() {
        val slices = guideProgrammeSlices((0 until 1440 step 5).map { programme(it, 5) }, start)
        val visible = visibleGuideSlices(slices, start + 600 * 60_000L, start + 660 * 60_000L)
        assertThat(slices).hasSize(288)
        assertThat(visible).hasSize(14)
        assertThat(visible.first().programme.startUtcMillis).isEqualTo(start + 595 * 60_000L)
        assertThat(visible.last().programme.startUtcMillis).isEqualTo(start + 660 * 60_000L)
    }

    @Test fun missingAndIncompleteGuideDataHasDeterministicFocusFallback() {
        assertThat(guideProgrammeSlices(emptyList(), start)).isEmpty()
        assertThat(visibleGuideSlices(emptyList(), start, start + 1)).isEmpty()
        assertThat(guideProgrammeAt(emptyList(), start)).isNull()
        val programmes = listOf(programme(0), programme(60), programme(120, -1))
        assertThat(guideProgrammeAt(programmes, start + 10 * 60_000L)).isEqualTo(programmes[0])
        assertThat(guideProgrammeAt(programmes, start + 50 * 60_000L)).isEqualTo(programmes[1])
        assertThat(guideProgrammeAt(programmes, start + 45 * 60_000L)).isEqualTo(programmes[0])
    }

    @Test fun hourAndDayNavigationPreserveTheRequestedTimeAcrossDaylightSaving() {
        val zone = ZoneId.of("America/Los_Angeles")
        val beforeSpringChange = Instant.parse("2026-03-07T20:00:00Z").toEpochMilli()
        val nextDay = guideDayAt(beforeSpringChange, 1, zone)
        assertThat(nextDay).isEqualTo(Instant.parse("2026-03-08T19:00:00Z").toEpochMilli())
        assertThat(guideDayAt(nextDay, -1, zone)).isEqualTo(beforeSpringChange)
        assertThat(guideHourAt(beforeSpringChange, 1) - beforeSpringChange).isEqualTo(GUIDE_HOUR_MILLIS)
        assertThat(guideHourAt(beforeSpringChange, -1) + GUIDE_HOUR_MILLIS).isEqualTo(beforeSpringChange)
    }

    @Test fun visiblePrefetchStaysBoundedForHugeCataloguesAndAtBothEdges() {
        assertThat(guideVisibleRange(20_000, (5000..5010).toList())).isEqualTo(4990..5020)
        assertThat(guideVisibleRange(20_000, (5000..5100).toList()).count()).isEqualTo(60)
        assertThat(guideVisibleRange(3, listOf(0))).isEqualTo(0..2)
        assertThat(guideVisibleRange(100, listOf(99))).isEqualTo(89..99)
        assertThat(guideVisibleRange(0, emptyList()).toList()).isEmpty()
        assertThat(guideVisibleRange(20_000, emptyList()).count()).isAtMost(60)
    }
}
