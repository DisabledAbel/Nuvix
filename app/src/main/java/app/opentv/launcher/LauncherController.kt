/* Nuvix modifications, Copyright (C) 2026 Nuvix Contributors. GPL-3.0-or-later. */
package app.opentv.launcher

import android.app.role.RoleManager
import android.app.UiModeManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Build
import android.provider.Settings

/** Device capability and current default-home status displayed by launcher settings. */
data class LauncherStatus(
    val enabled: Boolean,
    val isDefault: Boolean,
    val isTelevision: Boolean,
    val canRequestDefault: Boolean,
    val defaultLabel: String?,
    val compatibilityMessage: String,
)

/** Uses supported Android settings/roles only; never alters another launcher or intercepts Home. */
class LauncherController(private val context: Context) {
    val homeComponent = ComponentName(context.packageName, "app.opentv.launcher.NuvixHome")
    private val pm get() = context.packageManager

    /** Checks whether the optional HOME component is enabled. */
    fun isEnabled(): Boolean = pm.getComponentEnabledSetting(homeComponent) ==
        PackageManager.COMPONENT_ENABLED_STATE_ENABLED

    /** Changes the optional HOME component's enabled state. */
    fun setEnabled(enabled: Boolean) {
        pm.setComponentEnabledSetting(
            homeComponent,
            if (enabled) PackageManager.COMPONENT_ENABLED_STATE_ENABLED else PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
            PackageManager.DONT_KILL_APP,
        )
    }

    /** Inspects launcher availability and current home selection for this device. */
    fun status(): LauncherStatus {
        val television = pm.hasSystemFeature(PackageManager.FEATURE_LEANBACK) ||
            (context.getSystemService(Context.UI_MODE_SERVICE) as? UiModeManager)?.currentModeType ==
            Configuration.UI_MODE_TYPE_TELEVISION
        val default = runCatching {
            pm.resolveActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), PackageManager.MATCH_DEFAULT_ONLY)
        }.getOrNull()
        val defaultActivity = default?.activityInfo
        val isDefault = defaultActivity?.packageName == context.packageName
        val canRequest = defaultHomeRequest() != null
        val message = when {
            Build.MANUFACTURER.equals("Amazon", ignoreCase = true) ->
                "Fire TV commonly restricts replacing Home. Use Nuvix as a normal app if the system offers no launcher selector."
            !television -> "This device is not reported as Android TV. Home selection depends on its Android settings."
            !canRequest -> "This device exposes no supported Home selector. Nuvix remains available as a normal IPTV app."
            else -> "Android TV / Google TV support depends on the device maker. Only the Android selector can make Nuvix the default."
        }
        return LauncherStatus(
            enabled = isEnabled(),
            isDefault = isDefault,
            isTelevision = television,
            canRequestDefault = canRequest,
            defaultLabel = runCatching { default?.loadLabel(pm)?.toString() }.getOrNull(),
            compatibilityMessage = message,
        )
    }

    /** Builds the supported system request for selecting the default home application. */
    fun defaultHomeRequest(): Intent? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val roles = context.getSystemService(RoleManager::class.java)
            if (roles != null && runCatching { roles.isRoleAvailable(RoleManager.ROLE_HOME) }.getOrDefault(false)) {
                return runCatching { roles.createRequestRoleIntent(RoleManager.ROLE_HOME) }.getOrNull()
            }
        }
        return settingsIntent(Settings.ACTION_HOME_SETTINGS)
    }

    /** Finds an available intent for the device's system settings. */
    fun deviceSettingsIntent(): Intent? = settingsIntent(Settings.ACTION_SETTINGS)

    /** Finds an available intent for the default-home settings page. */
    fun homeSettingsIntent(): Intent? = settingsIntent(Settings.ACTION_HOME_SETTINGS)

    /** Builds a resolvable settings intent for the requested system action. */
    private fun settingsIntent(action: String): Intent? {
        val intent = Intent(action)
        return intent.takeIf { pm.resolveActivity(it, PackageManager.MATCH_DEFAULT_ONLY) != null }
    }

    companion object {
        /** Recognizes an Android HOME launch intent. */
        fun isHomeIntent(intent: Intent?): Boolean =
            intent?.action == Intent.ACTION_MAIN && intent.hasCategory(Intent.CATEGORY_HOME)
    }
}
