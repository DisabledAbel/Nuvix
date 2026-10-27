# Nuvix privacy and security

Nuvix ships no IPTV content, provider accounts, analytics, or tracking SDK. Users add their authorized providers. The local provider database includes source credentials and credential-bearing playback URLs; the Nuvix database protection is described in the implementation report. Android backup is disabled so provider and Keystore-dependent state is not copied into automatic cloud backups.

Ordinary playback contacts the configured provider and image/stream addresses supplied by that provider. TV detail metadata and user-enabled guide, Stremio, LAN pairing, NAS, update, and Supabase features have their own destinations. Existing optional regional guide feeds contain program listings only; they are not bundled channels or playlists.

TV show detail pages automatically contact `api.tvmaze.com` and the best-effort
secondary service `api.imdbapi.dev` with cleaned show titles, release years, or
public IMDb IDs. Actor profiles request public TVmaze person IDs. These services
and their image hosts receive normal network information such as your IP address.
Provider logins, playlist/stream URLs, source IDs, watch history, and cloud tokens
are not sent to metadata services. Public responses and images are cached locally;
metadata cache contents are not uploaded to Supabase. Existing TMDb traffic remains
gated on a user-supplied key. See [TV metadata](tv-metadata.md) for matching,
retention, and attribution.

Supabase cloud sync starts disabled. The original category sends typed favorites/progress records with hashed metadata identities, bounded progress/timestamps, and random device identifiers to the user-configured HTTPS project. Additional categories require separate opt-ins: channel names, visibility and ordering; custom group names, UUIDs and ordered channel references; home shelf visibility; pinned application package names and ordering; and allowlisted theme/subtitle/guide/startup preferences. A separate opt-in synchronizes explicit content favorite removals. Package lists and custom names can reveal personal interests. Disabling a category stops its transfers without erasing existing server records.

Hashing is an identity mechanism, not a promise of anonymous viewing history: familiar titles or profile names can be guessed. HTTPS protects transport, and tokens, durable pending changes and acknowledgement state use Android Keystore-backed local encryption. Cloud payloads are not end-to-end encrypted; the Supabase project operator can read the selected metadata, names, package lists, viewing positions/timestamps and random device identifiers. Project database/storage protection is the operator's responsibility. No stream/playlist URL, provider login/password/MAC/token, arbitrary settings blob, service-role key, PIN, recording/NAS path or credential, Android permission, or device playback tuning is uploaded. See [cloud sync](cloud-sync.md) for the exact allowlist.

Signing out removes local cloud tokens and account-bound synchronization state and pauses sync; it does not erase local viewing preferences or existing server records. Changing accounts does not replay the previous account's pending queue. Local preferences remain visible on the same TV and become eligible for a new account only after that account explicitly enables the relevant category.

Legacy LAN/NAS sync is explicitly user controlled and preserved. Its original bundles use stream URLs as cross-device identifiers; those may contain provider credentials. Use only trusted local peers/private NAS storage, protect NAS accounts, and do not expose pairing/sync servers publicly. The new Supabase path never reuses those bundles.

The optional Cloudflare browser manager starts only after **Start browser session** is selected.
It relays channel/category details, management actions and provider/NAS details submitted in forms
through the displayed HTTPS service. This traffic is not end-to-end encrypted: Cloudflare and the
service operator can inspect it. The manager stores only credential hashes and short session
deadlines, not provider/NAS credentials or catalogs; it does not log command bodies. Browser tokens
stay in memory and are not included in URLs or persistent browser storage. Existing NAS passwords
are not returned to the browser. Codes expire in five minutes, sessions in at most thirty minutes,
and leaving the TV manager ends access. See [service setup, privacy and limits](cloudflare-manager.md).

HTTP-only providers remain supported. On HTTP, usernames, passwords, and stream requests are unencrypted in transit. Prefer provider HTTPS addresses. Local encryption does not protect network HTTP or a fully compromised/rooted device. Do not paste secrets in public bug reports; diagnostics should classify failures without credential-bearing URLs or raw exceptions.

Updates contact only the explicitly configured `DisabledAbel/Nuvix` GitHub release origin and its approved HTTPS download destinations. The APK must match declared SHA-256/size, installed package, increasing numeric version code, and the installed signing certificate. The OS validates installation and asks for required user approval. Debug and release signatures/identities are distinct.

Uninstalling removes local data and Keystore material. Cloud records remain until removed from the configured Supabase project. Keep private signing keys off source control and securely backed up.
