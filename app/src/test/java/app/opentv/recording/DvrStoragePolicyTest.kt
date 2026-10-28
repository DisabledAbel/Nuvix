/* Copyright (C) 2026 Nuvix Contributors. GPL-3.0-or-later. */
package app.opentv.recording

import app.opentv.data.model.Recording
import app.opentv.data.model.RecordingStatus
import org.junit.Assert.*
import org.junit.Test

class DvrStoragePolicyTest {
    private val now = 100 * 86_400_000L
    private fun capacity(free: Long = 5 * DvrStoragePolicy.GIB) = StorageCapacity(StorageAvailability.AVAILABLE, now, free, 20 * DvrStoragePolicy.GIB)
    private fun row(id: Long = 1) = Recording(id = id, channelId = 1, sourceId = 1, channelName = "Synthetic", title = "Synthetic",
        filePath = "/synthetic.ts", streamUrl = "https://example.invalid/private", status = RecordingStatus.COMPLETED,
        startedAtMillis = now - 3_600_000, endedAtMillis = now, sizeBytes = DvrStoragePolicy.GIB)

    @Test fun capabilitiesRejectUnknownMalformedStaleAndFutureCapacity() {
        for (c in listOf(capacity().copy(availableBytes = null), capacity().copy(totalBytes = null),
            capacity().copy(totalBytes = 0), capacity().copy(availableBytes = -1), capacity().copy(availableBytes = Long.MAX_VALUE),
            capacity().copy(checkedAtMillis = now - 60_001), capacity().copy(checkedAtMillis = now + 1))) {
            assertFalse(c.reliable(now))
            assertNull(DvrStorageRules.remainingHours(c, listOf(row()), now))
            assertNull(DvrStorageRules.lowSpace(c, DvrStoragePolicy(), now))
        }
        for (state in StorageAvailability.entries.filter { it != StorageAvailability.AVAILABLE })
            assertFalse(capacity().copy(availability = state).reliable(now))
        assertTrue(capacity().copy(availableBytes = 0).reliable(now))
        assertTrue(capacity().copy(checkedAtMillis = now - 60_000).reliable(now))
    }

    @Test fun estimateUsesMeasuredDurationLargestRecentBitrateAndCaptureReserve() {
        val fast = row(2).copy(sizeBytes = 2 * DvrStoragePolicy.GIB)
        assertEquals(2.375, DvrStorageRules.remainingHours(capacity(), listOf(row(), fast), now)!!, 0.001)
        assertEquals(0.0, DvrStorageRules.remainingHours(capacity(1), listOf(row()), now)!!, 0.0)
        for (sample in listOf(row().copy(status = RecordingStatus.RECORDING), row().copy(status = RecordingStatus.PARTIAL),
            row().copy(startedAtMillis = 0), row().copy(sizeBytes = 0),
            row().copy(startedAtMillis = now - 1000), row().copy(endedAtMillis = now - 31 * 86_400_000L)))
            assertNull(DvrStorageRules.remainingHours(capacity(), listOf(sample), now))
        assertNull(DvrStorageRules.remainingHours(capacity(), emptyList(), now))
        assertNull(DvrStorageRules.remainingHours(capacity(), listOf(row(), fast.copy(sizeBytes = 10 * DvrStoragePolicy.GIB)), now))
    }

    @Test fun warningsUseInclusiveConservativeThresholdAndExplicitDisable() {
        val policy = DvrStoragePolicy()
        assertEquals(true, DvrStorageRules.lowSpace(capacity(DvrStoragePolicy.GIB), policy, now))
        assertEquals(false, DvrStorageRules.lowSpace(capacity(DvrStoragePolicy.GIB + 1), policy, now))
        assertEquals(false, DvrStorageRules.lowSpace(capacity(0), policy.copy(warnings = false), now))
        assertEquals(true, DvrStorageRules.lowSpace(capacity(2 * DvrStoragePolicy.GIB).copy(totalBytes = 100 * DvrStoragePolicy.GIB), policy, now))
    }

    @Test fun retentionRequiresOptInOwnershipTerminalStateAndIndependentProtection() {
        val old = row().copy(endedAtMillis = now - 30 * 86_400_000L, lockPadding = true)
        val policy = DvrStoragePolicy(cleanupEnabled = true)
        assertTrue(DvrStorageRules.eligible(old, true, policy, now))
        assertTrue(DvrStorageRules.eligible(old.copy(status = RecordingStatus.PARTIAL), true, policy, now))
        assertTrue(DvrStorageRules.eligible(old.copy(status = RecordingStatus.FAILED), true, policy, now))
        assertFalse(DvrStorageRules.eligible(old, true, DvrStoragePolicy(), now))
        assertFalse(DvrStorageRules.eligible(old, false, policy, now))
        assertFalse(DvrStorageRules.eligible(old.copy(protectedRecording = true), true, policy, now))
        assertFalse(DvrStorageRules.eligible(old.copy(endedAtMillis = old.endedAtMillis + 1), true, policy, now))
        for (state in listOf(RecordingStatus.SCHEDULED, RecordingStatus.PREPARING, RecordingStatus.RECORDING,
            RecordingStatus.FINALIZING, RecordingStatus.DELETED, RecordingStatus.CANCELED, RecordingStatus.MISSED))
            assertFalse(DvrStorageRules.eligible(old.copy(status = state), true, policy, now))
        assertFalse(DvrStorageRules.eligible(old.copy(endedAtMillis = 0), true, policy, now))
        assertFalse(DvrStorageRules.eligible(old.copy(endedAtMillis = now + 1), true, policy, now))
        assertEquals(listOf(1L, 2L), DvrStorageRules.oldestFirst(listOf(old.copy(id = 2), old.copy(id = 1))).map { it.id })
    }

    @Test fun usageExcludesDeletedAndCannotOverflow() {
        assertEquals(10, DvrStorageRules.usage(listOf(row().copy(sizeBytes = 10), row(2).copy(status = RecordingStatus.DELETED))))
        assertEquals(Long.MAX_VALUE, DvrStorageRules.usage(listOf(row().copy(sizeBytes = Long.MAX_VALUE), row(2))))
    }
}
