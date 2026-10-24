# Release signing and update continuity

Every stable Nuvix update must retain the release application ID `com.nuvix.player` and the same private signing key. Increment Android `versionCode` for every distributed update; the prepared first candidate uses code 22, versionName `1.0.0-rc.1`. The `vX.Y.Z` Git tag must match `versionName` exactly. The updater uses `versionCode` to prevent downgrades.

## Signing key

Reuse the existing long-lived production keystore. Do not generate or replace it as part of release setup. If no production installation has ever been distributed and no signing identity exists, establish that identity separately and record its certificate before enabling releases. The workflow never generates a production key.

Back up the key and passwords securely in multiple protected locations. Losing the private key prevents ordinary in-place updates of existing installations. A different key generally requires users to uninstall the app, which can erase app data and launcher choices. The shared Android debug key is unsuitable for public distribution. Never commit private keys or passwords.

For a local signed build set `KEYSTORE_PATH`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, and `KEY_PASSWORD` in your secure environment, then run `./gradlew :app:assembleRelease`. Supply all four or none; partial configuration fails. No signing credentials produces an unsigned local release APK, never a debug-signed stable APK.

## GitHub Actions secrets

If this is the first signed Nuvix release and no keystore exists yet, use the one-time
[first-key setup](#first-release-setup-in-github) below. It creates these values for you.

Configure these **repository secrets** in Settings → Secrets and variables → Actions, as described in [the release guide](releases.md):

| Secret | Contents |
| --- | --- |
| `KEYSTORE_BASE64` | Base64-encoded bytes of the release JKS file |
| `KEYSTORE_PASSWORD` | Keystore password |
| `KEY_ALIAS` | Alias of the release key |
| `KEY_PASSWORD` | Private-key password |

Also set the public repository **variable** `SIGNING_CERT_SHA256` to the 64-character SHA-256 fingerprint of the long-lived release certificate (hexadecimal without colons). Obtain it with `keytool -list -v -keystore nuvix-release.jks -alias nuvix`, which prompts for the password. Verify it against an independently trusted record before setup. Missing or unknown fingerprints block publication; never substitute a PR-provided fingerprint or a newly generated key. Keep this fingerprint stable: publication rejects a different certificate before uploading an unusable update.

Validation has no signing secrets and prepares an unsigned release APK. After Android and database-security validation pass, the trusted publisher verifies actor permissions and artifact digests, decodes the keystore temporarily with restrictive permissions, signs with `apksigner`, verifies the final APK, and removes the key. Signed APKs are not exposed as Actions artifacts. Only the trusted publishing job has `contents: write`.

Update the committed `versionCode`/`versionName` and changelog, merge reviewed changes into `main`, and manually dispatch Release with a matching version and `rc` or `stable` channel, or push the matching `v<version>` tag at current main. Do not precreate tags for a manual dispatch. RCs use the production package and signer, so stable needs a higher code than every candidate. See [releases](releases.md) for the trusted-actor allowlist, repository signing configuration, failure recovery and the v1.0 checklist. The direct release path requires no production environment or second-reviewer approval.

## First release setup in GitHub

Use this only before Nuvix's first signed distribution. Existing signing credentials,
partial credentials, a previous setup reservation, or any GitHub release stop generation.
This workflow does not publish an APK and never replaces an existing signing identity.

1. Create a **new fine-grained PAT**, restricted to **DisabledAbel/Nuvix**, with repository
   **Secrets: Read and write**, **Variables: Read and write**, **Actions: Read**, and
   **Contents: Read** permissions. Set a short expiration. Add it directly to GitHub under
   **Settings → Secrets and variables → Actions → New repository secret**, named
   `SIGNING_SETUP_TOKEN`. Revoke any token previously pasted into a chat; do not reuse it
   or paste its replacement into chat, workflow inputs, issues or files in the repository.
2. Generate a backup password with your password manager: **at least 24 characters**.
   Save it privately outside GitHub first, then add it as another repository secret named
   `SIGNING_BACKUP_PASSWORD`. You need this password to open the encrypted backup.
3. Open **Actions → Setup Production Signing → Run workflow**, select **main**, and run
   it once. It checks your trusted-publisher identity/current main, reserves the first
   signing identity, creates an RSA-3072 key with roughly 30 years of certificate validity,
   and generates random keystore/key passwords and alias `nuvix-release` automatically.
4. The workflow encrypts a ZIP using AES-256 and your saved backup password, restores that
   backup and verifies a signature using the recovered key. It uploads **only the encrypted
   ZIP**, then verifies that the backup artifact exists before installing the four signing
   repository secrets and public `SIGNING_CERT_SHA256` variable. None of the private values
   appear in logs or the public run summary. Only the public fingerprint appears there.
5. Download the run's **nuvix-first-signing-backup-<run ID>** artifact **within 7 days**.
   GitHub wraps the encrypted `nuvix-signing-backup.zip` in an outer artifact ZIP. The inner
   ZIP requires an AES-compatible ZIP application and the backup password you saved.
   It contains `nuvix-release.jks`, `credentials.json` (alias, passwords, exact GitHub secret
   values and fingerprint), and `certificate.pem` (the public certificate). Save a durable
   private copy and verify you can open it; keep the password separately. Do not commit it.
6. After successfully saving the backup, **revoke the setup PAT** and remove
   `SIGNING_SETUP_TOKEN` from repository secrets. Keep the backup password in your password
   manager; the release workflow does not need either setup secret. Subsequent releases use
   the installed signing credentials and fingerprint without running setup again.

Setup serializes with releases, rejects reruns and records `SIGNING_SETUP_RUN_ID` before
generating a key. Leave that reservation in place as an audit record. On failure, inspect
the run and its backup artifact; do not delete signing secrets or clear the reservation
merely to make another generation attempt pass. If credentials were partly installed,
recover the same key from the encrypted backup and enter its values in GitHub Settings.
Private runner files are removed whether setup succeeds or fails.

The setup PAT requires additional permissions because `GITHUB_TOKEN` cannot install
repository signing secrets. It is used only in the three setup-command steps; the normal
workflow token remains read-only. No token or private key needs to be sent through chat.

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
Never ask users to uninstall just to bypass a signature error. No signing keys or certificates
are generated by the identity tests or migration workflow.
