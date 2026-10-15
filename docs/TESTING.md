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
