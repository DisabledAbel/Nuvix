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
    private val metadata = UpdateMetadata(1, 21, "0.1.0", "com.nuvix.player", "nuvix.apk", "a".repeat(64), 123, "b".repeat(64))
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
        for (installed in listOf(21, 22, 23)) {
            assertNull(checker(release.copy(tagName = "v1.0.0-rc.2", assets = emptyList()), metadata, installed).check())
        }
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
        assertNull(checker(release, metadata.copy(applicationId = "attacker.app")).check())
    }

    /** Checks: release cannot move its metadata asset to another origin. */
    @Test fun `release cannot move its metadata asset to another origin`() = runTest {
        expectInvalid(checker(release.copy(assets = release.assets.map {
            if (it.name == "update.json") it.copy(browserDownloadUrl = "https://attacker.example/update.json") else it
        }), metadata))
    }

    @Test fun `different signer is never advertised as compatible`() = runTest {
        assertNull(checker(release, metadata.copy(signingCertificateSha256 = "c".repeat(64))).check())
    }

    @Test fun `old package cannot upgrade to the new production identity`() = runTest {
        assertNull(checker(release, metadata.copy(applicationId = "com.disabledabel.nuvix")).check())
    }

    @Test fun `missing installed certificate fails closed before networking`() = runTest {
        expectInvalid(UpdateChecker(OkHttpClient(), 20, "com.nuvix.player") { emptySet() })
    }

    @Test fun `RC installation can discover a higher code stable release`() = runTest {
        // Version name is deliberately not used for installed-version comparison.
        assertEquals(23L, checker(release, metadata.copy(versionCode = 23), 22).check()?.versionCode)
    }

    @Test fun `RC2 can advance to stable code 24 but equal codes downgrades and changed signers are ignored`() = runTest {
        val stable = release.copy(tagName = "v1.0.0", assets = release.assets.map {
            it.copy(browserDownloadUrl = it.browserDownloadUrl.replace("v0.1.0", "v1.0.0"))
        })
        val stableMetadata = metadata.copy(versionName = "1.0.0", versionCode = 24)
        assertEquals(24L, checker(stable, stableMetadata, 23).check()?.versionCode)
        for (code in listOf(22L, 23L)) {
            assertNull(checker(stable, stableMetadata.copy(versionCode = code), 23).check())
        }
        assertNull(checker(stable, stableMetadata.copy(signingCertificateSha256 = "c".repeat(64)), 23).check())
    }

    @Test fun `discovery selects highest compatible code across stable releases`() = runTest {
        fun fixture(version: String, code: Long, signer: String = "b".repeat(64)): Pair<GitHubRelease, UpdateMetadata> {
            val tag = "v$version"
            return release.copy(tagName = tag, assets = release.assets.map {
                it.copy(browserDownloadUrl = it.browserDownloadUrl.replace("v0.1.0", tag))
            }) to metadata.copy(versionName = version, versionCode = code, signingCertificateSha256 = signer)
        }
        val pages = listOf(listOf(fixture("0.4.0", 24, "c".repeat(64)), fixture("0.3.0", 23), fixture("0.2.0", 22)))
        assertEquals(23L, paginatedChecker(pages).check()?.versionCode)
    }

    @Test fun `discovery continues beyond a page full of ignored releases`() = runTest {
        val ignored = release.copy(prerelease = true, assets = emptyList()) to metadata
        val pages = listOf(List(30) { ignored }, listOf(release to metadata))
        assertEquals(21L, paginatedChecker(pages).check()?.versionCode)
    }

    @Test fun `unbounded history fails closed`() = runTest {
        val ignored = release.copy(draft = true, assets = emptyList()) to metadata
        expectInvalid(paginatedChecker(List(10) { List(30) { ignored } }))
    }

    private fun paginatedChecker(pages: List<List<Pair<GitHubRelease, UpdateMetadata>>>): UpdateChecker {
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            val body = if (request.url.host == "api.github.com") {
                assertEquals("30", request.url.queryParameter("per_page"))
                val page = request.url.queryParameter("page")!!.toInt()
                UpdateChecker.json.encodeToString(pages[page - 1].map { it.first })
            } else {
                val entry = pages.flatten().first { pair ->
                    pair.first.assets.any { it.name == "update.json" && it.browserDownloadUrl == request.url.toString() }
                }
                UpdateChecker.json.encodeToString(entry.second)
            }
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
                .code(200).message("OK").body(body.toResponseBody()).build()
        }.build()
        return UpdateChecker(client, 20, "com.nuvix.player") { setOf("b".repeat(64)) }
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
                assertEquals("https://api.github.com/repos/DisabledAbel/Nuvix/releases?per_page=30&page=1", url)
                UpdateChecker.json.encodeToString(listOf(release))
            } else {
                assertEquals(release.assets.single { it.name == "update.json" }.browserDownloadUrl, url)
                UpdateChecker.json.encodeToString(metadata)
            }
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(200).message("OK").body(body.toResponseBody()).build()
        }.build()
        return UpdateChecker(client, currentCode, "com.nuvix.player") { setOf("b".repeat(64)) }
    }
}
