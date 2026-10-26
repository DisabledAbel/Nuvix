# Nuvix v1.0 release readiness

**Decision: blocked. No beta or stable release is authorized by this checklist.**
Prepared second candidate: `v1.0.0-rc.2`, versionName `1.0.0-rc.2` / versionCode `23`,
production package `com.nuvix.player`, minimum API 23. RC1/code 22 is already published.
RC2 is not published; this preparation creates no release tag, signs no production APK,
dispatches no production workflow and does not merge the preparation PR.

This is the canonical v1 tracker. Use [tester instructions](BETA-TESTING.md),
[feedback triage](BETA-FEEDBACK.md) and the existing [release runbook](releases.md).
A maintainer records candidate/build and commit, test device/OS,
date, evidence link, owner and result before checking it. CI/JVM/emulator results cannot close
physical-TV or live-provider rows. Not Tested/Not Supported results require review, not a pass.
Repeat affected checks after fixes and record explicit acceptance of any remaining limitations.

The **IPTV Playback Tests** workflow adds PR fixture/emulator regressions and optional
manual Apple/Mux transport checks. Attach its sanitized JSON/Markdown results to playback
acceptance evidence; see [automated testing](TESTING.md#automated-iptv-playback-tests).
Rendered synthetic HLS/TS on an emulator, controlled Multi-View tests and generated-media DVR
coverage do not close the physical-TV/authorized-provider blocker below. No arbitrary public
stream may be used for recording tests. Existing signing/release/security gates remain required.

## Current blockers and evidence

| Blocker | Current evidence / action required |
| --- | --- |
| D-pad live channel switching (v1 blocker) | RC1 report: new channel label with previous video for about a minute, including same-provider channels. Close-before-resolution, cancellable portal tuning, latest-selection navigation and surface rebinding have automated regression coverage. **Keep blocked until a representative Android/Google TV and authorized constrained provider pass** the [channel-switch acceptance procedure](TESTING.md#live-channel-switching-v1-blocker). Record first-frame timings and provider format/capacity; virtual-time results do not certify hardware. |
| Production signing continuity | RC1 is published and the permanent encrypted backup draft exists. Retain the existing key/password and backup; do not rerun first-key setup. RC2 has no production-signed APK in this preparation, so actual RC1-to-RC2 certificate continuity remains unverified. See [compatibility evidence](releases.md#rc1-to-rc2-compatibility-evidence). |
| Production identity and publisher | Repository signing secrets, independently trusted certificate fingerprint and trusted-actor configuration must be verified using [Safari setup](releases.md#safari-settings-checklist-and-independent-fingerprint). No production environment or second reviewer is required. |
| Repository access protection | Review workflow/signing changes and restrict write access: trusted actors now publish directly after both validation jobs pass. Existing branch settings are not changed by the workflow. |
| Candidate review | RC2 metadata/code 23, changelog and generated notes are prepared. Review the preparation PR and exact-commit CI before any future dispatch; repeat history checks immediately before publication. See the [RC2 audit](releases.md#v100-rc2-version-plan). |
| Real-device/provider acceptance | Stalker, live playback, Multi-View, DVR, Google TV remote, cloud sync and compatible updates remain unverified by this program. Gather sanitized per-candidate reports. |
| GitHub form activation and triage ownership | All requested labels now exist, reusing `bug` and `enhancement`. After merge, preview the forms/default labels and assign a triage owner using [GitHub setup](BETA-FEEDBACK.md#labels-and-one-time-github-setup) before recruiting testers. |

The October 26, 2026 RC2 audit found published RC1 at `66e7b36` (code 22), its matching
tag and a separate permanent signing-backup draft; no RC2 tag or APK release exists.
The owner confirmed code 23 is unused by private/unpublished compatible builds.
See the [full version audit](releases.md#v100-rc2-version-plan). Historical PR findings and
metadata fingerprints do not certify the current production signing identity.

## RC2 preparation evidence

- [x] Metadata prepared: `1.0.0-rc.2` / `23`; `com.nuvix.player` and namespace unchanged.
- [x] [Changelog and canonical release notes](../CHANGELOG.md#100-rc2) prepared from PRs #58–#60
      after the actual RC1 release commit; no duplicate editable release-notes source.
- [x] Version progression audited: published RC1 code 22 → unused RC2 code 23;
      owner confirmed private history. Stable reserved at 24 or higher.
- [x] Source package/packaging and storage continuity inspected against the RC1 tag;
      schema 17, migrations, encrypted providers, presets, DVR and cloud preferences unchanged.
- [ ] Final PR commit CI: Build (unit tests, both lint variants, debug/unsigned release assembly,
      APK identity/history, Sports benchmark and Supabase security) and IPTV Playback Tests
      (offline fixtures and actual Media3 emulator readiness/first-frame/visible-output checks).
      Record final run links and outcomes in the preparation PR; pending is not a pass.
- [ ] Actual production signing continuity: no production RC2 signed APK or credentials available
      to this preparation. Synthetic verifier tests do not establish a production certificate pass.
- [ ] Emulator RC1→RC2 installation/data-retention test: not executed; this workspace has no
      Android SDK/adb/emulator or production-signed RC2. Media3 fixture tests do not test updates.
- [ ] Physical-TV RC1→RC2 in-place update with retained credentials/settings, Multi-View presets,
      recording schedules/history/storage access and cloud opt-ins.
- [ ] Physical-TV channel-switch acceptance and authorized-provider HLS/TS, Stalker/Xtream,
      same/shared/one/Unknown-account, rapid-navigation, Multi-View and protected-DVR checks.

Local October 26, 2026: all 160 Python tests, offline IPTV transport fixtures, release
identity/workflow security contracts, Sports/EPG benchmarks, documentation links and isolated
Supabase migrations/RLS/typed validation/concurrent merges passed. Synthetic signing-command
fixtures passed; no production certificate verification is claimed. Local Android validation could not start: wrapper download failed with
`UnknownHostException: services.gradle.org`; only JDK 21 and no Android SDK/emulator are
installed. Use the final PR commit's Actions results for Android/emulator/APK evidence.
This is a draft preparation while signing and hardware acceptance blockers remain open.

## RC1 preparation evidence (historical)

- [x] Version metadata prepared: `1.0.0-rc.1` / `22`, replacing `0.1.0` / `21`.
- [x] Source application ID validated as `com.nuvix.player`; namespace remains unchanged.
- [x] [Candidate changelog](../CHANGELOG.md#100-rc1) prepared from implemented code.
- [x] Release notes prepared in the workflow's changelog source, with testing priorities,
      implementation limits, untested acceptance, migration, privacy and GPL/OpenTV notices.
- [x] Public/private version history audited and [future stable progression](releases.md#v100-rc1-version-plan)
      documented; code 22 exceeds known compatible builds. Recheck before publication.
- [x] Release identity CI passed on `c55d2c4`, including actual unsigned APK metadata/history.
- [x] Android unit tests, debug/release lint and debug/unsigned-release APK builds passed on `c55d2c4`.
- [x] Local Python release/branding tests passing: 111 tests, including seven RC1 integration
      tests and the existing approval, certificate, checksum, prerelease and workflow regressions.
- [x] Local isolated Supabase migration/security/concurrent-merge checks passing using the
      unchanged PostgreSQL 16.15 runner; no hosted database was accessed.

Local validation on October 24, 2026 also passed `scripts/release-identity.py`, Sports/EPG
benchmarks (2,000 channels / 96,000 programmes), and `git diff --check`. Android unit tests,
debug/release lint and both APK builds were attempted locally but could not start: the Gradle
wrapper download failed with `UnknownHostException: services.gradle.org`. GitHub
[Build #185](https://github.com/DisabledAbel/Nuvix/actions/runs/40132919289) resolved that
local limitation: both jobs passed on `c55d2c4`, including all 111 Python tests, identity and
actual unsigned APK/history inspection, Android unit tests, debug/release lint, both APK
builds, Sports benchmark and isolated Supabase security. No production workflow was dispatched.
A source check is not a signed APK inspection; passing CI does not authorize production
or complete physical-device checks.

Initial [Build #183](https://github.com/DisabledAbel/Nuvix/actions/runs/40132047303) passed
Python/identity and Supabase checks but was canceled during the combined Android step,
before unsigned APK/history inspection. It is not an Android CI pass. Initial preparation is
recorded in [PR #50](https://github.com/DisabledAbel/Nuvix/pull/50), which was merged outside
this preparation session before CI completed. Final validation remains reviewable in
[follow-up PR #51](https://github.com/DisabledAbel/Nuvix/pull/51); consult its status for the
latest commit after this evidence-only documentation update.

## Scope and release control

- [ ] Feature freeze completed: owner records v1 scope and defers unrelated enhancements.
- [ ] Direct Release workflow reviewed and CI verified: manual main requests or version-tag pushes
      at current main publish only for trusted actors after both validation jobs pass.
      Ordinary branch pushes, schedules and successful PR CI do not publish; no nightly path exists.
- [ ] Production signing configured with the existing authorized identity; no key replacement.
- [ ] Existing signing key/password backup and recovery verified; no replacement identity generated.
- [ ] Independent production certificate SHA-256 verified against a trusted record and actual APK.
- [ ] Repository write access and workflow/signing change review are configured for the trusted-publisher model.
- [ ] Both `Compile, test and lint` and `Supabase migrations and security` are required and pass
      for the exact commit. Retain unit, debug/release lint, build, Python, identity/history,
      benchmark and isolated database security checks; do not weaken them to pass.
- [ ] One saved `SIGNING_BACKUP_PASSWORD` and permanent encrypted signing draft verified, or
      a complete legacy signing configuration verified. Private keys remain confined to the publisher.
- [ ] Trusted publisher identity, exact current-main commit and prepared-artifact checks verified.
- [ ] App identity verified from built APK: `com.nuvix.player`, non-debuggable, valid production
      signature, trusted certificate, matching committed versionName and versionCode.
- [x] Increasing version codes validated across RCs, stable and privately distributed compatible
      builds. Stable must exceed every RC; tags match versions and are unused.
- [x] RC channel policy verified by Python/JVM tests: `prerelease: true`, `make_latest: false`; stable updater
      rejects prereleases, drafts and nonstable tag formats. Manual and version-tag release paths share these checks.
- [ ] APK signature, SHA-256 and all release asset checksums verified; matching corresponding
      GPL source and notices retained. Unsigned CI artifacts are not beta downloads.

## Candidate acceptance on physical devices

Use the detailed [beta checklists](BETA-TESTING.md#run-and-record-a-test-session). Attach results
for Android TV, Google TV and compatible Fire TV, including constrained hardware where available.
Record device coverage gaps explicitly; do not fill them with host test results.

- [ ] Authorized Stalker portal checklist completed: add/authenticate/MAC edits, each Automatic
      and provider-supplied identity field, individual resets, restart/reconnect, validation,
      rejection, EPG/playback and retained connection allowance.
- [ ] Live playback and EPG tested: tune, audio/subtitles, recovery, missing guide data,
      navigation/preview ownership and provider limits.
- [ ] Multi-View tested: two/three/four where supported, remote focus, both audio modes, layout,
      preset save/restore, fullscreen, Compact Guide/Sports, buffering and decoder/memory limits.
- [ ] Background DVR tested: schedule/background/screen-off where supported, multiple captures,
      duration edits/stop, late starts, protection/padding, warnings/capacity/conflicts, partial
      recovery and external storage failure where safe. Document OEM restrictions.
- [ ] Google TV physical remote tested; Android TV/Fire TV D-pad/OK/Back, text entry, readable
      large text, accessible labels and focus restoration checked.
- [ ] Optional cloud sync tested on two devices with a disposable project: opt-ins, offline
      merges, account isolation, settings retention and no private provider identity export.
- [ ] Fresh installation and Android installation approval/cancellation tested; package identity
      and side-by-side old-package behavior verified without automatic uninstall.
- [ ] Compatible higher-code in-place update tested with preserved database/provider settings,
      Multi-View presets, recordings/storage and cloud settings. Migration evidence recorded.
- [ ] Stable discovery tested on-device: RCs ignored, newer compatible stable offered only when
      available; RC → stable installation/data retention verified when such releases exist.

## Feedback and stable decision

- [ ] Issue forms previewed in GitHub; required fields, optional sanitized attachments, Not Tested
      choices, links and default labels verified after merge. Do not submit fake public reports.
- [ ] Configured labels reviewed, named triage owner assigned, and reproducible reports linked
      from candidate session summaries. Track blockers using the documented issue filters.
- [ ] All Critical bugs resolved and verified on an affected candidate/device; security fixes
      handled privately as appropriate. Failure to reproduce is not resolution.
- [ ] High-priority release blockers reviewed individually; every remaining issue has evidence,
      owner and an explicit fix/deferral decision. Open release blockers prevent stable release.
- [ ] Documentation complete: installation/update limits, supported scope, known issues and
      provider authorization/privacy guidance match observed results.
- [ ] GPL license, attribution and third-party notices retained; corresponding source prepared.
- [x] Candidate release notes prepared from the matching changelog with beta warnings,
      unverified device coverage, known limitations, manual install/update instructions and feedback links.
- [ ] Stable release notes and final go/no-go reviewed separately after candidate feedback;
      no guaranteed date or unverified production-readiness claim.

## Future publication and follow-up — separate authorized work

Only after prerequisites above are satisfied may maintainers consider a separately authorized
manual `Release` dispatch on `main`, version `1.0.0-rc.2` (optional `v`), channel `rc`, by a
trusted publisher, or push its matching version tag at current main. This workflow change does
not dispatch it. A manual run creates the tag; a tag-push run verifies and retains that tag.
Follow [failure recovery](releases.md#failure-rejection-and-recovery) if anything fails;
never overwrite a reserved version or bypass signer, artifact or version-history checks.

- [ ] After a real approved RC publication, verify tag/commit, prerelease/non-Latest status,
      all assets/checksums/signature/source and release instructions before announcing availability.
- [ ] Update the beta guide's availability status with the real release link only after verification.
- [ ] Publish any availability announcement only through a separately authorized communication;
      the [preparation announcement](BETA-DISCORD-ANNOUNCEMENT.md) makes no APK availability claim.
