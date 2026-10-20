# Nuvix development roadmap

Smart Multi-View now extends the merged account-aware connection and background DVR
foundation with two-, three- and four-channel playback, balanced/focused layouts, remote
editing, 150 ms focus-follow or OK-only audio, compact Guide browsing, Sports Hub actions,
local presets, fullscreen transitions and review-before-restore recovery. The existing live
owner and connection allocator remain shared with optional previews and DVR. See the
[feature guide and architecture](smart-multiview-dvr.md).

The next Multi-View milestone is physical-device acceptance on Android TV, Google TV and
Fire TV, including a memory-constrained model: progressive decoder allocation, mixed
codecs/resolutions, surface transfers, remote focus, audio-focus interruptions, PiP and
simultaneous DVR. MediaCodec declarations and fake-player tests do not certify concurrent
playback. No four-panel device guarantee or uninterrupted unattended recording guarantee
is made. Retain the existing CI suite and record model/OS/stream format/provider allowance
for each hardware check.

Pre-recording Multi-View conflict review now includes configurable padded-start warnings, an
explicit authorized independent-provider picker, panel release, durable Keep Watching and
recording-priority deadline fallback. Hardware acceptance remains required for remote focus,
notification delivery, provider/decoder behavior and absent-user recording starts. See the
[conflict review guide](smart-multiview-dvr.md#pre-recording-multi-view-conflict-review).

Saved preset previews now evaluate the next 24 hours of effective recording windows with shared
account policies, explicit provider alternatives, one-time edits and confirmation/startup
revalidation. Continue With Warning preserves the later pre-recording review and unattended
priority. See [preset planning](smart-multiview-dvr.md#upcoming-dvr-conflicts-in-preset-previews).
Physical-TV validation, structured broadcast language preferences and a per-recording late-start
setting remain outstanding; the existing fixed grace policy is unchanged.

Further DVR work remains separate: explicit alternative-provider recording resolution,
reliable-source sports extensions, external-storage capacity
estimates and opt-in protection-aware retention. Existing Protect Recording, Lock Padding,
two/five-minute defaults, partial recovery and recording-priority policy remain intact.

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


## Active DVR duration controls

Duration confirmation now checks elapsed ends again immediately before committing. Stop consent
must belong to the displayed review; an end passing during validation requires a fresh explicit
stop review. Deterministic engine tests cover both review-time and pre-commit deadline races.

Active recordings now offer a shared Recording Duration editor with 5/10/15/30/60-minute
additions, custom date/time, shortening, restore and explicit stop confirmation. Revision-checked
Room updates and a live deadline watcher preserve the existing capture connection and file.
Conflict review reuses DVR priority and independent-provider alternatives for scheduled bookings;
protection, locked padding, two/five-minute defaults and Multi-View admission remain authoritative.
See [duration behavior, architecture and device checklist](recording-duration.md).

Automated tests cover arithmetic, Room commit/deadline races, duplicate/stale confirmations,
transport continuity, conflict/preset projection and Compose remote action semantics. Physical
Android TV/Google TV/Fire TV validation remains required, including date/hour/minute steppers,
USB/NAS behavior, alarms and process interruption. Automatic sports extensions remain disabled.
