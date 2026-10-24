# Nuvix v1.0 release readiness

**Decision: blocked. No beta or stable release is authorized by this checklist.**
Planned first candidate: `v1.0.0-rc.1`. Current committed metadata remains
`0.1.0` / versionCode `21`, production package `com.nuvix.player`, minimum API 23.
This preparation does not bump versions, create tags, sign APKs, dispatch workflows or publish.

This is the canonical v1 tracker. Use [tester instructions](BETA-TESTING.md),
[feedback triage](BETA-FEEDBACK.md) and the existing [release runbook](releases.md).
Every box starts incomplete. A maintainer records candidate/build and commit, test device/OS,
date, evidence link, owner and result before checking it. CI/JVM/emulator results cannot close
physical-TV or live-provider rows. Not Tested/Not Supported results require review, not a pass.
Repeat affected checks after fixes and record explicit acceptance of any remaining limitations.

## Current blockers and evidence

| Blocker | Current evidence / action required |
| --- | --- |
| Production signing recovery | PR [#47](https://github.com/DisabledAbel/Nuvix/pull/47) deliberately stops signing setup and publication. Reviewed iPad-accessible encrypted backup/recovery and an independent restore/certificate drill remain unimplemented. Preserve the hard stop. |
| Production identity and approval | Environment protection, signing-secret inventory, independent production certificate and reviewer availability are unverified. Connected tools cannot establish them. Administrator must follow [Safari setup](releases.md#safari-settings-checklist-and-independent-fingerprint); API denial blocks release. |
| Main review protection | October 24, 2026 inspection of [CI ruleset](https://github.com/DisabledAbel/Nuvix/rules/25127172): zero required approvals, no required Code Owner/stale/latest-push approval, admin bypass allowed and only Android CI required. Enforce independent review, no bypass and both existing CI jobs. |
| Candidate metadata | A separate reviewed version/changelog change is required before any future RC dispatch. Audit public and privately distributed compatible versionCodes; do not assume code 22 is unused. |
| Real-device/provider acceptance | Stalker, live playback, Multi-View, DVR, Google TV remote, cloud sync and compatible updates remain unverified by this program. Gather sanitized per-candidate reports. |
| Beta triage labels | Existing labels were inspected; `bug` and `enhancement` exist. The beta/domain labels are missing and label administration is unavailable through this session's tools. Complete [manual label setup](BETA-FEEDBACK.md#labels-and-one-time-github-setup) before recruiting testers. |

No GitHub Releases existed at the October 24, 2026 inspection. This does not prove that no
compatible APK or signing identity has ever been distributed privately. Historical PR findings
are evidence of implementation, not certification of current production settings.

## Scope and release control

- [ ] Feature freeze completed: owner records v1 scope and defers unrelated enhancements.
- [ ] Existing manual Release workflow validated for the exact candidate commit; no automatic
      tag/push/scheduled/CI publication and no nightly publisher introduced.
- [ ] Production signing configured with the existing authorized identity; no key replacement.
- [ ] Reviewed recovery implementation and encrypted certificate-bound backup restore drill verified
      independently, including the supported iPad flow. Resolve the hard stop through reviewed work.
- [ ] Independent production certificate SHA-256 verified against a trusted record and actual APK.
- [ ] Main requires independent PR/Code Owner approval, stale/latest-push review and no admin bypass.
- [ ] Both `Compile, test and lint` and `Supabase migrations and security` are required and pass
      for the exact commit. Retain unit, debug/release lint, build, Python, identity/history,
      benchmark and isolated database security checks; do not weaken them to pass.
- [ ] `production` requires another authorized human reviewer, Prevent self-review, no admin
      bypass and exactly main-only branch deployment; APIs can verify current protection.
- [ ] Human release approval process verified for the exact run/commit/artifacts. Approval of
      this documentation PR is not deployment approval. Never dispatch just to check a box.
- [ ] App identity verified from built APK: `com.nuvix.player`, non-debuggable, valid production
      signature, trusted certificate, matching committed versionName and versionCode.
- [ ] Increasing version codes validated across RCs, stable and privately distributed compatible
      builds. Stable must exceed every RC; tags match versions and are unused.
- [ ] RC channel policy verified: `prerelease: true`, `make_latest: false`; stable updater
      rejects prereleases, drafts and nonstable tag formats. Only the existing manual path may publish.
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
- [ ] Missing labels created/reused, named triage owner assigned, and reproducible reports linked
      from candidate session summaries. Track blockers using the documented issue filters.
- [ ] All Critical bugs resolved and verified on an affected candidate/device; security fixes
      handled privately as appropriate. Failure to reproduce is not resolution.
- [ ] High-priority release blockers reviewed individually; every remaining issue has evidence,
      owner and an explicit fix/deferral decision. Open release blockers prevent stable release.
- [ ] Documentation complete: installation/update limits, supported scope, known issues and
      provider authorization/privacy guidance match observed results.
- [ ] GPL license, attribution and third-party notices retained; corresponding source prepared.
- [ ] Candidate release notes prepared from the matching changelog with beta warnings, tested
      devices, known limitations, manual install/update instructions and feedback links.
- [ ] Stable release notes and final go/no-go reviewed separately after candidate feedback;
      no guaranteed date or unverified production-readiness claim.

## Future publication and follow-up — separate authorized work

Only after prerequisites above are satisfied may maintainers consider a separately authorized
manual `Release` dispatch on `main`, version `1.0.0-rc.1` (without `v`), channel `rc`, with the
existing independent production approval. This PR must not dispatch it. The workflow creates
the tag; do not precreate it. Follow [failure recovery](releases.md#failure-rejection-and-recovery)
if anything fails; never overwrite a reserved version or bypass signing/approval checks.

- [ ] After a real approved RC publication, verify tag/commit, prerelease/non-Latest status,
      all assets/checksums/signature/source and release instructions before announcing availability.
- [ ] Update the beta guide's availability status with the real release link only after verification.
- [ ] Publish any availability announcement only through a separately authorized communication;
      the [preparation announcement](BETA-DISCORD-ANNOUNCEMENT.md) makes no APK availability claim.
