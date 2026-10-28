/* Copyright (C) 2026 Nuvix Contributors. GPL-3.0-or-later. */
package app.opentv.recording

import app.opentv.data.model.Recording
import app.opentv.data.model.RecordingStatus

enum class StorageAvailability(val explanation: String) {
    AVAILABLE("Available"),
    UNAVAILABLE("Unavailable. Check the destination in Recording settings."),
    DISCONNECTED("Disconnected. Reconnect the drive or NAS and refresh."),
    READ_ONLY("Read-only. Recording and deletion require write access."),
    PERMISSION_DENIED("Permission denied. Grant folder access or check the NAS account permissions."),
}

/** A capacity observation, never an inferred balance after deleting files. */
data class StorageCapacity(
    val availability: StorageAvailability,
    val checkedAtMillis: Long,
    val availableBytes: Long? = null,
    val totalBytes: Long? = null,
) {
    fun fresh(now: Long): Boolean = now >= checkedAtMillis && now - checkedAtMillis <= MAX_AGE_MILLIS
    fun reliable(now: Long): Boolean = availability == StorageAvailability.AVAILABLE && fresh(now) &&
        availableBytes != null && totalBytes != null && totalBytes > 0 && availableBytes in 0..totalBytes

    companion object { const val MAX_AGE_MILLIS = 60_000L }
}

data class DvrStoragePolicy(
    val warnings: Boolean = true,
    val warningBytes: Long = GIB,
    val cleanupEnabled: Boolean = false,
    val targetBytes: Long = 2 * GIB,
    val retentionDays: Int = 30,
) {
    companion object { const val GIB = 1024L * 1024 * 1024 }
}

/** Pure, conservative decisions shared by the UI, worker and pre-delete recheck. */
object DvrStorageRules {
    private val terminal = setOf(RecordingStatus.COMPLETED, RecordingStatus.PARTIAL, RecordingStatus.FAILED)

    fun deletable(row: Recording): Boolean = row.status in terminal && !row.protectedRecording && row.filePath.isNotBlank()

    fun eligible(row: Recording, owned: Boolean, policy: DvrStoragePolicy, now: Long): Boolean =
        policy.cleanupEnabled && owned && deletable(row) && row.endedAtMillis > 0 &&
            row.endedAtMillis <= now && policy.retentionDays in 1..3650 &&
            now - row.endedAtMillis >= policy.retentionDays * 86_400_000L

    fun oldestFirst(rows: List<Recording>): List<Recording> = rows.sortedWith(compareBy({ it.endedAtMillis }, { it.id }))

    /** A warning is advisory; it never changes capture headroom or admission policies. */
    fun lowSpace(capacity: StorageCapacity, policy: DvrStoragePolicy, now: Long): Boolean? {
        if (!capacity.reliable(now)) return null
        return policy.warnings && capacity.availableBytes!! <= maxOf(policy.warningBytes, capacity.totalBytes!! / 20)
    }

    /** Measured, recently completed captures only. Never substitute scheduled duration or a preset bitrate. */
    fun remainingHours(capacity: StorageCapacity, rows: List<Recording>, now: Long): Double? {
        if (!capacity.reliable(now)) return null
        val samples = rows.filter {
            it.status == RecordingStatus.COMPLETED && it.sizeBytes > 0 && it.startedAtMillis > 0 &&
                it.endedAtMillis <= now && now - it.endedAtMillis <= 30 * 86_400_000L &&
                it.endedAtMillis - it.startedAtMillis in 60_000L..86_400_000L
        }.sortedByDescending { it.endedAtMillis }.take(20)
        if (samples.isEmpty()) return null
        val rates = samples.map { it.sizeBytes.toDouble() / (it.endedAtMillis - it.startedAtMillis) }
        if (rates.any { !it.isFinite() || it !in 1.0..125_000.0 } || rates.max() > rates.min() * 4) return null
        // Use the largest measured rate and leave the capture reserve untouched.
        return (capacity.availableBytes!! - RecordingStorage.MIN_FREE_BYTES).coerceAtLeast(0).toDouble() /
            rates.max() / 3_600_000
    }

    fun usage(rows: List<Recording>): Long = rows.filter { it.status != RecordingStatus.DELETED }
        .fold(0L) { sum, row -> if (row.sizeBytes > Long.MAX_VALUE - sum) Long.MAX_VALUE else sum + row.sizeBytes.coerceAtLeast(0) }
}

/** No result can accidentally include a locator, provider URL, credential or backend exception. */
enum class StorageDeleteResult(val explanation: String) {
    DELETED("Recording file deleted; history retained."),
    CHANGED("Recording changed, is active or is protected. Refresh and review again."),
    STORAGE_ERROR("Deletion could not be confirmed. The tracked entry and its history were retained. Check the destination and refresh before retrying."),
}
