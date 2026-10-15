# Mini TV Guide

During full-screen live TV, let the player bar hide, then press **D-pad Left** to open the Mini TV Guide. Video stays full-size behind a dark translucent panel. Opening, moving focus, filtering and closing do not submit playback requests or create a second player.

| Remote action | Result |
| --- | --- |
| Left with the player bar hidden | Open the guide |
| Up / Down with the guide open | Highlight channels without tuning |
| OK / Select on a channel | Tune once, then close; selecting the playing row just closes |
| Back | Close and return focus to the player |
| Left / Right with the guide open | Switch between the browsing list and its favourites |
| Up from the first row | Reach the filter and Last channel buttons |
| OK on Last channel | Recall the previous channel through the existing tune pathway |
| Number buttons | Close the guide and use existing direct number entry |
| Up / Down or Channel Up / Down outside the guide | Existing channel switching |
| Right with the guide closed and the bar hidden | Existing quality picker when variants are available |

The list retains the full guide's current provider, category, search, favourites and hidden-channel context, along with its ordering and stable channel identities. The favourites filter narrows that list; it never reveals channels outside the browsing context. Choose a different provider/category or custom group in the full guide, then start playback to use that context. There is no second favourites store or ordering system. The playing indicator also recognises quality variants.

Rows show the channel number (when supplied), logo, name, playing marker, current title, local start/end time, progress and upcoming title. EPG times remain UTC internally; display uses the device's time zone and 12/24-hour preference. Missing, stale or failed metadata displays **No program information.** Playback continues. Descriptions are optional and are not required for a row.

The guide observes bounded current/next queries in the existing Room EPG cache for visible rows. Candidate precedence follows the full guide: manual override, provider ID, then matched ID, including quality variants. Disabled feeds and expired entries are skipped. Program boundaries and database invalidation update the rows, with a minute check for clock changes and recovery. Opening and focus movement do not refresh or download EPG feeds.

## Settings

Under **Settings → Display & playback → Mini TV Guide**:

- **Enabled:** on by default. Turning it off retains a compact legacy channel list, channel logos, playing marker and Last channel navigation, without EPG subscriptions or auto-close.
- **Transparency:** Low, Medium (default), High. Low transparency gives the strongest background for readability.
- **Position:** Left (default) or Right.
- **Auto-close:** Off, 5, 10 (default), or 15 seconds of inactivity. Remote activity resets the timer; a held key keeps the guide open until release. System accessibility timeout recommendations can extend the delay.

These preferences use existing local application preferences and are not cloud sync records.

## Accessibility and device considerations

The overlay uses Nuvix theme focus colours, an outlined focus ring, large text, explicit playing text, merged row semantics and selected/playing state descriptions. It supports remote-only navigation, system time formatting, safe drawing insets and an additional TV overscan margin. Compose fade transitions follow the system animation scale. The video surface is never resized. PiP immediately hides the guide and returning from PiP leaves it closed.

The list uses stable lazy item keys. Logos use existing Coil caching, and only visible rows subscribe to EPG data. The guide closes when the player activity stops. Overlay coroutines and Room subscriptions stop when their UI leaves composition. Quality, subtitles, audio, buffering and retries remain owned by the existing player/controller. VOD, catch-up and recordings retain their own screens.

Android TV, Google TV and Fire TV firmware differ in remote key mapping, accessibility, PiP and decoder support. Verify focus, overscan, readability at 720p/1080p/4K, and uninterrupted playback on target hardware. No live provider or real-hardware verification is implied by the automated tests.

## Validation

Mini Guide JVM tests cover cancellation and deduplication of provider URL resolutions, shortcut routing and overlay precedence, selection vs browsing, same-channel/quality-variant safeguards, empty/stale lists, favourites order, rapid navigation, PiP state and timeout behaviour. Room/Robolectric tests exercise bounded queries, feed filtering and program transitions. Settings tests reload preferences. Compose/Robolectric tests check initial focus, missing/failed metadata, explicit selection and visible-row EPG work.

The existing Build workflow runs these tests with all Android unit tests, Kotlin compilation, lint, debug APK assembly and the release-publication Python tests on pushes, pull requests and manual dispatches. Test XML and HTML reports are uploaded as validation artifacts. See the feature PR for actual command results and any environment limitations; a passing JVM suite does not establish device playback continuity.
