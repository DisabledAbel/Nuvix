# OpenTV roadmap — feature gap analysis

This is an evidence-based plan for what to build next, benchmarked against the apps people
actually compare us to (TiviMate, IPTV Smarters Pro, Sparkle/OTT Navigator, Televizo) and
weighted by real user demand from the TiviMate wishlist forum, IPTV subreddits, and player
surveys. Sources are at the bottom.

## Where we already are

Table stakes are done: Xtream Codes + M3U, a live EPG guide grid, EPG name-matching against
free XMLTV guides, quality-variant grouping, favourites, categories, Movies + Series with
resume, phone-pairing setup, and a tuned ExoPlayer. Several of those (EPG name-matching,
quality grouping, QR phone-pairing) are things the paid incumbents do *not* do well — so the
list below is mostly about reaching parity on the features users treat as non-negotiable.

## The one that matters most: Catch-up / Archive

Across every source this is the single highest-demand missing feature, and OpenTV is unusually
well-placed to build it because it already parses XMLTV start/duration times. **No new video
decoding is involved** — an archived programme plays through the existing player exactly like a
movie. The work is reading the catch-up fields, building the right URL, and a guide gesture to
launch a past programme.

- **Xtream** signals it per channel in `get_live_streams`: `tv_archive` (0/1) and
  `tv_archive_duration` (days). Playback URL:
  `http://host:port/timeshift/USER/PASS/DURATION_MINUTES/YYYY-MM-DD:HH-MM/STREAM_ID.ts`
  (start time in **UTC**, duration from EPG end−start).
- **M3U/XMLTV** providers use the Kodi-standard tags instead — `catchup` (`default` / `append`
  / `shift` / `flussonic` / `xc`), `catchup-source`, `catchup-days`, `catchup-correction`,
  with `{utc}`/`{start}`/`{duration}`/`{offset}` tokens to substitute. Supporting these is what
  makes it work for *everyone's* provider, not just this one.

Tuneline's write-up confirms most "easy" players skip catch-up not because it's hard to play,
but because it needs full EPG parsing **and** the provider's URL scheme — and we already have
the EPG parsing.

## Ranked gaps

| # | Feature | Who has it | Demand | Difficulty |
|---|---------|-----------|--------|-----------|
| 1 | **Catch-up / archive** (Xtream `tv_archive` + Kodi M3U tags) | TiviMate, Sparkle, Smarters | Highest — "most glaring omission" | Medium |
| 2 | **Live pause / rewind** (client timeshift buffer) | TiviMate Premium, Sparkle | High, a paid-tier differentiator | Medium |
| 3 | **Recording / DVR** incl. recurring & series | TiviMate, Smarters, Sparkle | Top-3 valued capability | Medium-Hard |
| 4 | **Multi-view / Picture-in-Picture** | TiviMate (PiP), IPTV One (grid) | 63% want dual-sports viewing | Medium (PiP) / Hard (N-up) |
| 5 | **Programme reminders / notifications** | TiviMate, OTT Navigator | Recurrent request | Easy |
| 6 | **Parental controls / PIN lock** | all majors | Core family feature | Easy |
| 7 | **External player hand-off** (VLC / MX Player) | all majors | Fixes problem-stream support burden | Easy |
| 8 | **Multiple playlists / sources** at once | TiviMate (headline) | Assumed baseline | Medium |
| 9 | **In-player audio / subtitle picker** | all majors | Expected for multilingual providers | Easy |
| 10 | **Sleep timer** | TiviMate, OTT Navigator | "Small but missed" | Easy |
| 11 | **Aspect-ratio / zoom controls** | all majors | SD-on-16:9 complaint | Easy |
| 12 | **Channel numbers + number entry + last-channel toggle** | all majors | Ex-cable users expect it | Easy |
| 13 | **Channel/group sorting, hiding, reordering** | TiviMate, Televizo | Wishlist asks hiding be respected everywhere | Medium |
| 14 | **Buffering / decoder / stream settings** | TiviMate, Televizo | Power users want knobs for bad streams | Easy-Medium |
| 15 | **Backup / restore** (and later, local sync) | Televizo, TiviMate (partial) | #1 account request | Easy (local) / Hard (sync) |

Honourable mentions: unified search across live/VOD/series, an "on now" strip, favourites-only
zapping, per-channel catch-up offset (trivial once #1 ships), a stream health/codec overlay.

### Also queued (from user feedback)

- **Local watch-history sync (device-to-device, no backend).** Viewella synced continue-watching
  and watched state through its servers; we do the same over the user's own LAN by extending the
  existing pairing server. Device A exposes a watch-state bundle behind a short code; device B
  pulls and merges newest-wins per item (resume positions, watched flags, favourites). No
  credentials sync. See "Local sync without a backend" below. Demand: direct r/Viewella ask.
- **Channel search in Live TV.** Search box over the channel list (the repository + query flow
  already exist; this is UI only). Later folds into unified live/VOD/series search.
- **A single Settings hub.** One entry point that gathers Providers, TV guide, Display &
  playback, Channels (hide/reorder), Parental PIN, Sync & backup, Playback/stream, and About —
  replacing today's scattered screens. See "Settings hub" below.

## Recommended next five

1. **Catch-up / archive.** Highest demand, reuses the existing player and EPG, and is the
   clearest "why switch from TiviMate" story. Ship Xtream `xc` + M3U `append`/`default`/
   `flussonic` modes with a per-channel time offset.
2. **Reminders + parental PIN.** Two easy wins riding directly on the EPG grid and channel
   model we already have; the PIN also answers the r/Viewella "it's just porn now" complaint by
   letting people hide adult categories.
3. **External player hand-off.** Easy intent-based fallback that neutralises the "bad codec /
   won't play" support burden, especially on cheap Fire TV sticks — high value per line.
4. **The easy in-player cluster:** audio/subtitle picker + aspect-ratio + sleep timer. A group
   of ExoPlayer-surface features users treat as baseline; shipping them together removes a whole
   category of complaints.
5. **Live pause/rewind buffer.** The premium-feeling differentiator that pairs with catch-up.

**Deferred on purpose:** full N-up multiview and *server-backed* cross-device sync are both
Hard (concurrent decoders / a backend we deliberately don't run) and shouldn't jump the queue.
Note this is distinct from the **local** LAN sync below, which needs no backend and is planned.
Recording/DVR is the natural sixth, since it shares the stream-to-disk plumbing with the
timeshift buffer.

## Local sync without a backend

The goal is Viewella's cross-device continue-watching **without** the servers that made it die
when Viewella went dark. The app already runs a small on-device HTTP server for phone-pairing;
this reuses it.

- **What syncs:** resume positions, watched/finished flags, and favourites. Keyed by provider
  content id (works when devices share a provider — the household case), falling back to a
  name+url hash. Each record carries an `updatedAtMillis`; merge is newest-wins per item.
- **What does not:** provider credentials. They stay per-device — the one thing worth typing
  twice, and the one thing we never want on the wire.
- **Flow:** device A → Settings → "Sync with another device" shows a short code / QR; device B
  enters it, connects over the LAN, and the two merge in both directions. Explicit and
  deterministic — no background daemon, no discovery magic in v1.
- **Later:** an optional "sync folder" (user's own NAS or cloud drive) for devices that are
  never awake at the same time.

## Settings hub

One screen, grouped, replacing today's scattered entries:

- **Providers** — add / edit / remove sources, per-source User-Agent, re-test a login.
- **TV guide** — the existing EPG feeds screen (provider guide + free XMLTV + custom URL).
- **Display & playback** — theme (system/dark/light), captions default, live guide preview.
- **Channels & categories** — hide, reorder, rename; manage favourites (the "Channel Manager"
  idea, done locally).
- **Parental controls** — PIN, hide adult categories (rides on the category model; also answers
  the r/Viewella "it's just porn now" complaint).
- **Playback & streams** — default aspect/zoom, buffer size, hardware/software decoder,
  hand-off to an external player (VLC / MX) for problem streams.
- **Sync & backup** — local device-to-device sync (above) and export/import a settings file.
- **About** — version + in-app update check, GPL licence, links to repo / issues / privacy, and
  the "built with AI, in the open" note.

## Sources

- TiviMate features — https://tivimate.co.com/app/features/
- TiviMate wishlist (TROYPOINT) — https://troypointinsider.com/t/tivimate-feature-wish-list/153806
- Top IPTV players 2026 (TROYPOINT) — https://troypoint.com/top-iptv-players/
- Xtream catch-up API — https://github.com/worldofiptvcom/xtream-codes-api-documentation
- Catch-up / timeshift explained (Tuneline) — https://tuneline.app/blog/catch-up-tv-timeshift-iptv-explained
- Kodi IPTV Simple catchup tags — https://github.com/kodi-pvr/pvr.iptvsimple/blob/Omega/README.md
- PiP / multiview demand (IPTV One) — https://www.iptv-one.app/en/blog/iptv-pip-guide-v2
- IPTV Smarters Pro features — https://iptvsmarterspro.co.com/tv/
