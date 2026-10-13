# Optional Android TV launcher

Nuvix remains a normal IPTV application until you explicitly enable **Settings → Launcher**. Its
HOME activity alias is disabled in the manifest. Nuvix does not alter the manufacturer's launcher,
require root, or use an accessibility service to intercept the Home button.

## Setup and compatibility

1. Open **Settings → Launcher settings** and read the device compatibility result.
2. Enable launcher mode. This only advertises Nuvix as a Home candidate.
3. Choose **Choose default Home app**. On Android 10 and newer Nuvix requests the supported Home
   role when available; otherwise it opens Android's Home settings when the device provides them.
4. Select Nuvix in the system dialog. Return to Launcher settings to verify the actual default.
5. Select a startup destination: Nuvix Home, Live TV, TV Guide, Last Watched Channel, or Apps.

Android TV and Google TV manufacturers can restrict or omit default-home selection. Fire TV
typically imposes additional restrictions, so launcher replacement is not guaranteed. If there is
no supported selector, use Nuvix in normal application mode. No device-specific Home interception
or system-launcher removal is attempted. The compatibility screen reports available Android APIs,
not a promise that manufacturer firmware will honor the requested change.

The normal LAUNCHER and LEANBACK_LAUNCHER entry points remain present after launcher mode is
disabled. Home intents and app launches use the same activity, so playback state can be preserved
when returning to Nuvix. Startup preferences are local and take effect on the next start/Home intent.

## Returning to the manufacturer's launcher

Open **Device settings → Apps → Default apps → Home app** and choose the original launcher; menu
names vary. Then disable launcher mode inside Nuvix. If the Home selector is unavailable, disable
Nuvix launcher mode and press Home; Android resolves an available Home application. Nuvix never
disables or uninstalls other Home applications. The Apps and Launcher settings screens both expose
an Android Device settings shortcut.

Installing an update preserves the PackageManager component setting and local preferences when
Android updates the same package with the same certificate. Recheck the default Home status after
installation; firmware may reset Home selection. A canceled installation changes neither the alias
setting nor your startup destination. Uninstalling/reinstalling Nuvix resets launcher preferences.

## Installed applications

The Apps page queries Android PackageManager for the union of MAIN/LEANBACK_LAUNCHER and
MAIN/LAUNCHER activities. TV entry points take precedence for applications that provide both.
Only enabled, exported activities are offered; Nuvix itself is excluded. Package visibility uses
narrow intent declarations, without `QUERY_ALL_PACKAGES` or an installed-package inventory upload.

Favorites form an ordered horizontal row; the All Apps grid is alphabetic. Hold OK, long press a
tile, or press Menu to open the app menu. Pin/unpin, move a favorite left/right, launch, or open
Android's application-information page there. Package broadcasts and resuming the page refresh
the list. Removed applications lose stale favorite entries after successful discovery; launch
attempts recheck the activity and show an error when an app disappears, is disabled, or denies
launching. No streaming services are hardcoded or presented as installed.

The dedicated `nuvix_launcher` private preferences contain startup destination and favorite package
names only. They do not contain IPTV provider credentials. Android application backups are disabled
to keep provider passwords, tokens, and credential-bearing playlist URLs out of system backups.
Legacy HTTP IPTV support remains enabled for provider compatibility; prefer HTTPS because plain
HTTP does not protect credentials or streams in transit.

## Validation and remaining device checks

Unit/Robolectric tests cover opt-in HOME alias reversal, Home-intent detection, startup persistence,
favorite order/corruption handling, package discovery, and unavailable-app launch handling. Before
release, test real Android TV, Google TV, and Fire TV hardware: remote navigation, the manufacturer's
Home selector, recovery to device settings, and launcher/default state across a signed update.
Robolectric cannot establish firmware-specific launcher replacement support.
