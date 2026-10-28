# Install Nuvix

Nuvix is an Android application. Use an Android TV, Google TV or compatible Fire TV device.
An iPad or iPhone can use the [browser manager](browser-manager.md); it cannot install an Android APK.

## Get a published APK

Open the [official GitHub releases](https://github.com/DisabledAbel/Nuvix/releases) and read the
notes for the version you choose. Release candidates are prereleases and are not automatically
offered as stable updates. Use a published release APK, not an unsigned build artifact.

The existing [APK shortcut](apk/) remains available for Downloader and redirects to an APK from
the latest published stable GitHub release. If no stable APK is available, it explains the failure;
it does not silently select a release candidate. You can always browse releases manually.

## Google TV and Android TV

1. Install **Downloader by AFTVnews** from your device's app store, if available.
2. In system settings, allow **Install unknown apps** for Downloader when prompted. Device menus
   vary; this may be under **Apps → Security & restrictions** or **Device preferences**.
3. Open the documentation site's `apk/` shortcut in Downloader, or transfer the APK downloaded
   from the official release to the device.
4. Choose **Install**, then open Nuvix. On devices that hide sideloaded apps from the home row,
   look under **Settings → Apps → See all apps**.

Grant installation permission only to the app you are using. Nuvix cannot bypass Android's
installation approval or a device management policy.

## Fire TV

1. Open **Settings → My Fire TV → About**. If Developer options are hidden, select your device's
   name seven times to reveal them.
2. Open **Developer options → Install unknown apps** and allow Downloader. Older Fire OS versions
   may label this **Apps from Unknown Sources**.
3. Open the APK shortcut in Downloader or transfer the official release APK, then choose **Install**.

Firmware menus and sideloading support differ. Playback, standby recording and launcher
behavior require testing on your own device.

## Existing installations and updates

Use **Settings → Nuvix updates** for supported stable updates. Updates must have a matching
package and signing identity and a higher numeric version code; Android still asks for approval.
See [update troubleshooting](update-troubleshooting.md).

**Read the release migration notes before replacing a test build.** The first stable signing reset
requires RC1 users to uninstall and reinstall; uninstalling deletes private app data. Older
package identities and debug builds are separate installations. See [release and migration details](releases.md).
Do not uninstall merely to bypass an unexplained signing error.

## After installation

Continue with [first setup and providers](getting-started.md#add-your-provider). Nuvix supplies no content.
