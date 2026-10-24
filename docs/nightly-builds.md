# Nightly publishing is retired

There is no active nightly workflow, scheduled APK publication or nightly update channel.
Do not restore a nightly publisher or configure Gemini/signing secrets for nightly builds.

Legacy Gradle variants and script tests remain for compatibility; they do not enable releases.
Old nightly installations are separate packages and do not update in place to production
`com.nuvix.player`. No automatic uninstall or private-data migration is provided.

For the planned `v1.0.0-rc.1` community beta, follow the [beta guide](BETA-TESTING.md) and
[release readiness tracker](V1-RELEASE-CHECKLIST.md). Candidates are prepared for the existing
manual Release workflow with independent approval and verified production signing, not
automatic publication. Signing recovery remains blocked and no candidate APK is available.
The [release runbook](releases.md) documents the prerequisites; do not dispatch a release
as part of beta-program preparation. The in-app updater continues to discover stable releases only.
