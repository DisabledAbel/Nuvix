# OpenTV source audit and Nuvix implementation boundary

Audit date: 2026-10-13. Upstream: <https://github.com/opentvproject/opentv>. Exact revision: **`43b95c05d8f6a042f51c0cff662ef80789ee7528`**, commit message “OpenTV v0.11.8 — edit saved providers, Stalker auto-retry on rejection, player controls clear the system bar off-TV”.

## Imported baseline

The initial Nuvix repository contained only `LICENSE`. The Android source, build files, tests, schema history, resources, and documentation were imported directly into the Nuvix root; the existing GPL license was retained verbatim. The baseline import commit contains 184 files including LICENSE. Seven upstream-tracked transient Kotlin compiler error logs are excluded from Nuvix version control. Git metadata, generated builds, local Android SDK configuration, and private signing material were not imported. The wrapper JAR, executable wrapper script, schema history, tests, translations, and upstream documentation were retained. [upstream-source.json](upstream-source.json) records original tracked-file SHA-256 values, including the original license.

This is a native **Kotlin/Jetpack Compose Android application**, not a Flutter or browser application. The baseline uses Media3 1.5.0, Room 2.6.1, OkHttp 4.12.0, Kotlin 2.0.21, AGP 8.7.3, Gradle 8.11.1, JDK 17, compile/target SDK 35, and minimum SDK 23. The inherited source namespace is `app.opentv`; retaining internal package names does not require retaining the public Android application ID.

## Existing behavior verified by reading executable source

| System | Source evidence | Existing behavior retained |
| --- | --- | --- |
| Playlist import | `data/parser/M3uParser.kt`, `data/repo/CatalogRepository.kt` | Line-based M3U/M3U8 parsing, `group-title`, logos, channel numbers, declared EPG URL; malformed unlabeled entries skipped, duplicates deduplicated by stable `tvg-id` or FNV-1a URL hash. Other playlist directives are ignored. |
| Provider APIs | `data/remote/XtreamApi.kt`, `data/remote/StalkerApi.kt` | Xtream live/VOD/series and defensive variable-type JSON parsing; Stalker/Ministra MAC handshake and per-play link resolution. Provider authorization remains required. |
| Local catalogue | `data/db/Daos.kt:ChannelDao.replaceCatalogue` | Transactional merge preserves favorites, hidden flags, sort order, custom names, and EPG overrides; stale channels are removed after a successful non-empty catalogue replacement. |
| Guide | `data/parser/XmltvParser.kt`, `data/repo/EpgRepository.kt` | Streaming XMLTV, UTC timestamps with explicit offsets, missing-stop fallback, gzip sniffing, merged feeds, normalized name matching, batch upserts, and persisted Room cache. The baseline commits partial batches during refresh; it never clears the old guide before download. |
| Playback | `player/PlayerController.kt`, `ui/player/PlayerScreen.kt` | One Media3 player per controller, channel-switch debounce, per-source User-Agent, transient HTTP retries, audio/subtitle selection, catch-up, aspect ratio, external-player handoff, and picture-in-picture where supported. |
| DVR and reminders | `recording/`, `reminders/`, `ui/recordings/` | Local/USB/SMB recordings, scheduled and series rules, catch-up metadata, reminders, and growing-recording playback. These were already present upstream. |
| Movies and shows | `ui/vod/`, `data/repo/CatalogRepository.kt` | Provider-backed movie/series details, seasons/episodes, favorites, and per-profile playback positions. |
| Existing device sync | `sync/SyncData.kt`, `SyncEngine.kt`, `SyncServer.kt`, `SyncClient.kt`, `NasSync.kt` | Portable version-3 bundles: additive profiles/favorites/hidden categories/NAS recordings, and newest-timestamp-wins resume points. LAN HTTP sharing uses a short-lived six-digit code; SMB sync uses a user-owned NAS. Local-only recordings do not travel. |
| Background refresh | `data/work/SyncWorker.kt` | Six-hour WorkManager catalogue/EPG refresh, network constraints, exponential failure backoff, and retained local data. |

This audit establishes that source implementations exist; it does not substitute for a physical-device playback, DRM, provider, recording, or launcher test. In particular, no test provider credentials or channels were added.

## Nuvix changes and boundary

Nuvix keeps the functioning native IPTV data/parser/player/recording systems and modifies their surrounding application shell. The public application identity, original navy/blue styling, navigation, home screen, optional launcher, installed-app discovery, optional Supabase infrastructure, buffering preferences, and update/release configuration are Nuvix changes. The original OpenTV copyright and GPL notices remain attached to inherited source.

The Android package identity is independent of OpenTV so installing Nuvix does not overwrite an OpenTV installation. Device-local state and the existing LAN/NAS sync stay in the local native application. Cloud synchronization must use an explicit safe-data mapping: the original LAN/NAS bundles identify media by stream URL and therefore must not be uploaded unchanged, because provider stream URLs can contain credentials.

The upstream release updater targets OpenTV and cannot be left as Nuvix's update source. Nuvix uses its own trusted release repository, package/version/signature checks, and signing configuration. Upstream screenshots are historical OpenTV documentation and must not be represented as screenshots of the new Nuvix interface.

## Documentation and test limits

Some inherited prose predates the code. For example, the original architecture document says favorites cannot follow devices, while `sync/SyncEngine.kt` implements that functionality; executable source controls the audit result. Nuvix documentation should describe the current implementation and record build/device validation separately.

The baseline contains seven JVM test files covering M3U parsing, XMLTV times/channel aliases, URL normalization, EPG matching, channel-name normalization, and pairing HTTP rules. Nuvix validation results are recorded in the PR and build documentation. A build alone does not establish that a provider stream, restricted home role, Fire TV behavior, or remote-control focus path has been tested.

## Playback and guide stabilization in Nuvix

Nuvix adds Automatic/Small/Medium/Large/Custom buffering with validated duration ordering, a 120-second maximum, and a device-aware byte target. `ConfigurableLoadControl` changes the live policy without replacing ExoPlayer, preserving its position, surface, and decoded state. The guide preview retains a shallow policy; watch-while-recording retains its eight-second cushion. Existing opt-in DVR history remains supported; the byte target is not a strict memory guarantee because DVR keeps already-played samples and a small forward floor must refill to avoid stalling.

Guide refresh now downloads decompressed XML to a temporary file capped at 256 MB and encrypted with a one-use AES-GCM key held only in memory, then replaces one feed's programmes, aliases, and refresh metadata in a single Room transaction. Parsing remains batched and streaming. HTTP, malformed/truncated XML, empty guide, cancellation, or write failure keeps the previous valid cache. Temporary downloads are removed. Real Room/MockWebServer tests cover failures after a flushed batch, in addition to pure buffering decisions and runtime policy changes.

Source setup validates HTTP/HTTPS addresses, forbids URL user information and multiline header injection, and validates the supplied authorized Stalker MAC shape. Legacy HTTP providers remain supported. Source test/catalogue diagnostics do not echo credential-bearing request URLs or raw exceptions.

## Source distribution

The corresponding Nuvix source lives in this repository at <https://github.com/DisabledAbel/Nuvix>; release source must match the released APK revision. See [../COPYING.md](../COPYING.md) and [../THIRD_PARTY_NOTICES.md](../THIRD_PARTY_NOTICES.md). No official affiliation with OpenTV or DIRECTV is implied.
