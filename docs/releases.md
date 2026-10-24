# Manual Nuvix releases and v1.0 checklist

The canonical [v1 release readiness tracker](V1-RELEASE-CHECKLIST.md) records blockers and
acceptance evidence; this document remains the signing/approval/publication runbook.
See [beta instructions](BETA-TESTING.md), [feedback triage](BETA-FEEDBACK.md) and the
[Discord preparation draft](BETA-DISCORD-ANNOUNCEMENT.md). The first planned candidate is
`v1.0.0-rc.1`, not yet available. This preparation leaves versions and workflows unchanged
and must not dispatch signing setup or Release. Complete the blockers before any future request.

## Current status and prerequisites

Stable releases must never publish on a merge, tag push, schedule, or successful CI run. The Release workflow accepts only a manual dispatch on `main`, with a committed `versionName` and an explicit `rc` or `stable` channel. No nightly workflow is present or introduced.

**Production signing setup is blocked; publication remains disabled.** The one-time setup change is preparatory infrastructure only and must remain a draft until its security blockers are resolved. `Setup Production Signing` cannot generate a keystore, configure secrets, upload artifacts, or publish. The Release publisher additionally requires the certificate-bound recovery gate described below; that gate deliberately fails even with a matching confirmation variable while recovery is unimplemented. Do not remove the gate to make a release pass.

Inspection on October 23, 2026 confirmed the repository is **public**, `main` is protected and there are no GitHub Releases. The current connected GitHub API rejects environment administration reads; an authenticated CLI read also returned Forbidden. **The current production environment configuration and secret inventory could not be verified or changed.** PR #46 records an earlier browser inspection: only the owner was a reviewer, Prevent self-review was off, admin bypass was off, deployment branches were unrestricted and signing secrets/variables were absent. Those are historical findings, not proof of today's settings. An administrator must verify them in Safari before any future signing setup.

The current `CI` ruleset was inspected: zero required PR approvals, no required Code Owner review, no stale-review dismissal or latest-push approval, administrator bypass enabled, and only `Compile, test and lint` required. These do not meet signing setup requirements. This change does not alter GitHub security settings. No setup, signing or publication workflow was dispatched during implementation.

References: [GitHub environment protection](https://docs.github.com/en/actions/how-tos/deploy/configure-and-manage-deployments/manage-environments), [deployment review](https://docs.github.com/en/actions/how-tos/deploy/configure-and-manage-deployments/review-deployments), [review-history API](https://docs.github.com/en/rest/actions/workflow-runs#get-the-review-history-for-a-workflow-run).

## One-time signing setup from an iPad — blocked preparation

No iSH, local Java, Android Studio, terminal or second computer is required to use the eventual setup. **There is currently no approved iPad backup/recovery implementation. Stop before generating a production key.** A browser download of a keystore, an obscure artifact URL, a password shown in a log, or an encryption password generated only in an ephemeral runner does not meet the recovery requirement. A new workflow cannot retrieve secret values later from GitHub Settings: secrets are write-only.

### What the preparatory workflow does

After a future reviewed merge makes the workflow available on `main`, open [Actions → Setup Production Signing](https://github.com/DisabledAbel/Nuvix/actions/workflows/setup-production-signing.yml) in Safari, choose **Run workflow**, and select **main**. Do not dispatch this workflow as part of PR validation. It has no inputs and no generation mode. It checks the exact dispatch SHA against current protected main, the workflow identity, current actor permissions, independent PR review rules and required CI checks, absence of ruleset bypass, production protection and a different authorized human reviewer. It checks complete, paginated signing-secret/variable **names** and existing release history before refusing an existing or partial identity. It then stops at the recovery blocker. API denial, incomplete inventory, a rerun, a moved main or an unexpected workflow/ref is a failure, never permission to proceed.

The workflow has only `contents: read` and `actions: read`, uses a pinned checkout without persisted credentials, serializes all setup attempts, receives no signing secrets and has no upload or publishing steps. A branch's workflow can always be maliciously rewritten by someone able to bypass review; enforcement therefore also requires the real GitHub ruleset and independent Code Owner review. Repository contract tests cannot replace that protection.

### Required backup and restore design before implementation

No encryption product or recovery service has been selected, integrated or tested for this owner. Consequently **there is no production backup to download, decrypt or restore in this draft**. Do not generate a production keystore to experiment with a recovery app. A separately reviewed implementation must first demonstrate all of the following with a disposable key on the owner's iPad:

1. Select a supported encryption format and maintained iPad application with a documented recovery process. Establish and durably preserve the owner's recovery credential **before** the signing runner starts, independently of GitHub and the ephemeral runner. Keep an additional protected recovery copy; losing GitHub access must not also lose the only decryption credential.
2. Demonstrate authenticated encryption and decryption of a complete recovery bundle: keystore, store/key passwords, alias, public certificate, certificate SHA-256, format/version and integrity manifest. Password protection of a JKS alone is not the backup encryption mechanism. The runner may receive only the material needed to encrypt, never the owner's private decryption credential.
3. Demonstrate Safari/iPad delivery and durable storage of the encrypted bundle, expiration handling, and a restore drill that recovers the same certificate and a functioning signing key. Treat Actions artifacts in this public repository as public/untrusted ciphertext distribution, never as private storage or a durable backup. Do not upload plaintext key/password files, even temporarily; no recovery information belongs in an issue, PR, chat, log or public link.
4. Demonstrate a supported browser-only way to restore the same key and securely populate protected GitHub environment secrets. The owner must not need a terminal, ad-hoc decryption website or local Base64 command. A reviewed secret-entry UI or supported app export must handle this; no such integration exists here yet. Securely preserve the actual keystore/password bundle outside GitHub; saving only an encrypted archive without working recovery credentials is insufficient.
5. Independently compare the restored public certificate fingerprint with the original generation record, test a disposable signature, and check the certificate's validity dates. Record the public fingerprint separately from the backup. Have a different authorized human verify the drill and preserve its non-secret evidence. Only then review/implement real generation (recommended alias `nuvix-release`, RSA at least 3072 bits, long validity, cryptographically random credentials, noninteractive supported Java/Android tools). No production generation command is included in this draft.

If any step cannot be performed on the iPad with supported tools, leave setup and publication blocked. Do not create an improvised recovery service or generate a replacement key. Absence of a GitHub release or secret does not prove a compatible APK was never distributed privately; confirm that separately before first-key authorization.

### Safari Settings checklist and independent fingerprint

1. In [Settings → Rules → Rulesets](https://github.com/DisabledAbel/Nuvix/settings/rules), require independent PR approval and Code Owner review on main, dismiss stale approvals, require latest-push approval, disallow bypass, and require **both** `Compile, test and lint` and `Supabase migrations and security`. Select an independent trusted collaborator as a Code Owner when the owner authors policy changes.
2. In [Settings → Environments → production](https://github.com/DisabledAbel/Nuvix/settings/environments), configure the reviewer, no-self-review, no-admin-bypass and main-only deployment rules below. A second authorized human is mandatory; if unavailable, leave publication disabled. Never turn off protection for setup or restore.
3. After a reviewed recovery integration exists and its drill succeeds, use **production → Environment secrets → Add secret** for `KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS` and `KEY_PASSWORD`. Receive values only through the approved encrypted recovery/app flow. If the browser-only export/entry path is unavailable, stop. Do not use a PR, issue, Actions input, log or chat to transfer values. Any partial signing configuration reserves the identity and blocks new setup.
4. Use **production → Environment variables → Add variable** for `SIGNING_CERT_SHA256` (64 hex characters, no colons). The existing release pipeline intentionally reads this **variable**, not a same-named secret. It is the single independent trust baseline; do not introduce another trusted fingerprint in the repository or silently bootstrap it from an arbitrary APK. For an existing app, compare against the independently trusted original certificate. For a truly first identity, record the public certificate at generation and independently recompute the fingerprint from the recovered certificate; never approve only a workflow's self-reported fingerprint.
5. Only after the owner has durably saved the encrypted backup **and** recovery information, and another authorized person has verified recovery and certificate agreement, set production variable `SIGNING_KEY_BACKUP_CONFIRMED_SHA256` to that same fingerprint. This records recovery confirmation, not a new identity. It cannot enable publication in this preparatory implementation: the code-level recovery blocker must also be resolved through an independently reviewed PR with evidence of the supported iPad restore flow.

An ordinary `GITHUB_TOKEN` cannot write environment secrets. Read-only prerequisite checks may also be denied secret-name inventory or environment administration APIs; failure is a blocker, not an empty inventory. Automatic installation would require a separately authorized GitHub App installation token or fine-grained credential with the documented environment-secret write permission and appropriate variable access, plus GitHub's encrypted-secret API. This draft requests no such token, grants no additional write permissions and implements no secret-writing endpoint. The connected integration's repository admin metadata does not prove those sensitive API capabilities. The minimal supported fallback is Safari Settings **after** a reviewed browser-only recovery/export path exists. Do not paste a PAT or private encryption key into chat.

### Readiness and failed setup recovery

At present the correct result is **blocked**, even if all GitHub settings have been repaired. The setup workflow summary explains the blocker without showing inventory values, credentials or tool output. Do not rerun a production setup to obtain a fresh identity. In this draft no key can have been generated; repair only the reported prerequisites and review the backup implementation separately.

Before any future production publication, require complete protected credentials, the independent fingerprint, matching recovery confirmation, a successful recovery drill, current environment protection and actual independent approval. The existing Release workflow remains the only publication route. Its APK/application ID, non-debug release build, versionCode history, verified signature, certificate continuity, prepared-artifact and release-asset checksum checks remain mandatory. Explicit certificate-validity checking and the recoverable first-key lifecycle still require the future implementation; they are not claimed complete here.

If a future generation attempt stops after creating a key, preserve its encrypted bundle and non-secret identity record and treat the identity as reserved. Never delete partial secrets/fingerprints as a way to pass the duplicate check. Recover and install the **same** identity through the reviewed restore flow, reverify its fingerprint and obtain new human approval. If the bundle or recovery credential is missing, stop publication and investigate; silently regenerating a signing key can permanently break Android updates. No signing setup or recovery action may publish an APK or release.

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

At inspection there were **no GitHub Releases**. There is no public production certificate to
bootstrap from. If a production key already exists (including for private distribution), an
administrator must verify its fingerprint against an independently trusted record; never generate
a replacement or trust a PR-provided fingerprint. If no key has ever existed, follow the blocked
first-identity prerequisites above. Publication remains blocked until identity and recoverability
are established independently. This draft does not provision secrets or change the trust baseline.

## CI identity protection

`scripts/release-identity.py` runs in ordinary PR CI without private keys. It checks the permanent
ID, unsigned-release signing configuration, provider authority, startup class, required scripts,
and the allowlisted workflow inventory. It rejects added release workflows, write permissions
outside the gated publisher, signing secrets in validation jobs, scheduled publishing and removed
approval/verification stages. Policy edits themselves require protected Code Owner review;
repository tests cannot defend against an administrator deliberately rewriting tests and policy.

PR CI also builds the unsigned release, compares Gradle output with committed versions, reads
actual APK badging with `aapt`, rejects debuggable or signed validation outputs using `apksigner`,
and compares the inspected versionCode against published production stable/RC metadata. API
errors and malformed or unknown-package history fail closed. Legacy `com.disabledabel.nuvix`
releases are validated but do not constrain the new package's code sequence; tags remain globally
unique. Tags must match metadata versions and progress RC → stable or forward. Approved
production checks also compare historical production certificates to the protected fingerprint.
The signed APK verifier and checksum/approval transaction from PR #45 remain mandatory.

## Configure production before enabling publication

1. Open [Settings → Environments](https://github.com/DisabledAbel/Nuvix/settings/environments) and create `production`.
2. Enable **Required reviewers**. Select explicit trusted **user accounts** with repository write, maintain, or admin access. This personal repository uses user reviewers, not organization teams. An approval from a bot or a user without current write access is rejected by the publishing script.
3. Enable **Prevent self-review**. A different person must approve a run. If you are the only maintainer, add a trusted collaborator/release approver before attempting a release; selecting yourself as the only reviewer cannot approve your own dispatch.
4. Disable **Allow administrators to bypass configured protection rules**. Save the rules.
5. Under deployment branches and tags select **Selected branches and tags**. Add exactly one **Branch** rule named `main`. Do not add tag rules or wildcard branches. The workflow uses the immutable dispatch commit from `main`; it does not dispatch from tags.
6. Move the existing signing credentials into **production environment secrets** using the names below. Remove duplicate repository/organization signing secrets after verifying the environment copy, so ordinary validation or another workflow cannot use those credentials. Preserve the existing key; never create a replacement for an installed production app.
7. Configure `SIGNING_CERT_SHA256` as a production environment variable, with the independently recorded existing certificate fingerprint (64 hex characters, no colons). Do not copy a fingerprint from an untrusted build as a new trust root.
8. Retain the repository CI ruleset and review changes to workflows/signing scripts before merging. At inspection, ruleset `CI` required `Compile, test and lint` on the default branch, allowed admin bypass, and required zero PR approvals. This PR does not modify those settings. Require pull requests, at least one independent approval, Code Owner review, dismissal of stale approvals, approval of the latest push, and restrict bypass. Add a trusted collaborator as a code owner if the owner authors these changes (authors cannot approve their own PRs). Require both `Compile, test and lint` and `Supabase migrations and security`. Restrict version-tag modification and who can administer environments and signing credentials. `.github/CODEOWNERS` requests review for release policy, signing/updater code, Gradle and these docs; CODEOWNERS alone does not enforce any approval.

| Production secret | Value |
| --- | --- |
| `KEYSTORE_BASE64` | Base64 bytes of the existing long-lived release JKS |
| `KEYSTORE_PASSWORD` | Existing JKS password |
| `KEY_ALIAS` | Existing signing-key alias |
| `KEY_PASSWORD` | Existing private-key password |

No PAT is needed in the workflow. Validation uses `contents: read` and `actions: read`; only the approved publishing job uses `contents: write` with `actions: read` for run/approval checks. GitHub CLI, Python, Java and Android tools run on GitHub-hosted Ubuntu runners. No local computer is needed to initiate or approve releases.

In Safari, inspect and save the non-secret protection settings directly in **Settings → Environments → production**: at least one independent required user reviewer, **Prevent self-review** enabled, administrator bypass disabled, and **Selected branches and tags** containing exactly the **Branch / main** rule. The Release workflow checks those same settings through GitHub's environment and deployment-branch-policy APIs. A denied endpoint is a blocker, never a reason to remove a check. No terminal is needed. Signing-secret values are deliberately never queried or logged.

If plan/visibility changes make required reviewers unavailable, or approval/API access cannot be verified, **leave stable publication disabled**. This implementation provides no weaker fallback. Restore supported reviewer protection before enabling the workflow. A future alternative must use a separately authorized publishing action bound to the exact run, commit and artifacts and receive its own review; a confirmation string in the initial dispatch is insufficient.

## Prepare and request a candidate or stable release

1. Commit the intended version in `app/build.gradle.kts`, add the matching `CHANGELOG.md` section and pass the PR's CI. The release workflow never overrides Gradle metadata. The implementation deliberately leaves `0.1.0`/21 unchanged; v1.0 preparation needs a separate reviewed version bump.
2. RC examples: `versionName = "1.0.0-rc.1"`, then `1.0.0-rc.2`. Stable: `versionName = "1.0.0"`. Choose a new, increasing `versionCode` for **each distributed build**, exceeding all earlier stable and RC codes and any manually distributed compatible APKs. For example, codes 22, 23, 24 may be used only if none have already been distributed. Stable must exceed its candidates. Android's allowed integer range is checked.
3. Merge the reviewed version commit into `main`. Do **not** precreate its tag: existing tags and releases (including drafts) are rejected.
4. Open [Actions → Release](https://github.com/DisabledAbel/Nuvix/actions/workflows/release.yml) → **Run workflow**, select `main`, enter the version **without `v`**, and select `rc` or `stable`. Inputs accept only exact ASCII numeric `X.Y.Z` or `X.Y.Z-rc.N`; no leading zeros, suffixes, branch inputs or shell fragments. Release notes come from the committed changelog; there is no arbitrary notes-source input.
5. Validation runs Python/branding tests, Gradle configuration checks, Android unit tests, debug and release lint, debug and unsigned release builds, the Sports Hub benchmark, and isolated Supabase migration/security tests. Nothing is signed or publicly released yet. A bounded unsigned artifact is stored for seven days, along with validation reports.
6. A different configured maintainer opens the run, inspects validation and the prepared tag/versionCode/commit summary, chooses **Review deployments**, selects `production`, and approves. Reject if anything is wrong. Both RC and stable signing/publishing require this gate.
7. After approval the publishing job verifies the recorded human approval and current reviewer permissions, rechecks production protection, the exact current `main` SHA, requested version, history and artifact hashes. It signs the prepared unsigned release APK with the existing JKS, then checks the actual APK package, version, non-debug status, verified signature and expected certificate using build-tools 36.0.0. The keystore is temporary, private, never uploaded and removed even on failure. Signed APKs are never uploaded as Actions artifacts.
8. A single global concurrency group serializes RC and stable publication. Immediately before creating anything the workflow rechecks tag/release collisions. It reserves a lightweight `v…` tag at the exact validated commit, creates a draft **after approval**, uploads all assets and verifies GitHub's asset sizes and SHA-256 digests. Only after final cancellation, branch/tag, protection and approval checks does it make the complete draft public. RCs use `prerelease: true`, `make_latest: false`; stable uses `prerelease: false` and becomes latest.
9. Inspect the workflow summary, published commit/tag, notes, assets and checksums. A failed API request is never interpreted as an absent release unless it is an actual 404.

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

Missing secrets, unsupported/unprotected environments, missing reviewer approval, invalid versions, test failures, incorrect certificates, missing or tampered artifacts, reused versionCodes, duplicate tags/releases and API/permission failures stop publication. Main moving while approval is pending requires a fresh dispatch and approval. Approval rejection and cancellation do not authorize a later job. Reruns are deliberately rejected (`GITHUB_RUN_ATTEMPT` must be 1).

Before tag reservation, fix the cause and dispatch a new run. After tag reservation or draft creation, a failure leaves that version reserved for inspection: there is no automatic cleanup, overwrite, delete, upload clobber or retry. Inspect the tag/draft and run logs; do not manually publish an unverified partial draft. Roll forward with a new reviewed version/tag and higher versionCode. Preserve the failed draft/tag as evidence unless a maintainer makes an independently reviewed recovery decision. If the final API response is lost, inspect GitHub to determine whether the complete release became public before taking any further action. Cancellation cannot undo an API call GitHub has already accepted, but assets are complete before the final publication call.

Repository administrators and holders of release-capable tokens can change policy or publish outside Actions. Environment rules cannot eliminate that trust boundary. Keep them and the signing key restricted, and remove obsolete release-capable automations/tokens. No other checked-in workflow writes Releases or accesses signing credentials; Pages and PR Build retain their existing behavior.

## v1.0 readiness checklist

Use [V1-RELEASE-CHECKLIST.md](V1-RELEASE-CHECKLIST.md) as the single editable tracker for
feature freeze, signing/recovery/approval, CI, version identity, beta hardware acceptance,
blocker triage, licenses and release notes. Keep unverified rows incomplete. Future publication
and post-publication verification are separate work; this beta preparation does not merge,
create version tags or publish an APK, release candidate or v1.0.0.
