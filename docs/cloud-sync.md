# Optional Supabase cloud sync

Nuvix works without an account. Cloud sync is disabled until explicitly enabled. Use your own Supabase project; Nuvix provides no IPTV content, playlists, provider accounts or hosted backend. Each TV needs its own authorized content configuration. LAN/NAS sync remains independent and unchanged.

## Setup and migration rollout

1. Create or use your own Supabase project. Review and apply these forward-only migrations **in order**, using your normal migration workflow:
   - `supabase/migrations/20261013185918_cloud_records.sql`
   - `supabase/migrations/20261015071424_advanced_cloud_sync.sql`
   The second migration requires the first; it preserves existing favorites/progress rows. Do not edit the original deployed migration or reset the database. Repository validation uses isolated PostgreSQL; it does not deploy to your hosted project.
2. Enable email authentication and configure email delivery. The Supabase **Magic Link** template must contain the numeric `{{ .Token }}`, for example `<p>Your Nuvix sign-in code: {{ .Token }}</p>`. If signup is enabled, configure its confirmation template similarly. A clickable link alone cannot complete this TV code flow. Custom SMTP is usually needed for non-test recipients. CAPTCHA-required projects are not supported by this flow.
3. Open **Settings → Supabase cloud sync**. Enter the project's HTTPS origin and publishable client key (or legacy `anon` key), then save. Secret and `service_role` keys are rejected. The origin cannot contain credentials, paths, query parameters or fragments; custom HTTPS origins are supported.
4. Request an email code and enter it on the TV. This may create an Auth user if your project allows signup. The existing email-code flow is retained; no browser/QR device authorization is introduced.
5. Enable cloud sync. Select each additional category you want to share, on **each TV**. New categories default off, including for existing cloud users. Favorites/watch progress retain the original category behavior. Favorite removals require their own additional opt-in.
6. Repeat with the same Supabase project and email account on another TV. Use **Sync Now** to validate the session, capture pending permitted changes, upload, pull, apply and update status.

Deploy the additive migration before enabling advanced categories in updated clients. Older clients can continue using the original records. Older clients do not understand favorite removals; use updated clients on all TVs for that category. No production migration is applied automatically by the app or CI.

## Categories and supported data

| Category | Shared data | Default and limits |
|---|---|---|
| Favorites/watch progress | Channel/movie/series favorites; movie/episode resume positions | Existing opt-in semantics; legacy record format preserved |
| Channel organization | Hidden/unhidden state, custom display name, channel order | Off; name up to 120 Unicode code points, order 0–1,000,000 |
| Custom groups | UUID identity, name/deletion, display rank, ordered channel membership | Off; name 1–120 code points, rank 0–1,000,000, up to 2,000 unique references per group |
| Home preferences | Visibility of Continue Watching, Recent Channels, Favorites, On Now, Movies, Series and Apps shelves | Off; one boolean per supported shelf |
| App favorites | Desired ordered package list, including explicit unpins | Off; up to 200 unique packages, at most 255 characters each |
| Compatible settings | Theme, subtitle default, guide channel layout, startup destination | Off; strict key/value allowlist below |
| Favorite removals | Explicit true/false channel/movie/series favorite state | Off; additional opt-in, with versioned conflict resolution |

Home preferences synchronize shelf visibility only; shelf ordering and additional layout modes are not implemented. Live-preview/audio/persistence preferences and hardware-specific buffer tuning stay local.

The compatible-settings allowlist is exact:

| Key | Accepted values |
|---|---|
| `theme_mode` | `SYSTEM`, `DARK`, `LIGHT` |
| `subtitles_enabled` | Boolean |
| `channel_layout` | `GRID`, `LIST` |
| `startup_destination` | `HOME`, `LIVE_TV`, `GUIDE`, `LAST_WATCHED`, `APPS` |

Unknown keys, unexpected fields, wrong types, control characters in names and unsupported schema versions are rejected. No arbitrary SharedPreferences JSON is uploaded. Turning a category off stops its uploads/downloads, retaining local data and existing cloud rows. Retained pending edits are eligible again when that category is re-enabled; re-enabling does not create an account-wide reset.

The dashboard shows the signed-in account, enabled state, last successful sync, pending count, errors, **Sync Now** and **Sign Out**. A pending count describes the local queue, including edits waiting for a disabled category; it is not a count of server rows.

## Content, groups and app differences

Cloud content/profile identifiers use existing SHA-256 metadata fingerprints. Channels use the original channel name and EPG identity rather than a custom display name; movies/series prefer a positive numeric TMDB ID, otherwise normalized title/year; episodes add season/episode; profiles use normalized names. Provider IDs and local Room IDs are never uploaded.

A received reference is applied only when exactly one authorized local channel matches. Missing or ambiguous matches remain unresolved and can reconcile after a later catalog refresh; sync does not create playable channels or select one duplicate arbitrarily. Different provider titles or EPG IDs can prevent matching. Existing provider and parental visibility restrictions still apply.

The local editor supports up to 128 active custom groups; additional remote groups remain retained in the journal until space is available. Custom groups have stable UUIDs and a persistent mapping to local group IDs. Names, rank and ordered membership are separate records. A missing channel stays in the desired membership order even while omitted from local playable results. Recreating a deleted group requires a new UUID.

Existing local pin lists above the 200-package cloud bound are preserved without truncation and are skipped by the cloud exporter until reduced locally. The encrypted journal is bounded to 100,000 identities per state collection and 64 MiB in total, and each advanced pull is limited to 16 MiB of response data; exceeding those limits produces a setup error while retaining the previous durable journal and local data.

App favorites retain the complete desired cloud package order independently of the installed/displayable subset. A TV missing a pinned app hides that tile but retains the package reference; later installation makes it available again. Pin, unpin and reorder edit the desired list. Nuvix never transfers APKs or installs apps. Startup destination does not alter Android HOME-role grants, default launcher selection, device permissions or OEM configuration.

## Protocol and conflict semantics

The original `nuvix_sync_records` table and `nuvix_merge_records` RPC remain compatible. The original DTO has exactly `record_key`, `kind`, `profile_key`, `position_ms`, `duration_ms`, `updated_at_ms` and `device_id`. Allowed kinds remain `channel_favourite`, `movie_favourite`, `series_favourite`, `movie_progress` and `episode_progress`. Unrelated configuration is never stored in progress fields.

The additive `nuvix_advanced_records` table and `nuvix_merge_advanced_records` RPC use strict, typed version-1 records. Each logical identity belongs to one authenticated owner and one kind/key. Accepted kinds are `channel_hidden`, `channel_name`, `channel_order`, `group_name`, `group_rank`, `group_members`, `home_shelf`, `app_order`, `setting`, `channel_favourite_state`, `movie_favourite_state` and `series_favourite_state`. Upload batches contain at most 200 records.

Initial local values use low-priority seed revisions. Before their first upload, a pull gives an existing cloud value priority over a receiving TV’s defaults. Subsequent deliberate edits use a monotonic per-record timestamp.

Advanced records merge atomically per logical identity, using the newest update timestamp, then canonical device UUID lexical order, then a deterministic UTF-8 payload comparison for an otherwise equal revision. The client and SQL use the same comparison. Timestamps must be positive and no more than five minutes ahead of the server; accurate TV clocks matter. Idempotent replay and stale writes cannot replace a newer accepted record.

Each scalar setting, channel property and independent group field has a separate identity. If offline TV A renames a group while TV B edits membership, both survive reconnection. Two competing renames select one deterministic winner. App order and a group's ordered membership each form **one whole ordered-list merge unit**: concurrent edits to the same list select one complete revision, rather than interleaving lists. Lists reject duplicate entries and preserve valid order. Group rank and per-channel rank are separate scalar records. Group rank ties use the stable UUID; channel rank ties follow the existing channel-name ordering.

A group deletion is a permanent tombstone for that UUID and wins even over later stale/live updates. Deletion never physically removes the server row; replay cannot resurrect it. Create a fresh group to reuse a name. Missing provider content or missing installed apps never produces a deletion.

Favorites remain additive unless the user explicitly enables favorite-removal synchronization. In the original mode, a local removal is remembered on that TV without removing other TVs' favorites. With the additional option enabled, explicit boolean state carries both selections and removals for channel/movie/series favorites. A temporarily unavailable item does not count as an explicit removal. Devices without that option continue using legacy behavior; use matching options on all TVs for consistent removals.

Legacy watch progress retains its existing per-record timestamp/device comparison, including intentional rewinds and zero-position resets. Equal-timestamp incoming progress preserves the local row. The advanced protocol does not replace progress merging with a whole-library snapshot.

## Offline journal, retries and account boundaries

Local Room data and preferences save immediately and work offline. Permitted advanced mutations are captured into an encrypted durable, owner-bound journal without waiting for network availability. Rapid edits coalesce by logical identity. Pending records, remote baselines and unresolved references survive process restarts; a corrupted journal produces a visible error instead of uploading unchecked data.

The existing unique network-constrained WorkManager work coalesces changes, with a 15-minute periodic catch-up and bounded exponential retry backoff. Android controls actual execution timing. There is no permanent sync service or frequent polling. Transient network/rate-limit/server failures retain pending state; authentication/schema failures require attention in the dashboard.

Each run persists pending state, pulls existing advanced values, resolves and applies permitted changes, uploads safe pending records, then pulls and applies the current server winners. Local edits are captured again after network waits; local adapters compare their expected fields inside Room transactions before writing, preserving edits that raced with remote application. Acknowledgement occurs only after durable local completion and removes only the exact acknowledged revision, preserving newer edits made during the run. If interrupted, replay is safe. Remote-applied changes update the recorded baseline and are not authored as fresh local edits, preventing upload loops.

Pausing full sync cancels scheduled work without deleting local or server data. Signing out turns sync off, clears local tokens and account-bound pending/acknowledgement state, and attempts session revocation. Offline sign-out still removes local tokens; already issued access JWTs may remain valid until expiry. Project/account changes reset category opt-ins and do not replay another account's journal. Local content/preferences remain on the TV: sign-out is not a local-data wipe. Removing cloud setup also removes encrypted cloud configuration. None of these actions deletes server records; an operator can manage those separately, and deleting the Auth user cascades owned rows.

## Security and privacy

Both cloud tables enable and force RLS. Every permitted read/write is restricted to `auth.uid() = owner_id`; ownership cannot be reassigned. Merge functions run as `SECURITY INVOKER` with an explicit safe search path and derive ownership from the session. Anonymous users cannot access tables or RPCs. Advanced clients receive no physical DELETE privilege: tombstones pass through the validated merge protocol. Owner-leading primary keys and owner/pagination indexes support bounded authenticated reads.

The protocol excludes provider credentials, URLs, stream headers, IPTV tokens, MAC addresses, Supabase tokens/keys, PINs, device encryption keys, Wi-Fi credentials, recording/NAS paths or credentials, DRM state, local IDs, Android permissions and device-specific playback/buffer settings. Cloud sync never uploads the legacy LAN/NAS `SyncBundle`, whose stream URLs may contain secrets.

HTTPS protects transport. The existing Android Keystore-backed AES-GCM vault protects tokens, project configuration, pending records and synchronization state under `noBackupFilesDir`. This is **not end-to-end encryption**: the Supabase project operator can read selected metadata, custom group/channel names, package lists, resume positions, timestamps and random device IDs. Fingerprints can be guessed from familiar metadata and do not make viewing history anonymous. App package names may reveal interests; their category requires explicit opt-in. Hosted database encryption, backups and operator access are the project owner's responsibility. Diagnostics must not include raw tokens, credentials or full provider URLs. See [privacy](PRIVACY.md).

## Validation and troubleshooting

Run `bash scripts/test-supabase.sh` against its isolated Docker PostgreSQL harness, never a production database. It applies all migrations in order and runs the SQL security/merge tests. JVM cloud tests run with `./gradlew :app:testDebugUnitTest`. See [testing](TESTING.md#advanced-cloud-sync) for the two-TV acceptance procedure and evidence limits.

| Symptom | Check or action |
|---|---|
| Code never arrives or only opens a link | Check email delivery/SMTP, signup policy and numeric `{{ .Token }}` templates; CAPTCHA is unsupported |
| Advanced schema/RPC unavailable | Apply both migrations in order to the configured project; check Data API schema exposure and migration grants |
| Signed in but nothing transfers | Enable full sync and the same category on both TVs, then use Sync Now; check pending/error status |
| Clock error or edits remain pending | Set both TVs to accurate automatic date/time; timestamps over five minutes ahead are rejected |
| Channel/group entry is missing | Check authorized local content, original name/EPG fingerprints, duplicates and visibility restrictions; refresh catalogs and sync again |
| Pinned app is absent on one TV | Install it yourself if desired; the desired pin remains and Nuvix never installs apps |
| A concurrent list edit is absent | App/member order uses a whole-list winner; repeat the intended edit after both devices have synchronized |
| Deleted group does not return | Tombstones permanently retire a UUID; create a new group |
| Authentication expired | Sync refreshes sessions when possible; sign in again if revoked/expired credentials cannot refresh |
| Encrypted state unavailable/corrupted | Keep ordinary local data; remove cloud setup and configure/sign in again. Unsynced cloud journal state may be lost during reset |

Use a disposable project and two isolated clients for account-switch/deletion acceptance. Real OTP delivery, Android Keystore process-death behavior, vendor launchers and two-TV convergence require actual device verification; passing JVM/SQL tests alone does not establish those results. Supabase references: [email OTP](https://supabase.com/docs/guides/auth/auth-email-passwordless), [RLS](https://supabase.com/docs/guides/database/postgres/row-level-security) and [Data API security](https://supabase.com/docs/guides/api/securing-your-api).

### Sparse catalog state

Advanced sync exports explicit channel visibility and order edits, custom names, and favourite
intent. Provider-assigned order and untouched visibility/favourite defaults do not create cloud
records. Local edit markers retain explicit resets (unhide, clear name, unfavourite, or order zero)
through catalog refreshes. Existing custom names and positive favourites can seed a new device;
visibility and order changes made before this version need to be reapplied once to mark their intent,
because older catalogs do not distinguish those edits from provider defaults. Unavailable catalog
rows produce no reset or deletion, and saved cloud references reconcile when those rows return.

A remote record ahead of a TV's clock is structurally validated during download and deferred by
the journal. It does not stop unrelated records or categories from syncing.
