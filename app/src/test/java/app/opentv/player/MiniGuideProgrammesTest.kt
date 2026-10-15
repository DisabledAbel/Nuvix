/* Copyright (C) 2026 Nuvix Contributors. GPL-3.0-or-later. */
package app.opentv.player

import app.opentv.data.model.Programme
import com.google.common.truth.Truth.assertThat
import java.time.Instant
import org.junit.Test

class MiniGuideProgrammesTest {
    private fun programme(start: Long, end: Long, title: String = "Programme") =
        Programme(feedId = 1, epgChannelId = "test", startUtcMillis = start, endUtcMillis = end, title = title)

    @Test fun `missing stale and invalid schedules have no current programme`() {
        assertThat(MiniGuideProgrammes.at(emptyList(), 50)).isEqualTo(MiniGuideProgrammes())
        assertThat(MiniGuideProgrammes.at(listOf(programme(0, 20), programme(60, 60), programme(80, 70)), 50)).isEqualTo(MiniGuideProgrammes())
    }

    @Test fun `now and next are selected from unsorted data and ignore overlaps`() {
        val current = programme(0, 100, "Current")
        val next = programme(100, 200, "Next")
        val data = MiniGuideProgrammes.at(listOf(next, programme(20, 80, "Overlap"), current), 50)
        assertThat(data.current).isEqualTo(current)
        assertThat(data.next).isEqualTo(next)
    }

    @Test fun `end boundary belongs to new programme and gaps retain only next`() {
        val a = programme(0, 100)
        val b = programme(100, 200)
        assertThat(MiniGuideProgrammes.at(listOf(a, b), 100).current).isEqualTo(b)
        assertThat(MiniGuideProgrammes.at(listOf(a, programme(200, 300)), 150).current).isNull()
        assertThat(MiniGuideProgrammes.at(listOf(a, programme(200, 300)), 150).next?.startUtcMillis).isEqualTo(200)
    }

    @Test fun `progress clamps before start at start halfway end and after end`() {
        val p = programme(100, 200)
        assertThat(p.progressAt(0)).isEqualTo(0f)
        assertThat(p.progressAt(100)).isEqualTo(0f)
        assertThat(p.progressAt(150)).isEqualTo(0.5f)
        assertThat(p.progressAt(200)).isEqualTo(1f)
        assertThat(p.progressAt(300)).isEqualTo(1f)
        assertThat(programme(100, 100).progressAt(100)).isEqualTo(0f)
        assertThat(programme(200, 100).progressAt(150)).isEqualTo(0f)
    }

    @Test fun `UTC matching crosses midnight and daylight saving without local time arithmetic`() {
        val start = Instant.parse("2026-11-01T08:30:00Z").toEpochMilli()
        val p = programme(start, start + 7_200_000)
        assertThat(MiniGuideProgrammes.at(listOf(p), start + 3_600_000).current).isEqualTo(p)
        val midnight = Instant.parse("2026-10-15T00:00:00Z").toEpochMilli()
        val overnight = programme(midnight - 1_800_000, midnight + 1_800_000)
        assertThat(overnight.progressAt(midnight)).isEqualTo(0.5f)
        assertThat(MiniGuideProgrammes.at(listOf(overnight), midnight).current).isEqualTo(overnight)
        assertThat(overnight.description).isNull()
    }
}
