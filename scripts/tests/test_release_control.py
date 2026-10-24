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
                    "TRUSTED_RELEASE_ACTORS": "DisabledAbel",
                    "GITHUB_RUN_ID": "10", "GITHUB_ACTOR": "DisabledAbel", "GITHUB_TRIGGERING_ACTOR": "DisabledAbel"}
        self.run = {"event": "workflow_dispatch", "head_branch": "main", "head_sha": "a" * 40,
                    "status": "in_progress", "conclusion": None}

    def test_channel_version_parsing(self):
        for version, channel in [("0.0.0", "stable"), ("1.0.0", "stable"), ("10.20.30", "stable"),
                                 ("0.0.0-rc.1", "rc"), ("1.0.0-rc.1", "rc"), ("10.20.30-rc.12", "rc")]:
            with self.subTest(version=version, channel=channel):
                tag = control.version_tag(version, channel)
                self.assertEqual("v" + version, tag)
                control.version_key(tag)

    def test_unsafe_versions_and_channel_mismatches(self):
        for version in ["v1.0.0", "1.0", "01.0.0", "1.00.0", "1.0.00", "1.0.0\n", " 1.0.0",
                        "1.0.0 ", "1.0.0;id", "$(id)", "../main", "١.0.0", "1.0.0+build.1"]:
            for channel in ("stable", "rc"):
                candidate = version + "-rc.1" if channel == "rc" else version
                with self.subTest(version=candidate, channel=channel), self.assertRaises(ValueError):
                    control.version_tag(candidate, channel)
        with self.assertRaises(ValueError):
            control.version_tag("1.0.0-rc.1", "stable")
        for version in ["v1.0.0", "1.0.0", "1.0.0-rc.0", "1.0.0-rc.01", "1.0.0-beta.1",
                        "1.0.0-rc.1\n", "1.0.0-rc.١", "1.0.0-rc.1+build.1"]:
            with self.subTest(version=version), self.assertRaises(ValueError):
                control.version_tag(version, "rc")
        with self.assertRaises(ValueError):
            control.version_tag("1.0.0", "nightly")

    def test_tag_input_for_rc_explains_policy_before_api_access(self):
        # The failed manual release submitted a tag-like v1.0.0 for channel rc.
        with patch.object(control, "api") as api:
            with self.assertRaises(ValueError) as error:
                control.preflight("v1.0.0", "rc")
            self.assertIn("X.Y.Z-rc.N", str(error.exception))
            self.assertIn("without a leading 'v'", str(error.exception))
            api.assert_not_called()

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

    def test_tag_context_requires_an_exact_version_and_matching_run(self):
        for tag in ("v1.0.0", "v1.0.0-rc.1"):
            env = dict(self.env, GITHUB_EVENT_NAME="push", GITHUB_REF="refs/tags/" + tag)
            run = dict(self.run, event="push", head_branch=tag)
            control.check_context(env, run)
            for change in ({"head_branch": "main"}, {"head_sha": "b" * 40}, {"status": "completed"}):
                with self.subTest(tag=tag, change=change), self.assertRaises(ValueError):
                    control.check_context(env, dict(run, **change))
        for ref in ("refs/heads/main", "refs/tags/v01.0.0", "refs/tags/v1.0.0-rc.0", "refs/tags/v1.0.0;id"):
            with self.subTest(ref=ref), self.assertRaises(ValueError):
                control.check_context(dict(self.env, GITHUB_EVENT_NAME="push", GITHUB_REF=ref), self.run)

    def test_nonterminal_workflow_status_does_not_reject_a_running_job(self):
        for status in ("queued", "requested", "waiting", "pending", "in_progress"):
            with self.subTest(status=status):
                control.check_context(self.env, dict(self.run, status=status, conclusion=None))
            for conclusion in ("cancelled", "failure", "success", "timed_out"):
                with self.subTest(status=status, conclusion=conclusion), self.assertRaisesRegex(ValueError, "not active"):
                    control.check_context(self.env, dict(self.run, status=status, conclusion=conclusion))
        for status in ("completed", "cancelled", "failure", "success", "timed_out", "unknown", None):
            with self.subTest(status=status), self.assertRaisesRegex(ValueError, "not active"):
                control.check_context(self.env, dict(self.run, status=status, conclusion=None))

    def test_run_context_error_identifies_mismatched_field_or_terminal_status(self):
        for change, message in [({"event": "push"}, "Run event"), ({"head_branch": "feature"}, "Run branch/tag"),
                                ({"head_sha": "b" * 40}, "Run commit"),
                                ({"status": "completed", "conclusion": "cancelled"}, "status='completed', conclusion='cancelled'")]:
            with self.subTest(change=change), self.assertRaisesRegex(ValueError, message):
                control.check_context(self.env, dict(self.run, **change))

    def test_request_resolves_optional_dispatch_prefix_and_tag_channel(self):
        requests = [
            ({"GITHUB_EVENT_NAME": "workflow_dispatch", "RELEASE_VERSION": "v1.0.0", "RELEASE_CHANNEL": "stable"}, "1.0.0", "stable"),
            ({"GITHUB_EVENT_NAME": "workflow_dispatch", "RELEASE_VERSION": "1.0.0-rc.1", "RELEASE_CHANNEL": "rc"}, "1.0.0-rc.1", "rc"),
            ({"GITHUB_EVENT_NAME": "push", "GITHUB_REF": "refs/tags/v1.0.0"}, "1.0.0", "stable"),
            ({"GITHUB_EVENT_NAME": "push", "GITHUB_REF": "refs/tags/v1.0.0-rc.2"}, "1.0.0-rc.2", "rc"),
        ]
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory) / "env"
            for env, version, channel in requests:
                with self.subTest(env=env), patch.dict(os.environ, dict(env, GITHUB_ENV=str(output)), clear=True):
                    output.write_text("")
                    control.configure_request()
                    self.assertEqual(f"RELEASE_VERSION={version}\nRELEASE_CHANNEL={channel}\n", output.read_text())
            for env in ({"GITHUB_EVENT_NAME": "pull_request"},
                        {"GITHUB_EVENT_NAME": "workflow_dispatch", "RELEASE_VERSION": "v1.0.0", "RELEASE_CHANNEL": "rc"},
                        {"GITHUB_EVENT_NAME": "push", "GITHUB_REF": "refs/tags/v1.0.0\nEVIL=value"},
                        {"GITHUB_EVENT_NAME": "push", "GITHUB_REF": "refs/heads/main"}):
                with self.subTest(env=env), patch.dict(os.environ, dict(env, GITHUB_ENV=str(output)), clear=True):
                    output.write_text("")
                    with self.assertRaises(ValueError):
                        control.configure_request()
                    self.assertEqual("", output.read_text())

    def test_trusted_publisher_defaults_to_owner_and_requires_write_access(self):
        default_env = dict(self.env)
        default_env.pop("TRUSTED_RELEASE_ACTORS")
        with patch.object(control, "api", return_value={"permission": "admin"}) as api:
            control.authorize_publisher(default_env)
            for change in ({"GITHUB_ACTOR": "other"}, {"GITHUB_TRIGGERING_ACTOR": "other"},
                           {"TRUSTED_RELEASE_ACTORS": ""}):
                with self.subTest(change=change), self.assertRaisesRegex(ValueError, "TRUSTED_RELEASE_ACTORS"):
                    control.authorize_publisher(dict(self.env, **change))
            self.assertEqual(1, api.call_count)
        env = dict(self.env, GITHUB_ACTOR="Maintainer", GITHUB_TRIGGERING_ACTOR="maintainer",
                   TRUSTED_RELEASE_ACTORS=" DisabledAbel, maintainer ")
        with patch.object(control, "api", return_value={"permission": "write"}):
            control.authorize_publisher(env)
        with patch.object(control, "api", return_value={"permission": "read"}), self.assertRaisesRegex(ValueError, "write access"):
            control.authorize_publisher(env)

    def test_duplicate_draft_and_versioncode_reuse_are_rejected(self):
        metadata = {"applicationId": control.release.APPLICATION_ID, "versionCode": 22, "versionName": "1.0.0-rc.1"}
        for item, data, code in [({"tag_name": "v1.0.0", "draft": True}, None, 23),
                                 ({"tag_name": "v1.0.0-rc.1"}, metadata, 22),
                                 ({"tag_name": "v0.1.0"}, metadata, 21),
                                 ({"tag_name": "v0.1.0"}, None, 23)]:
            with self.subTest(item=item), self.assertRaises(ValueError):
                control.check_history("v1.0.0", code, [(item, data)])
        control.check_history("v1.0.0", 23, [({"tag_name": "v1.0.0-rc.1"}, metadata)])
        control.check_history("v1.0.0", 23, [({"tag_name": "nightly-20261015.1.1"}, None)])

    def test_permanent_signing_draft_is_not_treated_as_an_apk_release(self):
        saved = {"tag_name": "nuvix-signing-key", "draft": True,
                 "assets": [{"name": "nuvix-signing-backup.zip"}]}
        with patch.object(control, "pages", return_value=[saved]), patch.object(control, "api") as api:
            history = control.published_history()
        api.assert_not_called()
        control.check_history("v1.0.0-rc.1", 22, history)

    def test_committed_metadata_required_and_code_bounded(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / "app").mkdir()
            (root / "CHANGELOG.md").write_text("## 1.0.0\n\nRelease notes.\n")
            for name, code in [("1.0.0", 23), ("1.0.0", 0), ("1.0.0", 2147483648), ("0.1.0", 23)]:
                (root / "app/build.gradle.kts").write_text(f'versionName = "{name}"\nversionCode = {code}\n')
                with patch.object(control, "ROOT", root):
                    if code == 23 and name == "1.0.0":
                        self.assertEqual(23, control.source_metadata("1.0.0"))
                    else:
                        with self.assertRaises(ValueError):
                            control.source_metadata("1.0.0")

    def test_source_metadata_requires_matching_version_heading(self):
        with tempfile.TemporaryDirectory() as directory, patch.object(control, "ROOT", Path(directory)):
            root = Path(directory)
            (root / "app").mkdir()
            changelog = root / "CHANGELOG.md"
            for version in ["1.0.0", "1.0.0-rc.1"]:
                (root / "app/build.gradle.kts").write_text(f'versionName = "{version}"\nversionCode = 23\n')
                changelog.unlink(missing_ok=True)
                with self.subTest(version=version, heading="missing"), self.assertRaisesRegex(ValueError, "CHANGELOG.md"):
                    control.source_metadata(version)
                for heading in ["", "## Unreleased", "## 1.0.1", "## 1x0x0", "### " + version,
                                "## " + version + ".1", "## " + version + "-extra"]:
                    changelog.write_text(heading + "\nRelease notes mention " + version + ".\n")
                    with self.subTest(version=version, heading=heading), self.assertRaisesRegex(ValueError, "heading"):
                        control.source_metadata(version)
                for heading in ["## " + version, "## [" + version + "] - 2026-10-21"]:
                    changelog.write_text(heading + "\n\nRelease notes.\n")
                    with self.subTest(version=version, heading=heading):
                        self.assertEqual(23, control.source_metadata(version))
            (root / "app/build.gradle.kts").write_text('versionName = "1.0.0"\nversionCode = 23\n')
            changelog.write_text("## 1.0.0-rc.1\nCandidate notes.\n")
            with self.assertRaisesRegex(ValueError, "heading"):
                control.source_metadata("1.0.0")

    def fake_api(self, path, **kwargs):
        values = {f"{control.BASE}/actions/runs/10": self.run,
                  f"{control.BASE}/collaborators/DisabledAbel/permission": {"permission": "admin"},
                  f"{control.BASE}/git/ref/heads/main": {"object": {"sha": "a" * 40}},
                  f"{control.BASE}/git/ref/tags/v1.0.0": None,
                  f"{control.BASE}/releases?per_page=100&page=1": []}
        return values[path]

    def test_preflight_rejects_changed_main_and_existing_tag(self):
        def api(path, **kwargs):
            if path.endswith("heads/main"):
                return {"object": {"sha": "b" * 40}}
            return self.fake_api(path, **kwargs)
        with patch.dict(os.environ, self.env), patch.object(control.release, "run", return_value="a" * 40), \
             patch.object(control, "source_metadata", return_value=23), patch.object(control, "api", side_effect=self.fake_api) as calls:
            self.assertEqual(("v1.0.0", 23), control.preflight("1.0.0", "stable"))
            self.assertFalse(any("/environments/" in call.args[0] or "/approvals" in call.args[0]
                                 for call in calls.call_args_list))
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
                with self.assertRaisesRegex(expected, f"HTTP {status}"):
                    control.api(f"{control.BASE}/git/ref/tags/v1.0.0", missing=True)
        with patch.object(control.subprocess, "run", return_value=CompletedProcess([], 1, b"", b"(HTTP 404)")):
            self.assertIsNone(control.api(f"{control.BASE}/git/ref/tags/v1.0.0", missing=True))
        with self.assertRaises(ValueError):
            control.api("https://attacker.example/")

    def test_tag_preflight_checks_existing_tag_without_environment_api(self):
        env = dict(self.env, GITHUB_EVENT_NAME="push", GITHUB_REF="refs/tags/v1.0.0")
        run = dict(self.run, event="push", head_branch="v1.0.0")
        def api(path, **kwargs):
            if "/actions/runs/" in path:
                return run
            if "/git/ref/tags/" in path:
                return {"object": {"type": "commit", "sha": "a" * 40}}
            return self.fake_api(path, **kwargs)
        with patch.dict(os.environ, env, clear=True), patch.object(control, "api", side_effect=api), \
             patch.object(control.release, "run", return_value="a" * 40), \
             patch.object(control, "source_metadata", return_value=23):
            self.assertEqual(("v1.0.0", 23), control.preflight("1.0.0", "stable"))
            with patch.object(control, "tag_commit", return_value="b" * 40), self.assertRaisesRegex(ValueError, "Pushed release tag"):
                control.preflight("1.0.0", "stable")
            with self.assertRaisesRegex(ValueError, "Pushed release tag"):
                control.preflight("1.0.1", "stable")

    def test_tag_commit_peels_annotations_and_rejects_other_objects(self):
        annotated = {"object": {"type": "tag", "sha": "b" * 40}}
        commit = {"object": {"type": "commit", "sha": "a" * 40}}
        with patch.object(control, "api", side_effect=[annotated, commit]) as api:
            self.assertEqual("a" * 40, control.tag_commit("v1.0.0"))
            self.assertEqual(f"{control.BASE}/git/tags/" + "b" * 40, api.call_args.args[0])
        for obj in ({"type": "tree", "sha": "a" * 40}, {"type": "tag", "sha": "bad"}):
            with patch.object(control, "api", return_value={"object": obj}), self.assertRaises(ValueError):
                control.tag_commit("v1.0.0")
        with patch.object(control, "api", return_value=annotated), self.assertRaisesRegex(ValueError, "nesting limit"):
            control.tag_commit("v1.0.0")

    def test_api_error_reports_status_without_response_or_token(self):
        from subprocess import CompletedProcess
        path = f"{control.BASE}/environments/production/deployment-branch-policies"
        for status in (403, 404, 500):
            result = CompletedProcess([], 1, b'private response', f'private token (HTTP {status})'.encode())
            with self.subTest(status=status), patch.object(control.subprocess, "run", return_value=result):
                with self.assertRaises(RuntimeError) as error:
                    control.api(path)
                self.assertIn(f"HTTP {status}", str(error.exception))
                self.assertIn(path, str(error.exception))
                self.assertNotIn("private", str(error.exception))

    def test_artifact_tampering_rejected_before_signing(self):
        with tempfile.TemporaryDirectory() as directory, patch.object(control, "ROOT", Path(directory)), \
             patch.object(control, "preflight", return_value=("v1.0.0", 23)):
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

    def test_sign_uses_loaded_encrypted_identity_without_legacy_secrets(self):
        from subprocess import CalledProcessError
        with tempfile.TemporaryDirectory() as directory, \
             patch.object(control, "verify_prepared", return_value=("v1.0.0", 23)), \
             patch.dict(os.environ, {"RUNNER_TEMP": directory, "ANDROID_HOME": directory,
                                    "EXPECTED_SIGNING_CERT_SHA256": "a" * 64}, clear=True):
            private = Path(directory) / "nuvix-release-key"
            private.mkdir()
            record = {"repository": control.REPO, "fingerprint": "a" * 64, "secrets": {
                "KEYSTORE_BASE64": "Zml4dHVyZQ==", "KEYSTORE_PASSWORD": "test-store-password",
                "KEY_ALIAS": "test-alias", "KEY_PASSWORD": "test-key-password"}}
            (private / "credentials.json").write_text(json.dumps(record))
            def fail(*args):
                self.assertEqual(b"fixture", (Path(directory) / "nuvix-release.jks").read_bytes())
                self.assertEqual(record["secrets"]["KEYSTORE_PASSWORD"], os.environ["KEYSTORE_PASSWORD"])
                self.assertNotIn("test-store-password", args)
                raise CalledProcessError(1, "apksigner")
            with patch.object(control.release, "run", side_effect=fail), self.assertRaises(CalledProcessError):
                control.sign("1.0.0", "stable", "a" * 64)
            self.assertFalse((Path(directory) / "nuvix-release.jks").exists())
            record["fingerprint"] = "b" * 64
            (private / "credentials.json").write_text(json.dumps(record))
            for name in record["secrets"]:
                os.environ.pop(name, None)
            with patch.object(control.release, "run") as run, self.assertRaisesRegex(ValueError, "pinned certificate"):
                control.sign("1.0.0", "stable", "a" * 64)
            run.assert_not_called()

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
                                               "GITHUB_EVENT_NAME": "workflow_dispatch",
                                               "RELEASE_NAME": "",
                                               "GITHUB_STEP_SUMMARY": str(self.root / "summary")}),
                        patch.object(control, "verify_prepared", side_effect=lambda v, c, h: (control.version_tag(v, c), 23)),
                        patch.object(control, "verify_distribution"), patch.object(control, "check_context"),
                        patch.object(control, "authorize_publisher")]
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
            return {"object": {"sha": "a" * 40, "type": "commit"}}
        return {}

    def test_complete_stable_assets_before_single_publication_call(self):
        with patch.object(control, "api", side_effect=self.api), patch.object(control.subprocess, "run") as command:
            control.publish("1.0.0", "stable", "a" * 64)
        writes = [(method, data) for method, _, data in self.calls if method != "GET"]
        self.assertEqual(["POST", "POST", "PATCH"], [method for method, _ in writes])
        self.assertEqual({"ref": "refs/tags/v1.0.0", "sha": "a" * 40}, writes[0][1])
        self.assertTrue(writes[1][1]["draft"])
        self.assertEqual("Nuvix 1.0.0", writes[1][1]["name"])
        self.assertFalse(writes[1][1]["prerelease"])
        self.assertEqual({"draft": False, "make_latest": "true"}, writes[-1][1])
        self.assertNotIn("--clobber", command.call_args.args[0])

    def test_custom_release_title_preserves_text_without_changing_tag_or_command(self):
        name = 'My "Nuvix" launch 🚀 — $(id); `whoami`'
        with patch.dict(os.environ, {"RELEASE_NAME": name}), \
             patch.object(control, "api", side_effect=self.api), patch.object(control.subprocess, "run") as command:
            control.publish("1.0.0", "stable", "a" * 64)
        draft = next(data for method, path, data in self.calls
                     if method == "POST" and path.endswith("/releases"))
        self.assertEqual(name, draft["name"])
        self.assertEqual("v1.0.0", draft["tag_name"])
        self.assertNotIn(name, command.call_args.args[0])

    def test_candidate_publication_never_becomes_latest(self):
        self.channel = "rc"
        (self.dist / "nuvix-v1.0.0-source.tar.gz").rename(self.dist / "nuvix-v1.0.0-rc.1-source.tar.gz")
        with patch.object(control, "api", side_effect=self.api), patch.object(control.subprocess, "run"):
            control.publish("1.0.0-rc.1", "rc", "a" * 64)
        self.assertEqual("Nuvix 1.0.0-rc.1", next(data for method, path, data in self.calls
                         if method == "POST" and path.endswith("/releases"))["name"])
        self.assertTrue(next(data for method, path, data in self.calls if path.endswith("/releases"))["prerelease"])
        self.assertEqual("false", self.calls[-1][2]["make_latest"])

    def test_tag_push_publication_keeps_the_existing_tag(self):
        with patch.dict(os.environ, {"GITHUB_EVENT_NAME": "push"}), \
             patch.object(control, "api", side_effect=self.api), patch.object(control.subprocess, "run"):
            control.publish("1.0.0", "stable", "a" * 64)
        self.assertFalse(any(path.endswith("/git/refs") for _, path, _ in self.calls))
        self.assertEqual(["POST", "PATCH"], [method for method, _, _ in self.calls if method != "GET"])

    def test_moved_tag_or_revoked_publisher_keeps_draft_private(self):
        with patch.object(control, "api", side_effect=self.api), patch.object(control.subprocess, "run"), \
             patch.object(control, "tag_commit", return_value="b" * 40), \
             self.assertRaisesRegex(ValueError, "tag moved"):
            control.publish("1.0.0", "stable", "a" * 64)
        self.assertFalse(any(method == "PATCH" for method, _, _ in self.calls))
        self.calls.clear()
        with patch.object(control, "api", side_effect=self.api), patch.object(control.subprocess, "run"), \
             patch.object(control, "authorize_publisher", side_effect=ValueError("publisher revoked")), \
             self.assertRaisesRegex(ValueError, "publisher revoked"):
            control.publish("1.0.0", "stable", "a" * 64)
        self.assertFalse(any(method == "PATCH" for method, _, _ in self.calls))

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

    def test_manual_and_tag_triggers_and_single_direct_publisher(self):
        workflows = self.workflows()
        release = workflows["release.yml"]
        self.assertEqual({"workflow_dispatch", "push"}, set(release["on"]))
        self.assertEqual({"tags": ["v*.*.*"]}, release["on"]["push"])
        self.assertEqual("false", release["concurrency"]["cancel-in-progress"])
        self.assertEqual("read", release["permissions"]["contents"])
        publisher = release["jobs"]["publish"]
        self.assertNotIn("environment", publisher)
        self.assertIn("production-signing.py load", json.dumps(publisher))
        self.assertEqual(["validate", "security"], publisher["needs"])
        self.assertEqual("write", publisher["permissions"]["contents"])
        for filename, workflow in workflows.items():
            self.assertNotIn("schedule", workflow["on"])
            self.assertNotIn("nightly", filename)
            if filename != "release.yml":
                self.assertEqual("write" if filename == "setup-production-signing.yml" else "read", workflow["permissions"].get("contents"))
                if filename != "setup-production-signing.yml":
                    self.assertNotIn("contents: write", (ROOT / ".github/workflows" / filename).read_text())
        validation = json.dumps(release["jobs"]["validate"])
        self.assertNotIn("secrets.", validation)
        self.assertIn("assembleRelease", validation)
        self.assertIn("lintRelease", validation)
        steps = publisher["steps"]
        sign = next(s for s in steps if " sign " in s.get("run", ""))
        self.assertEqual({"GH_TOKEN", "KEYSTORE_BASE64", "KEYSTORE_PASSWORD", "KEY_ALIAS", "KEY_PASSWORD"}, set(sign["env"]))
        self.assertFalse(any("upload-artifact" in s.get("uses", "") for s in steps))
        for job in release["jobs"].values():
            self.assertIn("refs/heads/main", job["if"])
            self.assertIn("workflow_dispatch", job["if"])
            checkout = next(s for s in job["steps"] if "checkout@" in s.get("uses", ""))
            self.assertEqual("${{ github.sha }}", checkout["with"]["ref"])
            self.assertEqual("false", checkout["with"]["persist-credentials"])

    def test_publish_token_scope_and_setup_action_pins(self):
        workflow = self.workflows()["release.yml"]
        self.assertNotIn("GH_TOKEN", workflow.get("env", {}))
        publisher = workflow["jobs"]["publish"]
        self.assertNotIn("GH_TOKEN", publisher.get("env", {}))
        commands = set()
        pinned_actions = set()
        for step in publisher["steps"]:
            run = step.get("run", "")
            if run.startswith("python3 scripts/release-control.py "):
                commands.add(run.split()[2])
                if run.split()[2] != "request":
                    self.assertEqual("${{ github.token }}", step["env"]["GH_TOKEN"])
            elif run == "python3 scripts/production-signing.py load":
                self.assertEqual("${{ github.token }}", step["env"]["GH_TOKEN"])
            else:
                self.assertNotIn("GH_TOKEN", step.get("env", {}))
            uses = step.get("uses", "")
            name = uses.split("@")[0]
            if name in ("actions/setup-java", "android-actions/setup-android"):
                self.assertRegex(uses, r"^[^@]+@[a-f0-9]{40}$")
                pinned_actions.add(name)
        self.assertEqual({"request", "verify", "sign", "publish"}, commands)
        self.assertEqual({"actions/setup-java", "android-actions/setup-android"}, pinned_actions)


if __name__ == "__main__":
    unittest.main()
