/*
 * This file is part of Nuvix, an independent modified version of OpenTV.
 * Copyright (C) 2026 The Nuvix Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.launcher

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** Regression tests for app order. */
class AppOrderTest {
    /** Checks: pin appends a new app without changing existing order. */
    @Test
    fun `pin appends a new app without changing existing order`() {
        assertThat(AppOrder.pin(listOf("tv.alpha", "tv.beta"), "tv.gamma"))
            .containsExactly("tv.alpha", "tv.beta", "tv.gamma").inOrder()
    }

    /** Checks: pinning an already pinned app does not duplicate or move it. */
    @Test
    fun `pinning an already pinned app does not duplicate or move it`() {
        assertThat(AppOrder.pin(listOf("tv.alpha", "tv.beta"), "tv.alpha"))
            .containsExactly("tv.alpha", "tv.beta").inOrder()
    }

    /** Checks: unpin preserves the relative order of remaining apps. */
    @Test
    fun `unpin preserves the relative order of remaining apps`() {
        assertThat(AppOrder.unpin(listOf("tv.alpha", "tv.beta", "tv.gamma"), "tv.beta"))
            .containsExactly("tv.alpha", "tv.gamma").inOrder()
    }

    /** Checks: move supports both directions. */
    @Test
    fun `move supports both directions`() {
        val order = listOf("tv.alpha", "tv.beta", "tv.gamma")

        assertThat(AppOrder.move(order, "tv.beta", -1))
            .containsExactly("tv.beta", "tv.alpha", "tv.gamma").inOrder()
        assertThat(AppOrder.move(order, "tv.beta", 1))
            .containsExactly("tv.alpha", "tv.gamma", "tv.beta").inOrder()
    }

    /** Checks: move cannot lose an app at either edge. */
    @Test
    fun `move cannot lose an app at either edge`() {
        val order = listOf("tv.alpha", "tv.beta", "tv.gamma")

        assertThat(AppOrder.move(order, "tv.alpha", -1)).isEqualTo(order)
        assertThat(AppOrder.move(order, "tv.gamma", 1)).isEqualTo(order)
        assertThat(AppOrder.move(order, "tv.missing", 1)).isEqualTo(order)
    }

    /** Checks: extreme move offsets clamp without integer overflow. */
    @Test
    fun `extreme move offsets clamp without integer overflow`() {
        val order = listOf("tv.alpha", "tv.beta", "tv.gamma")

        assertThat(AppOrder.move(order, "tv.beta", Int.MAX_VALUE))
            .containsExactly("tv.alpha", "tv.gamma", "tv.beta").inOrder()
        assertThat(AppOrder.move(order, "tv.beta", Int.MIN_VALUE))
            .containsExactly("tv.beta", "tv.alpha", "tv.gamma").inOrder()
    }

    /** Checks: uninstalled favorites are removed without disturbing saved order. */
    @Test
    fun `uninstalled favorites are removed without disturbing saved order`() {
        assertThat(
            AppOrder.retainInstalled(
                listOf("tv.alpha", "tv.removed", "tv.beta"),
                setOf("tv.beta", "tv.new", "tv.alpha"),
            ),
        ).containsExactly("tv.alpha", "tv.beta").inOrder()
    }

    /** Checks: empty installed set removes all unavailable favorites. */
    @Test
    fun `empty installed set removes all unavailable favorites`() {
        assertThat(AppOrder.retainInstalled(listOf("tv.removed"), emptySet())).isEmpty()
    }
}
