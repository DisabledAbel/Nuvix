/* Copyright (C) 2026 Nuvix Contributors. GPL-3.0-or-later. */
package app.opentv.recording

import app.opentv.data.model.*
import app.opentv.recording.RecordingLateStart.Decision.Action.*
import org.junit.Assert.*
import org.junit.Test

class RecordingLateStartTest {
    private val start = 1_000_000L
    private val end = start + 60 * 60_000L
    private fun row() = Recording(id = 1, channelId = 1, sourceId = 1, channelName = "News", title = "News",
        filePath = "/missing.ts", streamUrl = "https://secret.invalid/user/token", status = RecordingStatus.SCHEDULED,
        scheduledStartMillis = start - 120_000, programmeStartMillis = start,
        programmeEndMillis = end, scheduledEndMillis = end + 300_000)
    private fun decision(at: Long, policy: LateStartPolicy = LateStartPolicy.RECORD_REMAINING,
        minutes: Int = 10, r: Recording = row()) = RecordingLateStart.evaluate(r, at, LateStartOptions(policy, minutes))

    @Test fun remainingIsDefaultAndDoesNotCompensateEnd() {
        val r = row()
        val d = RecordingLateStart.evaluate(r, start + 40 * 60_000, RecordingLateStart.resolve(r, null, LateStartOptions(LateStartPolicy.RECORD_REMAINING)))
        assertEquals(CAPTURE, d.action); assertTrue(d.contentMissed)
        assertEquals(40 * 60_000L, d.delayMillis); assertEquals(end + 300_000, r.scheduledEndMillis)
    }
    @Test fun optionalPaddingAndInclusiveGraceDoNotMakeCompleteProgramPartial() {
        for (at in listOf(start - 120_000, start - 1, start, start + 4_999, start + 5_000)) {
            for (policy in listOf(LateStartPolicy.RECORD_REMAINING, LateStartPolicy.SKIP_IF_LATE)) {
                assertEquals(CAPTURE, decision(at, policy).action)
                assertFalse(decision(at, policy).contentMissed)
            }
        }
        assertTrue(decision(start + 5_001).contentMissed)
        assertEquals(SKIP, decision(start + 5_001, LateStartPolicy.SKIP_IF_LATE).action)
    }
    @Test fun minimumEqualityIsInclusiveAndCustomThresholdIgnoresPaddingAndExtensions() {
        assertEquals(CAPTURE, decision(end - 15 * 60_000, LateStartPolicy.MINIMUM_REMAINING, 15).action)
        assertEquals(SKIP, decision(end - 15 * 60_000 + 1, LateStartPolicy.MINIMUM_REMAINING, 15).action)
        assertEquals(CAPTURE, decision(end - 7 * 60_000, LateStartPolicy.MINIMUM_REMAINING, 7).action)
        assertEquals(SKIP, decision(end - 7 * 60_000 + 1, LateStartPolicy.MINIMUM_REMAINING, 7,
            row().copy(scheduledEndMillis = end + 3_600_000, manualExtensionMillis = 3_300_000, durationRevision = 3)).action)
    }
    @Test fun endPaddingCanBePartialForRemainingButCannotSatisfyMinimum() {
        assertEquals(CAPTURE, decision(end + 1).action)
        assertEquals(SKIP, decision(end + 1, LateStartPolicy.MINIMUM_REMAINING, 1).action)
        assertEquals(SKIP, decision(end + 300_000).action)
        assertEquals(SKIP, decision(end + 300_001).action)
    }
    @Test fun usefulCaptureRetainsRecoveryRatherThanBeingSkippedOnReconnect() {
        for (policy in LateStartPolicy.entries) {
            val active = row().copy(status = RecordingStatus.RECORDING, startedAtMillis = start, sizeBytes = 564)
            assertEquals(CAPTURE, decision(end + 900_000, policy, r = active).action)
        }
        // An old PREPARING launch timestamp is explicitly not evidence of capture.
        assertEquals(SKIP, decision(start + 6_000, LateStartPolicy.SKIP_IF_LATE,
            r = row().copy(status = RecordingStatus.PREPARING, startedAtMillis = start)).action)
    }
    @Test fun terminalAndCanceledRowsIgnoreDuplicateDelivery() {
        for (status in listOf(RecordingStatus.COMPLETED, RecordingStatus.PARTIAL, RecordingStatus.FAILED,
            RecordingStatus.CANCELED, RecordingStatus.MISSED, RecordingStatus.FINALIZING))
            assertEquals(IGNORE, decision(start, r = row().copy(status = status)).action)
    }
    @Test fun futureAlarmClockCorrectionsAndEpochArithmeticAreDeterministic() {
        assertEquals(WAIT, decision(start - 120_001).action)
        assertEquals(SKIP, decision(start + 6_000, LateStartPolicy.SKIP_IF_LATE).action)
        assertEquals(CAPTURE, decision(start + 5_000, LateStartPolicy.SKIP_IF_LATE).action)
        val nearMax = row().copy(programmeStartMillis = Long.MAX_VALUE - 1_000,
            programmeEndMillis = Long.MAX_VALUE, scheduledEndMillis = 0)
        assertFalse(RecordingLateStart.isLate(nearMax, Long.MAX_VALUE))
    }
    @Test fun unknownAndInvalidBoundsCannotInventProgramCoverage() {
        val legacy = row().copy(programmeStartMillis = 0, programmeEndMillis = 0)
        assertEquals(CAPTURE, decision(start, r = legacy).action)
        assertFalse(decision(start, r = legacy).contentMissed)
        assertEquals(SKIP, decision(start, LateStartPolicy.SKIP_IF_LATE, r = legacy).action)
        assertEquals(SKIP, decision(start, LateStartPolicy.MINIMUM_REMAINING, r = legacy).action)
        assertEquals(SKIP, decision(start, r = row().copy(programmeEndMillis = start - 1)).action)
    }
    @Test fun inheritanceIsEpisodeThenSeriesThenCurrentGlobalAndUnknownStringsAreSafe() {
        val r = row()
        val series = SeriesRule(id = 9, channelId = 1, channelName = "News", titleKey = "news", title = "News")
        val skip = LateStartOptions(LateStartPolicy.SKIP_IF_LATE)
        assertEquals(skip, RecordingLateStart.resolve(r, series, skip))
        val rule = series.copy(lateStartPolicy = "MINIMUM_REMAINING", minimumRemainingMinutes = 15)
        assertEquals(LateStartOptions(LateStartPolicy.MINIMUM_REMAINING, 15), RecordingLateStart.resolve(r, rule, skip))
        assertEquals(LateStartPolicy.RECORD_REMAINING, RecordingLateStart.resolve(r.copy(lateStartPolicy = "RECORD_REMAINING"), rule, skip).policy)
        assertEquals(LateStartPolicy.RECORD_REMAINING, RecordingLateStart.resolve(r.copy(lateStartPolicy = "FUTURE_POLICY"), rule, skip).policy)
    }
    @Test fun thresholdsAreValidatedAndStoredInvalidValuesAreBounded() {
        for (value in listOf(0, -1, 1441, Int.MAX_VALUE))
            assertThrows(IllegalArgumentException::class.java) { LateStartOptions(LateStartPolicy.MINIMUM_REMAINING, value) }
        val resolved = RecordingLateStart.resolve(row().copy(lateStartPolicy = "MINIMUM_REMAINING", minimumRemainingMinutes = -3), null, LateStartOptions())
        assertEquals(1, resolved.minimumMinutes)
    }
    @Test fun historyContainsTimingPolicyAndContentDecisionWithoutSecrets() {
        val at = start + 6_000
        val text = RecordingLateStart.history(row(), at, decision(at))
        for (word in listOf("programStart=$start", "programEnd=$end", "delayMillis=6000", "contentMissed=true", "RECORD_REMAINING")) assertTrue(text.contains(word))
        for (secret in listOf("https://", "secret.invalid", "token", "/missing.ts")) assertFalse(text.contains(secret))
    }
}
