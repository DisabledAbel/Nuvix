package app.opentv.update

import app.opentv.data.remote.GitHubAsset
import app.opentv.data.remote.GitHubRelease
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Test

/** Regression tests for update checker. */
class UpdateCheckerTest {
    private val metadata = UpdateMetadata(1, 21, "0.1.0", "com.disabledabel.nuvix", "nuvix.apk", "a".repeat(64), 123, "b".repeat(64))
    private val release = GitHubRelease("v0.1.0", "Nuvix", "Notes", false, false, "https://github.com/DisabledAbel/Nuvix/releases/tag/v0.1.0",
        listOf(
            GitHubAsset("nuvix.apk", "https://github.com/DisabledAbel/Nuvix/releases/download/v0.1.0/nuvix.apk", 123),
            GitHubAsset("update.json", "https://github.com/DisabledAbel/Nuvix/releases/download/v0.1.0/update.json", 500),
        ))

    /** Checks: higher version Code wins despite a lower display version name. */
    @Test fun `higher versionCode wins despite a lower display version name`() = runTest {
        val candidate = checker(release, metadata, currentCode = 20).check()
        assertEquals(21L, candidate?.versionCode)
        assertEquals("0.1.0", candidate?.versionName)
    }

    /** Checks: equal and lower version Code are ignored regardless of release tag. */
    @Test fun `equal and lower versionCode are ignored regardless of release tag`() = runTest {
        assertNull(checker(release, metadata.copy(versionCode = 20), 20).check())
        assertNull(checker(release, metadata.copy(versionCode = 19), 20).check())
    }

    /** Checks: draft and prerelease are ignored before reading metadata. */
    @Test fun `draft and prerelease are ignored before reading metadata`() = runTest {
        assertNull(checker(release.copy(draft = true, assets = emptyList()), metadata).check())
        assertNull(checker(release.copy(prerelease = true, assets = emptyList()), metadata).check())
    }

    @Test fun `RC tags are excluded even when marked stable by mistake`() = runTest {
        assertNull(checker(release.copy(tagName = "v1.0.0-rc.1", assets = emptyList()), metadata).check())
        assertNull(checker(release.copy(tagName = "nightly-20261015.1.1", assets = emptyList()), metadata).check())
    }

    @Test fun `stable tag must match metadata version`() = runTest {
        expectInvalid(checker(release, metadata.copy(versionName = "1.0.0-rc.1")))
    }

    /** Checks: release cannot choose an arbitrary attached APK. */
    @Test fun `release cannot choose an arbitrary attached APK`() = runTest {
        expectInvalid(checker(release.copy(assets = release.assets.map {
            if (it.name == "nuvix.apk") it.copy(name = "other.apk") else it
        }), metadata))
    }

    /** Checks: duplicate APK assets and a foreign package are rejected. */
    @Test fun `duplicate APK assets and a foreign package are rejected`() = runTest {
        expectInvalid(checker(release.copy(assets = release.assets + release.assets.first()), metadata))
        expectInvalid(checker(release, metadata.copy(applicationId = "attacker.app")))
    }

    /** Checks: release cannot move its metadata asset to another origin. */
    @Test fun `release cannot move its metadata asset to another origin`() = runTest {
        expectInvalid(checker(release.copy(assets = release.assets.map {
            if (it.name == "update.json") it.copy(browserDownloadUrl = "https://attacker.example/update.json") else it
        }), metadata))
    }

    /** Asserts that the synthetic release is rejected by update validation. */
    private suspend fun expectInvalid(checker: UpdateChecker) {
        try { checker.check(); fail("Expected validation failure") } catch (_: UpdateValidationException) { }
    }

    /** Creates an update checker with deterministic release, metadata and installed-version fixtures. */
    private fun checker(release: GitHubRelease, metadata: UpdateMetadata, currentCode: Int = 20): UpdateChecker {
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val url = chain.request().url.toString()
            val body = if (url.startsWith("https://api.github.com/")) {
                assertEquals("https://api.github.com/repos/DisabledAbel/Nuvix/releases/latest", url)
                UpdateChecker.json.encodeToString(release)
            } else {
                assertEquals("https://github.com/DisabledAbel/Nuvix/releases/download/v0.1.0/update.json", url)
                UpdateChecker.json.encodeToString(metadata)
            }
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(200).message("OK").body(body.toResponseBody()).build()
        }.build()
        return UpdateChecker(client, currentCode, "com.disabledabel.nuvix")
    }
}
