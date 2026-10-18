/* Copyright (C) 2026 Nuvix Contributors. GPL-3.0-or-later. */
package app.opentv.recording

import app.opentv.data.model.Recording
import app.opentv.data.model.RecordingStatus
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Regression tests for recording start timing. */
class RecordingStartTimingTest {
    /** Checks: legacy Padded Windows Do Not Establish Programme Start. */
    @Test fun legacyPaddedWindowsDoNotEstablishProgrammeStart() {
        assertFalse(RecordingService.isLateStart(recording(0), 90_000))
        assertFalse(RecordingService.isLateStart(recording(-1), 90_000))
    }

    /** Checks: known Programme Start Includes Five Second Grace At Both Start Checks. */
    @Test fun knownProgrammeStartIncludesFiveSecondGraceAtBothStartChecks() {
        val recording = recording(10_000)
        assertFalse(RecordingService.isLateStart(recording, 9_999))
        assertFalse(RecordingService.isLateStart(recording, 10_000))
        assertFalse(RecordingService.isLateStart(recording, 14_999))
        assertFalse(RecordingService.isLateStart(recording, 15_000))
        assertTrue(RecordingService.isLateStart(recording, 15_001))
    }

    /** Checks: first Byte Arriving After The Grace Period Still Marks The Capture Late. */
    @Test fun firstByteArrivingAfterTheGracePeriodStillMarksTheCaptureLate() {
        val recording = recording(10_000)
        assertFalse(RecordingService.isLateStart(recording, 10_500))
        assertTrue(RecordingService.isLateStart(recording, 16_000))
        assertFalse(RecordingService.isLateStart(recording(Long.MAX_VALUE - 1_000), Long.MAX_VALUE))
    }

    /** Provides the recording fixture used by the current scheduling or capture scenario. */
    private fun recording(programmeStart: Long) = Recording(
        channelId = 1, sourceId = 1, channelName = "News", title = "News",
        streamUrl = "https://example.invalid/live.ts", filePath = "/recordings/test.ts",
        status = RecordingStatus.SCHEDULED,
        programmeStartMillis = programmeStart, scheduledStartMillis = 1_000, scheduledEndMillis = 100_000,
    )
}
