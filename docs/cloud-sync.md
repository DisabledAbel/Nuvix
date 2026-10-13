# Optional Supabase cloud sync

Nuvix works without an account. Cloud sync is off on a fresh install and does not contact Supabase until the user configures their own project and starts authentication. Existing LAN and NAS sync remain separate and unchanged.

## Setup

1. Create or use your own Supabase project. This repository does not contain a hosted Nuvix backend or project credentials.
2. Apply `supabase/migrations/20261013185918_cloud_records.sql` to that project through your normal migration workflow or Supabase SQL Editor. The app does not create tables automatically.
3. Enable email authentication and configure email delivery. For email OTP, the Supabase **Magic Link** template must include the numeric `{{ .Token }}` variable, for example `<p>Your Nuvix sign-in code: {{ .Token }}</p>`. If new users are allowed, configure the signup confirmation template to include the token as well. A template containing only a clickable link cannot complete this TV code flow. Custom SMTP is usually needed for non-test recipients. Projects requiring a CAPTCHA challenge are not supported by this TV flow yet.
4. Open Settings → Cloud sync on the TV. Enter the project HTTPS origin and its publishable key (or legacy `anon` key), then save. Secret and `service_role` keys are rejected. The origin must have no credentials, path, query, or fragment; self-hosted/custom HTTPS origins are supported.
5. Request an email code, read it on a phone or computer, and enter the code on the TV. This can create an Auth user if signup is enabled in the project. No QR or browser handoff is needed.
6. Enable cloud sync explicitly. Use the same project and email account on another device; that device must have its own legally obtained provider configuration and matching content/profile names.

## Shared data and identity

The cloud DTO has exactly seven fields: `record_key`, `kind`, `profile_key`, `position_ms`, `duration_ms`, `updated_at_ms`, and `device_id`. Postgres adds `owner_id` from the authenticated session and a pagination ID. Allowed kinds are channel/movie/series favourites and movie/episode watch progress. The function rejects extra fields.

Content and profile identities are SHA-256 fingerprints of selected metadata: channels use their display name and EPG ID; movies/series use a positive numeric TMDB ID when present, otherwise normalized title and year; episodes use the series fingerprint plus season/episode number; profiles use normalized names. Credentials, provider URLs, playlists, source IDs, recordings, MAC addresses, local database IDs, PINs, settings and app launch details are never part of this DTO. No LAN `SyncBundle` is uploaded: that upstream format intentionally contains stream URLs and is unsuitable for cloud use.

Fingerprints are identifiers, not encryption: someone with database access could guess known titles/profile names. The project operator can see viewing timestamps, resume times and the random device identifier. Transport uses HTTPS; tokens, project configuration, queue and acknowledgement state are AES-GCM encrypted locally with a non-exportable Android Keystore key and stored under `noBackupFilesDir`. No network request or error log includes the stored tokens or provider credentials.

Progress applies only when one local content row and one local profile match. Ambiguous duplicate titles/profiles are skipped instead of choosing an arbitrary item. Content missing on the receiving TV stays in the cloud for a later sync. Changes in provider titles, missing TMDB metadata or differing EPG IDs can prevent matching. Profile creation/deletion and app favourites/launcher preferences are not synchronized in this version.

## Local-first merge, queue and retry

The local Room database is authoritative for offline use. Local changes schedule unique network-constrained WorkManager work; a 15-minute periodic task catches edits missed while the process was stopped. WorkManager timing is controlled by Android. Each run persists its outbox before making an upload request, retains it on failure, and retries transient network/rate-limit/server failures with exponential backoff. Authentication/schema errors are shown and need user attention. Acknowledgement is recorded only after upload, pull and local application complete. Replaying a partially completed batch is safe.

Favourites merge additively. Local removals are remembered on that TV so a downloaded favourite does not silently restore one the user removed. They do not delete cloud favourites or remove favourites on other devices. There is no destructive account-wide reset in the app.

Watch progress uses the newest `updated_at_ms` **per record**, including intentional rewinds and zero-position resets. The atomic Postgres upsert compares timestamp and then device UUID to prevent an older concurrent upload replacing newer progress. Equal-timestamp incoming progress preserves the local row. A device clock more than five minutes ahead is rejected; accurate device clocks matter. This is record-level synchronization, never a whole-library overwrite.

Pausing cancels scheduled work and retains local data. Signing out removes this TV's session and cloud queue, attempts to revoke its Auth session, and turns synchronization off. Offline sign-out still clears local tokens; Supabase access JWTs may remain valid until expiration if revocation cannot reach the server. Removing setup clears its encrypted cloud files. These operations do not delete remote records. An operator can remove account rows through Supabase; deleting the Auth user cascades its rows.

## Row security and validation

Every exposed table enables and forces RLS. SELECT/INSERT/UPDATE/DELETE policies require `auth.uid() = owner_id`; UPDATE includes both USING and WITH CHECK. Anonymous users have no table/function access. The merge RPC is SECURITY INVOKER with an empty search path; the caller never supplies ownership. The primary key begins with `owner_id`, and `(owner_id, id)` supports paginated owner-scoped reads. No view or privileged function bypasses RLS.

The migration is supplied for operator review; no external database is modified during repository setup. Pure Kotlin tests cover metadata identity, payload allowlisting, malformed/future record rejection, intentional rewind conflicts, deterministic ties and secret-key rejection. The migration and `supabase/tests/cloud_records.sql` were executed successfully in isolated PGlite/PostgreSQL, checking account isolation, anonymous denial, owner reassignment rejection, unexpected-field rejection and stale replay. Real OTP delivery and a real Android Keystore session still require a configured project and device. See Supabase's [email OTP guide](https://supabase.com/docs/guides/auth/auth-email-passwordless) and [RLS guide](https://supabase.com/docs/guides/database/postgres/row-level-security).

Current implementation verified against the Supabase documentation tool and Auth OpenAPI `/otp`, `/verify`, `/token?grant_type=refresh_token`, and `/logout` contracts. The lightweight changelog endpoint returned HTTP 403 in the build environment; no hosted project was used as a fallback.
