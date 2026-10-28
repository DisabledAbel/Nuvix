# Manage Nuvix from a browser

The public service is live at [nuvix-manager.blazersboy1.workers.dev](https://nuvix-manager.blazersboy1.workers.dev/).
For pairing instructions, start with the [browser manager guide](browser-manager.md).

The Cloudflare manager is an optional public service for everyone running a configured Nuvix
build. Each TV creates an isolated session; there is no shared TV list, Nuvix account, subscription,
QR code, port forwarding, router change or always-on computer. This is management, not streaming.

## User flow

1. On the TV, open **Settings → Manage on phone or laptop**.
2. Read the service address and disclosure, then select **Start browser session**.
3. Open that HTTPS website in Safari, Chrome or another modern browser on a phone, iPad or laptop.
4. Enter the 12-character code shown on the TV. Hyphens and spaces are optional.
5. Browse categories; hide, favorite, rename or reorder channels; add/test providers; or configure/test
   the NAS recording destination. Existing repository operations apply changes directly to this TV.
6. Select **Disconnect** in the browser, or **Done** / Back on the TV, to end the session.

The devices can be on different internet connections. Keep the TV online with this screen open.
The one-use code shows a live countdown below it and expires five minutes after session creation.
An expired code disappears; select **Start browser session** to explicitly request a new one.
Pairing hides the code and countdown immediately. A paired session expires at most thirty minutes after
creation. Sleep, app/process shutdown, network loss or leaving the screen can interrupt access.
The browser token stays only in memory. Reloading/closing the tab forgets it; reopen the manager on
the TV for a new code. There is no unattended/background remote administration in this version.

## Deploy once for all users, entirely through GitHub

The public service above is already deployed. The following instructions are for operating the
service or hosting another instance. APKs without a configured service URL accept a trusted
HTTPS origin on the TV manager screen.

1. In the Cloudflare dashboard, find your account ID and create an account-scoped API token with
   **Workers Scripts: Edit** and **Account Settings: Read**, scoped to that account. Workers deployment
   manages the Durable Object namespace and migration. Do not give the token to app users.
2. Add GitHub repository/environment secrets `CLOUDFLARE_API_TOKEN` and `CLOUDFLARE_ACCOUNT_ID`.
   The deployment job uses the `cloudflare-manager` environment. Use its protection rules if desired.
3. Merge the reviewed code, then run **Actions → Cloudflare Manager → Run workflow** on `main`
   with **deploy** checked. Tests and a dry run run first. The job deploys `nuvix-manager` plus its
   static assets, SQLite-backed Durable Object namespace and rate-limit bindings.
4. Copy the HTTPS `workers.dev` address from deployment output. An optional custom domain can be
   configured in Cloudflare; no domain purchase is required for the supplied `workers.dev` route.
5. Add a GitHub repository **variable** `NUVIX_MANAGER_URL` containing only that HTTPS origin,
   for example `https://nuvix-manager.YOUR-SUBDOMAIN.workers.dev` (substitute the actual address).
   Build and Release workflows pass this public, non-secret address into `BuildConfig.MANAGER_URL`.
6. Build a new APK, install it on a test TV and complete the acceptance checks below before publishing
   an update. Existing installed APKs need the Android update to use the new connection protocol.

No Cloudflare API/admin token belongs in the APK, web assets, Gradle properties, logs or repository.

### Cloudflare Workers Builds (optional)

If the repository is also connected through **Workers & Pages → nuvix-manager → Settings → Build**,
set **Root directory** to `cloudflare/manager` and use Node.js 24. Set the **Build command** to
`npm ci && python3 generate-page.py --check && npm test && npm run check` and the
**Non-production branch deploy command** to `npx wrangler preview`. These dashboard settings are
separate from the GitHub Actions workflow; changing this repository does not update them.
Use `npm run deploy` as the production deploy command only if you intend Workers Builds to deploy
production instead of the manual GitHub workflow above.

Run commands from that root so npm installs the locked Wrangler version and Wrangler reads
`wrangler.toml`. Running `npx wrangler preview` from the repository root can download an unpinned
Wrangler and miss this configuration. The `previews` configuration explicitly supplies `ROOMS`
and both rate limiters; an empty block would leave required runtime bindings unavailable.
Each Preview gets its own Durable Object namespace and storage, and its rate-limit namespace IDs
differ from production. Assets, compatibility settings and migrations remain at the top level.
See [Preview configuration](https://developers.cloudflare.com/workers/previews/configuration/)
and [resource isolation](https://developers.cloudflare.com/workers/previews/resources/).

Retry the failed Cloudflare build after saving the dashboard settings and making the updated
configuration available on the branch. Local tests and packaging checks do not verify a hosted
Preview deployment, account permissions or actual device pairing.

For other build systems, set `NUVIX_MANAGER_URL` in the build environment or pass Gradle property
`nuvixManagerUrl`. The address must be an HTTPS origin without userinfo, path, query or fragment.

Cloudflare currently offers SQLite-backed Durable Objects on its Workers Free plan, with usage
limits. Public usage is not guaranteed to remain within free quotas. Monitor the dashboard; quota
exhaustion or abuse can make the service unavailable. Account-wide billing and access controls
remain the operator's responsibility. Local development is optional; users do not need a PC.

## Countdown rollout and timing

Deploy the updated Worker **before distributing the updated APK** through the existing Cloudflare
Manager workflow. The creation response adds `pairUntil` (the stored Unix-millisecond pairing deadline)
and `serverNow` (the server time when producing the response), retaining `ok` and HTTP status codes.
Older APKs ignore the additional fields. The updated APK requires both timing fields and fails the
session creation safely if an older Worker omits them; it does not invent a new five-minute window.
No storage migration or dependency update is needed.

Android converts the server's remaining budget once to `SystemClock.elapsedRealtime`, conservatively
charging the entire creation round trip to that budget. The local deadline may therefore be up to
one request round trip early; it never gains time from network delays or device clock differences.
WebSocket connection time consumes the same budget. Timer wakeups recalculate elapsed time, including
time spent asleep, instead of subtracting one second per wakeup. State collection and recomposition
do not restart it. Local expiry hides the code and stops refreshes without revoking the session:
a delayed Worker pairing notification can still show the paired state. Explicit stop, disconnect
and leaving the screen continue to revoke access. The Worker remains authoritative for pairing
and the thirty-minute session limit.

## Security, privacy and limits

- The TV makes an outbound authenticated WSS connection. The Worker is not a generic HTTP proxy.
  Both ends enforce an explicit management command allowlist; streams and arbitrary URLs cannot
  be fetched by the Worker. Provider/NAS connection tests run on the TV, using existing code.
- Codes contain 60 random bits and are never derived from a device ID, IP, MAC or user name.
  TV and browser credentials are independent 256-bit random capabilities. Knowledge of a consumed
  code cannot claim another browser or authenticate management commands.
- Pairing atomically claims one browser; an identical token may retry a lost pairing response.
  No accounts are required. Whoever enters an unused code can manage that session, so only show
  the code to trusted people. The TV switches to a paired indication after a browser claims it.
- Cloudflare stores only credential hashes, pairing/session deadlines and a short-lived revocation
  tombstone in the Durable Object. Expiry alarms close sockets and delete session records. Commands
  and responses are relayed in memory without application logging or durable catalog/credential storage.
- HTTPS/WSS encrypts transit; **this is not end-to-end encryption**. The operator and Cloudflare can
  inspect traffic. Channel data, provider/NAS details submitted in forms and responses cross the
  service. No provider stream URL is included in the channel DTO. Existing NAS passwords are not
  returned to the browser. Channel logos can load directly from provider-specified HTTPS image hosts.
- Browser tokens are never placed in URLs, cookies, localStorage or sessionStorage. Static assets
  use a restrictive CSP, no third-party script, no camera permissions and no cache/referrer storage.
  API responses disable caching. Provider names are rendered using the existing DOM text operations.
- Anonymous create/pair/device requests and browser commands have coarse, per-IP/Cloudflare-location
  rate limits. These are abuse reduction, not a global spend cap; shared IPs may be throttled. A room
  permits at most four pending commands, bounds requests/replies and times out after thirty seconds.
- Commands are not queued for an offline TV, automatically retried, or replayed after reconnection.
  A lost reply may follow a successful change. Refresh/review the result before manually repeating
  a mutation, especially adding a provider. Slow provider imports/tests can exceed the relay deadline.
- Large individual categories can exceed the remote reply limit and return an error. Full catalog
  pagination, persistent pairing, multi-TV accounts and screen-off background access are out of scope.

## Validation and acceptance

Automated tests cover one-use/concurrent pairing, role and session isolation, invalid/expired codes,
revocation, offline/disconnect errors, exact request/reply correlation, command allowlisting, JSON
size bounds and pre-allocation origin/rate rejection. Android unit tests cover endpoint validation,
unambiguous code/token generation and the independent command allowlist. Virtual-time Android tests
also cover countdown rounding, connection delays, collection/restart/cancellation, stale callbacks,
sleep and pairing before expiry; Worker tests verify timing metadata and both expiry boundaries. Existing Android CI
compiles/tests/lints the app; the Cloudflare workflow verifies generated assets and Worker packaging.

Before public rollout, use an actual Cloudflare deployment, TV and iPad browser:

- Pair with only the displayed code on separate networks; verify channels, rename/hide/favorite/order,
  provider test/add and NAS test/save against authorized test resources.
- Confirm a second browser cannot reuse the code, and a second TV is isolated.
- Verify Back/Done, browser Disconnect, five-minute unclaimed expiry, thirty-minute hard expiry,
  network interruption, app shutdown and TV sleep all deny further management.
- Confirm no secrets appear in browser URLs, browser persistent storage, job output or application logs.
- Check small-screen touch controls, TV D-pad focus, status/error messages, Safari session expiry and
  very large categories. An automated mock test is not a substitute for these device checks.

The public HTML/CSS/JS are derived from the existing GPL manager rather than a separate feature fork.
After changing `ManagerPage.kt` or `browser-transport.js`, regenerate them with
`python3 cloudflare/manager/generate-page.py`. CI checks that committed assets match the sources.

References: [Cloudflare WebSockets](https://developers.cloudflare.com/durable-objects/best-practices/websockets/),
[static assets](https://developers.cloudflare.com/workers/static-assets/binding/),
[rate limits](https://developers.cloudflare.com/workers/runtime-apis/bindings/rate-limit/) and
[plan limits/pricing](https://developers.cloudflare.com/durable-objects/platform/pricing/).
