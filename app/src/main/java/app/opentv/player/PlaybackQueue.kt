/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.player

/**
 * The ordered channel list the full-screen player can zap through.
 *
 * Set by whatever launches playback (the guide passes the list you were browsing), read by the
 * player for channel up/down and the in-player channel list. A plain in-memory handoff rather
 * than a nav argument, because the list can be thousands of channels — far too big for a URL —
 * and it only needs to survive the hop from the guide to the player.
 */
object PlaybackQueue {
    data class Item(val id: Long, val name: String, val logoUrl: String?, val number: Int? = null)

    @Volatile
    var items: List<Item> = emptyList()
}
