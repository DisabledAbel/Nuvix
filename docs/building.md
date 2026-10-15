# Building Nuvix

Nuvix is an independent GPL-3.0-or-later modification of [OpenTV](https://github.com/opentvproject/opentv). It retains OpenTV's Kotlin, Jetpack Compose, Room and Media3 architecture. Source packages remain `app.opentv` to preserve a reviewable upstream history; generated resources, the namespace and release application ID are `com.disabledabel.nuvix`.

## Requirements

- JDK 17 (CI uses Temurin 17; Gradle also runs with JDK 21).
- Android SDK platform 35, build-tools 35.0.0 and platform-tools.
- Android Studio with Kotlin support, or the command-line SDK tools.

The checked-in Gradle 8.11.1 wrapper includes an official distribution SHA-256 checksum. Android Gradle Plugin 8.7.3, Kotlin 2.0.21 and matching KSP versions are defined in `gradle/libs.versions.toml`. Nuvix targets Android 15 and preserves upstream support for Android 6/API 23 and newer. Android TV, Google TV and Fire TV capabilities vary by device.

Set `ANDROID_HOME` to your SDK directory, or put `sdk.dir=/absolute/path/to/android-sdk` in the ignored `local.properties`. Accept the Android SDK license terms and install the required packages:

```sh
sdkmanager --licenses
sdkmanager 'platforms;android-35' 'build-tools;35.0.0' 'platform-tools'
./gradlew help
./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
python3 -m unittest discover -s scripts/tests -v
```

The debug APK is `app/build/outputs/apk/debug/app-debug.apk`. Its ID is `com.disabledabel.nuvix.debug`, allowing it to coexist with the stable app. Debug builds cannot install updates intended for the stable ID. Install with `adb install -r app/build/outputs/apk/debug/app-debug.apk`.

A local `./gradlew :app:assembleRelease` without signing credentials produces an **unsigned** APK for inspection. Release builds never fall back to the Android debug signing key. Follow [release signing](signing.md) before distributing an APK.

If your environment requires an HTTP proxy, configure the standard Gradle `systemProp.http.proxyHost`, `systemProp.http.proxyPort`, `systemProp.https.proxyHost` and `systemProp.https.proxyPort` in your personal Gradle properties or JVM options. Keep environment-specific proxy values and credentials out of the repository. Retain TLS verification and trust the environment's installed CA.

## CI

`.github/workflows/Ci.yml` validates Gradle configuration, compiles Kotlin, runs Android unit tests and the release-verification Python tests, runs Android lint, assembles a debug APK and uploads reports on pushes, pull requests and manual dispatches. It requires no signing secrets. Release CI verifies a signed APK before publishing it; see [signing.md](signing.md).

The Android tests use Robolectric; the first run downloads its Android test runtime. All providers and playback sources must be supplied by users with legitimate access. Hardware playback, remote navigation, home selection, picture-in-picture and installation approval require testing on real Android TV, Google TV and Fire TV devices.
