/*
 * This file is part of Nuvix, an independent modified version of OpenTV.
 * Copyright (C) 2026 The Nuvix Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.launcher

import android.app.Application
import android.content.Intent
import android.content.pm.PackageManager
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Regression tests for launcher controller. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28])
class LauncherControllerTest {
    /** Checks: HOME alias is unavailable until the user enables launcher mode. */
    @Test
    fun `HOME alias is unavailable until the user enables launcher mode`() {
        val context = RuntimeEnvironment.getApplication()
        val controller = LauncherController(context)
        val homeAlias = context.packageManager.getActivityInfo(
            controller.homeComponent,
            PackageManager.MATCH_DISABLED_COMPONENTS,
        )

        assertThat(homeAlias.enabled).isFalse()
        assertThat(homeAlias.exported).isTrue()
        assertThat(homeAlias.targetActivity).isEqualTo("app.opentv.MainActivity")
        assertThat(controller.isEnabled()).isFalse()
    }

    /** Checks: launcher opt in is reversible and does not change the normal app activity. */
    @Test
    fun `launcher opt in is reversible and does not change the normal app activity`() {
        val context = RuntimeEnvironment.getApplication()
        val controller = LauncherController(context)
        val main = android.content.ComponentName(context.packageName, "app.opentv.MainActivity")
        val originalMainState = context.packageManager.getComponentEnabledSetting(main)

        controller.setEnabled(true)
        assertThat(LauncherController(context).isEnabled()).isTrue()
        controller.setEnabled(false)

        assertThat(LauncherController(context).isEnabled()).isFalse()
        assertThat(context.packageManager.getComponentEnabledSetting(controller.homeComponent))
            .isEqualTo(PackageManager.COMPONENT_ENABLED_STATE_DISABLED)
        assertThat(context.packageManager.getComponentEnabledSetting(main)).isEqualTo(originalMainState)
    }

    /** Checks: ordinary application launches are distinguished from HOME requests. */
    @Test
    fun `ordinary application launches are distinguished from HOME requests`() {
        assertThat(LauncherController.isHomeIntent(null)).isFalse()
        assertThat(
            LauncherController.isHomeIntent(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER)),
        ).isFalse()
        assertThat(
            LauncherController.isHomeIntent(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)),
        ).isTrue()
    }
}
