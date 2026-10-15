/* Copyright (C) 2026 Nuvix Contributors. GPL-3.0-or-later. */
package app.opentv.player

import app.opentv.data.model.Programme

data class MiniGuideProgrammes(val current: Programme? = null, val next: Programme? = null) {
    companion object {
        /** UTC comparisons handle midnight/DST; only display formatting uses the device zone. */
        fun at(programmes: List<Programme>, now: Long): MiniGuideProgrammes {
            val valid = programmes.filter { it.endUtcMillis > it.startUtcMillis }
                .sortedWith(compareBy<Programme> { it.startUtcMillis }.thenBy { it.feedId })
            val current = valid.firstOrNull { it.isLiveAt(now) }
            val next = valid.firstOrNull { it.startUtcMillis >= (current?.endUtcMillis ?: (now + 1)) }
            return MiniGuideProgrammes(current, next)
        }
    }
}
