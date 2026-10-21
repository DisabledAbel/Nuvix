# Building Nuvix

Nuvix is an independent GPL-3.0-or-later modification of [OpenTV](https://github.com/opentvproject/opentv). It retains OpenTV's Kotlin, Jetpack Compose, Room and Media3 architecture. Source packages remain `app.opentv` to preserve a reviewable upstream history; generated resources, the namespace and release application ID are `com.disabledabel.nuvix`.

## Requirements

- JDK 17 (CI uses Temurin 17; Gradle also runs with JDK 21).
- Android SDK platform 35, build-tools 36.0.0 and platform-tools.
- Android Studio with Kotlin support, or the command-line SDK tools.

The checked-in Gradle 9.6.0 wrapper includes an official distribution SHA-256 checksum. Android Gradle Plugin 9.4.1, built-in Kotlin 2.2.10 and KSP 2.3.1 are defined in `gradle/libs.versions.toml`. Nuvix targets Android 15 and preserves upstream support for Android 6/API 23 and newer. Android TV, Google TV and Fire TV capabilities vary by device.

AGP 9 supplies Kotlin support directly; do not reapply `org.jetbrains.kotlin.android`
or restore the removed `android.kotlinOptions` block. Kotlin's JVM target follows
`android.compileOptions.targetCompatibility` (Java 17). Compose and serialization
compiler plugins use the same Kotlin version. KSP 2 and Room 2.7.2 support the new
compiler while preserving compile/target SDK 35 and minimum API 23. Core 1.13.1,
TV Material 1.0.0, Media3 1.5.0, Navigation 2.8.4 and WorkManager 2.9.1 remain pinned.
Core 1.19.1 requires compile SDK 37; validate that SDK migration separately rather
than bypassing AAR checks or raising the device minimum silently.
SQLCipher stays at 4.12.0 because 4.19.1 requires compile SDK 36; the encrypted database
schema and cipher configuration are unchanged.
Robolectric stays at 4.13: the unchanged live-account admission regression passes there
but fails to publish its controller under 4.17 with this coroutine/Room test matrix.
Keep the original assertions; validate a test-runtime upgrade separately.

Compose BOM 2024.11.00 and OkHttp 4.12.0 are the validated dependencies for this SDK 35 toolchain. MockWebServer shares OkHttp's catalog version. Dependabot defers major upgrades of Compose, OkHttp and Gradle until a coordinated toolchain migration; minor and patch updates remain enabled.

Set `ANDROID_HOME` to your SDK directory, or put `sdk.dir=/absolute/path/to/android-sdk` in the ignored `local.properties`. Accept the Android SDK license terms and install the required packages:

```sh
sdkmanager --licenses
sdkmanager 'platforms;android-35' 'build-tools;36.0.0' 'platform-tools'
./gradlew help
./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
python3 -m unittest discover -s scripts/tests -v
```

The debug APK is `app/build/outputs/apk/debug/app-debug.apk`. Its ID is `com.disabledabel.nuvix.debug`, allowing it to coexist with the stable app. Debug builds cannot install updates intended for the stable ID. Install with `adb install -r app/build/outputs/apk/debug/app-debug.apk`.

A local `./gradlew :app:assembleRelease` without signing credentials produces an **unsigned** APK for inspection. Release builds never fall back to the Android debug signing key. Follow [release signing](signing.md) before distributing an APK.

If your environment requires an HTTP proxy, configure the standard Gradle `systemProp.http.proxyHost`, `systemProp.http.proxyPort`, `systemProp.https.proxyHost` and `systemProp.https.proxyPort` in your personal Gradle properties or JVM options. Keep environment-specific proxy values and credentials out of the repository. Retain TLS verification and trust the environment's installed CA.

## CI

`.github/workflows/build.yml` validates Gradle configuration, compiles Kotlin, runs Android unit tests and the release-verification Python tests, runs Android lint, assembles a debug APK and uploads reports on pushes, pull requests and manual dispatches. It requires no signing secrets. Release CI verifies a signed APK before publishing it; see [signing.md](signing.md).

The Android tests use Robolectric; the first run downloads its Android test runtime. All providers and playback sources must be supplied by users with legitimate access. Hardware playback, remote navigation, home selection, picture-in-picture and installation approval require testing on real Android TV, Google TV and Fire TV devices.

Daily signed nightly prereleases run at 21:00 PST and use Gemini to generate their
descriptions. See [nightly builds](nightly-builds.md) for repository secrets,
scheduling, installation and manual runs.
