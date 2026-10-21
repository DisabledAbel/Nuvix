# Manual Nuvix releases and v1.0 checklist

## Current status and prerequisites

Stable releases must never publish on a merge, tag push, schedule, or successful CI run. The Release workflow accepts only a manual dispatch on `main`, with a committed `versionName` and an explicit `rc` or `stable` channel. No nightly workflow is present or introduced.

During this implementation, GitHub reported this repository as **public**. GitHub supports required reviewers for public repositories on current plans, but the connected API did not expose environment administration: the actual `production` protection settings and secret inventory **could not be verified or changed**. Treat them as unconfigured until an administrator verifies them. Publication fails closed if the API cannot confirm every required setting. This PR must remain a draft until these prerequisites and CI are verified. No release was dispatched to test this change.

References: [GitHub environment protection](https://docs.github.com/en/actions/how-tos/deploy/configure-and-manage-deployments/manage-environments), [deployment review](https://docs.github.com/en/actions/how-tos/deploy/configure-and-manage-deployments/review-deployments), [review-history API](https://docs.github.com/en/rest/actions/workflow-runs#get-the-review-history-for-a-workflow-run).

## Configure production before enabling publication

1. Open [Settings → Environments](https://github.com/DisabledAbel/Nuvix/settings/environments) and create `production`.
2. Enable **Required reviewers**. Select explicit trusted **user accounts** with repository write, maintain, or admin access. This personal repository uses user reviewers, not organization teams. An approval from a bot or a user without current write access is rejected by the publishing script.
3. Enable **Prevent self-review**. A different person must approve a run. If you are the only maintainer, add a trusted collaborator/release approver before attempting a release; selecting yourself as the only reviewer cannot approve your own dispatch.
4. Disable **Allow administrators to bypass configured protection rules**. Save the rules.
5. Under deployment branches and tags select **Selected branches and tags**. Add exactly one **Branch** rule named `main`. Do not add tag rules or wildcard branches. The workflow uses the immutable dispatch commit from `main`; it does not dispatch from tags.
6. Move the existing signing credentials into **production environment secrets** using the names below. Remove duplicate repository/organization signing secrets after verifying the environment copy, so ordinary validation or another workflow cannot use those credentials. Preserve the existing key; never create a replacement for an installed production app.
7. Configure `SIGNING_CERT_SHA256` as a production environment variable, with the independently recorded existing certificate fingerprint (64 hex characters, no colons). Do not copy a fingerprint from an untrusted build as a new trust root.
8. Retain the repository CI ruleset and review changes to workflows/signing scripts before merging. At inspection, ruleset `CI` required `Compile, test and lint` on the default branch, allowed admin bypass, and required zero PR approvals. This PR does not modify those settings. Consider requiring human PR review, restricting version-tag modification, and limiting who can administer environments and signing credentials.

| Production secret | Value |
| --- | --- |
| `KEYSTORE_BASE64` | Base64 bytes of the existing long-lived release JKS |
| `KEYSTORE_PASSWORD` | Existing JKS password |
| `KEY_ALIAS` | Existing signing-key alias |
| `KEY_PASSWORD` | Existing private-key password |

No PAT is needed in the workflow. Validation uses `contents: read` and `actions: read`; only the approved publishing job uses `contents: write` with `actions: read` for run/approval checks. GitHub CLI, Python, Java and Android tools run on GitHub-hosted Ubuntu runners. No local computer is needed to initiate or approve releases.

An administrator can inspect the non-secret configuration through the following commands (each runs separately in a trusted authenticated GitHub CLI session). The workflow itself checks the same endpoints using its job token. A denied endpoint is a blocker, never a reason to remove a check.

```sh
gh api repos/DisabledAbel/Nuvix/environments/production
```

```sh
gh api repos/DisabledAbel/Nuvix/environments/production/deployment-branch-policies
```

Expected: a nonempty `required_reviewers` rule with `prevent_self_review: true`, `can_admins_bypass: false`, `deployment_branch_policy.custom_branch_policies: true`, and exactly the `branch`/`main` policy. Signing-secret values are deliberately never queried or logged.

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

RC and stable use `com.disabledabel.nuvix`, the same signing identity, and one increasing code sequence. An RC intentionally installed on a device can upgrade to stable only when stable has a higher versionCode and the same certificate. RC installation can change app data; exercise it on a test device first. Existing `.nightly` installations are a different package and cannot migrate in place. The legacy nightly Gradle variant and verification fixtures remain untouched for compatibility; there is no active nightly publishing path and the release CLI only accepts stable/RC.

`UpdateChecker` uses `/releases/latest`, ignores draft/prerelease responses and all nonstable tag formats even if an RC is accidentally marked stable, and requires the stable tag to match the manifest versionName. Existing versionCode, allowed-URL, size, APK checksum, package and signer checks remain. Android installation consent is unchanged. Test stable → newer stable and RC → newer stable on representative devices; confirm no RC is offered to a stable device and that failed/partial draft publication produces no available update. Updater behavior is covered by JVM regression tests.

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

- [ ] Verify `production` protections, main-only deployment rule, another authorized human reviewer, no admin bypass and API visibility.
- [ ] Securely copy existing signing secrets to `production`, remove broad copies, and independently verify the public certificate fingerprint.
- [ ] CI is green; retain Android, Python, Supabase and benchmark checks and current branch protections.
- [ ] Commit the intended RC or stable version/changelog and a fresh code greater than every distributed compatible build.
- [ ] Verify signing continuity, install/update consent and data retention on representative Android/Google TV devices.
- [ ] Confirm stable devices ignore RCs and source/license/third-party notices accompany the APK.
- [ ] Manually request the desired release and have another maintainer approve the exact commit after validation.
- [ ] Verify published assets, tag SHA, signatures, checksums, release notes and updater operation.

This implementation PR does not complete these manual steps, merge itself, create version tags or publish v1.0.0.
