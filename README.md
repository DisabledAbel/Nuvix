# Nuvix

**Your television. Your way.** Nuvix is a free, open-source IPTV player and optional Android TV home launcher for Android TV, Google TV, and compatible Fire TV devices. Users supply their own authorized providers. Nuvix includes no channels, IPTV accounts, playlists, or media.

Nuvix is an **independent modified version of [OpenTV](https://github.com/opentvproject/opentv)**, based on commit `43b95c05d8f6a042f51c0cff662ef80789ee7528`. It is not affiliated with OpenTV or DIRECTV. Its original navy/blue identity and television navigation use no DIRECTV assets. This repository is independent; GitHub's Fork feature was not used.

## Implemented

- Native Kotlin/Jetpack Compose app with Media3 ExoPlayer, Room local storage, and lazy guide/poster rendering. Existing OpenTV playback, source setup, recordings, reminders, profiles, and LAN/NAS sync are retained.
- Keystore-wrapped SQLCipher protection for the complete local provider/catalog database, encrypted NAS/API/add-on secret preferences, and explicit backup exclusions. Key loss retains data and shows recovery controls.
- Nuvix identity, application ID `com.disabledabel.nuvix`, version `0.1.0` / version code `21`, original icon/banner/splash, and About attribution. Kotlin source packages remain `app.opentv` to keep the upstream port reviewable; Android namespace and installed identity are Nuvix.
- Expandable, scrolling remote navigation: **Home, Live TV, Guide, Movies, TV Shows, Sports, Apps, Library, Settings**. Visible focus indicators, D-pad/Back support, and existing player remote controls.
- Home shelves for recently watched/favorite channels, continue watching, provider movie recommendations, recently added shows, and actually installed pinned apps. Empty states work without a provider. A guide preview is available when enabled; home artwork is not represented as a playing video.
- M3U/M3U8, Xtream Codes, XMLTV, multiple sources, channel search/groups/favorites, existing authorized Stalker/MAC support, catch-up where available, PiP, aspect ratio and external playback controls.
- Separate Live TV list and horizontal time-grid Guide. Atomic EPG replacement preserves valid cached guide data after a failed refresh. Recording/reminder features remain subject to device capabilities and provider authorization.
- Automatic/small/medium/large/custom buffering with validated ranges. Buffer policy changes update the running player without rebuilding it.
- Reversible, disabled-by-default HOME activity alias. Default-home selection and device compatibility/status controls, startup destinations, device-settings access, and normal app mode on restricted devices.
- Actual installed-app discovery via narrow package queries; icons, launch, favorites, ordering, long-press menus, and app-information shortcuts. No streaming services are hardcoded as installed.
- Optional Supabase email-code authentication and local-first favorites/resume sync. Owner-only RLS, encrypted session/outbox storage, offline retry, and guarded record merging. Cloud sync starts disabled and excludes provider credentials and stream URLs.
- Trusted GitHub release discovery with numeric version codes, release notes, optional automatic checks/notifications/background downloads, progress/cancellation, package/hash/signer/downgrade checks, and interactive Android PackageInstaller installation.
- CI with Kotlin compilation, unit tests, lint, debug APK artifacts; manually triggered builds; signed tag releases with verified APK identity, pinned signing fingerprint, `update.json`, checksums, and corresponding GPL source.

## Screenshot

Actual Nuvix Home captured from the Android TV API 28 emulator, with no provider configured.

![Nuvix Home](docs/screenshots/nuvix-home.png)

## Install and build

The current development APK is a **debug build**, identified as `com.disabledabel.nuvix.debug`; it is not a production signed release. Production updates target the stable application ID and require the maintainer's consistent signing certificate. There is no published production release until signing and release configuration are supplied.

Open the project in Android Studio with JDK 17 and Android SDK 35, or run:

```sh
./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

See [building](docs/building.md), [release signing](docs/signing.md), and [release publication](docs/RELEASING.md). Debug and release installations have separate data. Do not publish debug-signed APKs as production updates.

## Provider setup

Open **Settings → Providers** or select **Add provider** on Home. Supply an authorized M3U/M3U8 playlist URL, Xtream host/user/password, or your legitimately assigned Stalker portal/MAC. Test the source and save it. Configure the provider's XMLTV address in source/guide settings where needed.

Prefer HTTPS. Legacy HTTP is supported, but it sends provider authentication unencrypted on the network. Nuvix does not bypass authentication, DRM, subscriptions, or geographic/provider restrictions. Optional third-party metadata and guide sources are separate from IPTV playback.

## Launcher setup

Open **Settings → Home launcher**, enable the Nuvix HOME entry, and use the supported system default-home selector if available. Check status before expecting the Home button to change. Select Home, Live TV, Guide, Last Watched Channel, or Apps as the startup destination. Device settings remain reachable.

Google TV and Fire TV firmware may prohibit third-party default launchers. Normal-app functionality remains available. Nuvix never disables the original launcher, requires root, or uses accessibility services to capture Home. Restore the original default launcher in system settings before disabling Nuvix's HOME entry. See [device compatibility](docs/launcher-compatibility.md).

## Optional cloud sync

Cloud sync requires your Supabase project. Apply the migration in `supabase/migrations`, enable email OTP with a template containing `{{ .Token }}`, and enter the project HTTPS URL and **publishable/anon client key** in **Settings → Supabase cloud sync**. Enter the email verification code on the TV, then explicitly enable sync.

No service-role key belongs in the app. Favorites merge additively; local removals remain local and are not pushed as destructive deletions. Resume positions use guarded timestamps. The first version synchronizes channel/movie/series favorites and movie/episode progress; app settings, custom groups, installed-app favorites, and launcher preferences are currently local. Existing LAN/NAS sync is preserved. See [cloud setup and conflict semantics](docs/cloud-sync.md) and [privacy](docs/PRIVACY.md).

## Updates and releases

**Settings → Nuvix updates** provides manual checks and optional background preferences. Only stable releases from `DisabledAbel/Nuvix` with valid `update.json` metadata are accepted. The installed signing certificate is the trust anchor. Android controls install approval and installation permission; Nuvix never promises silent updates. Downloads and checks do not retune the player.

A production release requires four signing secrets and the pinned public `SIGNING_CERT_SHA256` repository variable. Losing the key prevents normal upgrades; retain a secure backup. See [signing](docs/signing.md) and [update troubleshooting](docs/update-troubleshooting.md).

## Validation and remaining work

See [implementation report](docs/implementation-report.md) for exact executed checks and build results. Source-level tests do not establish compatibility with every TV firmware. Real Android TV/Google TV/Fire TV testing is still required for default-home selection, D-pad focus flows, authorized live playback, PiP, process-death behavior, encrypted database migration, and interactive APK installation. Supabase needs a configured real project for device-to-device acceptance testing.

Further work includes translating the new English Nuvix surfaces and remaining inherited English fallback strings, richer provider-supplied sports event metadata, broader cloud preference sync, resume-capable update transfers, continuous live-preview playback between screens, and a larger TV-device test matrix. No unfinished feature is presented as complete. Historical screenshots under `docs/screenshots` are preserved OpenTV assets, not screenshots of the new Nuvix interface.

## Contributing and license

Read [CONTRIBUTING.md](CONTRIBUTING.md), [architecture](docs/nuvix-architecture.md), [upstream audit](docs/upstream-audit.md), and [roadmap](docs/ROADMAP.md).

Nuvix and its OpenTV-derived source are licensed **GPL-3.0-or-later**. Copyright (C) 2026 The OpenTV Contributors; Nuvix modifications Copyright (C) 2026 Nuvix Contributors. The original [LICENSE](LICENSE) and source copyright notices are preserved. [COPYING.md](COPYING.md) explains corresponding-source distribution; [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md) lists dependency licenses. Release APKs are accompanied by the exact tagged source archive, build scripts, and migrations.
