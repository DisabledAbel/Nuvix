# Nuvix updates and troubleshooting

Nuvix checks only **stable GitHub releases from `DisabledAbel/Nuvix`**. Drafts, prereleases, nightly tags and RC tags are ignored. Discovery scans at most 300 releases in pages of 30, selects the highest compatible versionCode, and fails closed if history exceeds that bound. The installed package and certificate must match before an update is advertised. Android's numeric `versionCode` determines whether a release is newer; a tag or a larger display version name cannot cause a downgrade.

Open **Settings → Updates** or the update controls in About for a manual check. Automatic checks default on and run approximately every six hours when Android schedules the WorkManager job. They do not open dialogs, interrupt playback, or change the player. Update networking uses its own HTTP dispatcher and connection pool. Automatic download defaults off; enabling it requires an unmetered network and a battery that Android considers sufficient. A download can still share device bandwidth with playback.

Update notifications have their own setting and require Android's notification permission/channel to be enabled. Android 13 and later may require the explicit **Enable Android notifications** action. A denied permission does not stop manual checks. Nuvix never installs an update automatically: installation begins only after the user requests it, and Android asks for confirmation.

## Release contract

Every stable release must have exactly one `nuvix.apk` and one `update.json` asset, with HTTPS download URLs belonging to that release in the configured repository. The build/release workflow emits metadata from the actual signed APK:

```json
{
  "schemaVersion": 1,
  "versionCode": 21,
  "versionName": "0.1.0",
  "applicationId": "com.nuvix.player",
  "apkAsset": "nuvix.apk",
  "sha256": "64 hexadecimal characters from the signed APK",
  "sizeBytes": 12345678,
  "signingCertificateSha256": "64 hexadecimal characters from its signing certificate"
}
```

Nuvix verifies metadata schema, APK length and SHA-256, archive package name and version, and the APK's signing certificate. **The installed application's certificate is the trust anchor.** A certificate stated by `update.json` cannot authorize a different signer. The archive signer must match the installed signer and the metadata must agree with the archive. The same checks run again immediately before creating an installation session. A corrupt, truncated, wrong-package, unsigned, differently signed, same-version, or older APK is rejected.

The metadata is publicly downloadable release information, not a signed trust document. Repository control alone cannot replace the installed signing identity. Keep the release signing key backed up securely; losing or changing it breaks upgrades for existing installs. Deliberate certificate rotation is not supported by the strict current-signer comparison. Debug builds with the `.debug` application ID do not accept production metadata.

## Download and installation

APK downloads run on an IO coroutine, report progress, and retry network errors up to twice. Cancel closes the active response and deletes the partial file. Completed candidates live in Nuvix's private files directory and are reused only after validation; older candidates are removed after a successful download. A process death may leave a partial file, which is overwritten on retry.

Nuvix uses a `PackageInstaller` full-install session, sets the intended package, writes and syncs the validated APK, and commits to an explicit non-exported status receiver. On Android 12 and later the session explicitly requires user action. A mutable PendingIntent is used only for Android to fill in the session result. Android's pending-user-action callback opens its confirmation screen. Submitting the session is **not** installation success; completion and cancellation come from Android's callback.

On Android 8 and later, allow **Install unknown apps → Nuvix** when requested, return to Nuvix, and choose **Continue installation**. Nuvix keeps the verified download, then rechecks it before submission. Some Fire TV/Android TV vendors place this permission under Developer options or restrict third-party installation through device policy. Enable it in the device's system settings where available. Nuvix cannot bypass device policy or silently grant permissions.

## Common failures

| Symptom | What to do |
|---|---|
| Could not check for updates | Check connectivity and retry. GitHub can return rate limits or temporary errors. A failed check is reported separately from “up to date.” |
| Release validation failed / missing metadata | The maintainer must publish both documented assets with metadata generated from the signed APK. Nuvix does not fall back to an arbitrary APK. |
| APK SHA-256 or size mismatch | Retry the download. If it persists, the release assets and metadata need correction. No APK is submitted to Android. |
| Signing certificate differs | The release used a different key. Do not uninstall an existing app merely to bypass this check; contact the release maintainer. |
| App install permission needed | Enable Nuvix under Android's “Install unknown apps,” return, and choose Continue installation. |
| Android blocked installation | Check device management policy and install permissions. OEM restrictions may prevent sideloaded upgrades. |
| Not enough storage | Free device storage and retry. Android needs room for the APK and installed application. |
| Installation cancelled | The current version remains installed. Open Updates again to retry when ready. |
| No background notification | Check Nuvix's notification preference and Android notification permission/channel. Use the manual check while notifications are disabled. |
| No scheduled check in standby | Android/Fire TV may defer work for Doze, networking, battery constraints, or force-stop. Open Nuvix or check manually; periodic timing is approximate. |

Exercise the download, permission-denied/permission-granted, installer approval/cancellation, reboot/process-death, and Fire TV vendor flows on physical devices before announcing release support. JVM tests cover repository pinning, stable-only discovery, numeric versions, metadata validation, corruption, wrong package, and signer trust rules; they do not emulate a real OEM installer or prove device compatibility.

## First stable identity

Old `com.disabledabel.nuvix` testers must install `com.nuvix.player` separately. This is not
an in-place update; see [migration steps](releases.md#application-id-migration). After that,
RC → stable and stable → stable retain data when ID/certificate match and versionCode increases.
Debug and legacy nightly packages remain separate from production. Stable users receive no RC
updates. The Android installer remains the final signature/installation authority.
