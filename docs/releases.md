# Nuvix releases

Nuvix uses the same direct trusted-publisher model as
[TunnelGuard](https://github.com/DisabledAbel/TunnelGuard/blob/main/.github/workflows/release.yml).
Run Release manually on `main`, or push a `vX.Y.Z` / `vX.Y.Z-rc.N` tag at the current
`main` commit. After validation succeeds, the publisher signs and publishes directly.
There is no `production` environment, second-reviewer requirement, administrator-bypass
check, environment-policy API call or signing-backup confirmation gate in this release path.
Merges, ordinary branch pushes, schedules and successful PR CI do not publish releases.

The [v1 tracker](V1-RELEASE-CHECKLIST.md), [beta guide](BETA-TESTING.md) and
[feedback triage](BETA-FEEDBACK.md) record product acceptance separately from release automation.
Changing the workflow does not establish that a signing identity or device-test evidence exists.

## Repository signing configuration

For the first signed release, follow [one-password setup](signing.md#first-release-setup-in-github):
add only the repository secret `SIGNING_BACKUP_PASSWORD` (a privately saved strong password
of at least 24 characters), then run **Setup Production Signing** on **main** once.
The built-in workflow token saves a verified AES-256 encrypted key bundle in the permanent
`nuvix-signing-key` draft release. Normal releases load that same key automatically.
No PAT, manual keystore upload, signing alias or fingerprint entry is needed.
Download an offline encrypted backup and keep the password separately; retain the draft.

If you already have a complete manual configuration, it remains supported: repository
secrets `KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`, plus repository
variable `SIGNING_CERT_SHA256` containing the independently trusted 64-character certificate
SHA-256 without colons. Existing or partial signing credentials stop first-key setup.
Never generate a replacement key to make an update pass. Preserve the existing identity.

The repository **variable** `TRUSTED_RELEASE_ACTORS` optionally lists comma-separated GitHub
usernames allowed to publish. If unset or empty, the workflow defaults to `DisabledAbel`.
Both original and triggering actors must be allowed, and the triggering actor must retain
write, maintain or admin permission. Setup and releases serialize and reject reruns.
Restrict write access and review workflow/signing changes: these controls and the signing
identity authorize publication directly.

### Safari Settings checklist and independent fingerprint

In Safari, open **Settings → Secrets and variables → Actions → New repository secret**,
name it `SIGNING_BACKUP_PASSWORD`, and enter the strong password you saved privately.
Then run **Actions → Setup Production Signing** on **main**. The setup summary shows the
public fingerprint and a backup link. No environment approval or admin PAT is required.
Existing manual signing configurations use the legacy names above instead.

## Prepare and request a candidate or stable release

1. Commit `versionName` and an increasing `versionCode` in `app/build.gradle.kts`, plus a
   matching `CHANGELOG.md` section. Stable versions use `X.Y.Z`; candidates use `X.Y.Z-rc.N`,
   where N starts at 1. Digits are ASCII and numeric components have no leading zeros.
   Every distributed compatible APK needs a new code; stable must exceed all candidates.
   Nuvix retains committed Android metadata instead of deriving a new code from the version.
2. Merge the version and notes into `main` and pass CI.
3. For a manual release, open **Actions → Release → Run workflow**, select **main**, enter
   the committed version (an optional leading `v` is accepted), select **rc** or **stable**,
   and optionally enter any **Release title** (`release_name`). Blank titles become
   `Nuvix <version>`. Do not precreate a tag for this path.
4. Alternatively push `v<version>` at the current `main` commit. Lightweight and annotated
   tags are supported. The version and stable/RC channel come from the tag. Tag pushes use
   the default title, and a tag must still identify the same commit throughout the run.
5. Both the Android validation job and isolated database-security job must pass. Validation
   runs Python/branding tests, identity checks, Gradle configuration, Android unit tests,
   debug/release lint and builds, APK inspection and the Sports Hub benchmark. It has no
   signing credentials and prepares only unsigned artifacts bound to the commit and run.
6. The publisher automatically rechecks actor access, current main, version history and
   artifact digests; signs with the existing key; verifies the APK package, version, signature
   and certificate; uploads a complete draft; verifies remote asset checksums; then publishes.
   No separate person or deployment approval is required. Only this job has `contents: write`.
7. RCs are prereleases and never Latest. Stable releases become Latest. Verify the release
   commit, assets, checksums and signature before announcing availability.

One concurrency group serializes RC and stable releases. Main must still match the exact
checked-out commit at publication; if main moves, make a fresh release request for current main.
The workflow uses the built-in token with `contents`/`actions` permissions; no PAT is required.
Only the publisher receives the saved backup password and loads the permanent encrypted key.
Passwords reach the signing command through environment variables, not command-line values;
private loaded credentials and the temporary keystore are always removed.

## CI identity protection

`scripts/release-identity.py` validates the permanent package, unsigned validation build,
startup class, provider authority, workflow inventory, trusted-actor configuration and complete
verification sequence. Signing secrets and write permissions remain absent from validation/PR
jobs. The publisher requires both validation jobs, exact checked-out commit, prepared-artifact
digests and an independent signing fingerprint. Tests reject skipped verification, alternative
publishers, untrusted events, mutable tag identity, invalid versions and mismatched certificates.

## Application ID migration

The permanent v1 production identity is **`com.nuvix.player`**. The former installed ID
`com.disabledabel.nuvix` is a different Android app even when signed by the same certificate.
Android cannot update it in place to the new ID. Namespace `com.disabledabel.nuvix` remains
for generated R/BuildConfig classes and Kotlin sources remain `app.opentv`; no broad rename
or database schema change is necessary. FileProvider authorities resolve from `${applicationId}`;
application, activity, HOME alias, recording and installer classes remain fully qualified.
Authentication uses an email code, without a package-bound OAuth/deep-link callback.

There is no general settings-file export/import or private-database transfer. Existing supported
Supabase metadata sync, LAN pairing and NAS sync retain their limited scopes. LAN/NAS bundles
may contain credential-bearing URLs, so do not publish or attach them as migration exports.
No automatic private-data copying, token/Keystore export or old-app uninstall is performed.

1. Keep the old installation and credentials privately available. Finish active recordings first.
2. If already using optional Supabase sync, run Sync Now on the old app for enabled categories.
3. Install the new package separately. Re-add authorized providers and match profile names;
   reselect storage permissions, launcher choices and recording settings. Recreate scheduled DVR
   bookings; old app alarms do not transfer and both apps could otherwise record at once.
4. To recover supported synced metadata, configure the same Supabase project and account,
   authenticate again, enable the desired categories, refresh provider catalogs, then Sync Now.
   Content matching must be unique; providers, tokens, playback tuning and local recording files
   are not cloud-restored. Verify favorites/groups/progress before making further edits.
5. Verify playback, settings, recordings and any shared storage access before deciding whether
   to remove the old app. Uninstalling can delete its private data. No seamless migration is claimed.

## v1.0.0-rc.1 version plan

| Build | versionName / eventual tag | versionCode |
| --- | --- | --- |
| Previous committed development metadata | `0.1.0` / no published tag | `21` |
| Prepared RC1 | `1.0.0-rc.1` / `v1.0.0-rc.1` | `22` |
| Reserved next candidate, if needed | `1.0.0-rc.2` / `v1.0.0-rc.2` | `23`, only if still unused |
| Eventual stable | `1.0.0` / `v1.0.0` | At least `23` after RC1 alone; at least `24` after RC2 |

The October 24, 2026 audit of latest `main` (`a6c304b`) found committed code 21,
no Git tags (`git ls-remote --tags origin`) and an empty GitHub Releases API result.
The owner confirmed in this preparation session that no private `com.nuvix.player`
APK with a code above 21 has been distributed. Code 22 therefore exceeds the known
compatible history. Legacy `com.disabledabel.nuvix` codes belong to a separate app.

Reserve a fresh code for every distributed build, including failed/reserved release attempts.
Without intervening builds, RC N can use `21 + N`; stable uses at least `22 + N`, where N
is the last distributed candidate. Always re-audit public, draft/reserved and private history
and choose **one greater than the highest used/reserved code** if it exceeds that plan.
Never freeze stable at 23/24 if more candidates consume those codes. Use the same
`com.nuvix.player` package and approved production signing identity throughout.
The existing strict published-history validator must pass again immediately before publication.

RC1 notes are authored in [CHANGELOG.md](../CHANGELOG.md#100-rc1), the existing workflow's
only notes source. `create-release.py:release_notes` extracts that exact version section and
adds installation/GPL/OpenTV notices; `release-control.py` includes it as `release-notes.md`
and the GitHub release body, bound by checksums. No second editable notes file is required.
The intended notes title is **Nuvix v1.0.0 Release Candidate 1**; the default release title
is `Nuvix 1.0.0-rc.1`. Manual input is `1.0.0-rc.1` (or `v1.0.0-rc.1`) with channel `rc`.
A manual run creates `v1.0.0-rc.1`; a tag-push run uses that existing tag.

## Compatibility and verification

RC and stable use `com.nuvix.player`, the same signing identity, and one increasing code sequence. An RC intentionally installed on a device can upgrade to stable only when stable has a higher versionCode and the same certificate. RC installation can change app data; exercise it on a test device first. Existing `.nightly` installations are a different package and cannot migrate in place. The legacy nightly Gradle variant and verification fixtures remain untouched for compatibility; there is no active nightly publishing path and the release CLI only accepts stable/RC.

`UpdateChecker` scans up to 10 pages of 30 releases (failing closed beyond the bound), ignores draft/prerelease responses and all nonstable tag formats even if an RC is accidentally marked stable, and requires the stable tag to match the manifest versionName. It offers only matching installed packages and certificate fingerprints, selecting the highest compatible versionCode. Existing allowed-URL, size, APK checksum, archive package and signer checks remain. Android installation consent is unchanged. Test stable → newer stable and RC → newer stable on representative devices; confirm no RC is offered to a stable device and that failed/partial draft publication produces no available update. Updater behavior is covered by JVM regression tests.

The release includes `nuvix.apk`, `update.json`, `SHA256SUMS`, `release-notes.md` and the corresponding `nuvix-v…-source.tar.gz` with GPL-3.0-or-later source and attribution. Checksums cover all four other assets. Notes include Android 6.0/API 23 minimum, installation guidance and the authorized-source policy. Nuvix supplies no channels, playlists, credentials or copyrighted media and cannot promise universal device/provider playback or DVR compatibility.

On a trusted system with Android build tools, independently verify a downloaded release:

```sh
sha256sum -c SHA256SUMS
```

```sh
apksigner verify --verbose --print-certs nuvix.apk
```

Compare the signer fingerprint against the previously trusted value, not just the manifest shipped beside the APK. The workflow also uses `aapt dump badging` to compare real APK metadata with Gradle output. No test publishes a release.

## Failure, rejection and recovery

Missing signing secrets/fingerprint, untrusted actors, invalid versions, test failures,
certificate mismatches, changed main/tags, tampered artifacts, reused codes, duplicate releases
and API errors stop publication. Failures report HTTP status without dumping API bodies or tokens.
There is no environment-approval stage to repair. Reruns are rejected; start a fresh request.

For a manual request, a pre-existing tag is rejected. For a tag-push request, the matching
existing tag is verified and retained. Neither path overwrites or deletes a release or tag.
A failure after tag reservation or draft creation leaves the version reserved for inspection.
Inspect the tag/draft and logs, then roll forward with a new version and higher versionCode;
do not manually publish a partial draft or use upload clobbering. If the final response is
lost, inspect GitHub before deciding whether publication failed. Cancellation cannot undo
an accepted API request, but asset verification precedes the public-release transition.

## v1.0 readiness checklist

Use [V1-RELEASE-CHECKLIST.md](V1-RELEASE-CHECKLIST.md) for feature scope, existing signing
identity and backup, CI, version history, device acceptance, blocker triage and licenses.
Unchecked physical-device acceptance is not filled in by workflow tests. This implementation
change does not dispatch a release, create a version tag, sign an APK or announce availability.
