"""RC2 integration with published RC1 history; never sign or publish."""
import importlib.util
import json
from pathlib import Path
import re
import unittest
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location("rc_identity", ROOT / "scripts/release-identity.py")
identity = importlib.util.module_from_spec(spec)
spec.loader.exec_module(identity)
control = identity.control
VERSION = "1.0.0-rc.2"
CODE = 23
# Public release metadata, integrity checked during the audit in docs/releases.md.
# This records the observed RC1 signer; it is not an independent production trust pin.
RC1 = json.loads((ROOT / "scripts/tests/fixtures/rc1-update.json").read_text())


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

    def test_candidate_advances_actual_published_rc1_metadata(self):
        previous = ({"tag_name": "v1.0.0-rc.1"}, RC1)
        self.assertEqual("com.nuvix.player", RC1["applicationId"])
        self.assertEqual(22, RC1["versionCode"])
        identity.check_pr_history(VERSION, CODE, [previous])
        control.check_history("v" + VERSION, CODE, [previous])
        for code in (22, 21):
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
        history = [({"tag_name": "v1.0.0-rc.1"}, RC1)]
        with self.assertRaisesRegex(ValueError, "versionCode"):
            control.check_history("v" + VERSION, RC1["versionCode"], history)
        with self.assertRaisesRegex(ValueError, "certificate"):
            control.check_history("v" + VERSION, CODE, history, "b" * 64)
        control.check_history("v" + VERSION, CODE, history, RC1["signingCertificateSha256"])

    def test_rc2_signature_verifier_uses_synthetic_tools_and_rejects_changed_key(self):
        # No real APK is signed or verified here. Exercise the publisher's same-key contract
        # using synthetic aapt/apksigner outputs, separately from production evidence.
        import tempfile
        with tempfile.TemporaryDirectory() as directory:
            apk = Path(directory) / "fixture.apk"
            apk.write_bytes(b"synthetic unsigned test bytes")
            metadata = {"applicationId": "com.nuvix.player", "elements": [
                {"versionName": VERSION, "versionCode": CODE}]}
            badging = f"package: name='com.nuvix.player' versionCode='{CODE}' versionName='{VERSION}'\n"
            signature = "Signer #1 certificate DN: CN=Synthetic fixture\nSigner #1 certificate SHA-256 digest: " + "a" * 64 + "\n"
            for expected in ("a" * 64, "b" * 64):
                with patch.object(control.release, "run", side_effect=[badging, signature]):
                    if expected == "a" * 64:
                        result = control.release.verify_apk(apk, Path("unused-sdk"), metadata,
                                                            "v" + VERSION, expected, "rc")
                        self.assertEqual(CODE, result["versionCode"])
                    else:
                        with self.assertRaisesRegex(ValueError, "long-lived release signing key"):
                            control.release.verify_apk(apk, Path("unused-sdk"), metadata,
                                                       "v" + VERSION, expected, "rc")

    def test_exact_changelog_section_generates_complete_candidate_notes(self):
        changelog = (ROOT / "CHANGELOG.md").read_text()
        self.assertEqual(1, len(re.findall(r"^## \[" + re.escape(VERSION) + r"\]$", changelog, re.M)))
        notes = control.release.release_notes(ROOT / "CHANGELOG.md", VERSION)
        for title in ("Nuvix v1.0.0 Release Candidate 2", "What's Fixed", "What's Improved", "Beta Testing Focus",
                      "Known Limitations", "Installation and Updates", "Reporting Bugs", "Licensing"):
            self.assertIn("### " + title, notes)
        for text in ("com.nuvix.player", "com.disabledabel.nuvix", "GPL-3.0-or-later", "OpenTV",
                     "https://github.com/DisabledAbel/Nuvix/issues", "Prerelease for public beta testing",
                     "portal URLs", "MAC addresses", "tokens", "device identifiers"):
            self.assertIn(text, notes)
        self.assertNotIn("No changes queued", notes)
        self.assertNotIn("Nuvix development foundation", notes)
        self.assertNotIn("### What's Included", notes)  # Never append RC1's section.
        self.assertNotIn("See the attached source and repository changelog.", notes)
        # GitHub release bodies and the downloaded notes have no repository-relative base.
        self.assertNotRegex(notes, r"\]\((?!https://|#)[^)]+\)")

    def test_metadata_and_notes_preparation_never_initiates_external_actions(self):
        # These are the same source readers used by preflight/signing, invoked without
        # dispatching those commands. Any API, signing, tag or publication call is a failure.
        with patch.object(control, "api", side_effect=AssertionError("No remote actions")), \
             patch.object(control.subprocess, "run", side_effect=AssertionError("No external commands")):
            self.assertEqual(CODE, control.source_metadata(VERSION))
            self.assertIn("Release Candidate 2", control.release.release_notes(ROOT / "CHANGELOG.md", VERSION))


if __name__ == "__main__":
    unittest.main()
