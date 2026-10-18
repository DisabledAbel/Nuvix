/* Nuvix Copyright (C) 2026 Nuvix Contributors. GPL-3.0-or-later. */
package app.opentv.recording

import android.content.Context
import app.opentv.connections.AccountConnectionSnapshot
import app.opentv.core.ServiceLocator
import app.opentv.data.model.Recording
import app.opentv.multiview.MultiViewPlaybackReference
import app.opentv.multiview.MultiViewSessionManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

data class RecordingConflictWarning(val conflict: MultiViewRecordingConflict, val panels: List<MultiViewPlaybackReference>, val nowMillis: Long)

/** Activity-session warning owner. No alarm, user response or UI work controls capture admission. */
class RecordingConflictCoordinator(
    private val scope: CoroutineScope,
    private val multiView: MultiViewSessionManager,
    private val recordings: suspend () -> List<Recording>,
    private val accounts: () -> List<AccountConnectionSnapshot>,
    private val leadMillis: () -> Long,
    private val dismissed: (String) -> Boolean,
    private val dismiss: (String) -> Unit,
    private val event: suspend (Long, String, String) -> Unit,
    private val notification: (MultiViewRecordingConflict) -> Unit = {},
    private val cancelNotification: (Long) -> Unit = {},
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val _warning = MutableStateFlow<RecordingConflictWarning?>(null)
    val warning = _warning.asStateFlow()
    private var visible = false
    private var inPip = false
    private val announced = mutableSetOf<String>()
    private var currentConflicts = emptyList<MultiViewRecordingConflict>()
    private var revision = 0L
    private val pendingSwitches = mutableMapOf<String, MultiViewPlaybackReference>()
    private var ticker: Job? = null
    private var cachedRows: List<Recording>? = null

    /** Schedule/source invalidation discards rows; countdown and capacity changes reuse them. */
    suspend fun invalidateRows() {
        cachedRows = null
        refresh()
    }

    /** Idle/single-channel sessions own no repeating task; only visible or PiP walls need ticks. */
    private fun updateTicker() {
        val needed = (visible || inPip) && multiView.playbackReferences().isNotEmpty()
        if (!needed) { ticker?.cancel(); ticker = null }
        else if (ticker?.isActive != true) ticker = scope.launch {
            while (true) { delay(1_000); refresh() }
        }
    }

    fun setVisible(value: Boolean) { visible = value; if (!value) _warning.value = null; scope.launch { refresh() } }
    fun setInPip(value: Boolean) { inPip = value; if (value) _warning.value = null; scope.launch { refresh() } }

    suspend fun refresh() {
        val generation = ++revision
        updateTicker()
        if (multiView.playbackReferences().isEmpty()) {
            currentConflicts.forEach { cancelNotification(it.recording.id) }
            currentConflicts = emptyList()
            pendingSwitches.clear()
            _warning.value = null
            return
        }
        try {
            val rows = cachedRows ?: recordings()
            if (generation != revision) return
            cachedRows = rows
            val references = multiView.playbackReferences()
            val primary = references.firstOrNull { it.panelId == multiView.state.value.primaryPanelId }?.token?.sessionId
            val conflicts = MultiViewRecordingConflictPolicy.detect(rows, accounts(), now(), leadMillis(), primary)
            currentConflicts.filter { old -> conflicts.none { it.key == old.key } }.forEach { cancelNotification(it.recording.id) }
            currentConflicts = conflicts
            pendingSwitches.toMap().forEach { (key, reference) ->
                val panel = multiView.state.value.panels.firstOrNull { it.id == reference.panelId }
                if (panel?.playbackGeneration == reference.generation && panel.channelId != reference.channelId) {
                    pendingSwitches.remove(key)
                    key.substringBefore(':').toLongOrNull()?.let { event(it, "VIEWING_SWITCHED", "Confirmed replacement playback freed the original account without restarting unrelated panels") }
                } else if (conflicts.none { it.key == key } || !visible || inPip || dismissed(key)) {
                    multiView.cancelRecordingReplacement(reference)
                    pendingSwitches.remove(key)
                } else if (!multiView.isCurrent(reference)) pendingSwitches.remove(key)
            }
            if (generation != revision) return
            val next = conflicts.firstOrNull { !dismissed(it.key) && it.victims.any { token -> references.any { it.token == token } } }
            _warning.value = if (visible && !inPip && next != null) RecordingConflictWarning(next,
                references.filter { it.token in next.victims }, now()) else null
            if (_warning.value != null) cancelNotification(next!!.recording.id)
            for (conflict in conflicts) {
                if (announced.add(conflict.key)) event(conflict.recording.id, "VIEWING_WARNING", "Multi-View needs to yield account capacity before the padded recording start; unattended recording priority remains active")
                if (generation != revision) return
                if (!visible || inPip) notification(conflict)
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { if (generation == revision) _warning.value = null /* Recorder remains independent if review data is unavailable. */ }
    }

    fun keepWatching(key: String) {
        val current = _warning.value?.takeIf { it.conflict.key == key } ?: return
        dismiss(key)
        pendingSwitches.remove(key)?.let(multiView::cancelRecordingReplacement)
        cancelNotification(current.conflict.recording.id); _warning.value = null
        scope.launch { event(current.conflict.recording.id, "VIEWING_DEFERRED", "User kept watching until the recording deadline; unattended fallback remains enabled") }
    }

    suspend fun release(key: String, reference: MultiViewPlaybackReference): Boolean {
        invalidateRows()
        if (!valid(key, reference)) return false
        val id = _warning.value!!.conflict.recording.id
        if (!multiView.releaseForRecording(reference)) return false
        event(id, "VIEWING_RELEASED", "User released the conflicting Multi-View panel for the scheduled recording")
        refresh()
        return true
    }

    suspend fun switch(key: String, reference: MultiViewPlaybackReference, channelId: Long): Boolean {
        invalidateRows()
        if (!valid(key, reference)) return false
        val id = _warning.value!!.conflict.recording.id
        val accepted = multiView.replaceForRecording(reference, channelId) {
            cachedRows != null && visible && !inPip && currentConflicts.any { it.key == key && now() < it.effectiveStartMillis } && !dismissed(key)
        }
        if (accepted) {
            multiView.playbackReferences().firstOrNull { it.token == reference.token }?.let { pendingSwitches[key] = it }
            event(id, "VIEWING_SWITCH_REQUESTED", "User selected an authorized independent broadcast; capacity and playback are revalidated before committing")
        }
        return accepted
    }

    private fun valid(key: String, reference: MultiViewPlaybackReference): Boolean = _warning.value?.let {
        it.conflict.key == key && now() < it.conflict.effectiveStartMillis && reference in it.panels && multiView.isCurrent(reference)
    } == true

    companion object {
        fun create(context: Context, scope: CoroutineScope, multiView: MultiViewSessionManager): RecordingConflictCoordinator {
            val graph = ServiceLocator.get(context)
            val store = RecordingWarningStore(context)
            val coordinator = RecordingConflictCoordinator(scope, multiView, recordings = {
                val rows = graph.recordingRepository.scheduled() + graph.recordingRepository.active()
                val available = graph.sourceRepository.enabled().associateBy { it.id }
                available.values.forEach { graph.connections.registerSource(it) }
                store.prune(rows.map { it.warningKey() }.toSet())
                rows.filter { it.sourceId in available }
            }, accounts = { graph.connections.snapshots.value },
                leadMillis = { graph.settings.recordConflictLeadMinutes.value * 60_000L },
                dismissed = store::dismissed, dismiss = { key ->
                    store.dismiss(key)
                    key.substringBefore(':').toLongOrNull()?.let(app.opentv.core.RecordingSignals::suppress)
                }, event = graph.recordingRepository::event,
                notification = { RecordingConflictNotifications.show(context, it) },
                cancelNotification = { RecordingConflictNotifications.cancel(context, it) })
            scope.launch { graph.recordingRepository.observeAll().map { rows -> rows.map { it.copy(sizeBytes = 0) } }
                .distinctUntilChanged().collect { coordinator.invalidateRows() } }
            scope.launch { graph.connections.snapshots.collect { coordinator.refresh() } }
            scope.launch { graph.sourceRepository.observeAll().collect { coordinator.invalidateRows() } }
            scope.launch { graph.settings.recordConflictLeadMinutes.collect { coordinator.refresh() } }
            scope.launch { multiView.state.collect { coordinator.refresh() } }
            return coordinator
        }
    }
}
