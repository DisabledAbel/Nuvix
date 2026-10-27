# Release signing and update continuity

Every stable Nuvix update must retain the release application ID `com.nuvix.player` and the same private signing key. Increment Android `versionCode` for every distributed update; the prepared first candidate uses code 22, versionName `1.0.0-rc.1`. The `vX.Y.Z` Git tag must match `versionName` exactly. The updater uses `versionCode` to prevent downgrades.

## Authorized recovery after loss of the RC1 signing key

On October 26, 2026 the owner confirmed that the original signing draft and local
backup are unavailable, and authorized a new key with a documented reinstall requirement.
The original store ID is `423036468`; the only distributed predecessor is RC1 release
`423729970`, `v1.0.0-rc.1`, code `22`, certificate
`2a6571bc8e4fa5cf303faf0aa70fd436a8360ba99d4b274edeedabeb0d191928`.
The historical recovery instructions later in this document describe that former store;
they do not establish that it is still accessible.

After these changes are merged to `main`:

1. Keep the existing saved `SIGNING_BACKUP_PASSWORD`. If its value is also lost, save a
   new password privately and update that secret before setup; it will encrypt the new key.
   Do not enter passwords in chat or workflow inputs.
2. If `SIGNING_CERT_SHA256` is configured, review it against the lost RC1 certificate
   above. Remove only that obsolete pin for this authorized reset. If it differs, stop
   and investigate. Keep any existing legacy signing credentials: setup refuses them
   because they might recover the original key. No secret removal is needed otherwise.
3. Open **Actions → Setup Production Signing → Run workflow**, select `main`, and enter
   exactly `REPLACE LOST RC1 KEY; REINSTALL REQUIRED` in **lost_rc1_key_confirmation**.
   This is a fresh run, not a rerun of an old job. The normal empty-input setup still
   refuses existing releases. This exceptional path requires the original store to return
   404, no surviving draft/backup, and exactly the known RC1 release/metadata in the full
   authenticated inventory. Other API errors stop recovery. It never deletes or overwrites
   a store. Generation reserves a new draft first, so a failed or repeated run cannot
   silently create another identity. If setup fails, inspect that reserved draft.
4. From the successful setup summary, replace repository Actions variable
   `SIGNING_STORE_RELEASE_ID` (`423036468`) with the **new numeric ID**. Retain the
   new draft. Download `nuvix-signing-backup.zip` immediately and save it outside GitHub,
   with its password separately. Record the new public certificate fingerprint securely.
5. Review CI and remaining physical-TV acceptance. When releasing, use a fresh **Release**
   run on `main`, version `1.0.0`, channel `stable`, title `Nuvix v1.0.0`.
   Never rerun the failed RC2 release or overwrite the RC1 tag/assets.

This replacement breaks Android update continuity from RC1. Users must manually uninstall
RC1 and reinstall; uninstalling erases private app data. Preserve provider details privately,
finish recordings, save accessible recording files and explain that cloud sync is not a
complete backup. Keep RC1 available as historical source; do not relabel it as stable.
The new stable uses code 24. The release policy exempts only the exact known RC1 record
from certificate continuity checks; every later release must match the replacement signer.
This is not Android signing-key rotation: the old key cannot authorize a signing lineage.
Normal releases still never generate a key, and APK certificate verification remains required.

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
3. Once setup succeeds, save the public numeric signing-store release ID from its summary
   as the repository Actions **variable** `SIGNING_STORE_RELEASE_ID`. This keeps lookup stable
   if GitHub changes the draft's tag. Then run **Release** normally. Releases load and decrypt the same key
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
Except for the explicitly authorized lost-RC1 recovery above, never create another key after distributing signed APKs. Restore the same key from your
backup if necessary. Complete legacy configurations using the four repository signing
secrets and a pinned fingerprint continue to work without changing the signing identity.

The old `SIGNING_SETUP_TOKEN` is no longer used; revoke and remove it if you added one.
Setup needs only the built-in token's `contents: write` permission to save the encrypted
backup. It never calls GitHub's secret/variable-management APIs or commits private files.

## Recovering an unexpected signing-draft tag

The signing draft's numeric release ID is durable; its title, tag and asset filename are
mutable metadata. Without `SIGNING_STORE_RELEASE_ID`, lookup requires exactly one store
with tag `nuvix-signing-key`. A matching title or filename only helps diagnose an unexpected
tag or conflict; neither can authorize recovery. A configured ID never falls back to another
release. Missing, conflicting, published or incomplete stores stop the job.

For the failed Release run `40142736539`, the existing store is draft release **423036468**,
currently tagged `untagged-b19841b4df050a93d7de`, containing asset **673553812**. After the
code fix is on main, the minimal recovery action is **Settings → Secrets and variables →
Actions → Variables → New repository variable**: set `SIGNING_STORE_RELEASE_ID` to
`423036468`. Keep the saved `SIGNING_BACKUP_PASSWORD` unchanged. No draft metadata edit,
backup upload, key generation or setup rerun is needed.

The old setup checked the tag after upload, then made a final body-only PATCH without
preserving `tag_name` or checking the result. Setup succeeded and the draft's last update
coincides with that PATCH; its tag subsequently appeared as `untagged-*`. The logs do not
record the response, so they cannot establish GitHub's internal reason for the rewrite.
Setup now addresses storage by the reservation's ID, explicitly preserves the tag and draft
state when updating the body, and checks storage again before reporting success.

Loading still verifies the downloaded size and SHA-256, AES password, repository,
credential set, certificate fingerprint and keystore bytes, and any configured
`SIGNING_CERT_SHA256` pin. Actual APK signing must also verify that the key's certificate
matches that identity. The fingerprint written in a draft body is only an observed value;
do not use it as independent proof or change an existing pin to pass CI. Verify a new pin
against the actual recovered certificate and an independently trusted record. Regression
tests use disposable encrypted fixtures; they do not verify the production certificate or
end-to-end publication. Inspect existing release versions/tags before any fresh Release run.

## Signing key

Reuse the existing long-lived production keystore. Do not generate or replace it as part of release setup. If no production installation has ever been distributed and no signing identity exists, establish that identity separately and record its certificate before enabling releases. The normal Release workflow never generates a key; Setup creates the first identity or performs the narrowly guarded lost-RC1 replacement above.

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
