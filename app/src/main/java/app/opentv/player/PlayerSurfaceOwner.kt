/* Copyright (C) 2026 Nuvix Contributors. Licensed under GPL-3.0-or-later. */
package app.opentv.player

import androidx.annotation.OptIn
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.PlayerView

/** The late disposal of an outgoing Compose view must never detach its replacement. */
internal class ExclusiveSurfaceOwner<T : Any>(private val bind: (T, Boolean) -> Unit) {
    private var attached: T? = null
    private var uncertain = false

    /** Transfers exclusive presentation ownership to the supplied surface owner. */
    fun attach(view: T): Boolean {
        if (uncertain) return false
        if (attached === view) return true
        val previous = attached
        attached = null
        if (previous != null && runCatching { bind(previous, false) }.isFailure) {
            // Do not attach a second surface if the first one rejected detachment.
            uncertain = true
            return false
        }
        return try {
            bind(view, true)
            attached = view
            true
        } catch (_: RuntimeException) {
            // Some older TV codecs reject a replacement surface. Keep audio and expose fallback UI.
            uncertain = runCatching { bind(view, false) }.isFailure
            false
        }
    }

    /** Detaches a surface only if it still owns the current presentation. */
    fun detach(view: T) {
        if (attached === view) {
            attached = null
            uncertain = runCatching { bind(view, false) }.isFailure
        }
    }

    /** Clears the currently owned presentation surface. */
    fun clear(): Boolean { attached?.let(::detach); return !uncertain }
    /** Only a newly allocated engine can clear uncertainty about an already released decoder. */
    fun reset() { attached = null; uncertain = false }
}

/**
 * Main-thread surface lease. Detach the old PlayerView before binding the new one: only one
 * surface owns the decoder, including during overlapping Navigation Compose transitions.
 * No Activity or View remains referenced after detach/clear.
 */
@OptIn(UnstableApi::class)
class PlayerSurfaceOwner(private val player: () -> Player?) {
    private val owner = ExclusiveSurfaceOwner<PlayerView> { view, attach ->
        view.player = if (attach) checkNotNull(player()) { "No active live player" } else null
    }

    /** Transfers the player's video surface to the requested presentation owner. */
    fun attach(view: PlayerView): Boolean = owner.attach(view)
    /** Detaches only the surface that still belongs to the supplied owner. */
    fun detach(view: PlayerView) = owner.detach(view)
    /** Clears the player's currently attached video surface. */
    fun clear(): Boolean = owner.clear()
    /** Clears saved presentation ownership so a new player can attach safely. */
    internal fun reset() = owner.reset()
}
