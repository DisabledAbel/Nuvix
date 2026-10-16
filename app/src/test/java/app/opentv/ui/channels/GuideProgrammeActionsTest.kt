/* Nuvix modifications Copyright (C) 2026 Nuvix Contributors. GPL-3.0-or-later. */
package app.opentv.ui.channels

import app.opentv.data.model.Channel
import app.opentv.data.model.Programme
import app.opentv.data.model.Source
import app.opentv.data.model.SourceKind
import org.junit.Assert.*
import org.junit.Test

class GuideProgrammeActionsTest {
    private val now = 1_000_000_000L
    private val channel = Channel(id = 1, sourceId = 2, streamId = "42", name = "Channel",
        categoryId = null, logoUrl = null, epgChannelId = "channel", number = 1,
        streamUrl = "https://provider.invalid/live/42.ts", tvArchive = true, tvArchiveDays = 2)
    private val source = Source(id = 2, name = "Provider", kind = SourceKind.XTREAM,
        url = "https://provider.invalid", username = "authorized-user", password = "authorized-password")
    private fun programme(start: Long = now - 1000, end: Long = now + 1000) =
        Programme(feedId = 1, epgChannelId = "channel", startUtcMillis = start, endUtcMillis = end, title = "Programme")
    private fun actions(programme: Programme = programme(), channel: Channel = this.channel, source: Source? = this.source) =
        GuideProgrammeActions.evaluate(programme, channel, source, now)

    @Test fun `airing starts inclusively ends exclusively and never offers reminders`() {
        val airing = actions(programme(now, now + 1000))
        assertEquals(GuideProgrammeStatus.AIRING, airing.status)
        assertTrue(airing.canWatchNow)
        assertTrue(airing.canRecordNow)
        assertFalse(airing.canSetReminder)
        assertFalse(airing.canScheduleRecording)
        assertFalse(airing.canCatchup)
        val ended = actions(programme(now - 1000, now))
        assertEquals(GuideProgrammeStatus.ENDED, ended.status)
        assertFalse(ended.canWatchNow)
        assertFalse(ended.canRecordNow)
        assertFalse(ended.canSetReminder)
        assertFalse(ended.canScheduleRecording)
        assertTrue(ended.canCatchup)
    }

    @Test fun `strictly future programmes offer reminders and scheduling without retuning`() {
        val upcoming = actions(programme(now + 1, now + 1000))
        assertEquals(GuideProgrammeStatus.UPCOMING, upcoming.status)
        assertTrue(upcoming.canSetReminder)
        assertTrue(upcoming.canScheduleRecording)
        assertFalse(upcoming.canWatchNow)
        assertFalse(upcoming.canRecordNow)
        assertFalse(upcoming.canCatchup)
        assertTrue(upcoming.canViewChannel)
    }

    @Test fun `invalid and overflowing windows expose no programme actions`() {
        for (invalid in listOf(programme(now, now), programme(now + 1, now),
            programme(-1, now), programme(Long.MIN_VALUE, Long.MAX_VALUE))) {
            val result = actions(invalid)
            assertEquals(GuideProgrammeStatus.INVALID, result.status)
            assertFalse(result.canWatchNow)
            assertFalse(result.canSetReminder)
            assertFalse(result.canRecordNow)
            assertFalse(result.canScheduleRecording)
            assertFalse(result.canRecordSeries)
            assertFalse(result.canCatchup)
            assertTrue(result.canViewChannel)
        }
    }

    @Test fun `recording requires matching enabled source and direct HTTP stream`() {
        assertTrue(actions(source = source.copy(kind = SourceKind.M3U)).canRecordNow)
        val stalker = actions(source = source.copy(kind = SourceKind.STALKER))
        assertTrue(stalker.canWatchNow)
        assertFalse(stalker.canRecordNow)
        assertFalse(actions(programme(now + 1, now + 1000), source = source.copy(kind = SourceKind.STALKER)).canScheduleRecording)
        for (unsupported in listOf(source.copy(enabled = false), source.copy(id = 3), null)) {
            val result = actions(source = unsupported)
            assertFalse(result.canRecordNow)
            assertFalse(result.canWatchNow)
            assertFalse(result.canViewChannel)
            assertFalse(actions(programme(now + 1, now + 1000), source = unsupported).canSetReminder)
        }
        for (url in listOf("", "stalker://command", "file:///local.ts", "ftp://provider/live.ts", "https://")) {
            assertFalse(actions(channel = channel.copy(streamUrl = url)).canRecordNow)
        }
    }

    @Test fun `catchup requires authorized Xtream archive and honors known retention`() {
        val past = programme(now - 1000, now)
        assertTrue(actions(past).canCatchup)
        assertTrue(actions(past, channel.copy(tvArchiveDays = 0)).canCatchup)
        assertFalse(actions(past, channel.copy(tvArchive = false)).canCatchup)
        assertFalse(actions(past, source = source.copy(kind = SourceKind.M3U)).canCatchup)
        assertFalse(actions(past, source = source.copy(kind = SourceKind.STALKER)).canCatchup)
        assertFalse(actions(past, source = source.copy(password = null)).canCatchup)
        assertFalse(actions(past, source = source.copy(enabled = false)).canCatchup)
        assertFalse(actions(programme(now - 3 * 86_400_000L, now - 2 * 86_400_000L)).canCatchup)
        assertTrue(actions(programme(now - 2 * 86_400_000L, now - 86_400_000L)).canCatchup)
    }

    @Test fun `series action requires valid episode metadata and supported recording source`() {
        assertFalse(actions().canRecordSeries)
        assertTrue(actions(programme().copy(season = 0)).canRecordSeries)
        assertTrue(actions(programme(now + 1, now + 1000).copy(episode = 2)).canRecordSeries)
        assertFalse(actions(programme().copy(season = -1, episode = -1)).canRecordSeries)
        assertFalse(actions(programme().copy(episode = 2), source = source.copy(kind = SourceKind.STALKER)).canRecordSeries)
        assertFalse(actions(programme(now - 1000, now).copy(episode = 2)).canRecordSeries)
    }

    @Test fun `existing recording protects its provider while another provider remains available`() {
        val busy = setOf(source.id)
        val airing = GuideProgrammeActions.evaluate(programme().copy(episode = 1), channel, source, now, busy)
        assertFalse(airing.canRecordNow)
        assertFalse(airing.canRecordSeries)
        val past = programme(now - 1000, now)
        assertFalse(GuideProgrammeActions.evaluate(past, channel, source, now, busy).canCatchup)
        assertTrue(GuideProgrammeActions.evaluate(past, channel, source, now, setOf(99)).canCatchup)
        val future = GuideProgrammeActions.evaluate(programme(now + 1, now + 1000), channel, source, now, busy)
        assertTrue(future.canSetReminder)
        assertTrue(future.canScheduleRecording)
        assertTrue(GuideProgrammeActions.canRecordChannel(channel, source))
        assertFalse(GuideProgrammeActions.canRecordChannel(channel, source.copy(kind = SourceKind.STALKER)))
    }
}
