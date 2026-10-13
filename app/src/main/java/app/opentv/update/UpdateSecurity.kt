package app.opentv.update

import kotlinx.serialization.Serializable
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.io.File
import java.security.MessageDigest

@Serializable
data class UpdateMetadata(
    val schemaVersion: Int,
    val versionCode: Long,
    val versionName: String,
    val applicationId: String,
    val apkAsset: String,
    val sha256: String,
    val sizeBytes: Long,
    val signingCertificateSha256: String,
)

class UpdateValidationException(message: String) : IllegalStateException(message)

/** Pure validation rules, shared by discovery and the installer and covered by JVM tests. */
object UpdateSecurity {
    private val digestPattern = Regex("[a-fA-F0-9]{64}")
    private const val MAX_APK_BYTES = 512L * 1024 * 1024

    fun validateMetadata(metadata: UpdateMetadata, applicationId: String, assetBytes: Long) {
        requireValid(metadata.schemaVersion == 1, "Unsupported update metadata schema")
        requireValid(metadata.applicationId == applicationId, "Update targets a different application")
        requireValid(metadata.versionCode in 1..Int.MAX_VALUE.toLong(), "Invalid update version code")
        requireValid(metadata.versionName.isNotBlank() && metadata.versionName.length <= 128, "Invalid update version name")
        requireValid(metadata.apkAsset == UpdateChecker.APK_ASSET, "Unexpected APK asset")
        requireValid(metadata.sizeBytes in 1..MAX_APK_BYTES && metadata.sizeBytes == assetBytes, "APK size does not match release metadata")
        requireValid(digestPattern.matches(metadata.sha256), "Invalid APK SHA-256")
        requireValid(digestPattern.matches(metadata.signingCertificateSha256), "Invalid signing certificate SHA-256")
    }

    fun requireAssetUrl(raw: String, tag: String, asset: String) {
        val url = raw.toHttpUrlOrNull() ?: throw UpdateValidationException("Invalid release asset URL")
        requireValid(
            url.scheme == "https" && url.host == "github.com" && url.port == 443 &&
                url.username.isEmpty() && url.password.isEmpty() && url.query == null && url.fragment == null &&
                url.pathSegments == listOf("DisabledAbel", "Nuvix", "releases", "download", tag, asset),
            "Update asset does not belong to the trusted release repository",
        )
    }

    fun verifyFile(file: File, expectedBytes: Long, expectedSha256: String) {
        requireValid(file.length() == expectedBytes && expectedBytes > 0, "Downloaded APK length does not match")
        val actual = file.inputStream().use { stream ->
            val digest = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = stream.read(buffer)
                if (read == -1) break
                digest.update(buffer, 0, read)
            }
            hex(digest.digest())
        }
        requireValid(actual.equals(expectedSha256, ignoreCase = true), "Downloaded APK SHA-256 does not match")
    }

    fun validateIdentity(
        installedPackage: String,
        installedVersion: Long,
        installedSigners: Set<String>,
        archivePackage: String,
        archiveVersion: Long,
        archiveVersionName: String?,
        archiveSigners: Set<String>,
        update: UpdateChecker.Update,
    ) {
        requireValid(update.applicationId == installedPackage && archivePackage == installedPackage, "APK package identity does not match")
        requireValid(archiveVersion == update.versionCode && archiveVersion > installedVersion, "APK would not upgrade the installed version")
        requireValid(archiveVersionName == update.versionName, "APK version does not match release metadata")
        requireValid(installedSigners.isNotEmpty() && installedSigners == archiveSigners, "APK signing certificate differs from the installed application")
        // Metadata is a consistency check; the INSTALLED signer is the trust anchor.
        requireValid(archiveSigners.size == 1 && archiveSigners.single().equals(update.signingCertificateSha256, true), "APK signing certificate does not match release metadata")
    }

    fun sha256(bytes: ByteArray): String = hex(MessageDigest.getInstance("SHA-256").digest(bytes))
    private fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it.toInt() and 0xff) }
    private fun requireValid(condition: Boolean, message: String) {
        if (!condition) throw UpdateValidationException(message)
    }
}
