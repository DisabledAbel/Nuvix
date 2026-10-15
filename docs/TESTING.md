> Current live playback validation and historical upstream testing notes. Earlier Nuvix foundation validation is recorded in [implementation-report.md](implementation-report.md); build setup is in [building.md](building.md).

# Testing OpenTV

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

The existing Build workflow runs on pushes and pull requests, keeps read-only repository
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
primitives used by RLS, applies all migrations in order, and runs both security suites. It also
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

1. Apply both migrations in order. Sign in on both clients with the existing email-code flow.
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
