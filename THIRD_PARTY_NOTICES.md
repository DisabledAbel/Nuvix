# Third-party notices

## OpenTV

Nuvix directly incorporates the Android source of OpenTV, rather than recreating its IPTV implementation.

- Project: <https://github.com/opentvproject/opentv>
- Imported revision: `43b95c05d8f6a042f51c0cff662ef80789ee7528` (OpenTV 0.11.8)
- Copyright (C) 2026 The OpenTV Contributors
- License: GNU General Public License version 3 or later (`GPL-3.0-or-later`)
- License text: [LICENSE](LICENSE)
- Changes and provenance: [docs/upstream-audit.md](docs/upstream-audit.md)

Original source notices are retained. Nuvix is an independently maintained modified version; neither the OpenTV contributors nor DIRECTV sponsor or endorse it.

## Android and JVM dependencies

Dependencies are resolved through Gradle, using the versions in [gradle/libs.versions.toml](gradle/libs.versions.toml) and [app/build.gradle.kts](app/build.gradle.kts). Their own license notices remain applicable; the Nuvix GPL does not replace the licenses of independently distributed libraries.

| Component | Project | License |
| --- | --- | --- |
| AndroidX, Compose, Room, WorkManager, Navigation, Media3 ExoPlayer | <https://github.com/androidx/androidx> | Apache-2.0 |
| Kotlin and kotlinx.coroutines/kotlinx.serialization | <https://github.com/JetBrains/kotlin> | Apache-2.0 |
| OkHttp/Okio | <https://github.com/square/okhttp> | Apache-2.0 |
| Coil | <https://github.com/coil-kt/coil> | Apache-2.0 |
| ZXing | <https://github.com/zxing/zxing> | Apache-2.0 |
| SMBJ | <https://github.com/hierynomus/smbj> | Apache-2.0 |
| OpenSSL (SQLCipher native cryptography backend) | <https://github.com/openssl/openssl> | Apache-2.0 |
| SQLCipher / SQLCipher Android | <https://github.com/sqlcipher/sqlcipher-android> | BSD-3-Clause |
| desugar_jdk_libs | <https://github.com/google/desugar_jdk_libs> | GPL-2.0 with Classpath exception |
| JUnit (test only) | <https://github.com/junit-team/junit4> | EPL-1.0 |
| Truth (test only) | <https://github.com/google/truth> | Apache-2.0 |
| Robolectric (test only) | <https://github.com/robolectric/robolectric> | MIT |
| Gradle wrapper and build tooling | <https://github.com/gradle/gradle> | Apache-2.0 |

The bundled `gradle/wrapper/gradle-wrapper.jar` contains its Apache-2.0 license at `META-INF/LICENSE`; an extracted readable copy is included at [licenses/Apache-2.0.txt](licenses/Apache-2.0.txt). This table lists declared direct dependency families and is not a claim to enumerate every resolved transitive dependency. Release maintainers must retain applicable notices for the actual resolved artifacts.

SQLCipher is Copyright (c) 2025, ZETETIC LLC; the SQLCipher Android wrapper is Copyright (c) 2008–2023, ZETETIC LLC. Their BSD notices are included in [licenses/SQLCipher.txt](licenses/SQLCipher.txt) and [licenses/SQLCipher-Android.txt](licenses/SQLCipher-Android.txt). The resolved SQLCipher Android 4.12.0 ARM64 binary identifies its cryptographic backend as OpenSSL 3.5.4; its Apache-2.0 text is preserved in [licenses/OpenSSL-3.5.4.txt](licenses/OpenSSL-3.5.4.txt). SQLCipher also incorporates SQLite (public domain). Preserve the applicable notices of any different resolved binary package when shipping it.

## User-supplied content and optional guide feeds

Nuvix includes no IPTV channels, provider accounts, playlists, or media. Logos, artwork, programme descriptions, and video from user-configured providers retain their owners' rights. The inherited optional XMLTV feed addresses do not bundle their remote data. Their providers' terms and licenses apply to downloaded data; the Freeview-EPG project identifies its feed as GPL-3.0. Nuvix contains no DIRECTV logos or proprietary DIRECTV assets.

## TV metadata services

TV show/season/episode/person metadata is provided by [TVmaze](https://www.tvmaze.com/)
through its [free public API](https://www.tvmaze.com/api), under
[CC BY-SA 4.0](https://creativecommons.org/licenses/by-sa/4.0/). Source links,
license links, and change notices are available in show details, actor profiles,
and About. Nuvix converts HTML summaries to plain text and combines selected
fields with provider metadata. Adaptations of TVmaze data remain under CC BY-SA
4.0; retain attribution, source/license links, and modification notices when
redistributing that data. No endorsement by TVmaze is implied. The GPL license
for application code does not supersede this data license.

[IMDbAPI.dev](https://imdbapi.dev/) is a best-effort secondary public metadata
service; IMDb ratings are explicitly labeled and are distinct from TVmaze ratings.
The existing [TMDb](https://www.themoviedb.org/) integration remains an optional
fallback using a user-supplied key. These services' data and provider content
retain their applicable rights; they are not relicensed as TVmaze material.
No live metadata, images, credentials, or API keys are bundled. Details and
redistribution guidance: [TV metadata](docs/tv-metadata.md).
