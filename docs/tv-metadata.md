# TV metadata

Opening a TV show detail page automatically enriches it from the free public
[TVmaze API](https://www.tvmaze.com/api). No account, API key, subscription, or
bundled credentials are required. Movies retain their existing provider/TMDb path.

## Matching and source order

1. Use a provider IMDb ID (`get_series_info.info.imdb_id` or `imdb`) for an exact
   TVmaze lookup. Otherwise search the cleaned title, with a release year when
   available. Trailing title years are extracted for matching.
2. Require a unique normalized title/year match. Ambiguous remakes, unrelated
   results, and conflicting IMDb IDs are rejected. In that case provider data
   stays usable.
3. Prefer TVmaze for show descriptions, genres, posters, background art, cast,
   characters, crew, seasons, and episodes. Backgrounds come from the images
   endpoint; a poster is not misclassified as a background.
4. Use the public [IMDbAPI.dev](https://imdbapi.dev/) REST API as a secondary
   source for descriptions, genres, posters, cast, IMDb IDs, and IMDb ratings.
   A unique TV-series/miniseries title/year search can also resolve an IMDb ID
   for another TVmaze lookup. This is a best-effort adapter to the documented
   `api.imdbapi.dev/search/titles` and `/titles/{id}` endpoints. The service's
   live availability could not be verified during development; failure is optional
   and does not require replacing it with a paid service.
5. Preserve provider values wherever the external sources have no value. The
   existing TMDb client fills remaining gaps only when the user has already
   configured their own key. No TMDb key is needed for TVmaze or IMDbAPI.dev.

The series detail header labels **TVmaze**, **IMDb**, **Provider**, and **TMDb**
ratings separately. IMDb ratings never replace or get inferred from TVmaze votes.
The provider's existing rating/year and identifiers remain intact.

## Episodes and people

TVmaze supplies the complete seasons list and full episode list, including
specials, with titles, summaries, air dates, runtime, and images where available.
Season summaries, dates, artwork, and episode orders are shown when supplied.
Missing API fields stay optional.

Numbered episodes attach to a provider stream only through a unique
`(season, episode number)` match. Unnumbered specials and ambiguous metadata
never acquire a guessed stream. Episodes absent from the provider appear as
non-playable metadata entries labeled **Not available from your provider**.
Provider episodes absent from TVmaze remain playable with their original data.
All playback URLs, provider episode IDs, local media keys, favorites, and watch
progress are preserved.

Cast and crew cards show actor photos, character names or crew roles and open
profiles by TVmaze person ID (namesakes do not share an identity). Profiles show
photos, life dates/country when supplied, and show-level cast/character and crew
credits. Credit links open the corresponding TVmaze show page. Generic provider
cast names retain the existing local-library person screen.

## Cache and request policy

Public metadata has a separate local cache in Android's cache directory:
`metadata/tvmaze` and `metadata/imdbapi`. Provider Room tables and Supabase cloud
records do not contain these responses or the display overlays. There is no
schema migration, cloud identity change, or cloud payload expansion.

Each service has one serialized transport shared by detail pages and profiles:

- Fresh responses and 404 misses are cached for one hour; stale responses up to
  seven days old can be shown on network failure or rate limiting.
- Cache misses are spaced by at least 600 ms (below TVmaze's published baseline
  of 20 calls per 10 seconds). Concurrent identical misses reuse the cached result.
- HTTP 429 retries are limited to three attempts, with exponential backoff and
  `Retry-After` seconds/HTTP-date support. Long cooldowns return promptly; later
  requests to any endpoint on that service honor the cooldown.
- Other HTTP/network failures have a one-minute service cooldown. Malformed JSON,
  missing fields, corrupt cache files, and unwritable cache storage are tolerated.
- Each cache is bounded to 256 responses/20 MiB, with an 8 MiB response limit.
  Writes use a temporary file and rename. Android may evict the cache at any time.
- Response bodies close after every response; eligible connections remain pooled
  and may be reused. A descriptive Nuvix User-Agent is sent.
  Metadata uses its own short timeout configuration and never changes playback's
  streaming client. Coroutine cancellation is preserved through backoff.

Images use the existing Coil image loader/cache. The integration does not fetch
an entire TV database or enrich the whole provider catalog in the background.

## Attribution and redistribution

TVmaze metadata is provided by **TVmaze** under
[Creative Commons Attribution-ShareAlike 4.0 International](https://creativecommons.org/licenses/by-sa/4.0/).
Show details and actor profiles link to their TVmaze source pages and the license;
About includes the same credit. Nuvix formats HTML summaries as plain text and
combines selected fields with provider metadata. These changes are identified in
the app. TVmaze-derived adaptations remain available under CC BY-SA 4.0; retain
source links, attribution, the license, and modification notices when sharing or
exporting that data. There is no endorsement by TVmaze.

The application's GPL-3.0-or-later license does not replace the data license.
Provider/IMDbAPI.dev/TMDb material retains its own applicable rights and is not
relicensed by this notice. No real metadata, images, IPTV content, or API keys
are bundled in the APK. Test fixtures are synthetic. See
[third-party notices](../THIRD_PARTY_NOTICES.md) and [privacy](PRIVACY.md).

## Validation

`app/src/test/java/app/opentv/metadata` covers IMDb lookup redirects, title/year
ambiguity, optional/null API fields, seasons/specials, artwork, cast/crew profiles,
source precedence, secondary service failure, cache persistence/expiry/corruption,
request throttling, bounded 429 retry/cooldown, cancellation, exact provider
playback preservation, and unchanged cloud identities.

Run the normal CI commands:

```sh
./gradlew help
./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
python3 -m unittest discover -s scripts/tests -v
```

Remote navigation, playback, actor-photo layouts and external links still need
real Android TV/Fire TV testing; some televisions have no browser. In that case
links display their URL as a toast.
