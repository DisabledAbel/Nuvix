# Changelog

## Unreleased

- Add lifecycle-owned Smart Multi-View with up to four live panels, shared account reservations,
  incremental decoder admission, stable panel identities and independent failed-panel actions.
- Add balanced two/three/four-channel and focused layouts, D-pad panel selection, explicit panel
  controls, Edit Layout, duplicate choices and resource-aware channel replacement.
- Coordinate one Android audio-focus owner with cancelable 150 ms focus-follow selection or
  OK-only audio; keep Guide navigation independent of the selected audio panel.
- Add compact Guide browsing and explicit Sports Hub broadcast actions, reusing authorized local
  catalog associations and provider choice. Enter fullscreen with the selected player and review
  secondary restoration against current provider and hardware availability.
- Add credential-free local preset CRUD, disconnected resource previews, interrupted-session
  review and local Multi-View settings. Restore primary first and never reconnect after a crash
  without confirmation. Physical-TV playback, PiP, audio focus and decoder acceptance remain
  separate from deterministic policy and UI tests.

- Add one account-aware local connection allocator for retained live playback, optional Guide
  preview and the existing recording service, with explicit shared-account links, local/provider
  allowance controls and one-request consent for additional unknown-capacity connections.
- Preserve exclusive live surfaces and use safe connection replacement; do not infer subscription
  limits from generic errors or claim capacity on other devices is known. Multi-View reuses
  the same allocator and keeps DVR capture ownership independent of visible playback.
- Add independent Protect Recording/Lock Padding controls, scheduled padding overrides,
  account-aware conflict review, deterministic deadline fallback and readable recording history.
  Use two-minute early/five-minute late defaults without replacing existing stored preferences.
- Add Preparing/Finalizing/Partial/Missed/Canceled lifecycle states and additive Room migration
  14, preserving existing providers, schedules, series rules and capture locators. Automatic
  deletion remains absent; active duration extension and alternate-provider selection remain future work.
- Harden existing capture with durable duplicate-start claims, recoverable partial content,
  collision-resistant destinations, local storage headroom checks, bounded empty reconnects
  and terminal storage failures. Validate direct MPEG-TS and reject playlists/nonmedia responses.
- Keep the selected recording provider authoritative and remove heuristic silent provider
  substitution. Default recording auto-navigation to Off while preserving explicit saved On;
  eligible background capture remains independent of visible playback.
- Declare DVR capture as an Android 14+ specialUse foreground service with an explicit subtype,
  add unattended-readiness checks and record denied/missed starts. Store approval and real-device
  background execution still require verification. Use user-granted SCHEDULE_EXACT_ALARM access
  and remove the restricted automatically granted USE_EXACT_ALARM declaration.

- Replace the basic Sports presentation with My Sports, Live/Upcoming games, favorite teams/leagues,
  All Sports and the existing category-based channel fallback in the same destination.
- Group enabled provider streams using exact league-scoped team evidence and time windows; distinguish
  confirmed matches from reviewable likely/possible suggestions and verified events from inferred EPG.
- Add local persistent favorites/order, preferred broadcasts, manual association/removal/restore,
  bounded search/filtering, game details and explicit stream switching through retained live playback.
- Deduplicate Sports/Guide reminders by game; preserve existing reminders in additive Room migration 13.
  Add stale/offline labels, optional verified-source score fields and replaceable schedule interfaces.
- Add synthetic matching, Room, persistence, reminder, timezone/DST and remote UI regressions, large
  catalog query validation in existing CI, source-terms review and physical-TV acceptance documentation.

- Add Standard, Compact and Expanded guide layouts, local guide customization, filters using
  existing channel organization, stable channel sorting, timeline controls and remembered position.
- Add bounded local EPG search and a remote-friendly program details panel with optional
  feed artwork, metadata-based categories and the existing reminder/recording/playback actions.
  Preserve automatic EPG discovery, atomic refresh and persistent live ownership.
- Add deterministic guide preference, navigation, database and Compose regressions to the existing
  test workflow; document remaining Android TV, Google TV and Fire TV hardware acceptance.
- Add remote-friendly home shelf ordering/visibility, Classic/Compact/Cinema layouts,
  card sizing/corners/metadata, solid/gradient/artwork backgrounds and featured-content
  selection. Preserve existing preferences, UUID groups and persistent live playback.
- Extend the opt-in home-preferences cloud category with validated whole-list order and
  versioned presentation records; add forward-only SQL migration, JVM/Compose and RLS tests.
- Add passive real-time playback diagnostics to live TV, movies, episodes and supported
  recordings, with remote-accessible Compact/Detailed panels, local preferences, bounded
  session history, documented health rules and sanitized playback errors.
- Add fake-player and Robolectric Compose diagnostics tests to the existing JVM/CI suite.
  Device playback, PiP and 720p/1080p/4K remote acceptance still require hardware testing.

- Extend optional Supabase sync with separate opt-ins for channel visibility, names and
  ordering; UUID custom groups and ordered membership; home shelf visibility; ordered
  pinned apps; and allowlisted appearance, subtitle, guide and startup preferences.
- Add an explicit favorite-removal opt-in while preserving existing additive favorites
  and watch-progress records. Retain missing channel/app references for reconciliation.
- Add a versioned advanced-record protocol, forward-only migration, owner-only RLS and
  atomic deterministic merges, durable encrypted account-bound pending changes, and
  per-category status controls. Keep email-code sign-in, WorkManager and LAN/NAS sync.
- Document migration rollout, conflict/deletion semantics, privacy boundaries, isolated
  SQL validation and a two-TV acceptance checklist. Hardware verification remains required.
- Retain one lifecycle-owned live TV player across main navigation, transfer its video
  surface between fullscreen and a current-channel preview, and add local persistence,
  preview, and browsing-audio preferences. Preserve guide-preview, channel-list, PiP,
  buffer, subtitle, recording, and VOD ownership paths; add playback regression tests.
- Add automatic keyless TVmaze TV metadata, full season/episode listings, cast and
  crew photos/characters, clickable actor profiles and credits, and CC BY-SA attribution.
- Prefer TVmaze, use IMDbAPI.dev secondarily, and retain optional TMDb fallback;
  label external ratings separately and preserve provider playback/cloud identities.
- Add bounded local metadata caches, throttling and HTTP 429 backoff plus regression tests.

## 0.1.0 — Nuvix development foundation

- Independently import OpenTV Android source at `43b95c05d8f6a042f51c0cff662ef80789ee7528`, preserving GPL-3.0-or-later notices and working provider/playback/guide/recording/LAN/NAS systems.
- Rebrand Android identity, icon, TV banner, splash and About; add Home, Live TV, Guide, Movies, TV Shows, Sports, Apps, Library and Settings navigation with provider-derived shelves.
- Add optional reversible HOME launcher entry, startup preferences and real installed-app launch/pinning/ordering.
- Add encrypted local database/secret preferences, retained-data recovery and explicit backup exclusions; guard provider URL/MAC/header inputs and redact diagnostics.
- Add live-applied validated buffer presets/custom values, transactional EPG replacement and bounded encrypted temporary guide downloads.
- Add optional email-code Supabase favorites/progress synchronization, local-first outbox, guarded merge RPC and owner-only RLS. Provider credentials/URLs are excluded.
- Add versionCode-based stable update discovery, APK integrity/package/signer checks, background preferences and interactive Android installation.
- Add debug CI and signed release verification/metadata/source archives. Production signing and real-device acceptance remain maintainer setup.

## Historical OpenTV changes

The entries below are preserved upstream history, not Nuvix release versions.

## 0.11.8

- **Edit a saved provider.** Settings → Providers now has an **Edit** button on each provider, so you
  can fix a server address, password, MAC or name in place instead of removing and re-adding it —
  your favourites, hidden channels and per-provider settings are kept.
- **Stalker portals: fewer dropped sessions.** A portal that occasionally rejected a call
  mid-session (channels loading only "sometimes") now gets one automatic re-handshake and retry, so
  an intermittent refusal becomes a silent re-auth rather than an empty channel list or a channel
  that won't tune.
- **Player controls stay clear of the system bar off-TV.** On phones and tablets the bottom control
  bar (subtitles, audio, quality, aspect ratio) could be drawn under the navigation bar and cut off;
  it's now inset above it. No change on Android TV.

## 0.11.7

- **Stalker portals: closer to a real box.** Building on v0.11.6, OpenTV now sends the rest of the
  identity a real MAG set-top box presents at login — the auth token in the session cookie (not just
  the header), a hardware-version hash, a timestamp and the box's API signature — and it probes a
  couple more portal paths. Some Ministra/Stalker panels only return the channel list when they see
  all of this, so lines that connected but showed no channels have a better chance of loading. If a
  portal still comes up empty, please open an issue and say which panel software it runs.

## 0.11.6

- **Stalker portals: full set-top-box identity.** OpenTV now authenticates to Stalker/Ministra
  portals the way a real MAG box does — sending the device serial, `device_id`, a signature and a
  metrics blob derived from your MAC, instead of the MAC on its own. Many portals won't hand over
  the channel list or stream links to a box that only presents a MAC, which is why some lines that
  worked in other apps came up empty in OpenTV. This lets those portals authorise OpenTV. If a
  portal still fails, please open an issue and say which one — it helps a lot.

## 0.11.5

- **Add a provider from your phone or laptop.** The "Manage on phone or laptop" web page now has an
  **Add a provider** section — pick Xtream, M3U or Stalker portal, fill it in with a real keyboard,
  then Test and Add. Channels load straight onto the TV, so you never have to type a server address
  or a MAC on the remote again.
- **Fixed the "camera won't scan it?" web address.** The manage screen was printing that link without
  its access token, so typing it into a browser hit a dead "Not found". It now shows the full working
  URL, and the token is short enough to type.


## 0.11.4

The big DVR release. Recordings behave like Sky Q, you can record straight to a NAS, watch a
recording while it's still taping, and the whole interface now speaks 30 languages.

### Recording & DVR

- **Watch a recording while it's still recording.** Start playing something the moment it begins
  taping — OpenTV reads the growing file straight off disk, so it costs **zero extra connections**
  to your provider. On a single-stream account that's the difference between "wait until it's
  finished" and "watch now". Fast-forward and rewind work within whatever's been taped so far.
- **Record straight to a NAS.** Point recordings at a network share over SMB (Settings →
  Recordings → NAS, or set it up from the phone/laptop web manager) so a cheap box isn't boxed in
  by its own storage — and every OpenTV in the house can reach the same recordings.
- **Single-connection auto-switch.** On a one-stream provider, if a recording is due to start on
  another channel, OpenTV moves the screen onto that recording as it begins — with a **30-second
  warning** first and a *Keep watching* button if you'd rather not. No more "why did my live stream
  just cut out?" (Recordings → Recording behaviour → *Auto-switch when recording starts*.)
- **Clash handling on the selected provider.** Recordings keep the provider selected in the
  Guide. Overlaps are checked against that account's capacity and recording-priority policy;
  providers are not substituted automatically.
- **Recording padding.** Start each recording a minute early and run a few minutes past the listed
  end, so a late kick-off or an overrun isn't clipped. Adjustable per side.
- **Series links show the next episode.** A series link now tells you exactly when it next records.
- **Storage readout.** See how much space recordings use and how much is left.

### Live TV

- **Pause & rewind live TV** *(experimental, opt-in).* Holds the last couple of minutes so you can
  pause and jump back. Off by default — it uses more memory.

### Languages

- **30 languages.** The whole interface is translated into Spanish, French, German, Italian,
  Portuguese, Dutch, Polish, Russian, Turkish, Arabic, Simplified Chinese, Japanese, Korean, Hindi,
  Swedish, Danish, Finnish, Norwegian, Czech, Greek, Romanian, Hungarian, Ukrainian, Indonesian,
  Thai, Vietnamese, Bulgarian, Slovak, Croatian and Persian. Settings → Language (each listed in
  its own name), or leave it on *System* to follow your device.

### Fixes & polish

- Smoother watch-while-recording, especially from a NAS: playback now keeps a buffer behind the
  live edge so a network share doesn't stutter at the write head.
- Back never lands you on a stray live channel or drops you out by accident — coming back from a
  recording returns to your recordings, and Back on the home screen asks before it exits.
- Password fields are masked, with a show/hide toggle.
- Recording stability: NAS write timeouts and reconnect handling, so a quiet network doesn't
  silently freeze a capture.

### Notes

- Everything stays on your device — provider logins never leave the box.
- Free and open source (GPL-3.0): https://github.com/opentvproject/opentv


## 0.10.0

- **Movies & Shows, redesigned.** A Plex-style layout with big artwork, cast & director, "more with
  this cast" rows, and cleaned-up titles (no more "NF -" / "(KR)" junk). Optional: add your own free
  TMDB key in Settings → Metadata and OpenTV fills in any posters, backdrops, cast or synopses your
  provider left blank — the key stays on your device.
- **No more stuck "Loading movies & shows".** The movies/series catalogue is cached, so it loads
  instantly on later launches instead of re-downloading every time — and the live preview no longer
  stutters while it loads.
- **Record to a USB / external drive.** Settings → Recordings → USB, pick a folder on a plugged-in
  drive, and recordings write straight there (no storage permission needed) and play back in-app.
- **Recordings screen overhaul.** Scheduled recordings show *when* they'll record and sit in their
  own "Scheduled" section; failed ones have a **Retry**; each is tagged to a profile; and bookings
  re-arm on every launch so a force-stop or app update can't quietly drop them.

## 0.9.0

- **Manage your channels from a phone or laptop.** A new "Manage on phone or laptop" screen in
  Settings shows a QR code and a link — open it on any device on your wifi and you get a proper web
  page to browse, **rename**, hide, favourite and **reorder** channels with a real keyboard and
  mouse. Changes apply to the TV instantly. Local-only: the TV is the server, nothing touches a
  cloud.
- **Recording keeps going when you leave the app or the box sleeps.** OpenTV can now ask Android to
  exempt it from battery optimisation (Settings → Recording → "Recording in the background"), which
  is what keeps recordings running in standby and when you switch away — swiping OpenTV out of
  recents no longer stops a recording either.
- **Rename channels.** Give a channel your own name; it sticks and survives a guide refresh (from
  the web manager for now).
- **Tidier update notes.** The "update available" prompt shows a clean summary of what's new instead
  of build metadata.

## 0.8.0

- **No more endless "Loading channels".** If a provider fails to load or comes back empty (a wrong
  login, a dead server), the guide now shows a clear error with **Retry** and **Provider setup**
  buttons instead of spinning forever.
- **Set up on a phone without the QR dance.** Installing on a phone now shows the login form
  directly so you can type your details in; the QR code is only offered on a TV, where typing is the
  painful part.
- **Paste into the setup fields.** The URL, username and password fields — and the on-screen
  keyboard — now have a **Paste** button, so a copied Xtream line drops straight in.
- **MPEG-TS or HLS, your choice.** Each Xtream provider now has a **Stream format** toggle. If your
  panel only serves `.ts` and channels wouldn't play, switch to MPEG-TS and they will. Defaults to
  HLS, so nothing changes unless you need it.
- **Parental controls scrolls.** The hidden-categories list is reachable to the bottom now, however
  many categories your provider has.
- **A real Channel Manager.** Settings → Channels is now a browsable, two-pane manager: pick a
  category on the left, see its channels on the right, hide or favourite each one — including
  hidden channels so you can bring them back. With more than one provider, a **source filter** keeps
  them separate instead of merged into one list.

## 0.3.0

- **Profiles.** Add local profiles (just a name — no accounts) from the person icon in the top
  menu, and switch between them. Each profile keeps its own resume points and watched state, so
  your half-watched film doesn't show as watched on someone else's profile. Existing resume points
  become the default "Me" profile's — nothing is lost.
- **Continue Watching.** Movies and Shows now open with a "Continue watching" shelf for the active
  profile — pick up your last film or episode where you left off, with a progress bar on each.
- **Local sync (no servers).** Settings → Sync copies your continue-watching between two OpenTV
  devices over your own wifi: one device shares behind a six-digit code, the other receives. It's
  local-only — nothing leaves the house — the household answer to the cloud sync that died with
  Viewella.

- **Proper player controls.** A single control bar slides up from the bottom with play/pause,
  rewind/forward (where the stream allows it), and pickers for **subtitles, audio track, quality
  and aspect ratio**. It hides after a few seconds and any remote button brings it back —
  nothing is ever left painted permanently over the picture.
- **Captions that actually show.** Subtitles are now chosen from the real tracks in the stream
  (and can be turned off), instead of a blind on/off that often left the renderer enabled but
  nothing on screen. Audio-track switching works the same way for multi-language streams.
- **Page through the guide.** Earlier / Now / Later buttons above the guide move the timeline
  forward and back, so you can see what's on later this evening with a remote.
- **Live preview in the guide.** The highlighted channel now plays, muted, inside the preview
  pane. It uses a single player that stops the instant you go full-screen or leave the guide —
  no second decoder — and it can be turned off in Display & playback for older boxes.
- **Channel manager.** Settings → Channels: search for a channel and hide it from the guide (or
  favourite it). Hidden channels drop out of Live TV and search but stay here, greyed, so you can
  bring them back. Hiding covers every quality variant of a channel at once.
- **Parental controls.** Set a 4-digit PIN and mark categories as hidden — they drop out of the
  guide, All channels and search until you unlock them (and with a PIN set, the parental screen
  itself is locked). In Settings → Parental controls.
- **Unified search with an on-screen keyboard.** Search now has its own screen with a d-pad
  keyboard (no more "type on your phone") and covers **channels, movies and shows** together, with
  live results as you type. Reachable from the top menu.
- **Loading indicators for Movies and Shows.** Both now show a spinner while the catalogue is
  syncing instead of a premature "nothing here".
- **Settings and Search in the top menu.** Both now live top-right, next to Live TV / Movies /
  Shows, instead of being buried behind the guide's icons.
- **A single Settings screen.** One Settings hub — Providers (add / remove / re-test sources), TV
  guide, Display & playback, Parental controls, and About (version, a manual update check,
  licence and links).
- **Dark / light / follow-system.** A new appearance setting. TV still defaults to dark; pick
  Dark or Light to force it on any device.
- **Quality picker only when there's a choice.** No more "Quality: Standard" on single-quality
  channels; the picker appears only when a channel actually has multiple qualities.
- New **Display & playback** settings screen (the sliders icon in the guide).

## 0.2.0

- **Guide detail pane** — highlighting a channel now shows its logo, what's on now with
  start/end times and a live progress bar, a synopsis when the guide carries one, and what's
  on next. Press to watch full-screen.
- **Live progress fill** on the current programme in the guide grid, and a focus highlight on
  the highlighted channel.
- **Fix: never asks for your provider again.** A cold launch could briefly decide "first run"
  before your saved sources had loaded and drop you on the setup screen — it now waits for the
  sources to load before deciding what to show.
- **Fix: no second video decoder on the guide**, which was locking up low-end boxes (a
  Chromecast could freeze). The detail pane is logo + guide info; the one decoder lives
  full-screen.

## 0.1.0

First public release.

- Xtream Codes and M3U/M3U8 sources
- Streaming XMLTV guide parsing with incremental, non-destructive sync
- Live TV playback on Media3/ExoPlayer with IPTV-tuned retry behaviour
- Movies and series catalogue
- Favourites, categories, search
- In-app self-update for sideloaded installs
- Single APK for Android TV, Fire TV, phones and tablets
