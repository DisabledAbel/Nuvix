# Manage your TV from a browser

Open the live manager at **[nuvix-manager.blazersboy1.workers.dev](https://nuvix-manager.blazersboy1.workers.dev/)**
on your phone, iPad or laptop. It manages Nuvix on your TV; it does not stream television to the browser.

## Pair with your TV

1. On the TV, open **Settings → Manage on phone or laptop**.
2. If the APK asks for a service address, enter `https://nuvix-manager.blazersboy1.workers.dev`.
   Use the HTTPS origin only, without another path. If your build displays a different configured
   service, only use a service you trust.
3. Read the disclosure and choose **Start browser session**. The TV shows a 12-character code.
4. Open the [web manager](https://nuvix-manager.blazersboy1.workers.dev/) and enter that code.
   Spaces and hyphens are optional. No QR code or Nuvix account is needed.
5. Keep the TV online with its manager screen open. Select **Disconnect** in the browser, or
   **Done** / Back on the TV, when finished.

The TV and browser can use different internet connections. The code expires after five minutes
and can claim only one browser. A paired session ends at most thirty minutes after creation.
Only give a current code to someone you trust to manage that TV.

## What you can manage

Browse categories and manage channels, add or test providers, and configure or test a NAS
recording destination. The controls available depend on the installed APK and service versions.
If the manager shows an upgrade message, update the TV app and start a new session. Publishing
a website does not update an already-installed APK.

Channel changes are applied to that TV using its existing catalog operations. Refresh actual TV
state after a timeout or disconnected reply before repeating a change: the TV may have completed
it even if the browser did not receive the result.

## Sessions and common problems

| What you see | What to do |
|---|---|
| No code or expired code | Start a new browser session on the TV; codes last five minutes |
| TV offline or disconnected | Check the TV's connection and keep its manager screen open |
| Expired or revoked session | Start a fresh session and enter the new code |
| Upgrade or compatibility message | Use compatible APK and service versions, then reload the browser |
| A change timed out | Refresh the TV's actual state and review the result before trying again |
| Reloaded or closed browser tab | Reopen the manager on the TV for a new session; the browser forgets its token |

## Privacy

Management details and provider/NAS information entered into forms pass through Cloudflare over
HTTPS and are saved on the TV. This is **not end-to-end encryption**: Cloudflare and the service
operator can inspect relayed traffic. Existing NAS passwords are not returned to the browser.
The relay stores credential hashes and session deadlines rather than catalogs or submitted
provider/NAS credentials. Browser tokens remain in memory, not browser persistent storage.

This documentation site does not ask for your TV code or provider credentials. Enter the code
only on the manager website. Read the [privacy guide](PRIVACY.md) before using optional services.

## Operator and implementation reference

For deployment, protocol details, compatibility and remaining device acceptance checks, see
[Cloudflare manager reference](cloudflare-manager.md). The public service is already live; those
instructions are for maintainers and people running another trusted instance.
