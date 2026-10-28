# DVR Storage Manager

Open **Settings → Recording → Storage Manager**, or use **Storage Manager** at the top
of **Recordings**. This manages recording storage without changing the selected recording
destination, provider connection allowances, conflict priorities, capture headroom, or padding.

## Space and estimates

Each configured device, USB/external folder and NAS destination has an availability status
and tracked recording usage. Local storage supplies available/total space. USB document
providers and the current SMB adapter do **not** supply reliable capacity; these display
**Unknown**, even when recording and manual deletion work. Availability checks do not create
probe files. NAS checks request directory list, create and delete-child permissions without
opening a provider stream. Disconnected storage, missing permissions and read-only storage
have separate explanations. Some servers report a read-only account as permission denied.

Usage comes from Nuvix's tracked byte counts, including active captures, rather than a disk-wide
scan; other files and filesystem overhead are excluded. Previous or unidentifiable destinations
are grouped under Other tracked destinations with unavailable capacity. Summaries refresh every 30 seconds
while the manager is open, and **Refresh storage** requests a fresh observation. Storage
values and cleanup-history entries can receive D-pad focus, including when no recordings
are selectable. Capacity older
than 60 seconds, from the future, malformed or unavailable is not used for estimates or cleanup.

Remaining hours are explicitly an **estimate**, using up to 20 completed recordings from the
same destination in the last 30 days. Samples require actual capture times, at least one minute
of captured content and a plausible measured byte rate. Missing samples or highly inconsistent
rates produce **Unknown**. The estimate uses the highest measured rate and leaves the existing
256 MiB capture reserve free. Different channels, bitrates and simultaneous captures can consume
space faster; this is not a guarantee or a change to recording admission.

Low-space warnings are on by default **in Storage Manager**, at 1 GiB or 5% of total capacity,
whichever is greater. **Configure storage policy** changes the threshold or disables the warning
independently for each destination. These advisory warnings do not stop or move captures.
Unknown capacity cannot generate a reliable threshold warning. This feature does not add push
notifications or exact low-space alarms.

## Review and delete

The completed-recording list includes completed, partial and failed captures. Sort by newest
date or largest size, then use D-pad and OK to select recordings. **Review deletion** lists the
exact titles, dates, sizes and total being proposed. Cancel is initially focused. Only
**Confirm delete recording files** deletes files. Back/Cancel abandons the review.

Active/preparing/finalizing recordings are ineligible. To delete a protected recording, first
turn off **Protect Recording** explicitly in Recording Options. **Lock Padding** only preserves
padding against automatic conflict trimming; it is not deletion protection.

Nuvix rechecks the reviewed row immediately before deletion and skips changed, protected or
active recordings. A successful storage operation is followed by an atomic deleted status and
history entry. The row and prior activity remain available for audit; it cannot be played or
retried as an existing file. Storage failures never remove the row or its history. A batch stops
at the first storage error; earlier successful deletions remain recorded. If a backend loses its
connection while deleting, success may be uncertain: refresh and check the destination before
retrying. No recursive directory removal or unrelated-file discovery is performed.

## Optional automatic cleanup

Cleanup is **off by default**, independently for every destination. Configuring a target or
retention does not enable it. The separate **Enable automatic cleanup** action explains the
policy and requires confirmation for that destination. Defaults are a 2 GiB minimum free-space
target and at least 30 days since recording completion. Higher targets and shorter retention
can delete more recordings; review the explanation before enabling or saving changes.

When enabled, hourly best-effort Android WorkManager maintenance tries to reach the target by
deleting eligible oldest recordings first (completion time, then local ID). Android may delay
background work. Each pass examines at most 200 candidates and deletes at most 10 files. The
manager shows the latest result and the latest 50 deletion/cleanup events; full recording activity
remains in Recording History. A target may require several passes or may be impossible to meet.
The result warns when eligible cleanup cannot meet it.

Only files with a device-local Nuvix creation receipt matching both the tracked recording ID
and locator are eligible. Existing recordings from before this feature, imported recordings,
foreign files, active recordings and protected recordings are excluded. Receipts are made only
after Nuvix creates an output, including its resolved USB document URI. Policies and receipts
are device-local and are not exported by NAS/cloud sync. Changing a destination root requires
separate consent; a retry/new locator does not inherit ownership from a different file.

Eligibility, opt-in, retention, protection and active state are checked again immediately before
each deletion. Slow storage operations do not hold the global capture-admission locks. Actual
capacity is reread after every deletion; row sizes are never added to a guessed free-space
balance. Errors, stale/unknown capacity or lost connectivity stop the pass safely. USB and SMB
currently have unknown capacity, so automatic cleanup on these destinations will stop without
deleting anything. Their manual review/deletion remains available. Cleanup never moves
recordings, switches destinations or changes connection/conflict/padding policies.

A crash between filesystem success and the database transaction can leave a tracked row for a
missing file. Nuvix conservatively reports failure instead of treating an absent or uncertain file
as successful deletion. Inspect that destination before retrying; there is no cross-filesystem
transaction or automatic missing-file reconciliation in this change.

## Validation and remaining device checks

Focused JVM/Robolectric tests cover capacity capabilities/freshness, measured estimates,
thresholds, opt-in, retention/order/bounds, protection versus padding, live-state and concurrent
changes, failed/partial deletion, ownership, disconnected storage, confirmation, focus refresh,
D-pad/OK/Back dispatch and large text. These are software tests, not hardware acceptance.

The following remain **unverified on physical hardware**:

- Android TV, Google TV and Fire TV: D-pad traversal, OK/Back through both entry points, dialog
  scrolling with largest system font, TalkBack labels, focus after refresh/delete and return.
- Real local storage near warning/reserve thresholds with simultaneous captures and scheduled
  starts; WorkManager timing while sleeping, after reboot, force-stop and app update.
- USB SAF providers: unplug/replug during probe/delete/write/finalization, revoked/regranted
  permissions, read-only media, full drives, slow providers and API 23 provider differences.
- NAS/SMB: real server ACLs, read-only share status, sleeping/offline NAS, connection loss during
  deletion and uncertain deletion acknowledgement; confirm credential/path/URL redaction in
  device/server diagnostics. Capacity remains unsupported and automatic cleanup must stay inert.
- Power loss between storage deletion and history commit, and recovery of uncertain operations.

No emulator or physical-TV/USB/NAS testing is claimed by this document.
