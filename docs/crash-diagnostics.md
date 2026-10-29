# Local crash diagnostics

Nuvix stores a single bounded **uncaught Java/Kotlin exception** report in app-private,
no-backup storage. It is not uploaded. To see it, reopen the app and visit
**Settings → About → Crash diagnostics**. Use **Copy report**, **Refresh** or
**Clear report**. A new uncaught crash replaces the old entry. Reports persist
until cleared, the app is uninstalled, or a later crash overwrites the entry.

The report contains the application version, a timestamp, exception class names
and bounded code symbols/line numbers. It deliberately excludes exception messages,
thread names, file paths, raw logcat, provider details, stream URLs, credentials and
device identifiers. Review any copied report before sharing it publicly.

This mechanism cannot detect native crashes, system low-memory kills, freezes/ANRs,
or crashes that occur before the Application initializes. Android may terminate the
process without calling the exception handler. **No report** does not mean the
app was stable, and the most recent saved report may be from an older crash.

For these cases, request a sanitized Android bug report or logcat excerpt including
the AndroidRuntime fatal exception or system exit reason, device model, OS version,
Nuvix build number, and approximate time. Redact credentials, URLs, MAC addresses,
identifiers and provider data before sharing. Do not use arbitrary streaming sources
to reproduce bugs.

Tests: CrashReportFormatterTest verifies bounded output and exclusion of raw
exception messages and malformed stack symbols. CI and unit tests cannot verify
native or physical Android TV crash reporting. Do not enable automatic telemetry,
change crash termination behavior, or expose this private file via the Cloudflare
manager or Supabase sync.
