# Smart Multi-View and background DVR

This document records the architecture review for the Smart Multi-View/background DVR
work. Device-dependent behavior must be verified on actual Android TV, Google TV and
Fire TV hardware. A local reservation is Nuvix's allocation decision; it cannot establish
how many streams another device is using or change a provider subscription.

## Draft foundation

The current change extends the existing live owner, Guide preview and recording service.
It does not provide a Multi-View screen. The full-feature list below remains outstanding.

`AccountConnectionManager` owns one application-scoped `ConnectionAllocator`. Pending,
Active and Releasing reservations all count against the account limit. Releasing the same
token twice is harmless; a callback holding an old generation cannot release a newer
reservation. No runtime reservations or consent tokens are restored from preferences.

Account identities are opaque digests of exact configured identity: canonical provider
endpoint plus Xtream username, Stalker portal plus MAC, or the exact M3U playlist URL.
Password rotation does not create a fresh Xtream allowance. Distinct playlist URLs are
not assumed to be independent subscriptions; explicitly associate them with the same
account when they share an allowance. Associations can change only when both affected
pools have no reservations. Linking merges the full account association, including other
playlists already associated with it and later imports of the same exact account identity.
There is no fuzzy host-based account linking.

An account can use provider-reported maximum connections or an explicit positive local
maximum. A missing or zero provider maximum means Unknown. Explicitly choosing Unknown
also preserves that state. The first local connection can start; each additional connection
requires its own approval. Consent is bound to the exact source, session generation and
purpose and cannot be reused for another attempt. Automatic optional previews do not
request consent or create an extra unknown-capacity connection. Provider-reported active
counts are observations that may include this device and become stale; they are never
subtracted as proof of guaranteed spare capacity. A generic timeout, decoder error or
HTTP 403 does not increment the explicit connection-limit rejection count.

Open **Settings → Providers → Connection Management** for a provider. Choose **Set limit**,
**Unknown**, or **Use provider-reported allowance**. The screen shows locally reserved
connections, provider usage observations when available, and known explicit limit errors.
Shared-account association requires explicit confirmation and idle affected pools. Configuring
a number changes local admission only; it does not buy or grant provider-side connections.

Actual single-channel changes use safe break-before-make: resolve the candidate, reject
known unavailable allocation where possible, then close the old decoder before opening its
replacement. Unknown capacity still prompts if another same-account reservation remains.
Fullscreen/preview/PiP presentation changes keep the existing live player. Seamless
make-before-break switching is not implemented in this foundation.

Account policies and manual associations persist locally as opaque account digests,
digest-to-digest aliases and numeric settings. They do not contain raw credentials, URLs or active player state,
and are not exported through cloud preferences.

Room schema 14 adds programme airtime, per-recording padding, Protect Recording and Lock
Padding columns, plus a `recording_events` table. Migration 13→14 is additive: it retains
existing recordings, schedules, provider configuration and series rules. Legacy padded
windows remain intact because their original programme airtime is unknown. New defaults
are two minutes early and five minutes late; existing explicitly stored padding preferences
remain in effect.

The capture service now declares Android 14+ `specialUse` with an explicit DVR subtype,
instead of claiming that byte capture is `mediaPlayback`. Distribution through a store
requires its foreground-service declaration/review; this code does not establish store
approval. `specialUse` is not a background-start exemption. Readiness observes exact-alarm
access, notification access and battery policy, and always explains that unattended capture
can be stopped or rejected. No powered-off-device recording is promised.

### Recording controls and conflict policy

In **Recordings**, open **Recording options** on a booking or captured item:

- **Protect Recording** gives that recording preference in unavoidable account conflicts.
  It is independent of the padding lock. There are no High/Normal/Low priority controls.
- **Lock Padding** prevents automatic trimming of that recording's padding. It cannot
  guarantee capture through denied capacity, storage failure or power loss.
- Before capture begins, bookings with known programme airtime allow start padding from
  0–30 minutes and end padding from 0–60 minutes. Legacy rows without airtime keep their
  existing windows. Active captures cannot change their committed end time in this draft.

**Review recording conflicts** shows account-aware conflicts and proposed minimum optional
padding adjustments. **Apply padding adjustments** explicitly accepts the current plan.
At the deadline, the disclosed unattended fallback makes a decision without waiting for
a dialog: preserve programme airtime before optional padding, never trim locked padding,
prefer protected recordings, then manual bookings over automatic series recordings,
then preserve already-active capture, then use scheduled start and stable recording ID.
Higher-priority conflicts can interrupt a lower-priority active recording. Running capture
windows are not automatically trimmed; a later extension must add a synchronized durable
end-time handoff before that is safe.

Unknown capacity remains unresolved until account configuration or request-specific
consent establishes local admission; it is not assumed to be one connection. An unattended
extra capture is not silently approved. The current review screen does not offer an
alternative-provider picker or restore displaced viewing automatically.
The selected Guide recording source remains authoritative; the recorder does not silently
substitute a similarly named channel from another provider.

Open **Recording history** to read persisted scheduling, preference, adjustment, conflict
and capture decisions. Messages exclude credentials and stream URLs. Completed and
recoverable Partial items remain playable; Missed/Failed explain unsuccessful capture.
No automatic retention/deletion job is installed. Explicit user deletion remains available,
including for a protected item; protection is not an undeletable-file lock.

### Capture reliability and storage scope

The existing foreground service still owns all captures. Durable Scheduled→Preparing
claims and a capture registry prevent duplicate alarm delivery from creating duplicate
jobs. Finalizing closes output before releasing account capacity. Late starts capture the
remaining window and report Partial; an already-expired window is Missed. Process-start
reconciliation preserves known captured bytes and file locators rather than reporting an
orphaned Recording row as healthy. Stopping a bounded programme early reports Partial;
an ordinary completed manual unbounded recording can finish when explicitly stopped.
Capture continues in the background where Android permits. Automatic navigation to a
scheduled recording now defaults to Off, preserving any explicitly stored On preference.
This avoids pulling viewers away from unrelated playback simply because capture starts.

Each file has a random unique suffix. Internal creation and SMB capture reject existing
destinations. Retry uses a new destination and keeps prior nonempty content as a Partial
library item. Local preflight checks 256 MiB of headroom per current capture, then checks
remaining local space about once a second while copying. This is a headroom guard, not a
complete-programme size estimate. Free capacity on SAF USB and SMB destinations is unknown;
write or finalization failures terminate capture and retain recoverable bytes. Removed
external storage must be verified on hardware. No shortage causes automatic deletion.

Network reconnects are bounded when attempts deliver no data. Storage-write failure is
terminal rather than reopening the provider repeatedly. Capture validates a direct MPEG-TS
prefix across fragmented reads and rejects HLS/DASH, HTML, JSON and other non-TS responses;
the recorder does not concatenate playlist segments. Only Xtream's transport-stream
variant receives the `.m3u8`→`.ts` conversion. Generic playlist URLs are not rewritten.
Fresh Stalker capture links, adaptive HLS/DRM ingest and shared viewing/capture ingest remain
unsupported. Existing encrypted local recording rows retain their stream snapshots; new
history and account settings do not log or persist credential-bearing URLs.

Open **Settings → Recording → Unattended recording readiness** for current alarm,
notification and battery observations and links to manage missing system access. Boot and
package replacement rearm bookings; they do not bypass foreground-start restrictions.
Denied starts have an explicit Failed reason, and expired bookings become Missed.
The app requests user-granted `SCHEDULE_EXACT_ALARM` access; the restricted automatically
granted `USE_EXACT_ALARM` permission is no longer declared.

## Architecture inspected before implementation

The baseline was `main` at `88aeb3a2056dc07cf1d15ab2155fca79daaccf19`.
The repository's issue/open-PR inventory returned no open items during this review.
Relevant prior work includes [persistent live playback #27](https://github.com/DisabledAbel/Nuvix/pull/27)
and [passive playback diagnostics #32](https://github.com/DisabledAbel/Nuvix/pull/32);
their existing ownership and privacy contracts remain the integration baseline.

| Existing component | Ownership and contract to preserve |
| --- | --- |
| `LivePlaybackSession` | Activity `AndroidViewModel`; application-context controller, foreground/PiP policy, browsing audio, sleep timer and recording observation. Navigation borrows playback. |
| `LivePlaybackCoordinator` | Main-thread session policy; lazy decoder, generation-checked asynchronous channel resolution, same-channel no-op, bounded controller retry, explicit release. |
| `PlayerController` | Media3 player, HTTP loading, live buffering, track selection, audio focus, debounce, bounded retries and passive diagnostics. |
| `PlayerSurfaceOwner` | Exclusive surface lease; detach outgoing view before attaching replacement, ignore late disposal, reject unsafe attachment after a detach failure. |
| `PlayerScreen` / `LivePlaybackPreview` | Borrow the retained player instead of allocating a decoder during navigation. |
| `RecordingEngine` | Existing immediate/program/series booking and alarm rearming. The foundation removes its old heuristic provider fallback to preserve the selected source. |
| `RecordingService` | Existing foreground capture service; one coroutine/HTTP call per recording, aggregate notification and partial wake lock. |
| `RecordingScheduler` / `RecordingAlarmReceiver` | Existing exact/inexact alarm infrastructure, prewarning, recording start and boot rearming. |
| `RecordingStorage` | Existing internal-file, USB SAF and SMB destination abstraction; growing internal-file playback remains a separate consumer of recorded bytes. |
| Room database | Encrypted local database, baseline schema 13 with explicit forward migrations; recording/series data already persisted. |
| `AppSettings` | Local preferences and `StateFlow`s, including padding, recording target and playback preferences. |
| Sports Hub | Cached event/channel associations; `SportsStream.available` and `SportsViewModel.play` enforce a confirmed local association before playback. |
| Guide | Retains the current live session while browsing. Highlighting rows updates information without tuning the retained channel. Optional standalone preview has separate ownership and is suppressed when live playback is active. |
| `PlaybackDiagnostics` | One observer per existing controller; safe error classification, monotonic stall counters, bounded memory-only history and visible-panel sampling. |

There was no Multi-View player manager, provider-account connection pool, saved Multi-View
layout or recording activity-history table at this baseline. There was no existing DVR
documentation file; the baseline recording behavior appeared in live-playback, architecture,
settings and testing documentation.

### Incomplete baseline behavior identified

- Recording conflicts used `sourceId` as a single occupied connection. Two sources belonging
  to one subscription could evade this check, while a subscription allowing several streams
  was unnecessarily blocked. Xtream account testing displayed `max_connections` but did not
  retain it as an allocation policy.
- The DVR service already accepted several captures. The missing part was safe shared
  allocation with viewers and durable, idempotent capture ownership, rather than another
  recording service.
- Recording states were limited to Scheduled, Recording, Completed and Failed. Nonempty
  early stops or exhausted reconnects could be reported as Completed; interrupted rows were
  simply marked Failed on startup. Capture insertion and duplicate-start checks could race.
- Scheduled starts used stored stream URLs. Stalker links were not resolved for recording;
  a generic `.m3u8` to `.ts` rewrite also incorrectly assumed that arbitrary playlist endpoints
  offered a continuous MPEG-TS variant.
- Scheduling could silently substitute another provider using a channel-name grouping. The
  foundation keeps the selected authorized source; an explicit alternative picker is future work.
- Capture filenames had minute precision, and reopening a destination could overwrite an
  existing recording. Capacity/headroom monitoring, external-drive interruption handling,
  durable history, protection and padding locks were missing.
- Default start padding was one minute; end padding was five. Per-recording locks,
  protection and a deterministic account-aware conflict resolver were absent.
- The recording settings readiness indicator checked battery-optimization exemption only.
  That does not establish exact-alarm access, notification permission, background service
  eligibility, storage readiness or reliable unattended execution.
- Diagnostics counted rebuffer events but did not provide a Multi-View alternative-provider
  suggestion policy. Decoder errors and generic HTTP 403 responses do not prove an account
  connection-limit rejection.

## Integration boundaries

Keep provider account, reservation, player session and panel as separate identities. Account
capacity belongs to an explicit local account association, not a screen or source URL.
Unknown capacity must remain unknown. Additional connections require request-specific
approval, and replaying a previous approval must not authorize another request.

Reservation acquisition and release must be atomic and generation checked. Runtime leases
are memory-only and reconstructed from current ownership after process death. Persist safe
account settings and channel references, never active lease objects, Media3 players,
surfaces or credential-bearing preset URLs.

Live tuning, standalone Guide preview, secondary panels and recorder capture must use the
same application-scoped allocator. A new recording may displace optional same-account
viewing by the documented priority policy; it must leave unrelated accounts running.
Releasing a viewer does not authorize reopening it later.

Multi-View should retain the existing live player as its primary session and allocate at
most three secondary players. Each player has one surface lease. Moving, focusing,
resizing, choosing audio and entering fullscreen do not resolve another URL or create
another connection. Codec capability reports are advisory; allocation failures must
release only the failed decoder and leave existing panels intact.

Use one Android audio-focus owner. Audio-follow-focus is a cancelable 150 ms delayed
selection; OK-only mode changes ownership on explicit selection. Guide information focus
must not retune or change the selected audio owner. Secondary Media3 players must not
independently compete for Android audio focus.

Sports integration must offer an explicit Add to Multi-View action for one confirmed,
enabled catalog choice. Merely opening an event or loading a preset preview must not
connect. The existing league/team favorites and association decisions remain authoritative.

### Recording ingest and storage

The existing recorder copies a continuous HTTP response; the viewer uses Media3's source
and buffering pipeline. There is no shared ingest abstraction or proof that playback and
capture count as one provider connection. Reserve them separately. Growing-file playback
of an internal recording does not itself reopen the provider stream.

Support continuous, authorized non-DRM capture only. Do not save HLS manifests as video,
concatenate arbitrary HLS segments or bypass encryption. Unsupported source formats must
fail with a safe explanation. Resolve expiring supported provider links at capture time.
Keep recoverable content and classify interruption as Partial rather than claiming success.
Storage shortages must stop capture accurately, without deleting other recordings.

### Android execution limits

The baseline targets SDK 35 and declares a `mediaPlayback` recording foreground service,
foreground-service permission, notifications, wake locks and exact-alarm permissions.
The type and distribution-policy eligibility for unattended DVR need review against the
platform's permitted use cases; naming capture a media task does not itself establish
eligibility. Do not replace it with indefinite `dataSync` or `mediaProcessing` capture.

An allowed, already-running foreground service can outlive visible playback. Starting a
future capture while backgrounded is a separate platform decision. An inexact alarm is
not equivalent to the exact-alarm foreground-start exemption. Android 15 restricts starting
`mediaPlayback` foreground services from `BOOT_COMPLETED`; boot handling should rearm
bookings and report missed windows, rather than start capture directly from boot.
Notification access, alarm access, battery policy, source availability, storage and device
power all affect readiness. Force-stop, process termination, revoked grants and a fully
powered-off device can prevent capture. A readiness display must explain those limits.

Platform references: [foreground service types](https://developer.android.com/develop/background-work/services/fgs/service-types),
[background-start restrictions](https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start)
and [Android 15 behavior changes](https://developer.android.com/about/versions/15/behavior-changes-15).
The baseline mediaPlayback boot restriction above explains why its old declaration could
not establish capture eligibility; the new specialUse declaration still follows normal
alarm/background-start and distribution-review requirements.

## Verification and remaining acceptance

The existing Build workflow runs:

```sh
./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
python3 -m unittest discover -s scripts/tests -v
python3 scripts/benchmark_sports_queries.py
bash scripts/test-supabase.sh
```

The workflow already separates the isolated database-security job and uploads APK/test/lint
artifacts. Preserve these checks. New deterministic providers, players and capture-policy
tests must use fake data and require no IPTV credentials.

Automated tests establish allocation, arbitration, persistence and lifecycle policy.
Actual multi-decoder stability, exclusive surface transfer, audio focus, physical remote
navigation, PiP, simultaneous network/storage throughput, screen-off capture, exact-alarm
delivery and Android background-start behavior require hardware verification. Until those
checks are performed, describe supported policy and tested boundaries without certifying
a TV model or promising uninterrupted unattended recording.

### Full feature acceptance still required

This foundation is not the complete Smart Multi-View feature. Keep the pull request a draft
until its outstanding criteria are implemented and validated. In particular:

- A bounded Multi-View session manager with working two-, three- and four-channel playback,
  hardware allocation fallback and independent failed-panel actions.
- All four layouts, add/remove/replace/reorder controls, dedicated editing mode, duplicate
  channel choices, visible remote focus and audio indicators.
- Canceled 150 ms focus-follow audio changes, OK-only audio, one audio-focus owner and
  selected audio retained through Guide navigation.
- Smart make-before-break switching only with authorized account and decoder headroom;
  safe break-before-make fallback and retention of the old stream when technically possible.
- Fullscreen handoff without unnecessary retuning, configurable secondary restoration,
  default Ask first and primary-first resource-checked restoration.
- Compact Guide Multi-View, explicit Focus Multi-View action and restored Guide focus/scroll.
- Sports Hub Add to Multi-View from one confirmed authorized broadcast with explicit
  provider choice; existing favorites and association confidence preserved.
- Credential-free saved presets with disconnected preview, stable channel references,
  arrangement/audio/presentation preferences and final capacity revalidation.
- Review & Restore after interruption, with no silent reconnection of prior sessions.
- Meaningful repeated-stall detection, authorized alternative suggestions, cooldowns,
  bounded retries and accurate network/server/provider-limit/decoder classifications.
- Complete authorized alternative-provider choice and detailed per-conflict resolution
  beyond the current padding review/confirmation and deterministic deadline fallback.
- Recording displacement of optional previews and secondary views, primary conflict
  disclosure and explicit restoration policy without silently reopening displaced streams.
- Live recording duration extension and safe running-window updates; bounded automatic
  sports extension only when a reliable permitted live-status source is available.
- Storage headroom and simultaneous-output estimates for each supported destination,
  removed-drive recovery, safe finalization and explicitly enabled protection-aware retention.
- Complete remaining settings surfaces, accessibility/localization, migration coverage and the full
  deterministic connection/Multi-View/DVR regression matrix requested for the final feature.

Shared ingest remains unsupported unless a future format/DRM/provider review establishes
that it is safe. No recording can be guaranteed through power loss, force-stop or denied
platform execution. No hosted database dependency is needed for this local feature.

### Hardware checklist

**Not yet executed.** Record TV model, OS/API level, decoder/stream formats, source allowance,
storage destination and observed result. Test at least one Android TV, Google TV and Fire TV
device, including a memory-constrained model.

| Check | Required evidence |
| --- | --- |
| Upgrade an existing installation | Providers, encrypted database, schedules, finished files, series rules and custom settings survive. |
| Independent providers and manually linked playlists | Reservations remain separate for independent accounts and share capacity after explicit linking; editing/removing a source does not strand a lease. |
| Unknown allowance and another device using the account | Additional local requests require specific consent; UI does not claim total provider usage is known. |
| Rapid tune, retry, Back, configuration change and process death | No leaked provider sessions, duplicate players, stale release or silent reopening. |
| Guide information focus and preview | Highlight changes do not retune; allocation denial does not start a connection; retained live playback remains exclusive. |
| Simultaneous capture and viewing | Verified local limit is respected; same-account optional viewing yields appropriately; unrelated accounts continue. |
| Leave Nuvix, open another app, screen off and remove task | Allowed foreground capture continues; notifications and partial wake lock behave correctly; unsupported vendor behavior is disclosed. |
| Exact-alarm permission granted/revoked, app force-stop and reboot | Readiness is accurate; missed/late starts have truthful status; boot does not bypass Android 15 service restrictions. |
| Internal, SAF USB and SMB capture; remove drive/network | No overwrite, no silent deletion, bounded storage failure, recoverable bytes retained and accurate Partial/Failed state. |
| Kill process or interrupt network during capture | Recovery reconciles real file size and does not report incomplete capture as Completed. |
| Protected/manual/series conflict and locked padding | Deterministic priority, visible adjustment/reason, locked padding preserved and unattended fallback reaches a decision. |
| Future Multi-View decoder, remote, audio and surface scenarios | Working panels survive a failed extra allocation; one audible owner; no fifth stream or surface attached to two views. |
