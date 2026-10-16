# Sports Hub

Open the existing **Sports** tab. Its seven sections are **My Sports**, **Live Games**,
**Upcoming Games**, **Favorite Teams**, **Favorite Leagues**, **All Sports**, and
**Sports Channels**. Nothing needs an account or a paid sports API. Add an authorized
provider and refresh its guide to populate the schedule. Existing category-derived
Sports Channels remain available even when event metadata is absent.

## Games and viewing choices

Select a game to open **Game Details → Available Streams**. The event card gives the
competition, sport, supplied matchup/title, local start time with timezone, status,
favorite marker and number of confirmed catalog entries. Logos and scores appear
only when supplied by a permitted verified source. EPG participant order is retained;
it does not establish which team plays at home. Unknown teams and leagues stay unknown.

Every EPG-derived event is labeled **inferred**, separate from **Verified event**.
A fresh EPG window can say **Airing in EPG**; that is not a live-score claim. Cached
source timestamps older than six hours say **Cached schedule**. Details show the last
successful source update. Schedule windows remain usable offline; starting a stream
still needs access to its configured provider.

Each choice identifies its provider and channel, channel artwork if supplied, event
program, current EPG program when available, confidence, favorite-channel state and
preferred-broadcast marker. A catalog quality name is explicitly **unverified** and
never becomes a measured resolution. Language is not fabricated from channel names.
The same logical channel from different providers is retained. Identical catalog
entries collapse only by the provider's stream identifier. Details page through batches
of at most 200 distinct provider choices; **Next streams page** and **Previous streams page** expose
additional choices. Card counts cover the complete matching catalog, not just a page. Generic sports slots without
identified participants appear as **Unmatched sports programs** for manual organization.

**Watch / Switch** calls the existing live-player navigation. Merely focusing a game,
opening its details, refreshing metadata or ordering broadcasts never opens a stream.
The retained `LivePlaybackSession` owns connection replacement, recording conflicts,
errors/retry, audio focus and PiP. Back from fullscreen returns to the selected event.
A failed stream can be retried through existing controls or another choice can be
selected explicitly. The hub never probes streams or starts parallel players.

## Matching and manual corrections

The cache retains the original EPG and matches only channels from enabled local
providers and enabled EPG feeds, respecting hidden channels, locked categories and
the guide's manual/provider/matched EPG-ID priority.

- **Confirmed Match:** matching competition, two exact normalized league-scoped team
  identities and identical start time, or your explicit confirmation. This confirms
  an association to supplied EPG evidence, not the real-world accuracy of that EPG.
- **Likely Match:** the same paired-team/competition evidence with overlapping program
  windows and a start difference of no more than 20 minutes. This remains a suggestion.
- **Possible Match:** insufficient league/team evidence, a generic sports program, or
  ambiguous candidate events. This remains a suggestion.

Team aliases are a deliberately small, reviewed, league-scoped map. Unknown names
receive exact normalized league-scoped IDs; substring nicknames, channels being in a
sports category, and a title alone cannot verify a broadcast. Replays, highlights,
previews and non-sports versus titles are excluded. Multiple plausible events are
not silently combined. The event model uses source-scoped identifiers, and the
verified-source identity helper requires an explicit mapping and compatible league,
teams and timing before cross-source linking.

Suggestions offer **Confirm match** and **Remove match**. Removed choices can be
restored. **Select game** associates an unmatched program or incorrect stream with
another overlapping cached event; the selector is independent of the current search/filter. **Manual organization / possible duplicates** opens
related events for review without automatically merging their identities. **Prefer
this broadcast** places that choice first among equally confident matches. Confidence
always outranks preference; sorting can then favor favorite channels or providers.
Corrections are local, scoped to provider stream ID plus an EPG-content fingerprint,
survive a normal guide row replacement and never rewrite catalogs or raw EPG. Reusing
a time slot for a different program does not inherit the old decision.

## Favorites, search and reminders

Follow/unfollow leagues from the league shelf, teams from cached matchups or their
game details, and sports from All Sports. My Sports promotes their games. Favorite
teams show their explicit competition to distinguish repeated names. **Move earlier**
and **Move later** reorder favorites within their kind. Teams without a supplied,
unambiguous competition are not turned into portable team identities or guessed rosters.

**Search** uses the existing remote keyboard. Search teams, league IDs/names appearing
in metadata, sports, event titles or available channels. Filters include Live/Airing,
Today, Tomorrow, Upcoming, Favorite Teams, Favorite Leagues and Games With Available
Streams. Calendar filters use device timezone boundaries, including DST transitions.
Search and filters apply in Room before the result limit. The list starts at 200 schedule results; search narrows the bounded database query,
and **Load more games** extends it to 2,000. Browse a team, league or sport from its
shelf; **Clear search and filters** returns to all games.

Open an upcoming game, confirm a viewing choice if needed, then choose **Remind me
about this game**. Notifications require device permission. The existing reminder
scheduler and receiver own alarms and reboot rearming. One game has one reminder
across provider choices; Guide actions resolve that same identity. Sports reminders
notify without automatically tuning. Cancellation uses the existing alarm infrastructure.
A verified source-scoped reschedule updates the same reminder; cancellation/postponement
cancels it. Broadcast choices tied to a non-overlapping old window are removed after a reliable reschedule. EPG-only timing changes have no authoritative reschedule identity and must
be reviewed instead of silently moving a reminder.

## Settings and privacy

**Sports → Sports settings** controls the default view, stream sorting, possible
matches, optional scores, reminder actions, favorite ordering/removal and clearing
the derived cache. Clearing the cache retains favorites, decisions, preferred
broadcasts, existing EPG and reminders, then rebuilds from local EPG.

Room schema 13 adds local sports cache/intent tables, indexed guide joins and a
nullable unique game key on existing reminders. The forward migration preserves
providers, favorites, guide overrides, custom groups and existing reminder data.
Sports preferences and corrections are local only. They do not enter Supabase's
explicit cloud record allowlists or LAN catalog exports. Existing advanced cloud sync,
RLS, typed records, encrypted journaling and account conflict handling are unchanged.
No hosted database changes are required.

## Schedule-source review (2026-10-16)

Primary data is the user's existing configured provider EPG. No external sports
schedule or score integration is enabled or contacted in this release. The
`SportsDataProvider` interface and validated cache-ingestion boundary support a
future permitted integration; the failure-isolating loader uses deterministic mocked
providers in tests. Source adapters must also implement app-visible attribution,
expiry, cancellation/rearm callbacks, retries and rate limits before being configured.

| Candidate | Official evidence reviewed | Decision |
| --- | --- | --- |
| football-data.org | [v4 API policies](https://docs.football-data.org/general/v4/policies.html), [terms](https://www.football-data.org/about), [FAQ](https://www.football-data.org/documentation/faq) | Documented API; registered free clients are limited to 10 requests/minute. Registration, one-application keys, visible attribution, confidential credentials, cessation after cancellation, and separate logo rights need implementation. No shared key or bundled adapter is shipped. |
| NHL | [Official terms](https://www.nhl.com/info/terms-of-service) | No documented distribution grant established; no adapter enabled. |
| MLB | [Official terms](https://www.mlb.com/official-information/terms-of-use) | No app redistribution permission established; no adapter enabled. |

Publicly reachable endpoints and third-party endpoint catalogs are not accepted as
permission. There is no scraping, external stream discovery, external IPTV-link import,
score synthesis, paid-API dependency or hidden live-score polling.

## Operational limits

Reconciliation runs after existing guide refresh and while Sports observes feed
revisions. Checkpoints skip unchanged feeds; reads are keyset batches of 500 programs.
Only changed feeds or the six-hour rolling-window extension are reconciled, off the
main thread. A pathological feed is capped at 50,000 programs with a partial-results
message. Other providers' results survive failures. The schedule covers seven days
forward and retains recent cached events for review; EPG availability can be shorter.
Cards and streams use lazy TV controls and the existing bounded Coil artwork cache.
No data-quality heuristic can guarantee a sparse provider's real-world schedule.
Unknown aliases/competitions need manual review. Physical TV focus, decoder, recording,
PiP and low-memory acceptance remain in [TESTING](TESTING.md#sports-hub).
