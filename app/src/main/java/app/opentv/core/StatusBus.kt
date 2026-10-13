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
 * A single line of "what the app is doing right now" — loading channels, building the guide,
 * loading movies — with an optional 0..1 progress fraction so slow jobs can show a real bar rather
 * than a spinner that gives nothing away. Shown as a slim status bar, so a big provider's slow
 * moments read as visible work instead of a frozen screen. Deliberately app-wide and dead simple:
 * any background job announces itself, the shell shows whatever's current, and it clears when done.
 */
object StatusBus {
    private val _message = MutableStateFlow<String?>(null)

    /** The current background activity, or null when nothing noteworthy is happening. */
    val message: StateFlow<String?> = _message.asStateFlow()

    private val _progress = MutableStateFlow<Float?>(null)

    /** 0..1 progress for [message], or null for indeterminate (show a spinner, not a bar). */
    val progress: StateFlow<Float?> = _progress.asStateFlow()

    fun set(message: String?, progress: Float? = null) {
        _message.value = message
        _progress.value = if (message == null) null else progress
    }

    /** Update just the fraction while a job runs, keeping the current message. */
    fun setProgress(progress: Float?) {
        _progress.value = progress
    }

    /** Announce [message] while [block] runs, then clear it — even if the work throws. */
    suspend fun <T> during(message: String, block: suspend () -> T): T {
        set(message)
        return try {
            block()
        } finally {
            set(null)
        }
    }
}
