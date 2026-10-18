/* Copyright (C) 2026 Nuvix Contributors. GPL-3.0-or-later. */
package app.opentv.ui.multiview

import app.opentv.multiview.MultiViewLayout
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import kotlin.math.max
import kotlin.math.min

class MultiViewGeometryTest {
    @Test fun everySupportedArrangementFillsCanvasWithoutStretchingOrOverlappingPanels() {
        for (count in 1..4) for (layout in MultiViewLayout.entries) for (primary in 0 until count) {
            val bounds = multiViewBounds(count, layout, primary)
            assertThat(bounds).hasSize(count)
            assertThat(bounds.sumOf { (it.width * it.height).toDouble() }).isWithin(0.00001).of(1.0)
            bounds.forEach { panel ->
                assertThat(panel.x).isAtLeast(0f)
                assertThat(panel.y).isAtLeast(0f)
                assertThat(panel.x + panel.width).isAtMost(1.00001f)
                assertThat(panel.y + panel.height).isAtMost(1.00001f)
            }
            for (a in bounds.indices) for (b in a + 1 until bounds.size) {
                val left = bounds[a]
                val right = bounds[b]
                val overlapWidth = max(0f, min(left.x + left.width, right.x + right.width) - max(left.x, right.x))
                val overlapHeight = max(0f, min(left.y + left.height, right.y + right.height) - max(left.y, right.y))
                assertThat(overlapWidth * overlapHeight).isWithin(0.00001f).of(0f)
            }
        }
    }

    @Test fun primaryPanelRemainsAttachedToItsIdentityAcrossFocusedRearrangement() {
        val first = multiViewBounds(4, MultiViewLayout.FOCUSED, 0)
        val moved = multiViewBounds(4, MultiViewLayout.FOCUSED, 3)
        assertThat(moved[3]).isEqualTo(first[0])
        assertThat(moved[3].width * moved[3].height).isGreaterThan(moved[0].width * moved[0].height)
        val three = multiViewBounds(3, MultiViewLayout.THREE, 1)
        assertThat(three[1].width).isEqualTo(0.7f)
        assertThat(three[0].x).isEqualTo(0.7f)
    }

    @Test fun balancedTwoAndFourPanelsHaveEqualSpace() {
        assertThat(multiViewBounds(2, MultiViewLayout.TWO, 0)).containsExactly(
            PanelBounds(0f, 0f, 0.5f, 1f), PanelBounds(0.5f, 0f, 0.5f, 1f),
        ).inOrder()
        assertThat(multiViewBounds(4, MultiViewLayout.GRID, 0).map { it.width * it.height }.distinct()).containsExactly(0.25f)
    }

    @Test fun editMovesAreBoundedAndLeaveCanvasAtEdgesSoDoneRemainsReachable() {
        assertThat(panelMoveOffset(0, 4, MultiViewLayout.GRID, 0, -1)).isEqualTo(0)
        assertThat(panelMoveOffset(0, 4, MultiViewLayout.GRID, 0, 1)).isEqualTo(2)
        assertThat(panelMoveOffset(3, 4, MultiViewLayout.GRID, 1, 0)).isEqualTo(0)
        assertThat(panelMoveOffset(2, 3, MultiViewLayout.THREE, 0, -1)).isEqualTo(-1)
    }
}
