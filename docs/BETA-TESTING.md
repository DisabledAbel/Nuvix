# Nuvix v1.0 community beta

**RC2 status: prepared for review, not published or downloadable.** Target `v1.0.0-rc.2`
uses versionCode `23` and package `com.nuvix.player`; [prepared release notes](../CHANGELOG.md#100-rc2)
cover the channel-switch repairs, optional Sports settings and automated playback regressions.
[RC1](https://github.com/DisabledAbel/Nuvix/releases/tag/v1.0.0-rc.1), code `22`, was published
on October 25, 2026. Production RC2 signing continuity, physical-TV switching and update/data
retention acceptance remain unverified; see the [readiness tracker](V1-RELEASE-CHECKLIST.md).
There is no RC2 publication date. Do not run a signing or release workflow to test this guide.

Nuvix is a GPL-3.0-or-later IPTV player. It supplies no channels, subscriptions, accounts,
playlists or media. Use only accounts, content and device identities authorized by your provider.
Beta software may crash or change stored data. Prefer a spare TV and finish active recordings
before installing or updating. There is no general settings/database export or guaranteed downgrade.

## Devices and limitations

The application minimum is Android 6.0/API 23. The beta targets Android TV, Google TV and
compatible Fire TV devices meeting that minimum. This is a testing scope, not a list of
certified models: no physical-device or live-provider pass is established by this program.
Record the manufacturer, exact model and Android/Google TV/Fire OS version with every report.
Include constrained hardware when available; do not infer support from emulator/JVM success.

Codecs, decoder counts, memory, storage permissions, exact alarms, notifications, screen-off
capture, PiP and installer flows vary by manufacturer. Four-panel Multi-View and uninterrupted
background DVR are not guaranteed. Provider channel/EPG availability, identity requirements,
catch-up and simultaneous connection allowances also vary. Ask the provider for authorization
and limits; Nuvix cannot supply access or bypass them. See [live playback](live-playback.md),
[Multi-View/DVR](smart-multiview-dvr.md) and [Stalker configuration](stalker-configuration.md).

## Obtain and verify a candidate — after publication

1. Open the official [GitHub Releases page](https://github.com/DisabledAbel/Nuvix/releases).
   Only proceed when a maintainer has published the exact candidate, for example
   **v1.0.0-rc.2**, with the **Pre-release** designation. If RC2 is absent, wait for publication.
   Do not use the stable `/releases/latest` link to find a candidate.
2. Read its notes and known issues. Download `nuvix.apk` and `SHA256SUMS` from that release's
   Assets. `update.json`, `release-notes.md` and the matching GPL source archive must also
   accompany it. Never install an unsigned preparation artifact or a debug APK as the beta.
3. On a trusted computer, verify the downloaded assets and certificate with Android build tools:

   ```sh
   sha256sum -c SHA256SUMS
   apksigner verify --verbose --print-certs nuvix.apk
   aapt dump badging nuvix.apk
   ```

   Download every listed asset before checking the complete checksum file. The package must be
   `com.nuvix.player`, and the version must match the candidate. Compare the certificate SHA-256
   with an independently trusted maintainer record or previously trusted production APK; a
   fingerprint in the same download alone is not independent verification. The production
   fingerprint is not yet verified here. A checksum checks integrity, not publisher identity.
   If you cannot verify locally, use the official page and maintainer verification evidence;
   record certificate/checksum checks as **Not Tested**, never as a pass.

## Install and check the version

1. Transfer the verified APK to your TV using a trusted local transfer or download tool.
   Open it with the TV's package installer. Device menus vary: allow **Install unknown apps**
   for that specific transfer/file app only if Android requests it. Review and approve the
   installation yourself; Nuvix cannot silently install. Revoke that permission afterward
   if it is no longer needed. Fire TV may expose this under Developer Options.
2. Optional, for testers already using authorized Android debugging:

   ```sh
   adb install -r nuvix.apk
   ```

   Android installation/signature rules still apply. Do not enable debugging just to submit a report.
3. Open Nuvix and **Settings → About**. Under **Nuvix version**, record both the version name
   and build number. Android's App info also helps distinguish installed packages.
4. Add only your authorized provider. Keep credentials and identities on your device, and
   check permissions and storage before testing DVR. Confirm that Android cancellation or
   denied installation approval leaves the existing app usable.

### RC1 to RC2 update acceptance

After RC2 publication, independently compare both production APKs: `com.nuvix.player`,
RC1 code `22` → RC2 code `23`, and the same trusted signing certificate. Finish active
recordings; install RC2 over RC1 using the installer or `adb install -r`, never uninstall,
clear data or use downgrade flags. Record installation approval/cancellation and verify
providers/credentials, Multi-View presets, scheduled recordings/history/storage grants and
cloud opt-ins before and after restarting. Private values stay on the device.

Repeat D-pad Up/Down with same-provider HLS/TS, one/spare/Unknown/shared allowances, Stalker,
Xtream, rapid input and concurrent protected DVR. Check actual video and sanitized first-frame
phases, then Multi-View, Sports Hub (both optional settings off/on) and remote focus.
Emulator/JVM results do not complete physical-TV acceptance.

### Older packages and later candidates

`com.disabledabel.nuvix` and `com.nuvix.player` are separate apps. Ordinary in-place updates
cannot cross those identities, even with the same signer. Keep the old installation until
you have verified the new setup; nothing in this program automatically uninstalls it.
Debug and legacy nightly packages are also separate. Follow the
[migration instructions](releases.md#application-id-migration) to re-add providers, permissions
and scheduled recordings safely. Private databases, credentials and device Keystore keys do
not transfer automatically. Optional sync restores only supported, previously enabled categories.

Install a newer RC manually from its own official prerelease page using the same procedure.
It must retain `com.nuvix.player`, the same production certificate and a **higher versionCode**.
Compatible updates should retain data; verify the checklist below. On signature/version errors,
stop and report the sanitized error. Do not uninstall, clear data or force a downgrade to get
past an error. A fix may require another candidate with a higher code.

**The in-app updater discovers stable releases only.** It ignores prereleases and RC tag
formats; it does not notify testers of later candidates. A future stable APK can update an RC
only with the same package/signer and a higher versionCode. Stable discovery and real installer
behavior still require device testing; see [update troubleshooting](update-troubleshooting.md).

## Run and record a test session

Use the [Beta test report form](https://github.com/DisabledAbel/Nuvix/issues/new?template=beta_test_report.yml)
for a session summary; submit a separate [Bug report](https://github.com/DisabledAbel/Nuvix/issues/new?template=bug_report.yml)
for each reproducible failure and link it from the summary. Search existing reports first.
Report **Pass, Fail, Partial, Not Tested or Not Supported** for each applicable check.
An unchecked box below means unverified, not passed. Copy the relevant rows into your report
with the result, candidate/build, device/OS, sanitized steps and observed behavior.
Record provider type and connection allowance without a provider name, private URL or identity.
Use [feedback triage](BETA-FEEDBACK.md) for severity, labels and blocker handling.

### Core playback, guide, remote and cloud sync

- [ ] Load authorized M3U, Xtream Codes or Stalker channels; record only the type actually tested.
- [ ] Tune live channels; test audio/subtitles, channel changes and network/buffering recovery.
- [ ] Load/refresh EPG, handle missing data and browse Compact Guide and Sports Hub without
      unexpectedly retuning the current channel. Sports availability depends on EPG metadata.
- [ ] On a physical remote, test D-pad, OK and Back, visible focus, text entry, scrolling,
      readable large text and accessible labels. Record Google TV remote results explicitly.
- [ ] If testing opt-in cloud sync, use your own disposable project/accounts and the
      [two-device procedure](TESTING.md#advanced-cloud-sync). Check category opt-ins, offline
      edits, account isolation and settings after updates; keep keys/tokens out of reports.

### Authorized Stalker portal checklist

Use only a provider-authorized account and assigned portal MAC/device identity. Do not copy,
guess or rotate another device's identity. Coordinate changes with your provider; reauthorization
can revoke existing stream URLs. The [configuration guide](stalker-configuration.md) describes
Automatic derivation and the **Settings → Providers → Advanced Configuration** controls.

- [ ] Add a portal and authenticate with authorized credentials; load channels and EPG.
- [ ] Configure the assigned portal MAC. Edit MAC Address, cancel once, then confirm an
      authorized change; verify strict colon-separated six-octet validation and useful focus.
- [ ] Test **Automatic** Serial Number, Device ID 1, Device ID 2 and Signature individually.
      The automatic signature is resolved during the portal handshake.
- [ ] Test provider-supplied **Custom** values for each field only where authorized and available;
      otherwise record Not Tested. Check that unrelated custom values remain independent.
- [ ] Restore each individual field to **Automatic** without clearing other custom fields.
- [ ] Save, leave/reopen configuration and restart Nuvix; verify saved values privately.
- [ ] Reconnect by refreshing the provider or next opening a channel after configuration changes.
      Saving alone does not reconnect or intentionally restart active playback/capture.
- [ ] Test invalid input with synthetic values, cancel/back behavior and sanitized validation.
- [ ] Check rejected authentication using a provider-approved test or local synthetic portal.
      Expect an ordinary failure, no identity guessing and no credentials in diagnostics.
- [ ] Verify EPG/channel loading and live playback after the authorized changes.
- [ ] Verify provider connection limits still apply, including concurrent Multi-View and DVR.
      Check an unrelated provider remains usable. Never exceed the provider's allowance.

### Multi-View checklist

Use [Smart Multi-View](smart-multiview-dvr.md) and a known authorized account allowance.
Record codec/resolution, panel count and model/memory where known; omit stream URLs.

- [ ] Play two simultaneous channels, then three, then four **where supported and authorized**.
      Record each count separately; graceful admission refusal is different from playback failure.
- [ ] Navigate panels and controls with D-pad; verify visible focus and no focus traps.
- [ ] Check audio-follow-focus delay (default 150 ms), rapid focus changes and interruptions.
- [ ] Select OK-only audio switching and verify focus movement alone does not switch audio.
- [ ] Edit layouts using the remote, save a preset, restart and restore it through its preview.
- [ ] Enter/leave fullscreen and return to the expected panel/layout/audio state.
- [ ] Use Compact Guide and Sports Hub to add/select an authorized channel or confirmed broadcast.
- [ ] Reach a known provider connection limit safely; verify capacity warnings/admission and DVR priority.
- [ ] Interrupt/recover the network; check buffering recovery without losing unrelated panels.
- [ ] On constrained Android TV hardware, test decoder/memory limits, mixed formats and reduced
      panel counts. Record unavailable hardware as Not Tested; never claim universal four-panel support.

### Background DVR checklist

Use a short authorized recording and expendable storage. See [DVR behavior](smart-multiview-dvr.md),
[duration controls](recording-duration.md) and [late-start policies](recording-late-start.md).
Screen-off capture depends on OEM power/foreground-service behavior; this checklist promises
no uninterrupted unattended capture on unsupported hardware. Do not interrupt valuable recordings.

- [ ] Schedule a recording; check permissions, alarm/notification behavior and actual playable output.
- [ ] Background Nuvix while recording; test screen-off recording **where supported**, then inspect
      captured bytes, duration and outcome instead of relying only on the status label.
- [ ] Record multiple programs within provider/storage limits, including overlapping schedules.
- [ ] Extend an active recording; shorten it; stop it through confirmation. Verify file continuity
      and the final end time; do not infer capture from an updated schedule alone.
- [ ] Test Record Remaining, Skip If Late and Minimum Time Remaining late-start policies,
      including inherited/overridden settings and visible Missed/Partial outcomes.
- [ ] Test **Protect Recording** and **Lock Padding**, scheduled conflict warnings and explicit choices.
- [ ] Start a scheduled recording during Multi-View; verify warning/deadline handling and recording
      priority without disrupting unrelated providers or silently exceeding capacity.
- [ ] Test missing/unknown provider capacity and the explicit request-specific warning/approval path.
- [ ] Interrupt a disposable capture; restart and inspect partial recording recovery and failure reason.
- [ ] Where safe/testable, disconnect expendable external storage or revoke its permission; verify
      a useful failure and retained partial data. Record unavailable storage tests as Not Tested.

### Installation and update checklist

Some rows need two real, consistently signed releases. They remain Not Tested until those exist.
Use [release verification](releases.md#compatibility-and-verification) for maintainer evidence.

- [ ] Fresh installation, denied/cancelled approval and approved Android installation on the device.
- [ ] Actual APK package `com.nuvix.player`, release signature and certificate consistency with
      an independently trusted record; version name/build match the release.
- [ ] In-place update with the same package/certificate and a strictly higher versionCode.
- [ ] App data retained; database migration succeeds without reset or loss of provider configuration.
- [ ] Provider settings (including Stalker Automatic/custom fields), favorites and guide organization retained.
- [ ] Saved Multi-View presets restore; existing recording entries/files and storage access remain usable.
- [ ] Cloud sync settings, opt-ins and account isolation retained; check only supported categories.
- [ ] Stable discovery ignores RCs/drafts; a newer compatible stable release is offered only when
      one exists. Test approval/cancellation and data retention for RC → stable separately.
- [ ] Old `com.disabledabel.nuvix` remains a separate installation, with no automatic uninstall.

## Report safely and reproducibly

Include exact version/build, manufacturer/model, OS, feature, frequency, numbered remote actions,
expected and actual behavior, and whether a restart changes the result. Include a small sanitized
example or synthetic fixture when possible. Say which checks were skipped and why. Screenshots
and logs are optional; review them yourself before attaching. A report does not require ADB.

**Never post IPTV credentials, private portal/playlist/stream URLs, MAC addresses, serial numbers,
device IDs, signatures, tokens, cloud keys, account emails or personal information.** Logs, QR codes,
screenshots, sync bundles, filenames and URL query strings can contain these. Replace them with
`[REDACTED]`; do not upload databases or provider configuration exports. Use synthetic identities
for validation/redaction evidence. GitHub issues and Discord are public; neither is a credential channel.
If information was exposed, remove it where possible, revoke/rotate it through your provider and
notify maintainers without reposting it. For suspected security problems, follow
[private reporting guidance](BETA-FEEDBACK.md#security-and-sensitive-reports) rather than a public issue.
