/* Copyright (C) 2026 Nuvix Contributors. GPL-3.0-or-later. */
package app.opentv.ui.multiview

import app.opentv.multiview.MultiViewLayout

/** Fractions of the available canvas; video itself always uses Media3's FIT resize mode. */
internal data class PanelBounds(val x: Float, val y: Float, val width: Float, val height: Float)

/** A single keyed canvas owns every panel, so changing arrangements cannot recreate players. */
internal fun multiViewBounds(count: Int, layout: MultiViewLayout, primaryIndex: Int): List<PanelBounds> {
    if (count <= 0) return emptyList()
    require(count <= 4)
    if (count == 1) return listOf(PanelBounds(0f, 0f, 1f, 1f))
    if (layout == MultiViewLayout.FOCUSED || count == 3) {
        val primary = primaryIndex.coerceIn(0, count - 1)
        var secondary = 0
        return List(count) { index ->
            if (index == primary) PanelBounds(0f, 0f, 0.7f, 1f)
            else PanelBounds(0.7f, secondary++.toFloat() / (count - 1), 0.3f, 1f / (count - 1))
        }
    }
    if (count == 2) return List(count) { PanelBounds(it * 0.5f, 0f, 0.5f, 1f) }
    return List(count) { PanelBounds((it % 2) * 0.5f, (it / 2) * 0.5f, 0.5f, 0.5f) }
}

/** D-pad swaps follow the visible geometry, including the primary-and-stack arrangements. */
internal fun panelMoveOffset(index: Int, count: Int, layout: MultiViewLayout, horizontal: Int, vertical: Int): Int {
    if (count < 2) return 0
    val offset = when {
        horizontal != 0 -> horizontal
        count == 4 && layout != MultiViewLayout.FOCUSED -> vertical * 2
        else -> vertical
    }
    return (index + offset).coerceIn(0, count - 1) - index
}
