/* Copyright (C) 2026 Nuvix Contributors. GPL-3.0-or-later. */
package app.opentv.recording

import app.opentv.data.model.Recording
import app.opentv.data.model.RecordingStatus
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import java.util.TimeZone

class RecordingDurationTest {
    private fun row() = Recording(id = 1, channelId = 1, sourceId = 1, channelName = "Game", title = "Game",
        filePath = "/game.ts", streamUrl = "https://private.invalid/token", status = RecordingStatus.RECORDING,
        scheduledStartMillis = 1000, startedAtMillis = 1000, programmeEndMillis = 600_000,
        scheduledEndMillis = 900_000, padEndMinutes = 5, protectedRecording = true, lockPadding = true)

    @Test fun allIncrementsAddToEffectiveEndExactlyOnceWithoutDoublePadding() {
        for (minutes in listOf(5, 10, 15, 30, 60)) {
            assertEquals(900_000L + minutes * 60_000, RecordingDuration.proposedEnd(row(), DurationChoice.Add(minutes), 100_000))
        }
    }
    @Test fun customShortenStopAndRestorePreserveOriginalPaddingOverrides() {
        val r = row().copy(padEndMinutes = 10, scheduledEndMillis = 1_200_000)
        assertEquals(800_000, RecordingDuration.proposedEnd(r, DurationChoice.EndAt(800_000), 100_000))
        assertEquals(100_000, RecordingDuration.proposedEnd(r, DurationChoice.Stop, 100_000))
        assertEquals(r.startedAtMillis, RecordingDuration.proposedEnd(r, DurationChoice.Stop, 500))
        assertEquals(1_200_000, RecordingDuration.proposedEnd(r.copy(scheduledEndMillis = 2_000_000), DurationChoice.Restore, 100_000))
        assertEquals(900_000, RecordingDuration.originalEnd(r.copy(originalEndMillis = 900_000, programmeEndMillis = 700_000)))
    }
    @Test fun legacyRestoreUsesSavedWindowAndUnboundedRecordingCannotRestore() {
        val legacy = row().copy(programmeEndMillis = 0)
        assertEquals(900_000, RecordingDuration.originalEnd(legacy))
        assertEquals(400_000, RecordingDuration.proposedEnd(legacy.copy(scheduledEndMillis = 0), DurationChoice.Add(5), 100_000))
        assertThrows(IllegalArgumentException::class.java) { RecordingDuration.proposedEnd(legacy.copy(scheduledEndMillis = 0), DurationChoice.Restore, 100_000) }
    }
    @Test fun invalidEndFinalizedAndOverflowAreRejected() {
        assertThrows(IllegalArgumentException::class.java) { RecordingDuration.proposedEnd(row(), DurationChoice.EndAt(999), 100_000) }
        assertThrows(IllegalArgumentException::class.java) { RecordingDuration.proposedEnd(row(), DurationChoice.Add(1), 100_000) }
        for (status in listOf(RecordingStatus.FINALIZING, RecordingStatus.CANCELED, RecordingStatus.COMPLETED, RecordingStatus.PARTIAL))
            assertThrows(IllegalArgumentException::class.java) { RecordingDuration.proposedEnd(row().copy(status = status), DurationChoice.Add(5), 100_000) }
        assertThrows(ArithmeticException::class.java) { RecordingDuration.proposedEnd(row().copy(scheduledEndMillis = Long.MAX_VALUE), DurationChoice.Add(60), 100_000) }
    }
    @Test fun timezoneAndDstDoNotChangeEpochArithmetic() {
        val original = TimeZone.getDefault()
        try {
            for (zone in listOf("UTC", "America/Los_Angeles", "Europe/London", "Pacific/Auckland")) {
                TimeZone.setDefault(TimeZone.getTimeZone(zone))
                for (end in listOf(1772963940000L, 1793523540000L))
                    assertEquals(end + 3_600_000, RecordingDuration.proposedEnd(row().copy(scheduledEndMillis = end), DurationChoice.Add(60), end - 1))
            }
        } finally { TimeZone.setDefault(original) }
    }
    @Test fun storageUsesObservedRateOrConservativeFallback() {
        assertEquals(300_000_000, RecordingDuration.estimatedBytes(row(), 300_000, 2000))
        assertEquals(30_000, RecordingDuration.estimatedBytes(row().copy(sizeBytes = 1000), 300_000, 11_000))
        assertEquals(0, RecordingDuration.estimatedBytes(row(), -1, 2000))
    }
    @Test fun staleStopClaimCannotCloseExtendedCaptureAndLatestDeadlineWins() = runTest {
        var r = row().copy(scheduledEndMillis = 100_000)
        var now = 100_000L
        var stops = 0
        var claims = 0
        RecordingDeadlineWatcher(read = { r }, claim = { rev, at ->
            claims++
            if (claims == 1) r = r.copy(durationRevision = 1, scheduledEndMillis = 200_000)
            rev == r.durationRevision && r.scheduledEndMillis <= at
        }, elapsed = { stops++ }, now = { now }, wait = { now = 200_000 }).run()
        assertEquals(2, claims)
        assertEquals(1, stops)
    }
    @Test fun watcherObservesShorteningAndStopsOnlyItsOwnSession() = runTest {
        var r = row()
        var waits = 0
        var stops = 0
        RecordingDeadlineWatcher(read = { r }, claim = { rev, _ -> rev == 1L }, elapsed = { stops++ },
            now = { 100_000 }, wait = { waits++; r = r.copy(scheduledEndMillis = 99_999, durationRevision = 1) }).run()
        assertEquals(1, waits)
        assertEquals(1, stops)
    }
    @Test fun canceledOrFinalizedWorkerNeverFiresDeadline() = runTest {
        for (status in listOf(RecordingStatus.COMPLETED, RecordingStatus.FINALIZING, RecordingStatus.CANCELED)) {
            RecordingDeadlineWatcher(read = { row().copy(status = status) }, claim = { _, _ -> fail("must not claim"); false },
                elapsed = { fail("must not stop") }).run()
        }
    }
}
