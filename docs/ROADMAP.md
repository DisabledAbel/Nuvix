# Nuvix development roadmap

The initial source import preserves OpenTV's functional native systems. The Nuvix foundation adds branding/navigation, home/provider shelves, installed apps and optional launcher, opt-in cloud favorites/progress, configurable buffering, cache protection, and verified release/update infrastructure. See implementation-report.md for completed checks.

The next phase is device acceptance testing: Android TV, Google TV and Fire TV D-pad/Back/long-press flows, cold starts, launchers that reject home replacement, authorized stream playback, EPG refresh failure, PiP, recording lifecycle, encrypted persistence and upgrades. Test installation cancellation and failed signatures on hardware without publishing invalid APKs.

Then expand cloud scope with explicitly typed custom groups/settings/app favorites/launcher preferences, review removal/conflict semantics, and add more device-to-device tests. Add QR/device authorization only with an authenticated, reviewed browser pairing architecture; no fake device code flow.

Further playback work includes uninterrupted shared live-preview state across navigation, update transfer range resume, broader provider sports-event metadata, low-memory performance profiling, and a pluggable playback-engine contract. Broaden automated TV UI tests alongside each feature.
