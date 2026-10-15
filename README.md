# Nuvix

**Your television. Your way.**

Nuvix is a free, open-source IPTV player and optional home launcher for Android TV, Google TV, and compatible Fire TV devices. Bring your own authorized provider—no channels, accounts, playlists, or media are included.

An independent modified version of [OpenTV](https://github.com/opentvproject/opentv),  Nuvix is not affiliated with OpenTV or DIRECTV and uses no DIRECTV assets.

## Features

- Live TV, time-grid guide, movies, TV shows, and remote-friendly navigation.
- M3U/M3U8, Xtream Codes, authorized Stalker/MAC portals, and XMLTV.
- Favorites, continue watching, search, multiple providers, and LAN/NAS sync.
- Adjustable buffering, catch-up where supported, PiP, recordings, and reminders.
- Persistent live TV across Home, Guide, Sports, and Search, with an optional current-channel preview. See [live playback](docs/live-playback.md).
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

- **Providers:** Open **Settings → Providers** or **Add provider** on Home. Add your authorized playlist, Xtream account, or assigned Stalker portal/MAC, then configure XMLTV if needed. Prefer HTTPS.
- **Launcher:** Open **Settings → Home launcher** and enable the HOME entry. Use your device's default-home selector where supported. Restore the original default launcher before disabling the entry. See [device compatibility](docs/launcher-compatibility.md).
- **Live playback:** Under **Settings → Display & playback → Playback**, choose **Persistent Live Playback**, **Live Preview**, and **Continue Audio While Browsing**. Select the preview to return to fullscreen. Disable persistence on devices that struggle to retain video while browsing.
- **Cloud sync:** Apply the supplied migrations to your own project, sign in under **Settings → Supabase cloud sync**, then enable sync and choose categories on each TV. Use only a publishable/anon client key. See [cloud setup](docs/cloud-sync.md) and [privacy](docs/PRIVACY.md).
- **Updates:** Open **Settings → Nuvix updates**. Stable updates require valid release metadata and a matching signing certificate; Android requires installation approval. See [update troubleshooting](docs/update-troubleshooting.md).

## Project status

Nuvix is in development. Real-device testing is still needed for launcher selection, remote navigation, playback, database migration, cloud sync, and APK updates.

See the [implementation report](docs/implementation-report.md), [roadmap](docs/ROADMAP.md), [architecture](docs/nuvix-architecture.md), and [contributing guide](CONTRIBUTING.md).

## Community

Join the [Nuvix Discord server](https://discord.gg/9tyVjwyNbq) to ask questions, share feedback, and discuss the project.

## License

**GPL-3.0.** Copyright (C) 2026 The OpenTV Contributors; Nuvix modifications Copyright (C) 2026 Nuvix Contributors.

Original license and source notices are preserved. See [LICENSE](LICENSE), [COPYING.md](COPYING.md), [third-party notices](THIRD_PARTY_NOTICES.md), and the [upstream audit](docs/upstream-audit.md). Release APKs include the corresponding tagged source, build scripts, and migrations.
