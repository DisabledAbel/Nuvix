"""RC1 source integration with the existing release policy; never sign or publish."""
import importlib.util
from pathlib import Path
import re
import unittest
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location("rc_identity", ROOT / "scripts/release-identity.py")
identity = importlib.util.module_from_spec(spec)
spec.loader.exec_module(identity)
control = identity.control
VERSION = "1.0.0-rc.1"
CODE = 22


class CandidatePreparationTest(unittest.TestCase):
    def candidate(self, number, code):
        version = f"1.0.0-rc.{number}"
        return ({"tag_name": "v" + version}, {
            "applicationId": "com.nuvix.player", "versionName": version,
            "versionCode": code, "signingCertificateSha256": "a" * 64,
        })

    def test_committed_candidate_version_code_and_permanent_identity(self):
        identity.check_source()
        self.assertEqual(CODE, control.source_metadata(VERSION))
        self.assertEqual("v" + VERSION, control.version_tag(VERSION, "rc"))
        identity.check_workflows()

    def test_candidate_format_is_exact_and_cannot_be_dispatched_as_stable(self):
        for version, channel in [(VERSION, "stable"), ("v" + VERSION, "rc"),
                                 ("1.0.0-rc.0", "rc"), ("1.0.0-rc.01", "rc"),
                                 (VERSION + "\n", "rc")]:
            with self.subTest(version=version, channel=channel), self.assertRaises(ValueError):
                control.version_tag(version, channel)

    def test_candidate_advances_known_development_metadata(self):
        previous = ({"tag_name": "v0.1.0"}, {
            "applicationId": "com.nuvix.player", "versionName": "0.1.0", "versionCode": 21,
        })
        control.check_history("v" + VERSION, CODE, [previous])
        for code in (21, 20):
            with self.subTest(code=code), self.assertRaisesRegex(ValueError, "versionCode"):
                control.check_history("v" + VERSION, code, [previous])

    def test_future_stable_uses_next_code_after_all_candidates(self):
        for count in (1, 2, 9, 79):
            history = [self.candidate(n, 21 + n) for n in range(1, count + 1)]
            with self.subTest(candidates=count):
                control.check_history("v1.0.0", 22 + count, history, "a" * 64)
                with self.assertRaisesRegex(ValueError, "versionCode"):
                    control.check_history("v1.0.0", 21 + count, history)

    def test_candidate_cannot_reuse_distributed_code_or_change_certificate(self):
        history = [self.candidate(1, CODE)]
        with self.assertRaisesRegex(ValueError, "versionCode"):
            control.check_history("v1.0.0-rc.2", CODE, history)
        with self.assertRaisesRegex(ValueError, "certificate"):
            control.check_history("v1.0.0", CODE + 1, history, "b" * 64)

    def test_exact_changelog_section_generates_complete_candidate_notes(self):
        changelog = (ROOT / "CHANGELOG.md").read_text()
        self.assertEqual(1, len(re.findall(r"^## \[1\.0\.0-rc\.1\]$", changelog, re.M)))
        notes = control.release.release_notes(ROOT / "CHANGELOG.md", VERSION)
        for title in ("Nuvix v1.0.0 Release Candidate 1", "What's Included", "Beta Testing Focus",
                      "Known Limitations", "Installation", "Reporting Bugs", "Licensing"):
            self.assertIn("### " + title, notes)
        for text in ("com.nuvix.player", "com.disabledabel.nuvix", "GPL-3.0-or-later", "OpenTV",
                     "https://github.com/DisabledAbel/Nuvix/issues", "no candidate APK is available yet",
                     "portal URLs", "MAC addresses", "tokens", "device identifiers"):
            self.assertIn(text, notes)
        self.assertNotIn("No changes queued", notes)
        self.assertNotIn("Nuvix development foundation", notes)
        self.assertNotIn("See the attached source and repository changelog.", notes)
        # GitHub release bodies and the downloaded notes have no repository-relative base.
        self.assertNotRegex(notes, r"\]\((?!https://|#)[^)]+\)")

    def test_metadata_and_notes_preparation_never_initiates_external_actions(self):
        # These are the same source readers used by preflight/signing, invoked without
        # dispatching those commands. Any API, signing, tag or publication call is a failure.
        with patch.object(control, "api", side_effect=AssertionError("No remote actions")), \
             patch.object(control.subprocess, "run", side_effect=AssertionError("No external commands")):
            self.assertEqual(CODE, control.source_metadata(VERSION))
            self.assertIn("Release Candidate 1", control.release.release_notes(ROOT / "CHANGELOG.md", VERSION))


if __name__ == "__main__":
    unittest.main()
