# Persistent live TV playback

Start a live channel, then press Back to browse Home, Live TV/Guide, Sports, or Search.
The active stream stays in the same live session. Select its preview or reopen the current
channel to return to fullscreen without retuning. Explicitly choosing a different channel
changes the shared session; merely highlighting a guide row does not.

## Local preferences

Open **Settings → Display & playback → Playback**. All three preferences default to On
and stay in the existing local `AppSettings` preferences; they are not cloud settings.

| Setting | On | Off |
|---|---|---|
| Persistent Live Playback | Retain live playback across supported in-app navigation | Release live playback when leaving fullscreen; opening a channel creates a fresh session |
| Live Preview | Show the retained channel in supported browsing screens | Hide its video preview while retaining the session |
| Continue Audio While Browsing | Preserve the user's audio choice while browsing | Temporarily mute browsing audio; restore the prior volume on return to fullscreen |

Browsing mute does not change system volume or the user's pause choice. The existing
**Live preview in the guide** preference also controls guide video. Without an active live
session, the optional guide preview and its independent **Preview sound** preference remain
available. The initial/explicitly selected preview channel stays stable while D-pad focus changes
program information. With an active live session, guide highlighting cannot
start a competing preview player or change the channel being watched. The optional guide
controller registers with the Activity session so notifications and startup tunes can
release it synchronously before opening live playback.
The standalone preview also reserves from the provider account's shared connection pool.
When local capacity is unavailable, or another connection to an Unknown allowance would
need consent, the optional preview stays off. Explicit Watch actions use normal live
admission and show any request-specific approval instead of silently opening a stream.

The preview shows the current channel, available logo and programme, live status, and
fullscreen/retry/stop actions. It is focusable with a visible D-pad focus indicator and does
not handle unrelated remote keys. No session means no persistent preview and no automatic
provider connection.

## Smart Multi-View

Choose **Multi-View** from the live player's controls or main navigation to watch up to four
channels. Entering closes the single-channel owner before Multi-View opens its primary, so
the old viewer does not remain as a hidden extra connection. This entry may re-resolve the
current stream; layout, audio and fullscreen changes inside Multi-View preserve player
identity where supported. Exiting Multi-View releases its players and returns to browsing.

The Activity's `LivePlaybackSession` owns the dedicated `MultiViewSessionManager` alongside
the existing single-channel coordinator. Both use PR #35's application-scoped account
allocator. A Multi-View start hands off single-channel/optional-preview ownership; an
explicit single-channel tune releases the wall. Recordings retain their independent
foreground service and reservations. Multi-View secondary owners yield before its primary
when the existing recording policy reclaims same-account viewing. Other accounts continue.

Before a scheduled recording needs occupied account capacity, the global [conflict review](smart-multiview-dvr.md#pre-recording-multi-view-conflict-review) offers explicit independent-provider replacement or release. The original stream stays active during candidate startup where possible; its panel position and audio role remain. Keep Watching dismisses review for that booking window; the independent recording service still reclaims capacity at the deadline. Guide/fullscreen navigation does not dismiss the warning or authorize a provider change.

Two equal panels, a three-channel primary-plus-stack, a four-channel grid and a focused
primary-plus-stack use the same stable panel identities. Reordering and surface attachment
do not allocate players. Each hardware-decoded panel has its own exclusive
`PlayerSurfaceOwner`; a late outgoing view cannot detach its replacement. Admission starts
one channel at a time and never exceeds four allocated players, including a candidate
replacement. Make-before-break requires confirmed account and decoder headroom; constrained
or Unknown allowances use break-before-make and fresh approval when needed.

The wall has one Android audio-focus lease. D-pad focus selects audio after a cancelable
150 ms delay by default; OK-only mode waits for explicit selection. Guide navigation does
not change audio. Fullscreen preserves the selected player and releases secondary resources;
Back defaults to asking before progressive restoration with fresh capacity checks. PiP uses
one selected panel and preserves the existing single-channel PiP path.

Ordinary backgrounding releases Multi-View players/reservations and leaves paused panels
for explicit review/resume. Memory pressure pauses secondary panels. Process death restores
no runtime lease: safe local presentation state offers Review & Restore without connecting.
Preset preview also opens no stream. Saved/recovery layouts now show effective upcoming DVR
windows over 24 hours and allow one-time panel/source/primary edits. Confirmation re-reads the
catalog and schedule; each stream revalidates again after an Unknown prompt. Future warnings do
not reserve capacity or suppress the deadline coordinator. See the [Multi-View guide](smart-multiview-dvr.md#multi-view-playback)
for controls, settings, Sports/Guide actions, presets and compatibility limits.

## Ownership and surfaces

`MainActivity` obtains one `LivePlaybackSession` from its Activity `ViewModelStore`.
The `AndroidViewModel` uses application context and owns `LivePlaybackCoordinator`, which
creates the existing `PlayerController` lazily. There is no application singleton holding
an Activity, player View, or an always-running playback service. The existing repositories
resolve channels, source-specific stream URLs, and User-Agent behavior.

`PlayerScreen` borrows the live controller; removing that composable detaches its View
without releasing the session. Home, Guide, Sports, and Search present that same player.
Presentation changes do not call `setMediaItem()` or `prepare()`. Reopening the current
channel is a no-op; channel changes and explicit retry resolve a fresh playback request.
Cancellation and generation checks prevent an obsolete lookup from tuning after a newer
selection. Existing buffering configuration, audio/subtitle selections, Media3 errors,
and bounded retry behavior stay with `PlayerController`.

Actual channel changes now use the shared account allocator. A replacement resolves while
the old stream is still owned, then safe switching closes the old decoder before opening
the new one. Known unavailable capacity can be rejected before relinquishing playback.
The single-channel coordinator continues to use break-before-make; Multi-View additionally
permits a bounded candidate when account and decoder headroom are confirmed. Presentation-only
surface transfers retain the existing player. See [connection rules](smart-multiview-dvr.md#connection-management-and-dvr-foundation)
for unknown allowances, manual account associations and local limits.

`PlayerSurfaceOwner` grants one surface attachment at a time. A replacement `PlayerView`
first detaches its predecessor. An outgoing Compose `AndroidView` can only detach its own
lease, so late disposal cannot remove the replacement's video. Detaching a View does not
release playback or start a second decoder. If a device rejects attachment, a fallback
message offers a return to fullscreen while retaining audio/session state; it does not
construct another player. Views and their Activity references are cleared on disposal and
Activity destruction. A rejected detach blocks further attachments; if cleanup still
fails when the Activity is destroyed, live playback is released rather than retaining an
Activity View or binding a competing surface.

## Lifecycle, audio focus, and recovery

- Configuration changes retain the ViewModel and controller and recreate only UI surfaces.
- Ordinary backgrounding and unsupported screens suspend loading, automatic retries, and
  playback. Foregrounding restores the retained media item when appropriate; that recovery
  may prepare the stopped item, unlike an in-app surface transfer.
- Media3 manages Android audio focus. Loss of focus cannot be bypassed by a navigation
  mute/unmute; permanent focus loss preserves a paused intent until the user resumes.
- PiP uses the same live controller and keeps its fullscreen video surface. PiP suppresses
  the browsing preview, preserves existing controls, and allows the supported PiP lifecycle
  to continue playback. Returning attaches the normal fullscreen surface.
- Explicit stop, sleep timer expiry, Activity ownership ending, or non-live playback
  relinquishes live resources and cancels its work. Background recordings have separate
  ownership and are not released by stopping the live session.
- Process death destroys the player. Only safe channel identifiers already used by the
  existing last-channel/startup preferences persist; startup resolves a fresh channel and
  stream only when those preferences request it. Stream URLs, buffers, player instances,
  and Views are not serialized.

Network/provider failures remain observable after navigation. Retry uses the existing
bounded controller policy; the preview's explicit Retry action can re-resolve an expired
provider link. Navigation does not retry or restart a healthy channel. Diagnostics must
not include provider credentials, tokens, or credential-bearing stream URLs.

## Recordings, VOD, and channel controls

Starting VOD, catch-up, or recording playback releases the retained live session before
the other player is allocated. Returning from that content does not silently reopen live
TV. Dedicated VOD/recording screens register their playback controller so an explicit
live tune also releases their viewing connection synchronously. This protects single-connection providers from an extra retained viewing stream;
live and DVR capture now reserve from the same account pool. Recording actions do not
unconditionally close unrelated live playback or stop other recordings. Independent
accounts remain independent, while explicitly associated playlists share their configured
allowance. Recording admission may reclaim optional same-account viewing according to
the recording fallback; it never reclaims another capture through that viewer mechanism.
A denied live request gives a recovery message instead of opening an extra stream.
An additional unknown-capacity request needs its own approval. Growing internal-recording
playback reads the captured file and does not itself open another provider stream.
Viewing and capture otherwise use independent reservations; shared stream ingest is not
implemented. See [DVR architecture and limits](smart-multiview-dvr.md).
Scheduled capture does not navigate away from viewing by default: recording auto-switch
defaults Off, with any explicitly saved On preference retained. Capture keeps the selected
recording source and never silently substitutes a similarly named provider channel.

The existing fullscreen channel-list overlay uses the shared live channel-change method.
Opening/dismissing it keeps the player, buffer, and track selections. This feature has no
dependency on an unmerged Mini TV Guide branch; a future Mini Guide should use the same
session operations rather than owning another player.

## Limited-memory devices and verification

One retained live controller uses the existing buffer policies. Memory-pressure callbacks
release optional browsing playback, hide its preview, and leave recording ownership alone.
The guide does not replace a reclaimed decoder with a new highlighted-channel preview.
The saved last-channel metadata remains available for a subsequent explicit open.
Fullscreen and PiP are not interrupted solely to reclaim a browsing preview. Turning
**Persistent Live Playback** off provides the release-on-navigation fallback on devices
that cannot reliably retain a decoder. No model-specific blacklist or new buffer sizes
are introduced without device evidence.

Decoder initialization and surface behavior differ across Android TV, Google TV, and Fire
TV hardware. No particular device is certified by fake-player or Robolectric tests.
Hardware acceptance must cover repeated surface transfers, D-pad focus, focus loss,
PiP entry/exit, Activity/process recreation, memory pressure, encrypted/local/NAS/USB
recordings, and authorized single-connection providers. See [testing](TESTING.md) for the
validation commands and device checklist. New preference and preview text currently uses
English fallback in other locales.

## Playback diagnostics

`PlayerController` owns one passive `PlaybackDiagnostics` observer, which stays with the
shared live session across screen/surface changes. `StreamInformation` uses a separate
Compose dialog focus window and samples at 1 Hz only while visible and STARTED. Listener
cleanup precedes player release. Selecting a new media URL resets the diagnostic session;
existing retries and surface transfers retain its counters. Settings clear the existing
observer without allocating a player. The same panel serves VOD/episode/recording controllers.
See [measurements and health rules](playback-diagnostics.md) for limitations and privacy.
