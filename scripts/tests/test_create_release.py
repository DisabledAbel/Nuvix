"""Publication must reject package, version and signing mistakes before upload."""
import importlib.util
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

spec = importlib.util.spec_from_file_location("release", Path(__file__).parents[1] / "create-release.py")
release = importlib.util.module_from_spec(spec)
spec.loader.exec_module(release)


class ReleaseVerificationTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.apk = Path(self.directory.name) / "app-release.apk"
        self.apk.write_bytes(b"test APK fixture")
        self.metadata = {"applicationId": release.APPLICATION_ID, "elements": [{"versionCode": 21, "versionName": "0.1.0"}]}
        self.badging = "package: name='com.disabledabel.nuvix' versionCode='21' versionName='0.1.0'\n"
        self.signature = "Signer #1 certificate DN: CN=Nuvix\nSigner #1 certificate SHA-256 digest: " + "a" * 64 + "\n"

    def verify(self):
        return release.verify_apk(self.apk, Path("sdk"), self.metadata, "v0.1.0", "a" * 64)

    def test_verified_release_records_apk_integrity_and_identity(self):
        with patch.object(release, "run", side_effect=[self.badging, self.signature]):
            result = self.verify()
        self.assertEqual(21, result["versionCode"])
        self.assertEqual(64, len(result["sha256"]))
        self.assertEqual(self.apk.stat().st_size, result["sizeBytes"])
        self.assertEqual("a" * 64, result["signingCertificateSha256"])

    def test_changed_release_key_is_rejected(self):
        with patch.object(release, "run", side_effect=[self.badging, self.signature.replace("a" * 64, "b" * 64)]):
            with self.assertRaisesRegex(ValueError, "long-lived release signing key"):
                self.verify()

    def test_missing_expected_fingerprint_is_rejected(self):
        with self.assertRaisesRegex(ValueError, "fingerprint"):
            release.verify_apk(self.apk, Path("sdk"), self.metadata, "v0.1.0", "")

    def test_oversized_version_code_is_rejected(self):
        self.metadata["elements"][0]["versionCode"] = 2_147_483_648
        with self.assertRaisesRegex(ValueError, "Int.MAX_VALUE"):
            self.verify()

    def test_filtered_split_apk_is_rejected(self):
        self.metadata["elements"][0]["filters"] = [{"filterType": "ABI", "value": "arm64-v8a"}]
        with self.assertRaisesRegex(ValueError, "universal APK"):
            self.verify()

    def test_wrong_apk_identity_is_rejected(self):
        with patch.object(release, "run", return_value=self.badging.replace("nuvix'", "nuvix.debug'")):
            with self.assertRaisesRegex(ValueError, "package identity"):
                self.verify()

    def test_mismatched_tag_is_rejected_before_executing_tools(self):
        self.metadata["elements"][0]["versionName"] = "0.2.0"
        with patch.object(release, "run") as run:
            with self.assertRaisesRegex(ValueError, "tag"):
                self.verify()
            run.assert_not_called()

    def test_debug_certificate_is_rejected(self):
        with patch.object(release, "run", side_effect=[self.badging, self.signature.replace("CN=Nuvix", "CN=Android Debug")]):
            with self.assertRaisesRegex(ValueError, "debug signing"):
                self.verify()

    def test_debuggable_apk_is_rejected(self):
        with patch.object(release, "run", return_value=self.badging + "application-debuggable\n"):
            with self.assertRaisesRegex(ValueError, "debuggable"):
                self.verify()

    def test_absent_verified_certificate_is_rejected(self):
        with patch.object(release, "run", side_effect=[self.badging, "unsigned"]):
            with self.assertRaisesRegex(ValueError, "signing certificate"):
                self.verify()

    def nightly_metadata(self):
        self.metadata["applicationId"] += ".nightly"
        self.metadata["elements"][0]["versionName"] = "0.1.0-nightly.20261015.1.1"
        self.badging = self.badging.replace("nuvix'", "nuvix.nightly'").replace("0.1.0'", "0.1.0-nightly.20261015.1.1'")

    def test_nightly_checks_real_package_version_and_signing_certificate(self):
        self.nightly_metadata()
        with patch.object(release, "run", side_effect=[self.badging, self.signature]):
            result = release.verify_apk(self.apk, Path("sdk"), self.metadata,
                                        "nightly-20261015.1.1", "a" * 64, "nightly")
        self.assertEqual("com.disabledabel.nuvix.nightly", result["applicationId"])
        self.assertEqual("0.1.0-nightly.20261015.1.1", result["versionName"])

    def test_nightly_cannot_be_published_as_stable(self):
        self.nightly_metadata()
        with self.assertRaisesRegex(ValueError, "application ID"):
            self.verify()

    def test_stable_package_cannot_be_published_as_nightly(self):
        with self.assertRaisesRegex(ValueError, "application ID"):
            release.verify_apk(self.apk, Path("sdk"), self.metadata,
                               "nightly-20261015.1.1", "a" * 64, "nightly")

    def test_nightly_tag_must_match_apk_version(self):
        self.nightly_metadata()
        with self.assertRaisesRegex(ValueError, "tag"):
            release.verify_apk(self.apk, Path("sdk"), self.metadata,
                               "nightly-20261015.2.1", "a" * 64, "nightly")

    def test_nightly_rejects_wrong_certificate(self):
        self.nightly_metadata()
        with patch.object(release, "run", side_effect=[self.badging, self.signature]):
            with self.assertRaisesRegex(ValueError, "long-lived release signing key"):
                release.verify_apk(self.apk, Path("sdk"), self.metadata,
                                   "nightly-20261015.1.1", "b" * 64, "nightly")


if __name__ == "__main__":
    unittest.main()
