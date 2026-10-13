/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.recording

import java.util.concurrent.ConcurrentHashMap

/**
 * A tiny in-process registry of which recordings are capturing *right now*, and where their bytes
 * are landing. It exists so the player can watch a recording while it is still being written: the
 * [app.opentv.player.GrowingRecordingDataSource] reads the growing file and, when it reaches the
 * current end, asks here whether the capture is still live — if it is, it waits for more bytes
 * rather than declaring the file finished.
 *
 * Recording and playback are in the same process, so a plain concurrent map is all this needs;
 * [RecordingService] marks a capture active when it starts and inactive in its `finally`.
 */
object RecordingLiveState {

    /** id -> the absolute file path the capture is currently writing to. */
    private val active = ConcurrentHashMap<Long, String>()

    /** A capture has started (or resumed) and is writing to [filePath]. */
    fun markActive(id: Long, filePath: String) {
        active[id] = filePath
    }

    /** The USB SAF sink learned its real locator after creation — but only a plain file path is
     *  tail-followable, so a `content://` update simply drops it from the live set. */
    fun updatePath(id: Long, filePath: String) {
        if (active.containsKey(id)) active[id] = filePath
    }

    /** The capture has finished, failed, or been stopped — its file is now at its final size. */
    fun markInactive(id: Long) {
        active.remove(id)
    }

    /** True while [id] is still being captured. */
    fun isActive(id: Long): Boolean = active.containsKey(id)

    /** The path bytes are being written to, or null if [id] isn't capturing. */
    fun pathOf(id: Long): String? = active[id]
}
