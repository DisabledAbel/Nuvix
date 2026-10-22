/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Nuvix modifications Copyright (C) 2026 Nuvix Contributors.
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.update

import android.content.Context
import com.disabledabel.nuvix.BuildConfig
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
    private val installedSigners: () -> Set<String>,
) {
    /** Validated stable release identity, notes and APK integrity metadata offered to the installer. */
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
        val signers = installedSigners().map { it.lowercase() }.toSet()
        if (signers.size != 1) throw UpdateValidationException("Installed signing certificate unavailable or ambiguous")
        var latest: Update? = null
        // GitHub's latest marker can point to an old package. Search bounded, paginated stable
        // releases and select by Android versionCode, never by the GitHub latest marker alone.
        for (page in 1..MAX_RELEASE_PAGES) {
            val request = Request.Builder()
                .url("https://api.github.com/repos/$REPO_SLUG/releases?per_page=$PAGE_SIZE&page=$page")
                .header("Accept", "application/vnd.github+json")
                .header("User-Agent", "Nuvix")
                .build()
            val releases = http.awaitResponse(request).use { response ->
                if (response.code == 404) return@withContext null
                if (!response.isSuccessful) throw IOException("Update check failed: HTTP ${response.code}")
                val body = response.body ?: throw IOException("Empty release response")
                if (body.contentLength() > MAX_RELEASE_BYTES) throw IOException("Release response too large")
                json.decodeFromString<List<GitHubRelease>>(body.readLimited(MAX_RELEASE_BYTES))
            }
            for (release in releases) {
                val candidate = compatibleUpdate(release, signers) ?: continue
                if (latest == null || candidate.versionCode > latest.versionCode) latest = candidate
            }
            if (releases.size < PAGE_SIZE) return@withContext latest
        }
        throw UpdateValidationException("Release history exceeds update discovery limit")
    }

    private suspend fun compatibleUpdate(release: GitHubRelease, signers: Set<String>): Update? {
        if (release.draft || release.prerelease || !STABLE_TAG.matches(release.tagName)) return null
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
        // An old package or a different signer must never be advertised as an upgrade.
        if (metadata.applicationId != applicationId || metadata.signingCertificateSha256.lowercase() !in signers) return null
        UpdateSecurity.validateMetadata(metadata, applicationId, apk.size)
        if (metadata.versionName != release.tagName.removePrefix("v")) {
            throw UpdateValidationException("Stable release tag and metadata version differ")
        }
        if (metadata.versionCode <= currentVersionCode.toLong()) return null
        return Update(
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
        private const val PAGE_SIZE = 30
        private const val MAX_RELEASE_PAGES = 10

        /** Use Android's installed certificate as the discovery trust anchor, including API 23. */
        fun forInstalledApplication(context: Context): UpdateChecker = UpdateChecker(
            UpdateNetwork.client, BuildConfig.VERSION_CODE, context.packageName,
            { InstalledSigningIdentity.read(context) },
        )

        private val STABLE_TAG = Regex("""v(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)""")
        const val REPO_SLUG = "DisabledAbel/Nuvix"
        const val APK_ASSET = "nuvix.apk"
        const val METADATA_ASSET = "update.json"
        private const val MAX_RELEASE_BYTES = 512 * 1024
        private const val MAX_METADATA_BYTES = 16 * 1024
        internal val json = Json { ignoreUnknownKeys = true }

        /** Sanitizes and bounds release notes before displaying them in update UI. */
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
