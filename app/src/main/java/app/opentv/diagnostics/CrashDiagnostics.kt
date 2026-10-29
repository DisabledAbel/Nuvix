/* Nuvix modifications Copyright (C) 2026 Nuvix Contributors. GPL-3.0-or-later. */
package app.opentv.diagnostics

import android.content.Context
import android.util.AtomicFile
import com.disabledabel.nuvix.BuildConfig
import java.io.File
import java.io.FileOutputStream

/**
 * Saves one bounded, local-only report for uncaught Java/Kotlin exceptions.
 *
 * Deliberately excludes Throwable.message, file paths, thread names, provider data and
 * full logcat. This is not a substitute for system exit diagnostics (ANR, OOM or native crash).
 */
object CrashDiagnostics {
    private const val REPORT_FILE = "last-uncaught-crash-v1.txt"
    private const val MAX_REPORT_LENGTH = 3500
    @Volatile private var installed = false

    fun install(context: Context) {
        if (installed) return
        val previous = Thread.getDefaultUncaughtExceptionHandler() ?: return
        val application = context.applicationContext
        synchronized(this) {
            if (installed) return
            Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
                try {
                    val report = CrashReportFormatter.format(
                        throwable = throwable,
                        timestampUtcMillis = System.currentTimeMillis(),
                        versionName = BuildConfig.VERSION_NAME,
                        versionCode = BuildConfig.VERSION_CODE,
                    )
                    write(application, report.take(MAX_REPORT_LENGTH))
                } catch (_: Throwable) {
                    // Never suppress or replace the original crash.
                } finally {
                    previous.uncaughtException(thread, throwable)
                }
            }
            installed = true
        }
    }

    private fun atomic(context: Context) =
        AtomicFile(File(context.noBackupFilesDir, REPORT_FILE))

    private fun write(context: Context, report: String) {
        val file = atomic(context)
        var stream: FileOutputStream? = null
        try {
            val output = file.startWrite()
            stream = output
            output.write(report.toByteArray(Charsets.UTF_8))
            file.finishWrite(output)
        } catch (_: Throwable) {
            stream?.let {
                try { file.failWrite(it) } catch (_: Throwable) { /* Best effort only. */ }
            }
        }
    }

    /** Reads only app-private diagnostic data; never accesses logcat or user media. */
    fun latest(context: Context): String? = try {
        atomic(context).openRead().bufferedReader(Charsets.UTF_8).use {
            it.readText().take(MAX_REPORT_LENGTH).ifBlank { null }
        }
    } catch (_: Exception) {
        null
    }

    /** Explicit user action, independent of playback diagnostics and app data. */
    fun clear(context: Context) {
        try { atomic(context).delete() } catch (_: Exception) { /* No fatal UI error. */ }
    }
}

/** Pure and unit-testable formatting: only validated program symbols, never raw messages. */
internal object CrashReportFormatter {
    private val symbol = Regex("[A-Za-z_$][A-Za-z0-9_.$]*")
    private val method = Regex("[A-Za-z_$<>][A-Za-z0-9_$<>-]*")

    fun format(
        throwable: Throwable,
        timestampUtcMillis: Long,
        versionName: String,
        versionCode: Int,
    ): String = buildString {
        appendLine("Nuvix local crash report (no exception messages or provider data)")
        appendLine("Time (Unix ms): ${timestampUtcMillis.coerceAtLeast(0)}")
        appendLine("Version: ${versionName.filter { it.isLetterOrDigit() || it in ".-_" }.take(32)} ($versionCode)")
        var cause: Throwable? = throwable
        var depth = 0
        while (cause != null && depth < 3) {
            val current = cause ?: break
            appendLine("Exception ${depth + 1}: ${current.javaClass.name.takeIf(symbol::matches) ?: "Unknown"}")
            var accepted = 0
            for (frame in current.stackTrace.take(30)) {
                if (accepted >= 12) break
                if (!symbol.matches(frame.className) || !method.matches(frame.methodName)) continue
                append("  at ${frame.className}.${frame.methodName}")
                if (frame.lineNumber > 0) append(":${frame.lineNumber}")
                appendLine()
                accepted++
            }
            if (accepted == 0) appendLine("  (no safe stack frames)")
            cause = current.cause
            depth++
        }
    }.take(3500)
}
