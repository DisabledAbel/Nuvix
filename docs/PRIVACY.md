# Nuvix privacy and security

Nuvix ships no IPTV content, provider accounts, analytics, or tracking SDK. Users add their authorized providers. The local provider database includes source credentials and credential-bearing playback URLs; the Nuvix database protection is described in the implementation report. Android backup is disabled so provider and Keystore-dependent state is not copied into automatic cloud backups.

Ordinary playback contacts the configured provider and image/stream addresses supplied by that provider. User-enabled metadata, guide, Stremio, LAN pairing, NAS, update, and Supabase features have their own destinations. Existing optional regional guide feeds contain program listings only; they are not bundled channels or playlists.

Supabase cloud sync starts disabled. It sends only typed favorites/progress records with hashed metadata identities, bounded progress/timestamps, and device identifiers to the user-configured HTTPS project. Hashing is an identity mechanism, not a promise of anonymous viewing history. No stream/playlist URL, provider login/password/MAC/token, arbitrary settings blob, service-role key, or recording path is uploaded. Cloud tokens and durable outbox state use Keystore-backed encryption. Signing out removes local cloud tokens; existing server records are not automatically deleted.

Legacy LAN/NAS sync is explicitly user controlled and preserved. Its original bundles use stream URLs as cross-device identifiers; those may contain provider credentials. Use only trusted local peers/private NAS storage, protect NAS accounts, and do not expose pairing/sync servers publicly. The new Supabase path never reuses those bundles.

HTTP-only providers remain supported. On HTTP, usernames, passwords, and stream requests are unencrypted in transit. Prefer provider HTTPS addresses. Local encryption does not protect network HTTP or a fully compromised/rooted device. Do not paste secrets in public bug reports; diagnostics should classify failures without credential-bearing URLs or raw exceptions.

Updates contact only the explicitly configured `DisabledAbel/Nuvix` GitHub release origin and its approved HTTPS download destinations. The APK must match declared SHA-256/size, installed package, increasing numeric version code, and the installed signing certificate. The OS validates installation and asks for required user approval. Debug and release signatures/identities are distinct.

Uninstalling removes local data and Keystore material. Cloud records remain until removed from the configured Supabase project. Keep private signing keys off source control and securely backed up.
