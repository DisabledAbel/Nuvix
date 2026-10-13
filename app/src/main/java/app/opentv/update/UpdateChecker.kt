/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Nuvix modifications Copyright (C) 2026 Nuvix Contributors.
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.update

import app.opentv.data.remote.GitHubRelease
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

/** Only stable releases from the application's fixed GitHub origin are eligible. */
class UpdateChecker(
    private val http: OkHttpClient,
    private val currentVersionCode: Int,
    private val applicationId: String,
) {
    @Serializable
    data class Update(
        val versionCode: Long,
        val versionName: String,
        val title: String,
        val notes: String,
        val apkUrl: String,
        val apkSizeBytes: Long,
        val releaseUrl: String,
        val sha256: String,
        val signingCertificateSha256: String,
        val applicationId: String,
    )

    /** null means current/no stable release; failures remain distinguishable for manual checks. */
    suspend fun check(): Update? = withContext(Dispatchers.IO) {
        val releaseRequest = Request.Builder()
            .url("https://api.github.com/repos/$REPO_SLUG/releases/latest")
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", "Nuvix")
            .build()
        val release = http.awaitResponse(releaseRequest).use { response ->
            if (response.code == 404) return@withContext null
            if (!response.isSuccessful) throw IOException("Update check failed: HTTP ${response.code}")
            val body = response.body ?: throw IOException("Empty release response")
            if (body.contentLength() > MAX_RELEASE_BYTES) throw IOException("Release response too large")
            json.decodeFromString<GitHubRelease>(body.readLimited(MAX_RELEASE_BYTES))
        }
        if (release.draft || release.prerelease) return@withContext null
        val apk = release.assets.singleOrNull { it.name == APK_ASSET }
            ?: throw UpdateValidationException("Release must contain one $APK_ASSET")
        val metadataAsset = release.assets.singleOrNull { it.name == METADATA_ASSET }
            ?: throw UpdateValidationException("Release must contain one $METADATA_ASSET")
        UpdateSecurity.requireAssetUrl(apk.browserDownloadUrl, release.tagName, APK_ASSET)
        UpdateSecurity.requireAssetUrl(metadataAsset.browserDownloadUrl, release.tagName, METADATA_ASSET)
        if (metadataAsset.size !in 1..MAX_METADATA_BYTES.toLong()) {
            throw UpdateValidationException("Invalid update metadata size")
        }
        val metadata = http.awaitResponse(
            Request.Builder().url(metadataAsset.browserDownloadUrl).header("User-Agent", "Nuvix").build(),
        ).use { response ->
            if (!response.isSuccessful) throw IOException("Metadata download failed: HTTP ${response.code}")
            val body = response.body ?: throw IOException("Empty update metadata")
            json.decodeFromString<UpdateMetadata>(body.readLimited(MAX_METADATA_BYTES))
        }
        UpdateSecurity.validateMetadata(metadata, applicationId, apk.size)
        if (metadata.versionCode <= currentVersionCode.toLong()) return@withContext null
        Update(
            versionCode = metadata.versionCode,
            versionName = metadata.versionName,
            title = release.name.ifBlank { "Nuvix ${metadata.versionName}" },
            notes = cleanNotes(release.body),
            apkUrl = apk.browserDownloadUrl,
            apkSizeBytes = metadata.sizeBytes,
            releaseUrl = "https://github.com/$REPO_SLUG/releases/tag/${release.tagName}",
            sha256 = metadata.sha256.lowercase(),
            signingCertificateSha256 = metadata.signingCertificateSha256.lowercase(),
            applicationId = metadata.applicationId,
        )
    }

    companion object {
        const val REPO_SLUG = "DisabledAbel/Nuvix"
        const val APK_ASSET = "nuvix.apk"
        const val METADATA_ASSET = "update.json"
        private const val MAX_RELEASE_BYTES = 512 * 1024
        private const val MAX_METADATA_BYTES = 16 * 1024
        internal val json = Json { ignoreUnknownKeys = true }

        fun cleanNotes(raw: String): String {
            var text = raw
            for (marker in listOf("Built by GitHub Actions", "**Full Changelog**", "## What's Changed", "<!--")) {
                val index = text.indexOf(marker, ignoreCase = true)
                if (index >= 0) text = text.substring(0, index)
            }
            return text.replace(Regex("""\[([^\]]+)\]\([^)]*\)"""), "$1")
                .replace(Regex("""[*_]{1,3}([^*_\n]+)[*_]{1,3}"""), "$1")
                .replace("`", "")
                .replace(Regex("""(?m)^\s{0,3}#{1,6}\s*"""), "")
                .replace(Regex("""(?m)^\s*[-*]\s+"""), "• ")
                .trim().take(1200)
        }
    }
}
