# Nuvix maintainer setup

The independent repository is `DisabledAbel/Nuvix`. No GitHub fork is necessary. Build locally using [docs/building.md](docs/building.md). Review the feature branch through a pull request before merging.

For production release publication, follow [the manual release and approval guide](docs/releases.md). Configure the protected `production` environment and existing signing identity, then manually dispatch Release from `main` after CI passes. A different authorized maintainer must approve signing and publishing. Tag pushes and merges do not initiate releases; do not precreate version tags or use a debug signing certificate. See [signing continuity](docs/signing.md).

Supabase is optional and not required to build or watch television. Follow [docs/cloud-sync.md](docs/cloud-sync.md) to deploy the migration in your own project, configure email-code authentication, and opt in from the app. No private cloud/signing secrets are present in this repository.

Default-home availability depends on the device. Read [docs/launcher-compatibility.md](docs/launcher-compatibility.md) before adopting launcher mode as a daily home screen.
