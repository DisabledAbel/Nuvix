# Automatic EPG configuration

Automatic configuration is enabled by default in **Guide settings**. After a playlist import,
on app startup and during the existing six-hour background refresh, Nuvix scans the channel
catalog and uses cached guides immediately. It then refreshes enabled feeds and searches for
guides for channels that still have no current or upcoming programme data.

Sources:

- Direct HTTPS XMLTV (`.xml` / `.xml.gz`) links advertised at `https://iptv-epg.org/guides`.
- IPTV-org's public channel and guide catalogs, including alternate channel names and the
  actual XML/GZIP source URLs advertised in `sources`. Catalog entries without a download
  source are skipped; a catalog ID alone does not establish a working guide.
- The existing UK Freeview and UK, USA, Canada and Australia epgshare01 feeds. Every relevant
  region is eligible, including a region with just one channel in a mixed-country playlist.
- Provider and user-added XMLTV feeds remain part of the combined guide.

Channel-name searches run locally. Requests download catalogs, and never send the user's
channel list or provider credentials to public discovery services. Catalog JSON is streamed,
responses have size limits, discovery requests have a one-minute timeout, and new automatic
guide downloads are limited to 12 per refresh. Remaining candidates can be tried on later
refreshes. Automatic downloads run in the background and share the existing encrypted guide
spool and atomic replacement logic.

Each successfully downloaded guide is matched immediately. The discovered TVG-id is stored
in the channel's `matchedEpgId` field, already consumed by the guide and player. Provider IDs
and manual overrides retain their precedence. Automatic matches survive catalog refreshes.
Only IDs with unexpired programmes in an enabled feed are eligible. Names with conflicting
IDs are left unmatched, and country hints prevent selecting an explicitly different country.
A previously verified working match stays stable while its data remains usable.

Turning a feed off prevents automatic discovery from turning it back on. Disabling automatic
configuration stops discovery; already enabled feeds continue their normal refresh. Turning
it on or choosing **Update guide now** triggers a scan with progress and a matched-channel
count. Unavailable feeds and discovery catalogs are reported in Guide settings. Failed guide
refreshes retain the last valid data.

Coverage depends on public sources. Directory pages must advertise direct XMLTV links;
JavaScript-only catalogs, subscription-only downloads and guide entries without programme
data cannot provide an automatic match. Unknown countries and ambiguous names may still
need a custom XMLTV feed or a manual override.

Validation: `AutoEpgConfigurationTest`, `PublicEpgDiscoveryTest`, `EpgRefreshTest`,
`EpgMatcherTest`, `XmltvChannelParseTest` and `XmltvTimeTest` cover discovery, mixed regions,
incremental persistence, conservative matching and atomic refresh behavior.

## Advanced guide

The full-screen guide uses the same imported XMLTV data, matching rules, channel groups,
reminders and recording engine. It works offline with cached data; discovering or refreshing
feeds remains a separate background operation. Changing presentation does not refresh a feed.

Choose a layout in **Guide settings**:

| Layout | Presentation |
|---|---|
| Standard | Traditional channel rows and a horizontal time grid |
| Compact | Shorter rows and smaller text to show more channels |
| Expanded | Taller rows, larger text and additional available program information |

Layout, timeline density, artwork visibility, progress visibility and remembering position
save locally and apply while the app is running. The existing grid/list setting remains
compatible: the channel-list view is still available. Guide preferences do not change provider
IDs, channel numbers, group contents, recording settings or playback tuning.

Defaults are Standard, Comfortable density, All channels and Provider order, with artwork,
progress and remembering position enabled. Older installations retain their grid/list choice;
new or malformed advanced values use these defaults. Density choices are Comfortable,
More time visible and Larger program cells. Turn off remembering position to clear the saved
guide position.

Optional cloud sync can transfer the seven portable presentation/filter/sort switches through
the existing **Compatible settings** opt-in after applying the
[forward migration](cloud-sync.md). It does not transfer the selected provider/group, channel/time
position, artwork URLs or live playback. The guide does not require cloud setup or sign-in.

### Filters and sorting

Use the guide's channel controls to select All Channels, Favorites, Recently Watched,
Channels With EPG Data or Currently Airing. Existing provider selection, provider categories
and custom channel groups remain available. A custom group uses existing membership; choose
Custom Channel Order to show the order saved in channel organization. An empty favorite,
recent or custom selection shows an empty state;
it does not add channels or change the selection to a different provider.

Channel sorting supports Provider Order, Channel Number, Alphabetical, Favorites First and
Custom Channel Order. These are views of existing data. Sorting by number retains the provider's
numbers; it never renumbers channels. Hidden channels and parental restrictions continue to
apply. Recently Watched uses local viewing history. Availability filters use the cached guide,
so a provider with incomplete data may have playable channels outside those views.
Provider Order uses the existing source/import row order. The catalog does not retain a separate
provider ordinal, so a provider reordering already imported channels does not replace that baseline;
use Channel Number or your existing custom order when that better matches the lineup.

### Remote navigation

Move up/down between channels and left/right through program cells. The focused cell has a
visible outline; Select opens its details. Channel cells keep the existing channel menu.
Timeline buttons provide **Now**, previous/next hour and previous/next day. **Current Channel**
returns to the retained live channel at Now. If the current filter excludes that channel, this
explicit action switches to All Channels before restoring focus; it does not tune a stream.
Hour buttons move inside the displayed 24-hour window. The live window includes six hours before
the current half-hour and eighteen hours ahead; a new guide opens at Now. Day buttons browse
yesterday through six days ahead; feed coverage determines which programs are available.
The Now action returns to today's current time.

Remember Last Position retains a safe local channel/time position. A removed channel or expired
position falls back to available guide data. No stream URL, player, surface or buffer is saved.
Back closes program details before leaving their screen. With no EPG data, the channel remains
reachable and can still be opened for live viewing.

The current-time line and optional progress fill describe the current airing. Browsing future
days does not imply that a feed contains a full week of data. Dates/times use the device's local
timezone; XMLTV source times continue to be stored as UTC.

### Search and program details

Open **Search → Programs** from the main navigation and enter text with the remote-friendly
keyboard. Search matches available program titles, channel names, categories and descriptions. Combine it with
Currently Airing, Upcoming, Movies, Sports or Series. Queries run against the local Room database
for current/upcoming programs in the next seven days, with at least two search characters,
at most 120 characters and at most 200 results. Narrow the text or category when a result limit is
reached. Search does not download catalogs or request third-party metadata for each keystroke.

Select a result or a grid cell to see title, channel, start/end, duration, description and
available category/episode information. Missing fields remain absent. Actions depend on timing
and existing source capabilities: watch the channel, add/remove a future reminder, record an
eligible airing, or play catch-up where the source and archive window support it. Reminder
notifications and exact scheduling still depend on Android permissions; recording still needs
authorized content, sufficient storage and a compatible provider connection. A completed
airing cannot be captured retroactively by recording its current channel.
Guide recording actions require a direct HTTP(S) stream; Stalker playback links require
per-play resolution and are not offered to this recorder. Catch-up requires an enabled Xtream
source with advertised archive support and honors a supplied archive-day limit. Missing archive
day counts cannot establish a guaranteed retention period.

Program artwork uses the existing Coil image loader and authorized feed artwork when available,
with decode sizes capped at 640×360 and no crossfade animation.
Disabled, missing or failed artwork has a local placeholder. There are no new paid APIs or
per-program external metadata lookups. Provider URLs and credentials are not displayed as
program metadata. Sports labeling follows supplied categories, not guesses from a title; teams,
live scores and verified event status are not invented.

### Live playback and limitations

With persistent live playback active, guide focus, filtering, layouts, timeline moves, search
and details leave the current live session in control. Only an explicit Watch/View Channel
action changes the tuned channel. Selecting the current preview returns to fullscreen. Existing
guide preview remains available when there is no retained session, using its existing preference
and ownership rules. Its initial/explicitly selected channel stays stable as focus moves;
highlighted program information can change without tuning video. See [live playback](live-playback.md).

The guide renders channel rows lazily, loads program objects for at most 60 visible/prefetched
channel rows in a 24-hour window, and composes visible program cells plus adjacent focus targets.
Guide ID batches are capped at 300 to remain below older SQLite bind limits. Search and artwork
are bounded separately. Large provider catalogs still require local channel grouping. Database bounds
do not guarantee a particular frame rate, and a low-end device can disable artwork or live
preview. Actual D-pad focus, text scaling, accessibility service behavior, PiP, decoder continuity,
recording handoffs and low-memory performance require the [device checklist](TESTING.md#advanced-epg).
Guide settings, search and details resource labels have translations for the existing app locales.
Some timeline toolbar, metadata and guide accessibility labels still appear in English.
