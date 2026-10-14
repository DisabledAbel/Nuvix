# Automatic EPG configuration

Automatic configuration is enabled by default in **Guide settings**. After a playlist import,
on app startup and during the existing six-hour background refresh, Nuvix scans the channel
catalog and uses cached guides immediately. It then refreshes enabled feeds and searches for
guides for channels that still have no current or upcoming programme data.

Sources:

- Direct HTTPS XMLTV (`.xml` / `.xml.gz`) links advertised at `https://iptv-epg.org/guides`.
- IPTV-org's public channel and guide catalogs, including alternate channel names and the
  actual XML/GZIP source URLs advertised in `sources`. Catalog entries without a download
  source are skipped; a catalog ID alone does not establish a working guide.
- The existing UK Freeview and UK, USA, Canada and Australia epgshare01 feeds. Every relevant
  region is eligible, including a region with just one channel in a mixed-country playlist.
- Provider and user-added XMLTV feeds remain part of the combined guide.

Channel-name searches run locally. Requests download catalogs, and never send the user's
channel list or provider credentials to public discovery services. Catalog JSON is streamed,
responses have size limits, discovery requests have a one-minute timeout, and new automatic
guide downloads are limited to 12 per refresh. Remaining candidates can be tried on later
refreshes. Automatic downloads run in the background and share the existing encrypted guide
spool and atomic replacement logic.

Each successfully downloaded guide is matched immediately. The discovered TVG-id is stored
in the channel's `matchedEpgId` field, already consumed by the guide and player. Provider IDs
and manual overrides retain their precedence. Automatic matches survive catalog refreshes.
Only IDs with unexpired programmes in an enabled feed are eligible. Names with conflicting
IDs are left unmatched, and country hints prevent selecting an explicitly different country.
A previously verified working match stays stable while its data remains usable.

Turning a feed off prevents automatic discovery from turning it back on. Disabling automatic
configuration stops discovery; already enabled feeds continue their normal refresh. Turning
it on or choosing **Update guide now** triggers a scan with progress and a matched-channel
count. Unavailable feeds and discovery catalogs are reported in Guide settings. Failed guide
refreshes retain the last valid data.

Coverage depends on public sources. Directory pages must advertise direct XMLTV links;
JavaScript-only catalogs, subscription-only downloads and guide entries without programme
data cannot provide an automatic match. Unknown countries and ambiguous names may still
need a custom XMLTV feed or a manual override.

Validation: `AutoEpgConfigurationTest`, `PublicEpgDiscoveryTest`, `EpgRefreshTest`,
`EpgMatcherTest`, `XmltvChannelParseTest` and `XmltvTimeTest` cover discovery, mixed regions,
incremental persistence, conservative matching and atomic refresh behavior.
