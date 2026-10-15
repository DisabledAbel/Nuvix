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
session, the existing highlighted-channel guide preview and its independent **Preview sound**
preference retain their behavior. With an active live session, guide highlighting cannot
start a competing preview player or change the channel being watched. The optional guide
controller registers with the Activity session so notifications and startup tunes can
release it synchronously before opening live playback.

The preview shows the current channel, available logo and programme, live status, and
fullscreen/retry/stop actions. It is focusable with a visible D-pad focus indicator and does
not handle unrelated remote keys. No session means no persistent preview and no automatic
provider connection.

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
immediate recording actions also relinquish live playback before capture starts. New scheduled
captures release same-provider live viewing without stopping the recorder. A live request
against a provider already recording shows a recovery message instead of opening a competing
stream; watch the growing recording or stop capture first. Future
recording scheduling, watch-while-recording, and NAS/USB playback retain their
existing implementations and provider limitations.

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
