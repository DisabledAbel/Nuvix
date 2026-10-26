/* Copyright (C) 2026 Nuvix Contributors. GPL-3.0-or-later. */
package app.opentv.iptv

import android.app.Application
import android.content.Context
import androidx.test.runner.AndroidJUnitRunner
import java.io.File

/** Keep background sync and production database startup out of the synthetic test process. */
class IptvTestRunner : AndroidJUnitRunner() {
    override fun newApplication(cl: ClassLoader, className: String, context: Context): Application =
        super.newApplication(cl, Application::class.java.name, context)

    override fun onStart() {
        // Reinstalling a debug APK preserves app data. An interrupted rerun must not reuse passes.
        File(targetContext.filesDir, "iptv-reports").deleteRecursively()
        super.onStart()
    }
}
