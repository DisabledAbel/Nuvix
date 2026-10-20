> Current live playback validation and historical upstream testing notes. Earlier Nuvix foundation validation is recorded in [implementation-report.md](implementation-report.md); build setup is in [building.md](building.md).

# Testing Nuvix

## Pre-recording conflict review

`MultiViewRecordingConflictTest` covers exact/shared/independent pools, confirmed spare and
Unknown capacity, padded timing/expiration, duplicate evaluation, simultaneous demand,
priority and locked padding. `MultiViewSessionManagerTest` adds warning-approved switches,
failed alternatives, deadline/stale callback rejection, paused primary preservation,
unrelated audio/players, cancellation, rescheduling, background notification and deferral history.
`RecordingWarningPersistenceTest` checks process-reopened acknowledgment, full-Long alarm
identities, alarm rearming/cancellation and configurable lead time. `RecordingConflictAlternativesTest`
checks strict guide identity, confirmed Sports precedence, independent/full/unavailable pools,
Unknown labeling and disconnected discovery. `RecordingConflictRemoteTest` exercises explicit
D-pad/OK actions and no-alternative recovery. Tests use synthetic URLs and virtual time; no
production IPTV credentials are required. Existing allocator/DVR/locked-padding tests remain active.

Device execution remains required; use the [conflict-review hardware checklist](smart-multiview-dvr.md#required-device-acceptance-for-conflict-review-not-executed-here).
Automated results do not establish OEM alarm/notification delivery, physical remote focus or
provider-side connection counts. Screenshots should be captured from the hardware acceptance run.

## Smart Multi-View

Use the unchanged Build workflow and the required local commands:

```sh
./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
python3 -m unittest discover -s scripts/tests -v
python3 scripts/benchmark_sports_queries.py
bash scripts/test-supabase.sh
```

The Android suite includes deterministic fake-player/allocator tests rather than production
IPTV credentials. `MultiViewSessionManagerTest` exercises two/three/four-channel admission,
hardware limits, failure isolation, replacement, duplicate choices, stable panel ordering,
request-specific Unknown approval, delayed audio, Guide ownership, fullscreen, background
cleanup and DVR priority. The allocator's existing shared-account/concurrency tests remain
part of the same suite. `MultiViewPresetStoreTest` covers preset CRUD/order/references,
interrupted-session durability, disconnected preview with missing channels/account changes,
safe identifiers, bounded records and preservation of malformed/future documents.
`AppSettingsMultiViewPreferencesTest` covers defaults, persistence, enum validation,
malformed values and opt-out behavior. `MultiViewGeometryTest` checks nonoverlapping layouts, stable primary arrangement and
bounded edit moves. `MultiViewGuideIntegrationTest` navigates the actual compact preview
and Guide grid over two fake players, preserving audio, starts and reservations.
`MultiViewSettingsScreenTest` exercises nested remote navigation, explicit setting selection
and recovery disablement without deleting presets. `MultiViewRemoteUiTest` sends remote
Menu/OK input to panel controls, Edit Layout/Done and mute selection;
`MultiViewPreviewCatalogTest` checks current local account registration and rejects moved,
hidden or disabled channel references without exposing stream credentials.

`SportsRemoteUiTest` verifies that Add Game first shows a chooser, providers are visible,
only one confirmed broadcast is selected, and unconfirmed suggestions expose no playback
action. `SportsViewModelTest` revalidates the one selected broadcast before dispatch.
Existing `SportsRepositoryTest`, Guide action/preview/selection tests and live lifecycle,
surface, PiP, recording and diagnostics regressions remain required.

Physical-TV checks **have not been performed for this feature**. Record Android TV,
Google TV and Fire TV model/OS, decoder formats/resolution, account allowance, storage and
pass/fail evidence using the [hardware checklist](smart-multiview-dvr.md#hardware-checklist).
In particular, verify actual two/three/four-decoder behavior, meaningful buffering,
physical D-pad focus/edit mode, 150 ms audio, focus interruptions/mute, surface transfers,
Guide focus/scroll, PiP entry/failure/return and simultaneous protected DVR. Fake players
and Robolectric do not prove these hardware properties. A host build is not device acceptance.

## Connection management and DVR foundation

Run the full existing unit, lint, assembly, Python and database-security checks listed in
[the feature architecture](smart-multiview-dvr.md#verification-and-remaining-acceptance).
The existing Build workflow remains the source of CI validation; Multi-View reuses the
existing recorder and account allocator without adding a parallel validation workflow.

New deterministic tests cover account-local capacity, exact shared-account associations,
request-specific unknown-capacity approval, concurrent reservations, idempotent release,
stale generations, recording priority, locked padding and additive recording persistence.
Capture tests use fake HTTP responses and destinations. No real IPTV credentials are
required. These checks exercise policy and controlled failures; they do not establish
Android foreground-start eligibility, exact-alarm delivery or decoder stability.

| Test group | Scope |
| --- | --- |
| `ConnectionAllocatorTest`, `AccountConnectionManagerTest` | Independent/linked accounts, opaque identity and local persistence, unknown approval scope, concurrency, release and recording reclaim. |
| `LivePlaybackCoordinatorTest`, existing live lifecycle/surface tests | Replacement denial, stale requests, safe old-engine release and presentation ownership. |
| `GuidePreviewConnectionTest`, `GuideProgrammeActionsTest` | Optional preview admission and explicit account-aware recording/viewing actions. |
| `DvrConflictPolicyTest`, `RecordingDefaultsTest` | Protected/manual/active ordering, known/unknown capacity, minimal unlocked trimming, locked/legacy/active windows and two/five-minute defaults. |
| `RecordingPersistenceTest`, `RecordingRepositoryTest`, `RecordingMigrationTest` | Durable duplicate-start claims, finalization, partial reconciliation, activity history and additive schema preservation. |
| `RecordingCaptureTest` | Controlled stream/storage failures, bounded reconnect and safe captured-byte accounting. |

Use the [hardware checklist](smart-multiview-dvr.md#hardware-checklist) for upgrades,
simultaneous viewing/capture, external drives, screen-off behavior, boot, process death and
permission revocation. Multi-View now has dedicated player, presentation and persistence coverage above; physical
decoder, surface, audio and remote acceptance remain outstanding.

## Advanced EPG

Run the existing Gradle/Python tasks below and `bash scripts/test-supabase.sh` when validating
portable preference changes. New guide tests are discovered by the existing JVM task, including
Robolectric Compose tests; no real IPTV credentials or public XMLTV services are required.
Deterministic data covers preference defaults/migration/recreation, layout/density, filters and
sorting, timeline hour/day/Now operations, missing metadata, action eligibility, search bounds,
artwork fallback and focus behavior. Existing live ownership, reminder/recording paths, automatic
discovery and failed-refresh preservation remain part of regression validation.

`AppSettingsGuidePreferencesTest` covers safe defaults, every enum round-trip, malformed stored
values, legacy grid/list preservation, local filter targets and clearing remembered position.
`GuidePreferencesUiTest` sends D-pad/Select/Back to the real preference controls, verifies layout,
sort/density and switch changes, and checks focus returns to the setting after its choice closes.

`GuideChannelSelectionTest` covers every filter/sort, favorites, local recent order, custom group
order/unresolved references, hidden channels and a 20,000-channel catalog. `GuidePresentationTest`
covers exact timeline geometry, overlap/invalid-data clipping, visible-cell/prefetch bounds,
missing-data focus fallback and calendar-day navigation across daylight saving.
`AdvancedGuideUiTest` sends remote events to the grid, checks layout and lazy-row focus,
hour/Now/Current Channel actions, saved position, rapid input, empty filters, legacy list loading
beyond the initial slice and changed automatic matches without scrolling.
`AdvancedGuideViewModelTest` uses the actual Room/view-model path for live preference changes,
provider/group restoration, category reset, restricted/empty groups, bounded viewport hydration
and isolation from playback/history. These tests do not allocate a live decoder.

`GuideProgrammeActionsTest` checks timing boundaries, malformed windows, supported recording
sources, busy providers, catch-up credentials/retention and series eligibility. It tests UI policy,
not recording bytes. `GuideReminderIntegrationTest` uses the existing reminder repository/helper
with in-memory Room and Robolectric AlarmManager: metadata persistence, deduplication, immutable
broadcasts, independent cancellation and future/unfired rearm eligibility. Notification delivery
and actual background alarms still need hardware. `ProgrammeDetailsMetadataTest` renders available
and malformed metadata/fallbacks in Compose; it does not certify the full dialog's focus or actions.
`ProgrammeArtworkTest` checks supplied HTTP(S) artwork and missing/invalid URL fallback; actual
network/image decoding and failure recovery remain device checks.

`EpgSearchTest` uses in-memory Room for result/time limits, minimum/escaped text, metadata-only
categories, hidden/disabled sources and feeds, manual matching precedence and parental exclusion
before the result cap. Its deterministic 2,000-channel fixture materializes 60 viewport program
rows instead of 2,000 for the full window, a 97% reduction in that fixture's returned rows. A
1,201-ID case verifies queries chunk below the legacy 999-bind limit; a 210-locked-result case
verifies restricted content cannot consume the allowed result cap. This is row-count evidence,
not a hardware timing or heap benchmark.

A host SQLite diagnostic using the existing indexes, 2,000 channels and 96,000 synthetic
programs returned the 200-result cap in approximately 0.116–0.263 seconds across runs of a common title query.
`EXPLAIN QUERY PLAN` confirmed program lookup through `index_programmes_epgChannelId` after
channel filtering. Rerun the deterministic fixture with `python3 scripts/benchmark_epg_queries.py`;
the script prints the SQLite version, current schema, result count, timing and query plan.
These host measurements are diagnostic evidence, not a CI threshold or
a TV benchmark; hardware performance and lower-memory acceptance remain unverified.

`GuideCloudPreferencesTest` covers the seven allowlisted `guide_setting` keys, malformed values,
category mapping and rejection of local identifiers/URLs. `supabase/tests/guide_preferences.sql`
checks the additive migration with owner-only RLS and the existing invoker merge RPC against
disposable PostgreSQL. Cloud preference tests do not apply migrations to a hosted project.

Synthetic large-catalog/query tests establish result and window bounds. They do not measure TV
frame time, real heap usage, image decoding, provider connection counts or decoder continuity.
Record actual query timings, row counts, allocations and device specifications when profiling;
do not claim a measured performance improvement from a bounded query alone.

Hardware acceptance requires **Android TV, Google TV and Fire TV**, authorized streams and a
provider feed with current/upcoming/missing programs. **These physical-TV checks have not been run
for this feature.** Record model, OS, resolution, text scale,
guide size, provider connection limit and pass/fail evidence for each row.

| Check | Expected result |
|---|---|
| Apply Standard, Compact and Expanded, every density, artwork/progress switches; restart offline | Preferences apply immediately and survive restart; existing grid/list choice and old settings survive upgrade |
| Filter All, Favorites, Recent, EPG Data, Currently Airing, provider/category and custom groups | Correct visible channels; empty states remain navigable; hidden/parental channels stay restricted; no channel/group data changes |
| Apply each sort with duplicate/missing numbers and names and multiple quality variants | Stable understandable order; provider IDs/numbers and custom order remain intact |
| Hold D-pad up/down and left/right; switch layout/filter; open/close details repeatedly | Selected cell stays visible, focus has clear contrast, no trap or unexpected tune; removed focused rows have a useful fallback |
| Use Now, previous/next hour/day and Current Channel across midnight and a daylight-saving transition | Correct local dates/times and bounded window; explicit Current Channel switches an excluding filter to All Channels and restores focus at Now without tuning |
| Leave/return, restart with Remember Last Position on/off, remove remembered channel, expire guide data | Valid position restores; invalid state falls back safely; disabling remembering follows current guide defaults |
| Search by title, channel, genre and description; use each status/category filter, long text and no matches | Remote entry works, results remain bounded and restricted to visible content, empty/limited states are useful |
| Open details with long descriptions, missing times/genre/episode/artwork and failed images | Readable scrollable content, local artwork fallback, accessible labels and no private URL in UI/errors |
| Set/remove a future reminder, deny exact alarms/notifications, restart, let it fire | Existing repository/alarm behavior remains usable; unavailable permissions do not claim guaranteed exact delivery |
| Record current/future programs, stop, handle conflicts; test unsupported/expired catch-up | Only eligible actions appear; existing padding/clash/storage handling works; no retrospective capture claim |
| Browse/filter/search/details while an authorized live stream plays; return via preview and View Channel | Same session and tracks persist, only explicit channel changes tune, no competing preview player/audio focus loss |
| Repeat while a recording starts, play a growing/local/NAS/USB recording, enter/exit PiP | Existing ownership handoffs and recordings continue; one active viewing surface; no second provider connection from browsing |
| Test 720p/1080p/4K, large text, accessibility services and remote Select/Back without touch | All controls reachable, clear focus/readability, dialog closes before guide and returns focus predictably |
| Browse a large guide rapidly on a low-memory device, fail network/images, background/recreate Activity | Bounded loading, responsive controls and existing recovery; optional preview/artwork can be disabled without losing guide access |
| Opt into compatible-setting sync on two disposable-project clients, change portable guide preferences offline, reconnect | Only validated portable preferences converge; provider/group IDs, position, streams and playback state remain local |

No screenshots or hardware certification are implied by JVM, Compose or SQL success. Keep
source credentials and private stream/artwork query parameters out of captured test evidence.

## Persistent live playback

The normal Android JVM suite includes credential-free session ownership, navigation policy,
surface ownership, and local playback-preference regressions. Test doubles count player
creation, explicit tuning, release, and attachment; they do not contact IPTV providers.
Robolectric exercises Android lifecycle/surface boundaries where supported. See
[live playback](live-playback.md) for expected behavior and device limitations.

Run the same validation tasks used by the PR workflow, separately:

```bash
./gradlew :app:testDebugUnitTest
```

```bash
./gradlew :app:lintDebug
```

```bash
./gradlew :app:assembleDebug
```

```bash
python3 -m unittest discover -s scripts/tests -v
```

```bash
git diff --check
```

The existing Build workflow runs on pull requests and manual dispatch, keeps read-only repository
permissions, and uploads JVM test/lint reports and the debug APK. A JVM fake cannot prove
decoder behavior, provider connection counts, audio focus, or vendor PiP behavior; use the
following device checks with an authorized stream before a release.

| Check | Expected result |
|---|---|
| Start live TV, then visit Home, Guide, Sports, and Search repeatedly | Current channel stays consistent; the guide does not tune newly highlighted rows; no extra persistent live player is created |
| Select the preview or reopen the current channel | Fullscreen returns without a fresh media item or buffer reset |
| Change channel through the guide or existing player channel list | Only the explicitly selected channel becomes current; rapid requests resolve to the latest channel |
| Change audio/subtitle tracks, browse, and return | Track choices and configured buffering remain effective |
| Disable Live Preview; disable browsing audio; return to fullscreen | Preview disappears without creating another player; browsing is muted; fullscreen restores the user's audio and pause choices |
| Disable persistence and leave fullscreen | Live playback is released; reopening follows the screen-owned fallback |
| Recreate the Activity; background/foreground the app; relaunch after process death | Configuration changes retain ownership, unsupported background playback pauses, and process recreation follows startup/resume preferences |
| Enter PiP, return, and close PiP | One stream and one active video surface; PiP controls work; no browsing preview appears inside PiP |
| Lose network, retry, switch channel during recovery | Existing bounded retry/error behavior remains visible; old channel requests cannot win |
| Open VOD, catch-up, local/NAS/USB recordings, or a growing recording | Live relinquishes ownership before another playback connection starts; scheduled/background recordings continue independently |
| Trigger memory pressure or a decoder/surface failure | Resources are bounded, recordings continue, and recovery/fallback offers a clear action |
| Use D-pad, Back, and channel-list controls | Preview has a visible focus state without capturing unrelated keys; Back and channel selection retain existing behavior |

Record device model, Android version, provider connection limit, and whether a result used
real hardware, an emulator, or fakes. Do not claim real-device playback from JVM tests or a
source-free app screenshot. New playback preference/preview strings currently fall back to
English in other locales, consistent with existing Nuvix additions.

There is no QA department; there is you, your provider and your telly. This page makes that
count. The app logs everything it does, so a bug report with a log attached is usually a fix
within the day — and a "works for me" without one is usually nothing.

## The quick loop (with a Mac/PC on the same network)

```bash
bash test-run.sh        # reinstalls, launches, records to testrun.log, shows a checklist
```

## What to test after any change

| # | Check | Pass looks like |
|---|-------|-----------------|
| A | Quality grouping | One row per logical channel; "N qualities" badge on grouped ones |
| B | Guide feeds | Gear → enable a guide for your country → "matched N of M" reported |
| C | Now/Next | Matched channels show programme + progress bar, not "No guide information" |
| D | Playback | Channel plays; quality switch top-right; switching mid-stream keeps playing |
| E | **Wrong matches** | No channel shows another channel's programmes — the worst bug this app can have. Report the exact channel name |
| F | State survives | Favourites/hidden survive a refresh and an app restart |
| G | Fast switching | Holding channel-change never wedges playback (one tune when you stop) |

## Reading the log

Interesting lines and what they mean:

- `Matcher: N of M channels have a working guide` — the headline EPG number.
- `Feed 'X' failed: <reason>` — a guide source failed; the same text shows in Guide settings.
- `Source N: ... channels` — catalogue sync result.
- `AndroidRuntime: FATAL` — a crash; everything above it in the log is the story.

## Reporting

Open a GitHub issue with: device + Android version, what you did (checklist letter is
enough), what happened, and the relevant chunk of `testrun.log`. **Strip your provider's
URL/username/password first** — logs deliberately never print credentials, but check what
you paste anyway.

## Advanced cloud sync

Automated checks use synthetic channels, application packages and UUIDs, without provider accounts:

```bash
./gradlew :app:testDebugUnitTest
./gradlew :app:lintDebug
./gradlew :app:assembleDebug
python3 -m unittest discover -s scripts/tests -v
bash scripts/test-supabase.sh
git diff --check
```

The SQL harness creates a disposable PostgreSQL Docker container, bootstraps only the Auth
primitives used by RLS, applies all migrations in order, and runs all security suites. It also
uses overlapping connections to test atomic conflict resolution. It accepts no hosted database
URL or production credentials. The Build workflow runs this as **Supabase migrations and security**,
separately from Android validation; repository administrators can require both jobs in branch rules.

`AdvancedCloudRecordTest` checks strict payloads and deterministic ordering;
`AdvancedCloudJournalTest` checks persistence, interrupted writes, seed defaults, categories,
account switches, tombstones and feedback prevention. `AdvancedSyncEngineTest` exercises isolated
peers through the full pull/merge/push/apply/acknowledgement cycle. Room adapter tests cover channel
matching, custom groups, independent fields, migration preservation and unavailable references.
Launcher/home tests cover desired app order, missing installations and actual shelf controls.

Before deployment, use a disposable Supabase project and two isolated Android TV clients:

1. Apply all migrations in order. Sign in on both clients with the existing email-code flow.
2. Enable cloud sync and the desired categories on each TV; verify new categories started off.
3. Configure matching authorized content independently. Create a custom group on A and sync;
   confirm B receives it. Rename on B, change membership on A, and confirm both changes survive.
4. Move groups and group channels; hide/unhide and rename channels using the channel manager.
   Change ordinary channel order through the existing web channel manager. Confirm synchronization.
5. Enable favorite removals explicitly on both clients; favorite and then unfavorite matching
   channels/movies/series. Confirm legacy additive favorites still work with that category off.
6. Pin and reorder apps on A, including an app absent on B. Confirm B omits its tile while retaining
   the desired reference. Install it later and confirm the original pin returns. Test explicit unpin.
7. Toggle home shelves and change theme/subtitles/guide layout/startup destination. Confirm only
   selected categories transfer. Verify Android's default launcher and device permissions do not change.
8. Disconnect B, make independent edits and competing renames on both clients, restart B, then
   reconnect. Confirm convergence using the documented timestamp/device/payload rule. Delete a
   group and confirm B's stale rename cannot resurrect it.
9. Change a provider or omit/duplicate a matching channel. No broken playable channel should appear.
   Restore a unique authorized match and confirm its retained references reconcile.
10. Disable categories and full sync; edit locally and confirm no corresponding transfers occur.
    Re-enable and sync. Expire/revoke a test session and confirm useful authentication feedback.
11. Sign out while offline, restart, and sign in with a different disposable account. Confirm sync
    and new categories are off and the previous account's queue cannot replay. Local data remains;
    selecting categories again explicitly shares the current local choices with the new account.
12. Repeat with different vendor launchers and lower-powered hardware. Check local playback,
    parental restrictions and independent LAN/NAS synchronization throughout.

A passing JVM/SQL run does not establish real OTP delivery, Android Keystore crash behavior,
launcher interoperability or actual two-TV convergence. Record those device checks separately;
never run destructive account/deletion testing against production data. See [cloud-sync.md](cloud-sync.md)
for protocol, privacy, limits and troubleshooting.

CodeRabbit regression coverage includes a 33,400-channel untouched catalog producing no advanced
journal seeds, explicit default resets surviving provider refresh (including separator visibility),
sparse optimistic application protecting a concurrent local edit, and a future-dated remote record
leaving other pulled records usable.

## Advanced home customization

The standard `:app:testDebugUnitTest` discovery includes preference migration/recreation,
order deduplication/movement, hidden positions, UUID groups, resets, malformed storage,
layout/card validation, background fallback, featured selection, portable cloud records,
offline journal replay, deterministic whole-list merges, category pauses and account binding.
Robolectric Compose tests send D-pad/Select key events to the real customization controls,
check movement/focus, Back and return focus, and apply layouts/metadata with a card displayed.
The card callback count proves these controls do not invoke it; it does not prove decoder
continuity on hardware. Existing live-session lifecycle tests remain part of the same suite.

Run `bash scripts/test-supabase.sh` for all migrations, old-record preservation, owner-only
RLS, home payload bounds/allowlists and deterministic merge tests on disposable PostgreSQL.
The Build workflow retains all Android, Python and security jobs and APK/report artifacts.

Hardware acceptance remains required on **Android TV, Google TV and Fire TV**:

- [ ] D-pad through every menu; move first/last shelves; hide/restore each section and a group;
      close with Back and verify visible return focus. Repeat with all shelves hidden/empty.
- [ ] Apply Classic, Compact and Cinema and every card size at 720p/1080p/4K and large text;
      check clipping, focus contrast, spacing and long names.
- [ ] Focus movies/shows rapidly with Dynamic artwork, then missing/failed artwork; verify
      fallback, readability, bounded memory and reduced-motion behavior on low-end hardware.
- [ ] Select every featured source with/without matching content and use resume/live/guide/provider
      actions; disable the hero and confirm navigation remains reachable.
- [ ] Start an authorized live stream, customize repeatedly, return to fullscreen; verify no
      reconnect, duplicate session, release, unexpected audio change or preview surface loss.
- [ ] Kill/relaunch after edits; upgrade from visibility-only preferences; verify app favorites,
      custom group membership/order, startup/launcher settings and LAN/NAS sync remain intact.
- [ ] Use two disposable-project accounts/TVs to verify opted-in home sync, offline conflicts,
      category pauses and account switches. Repeat with an older client after migration.

No TV-device, decoder, screenshot or low-end memory validation is implied by JVM/SQL checks.

## Playback diagnostics

The existing `:app:testDebugUnitTest` discovery includes `PlaybackDiagnosticsTest`,
`StreamInformationTest`, local settings persistence and persistent-playback regressions.
A strict fake rejects all unexpected player operations: observer sampling and overlay
open/close cannot prepare, seek, pause or restart it. Tests cover formats/unavailable fields,
buffer duration, health/state, startup/rebuffer/seek handling, dropped-frame accumulation,
session reset, listener balance, bounded/aged history, nested error privacy, protocol
identification, Compact/Detailed rows, hidden-until-open, initial Close focus, D-pad movement,
Close and Back. Compose tests run under Robolectric with the normal JVM suite, not a stream.
Run the Gradle/Python commands above and `bash scripts/test-supabase.sh` for existing SQL checks.

Hardware acceptance (requires authorized media; record device/OS/resolution/results):

| Check | Expected result |
|---|---|
| Live, movie, episode, local/USB/NAS and growing recording | Same playback continues as the panel opens/closes; unsupported fields say Unavailable |
| Compact/Detailed; restart app; disable entry/technical measurements | View and local settings persist; disabled panel stays hidden |
| 720p, 1080p, 4K and large system font on Android TV/Google TV/Fire TV | High-contrast readable text, visible focus, scrollable rows/actions with D-pad only |
| Back/Close, volume, audio/subtitle picks, channel changes | Panel dismisses first, focus returns to controls, unrelated controls retain behavior |
| Initial buffering, repeated stalls, seek, pause, channel switch | Startup/seek do not inflate rebuffer count; new channel resets metrics; bounded history |
| Navigate persistent playback, rotate/recreate Activity, background/foreground, PiP | Same owner and stream; sampling stops when hidden/stopped; no decoder or focus interference |
| Network/HTTP/decoder errors; recording begins; low-memory pressure | Safe guidance, existing retry/recording ownership, no secrets in panel/history/logs |

Automated UI tests cannot measure hardware decoder counters, real transfer throughput,
provider connection limits, vendor PiP behavior or prove display readability. Do not claim
hardware verification from these tests. No diagnostics screenshots are included until a
representative device/emulator capture is available.


## Sports Hub

Run the existing CI commands with no IPTV credentials or real stream endpoints:

```sh
./gradlew :app:testDebugUnitTest
./gradlew :app:lintDebug
./gradlew :app:assembleDebug
python3 -m unittest discover -s scripts/tests -v
python3 scripts/benchmark_sports_queries.py
bash scripts/test-supabase.sh
```

The deterministic `app.opentv.sports` tests cover source-scoped identity, Unicode/league aliases,
exact/likely/ambiguous/wrong-game matches, provider variants and duplicate evidence, missing metadata,
manual decisions/removal/reassignment, preferences and ordering, reminder concurrency and guide reuse,
verified reschedule/cancellation, offline cache continuity/age, provider failures/cancellation, validation,
timezones/DST and D-pad Select/focus without playback. Existing live-coordinator, session-lifecycle,
guide/reminder and cloud security regressions still run in the same unit suite. Migration preservation
runs from exported v2 through v13 and retains original credentials/favorites/overrides/resume state.
The host SQLite diagnostic uses the exported Room schema and actual DAO SQL with 2,000 channels,
96,000 programs, four synthetic providers and 100 events; it asserts bounded results and catalog index
use. Host times do not establish device frame rates.

Manual verification remains required; record device/OS, provider connection limit and observations:

- [ ] Android TV, Google TV and Fire TV: enter the existing Sports tab; reach My Sports, Live Games,
  Upcoming Games, Favorite Teams, Favorite Leagues, All Sports, Sports Channels, Unmatched sports programs
  and the conditional Filtered Games shelf, plus the search keyboard, filter chips, favorite ordering,
  settings and paged streams with D-pad only.
- [ ] Select a game, return with Back, play a confirmed choice, Back to the same event, and explicitly
  switch providers. Focusing cards or suggestions must never tune or open a provider connection.
- [ ] Verify favorite leagues/teams and order after restart; identical names in different competitions
  remain separate. Sparse EPG keeps Sports Channels usable; unknown participants stay unknown.
- [ ] Confirm/reject/restore suggestions, associate an unmatched sports program with a game and inspect
  possible duplicates. Refresh guides and verify intent persists while raw guide/catalog rows stay intact.
- [ ] Compare card counts with provider choices, hidden/locked categories, disabled providers/feeds,
  duplicates, manual EPG-ID overrides and large stream lists requiring multiple pages.
- [ ] With authorized streams, test failures/retry, single-connection limits, concurrent recording guards,
  PiP, browsing audio, audio focus, subtitle/track choices and existing guide/fullscreen surface transfers.
- [ ] Set the same game reminder via Sports and several Guide channels: one alarm/notification only.
  Test cancellation, denied notification/exact-alarm permissions and reboot rearming; no automatic tune.
- [ ] Go offline and refresh a failing provider: other schedules remain, cached timestamps remain original,
  and stale games do not claim live freshness. Check midnight, device timezone changes and DST boundaries.
- [ ] On low-memory TVs, scroll large catalogs rapidly, change text size, use accessibility services,
  inspect focus contrast/restoration, artwork caching and memory pressure with persistent playback.
- [ ] Upgrade an existing installation to Room 13 with provider favorites, overrides, custom groups and
  reminders populated. Verify all prior data and existing guide/reminder/recording workflows.

No physical TV, real-provider, decoder or licensed-score acceptance is implied by synthetic tests.
External API adapters and Sports cloud synchronization are not configured in this implementation.


## Upcoming DVR preset preview

Run the unchanged CI entry points:

```sh
./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
python3 -m unittest discover -s scripts/tests -v
python3 scripts/benchmark_sports_queries.py
bash scripts/test-supabase.sh
```

`MultiViewPresetRecordingPlannerTest` covers no recordings, upcoming/immediate conflict,
24-hour boundaries, padded/locked/protected/manual/series windows, overlapping captures,
shared/independent accounts, current pending/releasing reservations, replaced-wall accounting,
unknown versus provider-reported/configured limits, cancellation/rescheduling, removed/moved
channels, capture end release, preview privacy and a 40,000-row catalog. It also verifies that
previewing does not change allocator state or suppress the existing pre-deadline warning.
`MultiViewPresetPreviewControllerTest` exercises fresh confirmation reads, settings/schedule
changes, due recordings, deletion, duplicate OK, stale generations, background invalidation,
restart without consent, future-warning continuation and sanitized failures with fake readers.
`MultiViewSessionManagerTest` also checks due recordings across Unknown approval and stale
admission without allocating another fake player. Existing allocator/DVR/recovery suites remain.

`MultiViewPresetReviewRemoteTest` sends D-pad/OK to real Compose actions, verifies disabled current
conflicts and accessible recording/fallback descriptions. Store tests distinguish one-time edits
from explicit updates. Room tests exercise bounded time/status queries and read-only scheduling.
Existing alternative tests retain exact-guide/confirmed-Sports evidence, hidden/shared/full/unknown
account rejection and no fuzzy-title equivalence. No real IPTV credentials are required.

Follow the [preset hardware checklist](smart-multiview-dvr.md#remaining-boundaries) on Android TV,
Google TV and Fire TV. Automated UI tests do not establish device focus contrast, frame rate,
decoder capacity, actual provider allowances, alarm delivery or successful unattended capture.


## Active DVR duration controls

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

The duration engine also tests an end passing while a review is open and between the final
validation and commit clock reads. Neither case can turn ordinary confirmation into stop
consent, even if a caller passes a stop flag; a new reviewed stop is required.

Duration-specific suites: `RecordingDurationTest`, `RecordingDurationPersistenceTest`,
`RecordingDurationEngineTest`, `RecordingDurationConflictTest`, `RecordingDurationRemoteTest`
and `RecordingCaptureTest`. Run with the existing full Gradle commands above. The engine uses
an injected clock and recording stop/rearm hooks with an isolated Room database and fake provider
HTTP interceptor. No IPTV credentials or networked provider is required. Existing v12/v13 and
v6 migration tests now migrate through v15 and continue validating all prior data.
