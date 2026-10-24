# Beta feedback and triage

Testers: use the [beta guide](BETA-TESTING.md) and [issue chooser](https://github.com/DisabledAbel/Nuvix/issues/new/choose).
One **Beta test report** summarizes a candidate/device session, including Not Tested results.
One **Bug report** describes one reproducible problem. Link bugs from the session report and
search for duplicates first. **Feature requests** remain separate and may be deferred until after v1.0.

## Labels and one-time GitHub setup

October 24, 2026 inspection of the public labels page found `bug`, `enhancement`,
`documentation`, `duplicate`, `question`, `invalid`, `wontfix`, `help wanted`, `good first issue`,
`accessibility`, `dependencies`, `github_actions` and `java`. Reuse those names.
The session's GitHub connector cannot administer repository labels and the CLI label API read
was denied; no missing labels have been created. This is outstanding manual setup, not a
completed configuration. Forms can collect reports, but a nonexistent default label will not apply.

Before inviting testers, a maintainer opens [Issues → Labels](https://github.com/DisabledAbel/Nuvix/labels),
searches each exact name and creates only missing entries using **New label**. Do not rename or
delete unrelated labels, create case variants, or replace an existing equivalent without review.
These are organizational labels, not new workflows or automated release gates.

| Label | Suggested color | Use |
| --- | --- | --- |
| `beta` | `5319e7` | Candidate session reports and confirmed beta bugs; default on Beta test report. |
| `release-blocker` | `b60205` | Unresolved issue preventing the next candidate or stable release. |
| `bug` (reuse) | Keep existing | Default on Bug report. |
| `enhancement` (reuse) | Keep existing | Default on Feature request; feature freeze may defer it. |
| `stalker` | `0052cc` | Authorized Stalker setup/authentication/identity/EPG issues. |
| `playback` | `1d76db` | Live playback, buffering, tracks or decoder failures. |
| `multiview` | `0e8a16` | Panel/layout/audio/preset behavior. |
| `dvr` | `006b75` | Recording, timing, storage or conflict behavior. |
| `ui` | `c5def5` | Remote navigation, focus, text entry or accessibility. |
| `device-compatibility` | `fbca04` | Model/OS/decoder/installer/power restrictions. |
| `needs-reproduction` | `d4c5f9` | Evidence is incomplete or the failure is not reproduced yet. |

After this PR is merged, preview all three forms in **New issue** on GitHub. Verify required
fields and Not Tested/Not Supported choices without publishing dummy reports. On the first real
submission, confirm the expected default label; maintainers add feature/beta labels to bug reports
as appropriate. Repository files do not create labels or a GitHub milestone automatically.
Assign a triage owner and review frequency before recruitment. No new Action is needed.

## Severity and decisions

Put **Severity: Critical / High / Medium / Low** at the start of the maintainer's triage comment;
severity is separate from a feature label and does not require another set of labels.

| Severity | Meaning | Handling |
| --- | --- | --- |
| Critical | Security problem, data loss, severe crash or unusable core feature. | Treat as a release blocker; stop affected testing/distribution and investigate. Handle security details privately. |
| High | Major feature failure affecting normal use. | Review for `release-blocker`; record affected scope, owner and decision before stable. |
| Medium | Recoverable bug or compatibility problem. | Prioritize by affected devices/users; document workaround and retest. Can still block if scope warrants it. |
| Low | Cosmetic issue or minor inconvenience. | Fix or explicitly defer with rationale; retain accessibility impact in the assessment. |

1. Check reports for sensitive data; do not quote leaked values. Ask for redacted evidence only.
2. Record candidate/build, device/OS, provider type, frequency, expected/actual behavior and
   reproducible steps. Request missing information and add `needs-reproduction` when needed.
3. Add `beta`, relevant feature/device labels and the severity comment. Link duplicates to the
   canonical report without losing device evidence. Assign an owner to every release blocker.
4. Reproduce using synthetic fixtures first; use only authorized provider identities for device
   tests. Never ask for a real MAC, credentials, private portal URL or configuration export.
5. Link a proposed fix and its candidate version. Keep the issue open pending verification on
   the affected scenario/device, or document an explicit deferred/duplicate decision with a link.
   **Cannot reproduce is not resolved.** Leave unresolved reports open with `needs-reproduction`;
   do not close them merely because a maintainer's different device passes.
6. Record retest evidence, remove `release-blocker` only after the stated blocking condition is
   resolved or explicitly reviewed as out of scope, and update the [release tracker](V1-RELEASE-CHECKLIST.md).
   A deferred issue stays documented; do not erase a compatibility gap by calling it passed.

Saved searches need no automation:

- [Open beta feedback](https://github.com/DisabledAbel/Nuvix/issues?q=is%3Aissue+is%3Aopen+label%3Abeta)
- [Open release blockers](https://github.com/DisabledAbel/Nuvix/issues?q=is%3Aissue+is%3Aopen+label%3Arelease-blocker)
- [Needs reproduction](https://github.com/DisabledAbel/Nuvix/issues?q=is%3Aissue+is%3Aopen+label%3Aneeds-reproduction)

## Security and sensitive reports

GitHub issues and Discord are public. Do not submit credentials, private URLs, MAC addresses,
serials/device IDs/signatures, tokens, personal information, databases, sync bundles or raw provider logs.
For a suspected vulnerability, use the repository **Security → Advisories → Report a vulnerability**
option **only if GitHub offers it**. Private vulnerability reporting availability has not been verified.
If unavailable, ask a maintainer to establish a private channel without posting exploit details or
secrets publicly. No private support address or guaranteed private Discord channel is claimed here.
If data was exposed, remove the attachment/post where possible and revoke affected access through
the provider; deleting a public post cannot guarantee that copies no longer exist.
