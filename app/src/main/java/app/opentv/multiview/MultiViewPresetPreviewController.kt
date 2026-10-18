/* Nuvix Copyright (C) 2026 Nuvix Contributors. GPL-3.0-or-later. */
package app.opentv.multiview

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Safe catalog and policy values only; suitable for presentation and deterministic tests. */
data class MultiViewPresetInspection(
    val presentation: MultiViewPresentation,
    val channels: List<MultiViewPreviewChannel>,
    val plan: PresetRecordingPlan,
) {
    // The moving horizon/countdown alone does not require another click. Capture windows do.
    fun sameDecision(other: MultiViewPresetInspection): Boolean = presentation == other.presentation &&
        channels == other.channels && plan.accounts == other.plan.accounts && plan.recordings == other.plan.recordings &&
        plan.unavailablePanelIds == other.plan.unavailablePanelIds &&
        plan.conflicts.map { listOf(it.kind, it.atMillis, it.recordingIds, it.panelIds, it.accountId) } ==
        other.plan.conflicts.map { listOf(it.kind, it.atMillis, it.recordingIds, it.panelIds, it.accountId) }
    val canLoad get() = presentation.panels.isNotEmpty() && plan.unavailablePanelIds.isEmpty() && !plan.currentCapacityExceeded &&
        plan.conflicts.none { it.kind == PresetConflictKind.IMMEDIATE }
}

data class MultiViewPresetReviewState(
    val inspection: MultiViewPresetInspection? = null,
    val busy: Boolean = false,
    val message: String? = null,
)

/** Main-dispatcher owner. Stale asynchronous work and repeated OK never commit a second load. */
class MultiViewPresetPreviewController(
    private val inspect: suspend (MultiViewPresentation) -> MultiViewPresetInspection,
    private val load: (MultiViewPresentation) -> Unit,
) {
    private val mutable = MutableStateFlow(MultiViewPresetReviewState())
    val state = mutable.asStateFlow()
    private var revision = 0L
    private var confirming = false
    private var loaded = false

    fun invalidate() { ++revision; mutable.value = mutable.value.copy(inspection = null) }

    suspend fun refresh(proposal: MultiViewPresentation) {
        if (loaded || confirming) return
        val generation = ++revision
        try {
            val fresh = inspect(proposal)
            if (generation == revision && !loaded) mutable.value = MultiViewPresetReviewState(fresh)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) {
            if (generation == revision) mutable.value = MultiViewPresetReviewState(message = "The local catalog or recording plan could not be checked. No streams were opened. Try refreshing.")
        }
    }

    suspend fun confirm(proposal: MultiViewPresentation): Boolean {
        if (confirming || loaded) return false
        val shown = mutable.value.inspection ?: return false
        confirming = true
        val generation = ++revision
        mutable.value = mutable.value.copy(busy = true, message = null)
        try {
            val fresh = inspect(proposal)
            if (generation != revision) return false
            if (!shown.sameDecision(fresh)) {
                mutable.value = MultiViewPresetReviewState(fresh, message = "Availability or recording requirements changed. Review the updated plan and choose again.")
                return false
            }
            if (!fresh.canLoad) { mutable.value = MultiViewPresetReviewState(fresh); return false }
            loaded = true
            load(fresh.presentation)
            return true
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) {
            mutable.value = mutable.value.copy(message = "The proposal could not be revalidated. Refresh and choose again.")
            return false
        } finally {
            confirming = false
            mutable.value = mutable.value.copy(busy = false)
        }
    }
}
