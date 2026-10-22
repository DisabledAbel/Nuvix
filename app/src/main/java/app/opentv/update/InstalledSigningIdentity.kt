package app.opentv.update

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build

/** Reads the installed APK certificate; release metadata cannot choose the trust anchor. */
internal object InstalledSigningIdentity {
    @Suppress("DEPRECATION")
    fun read(context: Context): Set<String> {
        val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
        val info = context.packageManager.getPackageInfo(context.packageName, flags)
        val signatures = if (Build.VERSION.SDK_INT >= 28) info.signingInfo?.apkContentsSigners else info.signatures
        return signatures.orEmpty().map { UpdateSecurity.sha256(it.toByteArray()) }.toSet()
    }
}
