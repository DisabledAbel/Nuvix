# Release signing and update continuity

Every stable Nuvix update must retain the release application ID `com.nuvix.player` and the same private signing key. Increment Android `versionCode` for every distributed update; the current source retains code 21 (after OpenTV's source baseline of 20), versionName `0.1.0`. The `vX.Y.Z` Git tag must match `versionName` exactly. The updater uses `versionCode` to prevent downgrades.

## Signing key

Reuse the existing long-lived production keystore. Do not generate or replace it as part of release setup. If no production installation has ever been distributed and no signing identity exists, establish that identity separately and record its certificate before enabling releases. The workflow never generates a production key.

Back up the key and passwords securely in multiple protected locations. Losing the private key prevents ordinary in-place updates of existing installations. A different key generally requires users to uninstall the app, which can erase app data and launcher choices. The shared Android debug key is unsuitable for public distribution. Never commit private keys or passwords.

For a local signed build set `KEYSTORE_PATH`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, and `KEY_PASSWORD` in your secure environment, then run `./gradlew :app:assembleRelease`. Supply all four or none; partial configuration fails. No signing credentials produces an unsigned local release APK, never a debug-signed stable APK.

## GitHub Actions secrets

Configure these **production environment secrets** only after setting the protection rules in [the release guide](releases.md):

| Secret | Contents |
| --- | --- |
| `KEYSTORE_BASE64` | Base64-encoded bytes of the release JKS file |
| `KEYSTORE_PASSWORD` | Keystore password |
| `KEY_ALIAS` | Alias of the release key |
| `KEY_PASSWORD` | Private-key password |

Also set the public production environment **variable** `SIGNING_CERT_SHA256` to the 64-character SHA-256 fingerprint of the long-lived release certificate (hexadecimal without colons). Obtain it with `keytool -list -v -keystore nuvix-release.jks -alias nuvix`, which prompts for the password. Verify it against an independently trusted record before setup. Missing or unknown fingerprints block publication; never substitute a PR-provided fingerprint or a newly generated key. Keep this fingerprint stable: publication rejects a different certificate before uploading an unusable update.

Validation has no signing secrets and prepares an unsigned release APK. After a different maintainer approves `production`, the publishing job verifies the recorded approval and artifact digests, decodes the keystore temporarily with restrictive permissions, signs with `apksigner`, verifies the final APK, and removes the key. Signed APKs are not exposed as Actions artifacts. Only the gated publishing job has `contents: write`.

Update the committed `versionCode`/`versionName` and changelog, merge reviewed changes into `main`, and manually dispatch Release with a matching version and `rc` or `stable` channel. Do not precreate tags. RCs use the production package and signer, so stable needs a higher code than every candidate. See [manual releases](releases.md) for approval, environment verification, failure recovery and the v1.0 checklist.

## Release verification and assets

`scripts/create-release.py` reads Gradle's APK metadata and verifies the actual APK using Android `aapt` and `apksigner`. It rejects unsigned APKs, debuggable APKs, the Android debug certificate, a wrong package ID, an unexpected version, ambiguous APK outputs, a tag mismatch, or a certificate that differs from the configured long-lived key. It creates:

- `nuvix.apk`: verified universal release APK.
- `update.json`: schema 1 metadata containing `versionCode`, `versionName`, `applicationId`, `apkAsset`, APK `sha256`, `sizeBytes`, and `signingCertificateSha256`.
- `SHA256SUMS`: integrity checksums for the APK, manifest, notes and corresponding source in the approved workflow (standalone `create-release.py` emits the APK checksum).
- Release notes extracted from the matching changelog section.

The workflow attaches a `nuvix-vX.Y.Z-source.tar.gz` archive from the exact tagged commit, including the GPL license, to meet corresponding-source obligations. GitHub Releases also exposes that commit's source archives. Preserve third-party copyright notices and licenses when distributing binaries.

The application must be configured to trust your independently maintained GitHub repository before checking releases. Configure the same stable release source on all devices. Android still requires user approval to install an APK and may require granting Nuvix permission to install unknown apps. This workflow cannot enable silent installation on consumer devices. Keep your repository and signing secrets protected: a malicious release signed with the legitimate key can pass certificate checks.

## Package transition and mismatch recovery

The old `com.disabledabel.nuvix` package cannot upgrade in place to `com.nuvix.player`.
Keep the existing production key through this transition; only the installed ID changes.
See [migration steps](releases.md#application-id-migration). Subsequent RC/stable releases use
one permanent ID, signer and increasing code sequence. The protected fingerprint is independent
of repository changes and is cross-checked against prior new-package release metadata.

On a mismatch, stop publication and compare the trusted record with the **existing** keystore
and previously trusted production APKs in a secure environment. Restore the correct key/alias
configuration; do not update the expected fingerprint merely to pass CI. If the key is lost,
ordinary updates cannot be preserved; resolve recovery separately before distributing anything.
Never ask users to uninstall just to bypass a signature error. No signing keys or certificates
are generated by the identity tests or migration workflow.
