# Nuvix maintainer setup

The independent repository is `DisabledAbel/Nuvix`. No GitHub fork is necessary. Build locally using [docs/building.md](docs/building.md). Review the feature branch through a pull request before merging.

For production release publication, configure the signing secrets and public signer fingerprint described in [docs/signing.md](docs/signing.md). Run CI, then create a version tag whose name matches `versionName`; the release workflow verifies the APK and publishes matching update metadata and corresponding source. Do not tag unfinished changes or use a debug certificate as the production signer.

Supabase is optional and not required to build or watch television. Follow [docs/cloud-sync.md](docs/cloud-sync.md) to deploy the migration in your own project, configure email-code authentication, and opt in from the app. No private cloud/signing secrets are present in this repository.

Default-home availability depends on the device. Read [docs/launcher-compatibility.md](docs/launcher-compatibility.md) before adopting launcher mode as a daily home screen.
