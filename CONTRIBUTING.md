# Contributing to OpenTV

Everyone is welcome here, including — especially — people who have never contributed to an
open-source project before. If you are fixing the bug that has been annoying you, you are
exactly the person this project is for.

## The one hard rule

**Do not copy code from any other IPTV app, and do not decompile one.**

OpenTV is a clean-room implementation. If any decompiled or copied code enters this repository,
the project loses the legal footing that makes it safe for anyone to use, fork and distribute —
which is the whole reason it exists. Pull requests that appear to contain code lifted from a
closed-source app will be closed.

You may absolutely:

- use another app as a *user* and describe how it behaves
- read public API documentation, XMLTV specs, or the Xtream Codes request format
- port ideas, layouts and interaction patterns

You may not:

- run an APK through a decompiler and paste the output
- copy source you obtained from a private repository or leak

## Reporting a bug

The most valuable bug report in this project names a **device**. Most failures here are
device-specific — a particular box, a particular Android version, a particular provider. Please
include:

- Device and Android version (e.g. "Ugoos AM9 Pro, Android 14"; "Fire TV Stick 4K, Fire OS 7")
- OpenTV version
- Source type: Xtream login or M3U playlist
- What happened, and what you expected
- Logs if you can get them: `adb logcat -s OpenTV:* EpgRepository:* SyncWorker:*`

**Never paste your provider's URL, username or password into an issue.** Redact them. They are
credentials for a service you pay for, and issues are public forever.

## Setting up

```bash
git clone https://github.com/OWNER/opentv.git
cd opentv
./gradlew test          # fast, no device needed
./gradlew assembleDebug
```

JDK 17+ and Android SDK API 35. Android Studio handles both.

Testing against a real provider is the hard part of working on this project. If you do not have
one, the parsers, the database layer and the UI can all be worked on with unit tests and
synthetic data — and those are where most of the interesting bugs are.

## Pull requests

- One change per PR. A PR that fixes a bug *and* restyles three screens is hard to review and
  hard to revert.
- Add a test if the change is testable. Parsers, URL handling, time arithmetic and database
  merge logic all are.
- Run `./gradlew test` before pushing.
- Explain the *why* in the description. The code says what it does.

Comments should explain reasoning, not restate the line below them. Look at
`EpgRepository` or `PlayerController` for the house style: the interesting comments are the ones
explaining why an obvious implementation was rejected.

## Good first issues

- Device-specific playback bugs — if you own the device, you are uniquely able to fix it
- Test coverage for `EpgRepository.sync` failure paths (see the end of `docs/ARCHITECTURE.md`)
- Accessibility: content descriptions, focus order, caption styling
- Translations

## Things that need discussion before you build them

Open an issue first for these. They are not refusals — they are decisions with consequences
that outlive the PR:

- **Anything requiring a server we have to run.** See `docs/ARCHITECTURE.md`. The short version:
  a hosting bill that grows with users is how this category of app dies.
- **Recording and catch-up.** Wanted, but large. Worth agreeing the shape first.
- **Replacing the hand-rolled Xtream JSON parsing with typed models.** There is a specific
  reason it is written that way; read the class docs before proposing it.

## Code of conduct

Be decent. Assume the person you are replying to is doing their best with less context than
you have. Frustration with a *piece of software* is fine and often justified; taking it out on
a person is not.

Maintainers are volunteers. Nobody here owes anyone a fix by a deadline. Equally, if a
maintainer goes quiet, that is what forking is for — the licence guarantees you that option,
permanently, and using it is not a betrayal. It is the design working.
