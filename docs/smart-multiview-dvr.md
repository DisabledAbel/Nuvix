# Smart Multi-View and background DVR

This document records the architecture review for the Smart Multi-View/background DVR
work. Device-dependent behavior must be verified on actual Android TV, Google TV and
Fire TV hardware. A local reservation is Nuvix's allocation decision; it cannot establish
how many streams another device is using or change a provider subscription.

## Multi-View playback

Open **Multi-View** in the main navigation or from live playback, Guide or a confirmed
Sports broadcast. Entering hands ownership away from the retained single-channel session
before opening Multi-View; it does not leave a hidden fifth viewer. **Exit Multi-View**
closes its streams. Existing single-channel persistent playback remains available.

Use **Add Channel** to browse All Channels, Favorites, Recent, Custom Groups or Providers.
No provider connection opens while browsing. The picker uses the existing local catalog;
channels must still resolve and pass account/resource checks when selected. A channel already
playing offers **Focus Existing Panel**, **Replace Selected Panel** and **Cancel** rather
than opening a duplicate stream.

| Layout | Presentation |
| --- | --- |
| Two channels | Two equal side-by-side panels. |
| Three channels | Large primary panel beside two stacked panels. |
| Four channels | Four panels in a 2×2 grid. |
| Focused | Large primary panel beside up to three smaller panels. |

**Layout → Balanced** chooses the two/three/four arrangement from the current panel count;
**Focused** uses the larger primary arrangement. Video uses fit-to-panel aspect ratio,
with letterboxing as needed. Layout changes, focus and rearrangement retain each panel's
identity and player. **Panel Actions → Make Primary Panel** chooses the large panel.
**Edit Layout** makes D-pad moves rearrange the focused panel; **Done** returns to normal
navigation. Channel/provider labels and an active-audio indicator identify each panel.

### Remote and audio controls

D-pad selects a panel. **Follow Focus** waits 150 ms by default before selecting its audio;
a newer focus change cancels the pending change. **Press OK to Switch Audio** moves visual
focus without changing audio until OK is pressed. **Panel Actions** is an explicit toolbar
action; Menu/F2 also opens it, so a remote long press is never required. The actions include
**Listen to This Panel**, **Watch Fullscreen**, **Replace Channel**, **Retry Playback**,
**Make Primary Panel** and **Remove Panel**.

Only one panel has nonzero volume. The wall requests one Android audio-focus lease;
individual Media3 players do not request competing focus. Focus loss mutes the wall.
Audio changes keep other video streams playing and do not resolve or retune channels.
Guide information navigation never changes panel focus or the chosen audio source.
**Mute / Unmute** preserves the wall's mute choice when audio ownership changes.

Open **Settings → Display & playback → Multi-View** for these local preferences:

| Preference | Default |
| --- | --- |
| Multi-View Enabled | On |
| Audio Switching | Follow Focus |
| Audio Focus Delay | 150 ms; 0/150/300/500 ms choices |
| Fullscreen Restoration | Ask First |
| Buffering Alternative Suggestions | On |
| Compact Guide Multi-View | On |
| Channel and Provider Labels | On |
| Save Presentation Preferences | On |
| Recovery After Unexpected Exit | Ask Before Restore |

Provider connection limits remain in each provider's **Connection Management** settings.
Multi-View settings and presets are local; they add no hosted database dependency.

### Guide and Sports Hub

Open **Guide** from the Multi-View toolbar to keep the same panels compactly above the
Guide. **Focus Multi-View** returns panel control explicitly; Guide D-pad navigation leaves
selected audio alone. Opening the compact presentation transfers existing surface leases;
it does not allocate another player or tune a highlighted row. Disabling compact Guide
presentation hides the wall while leaving the session policy in charge of playback.
Guide channel/program actions offer **Add to Multi-View**, **Replace Selected Panel** and
**Watch Fullscreen**. Returning from Guide keeps the prior panel arrangement; the existing
Guide position preferences remain in effect. A surface-attachment failure displays a
safe fallback rather than allocating a duplicate player.

Sports game details offer **Add Game to Multi-View**, which opens an explicit chooser of
confirmed available broadcasts and their providers. Each eligible broadcast also offers
**Add Broadcast to Multi-View**, **Replace Multi-View Panel** and **Watch Fullscreen**.
Only the selected broadcast is dispatched. Actions revalidate the existing enabled-source,
channel-visibility and current confirmed-association rules; unconfirmed suggestions do not
open streams. Existing favorite teams/leagues and match corrections remain authoritative.
No paid sports API or additional sports feed is introduced.

### Presets, fullscreen and recovery

**Presets** supports named save, rename, replace and delete. Preview lists intended channels,
providers, local account requirements, unavailable references and unknown allowances without
opening streams. Confirming restoration resolves current channels and checks reservations
again, restores the primary first and opens secondary panels progressively. An unanswered
unknown-limit request never authorizes another stream.

Preset records use a versioned local SharedPreferences document containing stable channel,
source, opaque account and panel references, arrangement, primary/audio selections, layout
and label presentation. They contain no stream URLs, tokens, passwords, players, active
reservations or surfaces. The initial document migration is additive and leaves Room schema
14 and the encrypted provider database unchanged. Malformed/future documents are retained
read-only rather than overwritten.

**Watch Fullscreen** keeps the selected player's identity and surface ownership where
supported and releases secondary resources according to session policy. Back uses
**Fullscreen Restoration**: Ask First (default), Restore After Checks, or Keep One Channel.
Restoration always rechecks resources and restores secondary streams incrementally.
PiP uses one selected live panel; returning requires the same restoration policy.

An interrupted session offers **Review & Restore** on a later launch. Review is disconnected
and safe presentation references are the only persisted state. No prior connection is
reopened before explicit confirmation. Recovery can be disabled; it never silently restores
after process death. Explicitly saved presets and presentation preferences survive exit.

### Provider, hardware and failure boundaries

Every panel uses the PR #35 application-scoped account allocator. Independent accounts
retain independent capacity, explicitly linked sources share allowance, and recordings
remain protected. Additional connections on an Unknown account require consent for that
specific request. Previewing a preset or channel list needs no consent because it opens no
connection. Local reservations cannot establish usage on another device.

Replacement uses make-before-break only with confirmed spare account capacity and decoder
headroom. Otherwise it resolves the candidate before safe break-before-make. It never
briefly exceeds the local configured account allowance. A failed replacement or allocation
affects its own panel; unrelated working panels remain. Stale generations cannot release a
replacement player or reservation. Layout-only operations never reconnect.

Hardware admission is conservative: MediaCodec reports and device memory estimate a limit
of at most four players, and streams start one at a time. Decoder aliases are not added
together as independent hardware. Multi-View uses hardware video decoding, bounded buffers
and a 1080p selection ceiling; unsupported codecs/profiles or high-resolution-only streams
may fail rather than forcing software decoding. A runtime decoder failure reduces further
admission and offers a smaller layout while preserving working panels. Reported instance
counts do not certify simultaneous playback on a TV.

A failed panel stays visible with Retry, Replace and Remove actions. Retries are bounded.
Three completed stalls of at least two seconds within two minutes can offer authorized
alternatives, with a one-minute suggestion cooldown. Initial startup and short stalls do
not count. A user must choose any provider change. Network, stream, unsupported-format and decoder failures remain
distinct; generic HTTP failures do not prove a subscription limit rejection. Diagnostics
retain the existing sanitized, bounded, memory-only contract.

Leaving the app releases the wall's streams unless the selected panel is in PiP. Returning
offers **Resume Channels** and revalidates capacity instead of silently reconnecting.
If recording reclamation pauses a panel, **Panel Actions → Choose Authorized Alternative**
opens matching local broadcasts. This post-interruption choice does not delay a recording;
interactive primary-panel conflict review before the recording deadline remains unfinished.

## Connection management and DVR foundation

The merged PR #35 foundation extends the existing live owner, Guide preview and recording
service. Multi-View reuses it rather than introducing another recorder or connection pool.

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
make-before-break is additionally available for Multi-View replacement when both account and
hardware headroom are confirmed.

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
  existing windows. Active captures cannot change their committed end time.

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

The Multi-View completion work starts from merged PR #35 on `main` at
`fef3d8db1d7f1620665893a520a26ca7614b34e7`. The following original foundation
assessment is retained for context; its pre-PR #35 baseline was `main` at
`88aeb3a2056dc07cf1d15ab2155fca79daaccf19`.
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

Entering Multi-View releases the single-channel live owner before resolving its initial
primary; this entry can retune, but never retains a hidden extra session. The manager
allocates at most four players including any replacement candidate. Each player has one
surface lease. Moving, focusing, resizing, choosing audio and entering fullscreen within
Multi-View do not resolve another URL or create another connection. Codec capability reports are advisory; allocation failures must
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

### Implemented scope and remaining acceptance

The Multi-View implementation covers session admission, bounded players, layouts and remote
controls, delayed audio ownership, replacement/duplicates, Guide/Sports actions, local
presets, fullscreen and reviewed recovery. It reuses protected recording priority,
secondary-view displacement and the existing independent recording service. Runtime
reservations and unknown-limit approvals never survive process death.

Physical Android TV, Google TV and Fire TV acceptance below remains required. Automated
policy/UI coverage does not certify hardware decoders, uninterrupted network throughput,
real remote focus, exact-alarm delivery or Android/vendor background execution.

Interactive primary-panel conflict review with an authorized alternative-provider picker
at a recording deadline remains an outstanding Multi-View acceptance item. Current behavior
pauses conflicting optional viewing under the established recording-priority policy and
explains why; it never automatically switches providers or cancels protected capture.

Separate future DVR scope includes running duration extension, reliable-source automatic
sports extension, external-drive free
space/whole-program size estimates, removed-drive hardware recovery and opt-in
protection-aware retention. Existing recording padding review and deadline fallback stay
in place; active locked padding is never silently trimmed. No automatic retention/deletion
job is introduced.

Shared viewing/capture ingest remains unsupported: these consumers reserve separately.
No recording can be guaranteed through power loss, force-stop or denied platform execution.
No hosted database dependency is needed for this local feature.

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
| Two/three/four streams; unsupported codec/profile; mixed resolutions; repeated extra allocation | Working panels survive failed hardware admission; bounded decoder count and honest smaller-layout advice. |
| Follow Focus at 150 ms, rapid focus, OK-only, system focus loss/gain and mute | One audible owner, cancelable pending switches, no retune, preserved user mute and no focus-loss bypass. |
| Add/replace/duplicate/rearrange/remove with constrained/Unknown/shared accounts | No temporary configured overage, request-specific approval, stable identities and unrelated playback preserved. |
| Fullscreen, secondary restoration, Guide compact transfer, Back and PiP | Same selected player where supported; no double surface attachment; primary-first checked restoration and Guide focus/audio preservation. |
| Sports multiple confirmed broadcasts, suggestions and disabled/hidden sources | One explicit authorized choice; provider identity visible; unavailable choices never start playback. |
| Preset preview/CRUD, missing channels, process death and Review & Restore | Preview opens no connection; credentials absent; no silent reconnection; unavailable references explained. |
