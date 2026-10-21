"""Release safety contracts. All API calls and signing commands use isolated fixtures."""
import copy
import importlib.util
import json
import os
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

import yaml

ROOT = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location("release_control", ROOT / "scripts/release-control.py")
control = importlib.util.module_from_spec(spec)
spec.loader.exec_module(control)


class ReleasePolicyTest(unittest.TestCase):
    def setUp(self):
        self.environment = {"id": 1, "can_admins_bypass": False,
                            "protection_rules": [{"type": "required_reviewers", "prevent_self_review": True,
                                                   "reviewers": [{"type": "User", "reviewer": {"login": "approver"}}]}],
                            "deployment_branch_policy": {"custom_branch_policies": True, "protected_branches": False}}
        self.policies = [{"type": "branch", "name": "main"}]
        self.env = {"GITHUB_REPOSITORY": control.REPO, "GITHUB_EVENT_NAME": "workflow_dispatch",
                    "GITHUB_REF": "refs/heads/main", "GITHUB_RUN_ATTEMPT": "1", "GITHUB_SHA": "a" * 40,
                    "GITHUB_RUN_ID": "10", "GITHUB_ACTOR": "initiator", "GITHUB_TRIGGERING_ACTOR": "initiator"}
        self.run = {"event": "workflow_dispatch", "head_branch": "main", "head_sha": "a" * 40,
                    "status": "in_progress", "conclusion": None}
        self.review = {"state": "approved", "environments": [{"id": 1}],
                       "user": {"login": "approver", "type": "User"}}

    def test_channel_version_parsing(self):
        for version, channel in [("1.0.0", "stable"), ("1.0.0-rc.1", "rc"), ("1.0.0-rc.2", "rc")]:
            self.assertEqual("v" + version, control.version_tag(version, channel))

    def test_unsafe_versions_and_channel_mismatches(self):
        for version in ["v1.0.0", "1.0", "01.0.0", "1.0.0\n", "1.0.0;id", "$(id)", "../main", "١.0.0", "1.0.0-rc.1"]:
            with self.subTest(version=version), self.assertRaises(ValueError):
                control.version_tag(version, "stable")
        for version in ["1.0.0", "1.0.0-rc.0", "1.0.0-rc.01", "1.0.0-beta.1"]:
            with self.subTest(version=version), self.assertRaises(ValueError):
                control.version_tag(version, "rc")
        with self.assertRaises(ValueError):
            control.version_tag("1.0.0", "nightly")

    def test_environment_must_be_explicitly_protected(self):
        control.check_environment(self.environment, self.policies)
        for mutate in [lambda e: e.update(can_admins_bypass=True),
                       lambda e: e.pop("can_admins_bypass"),
                       lambda e: e.update(protection_rules=[]),
                       lambda e: e["protection_rules"][0].update(prevent_self_review=False),
                       lambda e: e["protection_rules"][0].update(reviewers=[]),
                       lambda e: e.update(deployment_branch_policy=None)]:
            environment = copy.deepcopy(self.environment)
            mutate(environment)
            with self.assertRaises(ValueError):
                control.check_environment(environment, self.policies)
        for policies in [[], self.policies + [{"type": "tag", "name": "v*"}], [{"type": "branch", "name": "*"}]]:
            with self.assertRaises(ValueError):
                control.check_environment(self.environment, policies)

    def test_only_fresh_manual_main_run_can_publish(self):
        control.check_context(self.env, self.run)
        for key, value in [("GITHUB_REF", "refs/heads/feature"), ("GITHUB_EVENT_NAME", "push"),
                           ("GITHUB_RUN_ATTEMPT", "2"), ("GITHUB_REPOSITORY", "fork/Nuvix")]:
            with self.subTest(key=key), self.assertRaises(ValueError):
                control.check_context(dict(self.env, **{key: value}), self.run)
        for change in [{"status": "completed", "conclusion": "cancelled"}, {"head_sha": "b" * 40}, {"event": "pull_request"}]:
            with self.assertRaises(ValueError):
                control.check_context(self.env, dict(self.run, **change))

    def test_human_approval_must_match_environment_and_differ_from_initiator(self):
        self.assertEqual(["approver"], control.check_approval([self.review], 1, "initiator"))
        for reviews in [[], [dict(self.review, state="rejected")], [dict(self.review, environments=[{"id": 2}])],
                        [dict(self.review, user={"login": "initiator", "type": "User"})],
                        [dict(self.review, user={"login": "bot", "type": "Bot"})]]:
            with self.assertRaises(ValueError):
                control.check_approval(reviews, 1, "initiator")

    def test_duplicate_draft_and_versioncode_reuse_are_rejected(self):
        metadata = {"applicationId": control.release.APPLICATION_ID, "versionCode": 22}
        for item, data, code in [({"tag_name": "v1.0.0", "draft": True}, None, 23),
                                 ({"tag_name": "v1.0.0-rc.1"}, metadata, 22),
                                 ({"tag_name": "v0.1.0"}, metadata, 21),
                                 ({"tag_name": "v0.1.0"}, None, 23)]:
            with self.subTest(item=item), self.assertRaises(ValueError):
                control.check_history("v1.0.0", code, [(item, data)])
        control.check_history("v1.0.0", 23, [({"tag_name": "v1.0.0-rc.1"}, metadata)])
        control.check_history("v1.0.0", 23, [({"tag_name": "nightly-20261015.1.1"}, None)])

    def test_committed_metadata_required_and_code_bounded(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / "app").mkdir()
            for name, code in [("1.0.0", 23), ("1.0.0", 0), ("1.0.0", 2147483648), ("0.1.0", 23)]:
                (root / "app/build.gradle.kts").write_text(f'versionName = "{name}"\nversionCode = {code}\n')
                with patch.object(control, "ROOT", root):
                    if code == 23 and name == "1.0.0":
                        self.assertEqual(23, control.source_metadata("1.0.0"))
                    else:
                        with self.assertRaises(ValueError):
                            control.source_metadata("1.0.0")

    def fake_api(self, path, **kwargs):
        values = {f"{control.BASE}/actions/runs/10": self.run,
                  f"{control.BASE}/collaborators/initiator/permission": {"permission": "admin"},
                  f"{control.BASE}/git/ref/heads/main": {"object": {"sha": "a" * 40}},
                  f"{control.BASE}/environments/production": self.environment,
                  f"{control.BASE}/environments/production/deployment-branch-policies": {"branch_policies": self.policies},
                  f"{control.BASE}/git/ref/tags/v1.0.0": None,
                  f"{control.BASE}/releases?per_page=100&page=1": []}
        return values[path]

    def test_preflight_rejects_changed_main_and_existing_tag(self):
        def api(path, **kwargs):
            if path.endswith("heads/main"):
                return {"object": {"sha": "b" * 40}}
            return self.fake_api(path, **kwargs)
        with patch.dict(os.environ, self.env), patch.object(control.release, "run", return_value="a" * 40), \
             patch.object(control, "source_metadata", return_value=23), patch.object(control, "api", side_effect=self.fake_api):
            self.assertEqual(("v1.0.0", 23), control.preflight("1.0.0", "stable"))
            with patch.object(control, "api", side_effect=api), self.assertRaisesRegex(ValueError, "main changed"):
                control.preflight("1.0.0", "stable")
            def collision(path, **kwargs):
                return {"object": {"sha": "a" * 40}} if "/tags/" in path else self.fake_api(path, **kwargs)
            with patch.object(control, "api", side_effect=collision), self.assertRaisesRegex(ValueError, "Tag already"):
                control.preflight("1.0.0", "stable")

    def test_api_fails_closed_except_genuine_404(self):
        from subprocess import CompletedProcess
        for status, expected in [(403, RuntimeError), (500, RuntimeError)]:
            with patch.object(control.subprocess, "run", return_value=CompletedProcess([], 1, b"", f"(HTTP {status})".encode())):
                with self.assertRaises(expected):
                    control.api(f"{control.BASE}/git/ref/tags/v1.0.0", missing=True)
        with patch.object(control.subprocess, "run", return_value=CompletedProcess([], 1, b"", b"(HTTP 404)")):
            self.assertIsNone(control.api(f"{control.BASE}/git/ref/tags/v1.0.0", missing=True))
        with self.assertRaises(ValueError):
            control.api("https://attacker.example/")

    def test_artifact_tampering_rejected_before_signing(self):
        with tempfile.TemporaryDirectory() as directory, patch.object(control, "ROOT", Path(directory)), \
             patch.object(control, "preflight", return_value=("v1.0.0", 23)), patch.object(control, "approved_run"):
            prepared = Path(directory) / "prepared"
            prepared.mkdir()
            (prepared / "unsigned.apk").write_bytes(b"unsigned")
            (prepared / "output-metadata.json").write_text("{}")
            plan = {"tag": "v1.0.0", "channel": "stable", "commit": "a" * 40, "versionCode": 23,
                    "unsignedSha256": control.digest(prepared / "unsigned.apk"),
                    "metadataSha256": control.digest(prepared / "output-metadata.json")}
            (prepared / "plan.json").write_text(json.dumps(plan))
            checksum = control.digest(prepared / "plan.json")
            with patch.dict(os.environ, self.env):
                control.verify_prepared("1.0.0", "stable", checksum)
                (prepared / "unsigned.apk").write_bytes(b"tampered")
                with self.assertRaisesRegex(ValueError, "checksum mismatch"):
                    control.verify_prepared("1.0.0", "stable", checksum)
                with self.assertRaisesRegex(ValueError, "plan checksum"):
                    control.verify_prepared("1.0.0", "stable", "0" * 64)

    def test_missing_signing_credentials_stop_before_any_command(self):
        with patch.object(control, "verify_prepared", return_value=("v1.0.0", 23)), \
             patch.dict(os.environ, {}, clear=True), patch.object(control.release, "run") as run:
            with self.assertRaisesRegex(ValueError, "credentials missing"):
                control.sign("1.0.0", "stable", "a" * 64)
            run.assert_not_called()

    def test_prepare_binds_unsigned_apk_and_metadata_to_job_output(self):
        with tempfile.TemporaryDirectory() as directory, patch.object(control, "ROOT", Path(directory)), \
             patch.object(control, "preflight", return_value=("v1.0.0", 23)):
            root = Path(directory)
            output = root / "app/build/outputs/apk/release"
            output.mkdir(parents=True)
            (output / "app-release-unsigned.apk").write_bytes(b"unsigned")
            (output / "output-metadata.json").write_text(json.dumps({"applicationId": control.release.APPLICATION_ID,
                "elements": [{"versionName": "1.0.0", "versionCode": 23, "outputFile": "app-release-unsigned.apk"}]}))
            with patch.dict(os.environ, dict(self.env, GITHUB_OUTPUT=str(root / "output"), GITHUB_STEP_SUMMARY=str(root / "summary"))):
                control.prepare("1.0.0", "stable")
            plan = json.loads((root / "prepared/plan.json").read_text())
            self.assertEqual(control.digest(root / "prepared/unsigned.apk"), plan["unsignedSha256"])
            self.assertEqual(control.digest(root / "prepared/output-metadata.json"), plan["metadataSha256"])
            self.assertEqual("plan_sha256=" + control.digest(root / "prepared/plan.json") + "\n", (root / "output").read_text())
            self.assertEqual("a" * 40, plan["commit"])

    def test_signing_tool_failure_cleans_multiline_base64_keystore(self):
        from subprocess import CalledProcessError
        with tempfile.TemporaryDirectory() as directory, \
             patch.object(control, "verify_prepared", return_value=("v1.0.0", 23)), \
             patch.dict(os.environ, {"KEYSTORE_BASE64": "Zml4\ndHVyZQ==\n", "KEYSTORE_PASSWORD": "private",
                                    "KEY_ALIAS": "alias", "KEY_PASSWORD": "private", "ANDROID_HOME": directory,
                                    "RUNNER_TEMP": directory, "EXPECTED_SIGNING_CERT_SHA256": "a" * 64}):
            key = Path(directory) / "nuvix-release.jks"
            def fail(*args):
                self.assertEqual(b"fixture", key.read_bytes())
                self.assertEqual(0o600, key.stat().st_mode & 0o777)
                self.assertNotIn("private", args)
                raise CalledProcessError(1, "apksigner")
            with patch.object(control.release, "run", side_effect=fail), self.assertRaises(CalledProcessError):
                control.sign("1.0.0", "stable", "a" * 64)
            self.assertFalse(key.exists())

    def test_failed_revalidation_has_no_publication_side_effect(self):
        with patch.object(control, "verify_prepared", side_effect=ValueError("canceled")), \
             patch.object(control, "api") as api:
            with self.assertRaises(ValueError):
                control.publish("1.0.0", "stable", "a" * 64)
            api.assert_not_called()


class PublicationTransactionTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.root = Path(self.directory.name)
        self.dist = self.root / "dist"
        self.dist.mkdir()
        for name in ["nuvix.apk", "update.json", "release-notes.md", "nuvix-v1.0.0-source.tar.gz"]:
            (self.dist / name).write_bytes(b"fixture")
        (self.dist / "SHA256SUMS").write_text("".join(
            f"{control.digest(p)}  {p.name}\n" for p in sorted(self.dist.iterdir())))
        self.patches = [patch.object(control, "ROOT", self.root),
                        patch.dict(os.environ, {"GITHUB_SHA": "a" * 40, "GITHUB_RUN_ID": "10",
                                               "GITHUB_STEP_SUMMARY": str(self.root / "summary")}),
                        patch.object(control, "verify_prepared", side_effect=lambda v, c, h: (control.version_tag(v, c), 23)),
                        patch.object(control, "verify_distribution"), patch.object(control, "check_context"),
                        patch.object(control, "check_environment"), patch.object(control, "approved_run")]
        for item in self.patches:
            item.start()
            self.addCleanup(item.stop)
        self.calls = []
        self.fail_digest = False
        self.channel = "stable"

    def api(self, path, method="GET", data=None, **kwargs):
        self.calls.append((method, path, data))
        if path.endswith("/git/refs"):
            return {}
        if path.endswith("/releases"):
            return {"id": 7}
        if path.endswith("/releases/7"):
            if method == "PATCH":
                return {"html_url": "https://github.com/DisabledAbel/Nuvix/releases/tag/v1.0.0"}
            return {"draft": True, "prerelease": self.channel == "rc", "tag_name": "v1.0.0-rc.1" if self.channel == "rc" else "v1.0.0", "assets": [
                {"name": p.name, "state": "uploaded", "size": p.stat().st_size,
                 "digest": "sha256:" + ("0" * 64 if self.fail_digest else control.digest(p))}
                for p in self.dist.iterdir()]}
        if "/git/ref/" in path:
            return {"object": {"sha": "a" * 40}}
        if path.endswith("/deployment-branch-policies"):
            return {"branch_policies": []}
        return {}

    def test_complete_stable_assets_before_single_publication_call(self):
        with patch.object(control, "api", side_effect=self.api), patch.object(control.subprocess, "run") as command:
            control.publish("1.0.0", "stable", "a" * 64)
        writes = [(method, data) for method, _, data in self.calls if method != "GET"]
        self.assertEqual(["POST", "POST", "PATCH"], [method for method, _ in writes])
        self.assertEqual({"ref": "refs/tags/v1.0.0", "sha": "a" * 40}, writes[0][1])
        self.assertTrue(writes[1][1]["draft"])
        self.assertFalse(writes[1][1]["prerelease"])
        self.assertEqual({"draft": False, "make_latest": "true"}, writes[-1][1])
        self.assertNotIn("--clobber", command.call_args.args[0])

    def test_candidate_publication_never_becomes_latest(self):
        self.channel = "rc"
        (self.dist / "nuvix-v1.0.0-source.tar.gz").rename(self.dist / "nuvix-v1.0.0-rc.1-source.tar.gz")
        with patch.object(control, "api", side_effect=self.api), patch.object(control.subprocess, "run"):
            control.publish("1.0.0-rc.1", "rc", "a" * 64)
        self.assertTrue(next(data for method, path, data in self.calls if path.endswith("/releases"))["prerelease"])
        self.assertEqual("false", self.calls[-1][2]["make_latest"])

    def test_bad_uploaded_checksum_leaves_draft_private(self):
        self.fail_digest = True
        with patch.object(control, "api", side_effect=self.api), patch.object(control.subprocess, "run"):
            with self.assertRaisesRegex(ValueError, "checksum mismatch"):
                control.publish("1.0.0", "stable", "a" * 64)
        self.assertFalse(any(method in ("PATCH", "DELETE") for method, _, _ in self.calls))

    def test_upload_error_never_publishes_or_deletes_reserved_version(self):
        from subprocess import CalledProcessError
        with patch.object(control, "api", side_effect=self.api), \
             patch.object(control.subprocess, "run", side_effect=CalledProcessError(1, "gh")):
            with self.assertRaises(CalledProcessError):
                control.publish("1.0.0", "stable", "a" * 64)
        self.assertFalse(any(method in ("PATCH", "DELETE") for method, _, _ in self.calls))

    def test_cancellation_after_upload_never_publishes(self):
        with patch.object(control, "api", side_effect=self.api), patch.object(control.subprocess, "run"), \
             patch.object(control, "check_context", side_effect=ValueError("canceled")):
            with self.assertRaisesRegex(ValueError, "canceled"):
                control.publish("1.0.0", "stable", "a" * 64)
        self.assertFalse(any(method == "PATCH" for method, _, _ in self.calls))


class WorkflowSecurityTest(unittest.TestCase):
    def workflows(self):
        return {p.name: yaml.load(p.read_text(), Loader=yaml.BaseLoader) for p in (ROOT / ".github/workflows").glob("*.yml")}

    def test_manual_trigger_and_single_gated_publisher(self):
        workflows = self.workflows()
        release = workflows["release.yml"]
        self.assertEqual({"workflow_dispatch"}, set(release["on"]))
        self.assertEqual("false", release["concurrency"]["cancel-in-progress"])
        self.assertEqual("read", release["permissions"]["contents"])
        publisher = release["jobs"]["publish"]
        self.assertEqual({"name": "production"}, publisher["environment"])
        self.assertEqual(["validate", "security"], publisher["needs"])
        self.assertEqual("write", publisher["permissions"]["contents"])
        for filename, workflow in workflows.items():
            self.assertNotIn("schedule", workflow["on"])
            self.assertNotIn("nightly", filename)
            if filename != "release.yml":
                self.assertEqual("read", workflow["permissions"].get("contents"))
                self.assertNotIn("contents: write", (ROOT / ".github/workflows" / filename).read_text())
        validation = json.dumps(release["jobs"]["validate"])
        self.assertNotIn("secrets.", validation)
        self.assertIn("assembleRelease", validation)
        self.assertIn("lintRelease", validation)
        steps = publisher["steps"]
        sign = next(s for s in steps if " sign " in s.get("run", ""))
        self.assertEqual({"KEYSTORE_BASE64", "KEYSTORE_PASSWORD", "KEY_ALIAS", "KEY_PASSWORD"}, set(sign["env"]))
        self.assertFalse(any("upload-artifact" in s.get("uses", "") for s in steps))
        for job in release["jobs"].values():
            self.assertIn("refs/heads/main", job["if"])
            self.assertIn("workflow_dispatch", job["if"])
            checkout = next(s for s in job["steps"] if "checkout@" in s.get("uses", ""))
            self.assertEqual("${{ github.sha }}", checkout["with"]["ref"])
            self.assertEqual("false", checkout["with"]["persist-credentials"])


if __name__ == "__main__":
    unittest.main()
