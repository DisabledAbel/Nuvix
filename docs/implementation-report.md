# Nuvix implementation report

Date: October 13, 2026. Repository: https://github.com/DisabledAbel/Nuvix. Branch: `feat/nuvix-tv-foundation`.

## 1. Implemented

Nuvix now has a native Kotlin/Compose Android project derived directly from OpenTV commit `43b95c05d8f6a042f51c0cff662ef80789ee7528`. The original GPL license and copyright notices remain. The independent Android namespace/application ID is `com.disabledabel.nuvix`, version `0.1.0`, version code `21`; debug uses `.debug`. Internal Kotlin packages remain `app.opentv` to preserve the audited working source structure. The repository was not created with GitHub's Fork feature, and no official affiliation with OpenTV or DIRECTV is suggested.

The original Media3 playback, M3U/M3U8/Xtream/authorized Stalker sources, XMLTV guide, movies/series, profiles, favorites, recordings/reminders, catch-up, PiP, external playback, LAN/NAS synchronization and provider setup are retained. Nuvix adds an original identity/icon/banner/splash/About, nine-section TV navigation, local provider-derived Home shelves, recent channel identifiers, Sports category discovery, Library, real installed-app discovery/pinning/ordering/info shortcuts, and a reversible opt-in HOME alias with compatibility/status/startup settings and device-settings access.

New protection includes whole-database SQLCipher encryption with a Keystore-wrapped random key, encrypted credential-adjacent preferences, explicit backup exclusions, source URL/MAC/header validation, credential-free diagnostics, and retained-data recovery controls. A directly bound SQLCipher corruption handler avoids the library's default delete-and-retry behavior. Buffer policies change the active Media3 load control without replacing the player. Guide replacement is transactional; failed/truncated/empty refreshes keep valid data, and bounded temporary XML is encrypted with an ephemeral key.

Protected startup settings and database work run on workers, including the Application's optional NAS-sync preference lookup, which otherwise initialized encrypted settings on the main thread.

Optional Supabase email-code authentication and favorites/progress sync use an encrypted durable outbox, offline retries, explicit opt-in, typed metadata identities, owner-only RLS and atomic per-record conflict handling. Provider credentials and URLs are excluded. Existing LAN/NAS sync is preserved separately.

The update manager uses stable `DisabledAbel/Nuvix` releases, numeric version codes, required `update.json`, integrity/size/package/version/installed-signer verification, cancellable downloads, optional scheduled checks/notifications/downloads and interactive Android PackageInstaller sessions. CI builds/tests/lints debug artifacts; release workflows require a production signer and pinned public fingerprint and attach APK metadata/checksums/corresponding source.

## 2. Files changed

The baseline import contains 184 files including the unchanged LICENSE; seven upstream-tracked transient compiler logs are excluded from version control. Significant Nuvix changes are in:

- `app/build.gradle.kts`, Gradle settings/wrapper, `.github/workflows`, `scripts/create-release.py` and publication tests.
- `MainActivity.kt`, `OpenTvApp.kt`, `ui/MainScreen.kt`, `ui/home/`, settings screens, theme/resources/icons/banner/splash and default/localized branding.
- `launcher/`, manifest HOME alias/queries, and XML backup/transfer exclusions.
- `core/DatabaseKeyStore.kt`, `PrivateSettingsVault.kt`, `ProviderDiagnostics.kt`, `RecentChannels.kt`, `data/db/EncryptedDatabaseFactory.kt`, Room configuration and bounded Home/Sports queries.
- `player/BufferSettings.kt`, `ConfigurableLoadControl.kt`, `PlayerController.kt`, XMLTV parsing, `EpgRepository.kt`, `EncryptedGuideSpool.kt`, source validation and safe provider/network diagnostics.
- `cloud/`, `supabase/migrations/20261013185918_cloud_records.sql`, SQL account-isolation/replay assertions and cloud tests.
- `update/`, `data/remote/GitHubReleaseApi.kt`, update security/discovery tests and installer status receiver.
- README, contributing/setup/changelog, feature/build/signing/privacy/architecture/roadmap documents, upstream provenance, COPYING, dependency notices and license texts.

The complete path list is in `changed-files.txt` alongside this report and in the source archive.

## 3. Tests executed

- **151 Android JVM/Robolectric tests passed; zero failures/errors/skips.** Coverage includes inherited playlist/XMLTV/normalization/pairing tests; bounded Home/Sports/history queries; launcher discovery/pinning/ordering/status; actual AES-GCM envelope/secret migration behavior; Room schema preservation; native-handler binding without claiming native database execution; buffer validation and runtime policy changes; guide rollback after flushed batches; encrypted guide-frame tampering/truncation; URL/MAC/header validation and redacted diagnostics; cloud allowlists/identities/outbox/conflicts; and update origin/version/package/length/hash/signing trust.
- **10 release-publication Python tests passed**, including wrong package/version/tag/debug key/changed signer/split APK rejection.
- Supabase migration and isolation/replay assertions passed in isolated **PGlite/PostgreSQL**: forced/enabled RLS, owner access, cross-account/anonymous denial, owner reassignment rejection, unknown fields, bounds, stale rewind replay and deterministic ties. No connected production database was changed.
- **Actionlint passed** for build, release and retained Pages workflows.
- **Hosted GitHub CI passed** after explicitly selecting supported Android SDK 35/platform/build-tools packages instead of the setup action's retired `tools` default: https://github.com/DisabledAbel/Nuvix/actions/runs/38470175257. Final startup changes use the same workflow and are also rebuilt and tested locally.
- **Android lint passed with zero errors and 64 warnings**. Warnings remain, including inherited locale/unused-resource/deprecation issues and intentionally durable preference commits. Specific untranslated English fallback strings are explicitly scoped; translations of new Nuvix surfaces remain further work.
- `git diff --check` passed. The GPL license is byte-identical to the imported upstream license. The debug APK's Android signatures and package/version identity were checked.

## 4. Build results

JDK 17, Android SDK/build tools 35, Gradle 8.11.1 with verified wrapper/distribution checksum, AGP 8.7.3 and Kotlin 2.0.21 were used.

`./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleRelease` completed successfully. The cloud workspace required its inherited proxy/CA configuration and a JDK with compiler; no signing or provider secrets were supplied.

- Installable debug APK: `com.disabledabel.nuvix.debug`, version code 21 / version name 0.1.0, Android debug certificate. 56333370 bytes; SHA-256 `038e826172f5aac85521aaf2c3996896304f73fa6133722eef96058528bb796e`.
- Optimized release APK: `com.disabledabel.nuvix`, version code 21 / version name 0.1.0, **unsigned**. 29916152 bytes; SHA-256 `fa866dc826897b59bacfbb67130f2139835e20ad1b85bb80bae2d82f6811e937`. It must be signed before installation/distribution and is not a published production release.
- Corresponding Nuvix source, build scripts, migrations, tests and documentation are included in the source archive. The external artifact manifest identifies the exact source commit and file hashes.

Runtime smoke result: Android TV 9 / API 28 x86 software emulator installed the debug APK and rendered the actual source-free Nuvix Home screen. Native Room/SQLCipher startup succeeded; its 16-byte database header is not the plaintext SQLite signature. Reinstall with adb -r also succeeded using the same debug identity. Software-emulator retries without a hardware accelerator included 37–55 second launches and an input-dispatch ANR while waiting for the first window; startup performance and responsiveness remain acceptance items. This is not a hardware performance or complete remote/launcher/playback test. The screenshot in screenshots/nuvix-home.png is a real emulator capture, not a mockup.

## 5. Unfinished or unverified

Real Android TV/Google TV/Fire TV acceptance remains required for D-pad/Back/long-press flows, default-home OEM restrictions and restoration, actual authorized streams/PiP/recording/catch-up, process death, native SQLCipher open/plaintext migration/key recovery and real interactive APK installation/cancellation. A compiling player and unit tests are not a live-provider verification.

A real Supabase project is needed to verify OTP delivery, platform Keystore sessions, offline/device-to-device synchronization and owner isolation end to end. The migration/merge protocol is tested locally; it was not applied to an external project.

Current cloud scope is additive channel/movie/series favorites and movie/episode progress. Favorite removals stay local and are not destructive cloud deletions. Custom groups, installed-app favorites, app settings and launcher preferences remain local. QR/device authorization is not implemented; email codes are the real sign-in flow.

Range-resumable update transfers, seamless persistent live playback across every navigation surface, richer provider sports-event metadata, a pluggable playback-engine contract, broader localization and a hardware performance/accessibility matrix remain further work. Historical OpenTV screenshots are labeled as historical and are not Nuvix screenshots. No fake channels/providers/accounts were shipped.

## 6. Configuration required

Supply your own authorized provider URLs/credentials on the device. Prefer HTTPS; set XMLTV and any compatible provider User-Agent/container options as needed. Launcher selection depends on supported system settings/roles; enabling the alias does not silently replace the default Home.

For optional cloud sync, deploy the supplied migration, configure Supabase email OTP with `{{ .Token }}` in the template, enter the HTTPS project URL and a publishable/anon client key, sign in, then explicitly enable sync. Never use a service-role key in the app.

For production updates/releases, configure GitHub signing secrets `KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`, and public repository variable `SIGNING_CERT_SHA256`. Retain the stable application ID, increasing version code and same long-lived signing certificate. Losing the signing key prevents normal upgrades. Debug and production IDs/signers/data are separate.

## 7. Security considerations

Provider credentials/credential-bearing URLs stay in encrypted local stores and never enter the Supabase DTO. Cloud sync starts off; it uses HTTPS and does not forward auth headers through redirects. RLS is enabled/forced on every supplied user-owned table. Metadata hashing is an identity mechanism, not a promise of anonymous viewing history.

Legacy HTTP providers and user-activated LAN pairing/NAS sync remain supported. HTTP sends authentication unencrypted; legacy LAN/NAS bundles may contain stream URLs with credentials, so use trusted peers/private NAS storage. They are not uploaded through the new cloud path.

Keystore-backed encryption does not repair missing keys or fully compromised devices. Key/migration errors retain data instead of silently resetting it; backup/transfer exclusions prevent unusable restores. The real native database path still needs device acceptance. No private production signing key, provider account, service-role key, DRM bypass, root requirement, accessibility Home interception or original-launcher modification is included.

Update metadata is not a signing trust root: the APK must match the installed signer as well as its declared hash/package/version. Android governs install permission and user confirmation. Nuvix does not silently install on consumer devices.

## 8. Next recommended phase

Complete **Phase 6 device acceptance** before announcing a stable consumer release. Test protected persistence and upgrade/recovery first, then remote navigation/launcher reversibility, authorized playback, recording/PiP, real cloud auth/offline conflicts and signed update installation. Configure production signing, run the release workflow on a matching tag, and distribute the exact corresponding source. Expand cloud preferences and continuous playback only after these flows pass.

Publication: Committed and pushed on the feature branch; draft pull request https://github.com/DisabledAbel/Nuvix/pull/1 is open. Main remains unchanged. No production release/tag was published.
