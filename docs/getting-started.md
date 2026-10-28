# Get started with Nuvix

Nuvix is a free, open-source player for Android TV, Google TV and compatible Fire TV devices.
You supply an authorized provider. No channels, playlists, subscriptions or provider accounts
are included, and a Nuvix account is not required for ordinary playback.

## Install the app

Follow the [installation guide](installation.md) for your device. Use a published APK from
[the official releases](https://github.com/DisabledAbel/Nuvix/releases), rather than an unsigned
CI artifact. Features vary with the installed app version, provider and device.

## Add your provider

Open **Settings → Providers** or **Add provider** on Home. Choose the type supplied by your provider:

| Provider type | What you need |
|---|---|
| M3U / M3U8 | Your authorized playlist URL; an XMLTV guide URL if supplied separately |
| Xtream Codes | The server address, username and password issued to you |
| Stalker / Ministra | An authorized portal and assigned MAC identity; advanced fields only if supplied by your provider |

Prefer HTTPS where the provider supports it. Keep login details private. For Stalker identity
options, use the [authorized portal guide](stalker-configuration.md); do not substitute an
unassigned identity. Import time depends on playlist size and provider availability.

## Set up the guide

Open **Guide** after the live catalog loads. If listings are missing, check the provider's
XMLTV settings and the [EPG setup guide](automatic-epg.md). A guide feed contains listings;
it does not add playable channels. Use **Guide settings** for layout, filters and sorting.

## Manage from a larger screen

The [web manager](browser-manager.md) lets you use your phone, iPad or laptop for management.
Start a browser session on the TV, open the manager website and enter the TV code. Keep the
TV's manager screen open while using it. No QR code or Nuvix account is needed.

## Choose what to explore next

- [Persistent playback](live-playback.md): browse without leaving your current channel.
- [Sports Hub](sports-hub.md): find games and compare available provider choices.
- [Multi-View and DVR](smart-multiview-dvr.md): manage streams, recordings and connection allowances.
- [Recording storage](dvr-storage-manager.md): review destinations, space and recording files.
- [Optional cloud sync](cloud-sync.md): choose supported categories to sync between your own devices.

Extra streams depend on provider allowances and hardware decoders. Recording in standby,
launcher integration and playback behavior vary by device; there is no universal hardware guarantee.

## If something goes wrong

Start with [playback diagnostics](playback-diagnostics.md) or [update troubleshooting](update-troubleshooting.md).
When reporting a problem, include your app version, device and sanitized steps. Never include
provider passwords, stream URLs, cloud tokens, NAS credentials or a current TV pairing code.
