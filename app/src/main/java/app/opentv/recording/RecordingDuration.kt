/* Copyright (C) 2026 Nuvix Contributors. GPL-3.0-or-later. */
package app.opentv.recording

import app.opentv.data.model.Recording
import app.opentv.data.model.RecordingStatus

/** Absolute UTC choices prevent duplicate confirmation from adding another increment. */
sealed interface DurationChoice {
    data class Add(val minutes: Int) : DurationChoice
    data class EndAt(val utcMillis: Long) : DurationChoice
    data object Restore : DurationChoice
    data object Stop : DurationChoice
}

/** Pure duration arithmetic, independent of display timezone and DST. */
object RecordingDuration {
    val increments = listOf(5, 10, 15, 30, 60)
    fun originalEnd(row: Recording): Long = when {
        row.originalEndMillis != 0L -> row.originalEndMillis
        row.programmeEndMillis > 0 -> checkedAdd(row.programmeEndMillis, row.padEndMinutes * 60_000L)
        else -> row.scheduledEndMillis
    }
    fun proposedEnd(row: Recording, choice: DurationChoice, now: Long): Long {
        require(row.status == RecordingStatus.RECORDING) { "Recording is no longer capturing. Refresh before editing." }
        val end = when (choice) {
            is DurationChoice.Add -> {
                require(choice.minutes in increments) { "Unsupported extension." }
                checkedAdd(row.scheduledEndMillis.takeIf { it > 0 } ?: now, choice.minutes * 60_000L)
            }
            is DurationChoice.EndAt -> choice.utcMillis
            DurationChoice.Restore -> originalEnd(row).also { require(it > 0) { "This recording has no original end." } }
            DurationChoice.Stop -> maxOf(now, row.startedAtMillis)
        }
        require(end >= row.startedAtMillis && end > 0) { "End time cannot precede capture start." }
        return end
    }
    private fun checkedAdd(first: Long, second: Long): Long {
        val sum = first + second
        if (((first xor sum) and (second xor sum)) < 0) throw ArithmeticException("End time overflow")
        return sum
    }
    /** Conservative 8 Mbps fallback until enough captured bytes establish an observed rate. */
    fun estimatedBytes(row: Recording, durationMillis: Long, now: Long): Long {
        val elapsed = now - row.startedAtMillis
        val rate = if (elapsed >= 10_000 && row.sizeBytes > 0) row.sizeBytes.toDouble() / elapsed else 1_000.0
        return (rate * durationMillis.coerceAtLeast(0)).coerceAtMost(Long.MAX_VALUE.toDouble()).toLong()
    }
}

/** Review is a snapshot, including all demand and policy inputs that require renewed consent. */
data class DurationReview(
    val recording: Recording,
    val choice: DurationChoice,
    val proposedEndMillis: Long,
    val demand: List<Recording>,
    val affected: List<Recording>,
    val accountLabel: String,
    val capacity: Int?,
    val policySignature: List<String>,
    val warnings: List<String>,
    val blocked: Boolean,
    val additionalBytes: Long,
) {
    val needsStopConfirmation get() = choice == DurationChoice.Stop || warnings.any { it == STOP_WARNING }
    companion object { const val STOP_WARNING = "The selected end has passed. Confirming will stop capture now." }
}
