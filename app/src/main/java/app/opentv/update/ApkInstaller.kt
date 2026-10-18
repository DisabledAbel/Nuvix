/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Nuvix modifications Copyright (C) 2026 Nuvix Contributors.
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.update

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException

/** Validates against the installed signer, then asks the platform for an interactive update. */
class ApkInstaller(private val http: OkHttpClient = UpdateNetwork.client) {
    /** Receives APK download progress without owning download or installation state. */
    fun interface Progress {
        /** Reports the current APK download fraction to the caller. */
        fun onProgress(fraction: Float)
    }

    /** Outcome of attempting to submit a verified APK to Android's package installer. */
    sealed interface InstallResult {
        /** Installation state requiring the user to grant install-source permission. */
        data object PermissionRequired : InstallResult
        /** Installation state with the PackageInstaller session ID submitted to Android. */
        data class Submitted(val sessionId: Int) : InstallResult
    }

    /** Downloads an eligible release APK and verifies its metadata and integrity. */
    suspend fun download(
        context: Context,
        update: UpdateChecker.Update,
        progress: Progress = Progress {},
    ): File = downloadLock.withLock {
        withContext(Dispatchers.IO) {
            validateUpdate(context, update)
            val dir = File(context.filesDir, "updates").apply {
                if (!mkdirs() && !isDirectory) throw IOException("Cannot create update directory")
            }
            val output = File(dir, "nuvix-${update.versionCode}.apk")
            if (output.exists()) {
                try {
                    validateApk(context, output, update)
                    progress.onProgress(1f)
                    return@withContext output
                } catch (_: UpdateValidationException) {
                    output.delete()
                }
            }
            val partial = File(dir, "nuvix-${update.versionCode}.part")
            try {
                var lastFailure: IOException? = null
                for (attempt in 0..2) {
                    currentCoroutineContext().ensureActive()
                    try {
                        downloadOnce(update, partial, progress)
                        lastFailure = null
                        break
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (failure: IOException) {
                        lastFailure = failure
                        partial.delete()
                        if (attempt < 2) delay((attempt + 1) * 1500L)
                    }
                }
                lastFailure?.let { throw it }
                validateApk(context, partial, update)
                currentCoroutineContext().ensureActive()
                if (!partial.renameTo(output)) throw IOException("Cannot store verified update")
                // Retain at most the current candidate; never delete a file another active download uses.
                dir.listFiles()?.filter { it != output && it.extension == "apk" }?.forEach { it.delete() }
                output
            } finally {
                partial.delete()
            }
        }
    }

    /** Performs one APK transfer with progress, cancellation and bounded output handling. */
    private suspend fun downloadOnce(update: UpdateChecker.Update, partial: File, progress: Progress) {
        http.awaitResponse(Request.Builder().url(update.apkUrl).header("User-Agent", "Nuvix").build()).use { response ->
            if (!response.isSuccessful) throw IOException("Download failed: HTTP ${response.code}")
            val body = response.body ?: throw IOException("Empty APK response")
            if (body.contentLength() > 0 && body.contentLength() != update.apkSizeBytes) {
                throw UpdateValidationException("APK response size does not match release metadata")
            }
            coroutineScope {
                // Closing the body on cancellation interrupts a blocked socket read immediately.
                val closer = launch {
                    try { awaitCancellation() } finally { response.close() }
                }
                try {
                    body.byteStream().use { source ->
                        partial.outputStream().use { sink ->
                            val buffer = ByteArray(64 * 1024)
                            var written = 0L
                            while (true) {
                                currentCoroutineContext().ensureActive()
                                val read = source.read(buffer)
                                if (read == -1) break
                                written += read
                                if (written > update.apkSizeBytes) throw UpdateValidationException("APK exceeds expected length")
                                sink.write(buffer, 0, read)
                                progress.onProgress((written.toFloat() / update.apkSizeBytes).coerceIn(0f, 1f))
                            }
                        }
                    }
                } finally {
                    closer.cancel()
                }
            }
        }
    }

    /** Validates the APK and submits an interactive Android installation session when permission allows. */
    suspend fun install(context: Context, apk: File, update: UpdateChecker.Update): InstallResult = withContext(Dispatchers.IO) {
        validateUpdate(context, update)
        validateApk(context, apk, update) // Repeat every security check at the installation boundary.
        if (Build.VERSION.SDK_INT >= 26 && !context.packageManager.canRequestPackageInstalls()) {
            return@withContext InstallResult.PermissionRequired
        }
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName)
            setSize(apk.length())
            if (Build.VERSION.SDK_INT >= 31) setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_REQUIRED)
        }
        val sessionId = installer.createSession(params)
        try {
            installer.openSession(sessionId).use { session ->
                session.openWrite("nuvix.apk", 0, apk.length()).use { destination ->
                    apk.inputStream().use { source ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val count = source.read(buffer)
                            if (count == -1) break
                            destination.write(buffer, 0, count)
                        }
                    }
                    session.fsync(destination)
                }
                currentCoroutineContext().ensureActive()
                val intent = Intent(context, InstallStatusReceiver::class.java)
                    .setAction("${context.packageName}.UPDATE_INSTALL_STATUS")
                val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                    if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
                val callback = PendingIntent.getBroadcast(context, sessionId, intent, flags)
                UpdatePreferences(context).savePendingSession(sessionId, update)
                session.commit(callback.intentSender)
            }
            InstallResult.Submitted(sessionId)
        } catch (failure: Throwable) {
            runCatching { installer.abandonSession(sessionId) }
            UpdatePreferences(context).clearPendingSession()
            throw failure
        }
    }

    /** Opens Android's install-source permission settings for this application. */
    fun openInstallPermission(context: Context) {
        if (Build.VERSION.SDK_INT >= 26) {
            context.startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }

    /** Validates release metadata before an APK download or installation attempt. */
    private fun validateUpdate(context: Context, update: UpdateChecker.Update) {
        val url = update.apkUrl.toHttpUrlOrNull() ?: throw UpdateValidationException("Invalid APK URL")
        val tag = url.pathSegments.getOrNull(4) ?: throw UpdateValidationException("Invalid APK URL")
        UpdateSecurity.requireAssetUrl(update.apkUrl, tag, UpdateChecker.APK_ASSET)
        UpdateSecurity.validateMetadata(
            UpdateMetadata(1, update.versionCode, update.versionName, update.applicationId, UpdateChecker.APK_ASSET,
                update.sha256, update.apkSizeBytes, update.signingCertificateSha256),
            context.packageName,
            update.apkSizeBytes,
        )
    }

    /** Checks the downloaded APK's package, version and signer against the allowed update identity. */
    @Suppress("DEPRECATION")
    private fun validateApk(context: Context, apk: File, update: UpdateChecker.Update) {
        UpdateSecurity.verifyFile(apk, update.apkSizeBytes, update.sha256)
        val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
        val installed = context.packageManager.getPackageInfo(context.packageName, flags)
        val archive = context.packageManager.getPackageArchiveInfo(apk.absolutePath, flags)
            ?: throw UpdateValidationException("Downloaded file is not a valid signed APK")
        UpdateSecurity.validateIdentity(
            installed.packageName, versionCode(installed), signerDigests(installed),
            archive.packageName, versionCode(archive), archive.versionName, signerDigests(archive), update,
        )
    }

    /** Reads an APK's numeric version using the supported Android package-info API. */
    @Suppress("DEPRECATION")
    private fun versionCode(info: PackageInfo): Long = if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()

    /** Computes signing-certificate digests from Android package information. */
    @Suppress("DEPRECATION")
    private fun signerDigests(info: PackageInfo): Set<String> {
        val signatures = if (Build.VERSION.SDK_INT >= 28) info.signingInfo?.apkContentsSigners else info.signatures
        return signatures.orEmpty().map { UpdateSecurity.sha256(it.toByteArray()) }.toSet()
    }

    private companion object { val downloadLock = Mutex() }
}
