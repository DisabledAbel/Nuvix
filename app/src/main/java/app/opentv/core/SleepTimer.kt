/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.core

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * A "stop watching after a while" timer — the thing you set when you're dozing off in front of a
 * film. Deliberately in-memory and app-wide: a sleep timer that survived an app restart would be a
 * surprise, and it needs to be visible from both the settings screen (where you arm it) and the
 * players (which honour it), so a plain singleton beats threading it through the database.
 *
 * The players observe [deadline] and, once the clock passes it, leave playback. Setting a new
 * duration re-arms it; [clear] cancels.
 */
object SleepTimer {
    private val _deadline = MutableStateFlow<Long?>(null)

    /** Epoch millis at which playback should stop, or null when the timer is off. */
    val deadline: StateFlow<Long?> = _deadline.asStateFlow()

    /** The options offered in the UI, in minutes. */
    val presets = listOf(15, 30, 45, 60, 90, 120)

    fun armMinutes(minutes: Int) {
        _deadline.value = System.currentTimeMillis() + minutes * 60_000L
    }

    fun clear() {
        _deadline.value = null
    }

    /** Minutes left (rounded up), or null if the timer is off. For showing "Sleep in 22 min". */
    fun minutesRemaining(nowMillis: Long = System.currentTimeMillis()): Int? {
        val d = _deadline.value ?: return null
        val left = d - nowMillis
        if (left <= 0L) return 0
        return ((left + 59_999L) / 60_000L).toInt()
    }
}
