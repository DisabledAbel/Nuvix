# Nightly prereleases

The **Nightly prerelease** GitHub Actions workflow builds the repository's default
branch every day at **21:00 PST (UTC−8)**, using cron `0 5 * * *` (05:00 UTC on the
following day). This is a fixed PST schedule: during daylight saving time it runs
at **22:00 PDT** in Los Angeles. GitHub can delay scheduled jobs; the APK is
published after the build and verification finish. You can also run it from
**Actions → Nightly prerelease → Run workflow**.

## Repository configuration

Merge `.github/workflows/nightly.yml` and its supporting changes into the default
branch and enable GitHub Actions. Configure the existing release signing secrets
and certificate variable from [signing.md](signing.md):

- Secrets: `KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`.
- Variable: `SIGNING_CERT_SHA256`.
- New secret: `GEMINI_API_KEY`, a Google Gemini API key with access to the selected
  model and sufficient quota. Store it in **Settings → Secrets and variables →
  Actions**, never in source code or the app.
- Optional variable: `GEMINI_MODEL`; defaults to `gemini-2.5-flash`. Set this to an
  available Gemini model ID if your account uses a different model or Google
  retires the default. API usage is subject to your Google account's quota and pricing.

The build job has read-only repository access. The publish job uses the automatic
`GITHUB_TOKEN` with `contents: write`; no personal access token is needed. Repository
or organization policies must allow this permission. On public repositories,
GitHub may disable scheduled workflows after 60 days without repository activity.

## Published builds

Each successful run creates a new `nightly-YYYYMMDD.RUN.ATTEMPT` GitHub release,
marked **prerelease** and excluded from **Latest**. The date uses fixed PST. The tag
points to the exact commit built, even if the default branch advances during CI.
The workflow runs Android unit tests and lint for the nightly variant, verifies
the APK's package, version, signing certificate and checksum, and attaches:

- `nuvix.apk`, a signed, optimized nightly APK.
- `update.json` and `SHA256SUMS` with the verified APK identity and integrity data.
- The matching GPL source archive, including build scripts and license notices.

Nightlies use application ID `com.disabledabel.nuvix.nightly`, so they install
alongside stable Nuvix and maintain separate app data. Version names look like
`0.1.0-nightly.20261015.42.1`; version codes use `RUN * 100 + ATTEMPT` so each new
run or rerun increases within the nightly app. Keep the workflow file and its run
counter; recreating it may reset numbering. Fewer than 100 attempts per run are
supported. A local `./gradlew :app:assembleNightly` creates an unsigned nightly
with suffix `-nightly.local` and version code 1 unless signing credentials and
`-PnightlyBuildId=YYYYMMDD.RUN.ATTEMPT -PnightlyVersionCode=NUMBER` are supplied.

Install nightly APKs manually from GitHub Releases. The existing in-app updater
selects stable releases; it does not provide nightly channel updates.

## Gemini descriptions

`scripts/nightly-notes.py` sends commit subjects and a changed-file summary to
Gemini, comparing with the most recent reachable nightly tag. The first nightly
uses the most recent reachable stable tag, or repository history if no tag exists.
Input is limited to 200 commits and bounded text sizes; the description identifies
limited history. Every daily run publishes a prerelease, even without code changes.

The description includes a prerelease notice, exact build commit, installation
instructions and source/license information. The API key is passed only to the
notes step, outside the APK build. Transient Gemini errors are retried; missing
credentials, permanent errors or incomplete descriptions fail the run before
publication. There is no non-Gemini fallback. The validation reports remain
available in Actions for failed builds.
