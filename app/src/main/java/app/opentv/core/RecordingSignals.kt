/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.core

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.ConcurrentHashMap

/**
 * The link between a scheduled recording firing and the live UI reacting to it.
 *
 * On a single-connection provider account you cannot watch one channel and record another at the
 * same time — the provider cuts one of the two streams. OpenTV's answer, when auto-switch is on, is
 * to move the live view onto the recording itself: 30 seconds before a booking starts the UI warns
 * the viewer ([imminent]), and when the capture begins the UI switches to *watching the recording
 * file* ([watch]) rather than opening a second live stream. That's one connection, no conflict, and
 * the viewer ends up on exactly what's being recorded.
 *
 * All of this lives in one process (the capture service and the UI share it), so plain flows and a
 * small set are all it takes. Values are one-shot: set from a broadcast receiver or the service,
 * consumed by the nav graph, and cleared so a stale one never re-fires.
 */
object RecordingSignals {

    /** A recording that just started capturing and the UI should switch to watching, plus when the
     *  request was made so a long-backgrounded app can ignore a stale one. */
    data class Watch(val recordingId: Long, val requestedAtMillis: Long)

    private val _watch = MutableStateFlow<Watch?>(null)
    val watch: StateFlow<Watch?> = _watch.asStateFlow()

    /** A booking about to start — shown as a brief "we're about to switch" banner. */
    data class Imminent(
        val recordingId: Long,
        val title: String,
        val channelName: String,
        val startAtMillis: Long,
    )

    private val _imminent = MutableStateFlow<Imminent?>(null)
    val imminent: StateFlow<Imminent?> = _imminent.asStateFlow()

    /** Recordings whose auto-switch the viewer chose to skip ("keep watching"). */
    private val suppressed = ConcurrentHashMap.newKeySet<Long>()

    fun requestWatch(recordingId: Long, nowMillis: Long) {
        if (recordingId > 0 && recordingId !in suppressed) {
            _watch.value = Watch(recordingId, nowMillis)
        }
    }

    fun consumeWatch() {
        _watch.value = null
    }

    fun warn(imminent: Imminent) {
        if (imminent.recordingId !in suppressed) _imminent.value = imminent
    }

    fun clearImminent() {
        _imminent.value = null
    }

    /** The viewer tapped "keep watching" — don't yank the screen for this booking. */
    fun suppress(recordingId: Long) {
        suppressed.add(recordingId)
        if (_imminent.value?.recordingId == recordingId) _imminent.value = null
        if (_watch.value?.recordingId == recordingId) _watch.value = null
    }
}
