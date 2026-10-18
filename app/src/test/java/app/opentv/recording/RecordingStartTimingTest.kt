/* Copyright (C) 2026 Nuvix Contributors. GPL-3.0-or-later. */
package app.opentv.recording

import app.opentv.data.model.Recording
import app.opentv.data.model.RecordingStatus
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordingStartTimingTest {
    @Test fun legacyPaddedWindowsDoNotEstablishProgrammeStart() {
        assertFalse(RecordingService.isLateStart(recording(0), 90_000))
        assertFalse(RecordingService.isLateStart(recording(-1), 90_000))
    }

    @Test fun knownProgrammeStartIncludesFiveSecondGraceAtBothStartChecks() {
        val recording = recording(10_000)
        assertFalse(RecordingService.isLateStart(recording, 9_999))
        assertFalse(RecordingService.isLateStart(recording, 10_000))
        assertFalse(RecordingService.isLateStart(recording, 14_999))
        assertFalse(RecordingService.isLateStart(recording, 15_000))
        assertTrue(RecordingService.isLateStart(recording, 15_001))
    }

    @Test fun firstByteArrivingAfterTheGracePeriodStillMarksTheCaptureLate() {
        val recording = recording(10_000)
        assertFalse(RecordingService.isLateStart(recording, 10_500))
        assertTrue(RecordingService.isLateStart(recording, 16_000))
        assertFalse(RecordingService.isLateStart(recording(Long.MAX_VALUE - 1_000), Long.MAX_VALUE))
    }

    private fun recording(programmeStart: Long) = Recording(
        channelId = 1, sourceId = 1, channelName = "News", title = "News",
        streamUrl = "https://example.invalid/live.ts", filePath = "/recordings/test.ts",
        status = RecordingStatus.SCHEDULED,
        programmeStartMillis = programmeStart, scheduledStartMillis = 1_000, scheduledEndMillis = 100_000,
    )
}
