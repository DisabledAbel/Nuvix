# Release signing and update continuity

Every stable Nuvix update must retain the release application ID `com.disabledabel.nuvix` and the same private signing key. Increment Android `versionCode` for every distributed update; the first Nuvix release uses 21 (after OpenTV's source baseline of 20), versionName `0.1.0`. The `vX.Y.Z` Git tag must match `versionName` exactly. The updater uses `versionCode` to prevent downgrades.

## Signing key

Create a dedicated, long-lived key on a trusted machine, using password prompts rather than putting passwords into shell history:

```sh
keytool -genkeypair -v -keystore nuvix-release.jks -alias nuvix -keyalg RSA -keysize 4096 -validity 10000
```

Back up the key and passwords securely in multiple protected locations. Losing the private key prevents ordinary in-place updates of existing installations. A different key generally requires users to uninstall the app, which can erase app data and launcher choices. The shared Android debug key is unsuitable for public distribution. Never commit private keys or passwords.

For a local signed build set `KEYSTORE_PATH`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, and `KEY_PASSWORD` in your secure environment, then run `./gradlew :app:assembleRelease`. Supply all four or none; partial configuration fails. No signing credentials produces an unsigned local release APK, never a debug-signed stable APK.

## GitHub Actions secrets

Configure these repository Actions secrets before pushing a release tag:

| Secret | Contents |
| --- | --- |
| `KEYSTORE_BASE64` | Base64-encoded bytes of the release JKS file |
| `KEYSTORE_PASSWORD` | Keystore password |
| `KEY_ALIAS` | Alias of the release key |
| `KEY_PASSWORD` | Private-key password |

Also set the public repository Actions **variable** `SIGNING_CERT_SHA256` to the 64-character SHA-256 fingerprint of the long-lived release certificate (hexadecimal without colons). Obtain it with `keytool -list -v -keystore nuvix-release.jks -alias nuvix`, which prompts for the password. Keep this fingerprint stable: publication rejects a different certificate before uploading an unusable update.

The workflow checks every secret and the configured certificate fingerprint, decodes the keystore into the runner's temporary directory with restrictive permissions, and deletes it after building. It does not print passwords or key material. The build job has read-only repository permission; only the publication job receives `contents: write`.

Update `versionCode`/`versionName` in `app/build.gradle.kts` and the changelog. Commit the complete corresponding source, then push `v0.1.0` (or a future matching version). Manual release dispatch requires an existing matching version tag and checks out that tag. Do not reuse a distributed versionCode or replace a public release with a differently signed build.

## Release verification and assets

`scripts/create-release.py` reads Gradle's APK metadata and verifies the actual APK using Android `aapt` and `apksigner`. It rejects unsigned APKs, debuggable APKs, the Android debug certificate, a wrong package ID, an unexpected version, ambiguous APK outputs, a tag mismatch, or a certificate that differs from the configured long-lived key. It creates:

- `nuvix.apk`: verified universal release APK.
- `update.json`: schema 1 metadata containing `versionCode`, `versionName`, `applicationId`, `apkAsset`, APK `sha256`, `sizeBytes`, and `signingCertificateSha256`.
- `SHA256SUMS`: APK integrity checksum.
- Release notes extracted from the matching changelog section.

The workflow attaches a `nuvix-vX.Y.Z-source.tar.gz` archive from the exact tagged commit, including the GPL license, to meet corresponding-source obligations. GitHub Releases also exposes that commit's source archives. Preserve third-party copyright notices and licenses when distributing binaries.

The application must be configured to trust your independently maintained GitHub repository before checking releases. Configure the same stable release source on all devices. Android still requires user approval to install an APK and may require granting Nuvix permission to install unknown apps. This workflow cannot enable silent installation on consumer devices. Keep your repository and signing secrets protected: a malicious release signed with the legitimate key can pass certificate checks.
