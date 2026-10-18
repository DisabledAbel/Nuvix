/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Nuvix modifications Copyright (C) 2026 Nuvix Contributors.
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.data.remote

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The slice of GitHub's `releases/latest` response we care about.
 *
 * Every field defaults, so a shape change on GitHub's side (a renamed or dropped key) leaves
 * us with an empty value rather than a parse exception that would break launch. `ignoreUnknownKeys`
 * on the decoder handles the ninety other fields we do not read.
 */
@Serializable
data class GitHubRelease(
    @SerialName("tag_name") val tagName: String = "",
    val name: String = "",
    val body: String = "",
    val draft: Boolean = false,
    val prerelease: Boolean = false,
    @SerialName("html_url") val htmlUrl: String = "",
    val assets: List<GitHubAsset> = emptyList(),
) {
    /** Only the documented release APK is eligible; unrelated attachments are ignored. */
    fun apkAsset(): GitHubAsset? = assets.singleOrNull { it.name == "nuvix.apk" }
}

/** Release asset name, browser download URL and published byte size. */
@Serializable
data class GitHubAsset(
    val name: String = "",
    @SerialName("browser_download_url") val browserDownloadUrl: String = "",
    val size: Long = 0,
)
