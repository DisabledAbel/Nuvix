# Release signing and update continuity

Every stable Nuvix update must retain the release application ID `com.nuvix.player` and the same private signing key. Increment Android `versionCode` for every distributed update; the prepared first candidate uses code 22, versionName `1.0.0-rc.1`. The `vX.Y.Z` Git tag must match `versionName` exactly. The updater uses `versionCode` to prevent downgrades.

## First release setup in GitHub

**One password, one setup run. No PAT, keystore upload, alias or fingerprint entry is needed.**

1. Save a strong password of **at least 24 characters** in your password manager. Add it
   under **Settings → Secrets and variables → Actions → New repository secret**, named
   `SIGNING_BACKUP_PASSWORD`. Enter it directly in GitHub Settings, never in chat.
2. Open **Actions → Setup Production Signing → Run workflow**, select **main**, and run it
   once. It creates the permanent RSA-3072 signing key and random keystore/key passwords,
   encrypts them in an AES-256 ZIP with your saved password, restores the backup and verifies
   the recovered key can sign data. The built-in workflow token saves the encrypted ZIP in
   a permanent **draft** named **Nuvix encrypted signing backup — keep as draft**.
3. Once setup succeeds, run **Release** normally. Releases load and decrypt the same key
   automatically. You do not need `SIGNING_SETUP_TOKEN`, the four manual signing secrets,
   or a `SIGNING_CERT_SHA256` variable for this path.

The setup summary links to your signing backup and shows the public certificate fingerprint.
Download an offline copy of `nuvix-signing-backup.zip` and keep the password separately.
The draft backup has no Actions artifact expiration. Keep it as a draft: do not publish,
replace or delete it. It contains only encrypted key material; the password is never uploaded.
Opening the ZIP requires an AES-compatible ZIP application. It contains the permanent JKS,
its credentials in `credentials.json`, and the public certificate in `certificate.pem`.

During a release, only the publisher loads the key after both validation jobs pass.
The public fingerprint is compared with prior compatible releases and any optional pinned
`SIGNING_CERT_SHA256` variable. Private credentials stay in a restricted runner directory
and are removed at job completion. No release generates a new key.

Setup refuses an existing or partial manual signing configuration, any release or draft
backup, and workflow reruns. It reserves the signing draft before generation; failures never
silently generate a replacement identity. On failure, inspect the run and existing draft.
Never create another key after distributing signed APKs. Restore the same key from your
backup if necessary. Complete legacy configurations using the four repository signing
secrets and a pinned fingerprint continue to work without changing the signing identity.

The old `SIGNING_SETUP_TOKEN` is no longer used; revoke and remove it if you added one.
Setup needs only the built-in token's `contents: write` permission to save the encrypted
backup. It never calls GitHub's secret/variable-management APIs or commits private files.

## Signing key

Reuse the existing long-lived production keystore. Do not generate or replace it as part of release setup. If no production installation has ever been distributed and no signing identity exists, establish that identity separately and record its certificate before enabling releases. The normal Release workflow never generates a key; one-time Setup creates only the first identity.

Back up the key and passwords securely in multiple protected locations. Losing the private key prevents ordinary in-place updates of existing installations. A different key generally requires users to uninstall the app, which can erase app data and launcher choices. The shared Android debug key is unsuitable for public distribution. Never commit private keys or passwords.

For a local signed build set `KEYSTORE_PATH`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, and `KEY_PASSWORD` in your secure environment, then run `./gradlew :app:assembleRelease`. Supply all four or none; partial configuration fails. No signing credentials produces an unsigned local release APK, never a debug-signed stable APK.

## Existing manual GitHub Actions secrets

This older configuration remains supported for an existing key. First-release users can
skip it and follow [one-password setup](#first-release-setup-in-github).

Configure these **repository secrets** in Settings → Secrets and variables → Actions, as described in [the release guide](releases.md):

| Secret | Contents |
| --- | --- |
| `KEYSTORE_BASE64` | Base64-encoded bytes of the release JKS file |
| `KEYSTORE_PASSWORD` | Keystore password |
| `KEY_ALIAS` | Alias of the release key |
| `KEY_PASSWORD` | Private-key password |

Also set the public repository **variable** `SIGNING_CERT_SHA256` to the 64-character SHA-256 fingerprint of the long-lived release certificate (hexadecimal without colons). Obtain it with `keytool -list -v -keystore nuvix-release.jks -alias nuvix`, which prompts for the password. Verify it against an independently trusted record before setup. Missing or unknown fingerprints block publication; never substitute a PR-provided fingerprint or a newly generated key. Keep this fingerprint stable: publication rejects a different certificate before uploading an unusable update.

Validation has no signing secrets and prepares an unsigned release APK. After Android and database-security validation pass, the trusted publisher verifies actor permissions and artifact digests, decodes the keystore temporarily with restrictive permissions, signs with `apksigner`, verifies the final APK, and removes the key. Signed APKs are not exposed as Actions artifacts. Only the trusted publisher may publish APKs; setup also has `contents: write` solely for encrypted key storage.

Update the committed `versionCode`/`versionName` and changelog, merge reviewed changes into `main`, and manually dispatch Release with a matching version and `rc` or `stable` channel, or push the matching `v<version>` tag at current main. Do not precreate tags for a manual dispatch. RCs use the production package and signer, so stable needs a higher code than every candidate. See [releases](releases.md) for the trusted-actor allowlist, repository signing configuration, failure recovery and the v1.0 checklist. The direct release path requires no production environment or second-reviewer approval.

## Release verification and assets

`scripts/create-release.py` reads Gradle's APK metadata and verifies the actual APK using Android `aapt` and `apksigner`. It rejects unsigned APKs, debuggable APKs, the Android debug certificate, a wrong package ID, an unexpected version, ambiguous APK outputs, a tag mismatch, or a certificate that differs from the configured long-lived key. It creates:

- `nuvix.apk`: verified universal release APK.
- `update.json`: schema 1 metadata containing `versionCode`, `versionName`, `applicationId`, `apkAsset`, APK `sha256`, `sizeBytes`, and `signingCertificateSha256`.
- `SHA256SUMS`: integrity checksums for the APK, manifest, notes and corresponding source in the release workflow (standalone `create-release.py` emits the APK checksum).
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
Never ask users to uninstall just to bypass a signature error. No permanent user signing keys
are generated by tests or the APK release workflow.
