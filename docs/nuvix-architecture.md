# Nuvix architecture

Nuvix keeps OpenTV's native Kotlin/Compose/Media3/Room structure. The upstream commit and original-file digests are recorded in `upstream-source.json`. `MainActivity` owns navigation, the sidebar/home screens present local provider catalogs, `ServiceLocator` retains the dependency graph, and existing catalog, EPG, player, recording, reminder, LAN pairing, and NAS components remain in place. The Kotlin source namespace `app.opentv` preserves upstream structure; generated Android resources and installed identity use `com.disabledabel.nuvix`.

New code has explicit boundaries: `launcher/` wraps PackageManager, optional HOME alias activation, app favorites, and startup preferences; `cloud/` implements optional email OTP, an encrypted allowlisted outbox, REST/RPC sync and WorkManager retry; `update/` validates fixed-origin release metadata, hashes, Android package/version/signers and uses interactive PackageInstaller sessions. `player/BufferSettings` and `ConfigurableLoadControl` expose bounded, live-applied buffering. `core/RecentChannels` persists local channel identifiers only.

The guide downloads and validates an XML feed off the main thread before replacing a feed inside a Room transaction. Parsing or database failure rolls back previous programs/aliases. Catalog and VOD loading continue through the original repositories. Home channel/sports shelves use bounded database queries instead of loading full playlists.

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
