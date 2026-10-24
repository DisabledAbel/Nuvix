# Authorized Stalker portal configuration

Open **Settings → Providers**, find your Stalker provider, and select **Advanced
Configuration**. The existing provider **Edit** form also supports editing the portal MAC;
it now asks for explicit confirmation of a changed MAC.

**Portal MAC Address** is the subscription identity assigned by your IPTV provider. It
never changes your television's physical Ethernet/Wi-Fi MAC. Select **Edit MAC Address**,
enter six colon-separated hexadecimal pairs, then **Save**. Letters display in uppercase.
Malformed/incomplete input is rejected. Confirm the warning:

> Changing your portal MAC may require your IPTV provider to reauthorize your device and could temporarily interrupt access.

Cancel or Back discards the local draft. Cancel in the confirmation dialog returns to the
draft. Saving an identity change does not test the portal, load the catalog, restart players,
or stop recordings. Reconnect using the next channel open or provider refresh. The existing
Edit form retains its explicitly requested Save & Load behavior.

## Automatic and provider-supplied identity

Expand **Advanced Device Identity**. Serial Number, Device ID 1, Device ID 2 and Signature
each have **Automatic (Default)** and **Custom — Provider Supplied** selectors. A custom
value must be explicitly supplied by the authorized provider. Its casing and formatting
are preserved. **Restore Automatic** resets only that field. Automatic is recommended
unless the provider instructs otherwise.

Automatic values use the existing formulas: serial = first 13 uppercase hex characters
of MD5(portal MAC); both device IDs = uppercase hex SHA-256(uppercase portal MAC);
signature = uppercase hex SHA-256(effective serial + portal MAC + handshake challenge).
The challenge arrives from the portal, so the automatic signature is resolved at
handshake time. A changed MAC affects automatic fields; custom values remain independent.
Legacy stored MAC casing is retained on migration and identity-only edits so existing
case-sensitive serial/signature derivation stays unchanged. Newly entered MACs are canonical.

Custom values are opaque protocol strings with a 1–1024 character bound. Blank values,
control characters and malformed Unicode are rejected without echoing their contents.
There are no universal provider-specific hex lengths; check requirements with your provider.
Query parameters and cookies are encoded and profile metrics use JSON serialization.

## Storage, authentication and connections

Room migration 16→17 adds four nullable overrides, a nullable opaque original-account key
and a revision (default zero) inside the existing SQLCipher provider database. Null means
Automatic; existing rows and hashes are not rewritten. Provider ID, credentials, guide,
favorites, channel organization, recordings and sync preferences remain intact. The cloud
payload allowlist is unchanged: portal MAC and identity overrides are never sent to Supabase.
Provider objects and diagnostics redact credentials and identities.

Identity edits update the fields in a single revision-checked SQL statement. Old tokens
are invalidated, and an in-flight old authentication cannot publish its session or retry
using a stale revision. An unsaved connection test cannot replace the saved cached session.
A rejected profile yields an ordinary provider failure; verify the configuration with your
provider. Nuvix never guesses or rotates identity values.

The existing account connection key is retained across MAC/identity edits, including manual
account associations and connection policies. The same allocator still admits live viewing,
Smart Multi-View, Sports Hub, previews and DVR; editing cannot create another allowance.
Existing playback/capture owners, scheduled windows, warning acknowledgements, saved presets,
duration controls and late-start policies are unchanged. No automatic reconnection is
scheduled by the configuration save. An authorized provider may revoke existing stream URLs
when reauthorizing a device; Nuvix cannot prevent provider-side interruptions.

## Beta and hardware acceptance

Use only synthetic fixtures or your own authorized provider's assigned values. For a local
mock, follow [tools/README.md](../tools/README.md); it logs requests, so never enter real
subscriber credentials in it. Automated MockWebServer tests require no live subscriptions.

On Android TV, Google TV and Fire TV (including a device with limited memory):

- Navigate from Settings → Providers to Advanced Configuration using D-pad/OK. Check
  visible focus borders, accessible field labels, platform on-screen text entry and Back.
- Change a synthetic MAC, cancel the warning, verify no changes, then confirm a valid edit.
  Reject incomplete, nonhex and non-colon addresses. Reopen after process restart.
- Configure each custom field, change MAC, verify custom values persist, and independently
  restore each field to Automatic. Verify provider reauthorization and ordinary rejection.
- While recording and using Multi-View, save an edit. Confirm recording files continue growing,
  players are not restarted, and extra viewing still respects the original account limit.
  Check Sports Hub/EPG tuning, presets, conflict warnings, duration edits and late-start policy.
- Test an unrelated provider simultaneously; its token, playback and recording must continue.
- Inspect on-device SQLCipher storage/backup boundaries and credential-free logs with synthetic
  markers. Native encryption and physical remote/IME behavior require device validation.

Record model, OS, stream format, configured allowance and results. Host tests do not establish
provider-side concurrency or OEM recording/alarm reliability. Do not publish a release as part
of beta validation.
