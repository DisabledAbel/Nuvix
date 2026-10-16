# Nuvix architecture

Nuvix keeps OpenTV's native Kotlin/Compose/Media3/Room structure. The upstream commit and original-file digests are recorded in `upstream-source.json`. `MainActivity` owns navigation, the sidebar/home screens present local provider catalogs, `ServiceLocator` retains the dependency graph, and existing catalog, EPG, player, recording, reminder, LAN pairing, and NAS components remain in place. The Kotlin source namespace `app.opentv` preserves upstream structure; generated Android resources and installed identity use `com.disabledabel.nuvix`.

New code has explicit boundaries: `launcher/` wraps PackageManager, optional HOME alias activation, app favorites, and startup preferences; `cloud/` implements optional email OTP, an encrypted allowlisted outbox, REST/RPC sync and WorkManager retry; `update/` validates fixed-origin release metadata, hashes, Android package/version/signers and uses interactive PackageInstaller sessions. `player/BufferSettings` and `ConfigurableLoadControl` expose bounded, live-applied buffering. `core/RecentChannels` persists local channel identifiers only.

The guide downloads and validates an XML feed off the main thread before replacing a feed inside a Room transaction. Parsing or database failure rolls back previous programs/aliases. Catalog and VOD loading continue through the original repositories. Home channel/sports shelves use bounded database queries instead of loading full playlists.

Advanced EPG extends that guide rather than replacing its import/matcher or introducing another
channel store. Typed local guide preferences feed the existing channel view model and guide
composables. Filters reuse favorite flags, local recent IDs, provider/category selection and
custom-group membership. Sorting changes the displayed order without writing channel numbers,
provider identifiers or custom-group membership. Existing hidden-channel and parental checks
remain in the shared visibility path.

The visible guide observes a 24-hour time window for at most 60 visible/prefetched channel rows,
with today's window extending from six hours before the current half-hour to eighteen hours ahead,
with ID queries chunked at 300 and channel grouping off the main thread. Grid and list viewports
both report their visible slice; metadata changes for the same channel refresh its EPG candidates.
Stable channel/program identities support lazy rendering and focus restoration. Program search
uses Room queries over cached programs and authorized local channels, bounded to 200 rows in the
upcoming week, with cancellation when the query changes, 200 ms input debounce and a minute tick
for airing/upcoming status. Parental exclusions and matching precedence apply before the result
limit. It does not create a full-guide in-memory search index. Artwork is an
optional bounded Coil request from existing metadata, with local fallback and no new service.
Program details call the existing reminder scheduler/repository, recording engine and playback
handoff. UI presentation and focus changes do not allocate a playback controller or tune a stream.

Guide presentation preferences remain usable offline. Remembered local channel/time position
is excluded from cloud transfer, along with provider-specific references, URLs, playback state
and hardware settings. Seven enum/boolean values use the additive `guide_setting` record kind
under the existing opt-in `COMPATIBLE_SETTINGS` category. The forward-only guide migration extends
the existing validation guard without changing RLS, grants, merge RPCs or old records. Older
clients' explicit kind queries exclude the new kind. See [guide instructions](automatic-epg.md#advanced-guide),
[cloud sync](cloud-sync.md), and [guide testing](TESTING.md#advanced-epg).

Cloud sync deliberately does not serialize the legacy LAN/NAS `SyncBundle`: that bundle contains stream URLs, which can contain provider credentials. The original `CloudRecord` protocol and `nuvix_sync_records` table continue to carry additive favorites and watch progress. A separate versioned `AdvancedCloudRecord` protocol and bounded `nuvix_advanced_records` table carry allowlisted channel organization, UUID groups, home preferences, ordered app packages, compatible settings and opt-in favorite booleans. Each preference key or independent group field has its own identity; ordered member/package lists are whole-list merge units. This keeps a rename independent of membership changes.

Local Room rows and preference stores remain usable without a connection. Custom groups persist a UUID-to-local-ID mapping; cloud channel fingerprints resolve only to one authorized local channel, with unresolved references retained rather than converted into playable rows. Launcher state retains the desired package order independently of the installed subset. Remote settings enter existing typed setters rather than an arbitrary preferences import.

The existing email-code session, API client, Android Keystore-backed vault and network-constrained WorkManager are reused. Advanced state is account-bound and encrypted, including pending edits, replay/acknowledgement state and unresolved references. Pending changes coalesce by logical identity; acknowledgements follow successful remote merge, pull and durable local application. Remote application is distinguished from local authoring to avoid feedback uploads. Existing LAN/NAS scheduling and data formats are unchanged.

Owner-scoped RLS is enabled and forced on both cloud tables. Merge RPCs execute as the authenticated invoker with explicit search paths; server validation rejects unknown fields, incompatible versions and oversized data. The additive migration leaves the deployed original migration and records intact. HTTPS protects transport and the vault protects local storage, but selected metadata is visible to the Supabase project operator; this is not end-to-end encryption. See [cloud sync](cloud-sync.md) for exact scope, conflict rules and deployment, and [testing](TESTING.md#advanced-cloud-sync) for validation boundaries.

Player playback behavior remains derived from OpenTV. An Activity-scoped live session retains the existing `PlayerController` across navigation and transfers a single video surface between fullscreen and preview; VOD/catch-up/recording playback takes exclusive playback ownership. Changes to buffer settings do not recreate the player. Playback, preview, and browsing-audio preferences remain local. See [live playback](live-playback.md) for lifecycle, recovery, PiP, and low-memory behavior. Automatic updates perform background work and show notifications only with permission.

See the separate original `ARCHITECTURE.md` for historical upstream implementation notes; this document and feature-specific documents describe Nuvix changes.

Home presentation is local-first in `HomePreferencesStore` (`nuvix_home`). Legacy per-section
visibility keys remain authoritative. Versioned order and presentation values are bounded,
validated, and recover independently from malformed storage. Safe future local shelf keys
are retained. The fixed header and navigation actions are outside the customizable order.
`HomeCustomizationAccess` owns dialog/return focus; rearrangement keeps controls mounted
and focusable at boundaries. `TelevisionHomeScreen` retains its existing feed flows and
keyed lazy shelves. The existing group resolver is shared for individually positioned UUID
shelves. No playback controller or stream is created by customization.

Dynamic backgrounds debounce focus, decode one provider artwork request to at most 640×360,
retain the last successful painter while loading, and crossfade under a dark overlay.
Missing/failed artwork falls back to the default; Android's animator scale controls motion.
Presentation flows feed the same cards rather than reloading provider feeds.

The existing HOME_PREFERENCES category now also carries `home_order:shelves_v1`,
`home_layout:presentation_v1` and UUID-keyed `home_group` visibility. New kind identities
isolate these values from older clients' explicit kind queries. Order and presentation
are each one atomic merge revision with the existing timestamp/device/payload tie-breaker.
Existing visibility records, owner binding, durable journal and worker are reused.


## Sports Hub

The existing `ui/home/SportsScreen` renders hub and game details without a second navigation tab.
`SportsViewModel` retains the selected event through fullscreen playback and uses SavedStateHandle
for query/filter/event identity. It observes bounded Room queries, local preferences and current
EPG programs. Focus surfaces perform actions only on Select and restore the chosen game on Back.

`sports/SportsRepository` indexes each changed enabled EPG feed in keyset batches of 500 after
`EpgRepository.syncAll`, using revision checkpoints and a six-hour rolling seven-day horizon. It
never resolves a playback URL. Events and programme evidence are separate from durable local
favorites, content/provider-scoped decisions and preferred broadcasts. Indexed EPG-ID OR joins
preserve distinct provider variants, apply guide ID priority, and exclude disabled/hidden/locked
choices. Cards are capped at 200 initially (2,000 on request); stream pages read 200 distinct provider choices. Database filters apply before limits,
and a saveable hub subtree retains lazy scroll positions for game-card focus restoration.
A 50,000-program per-feed processing bound reports partial indexing rather than silently claiming
completeness. Source timestamps, not UI refresh timestamps, determine cache freshness.

`SportsMatching` requires paired normalized team identities, explicit competition and time evidence;
lower confidence is reviewable and does not count as available. Source-scoped verified IDs are
validated separately; cross-source identity requires an explicit compatible association. No external
provider is enabled. See [source review and behavior](sports-hub.md).

Room v13 adds six sports tables, programme/catalog lookup indexes and nullable unique
`reminders.sportsEventId`. `ReminderDao` resolves confirmed game identities for guide actions and
uses transactional insertion to avoid duplicate provider reminders. The existing alarm scheduler,
receiver, notification permissions and live playback session remain the owners of those effects.
The migration is additive and covered by the v2-to-current preservation regression.
Sports records are not in cloud protocol allowlists; hosted Supabase schema and encrypted cloud
journal behavior are unchanged. Derived caches can be cleared independently of local user intent.
