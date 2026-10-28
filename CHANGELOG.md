# Changelog

## [Unreleased]

- Turn GitHub Pages into a documentation hub with static rendered guides, local search,
  responsive navigation and code-only pairing instructions for the live web manager.
  Preserve the permanent APK shortcut and keep documentation free of analytics and remote scripts.

- Configure Cloudflare Worker previews with isolated browser-manager session storage and
  separate rate-limit namespaces; document the Workers Builds root and commands.

- Harden storage review paths: match USB receipts to their tree on older Android versions,
  isolate recording mutation locks, remove receipts for discarded empty outputs, retry failed
  refreshes, and retain unsaved policy drafts when asynchronous persistence fails.

- Add DVR Storage Manager in Recording settings and Recordings: destination availability,
  tracked usage, supported capacity, measured remaining-hours estimates and configurable
  conservative low-space warnings. Unsupported or stale capacity is shown as Unknown.
- Add explicit date/size selection and deletion confirmation; preserve history until storage
  success and retain a deletion audit trail. Storage failures no longer silently erase rows.
- Add optional per-destination cleanup, off by default, with free-space/retention controls,
  creation receipts, fresh eligibility/capacity checks, oldest-first bounded work and error/target
  warnings. Active and protected recordings are excluded; Lock Padding remains independent.
- Add storage and remote-navigation regression tests and document unverified physical TV,
  USB and NAS acceptance checks in [DVR Storage Manager](docs/dvr-storage-manager.md).

## [1.0.0]

### Nuvix v1.0.0 — first stable release

**RC1 users must uninstall and reinstall.** The RC1 private signing key and its
backup are unavailable. The owner authorized a replacement signing identity on
October 26, 2026. Android will reject an in-place update from RC1 even though the
package remains `com.nuvix.player`. Uninstalling deletes private app data, including
saved providers/credentials, preferences, favorites and recording schedules/history.
Save provider details privately, finish active recordings, and retain any accessible
recording files before uninstalling. Re-add providers and storage permissions after
installation. Optional cloud sync restores only its supported metadata categories;
it is not a complete backup. Future stable updates must retain the replacement key.

### What's included

- Live IPTV with M3U/M3U8, Xtream Codes and authorized Stalker portals, plus movies,
  series, favorites, search, XMLTV guide and retained playback while browsing.
- Mini TV Guide, Sports Hub, optional Sports playback recovery/cushion, customizable
  Home and optional TV launcher.
- Multi-View with up to four streams where provider capacity and hardware allow.
- Background DVR with scheduling, provider conflict handling, protected recordings,
  padding and late-start controls.
- Optional category-controlled cloud sync and stable-release update discovery.
- Channel-switch repairs for stale playback surfaces and callbacks, cancellation of
  superseded requests, and provider connection transitions. Physical-TV resolution
  of the original one-minute delay is not yet confirmed by recorded acceptance.

RC2 was prepared but was not published. This release uses `versionName` **1.0.0**, `versionCode` **24** and package **com.nuvix.player**.

Known limitations remain: provider/hardware-dependent playback and decoder limits,
direct MPEG-TS-only DVR capture, vendor background execution restrictions,
EPG-dependent Sports matching and partial opt-in cloud sync. No universal
channel-switch latency, uninterrupted DVR or full settings restoration is promised.

Physical-TV and live-provider acceptance remain unverified in
[the readiness tracker](https://github.com/DisabledAbel/Nuvix/blob/main/docs/V1-RELEASE-CHECKLIST.md).
Passing automated tests does not certify those device checks.
Use the exact `v1.0.0` GitHub release and verify its checksums and
replacement certificate before installing `nuvix.apk` through Android's prompt.
An RC1 installation cannot accept the new signer through the in-app updater;
use the release page manually after preserving what you can.


### Licensing and feedback

Requires Android 6.0/API 23 or newer. Nuvix provides no channels, playlists or media;
use authorized sources. GPL-3.0-or-later, with OpenTV copyright, attribution and
third-party notices retained. Nuvix is an independent modified version of OpenTV,
with no official affiliation with OpenTV or DIRECTV. The release includes corresponding
source. Report reproducible bugs at https://github.com/DisabledAbel/Nuvix/issues;
redact provider credentials, private URLs, MAC addresses, tokens and device identifiers.

## [1.0.0-rc.2]

### Nuvix v1.0.0 Release Candidate 2

**Prerelease for public beta testing.** RC2 is prepared, not published. These notes cover
changes after the actual RC1 release commit `66e7b366b3beef0564d53fe8c52a1353abfc04f8`.
Use a test device and finish active recordings before updating.

### What's Fixed

- Repaired confirmed defects behind the RC1 D-pad Up/Down report where a new channel label
  appeared while the previous video remained for about a minute. Corrected player surface
  binding and replacement player handling reattach the retained video view to the current player;
  late callbacks and outgoing views cannot take ownership from it.
- More responsive channel-switch request processing follows the latest requested channel,
  cancels obsolete navigation requests and Stalker portal calls, and rejects stale callbacks.
  Provider connection transitions close constrained same-account playback before resolving
  the next link, while preserving shared-account allowances and protected recording reservations.
- Safer playback metadata and loading-state behavior distinguish the requested channel from
  the previous stream while a replacement starts. A failed or canceled tune cannot commit
  obsolete playback information.

These are verified implementation repairs with automated regressions. The original delay is
**not yet confirmed resolved on physical TVs**; startup performance varies by hardware,
network and provider. No universal channel-switch latency is promised.

### What's Improved

- Sports Hub has optional auto switchover to another currently confirmed broadcast of the
  selected game after failure, end or persistent buffering. It rechecks eligibility and provider
  capacity, uses one retained player, and stops recovery when the user changes channel or pauses.
- An independent optional Sports playback delay requests a ten-second startup/rebuffer cushion,
  subject to stream support and device memory limits. Both Sports settings default to off and
  are saved locally; recovery is not seamless and this is not a DVR extension feature.
- Stream Information shows sanitized elapsed channel-transition phases for troubleshooting.
- Automated IPTV playback coverage now decodes generated HLS and MPEG-TS through Media3 with
  Xtream-style and synthetic Stalker providers. It checks playback readiness, actual first frames
  and visible replacement video, rapid channel navigation, connection limits, stream failure and
  recovery, player cleanup, provider imports and generated-media DVR regressions. HTTP availability
  alone does not count as application playback. Emulator results do not certify TV/provider behavior.

### Beta Testing Focus

Use the [beta guide](https://github.com/DisabledAbel/Nuvix/blob/main/docs/BETA-TESTING.md)
and report Pass/Fail/Partial/Not Tested for:

1. D-pad Up/Down channel switching, including rapid input and actual new-channel video.
2. Same-provider switching with one, spare and Unknown connection allowances, including shared accounts.
3. Authorized Stalker playback and cancellation during stream resolution.
4. Authorized Xtream playback.
5. HLS and MPEG-TS startup, first frame, failure and recovery.
6. Multi-View, presets, audio and concurrent provider/decoder limits.
7. Background DVR, protected recordings, conflict handling and recording history.
8. Sports Hub with both optional settings off and independently enabled.
9. Physical remote navigation, focus, Guide/preview/fullscreen and PiP transfers.
10. Updating from RC1 to RC2 with retained provider settings/credentials, presets, recording
    schedules/history and cloud synchronization preferences.

### Known Limitations

- Physical Android TV/Google TV/Fire TV channel-switching and RC1-to-RC2 update acceptance
  remain release blockers. Automated synthetic tests do not resolve those hardware reports.
- Playback performance varies by hardware, network and provider. Multi-View needs sufficient
  hardware decoders and authorized provider capacity; four streams are not guaranteed.
- Background DVR depends on Android/vendor execution policy, permissions, provider and storage.
  Capture requires direct MPEG-TS; HLS playback does not imply HLS recording support. Automatic
  sports recording extensions and automatic recording deletion remain absent.
- Sports matching depends on supplied EPG; suggestions are not verified broadcasts or live scores.
  Other-device provider usage and future capacity remain unknown.
- Optional cloud sync does not restore provider credentials, recording files, device permissions
  or playback tuning. There is no general private database/settings export or guaranteed downgrade.

See the [readiness tracker](https://github.com/DisabledAbel/Nuvix/blob/main/docs/V1-RELEASE-CHECKLIST.md)
for exact-commit CI, signing evidence and remaining device checks.

### Installation and Updates

Requires Android 6.0/API 23 or newer. RC2 is not available until separately published as the
exact `v1.0.0-rc.2` GitHub **Pre-release**. After publication, verify all `SHA256SUMS` assets and
the independently trusted production certificate before installing `nuvix.apk` through Android's
installation prompt. Debug/unsigned CI APKs are validation artifacts, not beta downloads.

RC2 uses **`com.nuvix.player`**, versionName **`1.0.0-rc.2`**, versionCode **`23`** (RC1: `22`).
RC1 users should be able to update without uninstalling **provided both APKs have matching
production signing identities**. Production RC2 signing continuity and on-device data retention
are not yet verified. Do not uninstall, clear data or force a downgrade after an installation error.
Stable v1.0.0 must use at least code `24`, or higher if more codes are consumed.

Old **`com.disabledabel.nuvix`** installations are a separate package and cannot update in place;
private data and alarms do not migrate automatically. Debug and legacy nightly packages are
also separate. The in-app updater offers stable releases only and will not automatically offer
RC2. Follow the [installation and migration guide](https://github.com/DisabledAbel/Nuvix/blob/main/docs/BETA-TESTING.md).

### Reporting Bugs

Report reproducible problems at <https://github.com/DisabledAbel/Nuvix/issues>. Include
candidate/build, device/OS, provider type/format/allowance, steps, actual video behavior and
sanitized diagnostics. Do not share IPTV credentials, private portal URLs, MAC addresses,
tokens or device identifiers (including serial numbers, device IDs and signatures).
Redact screenshots and logs before attaching them.

### Licensing

GPL-3.0-or-later; original OpenTV copyright and attribution are retained. Nuvix is an independent
modified version of OpenTV, with no official affiliation with OpenTV or DIRECTV. A published
release must include corresponding source and notices. Nuvix supplies no IPTV channels,
subscriptions, playlists or media. Use authorized sources and provider-assigned identities.

## [1.0.0-rc.1]

### Nuvix v1.0.0 Release Candidate 1

**Prerelease for public beta testing.**
Preparing these notes does not authorize signing, tagging or publication. Beta software may
crash or change stored data; use a test device and finish active recordings before updating.

### What's Included

- Live IPTV playback with M3U/M3U8 playlists, Xtream Codes and authorized Stalker portals;
  XMLTV guide support, movies, series, favorites, search and retained live playback while browsing.
- Stalker Advanced Configuration: confirmed portal MAC editing and independent Automatic or
  provider-supplied serial number, device IDs and signature, with per-field resets. Identity
  stays in encrypted local storage; edits preserve account connection limits.
- Advanced EPG with Standard/Compact/Expanded layouts, filters, sorting, timeline navigation,
  bounded program search, details, reminders and recording actions.
- Sports Hub with EPG-derived game grouping, provider stream choices, favorite teams/leagues,
  manual match corrections and game reminders. No external live-score service is enabled.
- Smart Multi-View with up to four live panels where hardware and provider capacity allow,
  balanced/focused layouts, remote audio selection, compact Guide/Sports actions and local presets.
- Background DVR with account-aware conflict review, independent recording protection/padding
  locks, pre-recording Multi-View warnings, recording history and recoverable partial captures.
- Active recording duration controls: additions, custom end time, shortening, restore and explicit
  stop review. Late starts support Record Remaining, Skip If Late and Minimum Time Remaining.
- Customizable Home shelves, layouts, cards and backgrounds; optional TV HOME launcher and
  installed-app shortcuts, with manufacturer-dependent default-home selection.
- Optional category-controlled Supabase cloud sync, plus existing LAN/NAS sync. Provider credentials,
  stream URLs and Stalker identities are excluded from Supabase sync; categories require opt-in.
- Stream Information diagnostics, adjustable playback buffering and provider connection management
  shared by live playback, previews, Multi-View and DVR.
- In-app stable update discovery with package, signer, version-code and checksum validation,
  followed by Android installation approval. Candidates require manual installation.

### Beta Testing Focus

Use the [beta testing guide](https://github.com/DisabledAbel/Nuvix/blob/main/docs/BETA-TESTING.md) and report Pass/Fail/Partial/Not Tested:

1. Authorized Stalker authentication, portal MAC and advanced identity configuration.
2. Live TV playback stability, reconnects and provider connection allowances.
3. Multi-View performance, audio selection and decoder/memory limits.
4. EPG navigation, search, details and remote focus.
5. Background DVR, conflicts, duration changes, late starts and storage interruptions.
6. Physical remote D-pad/OK/Back, text entry and focus restoration.
7. Opt-in cloud sync across two devices and offline merges.
8. Fresh installation, old-package separation and compatible in-place updates.

### Known Limitations

Verified implementation limits:

- DVR capture requires a direct MPEG transport stream; playlist/nonmedia responses are rejected.
  Playback support does not imply that the same stream can be recorded. Automatic sports
  recording extensions and automatic recording deletion are absent.
- Sports schedules depend on supplied EPG and local matching; suggestions are not verified
  broadcasts or live scores. Future provider capacity and usage on other devices are unknown.
- Supabase sync does not restore provider credentials, local recording files, device permissions
  or playback tuning. There is no general private database/settings export or guaranteed downgrade.
- The stable updater excludes prereleases, drafts and nonstable tags. Nightly publishing is removed.
- Publication requires existing repository signing credentials and a trusted certificate fingerprint.
  Trusted publishers release after validation; the legacy signing-setup recovery gate is not used.

Untested acceptance: physical Android TV/Google TV/Fire TV remotes, authorized live providers,
codecs/decoder counts, Multi-View, OEM background execution, USB/NAS capture, cloud sync and
installation/update data retention still need per-device reports. No universal compatibility or
uninterrupted recording guarantee is claimed. See the [readiness tracker](https://github.com/DisabledAbel/Nuvix/blob/main/docs/V1-RELEASE-CHECKLIST.md).

### Installation

Requires Android 6.0/API 23 or newer. After an independently approved publication, use the exact
`v1.0.0-rc.1` GitHub **Pre-release**, verify `SHA256SUMS` and the independently trusted signing
certificate, and install `nuvix.apk` through Android's installation prompt. CI debug/unsigned APKs
are validation artifacts, not the beta. See [installation instructions](https://github.com/DisabledAbel/Nuvix/blob/main/docs/BETA-TESTING.md#obtain-and-verify-a-candidate--after-publication).

The package is **`com.nuvix.player`**, versionName **`1.0.0-rc.1`**, versionCode **`22`**.
Existing `com.disabledabel.nuvix` installations are a separate app and cannot update in place;
private data, credentials and recording alarms do not migrate automatically. Future compatible
versions, including stable, can update the new package only with the same signing identity and
a higher versionCode. See the [version plan](https://github.com/DisabledAbel/Nuvix/blob/main/docs/releases.md#v100-rc1-version-plan) and
[migration guidance](https://github.com/DisabledAbel/Nuvix/blob/main/docs/releases.md#application-id-migration).

### Reporting Bugs

Report reproducible problems at <https://github.com/DisabledAbel/Nuvix/issues> using the bug
or beta report form. Include candidate/build, model/OS, steps, expected/actual behavior and
sanitized diagnostics. Do not share private provider credentials, portal URLs, MAC addresses,
tokens or device identifiers; redact screenshots and logs before attaching them.

### Licensing

GPL-3.0-or-later; original OpenTV copyright and attribution are retained. Nuvix is an independent
modified version of OpenTV, with no official affiliation with OpenTV or DIRECTV. The approved
release must include corresponding source and notices. Nuvix includes no IPTV channels,
subscriptions, playlists or media. Use only authorized sources and provider-assigned identities.

## 0.1.0 — Nuvix development foundation

- Independently import OpenTV Android source at `43b95c05d8f6a042f51c0cff662ef80789ee7528`, preserving GPL-3.0-or-later notices and working provider/playback/guide/recording/LAN/NAS systems.
- Rebrand Android identity, icon, TV banner, splash and About; add Home, Live TV, Guide, Movies, TV Shows, Sports, Apps, Library and Settings navigation with provider-derived shelves.
- Add optional reversible HOME launcher entry, startup preferences and real installed-app launch/pinning/ordering.
- Add encrypted local database/secret preferences, retained-data recovery and explicit backup exclusions; guard provider URL/MAC/header inputs and redact diagnostics.
- Add live-applied validated buffer presets/custom values, transactional EPG replacement and bounded encrypted temporary guide downloads.
- Add optional email-code Supabase favorites/progress synchronization, local-first outbox, guarded merge RPC and owner-only RLS. Provider credentials/URLs are excluded.
- Add versionCode-based stable update discovery, APK integrity/package/signer checks, background preferences and interactive Android installation.
- Add debug CI and signed release verification/metadata/source archives. Production signing and real-device acceptance remain maintainer setup.

## Historical OpenTV changes

The entries below are preserved upstream history, not Nuvix release versions.

## 0.11.8

- **Edit a saved provider.** Settings → Providers now has an **Edit** button on each provider, so you
  can fix a server address, password, MAC or name in place instead of removing and re-adding it —
  your favourites, hidden channels and per-provider settings are kept.
- **Stalker portals: fewer dropped sessions.** A portal that occasionally rejected a call
  mid-session (channels loading only "sometimes") now gets one automatic re-handshake and retry, so
  an intermittent refusal becomes a silent re-auth rather than an empty channel list or a channel
  that won't tune.
- **Player controls stay clear of the system bar off-TV.** On phones and tablets the bottom control
  bar (subtitles, audio, quality, aspect ratio) could be drawn under the navigation bar and cut off;
  it's now inset above it. No change on Android TV.

## 0.11.7

- **Stalker portals: closer to a real box.** Building on v0.11.6, OpenTV now sends the rest of the
  identity a real MAG set-top box presents at login — the auth token in the session cookie (not just
  the header), a hardware-version hash, a timestamp and the box's API signature — and it probes a
  couple more portal paths. Some Ministra/Stalker panels only return the channel list when they see
  all of this, so lines that connected but showed no channels have a better chance of loading. If a
  portal still comes up empty, please open an issue and say which panel software it runs.

## 0.11.6

- **Stalker portals: full set-top-box identity.** OpenTV now authenticates to Stalker/Ministra
  portals the way a real MAG box does — sending the device serial, `device_id`, a signature and a
  metrics blob derived from your MAC, instead of the MAC on its own. Many portals won't hand over
  the channel list or stream links to a box that only presents a MAC, which is why some lines that
  worked in other apps came up empty in OpenTV. This lets those portals authorise OpenTV. If a
  portal still fails, please open an issue and say which one — it helps a lot.

## 0.11.5

- **Add a provider from your phone or laptop.** The "Manage on phone or laptop" web page now has an
  **Add a provider** section — pick Xtream, M3U or Stalker portal, fill it in with a real keyboard,
  then Test and Add. Channels load straight onto the TV, so you never have to type a server address
  or a MAC on the remote again.
- **Fixed the "camera won't scan it?" web address.** The manage screen was printing that link without
  its access token, so typing it into a browser hit a dead "Not found". It now shows the full working
  URL, and the token is short enough to type.


## 0.11.4

The big DVR release. Recordings behave like Sky Q, you can record straight to a NAS, watch a
recording while it's still taping, and the whole interface now speaks 30 languages.

### Recording & DVR

- **Watch a recording while it's still recording.** Start playing something the moment it begins
  taping — OpenTV reads the growing file straight off disk, so it costs **zero extra connections**
  to your provider. On a single-stream account that's the difference between "wait until it's
  finished" and "watch now". Fast-forward and rewind work within whatever's been taped so far.
- **Record straight to a NAS.** Point recordings at a network share over SMB (Settings →
  Recordings → NAS, or set it up from the phone/laptop web manager) so a cheap box isn't boxed in
  by its own storage — and every OpenTV in the house can reach the same recordings.
- **Single-connection auto-switch.** On a one-stream provider, if a recording is due to start on
  another channel, OpenTV moves the screen onto that recording as it begins — with a **30-second
  warning** first and a *Keep watching* button if you'd rather not. No more "why did my live stream
  just cut out?" (Recordings → Recording behaviour → *Auto-switch when recording starts*.)
- **Clash handling on the selected provider.** Recordings keep the provider selected in the
  Guide. Overlaps are checked against that account's capacity and recording-priority policy;
  providers are not substituted automatically.
- **Recording padding.** Start each recording a minute early and run a few minutes past the listed
  end, so a late kick-off or an overrun isn't clipped. Adjustable per side.
- **Series links show the next episode.** A series link now tells you exactly when it next records.
- **Storage readout.** See how much space recordings use and how much is left.

### Live TV

- **Pause & rewind live TV** *(experimental, opt-in).* Holds the last couple of minutes so you can
  pause and jump back. Off by default — it uses more memory.

### Languages

- **30 languages.** The whole interface is translated into Spanish, French, German, Italian,
  Portuguese, Dutch, Polish, Russian, Turkish, Arabic, Simplified Chinese, Japanese, Korean, Hindi,
  Swedish, Danish, Finnish, Norwegian, Czech, Greek, Romanian, Hungarian, Ukrainian, Indonesian,
  Thai, Vietnamese, Bulgarian, Slovak, Croatian and Persian. Settings → Language (each listed in
  its own name), or leave it on *System* to follow your device.

### Fixes & polish

- Smoother watch-while-recording, especially from a NAS: playback now keeps a buffer behind the
  live edge so a network share doesn't stutter at the write head.
- Back never lands you on a stray live channel or drops you out by accident — coming back from a
  recording returns to your recordings, and Back on the home screen asks before it exits.
- Password fields are masked, with a show/hide toggle.
- Recording stability: NAS write timeouts and reconnect handling, so a quiet network doesn't
  silently freeze a capture.

### Notes

- Everything stays on your device — provider logins never leave the box.
- Free and open source (GPL-3.0): https://github.com/opentvproject/opentv


## 0.10.0

- **Movies & Shows, redesigned.** A Plex-style layout with big artwork, cast & director, "more with
  this cast" rows, and cleaned-up titles (no more "NF -" / "(KR)" junk). Optional: add your own free
  TMDB key in Settings → Metadata and OpenTV fills in any posters, backdrops, cast or synopses your
  provider left blank — the key stays on your device.
- **No more stuck "Loading movies & shows".** The movies/series catalogue is cached, so it loads
  instantly on later launches instead of re-downloading every time — and the live preview no longer
  stutters while it loads.
- **Record to a USB / external drive.** Settings → Recordings → USB, pick a folder on a plugged-in
  drive, and recordings write straight there (no storage permission needed) and play back in-app.
- **Recordings screen overhaul.** Scheduled recordings show *when* they'll record and sit in their
  own "Scheduled" section; failed ones have a **Retry**; each is tagged to a profile; and bookings
  re-arm on every launch so a force-stop or app update can't quietly drop them.

## 0.9.0

- **Manage your channels from a phone or laptop.** A new "Manage on phone or laptop" screen in
  Settings shows a QR code and a link — open it on any device on your wifi and you get a proper web
  page to browse, **rename**, hide, favourite and **reorder** channels with a real keyboard and
  mouse. Changes apply to the TV instantly. Local-only: the TV is the server, nothing touches a
  cloud.
- **Recording keeps going when you leave the app or the box sleeps.** OpenTV can now ask Android to
  exempt it from battery optimisation (Settings → Recording → "Recording in the background"), which
  is what keeps recordings running in standby and when you switch away — swiping OpenTV out of
  recents no longer stops a recording either.
- **Rename channels.** Give a channel your own name; it sticks and survives a guide refresh (from
  the web manager for now).
- **Tidier update notes.** The "update available" prompt shows a clean summary of what's new instead
  of build metadata.

## 0.8.0

- **No more endless "Loading channels".** If a provider fails to load or comes back empty (a wrong
  login, a dead server), the guide now shows a clear error with **Retry** and **Provider setup**
  buttons instead of spinning forever.
- **Set up on a phone without the QR dance.** Installing on a phone now shows the login form
  directly so you can type your details in; the QR code is only offered on a TV, where typing is the
  painful part.
- **Paste into the setup fields.** The URL, username and password fields — and the on-screen
  keyboard — now have a **Paste** button, so a copied Xtream line drops straight in.
- **MPEG-TS or HLS, your choice.** Each Xtream provider now has a **Stream format** toggle. If your
  panel only serves `.ts` and channels wouldn't play, switch to MPEG-TS and they will. Defaults to
  HLS, so nothing changes unless you need it.
- **Parental controls scrolls.** The hidden-categories list is reachable to the bottom now, however
  many categories your provider has.
- **A real Channel Manager.** Settings → Channels is now a browsable, two-pane manager: pick a
  category on the left, see its channels on the right, hide or favourite each one — including
  hidden channels so you can bring them back. With more than one provider, a **source filter** keeps
  them separate instead of merged into one list.

## 0.3.0

- **Profiles.** Add local profiles (just a name — no accounts) from the person icon in the top
  menu, and switch between them. Each profile keeps its own resume points and watched state, so
  your half-watched film doesn't show as watched on someone else's profile. Existing resume points
  become the default "Me" profile's — nothing is lost.
- **Continue Watching.** Movies and Shows now open with a "Continue watching" shelf for the active
  profile — pick up your last film or episode where you left off, with a progress bar on each.
- **Local sync (no servers).** Settings → Sync copies your continue-watching between two OpenTV
  devices over your own wifi: one device shares behind a six-digit code, the other receives. It's
  local-only — nothing leaves the house — the household answer to the cloud sync that died with
  Viewella.

- **Proper player controls.** A single control bar slides up from the bottom with play/pause,
  rewind/forward (where the stream allows it), and pickers for **subtitles, audio track, quality
  and aspect ratio**. It hides after a few seconds and any remote button brings it back —
  nothing is ever left painted permanently over the picture.
- **Captions that actually show.** Subtitles are now chosen from the real tracks in the stream
  (and can be turned off), instead of a blind on/off that often left the renderer enabled but
  nothing on screen. Audio-track switching works the same way for multi-language streams.
- **Page through the guide.** Earlier / Now / Later buttons above the guide move the timeline
  forward and back, so you can see what's on later this evening with a remote.
- **Live preview in the guide.** The highlighted channel now plays, muted, inside the preview
  pane. It uses a single player that stops the instant you go full-screen or leave the guide —
  no second decoder — and it can be turned off in Display & playback for older boxes.
- **Channel manager.** Settings → Channels: search for a channel and hide it from the guide (or
  favourite it). Hidden channels drop out of Live TV and search but stay here, greyed, so you can
  bring them back. Hiding covers every quality variant of a channel at once.
- **Parental controls.** Set a 4-digit PIN and mark categories as hidden — they drop out of the
  guide, All channels and search until you unlock them (and with a PIN set, the parental screen
  itself is locked). In Settings → Parental controls.
- **Unified search with an on-screen keyboard.** Search now has its own screen with a d-pad
  keyboard (no more "type on your phone") and covers **channels, movies and shows** together, with
  live results as you type. Reachable from the top menu.
- **Loading indicators for Movies and Shows.** Both now show a spinner while the catalogue is
  syncing instead of a premature "nothing here".
- **Settings and Search in the top menu.** Both now live top-right, next to Live TV / Movies /
  Shows, instead of being buried behind the guide's icons.
- **A single Settings screen.** One Settings hub — Providers (add / remove / re-test sources), TV
  guide, Display & playback, Parental controls, and About (version, a manual update check,
  licence and links).
- **Dark / light / follow-system.** A new appearance setting. TV still defaults to dark; pick
  Dark or Light to force it on any device.
- **Quality picker only when there's a choice.** No more "Quality: Standard" on single-quality
  channels; the picker appears only when a channel actually has multiple qualities.
- New **Display & playback** settings screen (the sliders icon in the guide).

## 0.2.0

- **Guide detail pane** — highlighting a channel now shows its logo, what's on now with
  start/end times and a live progress bar, a synopsis when the guide carries one, and what's
  on next. Press to watch full-screen.
- **Live progress fill** on the current programme in the guide grid, and a focus highlight on
  the highlighted channel.
- **Fix: never asks for your provider again.** A cold launch could briefly decide "first run"
  before your saved sources had loaded and drop you on the setup screen — it now waits for the
  sources to load before deciding what to show.
- **Fix: no second video decoder on the guide**, which was locking up low-end boxes (a
  Chromecast could freeze). The detail pane is logo + guide info; the one decoder lives
  full-screen.

## 0.1.0

First public release.

- Xtream Codes and M3U/M3U8 sources
- Streaming XMLTV guide parsing with incremental, non-destructive sync
- Live TV playback on Media3/ExoPlayer with IPTV-tuned retry behaviour
- Movies and series catalogue
- Favourites, categories, search
- In-app self-update for sideloaded installs
- Single APK for Android TV, Fire TV, phones and tablets
