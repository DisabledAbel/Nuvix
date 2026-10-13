package app.opentv.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class UpdateSecurityTest {
    @get:Rule val temporary = TemporaryFolder()
    private val trustedSigner = "a".repeat(64)
    private val attackerSigner = "b".repeat(64)
    private val metadata = UpdateMetadata(1, 21, "0.1.0", "com.disabledabel.nuvix", "nuvix.apk",
        "c".repeat(64), 12, trustedSigner)
    private val update = UpdateChecker.Update(21, "0.1.0", "Nuvix", "", 
        "https://github.com/DisabledAbel/Nuvix/releases/download/v0.1.0/nuvix.apk", 12,
        "https://github.com/DisabledAbel/Nuvix/releases/tag/v0.1.0", "c".repeat(64), trustedSigner, "com.disabledabel.nuvix")

    @Test fun `valid release metadata is accepted`() {
        UpdateSecurity.validateMetadata(metadata, "com.disabledabel.nuvix", 12)
    }

    @Test fun `rejects malformed hashes schema package and asset length`() {
        for (invalid in listOf(
            metadata.copy(schemaVersion = 2), metadata.copy(applicationId = "app.opentv"),
            metadata.copy(apkAsset = "other.apk"), metadata.copy(sizeBytes = 11),
            metadata.copy(sha256 = "bad"), metadata.copy(signingCertificateSha256 = "bad"),
            metadata.copy(versionCode = 0), metadata.copy(sizeBytes = 600L * 1024 * 1024),
        )) assertThrows(UpdateValidationException::class.java) {
            UpdateSecurity.validateMetadata(invalid, "com.disabledabel.nuvix", 12)
        }
    }

    @Test fun `asset URLs must belong to exact release repository and tag`() {
        UpdateSecurity.requireAssetUrl(update.apkUrl, "v0.1.0", "nuvix.apk")
        for (bad in listOf(
            update.apkUrl.replace("https:", "http:"),
            update.apkUrl.replace("github.com", "github.com.attacker.example"),
            update.apkUrl.replace("DisabledAbel", "attacker"),
            update.apkUrl.replace("v0.1.0", "v0.2.0"),
            update.apkUrl + "?redirect=attacker", update.apkUrl + "#fragment",
            update.apkUrl.replace("nuvix.apk", "other.apk"),
        )) assertThrows(UpdateValidationException::class.java) {
            UpdateSecurity.requireAssetUrl(bad, "v0.1.0", "nuvix.apk")
        }
    }

    @Test fun `corrupt and truncated downloads are rejected`() {
        val file = temporary.newFile("update.apk").apply { writeText("verified bytes") }
        val sha = UpdateSecurity.sha256(file.readBytes())
        UpdateSecurity.verifyFile(file, file.length(), sha)
        assertThrows(UpdateValidationException::class.java) { UpdateSecurity.verifyFile(file, file.length() + 1, sha) }
        file.writeText("modified bytes")
        assertThrows(UpdateValidationException::class.java) { UpdateSecurity.verifyFile(file, file.length(), sha) }
    }

    @Test fun `signer metadata cannot replace the installed trust anchor`() {
        assertThrows(UpdateValidationException::class.java) {
            verifyIdentity(update.copy(signingCertificateSha256 = attackerSigner), signers = setOf(attackerSigner))
        }
    }

    @Test fun `correct installed signer and strictly greater numeric build are accepted`() {
        verifyIdentity(update)
    }

    @Test fun `same version downgrade wrong package and forged version metadata are rejected`() {
        assertThrows(UpdateValidationException::class.java) { verifyIdentity(update, installedVersion = 21) }
        assertThrows(UpdateValidationException::class.java) { verifyIdentity(update, installedVersion = 22) }
        assertThrows(UpdateValidationException::class.java) { verifyIdentity(update, archivePackage = "attacker.app") }
        assertThrows(UpdateValidationException::class.java) { verifyIdentity(update, archiveVersion = 22) }
        assertThrows(UpdateValidationException::class.java) { verifyIdentity(update, archiveName = "0.2.0") }
        assertThrows(UpdateValidationException::class.java) { verifyIdentity(update, signers = emptySet()) }
    }

    @Test fun `digest bytes are formatted as full SHA256`() {
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", UpdateSecurity.sha256(byteArrayOf()))
    }

    private fun verifyIdentity(
        candidate: UpdateChecker.Update,
        installedVersion: Long = 20,
        archivePackage: String = "com.disabledabel.nuvix",
        archiveVersion: Long = 21,
        archiveName: String = "0.1.0",
        signers: Set<String> = setOf(trustedSigner),
    ) = UpdateSecurity.validateIdentity("com.disabledabel.nuvix", installedVersion, setOf(trustedSigner),
        archivePackage, archiveVersion, archiveName, signers, candidate)
}
