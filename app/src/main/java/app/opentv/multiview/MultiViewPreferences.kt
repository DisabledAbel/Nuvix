/* Nuvix modifications Copyright (C) 2026 Nuvix Contributors. GPL-3.0-or-later. */
package app.opentv.multiview

/** Recovery never automatically opens connections after process death. */
enum class MultiViewRecoveryPolicy { ASK_BEFORE_RESTORE, DISABLED }

/** Device-local choices. Provider allowances remain in Connection Management. */
data class MultiViewPreferences(
    val enabled: Boolean = true,
    val audioMode: MultiViewAudioMode = MultiViewAudioMode.FOLLOW_FOCUS,
    val focusDelayMs: Int = 150,
    val fullscreenRestoration: MultiViewRestorePolicy = MultiViewRestorePolicy.ASK_FIRST,
    val bufferingSuggestions: Boolean = true,
    val compactGuide: Boolean = true,
    val showLabels: Boolean = true,
    val savePresentationPreferences: Boolean = true,
    val recovery: MultiViewRecoveryPolicy = MultiViewRecoveryPolicy.ASK_BEFORE_RESTORE,
) {
    fun validated(): MultiViewPreferences = copy(focusDelayMs = focusDelayMs.coerceIn(0, 2_000))
}
