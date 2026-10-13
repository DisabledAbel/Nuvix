# Contributing to Nuvix

Nuvix is an independent GPL-3.0-or-later derivative of OpenTV. Preserve original notices, document changes, and include corresponding source when distributing binaries. Do not import proprietary assets, decompiled closed-source code, credential dumps, or playlists containing unauthorized media. Compatible openly licensed contributions are welcome with attribution.

Use a feature branch and open a pull request against `main`. Keep changes scoped, describe final behavior and validation, and retain working provider/playback/sync paths. Run `./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug` before submitting. See [building](docs/building.md).

Bug reports should include Nuvix version, TV/device/Android version, provider type, steps, expected behavior, and sanitized diagnostics. Never post provider URL query tokens, usernames, passwords, MAC credentials, cloud tokens, or signing material. Test fixtures must be synthetic and must not ship as production channels or accounts.

Changes to launcher behavior require tests on supported firmware and an accessible route to system settings. Changes to cloud sync require owner-isolation and credential-exclusion tests. Update changes require integrity, package, signing-certificate, numeric-version, cancellation, and install-approval checks.
