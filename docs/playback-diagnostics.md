# Advanced playback diagnostics

During live TV, a movie, an episode, or supported recording playback, reveal the playback
controls and select **Stream Information**. Playback keeps running. D-pad through the
controls and statistics; **Close** or **Back** dismisses the panel and returns to playback
controls. Long lists scroll with D-pad focus. No touchscreen is required.

**Compact** shows playback health/status, resolution, codec, format frame rate and buffered
duration. **Detailed** adds audio, renderer, network, error, buffering and recent-history
information. The view button remembers your choice locally. Under **Settings → Display &
playback → Playback Diagnostics**, enable/disable the entry, choose the default view, hide
technical measurements, or clear the current session's counters/history. Clearing statistics
does not retune, change buffer settings, or alter tracks. The panel is hidden until opened.
New interface text uses English fallback in other locales.

## Interpreting measurements

- Resolution, codec, frame rate, audio channels/sample rate/language come from Media3's
  current renderer input formats. A frame rate is the format's declared rate, not a
  measured display refresh rate. Selected audio track is its ordinal among audio tracks;
  provider labels are not retained. Missing metadata displays **Unavailable**.
- Measured **video bitrate** is unavailable: Media3 transfer samples do not isolate video
  elementary-stream bytes. **Declared video bitrate** is shown separately when the format
  provides it; it is not a network measurement.
- **Network throughput (last transfer)** is bytes × 8 / elapsed time from Media3's bandwidth
  listener, across network loading, potentially including audio/manifests. It is not
  continuous link speed or video bitrate. **Available bandwidth (estimate)** is Media3's
  adaptive estimate, explicitly labeled. Both become unavailable after ten seconds without
  a new sample. Local, SMB, RTSP and growing recordings may not provide either value.
- Buffered duration uses `totalBufferedDuration`, not a subtraction of live-window positions.
  Playback position is within Media3's current window/item; playback speed is the current
  Media3 parameter. Protocol is identified only from a known MIME type, manifest extension,
  RTSP scheme, or supported local/SMB recording scheme. Generic HTTP URLs show unavailable.
- Decoder name and rendered frames require renderer callbacks. Dropped frames accumulate
  reported dropped-video-frame events. Counters and formats may be unavailable during
  startup, track changes, audio-only playback, or unsupported renderers. HDR requires an
  explicit PQ/HLG transfer or Dolby Vision MIME type; no HDR claim is inferred from resolution.

## Buffer health rules

These are simple status rules, not a numerical quality score, evaluated in this order:

1. A current terminal playback error: **Playback error**.
2. Media3 BUFFERING while playback is requested: **Buffering**.
3. A rebuffer, decoder error, or terminal stream failure within 30 seconds:
   **Experiencing interruptions**.
4. Playback has produced a playing event: **Playing normally**.
5. Otherwise: **Insufficient measurement data**.

A rebuffer/stall is a new BUFFERING transition after playback has started, while playback is
requested, excluding explicit seeks and initial startup. Repeated events during the same
stall count once. Time buffering includes startup and active stalls, excludes paused time,
and uses the monotonic clock. Rebuffer frequency is session count divided by elapsed session
minutes and remains unavailable until at least one minute is observed. Stream interruptions
count non-decoder terminal playback failures; decoder errors count codec/fatal decoder events.
These counters describe observable player events, not every lost packet or provider outage.

## Troubleshooting and privacy

Connection failures: check the device network and provider access. Timeouts: retry when the
provider is responsive. HTTP 401/403: check account access, concurrent-stream limits and the
source User-Agent setting. HTTP 404: refresh the catalogue. Unsupported formats or decoder
failures: try another quality or audio track. Audio failures: change audio tracks. Source
failures on recordings: check local/NAS permission and availability. The existing player
recovery policy still applies; diagnostics introduces no retries, restarts or buffer changes.

Exceptions are classified through a bounded cause chain using fixed user-facing messages;
raw exception text, headers, URLs, stream tokens and provider labels are never displayed or
stored in diagnostics. History is memory-only: at most 300 events from the last five minutes,
covering buffering/state changes, transfer samples and dropped-frame changes. Switching
media resets the metrics; retrying the same item retains continuity. Owner stop/release clears
history. No diagnostic measurements are cloud-synced or written to disk.

## Ownership and performance

One `PlaybackDiagnostics` observer belongs to each existing `PlayerController`. It registers
one Player listener and one AnalyticsListener on that controller's ExoPlayer. No new player,
source, stream connection, recording job, or network request is constructed. The shared live
session retains the observer across navigation/configuration changes. Its listeners are
removed before player release. Preview/low-memory playback uses the same passive observer.

Only the visible panel samples position, buffers, formats and counters, once per second while
the Activity lifecycle is STARTED. Closing it cancels sampling. Event counters remain cheap
and bounded while hidden so opening the panel can report preceding interruptions. PiP hides
the panel and stops its sampling; scheduled recording ownership is untouched.

See [testing](TESTING.md#playback-diagnostics) for automated coverage and required hardware checks.
