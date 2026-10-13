/*
 * This file is part of Nuvix, an independent modified version of OpenTV.
 * Copyright (C) 2026 The Nuvix Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.launcher

import android.app.Application
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.ResolveInfo
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28])
class InstalledAppsRepositoryTest {
    private val context: Application get() = RuntimeEnvironment.getApplication()

    @Test
    fun `discovery prefers TV activities and omits unsafe and own activities`() {
        val tv = activity("tv.alpha", "Alpha", "TvActivity")
        val phone = activity("tv.alpha", "Alpha", "PhoneActivity")
        val other = activity("tv.beta", "Beta")
        val privateApp = activity("tv.private", "Private").apply { exported = false }
        val disabled = activity("tv.disabled", "Disabled").apply { applicationInfo.enabled = false }
        val own = activity(context.packageName, "Nuvix", "app.opentv.MainActivity")
        install(tv, phone, other, privateApp, disabled)
        resolve(tv, Intent.CATEGORY_LEANBACK_LAUNCHER)
        resolve(phone, Intent.CATEGORY_LAUNCHER)
        resolve(other, Intent.CATEGORY_LAUNCHER)
        resolve(privateApp, Intent.CATEGORY_LEANBACK_LAUNCHER)
        resolve(disabled, Intent.CATEGORY_LEANBACK_LAUNCHER)
        resolve(own, Intent.CATEGORY_LEANBACK_LAUNCHER)

        val discovered = InstalledAppsRepository(context).discover()

        assertThat(discovered.map { it.packageName }).containsExactly("tv.alpha", "tv.beta").inOrder()
        assertThat(discovered.first().componentName).isEqualTo(ComponentName("tv.alpha", "TvActivity"))
        assertThat(discovered.first().isTvApp).isTrue()
        assertThat(discovered.last().isTvApp).isFalse()
    }

    @Test
    fun `launch opens the verified TV component in a new task`() {
        val info = activity("tv.alpha", "Alpha")
        install(info)

        val launched = InstalledAppsRepository(context).launch(info.app(isTvApp = true))
        val intent = shadowOf(context).nextStartedActivity

        assertThat(launched).isTrue()
        assertThat(intent.component).isEqualTo(ComponentName(info.packageName, info.name))
        assertThat(intent.hasCategory(Intent.CATEGORY_LEANBACK_LAUNCHER)).isTrue()
        assertThat(intent.flags and Intent.FLAG_ACTIVITY_NEW_TASK).isNotEqualTo(0)
    }

    @Test
    fun `launch of an uninstalled app fails without opening an activity`() {
        val unavailable = activity("tv.removed", "Removed").app()

        assertThat(InstalledAppsRepository(context).launch(unavailable)).isFalse()
        assertThat(shadowOf(context).nextStartedActivity).isNull()
    }

    @Test
    fun `launch rejects private and disabled app activities`() {
        val privateApp = activity("tv.private", "Private").apply { exported = false }
        val disabled = activity("tv.disabled", "Disabled").apply { applicationInfo.enabled = false }
        install(privateApp, disabled)
        val repository = InstalledAppsRepository(context)

        assertThat(repository.launch(privateApp.app())).isFalse()
        assertThat(repository.launch(disabled.app())).isFalse()
        assertThat(shadowOf(context).nextStartedActivity).isNull()
    }

    @Test
    fun `platform failure between availability check and launch is handled`() {
        val info = activity("tv.alpha", "Alpha")
        install(info)
        val refusingContext = object : ContextWrapper(context) {
            override fun startActivity(intent: Intent) {
                throw ActivityNotFoundException("App was removed while launching")
            }
        }

        assertThat(InstalledAppsRepository(refusingContext).launch(info.app())).isFalse()
        assertThat(shadowOf(context).nextStartedActivity).isNull()
    }

    private fun activity(packageName: String, label: String, name: String = "LaunchActivity") = ActivityInfo().apply {
        this.packageName = packageName
        this.name = name
        nonLocalizedLabel = label
        exported = true
        enabled = true
        applicationInfo = ApplicationInfo().apply {
            this.packageName = packageName
            enabled = true
            nonLocalizedLabel = label
        }
    }

    private fun install(vararg activities: ActivityInfo) {
        for ((packageName, entries) in activities.groupBy { it.packageName }) {
            shadowOf(context.packageManager).installPackage(PackageInfo().apply {
                this.packageName = packageName
                applicationInfo = entries.first().applicationInfo
                this.activities = entries.toTypedArray()
            })
        }
    }

    private fun resolve(activity: ActivityInfo, category: String) {
        val info = ResolveInfo().apply {
            activityInfo = activity
            nonLocalizedLabel = activity.nonLocalizedLabel
        }
        shadowOf(context.packageManager).addResolveInfoForIntent(Intent(Intent.ACTION_MAIN).addCategory(category), info)
    }

    private fun ActivityInfo.app(isTvApp: Boolean = false) = InstalledApp(
        packageName = packageName,
        label = nonLocalizedLabel.toString(),
        componentName = ComponentName(packageName, name),
        icon = null,
        isTvApp = isTvApp,
    )
}
