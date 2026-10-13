# Local credential storage

Nuvix encrypts its complete Room database with SQLCipher for Android, using the maintained
`net.zetetic:sqlcipher-android` replacement library. Encryption covers sources, portal MAC
credentials, provider passwords, playlist URLs, guide feed URLs, and cached stream URLs across
channels, movies, episodes, and recordings. The schema and existing DAO APIs remain unchanged.

Each installation creates a random 32-byte database password. AndroidKeyStore holds a separate
256-bit AES wrapping key. Only a versioned AES-GCM authenticated envelope of the database password
is saved in private preferences; there is no plaintext passphrase, password constant, or private
key in the source/APK. The wrapper uses a random 12-byte nonce, a 128-bit authentication tag, and
domain-specific associated data. AndroidKeyStore keys are hardware-backed when the device supports
it; hardware backing is not assumed. Keys do not require user authentication so scheduled
recordings can run while a television is asleep.

The database opens through a lazy Room helper. Native library loading, key access, and any one-time
conversion run when Room first opens on its worker thread, rather than during building the UI's
dependency graph. The Java SQLCipher logger is disabled. Normal encrypted WAL operation remains
enabled for guide performance.

## Upgrading a plaintext Nuvix database

Nuvix has an independent application ID, so it does not inherit OpenTV's private database. For
pre-encryption Nuvix installations, the helper recognizes the SQLite plaintext header, checkpoints
and closes its WAL, exports into a new encrypted temporary database using `sqlcipher_export`,
preserves `user_version`, and verifies SQLite integrity plus SQLCipher authentication. It syncs the
completed file and atomically replaces the old database within the same directory. An interrupted
or failed export leaves the original database; temporary exports are discarded and retried.
The original schema, Room identity table, credentials, favorites, and history are exported.

Nuvix binds SQLCipher's native corruption handler directly through its SQLiteOpenHelper; Room's corruption callback alone is not forwarded by the pinned library's default factory. A regression test checks the actual native-handler binding and retained file bytes without opening native SQLCipher.

Nuvix does not use destructive Room migration fallback. Missing migrations, missing/invalidated
keys, corrupt envelopes, and corrupt encrypted databases produce an error while retaining existing
data. They do not silently create a replacement key or clear the database. There is no automatic
plaintext fallback.

## Credentials outside Room

NAS passwords, user-supplied TMDB API keys, and Stremio add-on JSON containing potentially credential-
bearing manifest URLs use a separate AES-GCM AndroidKeyStore vault. Public `AppSettings` setters
and StateFlows remain compatible. Legacy plaintext values are encrypted and read back successfully
before their original preference entries are removed. Interrupted migration can resume safely;
conflicting encrypted/plaintext copies are retained and reported rather than overwritten.
Ordinary display preferences, NAS host/user/share names, and launcher package names remain local
non-secret preferences. Supabase sessions have their own encrypted credential vault.

## Recovery and limitations

Android backups are disabled. Explicit legacy backup rules and Android 12 cloud/device-transfer
exclusions also cover app databases, preferences, files, root data, external app files, and
device-protected data, preventing unusable Keystore-dependent stores from being transferred even
where firmware ignores `allowBackup` for device migration. AndroidKeyStore keys are tied to the application's installation and
device: copying an encrypted database and wrapped preferences to another device is insufficient to
unlock them. Uninstalling Nuvix, clearing app data, losing/invalidation of a Keystore key, or
changing package/signing identity can make existing encrypted data unavailable. Preserve the files
when troubleshooting; clearing app storage is a deliberate destructive recovery choice and requires
re-entering sources. Do not change signing identity for upgrades.

Encryption at rest does not protect credentials on a rooted/compromised running device, secrets
held in playback memory, authorized legacy NAS/device sync exports, user-created recording content,
or plain HTTP network traffic. Prefer HTTPS and trusted networks. Legacy plaintext storage may have
forensic remnants on flash; the migration does not claim secure physical erasure.

SQLCipher is pinned to 4.12.0 to stay compatible with this app's Kotlin/Android toolchain. Newer
published artifacts require newer SQLite/Kotlin/compileSdk versions; upgrade these together and
rerun encrypted database and signed-update device checks before advancing the dependency.

## Validation

JVM/Robolectric tests exercise real AES-GCM wrapping with injected test keys, persistent recovery
envelopes, tamper rejection, missing-key failures, legacy private-settings migration, plaintext file
header detection, and existing Room schema migration data preservation. They do not emulate
AndroidKeyStore hardware or execute SQLCipher's Android native library. Before a public release,
run on Android TV hardware: new database creation, plaintext conversion including pending WAL data,
process interruption during conversion, signed application update, locked-screen recording, and
key-loss recovery messaging. A successful APK build is not evidence of these native device checks.
