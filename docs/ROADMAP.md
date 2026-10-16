# Nuvix development roadmap

The initial source import preserves OpenTV's functional native systems. The Nuvix foundation adds branding/navigation, home/provider shelves, installed apps and optional launcher, opt-in cloud synchronization, configurable buffering, cache protection, and verified release/update infrastructure. See implementation-report.md for earlier foundation checks and [cloud sync](cloud-sync.md) for current scope.

The next phase is device acceptance testing: Android TV, Google TV and Fire TV D-pad/Back/long-press flows, cold starts, launchers that reject home replacement, authorized stream playback, EPG refresh failure, PiP, recording lifecycle, encrypted persistence and upgrades. Test installation cancellation and failed signatures on hardware without publishing invalid APKs.

Advanced cloud sync now includes typed channel organization, UUID custom groups, home shelf visibility, ordered app favorites, compatible settings and an additional favorite-removal opt-in. New categories default off. The next cloud milestone is two-device acceptance with a disposable Supabase project: different providers, missing apps, offline concurrent edits, deleted groups, category pauses, expired sessions and account switches. Follow the [test procedure](TESTING.md#advanced-cloud-sync); do not treat JVM/SQL coverage as hardware verification.

Advanced home customization now provides remote shelf ordering, three layouts, card controls, backgrounds and featured preferences. Hardware acceptance (including low-end artwork memory and D-pad focus at large text sizes) remains the next milestone. Additional compatible settings and reviewed device authorization remain future work. Add QR/device authorization only with an authenticated, reviewed browser pairing architecture; preserve email-code authentication until then.

Shared live playback and a current-channel preview now span main navigation; see [live playback](live-playback.md) for ownership, preferences, and fallback behavior. Device acceptance must verify surface transfers, decoder recovery, PiP, audio focus, and low-memory behavior with authorized streams on Android TV, Google TV, and Fire TV hardware.

Further playback work includes update transfer range resume, broader provider sports-event metadata, low-memory performance profiling, and a pluggable playback-engine contract. Broaden automated TV UI tests alongside each feature.

Advanced playback diagnostics now observe the existing player with Compact/Detailed views,
local settings, safe errors and bounded memory-only history. See [diagnostics](playback-diagnostics.md).
The next acceptance milestone is real Android TV, Google TV and Fire TV verification at
720p/1080p/4K, text scaling, repeated channel changes, PiP, persistent navigation and
local/NAS/growing recordings; automated fake/Robolectric tests cannot certify those devices.
