# Active recording duration

Open **Recording Duration** from an active recording in Recordings, recording details,
Live TV's record control, Guide program actions, or the Multi-View toolbar. Use the D-pad
and OK to choose Add 5, 10, 15, 30 or 60 Minutes, Choose End Time, Restore Original End,
or Stop Recording Now. Back/Cancel leaves the recording unchanged. The current end,
remaining time, original program/padded ends, manual change, modification time, protection
and padding lock are displayed. Custom time uses D-pad date/hour/minute steppers with a timezone display; review
shows the resolved timestamp, including the date, before saving. Elapsed ends require a
separate **Confirm Stop Recording** action. Times before capture start are rejected. If a selected end passes while the review is open
or while confirmation is being validated, the change is rejected without stopping capture.
Review again to see **Confirm Stop Recording** and explicitly approve stopping. Stop consent
is tied to that displayed review, including when the device clock moves forward.

The defaults remain two minutes early and five minutes late. The original padded end
uses the booking's saved override, not today's global settings. Extensions add to the
effective end without adding padding again. The first edit freezes the original padded
end; Restore returns to it. Legacy rows retain their saved window. Recordings originally
without a scheduled end can receive an end but cannot restore an unknown original end.
EPG refresh does not rewrite active booking snapshots or discard manual changes.

Protect Recording and Lock Padding remain separate choices. An explicit duration edit
can shorten locked padding after its warning; the lock stays enabled. Automatic conflict
resolution still cannot trim locked padding or an active window. Protection is never
silently toggled, and editing duration does not enable protection or lock padding.
Stopping early preserves content and may produce Partial rather than Completed.

## Conflicts and storage

Review uses the existing `DvrConflictPolicy`, shared account identities and configured
capacity, including active/pending recordings, padding and manual/series priority. It shows
affected programs, account capacity and consequences. Adjust Extension, Review Conflicting
Recording and Cancel remain available. A conflict against higher-priority demand blocks
confirmation. Where existing priority permits the end, Confirm With Warning records explicit
consent. This does not reserve future provider capacity or bypass admission at capture start.

For a conflicting scheduled recording, Choose an Authorized Alternative reuses the existing
exact-guide/verified-Sports matching. It offers enabled independent providers with a known
allowance. Confirm Provider Choice rechecks the full recording window and preserves the
booking's padding/protection, then requires a fresh duration review. Running captures are
never moved to another stream by this action. Missing alternatives require adjusting the
extension or reviewing the conflicting booking.

An estimate uses observed captured bytes after ten seconds, otherwise a conservative
8 Mbps estimate. Local free space is checked against headroom and expected remaining
writes from concurrent captures. Low predicted space produces a warning; unavailable
local storage or headroom blocks a longer end. USB/document providers and NAS may not
expose reliable capacity, so the UI discloses this. Existing write/storage monitoring
continues; removal, full storage and finalization failures preserve recoverable content
as Partial/Failed. No automatic retention or deletion is introduced.

Multi-View/preset planners observe the same updated rows. Existing pre-deadline warnings
and DVR reclamation remain authoritative; reviewing a future conflict does not release
panels. Duration editing creates no players, provider reservations or audio-focus changes.

## Persistence and background capture

Room migration 14 → 15 adds original-end, manual-change, modification-time and revision
columns with defaults, without rewriting booking windows or files. Editing reads fresh
state, rechecks policy on confirmation, and commits a revision comparison plus activity
in one transaction. That transaction also checks the complete recording-demand snapshot;
byte-progress changes do not invalidate it. Duplicate OK and stale editor confirmations
cannot apply the increment twice. New bookings, finalization, protection or account-policy
changes require review again. Rejected confirmations, including stale demand or elapsed-end
consent, add a credential-free `EXTENSION_REJECTED` entry to recording history.

The foreground service keeps its existing HTTP response, output sink, progress and account
reservation. A separate 250 ms deadline watcher reads the committed end. It must atomically
claim FINALIZING for the observed revision before canceling I/O. An obsolete callback cannot
win against a newer extension. Service capture no longer binds an HTTP timeout to an obsolete
end. Transport inactivity/retry and storage behavior remain unchanged. Deadlines use UTC epoch
time; display timezone/DST changes do not change duration arithmetic. Wall-clock corrections
are observed on the next deadline check; Android scheduling is not an exact timing guarantee.

Process death retains committed duration metadata and existing recoverable-partial behavior.
It does not resume a finalized file or promise seamless capture across a killed process.
Retries retain the updated end and use a new destination, preserving previous partial content.
No new Android permission or background service start is required for an edit.

Sports overtime, extra innings and delayed broadcasts use the same bounded manual additions.
No live game-status source or automatic sports extension policy is enabled.

## Device acceptance

JVM/Room/Compose tests are separate from physical-TV verification. On Android TV, Google TV
and Fire TV, including a low-memory model, verify:

- D-pad/OK/Back through every entry point, custom date/time pickers, scrolling, focus contrast,
  large text and accessibility services; Back and reviewing a past end must never stop capture.
- Extend close to the deadline while monitoring one HTTP connection, the same growing file,
  captured byte progress, provider capacity, unrelated recordings and all Multi-View panels.
- Shorten, restore and explicitly stop with protected/locked recordings; verify Partial status
  for stopping before program end, activity history, repeated OK and a finalized editor.
- Test known/shared/unknown limits, protected manual versus series demand, changed account
  limits, concurrent scheduling, authorized provider alternatives and warnings at the deadline.
- Test internal/USB/NAS storage, drive removal, full space, background recording with app closed,
  process interruption/retry, timezone/DST changes and device clock corrections.
- Upgrade a populated v14 database; verify old recordings, profiles, overrides and partial files.

No physical-device or real-provider acceptance is implied by automated tests.
