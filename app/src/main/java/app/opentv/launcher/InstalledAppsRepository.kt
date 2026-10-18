/* Nuvix modifications, Copyright (C) 2026 Nuvix Contributors. GPL-3.0-or-later. */
package app.opentv.launcher

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.net.Uri
import android.provider.Settings
import java.text.Collator

/** Launchable installed package with its display label, icon and launch intent. */
data class InstalledApp(
    val packageName: String,
    val label: String,
    val componentName: ComponentName,
    val icon: Drawable?,
    val isTvApp: Boolean,
)

/** Discovers launchable applications and exposes supported package-management actions. */
class InstalledAppsRepository(private val context: Context) {
    private val pm get() = context.packageManager

    /** Call on a worker dispatcher; prefer a TV launch activity when an app advertises both. */
    fun discover(): List<InstalledApp> {
        val discovered = linkedMapOf<String, InstalledApp>()
        for (category in listOf(Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER)) {
            val intent = Intent(Intent.ACTION_MAIN).addCategory(category)
            for (resolved in pm.queryIntentActivities(intent, 0)) {
                val activity = resolved.activityInfo ?: continue
                if (activity.packageName == context.packageName || !activity.enabled || !activity.exported ||
                    !activity.applicationInfo.enabled || discovered.containsKey(activity.packageName)) continue
                discovered[activity.packageName] = InstalledApp(
                    packageName = activity.packageName,
                    label = runCatching { resolved.loadLabel(pm).toString() }.getOrDefault(activity.packageName),
                    componentName = ComponentName(activity.packageName, activity.name),
                    icon = runCatching { resolved.loadIcon(pm) }.getOrNull(),
                    isTvApp = category == Intent.CATEGORY_LEANBACK_LAUNCHER,
                )
            }
        }
        val collator = Collator.getInstance()
        return discovered.values.sortedWith { a, b -> collator.compare(a.label, b.label) }
    }

    /** Recheck availability immediately before launching to tolerate removal or disabling. */
    fun launch(app: InstalledApp): Boolean = runCatching {
        val info = pm.getActivityInfo(app.componentName, 0)
        if (!info.enabled || !info.exported || !info.applicationInfo.enabled ||
            info.packageName != app.packageName || app.packageName == context.packageName) return@runCatching false
        val intent = Intent(Intent.ACTION_MAIN)
            .addCategory(if (app.isTvApp) Intent.CATEGORY_LEANBACK_LAUNCHER else Intent.CATEGORY_LAUNCHER)
            .setComponent(app.componentName)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        context.startActivity(intent)
        true
    }.getOrDefault(false)

    /** Opens Android's details page for the selected installed package. */
    fun openAppInfo(packageName: String): Boolean = runCatching {
        pm.getApplicationInfo(packageName, 0) // A removed app must not leave a dead context-menu action.
        context.startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        true
    }.getOrDefault(false)
}
