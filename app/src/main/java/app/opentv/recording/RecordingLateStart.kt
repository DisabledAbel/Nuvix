/* Copyright (C) 2026 Nuvix Contributors. GPL-3.0-or-later. */
package app.opentv.recording

import app.opentv.data.model.Recording
import app.opentv.data.model.RecordingStatus
import app.opentv.data.model.SeriesRule

/** Persist enum names, never ordinals. Unknown policies safely use Record Remaining. */
enum class LateStartPolicy(val label: String, val description: String) {
    INHERIT("Use Default Policy", "Use the series rule, then the global recording default."),
    RECORD_REMAINING("Record Remaining", "Capture available content until the configured end; missed program content is Partial."),
    SKIP_IF_LATE("Skip If Late", "Skip if useful capture has not begun within five seconds of program start."),
    MINIMUM_REMAINING("Minimum Time Remaining", "Begin only when at least the selected scheduled program time remains; padding does not count.");
    companion object {
        fun parse(value: String): LateStartPolicy = entries.firstOrNull { it.name == value } ?: RECORD_REMAINING
    }
}

data class LateStartOptions(val policy: LateStartPolicy = LateStartPolicy.INHERIT, val minimumMinutes: Int = 10) {
    init { require(minimumMinutes in 1..1440) { "Minimum remaining time must be 1–1440 minutes" } }
}

/** One evaluator for alarms, reconciliation, admission and the first useful media write. */
object RecordingLateStart {
    const val GRACE_MILLIS = 5_000L
    fun resolve(recording: Recording, series: SeriesRule?, global: LateStartOptions): LateStartOptions {
        val own = LateStartPolicy.parse(recording.lateStartPolicy)
        val rule = series?.let { LateStartPolicy.parse(it.lateStartPolicy) }
        return when {
            own != LateStartPolicy.INHERIT -> LateStartOptions(own, recording.minimumRemainingMinutes.coerceIn(1, 1440))
            rule != null && rule != LateStartPolicy.INHERIT -> LateStartOptions(rule, series.minimumRemainingMinutes.coerceIn(1, 1440))
            else -> global.copy(policy = global.policy.takeUnless { it == LateStartPolicy.INHERIT } ?: LateStartPolicy.RECORD_REMAINING)
        }
    }
    data class Decision(val action: Action, val options: LateStartOptions, val delayMillis: Long = 0,
        val contentMissed: Boolean = false, val reason: String) {
        enum class Action { CAPTURE, WAIT, SKIP, IGNORE }
    }
    fun isLate(recording: Recording, at: Long): Boolean = recording.programmeStartMillis > 0 &&
        at > recording.programmeStartMillis && at - recording.programmeStartMillis > GRACE_MILLIS

    fun evaluate(recording: Recording, now: Long, options: LateStartOptions): Decision {
        fun decision(action: Decision.Action, reason: String) = Decision(action, options,
            if (recording.programmeStartMillis > 0 && now > recording.programmeStartMillis) now - recording.programmeStartMillis else 0,
            isLate(recording, now), reason)
        if (recording.status !in setOf(RecordingStatus.SCHEDULED, RecordingStatus.PREPARING, RecordingStatus.RECORDING))
            return decision(Decision.Action.IGNORE, "Recording is no longer awaiting capture")
        // PREPARING timestamps from old databases are not evidence of useful bytes.
        if (recording.status == RecordingStatus.RECORDING || recording.sizeBytes > 0)
            return decision(Decision.Action.CAPTURE, "Existing capture retains its recovery and duration controls")
        if (recording.scheduledEndMillis > 0 && now >= recording.scheduledEndMillis)
            return decision(Decision.Action.SKIP, "Recording window elapsed before useful capture became available")
        if (recording.scheduledStartMillis > now)
            return decision(Decision.Action.WAIT, "Waiting for the effective padded start")
        val known = recording.programmeStartMillis > 0 && recording.programmeEndMillis > recording.programmeStartMillis
        if (recording.programmeStartMillis > 0 && recording.programmeEndMillis != 0L && !known)
            return decision(Decision.Action.SKIP, "Invalid scheduled program bounds; capture cannot be verified")
        when (options.policy) {
            LateStartPolicy.SKIP_IF_LATE -> {
                if (!known) return decision(Decision.Action.SKIP, "Scheduled program bounds unavailable for Skip If Late")
                if (isLate(recording, now)) return decision(Decision.Action.SKIP, "Skipped because the program already started (five-second grace elapsed)")
            }
            LateStartPolicy.MINIMUM_REMAINING -> {
                if (!known) return decision(Decision.Action.SKIP, "Scheduled program bounds unavailable for Minimum Time Remaining")
                val remaining = (recording.programmeEndMillis - maxOf(now, recording.programmeStartMillis)).coerceAtLeast(0)
                if (remaining < options.minimumMinutes * 60_000L)
                    return decision(Decision.Action.SKIP, "Skipped because insufficient program time remained (minimum ${options.minimumMinutes} minutes; end padding excluded)")
            }
            else -> Unit
        }
        return decision(Decision.Action.CAPTURE, if (isLate(recording, now))
            "Captured remaining portion of program; started late after scheduler/device wakeup or provider availability"
            else "Useful capture started within the program-start grace; optional padding does not affect completeness")
    }

    /** First instant a currently eligible start must be checked again, including equality. */
    fun recheckAt(recording: Recording, options: LateStartOptions, now: Long): Long {
        val end = recording.scheduledEndMillis.takeIf { it > 0 } ?: Long.MAX_VALUE
        val policyEnd = when (options.policy) {
            LateStartPolicy.SKIP_IF_LATE -> recording.programmeStartMillis.coerceAtMost(Long.MAX_VALUE - GRACE_MILLIS - 1) + GRACE_MILLIS + 1
            LateStartPolicy.MINIMUM_REMAINING -> recording.programmeEndMillis - options.minimumMinutes * 60_000L + 1
            else -> end
        }
        return minOf(end, policyEnd).coerceAtLeast(now.coerceAtMost(Long.MAX_VALUE - 1) + 1)
    }

    /** Numeric timing and fixed reasons only: never serialize a Recording or a provider URL. */
    fun history(recording: Recording, at: Long, decision: Decision): String =
        "${decision.reason}. Policy=${decision.options.policy.name}; minimumMinutes=${if (decision.options.policy == LateStartPolicy.MINIMUM_REMAINING) decision.options.minimumMinutes else 0}; " +
        "programStart=${recording.programmeStartMillis}; paddedStart=${recording.scheduledStartMillis}; " +
        "programEnd=${recording.programmeEndMillis}; captureEnd=${recording.scheduledEndMillis}; " +
        "decisionAt=$at; actualCaptureStart=${if (decision.action == Decision.Action.CAPTURE && recording.status == RecordingStatus.PREPARING) at else 0}; " +
        "delayMillis=${decision.delayMillis}; contentMissed=${decision.contentMissed}"
}
