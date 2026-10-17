# Nuvix development roadmap

Smart Multi-View and advanced background DVR are staged work. The current draft establishes
shared account allocation and extends the existing DVR service; it does not ship Multi-View
panels or claim complete acceptance. See the [architecture audit, remaining criteria and
hardware checklist](smart-multiview-dvr.md). Subsequent stages must deliver real two-channel
playback before expanding to three/four views, 150 ms audio selection, fullscreen handoff,
Guide/Sports integration, safe presets/recovery and recording conflict review. Preserve the
existing CI suite and distinguish fake-provider policy tests from physical-TV verification.

The initial source import preserves OpenTV's functional native systems. The Nuvix foundation adds branding/navigation, home/provider shelves, installed apps and optional launcher, opt-in cloud synchronization, configurable buffering, cache protection, and verified release/update infrastructure. See implementation-report.md for earlier foundation checks and [cloud sync](cloud-sync.md) for current scope.

The next phase is device acceptance testing: Android TV, Google TV and Fire TV D-pad/Back/long-press flows, cold starts, launchers that reject home replacement, authorized stream playback, EPG refresh failure, PiP, recording lifecycle, encrypted persistence and upgrades. Test installation cancellation and failed signatures on hardware without publishing invalid APKs.

Advanced cloud sync now includes typed channel organization, UUID custom groups, home shelf visibility, ordered app favorites, compatible settings and an additional favorite-removal opt-in. New categories default off. The next cloud milestone is two-device acceptance with a disposable Supabase project: different providers, missing apps, offline concurrent edits, deleted groups, category pauses, expired sessions and account switches. Follow the [test procedure](TESTING.md#advanced-cloud-sync); do not treat JVM/SQL coverage as hardware verification.

Advanced home customization now provides remote shelf ordering, three layouts, card controls, backgrounds and featured preferences. Hardware acceptance (including low-end artwork memory and D-pad focus at large text sizes) remains the next milestone. Additional compatible settings and reviewed device authorization remain future work. Add QR/device authorization only with an authenticated, reviewed browser pairing architecture; preserve email-code authentication until then.

Shared live playback and a current-channel preview now span main navigation; see [live playback](live-playback.md) for ownership, preferences, and fallback behavior. Device acceptance must verify surface transfers, decoder recovery, PiP, audio focus, and low-memory behavior with authorized streams on Android TV, Google TV, and Fire TV hardware.

Advanced EPG adds three guide layouts, local presentation preferences, filters/sorting over
existing channel organization, timeline jumps, bounded program search and program details.
See [guide instructions](automatic-epg.md#advanced-guide) and the
[acceptance checklist](TESTING.md#advanced-epg). The next milestone is device verification of
rapid D-pad input, focus restoration, large text, artwork memory, single-connection recording
handoff and persistent playback while browsing a large authorized guide. Automated bounds and
synthetic data establish regression coverage; they do not establish frame rates or decoder
performance on low-end televisions. Sports metadata still depends on feed quality; permitted verified schedule adapters remain future work.

Further playback work includes update transfer range resume, broader provider sports-event metadata, low-memory performance profiling, and a pluggable playback-engine contract. Broaden automated TV UI tests alongside each feature.

Advanced playback diagnostics now observe the existing player with Compact/Detailed views,
local settings, safe errors and bounded memory-only history. See [diagnostics](playback-diagnostics.md).
The next acceptance milestone is real Android TV, Google TV and Fire TV verification at
720p/1080p/4K, text scaling, repeated channel changes, PiP, persistent navigation and
local/NAS/growing recordings; automated fake/Robolectric tests cannot certify those devices.


Sports Hub now organizes EPG-derived events and provider choices with explicit confidence, local
team/league/sport favorites, corrections, preferred broadcasts, search and game reminders in the
existing Sports destination. See [Sports Hub](sports-hub.md) and [device checks](TESTING.md#sports-hub).
Future schedule adapters require verified distribution terms, attribution, credentials/rate-limit
handling and source-specific update integration; none is enabled by this change. Next acceptance
is real remote/focus restoration and large-catalog performance on low-memory TVs, with authorized
multi-provider playback and recording conflicts. Sports cloud sync remains a separate opt-in protocol
extension for a future change; no provider data or local correction identifiers are exported.
