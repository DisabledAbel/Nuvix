# Manage Nuvix from a browser

The Cloudflare manager is an optional public service for everyone running a configured Nuvix
build. Each TV creates an isolated session; there is no shared TV list, Nuvix account, subscription,
QR code, port forwarding, router change or always-on computer. This is management, not streaming.

## User flow

1. On the TV, open **Settings → Manage on phone or laptop**.
2. Read the service address and disclosure, then select **Start browser session**.
3. Open that HTTPS website in Safari, Chrome or another modern browser on a phone, iPad or laptop.
4. Enter the 12-character code shown on the TV. Hyphens and spaces are optional.
5. Browse categories or search a selected provider; select channels for reviewed bulk edits;
   hide, favorite, rename or reorder individual channels; add/test providers; or configure/test
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

The service is **not deployed automatically by this PR**. Until the maintainer deploys and configures
its URL, builds explain that no public service is configured and accept a trusted self-hosted URL.

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
set **Root directory** to `cloudflare/manager` and the build variable `NODE_VERSION` to `24`.
Set the **Build command** to
`npm ci && python3 generate-page.py --check && npm test && npm run check` and the
**Non-production branch deploy command** to `npm run preview`. These dashboard settings are
separate from the GitHub Actions workflow; changing this repository does not update them.
Use `npm run deploy` as the production deploy command only if you intend Workers Builds to deploy
production instead of the manual GitHub workflow above.

Run commands from that root so npm installs the locked Wrangler version and Wrangler reads
`wrangler.toml`. Running `npx wrangler preview` from the repository root can download an unpinned
Wrangler and miss this configuration. `npm run preview` uses the Wrangler installed by `npm ci`
and explicitly loads `wrangler.toml`; it does not ask npx to download a different version.
The `previews` configuration explicitly supplies `ROOMS` and both rate limiters; an empty block would leave required runtime bindings unavailable.
Each Preview gets its own Durable Object namespace and storage, and its rate-limit namespace IDs
differ from production. Assets, compatibility settings and migrations remain at the top level.
See [Preview configuration](https://developers.cloudflare.com/workers/previews/configuration/)
and [resource isolation](https://developers.cloudflare.com/workers/previews/resources/).

If the log goes straight from cloning to `Executing user deploy command: npx wrangler preview`,
then downloads a Wrangler version absent from this lockfile and reports a missing `previews`
block, check the dashboard root and build command first. This repository already has that block
in `cloudflare/manager/wrangler.toml`; adding an empty block at the repository root would omit the
required isolated session and rate-limit bindings. The expected log runs `npm ci`, tests and
packaging checks before `npm run preview`, using the locked Wrangler version.

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
  returned to the browser. Channel responses omit logos and all playback URLs; browsing makes no provider image requests.
- Browser tokens are never placed in URLs, cookies, localStorage or sessionStorage. Static assets
  use a restrictive CSP, no third-party script, no camera permissions and no cache/referrer storage.
  API responses disable caching. Provider names are rendered using the existing DOM text operations.
- Anonymous create/pair/device requests and browser commands have coarse, per-IP/Cloudflare-location
  rate limits. These are abuse reduction, not a global spend cap; shared IPs may be throttled. A room
  permits at most four pending commands, bounds requests/replies and times out after thirty seconds.
- Commands are not queued for an offline TV, automatically retried, or replayed after reconnection.
  A lost reply may follow a successful change. Refresh/review the result before manually repeating
  a mutation, especially adding a provider. Slow provider imports/tests can exceed the relay deadline.
- Channel pages contain at most 100 rows (the browser uses 50). Channel names are capped at 512
  characters in responses; search text at 120 and page offsets at 1,000,000. Channel responses omit stream commands, URLs,
  provider credentials and logos (logo URLs can contain credentials). Persistent pairing, multi-TV
  accounts and screen-off background access remain out of scope.

## Large playlists, search and explicit selection (manager protocol 2)

Choose a provider and **Browse or search provider channels**, or open a category. Search checks
original provider names, cleaned display names and custom renames, including hidden channels.
**Only this category** restricts search when a category is open. Matching uses SQLite's literal,
case-insensitive substring comparison (SQLite `lower` folds ASCII; full Unicode case folding is
not provided). `%` and `_` are ordinary search text. Browse order is `sortIndex`, `displayName`,
then local channel ID, so duplicate names have a stable tie-breaker. Responses never contain an
entire category beyond the requested page.

Use the labeled checkboxes, **Select All on This Page** or **Clear Selection**. The count explicitly
separates current-page selections from other pages/filters. Selection is limited to 500 explicit
IDs in one provider; it survives page/search/filter changes while that provider and catalog revision
remain valid. No action selects a whole provider or all search results. The selection review lists
last known favorite/visibility state, including off-page selections. A different provider clears it.

Choose **Favorite**, **Unfavorite**, **Hide** or **Unhide**, then **Review action**. The review shows
the requested action and exact selected count before **Apply reviewed action** sends anything.
Changing selection/navigation cancels the review. Batches contain at most 100 unique IDs and run
sequentially. **Stop after current batch** leaves remaining IDs selected and reports them as
not attempted; an accepted TV batch may finish. Searches are debounced and coalesced with at most one outstanding read. Navigating,
editing the search or ending the session cancels the old result locally. It does not replay or
cancel an already accepted TV transaction. All operations stay off the Android/UI main thread.

Each page has an opaque revision. Database triggers advance it for channel insertion/deletion,
refresh stamps, names, order and provider configuration changes, including changes outside the
manager. The additive v17→v18 migration adds the revision and triggers, without replacing catalog
rows. A fresh provider incarnation receives a fresh revision even if its local ID is reused.
Page reads and revision checks are transactional. Before each mutation batch, the TV checks the
provider is still enabled and its revision still matches, in the same transaction as the edits.
A refresh, removal or configuration change before a batch rejects that batch with HTTP 409;
already committed earlier batches remain applied, and subsequent batches stop. Favorite/hidden
edits intentionally leave the browse revision valid because they do not change membership/order.

The outcome reports successful, failed, skipped (already explicitly at the requested value, or absent from the
provider), unknown and not-attempted counts. Only confirmed success/skipped rows leave selection;
failed, uncertain and unattempted rows remain. Each TV batch is atomic: a database write error
rolls back that batch, and results are classified from actual state after rollback. Previously
committed batches remain applied; failure in one batch does not claim success for rolled-back rows. An HTTP rejection is a known failure; a timeout,
disconnect or malformed/lost reply leaves the sent batch unknown. **No mutation is automatically
retried**, including on reconnect. Further edits are blocked until **Refresh TV state** reads all
selected IDs in bounded batches and reloads the page. Review actual state before explicitly
confirming another action; refresh itself only reads. If the revision changed, stale selection is
cleared and the user must select again. If the session expired, start a new code-only session.
A batch accepted before expiry/disconnect may finish; no later batch is sent automatically.

Single-channel rename, favorite/visibility and move actions remain available and use the same
revision check. Move operates within the original category, including across page boundaries,
preserving other rows' relative order. It normalizes category ranks transactionally, using a
bounded ID/rank read of at most 100,001 entries; categories above 100,000 cannot be reordered.
A very large category move can exceed the relay deadline: refresh and reconcile rather than
repeating it. Bulk edits record explicit intent through the existing flag setters even when the visible flag
already matches but has no local sync-intent bit. They preserve names, order, guide
mappings, playback fields and existing `syncTouched`/cloud-sync behavior.

### Compatibility and deployment order

Deploy the **Worker and its generated browser assets before distributing the APK**. Creation and
pair responses advertise `managerVersion: 2`; `/meta` advertises the APK's version. A new APK
against an older Worker displays an upgrade message without opening a session; a new browser
against an older Worker/APK displays an explicit compatibility message. Old browsers calling
`/channels` or `/reorder` against the updated Worker or APK get HTTP 426 instead of an oversized fallback or unsafe
positional edit. The updated Worker also rejects legacy single-channel bodies without a provider/revision.
Existing old APK/old browser sessions are not retrofitted; reload the browser and
update the APK. No Durable Object storage migration is required. Android's database migration is
additive. Pairing deadlines, hard session expiry, authentication, command allowlists, rate limits,
CSP and memory-only browser tokens remain enforced.

API additions: `GET /channels/page?source=<id>[&cat=<id>&q=<text>&limit=50&offset=0&revision=<revision>]`,
`POST /channels/bulk {source,revision,ids,action}`, read-only
`POST /channels/state {source,revision,ids}` and `POST /channels/move {source,revision,id,direction}`.
`/channel` now requires `source` and `revision`. Worker and Android reject invalid page parameters
and oversized/duplicate batches independently. Existing overall request/reply limits still apply.
There is no batch queue, streaming data, credential storage or new dependency.

## Validation and acceptance

Automated tests cover one-use/concurrent pairing, role and session isolation, invalid/expired codes,
revocation, offline/disconnect errors, exact request/reply correlation, command allowlisting, JSON
size bounds and pre-allocation origin/rate rejection. Android unit tests cover endpoint validation,
unambiguous code/token generation and the independent command allowlist. Virtual-time Android tests
also cover countdown rounding, connection delays, collection/restart/cancellation, stale callbacks,
sleep and pairing before expiry; Worker tests verify timing metadata and both expiry boundaries. Existing Android CI
compiles/tests/lints the app; the Cloudflare workflow verifies generated assets and Worker packaging.

New focused automated tests exercise 40,001 synthetic channels, duplicate-name page boundaries,
original/custom-name search, revision conflicts, removed providers, oversized batches, partial
SQLite failures, unrelated-setting and sync-bit preservation, browser selections across pages,
stale searches, lost replies and session expiry. Browser tests use the generated production JS
with a minimal DOM; headless Chromium smoke checks cover phone (390px), iPad-sized (1024px) and
desktop (1440px) layouts. These are not physical-TV or iPad Safari checks.

Before public rollout, use an actual Cloudflare deployment, TV and iPad browser (unverified here):

- Pair with only the displayed code on separate networks; verify channels, rename/hide/favorite/order,
  provider test/add and NAS test/save against authorized test resources.
- Confirm a second browser cannot reuse the code, and a second TV is isolated.
- Verify Back/Done, browser Disconnect, five-minute unclaimed expiry, thirty-minute hard expiry,
  network interruption, app shutdown and TV sleep all deny further management.
- Confirm no secrets appear in browser URLs, browser persistent storage, job output or application logs.
- Check small-screen touch controls, TV D-pad focus, status/error messages, Safari session expiry and
  very large categories, search keyboard behavior, VoiceOver/focus, selection across pages,
  confirmation, refresh during browsing/batches, removed/changed providers, partial failures and
  a lost reply after a successful TV edit. An automated test is not a substitute for these checks.

The public HTML/CSS/JS are derived from the existing GPL manager rather than a separate feature fork.
After changing `ManagerPage.kt` or `browser-transport.js`, regenerate them with
`python3 cloudflare/manager/generate-page.py`. CI checks that committed assets match the sources.

References: [Cloudflare WebSockets](https://developers.cloudflare.com/durable-objects/best-practices/websockets/),
[static assets](https://developers.cloudflare.com/workers/static-assets/binding/),
[rate limits](https://developers.cloudflare.com/workers/runtime-apis/bindings/rate-limit/) and
[plan limits/pricing](https://developers.cloudflare.com/durable-objects/platform/pricing/).
