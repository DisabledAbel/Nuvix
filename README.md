# Nuvix

**Your television. Your way.**
Nuvix is a free, open-source IPTV player and optional home launcher for Android TV, Google TV, and compatible Fire TV devices. Bring your own authorized provider—no channels, accounts, playlists, or media are included.

An independent modified version of [OpenTV](https://github.com/opentvproject/opentv),  Nuvix is not affiliated with OpenTV or DIRECTV and uses no DIRECTV assets.

## Features

- Live TV, Standard/Compact/Expanded time-grid guides, movies, TV shows, and remote-friendly navigation.
- Guide filters, channel sorting, hour/day jumps, bounded program search, and program details
  with existing reminders and recording actions. See the [guide instructions](docs/automatic-epg.md#advanced-guide).
- Sports Hub with EPG-derived game grouping across providers, available-stream choices, local
  favorite teams/leagues, manual match corrections and game reminders. See [Sports Hub](docs/sports-hub.md).
- M3U/M3U8, Xtream Codes, authorized Stalker/MAC portals, and XMLTV.
- Favorites, continue watching, search, multiple providers, and LAN/NAS sync.
- Pre-recording Multi-View conflict warnings with explicit authorized provider choices, remote-friendly
  panel release and recording-priority deadline fallback. See [conflict review](docs/smart-multiview-dvr.md#pre-recording-multi-view-conflict-review).
- Real-time Stream Information with Compact/Detailed views, buffer health and safe errors. See [playback diagnostics](docs/playback-diagnostics.md).
- Adjustable buffering, catch-up where supported, PiP, recordings, and reminders.
- Persistent live TV across Home, Guide, Sports, and Search, with an optional current-channel preview. See [live playback](docs/live-playback.md).
- Smart Multi-View for two, three or four authorized live channels, with account-aware admission,
  balanced/focused layouts, remote audio selection, compact Guide browsing, Sports actions,
  local presets and review-before-restore recovery. See [Multi-View](docs/smart-multiview-dvr.md#multi-view-playback).
- Optional home launcher with installed-app shortcuts and customizable startup destinations.
- Automatic TVmaze show, season, episode, and cast metadata with clickable actor profiles; no key required. See [TV metadata](docs/tv-metadata.md).
- Optional Supabase sync for favorites/progress, channel organization, custom groups,
  home shelves, pinned apps, and compatible settings, with separate category controls.
- Encrypted local provider/catalog storage and protected credentials.
- In-app release checks, downloads, and Android-approved updates.

Recording, playback, and launcher support depend on your device and provider. Cloud sync is disabled by default; new categories require explicit opt-in. Provider credentials, stream URLs, device permissions, and playback tuning stay local.

## Screenshot

Nuvix Home on an Android TV emulator, with no provider configured.

![Nuvix Home](docs/screenshots/nuvix-home.png)

## Setup

- **Stalker portal identity:** Edit your authorized portal MAC and independently select Automatic or provider-supplied Serial Number, Device ID 1, Device ID 2 and Signature under **Settings → Providers → Advanced Configuration**. Saved locally in encrypted storage; the physical network MAC and account connection limits are preserved. See the [configuration and beta guide](docs/stalker-configuration.md).
- **Providers:** Open **Settings → Providers** or **Add provider** on Home. Add your authorized playlist, Xtream account, or assigned Stalker portal/MAC, then configure XMLTV if needed. Prefer HTTPS.
- **Connections:** Open a provider's **Connection Management** to set its local allowance,
  keep it Unknown, use a provider-reported allowance, or explicitly link shared-account
  playlists. Additional unknown-capacity connections require individual approval; usage on
  other devices remains uncertain. See [connection management and DVR](docs/smart-multiview-dvr.md).
- **Recordings:** Use **Recording options** for independent **Protect Recording** and
  **Lock Padding** controls and scheduled padding overrides. **Review recording conflicts**
  previews minimum unlocked-padding trims; **Recording history** explains decisions.
  Background capture depends on Android permission, device policy, provider and storage.
  Recording keeps your selected provider; automatic navigation to capture defaults Off,
  while an existing explicitly saved On preference is preserved.
- **Launcher:** Open **Settings → Home launcher** and enable the HOME entry. Use your device's default-home selector where supported. Restore the original default launcher before disabling the entry. See [device compatibility](docs/launcher-compatibility.md).
- **Live playback:** Under **Settings → Display & playback → Playback**, choose **Persistent Live Playback**, **Live Preview**, and **Continue Audio While Browsing**. Select the preview to return to fullscreen. Disable persistence on devices that struggle to retain video while browsing.
- **Multi-View:** Open **Multi-View** from live playback or use a Guide/Sports Multi-View action.
  Choose **Add Channel** or **Panel Actions**, use D-pad to focus and OK to select audio, and
  use **Edit Layout** to rearrange panels. **Settings → Display & playback → Multi-View**
  controls focus-follow audio (150 ms by default), fullscreen restoration and presentation.
  Presets preview the next 24 hours of DVR conflicts without connecting. Exclude panels, choose
  authorized alternate providers, change the primary, save explicitly or load edits once.
  Continue With Warning keeps later recording priority and warnings intact; loading rechecks
  current capacity. Predicted local availability is never a provider-side guarantee.
  See [upcoming DVR conflicts](docs/smart-multiview-dvr.md#upcoming-dvr-conflicts-in-preset-previews).
- **Guide:** Open **Guide settings** to choose the layout, default filter, sorting, timeline
  density, artwork, progress and position preferences. Guide controls apply without restarting;
  D-pad and Select open programs, and Back returns to the guide. Filters use your existing
  favorites, recent channels, providers and custom groups. They do not change channel data.
- **Sports:** Open **Sports**, select a game for all matching provider choices, or follow leagues
  and teams in their shelves. Sports settings control sorting, suggestions, reminders and cache.
  Schedules use your existing EPG; external scores are optional and no external sports API is enabled.
- **Cloud sync:** Apply the supplied migrations to your own project, sign in under **Settings → Supabase cloud sync**, then enable sync and choose categories on each TV. Use only a publishable/anon client key. See [cloud setup](docs/cloud-sync.md) and [privacy](docs/PRIVACY.md).
- **Updates:** Open **Settings → Nuvix updates**. Stable updates require valid release metadata and a matching signing certificate; Android requires installation approval. See [update troubleshooting](docs/update-troubleshooting.md).

## Project status

Nuvix is in development. Real-device testing is still needed for launcher selection, remote navigation, playback, database migration, cloud sync, and APK updates.

Smart Multi-View extends the merged account-management and background DVR foundation.
Additional streams depend on configured provider allowances, recording reservations and
available hardware decoders; four-channel support is not guaranteed on every device.
Unknown allowances require approval for each extra stream. Android TV, Google TV and Fire TV
playback, audio, remote, PiP and decoder acceptance still require physical-device verification.
See [implemented scope and limits](docs/smart-multiview-dvr.md).

See the [implementation report](docs/implementation-report.md), [roadmap](docs/ROADMAP.md), [architecture](docs/nuvix-architecture.md), and [contributing guide](CONTRIBUTING.md).

## Community

Join the [Nuvix Discord server](https://discord.gg/9tyVjwyNbq) to ask questions, share feedback, and discuss the project.

## License

**GPL-3.0.** Copyright (C) 2026 The OpenTV Contributors; Nuvix modifications Copyright (C) 2026 Nuvix Contributors.

Original license and source notices are preserved. See [LICENSE](LICENSE), [COPYING.md](COPYING.md), [third-party notices](THIRD_PARTY_NOTICES.md), and the [upstream audit](docs/upstream-audit.md). Release APKs include the corresponding tagged source, build scripts, and migrations.

### Customize home

Open **Home → Customize home** with the TV remote. Choose Classic (balanced), Compact
(smaller cards and spacing), or Cinema (larger artwork and hero). Select a section and
use **Move up / Move down / Hide / Restore**; hidden sections retain their position.
Custom channel groups share the same ordering using their existing UUIDs.

Movie, series and channel sizes, card corners and secondary metadata are adjustable.
Backgrounds include Default, three dark solid colors, Ocean/Dusk gradients and focused
movie/show artwork with a dark overlay. Featured content can use automatic live selection,
Continue Watching, recent/favorite channels, or be disabled. Empty selections retain
navigation actions. D-pad changes focus, Select applies, Back returns one level, and
closing customization returns focus to its button. Settings save immediately and work offline.

Defaults preserve the existing Classic design: medium cards, soft corners, metadata,
Default background, automatic hero, and all sections shown. **Reset order** only resets
positions; **Restore home defaults** also resets presentation and visibility. App favorites,
custom group contents, startup settings and playback are preserved. Existing visibility
choices migrate without being cleared. Optional home-preferences sync requires the new
[forward migration](docs/cloud-sync.md); see [testing](docs/TESTING.md#advanced-home-customization)
for automated coverage and outstanding hardware checks.


## Active DVR duration controls

If a custom end passes while you review or save it, review again and choose **Confirm Stop
Recording**. Ordinary duration confirmation cannot authorize an immediate stop.

Active recordings now offer a shared Recording Duration editor with 5/10/15/30/60-minute
additions, custom date/time, shortening, restore and explicit stop confirmation. Revision-checked
Room updates and a live deadline watcher preserve the existing capture connection and file.
Conflict review reuses DVR priority and independent-provider alternatives for scheduled bookings;
protection, locked padding, two/five-minute defaults and Multi-View admission remain authoritative.
See [duration behavior, architecture and device checklist](docs/recording-duration.md).

Automated tests cover arithmetic, Room commit/deadline races, duplicate/stale confirmations,
transport continuity, conflict/preset projection and Compose remote action semantics. Physical
Android TV/Google TV/Fire TV validation remains required, including date/hour/minute steppers,
USB/NAS behavior, alarms and process interruption. Automatic sports extensions remain disabled.

### DVR late-start controls

Settings → Recording → Late Start defaults to **Record Remaining**. Choose **Skip If Late**
(the existing inclusive five-second program-start grace) or **Minimum Time Remaining**
(ten minutes initially; presets/custom D-pad selector). Guide scheduling, recording options
and series rules support explicit overrides or live default inheritance. Late media is Partial;
missing only start padding is not. Skips stay visible as Missed with reasons and no empty output.
See [timing, inheritance, account/Multi-View behavior and Google TV checklist](docs/recording-late-start.md).
