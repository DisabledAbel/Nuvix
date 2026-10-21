# DVR late-start controls

Open **Settings → Recording → Late Start** to select **Record Remaining** (the default),
**Skip If Late**, or **Minimum Time Remaining**. The minimum defaults to ten minutes;
5/10/15/30-minute presets and custom 1–1440-minute D-pad selectors are available. Settings
apply immediately. No physical keyboard is needed.

Guide/program recording actions offer **Late Start** before Record/Schedule/Record Series.
Use **Recordings → recording options → Late Start** to edit an existing scheduled booking,
and **Recordings → series link → Late Start** to edit a rule. Save recording options together
with padding/protection; canceling leaves every option unchanged. Running captures retain
the decision made at their first useful media write.

Inheritance is evaluated when capture is attempted: an explicit episode override wins over
an explicit series rule, which wins over the current global default. **Use Default Policy**
retains this inheritance rather than copying today's value. Changing a rule applies to its
inheriting pending episodes, including episodes already booked; explicit episode overrides
survive. Existing bookings/rules migrate to inheritance. Unknown future policy strings safely
use Record Remaining, without making Room unable to open the library.

## Timing and results

All comparisons use UTC epoch milliseconds, so display timezone and DST changes do not alter
program duration. Current wall-clock corrections are observed on each start check.

- Program start/end are the EPG airtime snapshot saved with the booking.
- Effective capture start is that start minus the selected start padding (or the accepted
  conflict-adjusted start); effective capture end includes the saved end padding and any
  explicit duration edit.
- Actual capture start records the instant valid MPEG-TS data is ready for its first destination
  write, after storage preparation. Merely launching a worker or receiving HTTP 200 does not
  establish capture. Start preparation leaves this timestamp unset.
- The existing **five-second** program-start grace remains inclusive: exactly start + 5,000 ms
  is within the on-time window. Later useful capture misses program content and is Partial.
  Missing only optional start padding does not make a complete program Partial.

**Record Remaining** can start until the effective capture end, including during end padding.
It retains the configured end; it never adds missed time to that end. Late useful media produces
Partial, while no useful media cannot be Completed. Existing storage/failure/interruption and
explicit stop semantics still determine the final result.

**Skip If Late** marks a booking Missed when useful capture cannot begin within the existing
five-second grace. Capture that already began keeps its existing recovery behavior; a reconnect
is not a new late start.

**Minimum Time Remaining** compares `programEnd - max(actualStart, programStart)` with the
selected threshold. Equality is allowed. A 19:00–20:00 program with a 15-minute threshold may
start at 19:45 exactly, but not at 19:45:00.001. End padding and manual duration extensions do
not count as remaining program time. This policy also rejects programs shorter than its threshold.

Legacy/unbounded recordings without program metadata can use Record Remaining, which does
not invent coverage. Explicit Skip If Late or Minimum Time Remaining cannot verify unknown
program bounds and marks them Missed with an explanation. Invalid known program bounds also
skip. A completed, canceled, missed, failed, partial or finalizing row cannot restart from an
old alarm. Retry preserves prior content in a separate entry, uses a new destination and applies
the selected policy again.

## Admission, background capture and history

One evaluator is shared by due alarms, launch/boot reconciliation, background service admission,
account-conflict waits and first useful media. It reads current inheritance, current bounds and
lifecycle state. Policy is rechecked after slow provider/storage work and around optional-viewing
reclamation. Validated media reaches storage only after the final check. Output is opened lazily;
a rejected start creates no persistent empty capture file. If slow external storage crosses a
policy boundary during opening, the newly created empty destination is removed after closing.

Account conflicts keep independent pools separate and honor explicitly linked shared accounts.
A finite active recording can defer a waiting booking until capacity is expected; the prospective
start and the eventual start both use its policy. A duration extension triggers another check when
the wait expires. No active recording is silently canceled to admit a late booking. Protection,
manual-before-series scheduling priority, padding locks and the existing conflict planner remain
in force. Unknown additional-account allowance still requires existing approval; policy never
grants provider access. Empty transient network attempts release capacity and rearm bounded
bookings for a later policy-checked attempt.

Missed decisions are committed atomically with credential-free history before playback
reclamation. First useful capture records policy/threshold, program and padded bounds, actual
start, delay and whether content was missed; finalization adds Completed/Partial/Missed/Failed
or Canceled. A skip based on expected future provider availability is timestamped when the
decision is made and labels the anticipated start separately; it never invents an actual capture
start. History explains program-start skips, insufficient remaining time, waiting for
provider capacity/network, and capturing remaining content. It contains no stream URL or
provider credentials. A start event's timing source identifies scheduler/device wakeup or provider
availability collectively; Android cannot reliably attribute every delayed alarm to a sleep cause.

The existing foreground service, reservation allocator, deadline watcher and unique sinks remain
shared. No new recorder, background-start exemption, provider substitution, retention deletion,
Multi-View player or audio-focus behavior is introduced. Already-Missed rows cannot reclaim
panels; eligible recordings retain account-scoped reclamation while unrelated panels continue.
The four-panel limit, compact Guide/Sports flows, presets and 150 ms focus-follow audio remain.

Android alarm/foreground-service restrictions, exact-alarm access, OEM sleep policy, working
storage and a running device still govern capture. A powered-off TV cannot record; Nuvix cannot
recover a missed live broadcast. Local capture remains limited to supported direct MPEG-TS
sources. Process death preserves recoverable partial files, without claiming seamless resumption.
Preparation interrupted before confirmed useful capture rearms with the current policy and a new
non-overwriting destination. An uncertain external destination is retained as a separate recovery
entry. Reconciliation waits for this recovery barrier before enumerating scheduled bookings.

## Persistence and validation

Room **15 → 16** adds policy/threshold inheritance columns on recordings and series rules plus
recording decision/revision metadata. It does not rewrite existing windows, provider settings,
files, protection, padding locks, duration revisions, saved media or history. Historical launch
timestamps are not used to prove that PREPARING recordings captured bytes. Existing stored
recording results are preserved.

Deterministic JVM, Room, fake-clock/provider and Compose tests cover grace boundaries, padding,
threshold equality/custom values, actual-media gating/reconnects, inheritance, stale policy
callbacks, duplicate claims, reconciliation, account waits/extensions, independent accounts,
protected/locked preferences, migration preservation, history secrecy and remote actions.
The existing duration, provider/Multi-View and security suites continue to run in CI.

## Manual Google TV checklist

- [ ] D-pad Up/Down/Left/Right, OK and Back across global settings, Guide scheduling, episode
  options and series options; visible borders, readable text, scrolling and focus restored
  to the invoking action after dialog dismissal. Verify custom minimum limits/presets.
- [ ] Save/cancel each policy; change global/series defaults with pending episodes and explicit
  overrides; reopen the app and verify persistence without resetting padding/protection.
- [ ] Deliver alarms during start padding, at program start, +5 seconds, +6 seconds, threshold
  equality, near program end, end padding and after capture end. Check Partial versus Completed
  and Missed reasons, actual start/delay, no empty output and no compensated end.
- [ ] Screen off, app backgrounded, device wakeup, boot, force-stop/relaunch, duplicated alarms,
  network unavailable/returning, slow provider response and process death during preparation.
- [ ] Known/unknown/shared/independent account limits, two simultaneous starts, locked padding,
  protected manual versus series bookings, capacity released late and active end extensions.
- [ ] Keep four eligible Multi-View panels, compact Guide, Sports and presets active; ensure
  skipped recordings release reservations and do not reclaim panels or change audio behavior.
- [ ] Extend/shorten/restore an active recording near its deadline, clock/timezone/DST changes,
  storage full/unavailable, USB removal and NAS delays; retain prior bytes and stale-callback safety.
- [ ] Upgrade populated v15 data; retain provider credentials/configurations, series, media,
  activity history, original ends, duration revisions, protection and padding locks.

Physical Google TV/Android TV/Fire TV and production-provider checks are separate from automated
tests and have not been performed by the implementation environment.
