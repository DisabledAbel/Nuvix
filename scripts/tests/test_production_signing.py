"""Deterministic, secret-free signing preparation tests; no production/test keys needed."""
import copy
import importlib.util
import os
from pathlib import Path
import subprocess
import unittest
from unittest.mock import patch

import yaml

ROOT = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location("production_signing", ROOT / "scripts/production-signing.py")
signing = importlib.util.module_from_spec(spec)
spec.loader.exec_module(signing)
spec = importlib.util.spec_from_file_location("setup_identity", ROOT / "scripts/release-identity.py")
identity = importlib.util.module_from_spec(spec)
spec.loader.exec_module(identity)


class SetupPolicyTest(unittest.TestCase):
    def setUp(self):
        self.workflow = yaml.load((ROOT / ".github/workflows/setup-production-signing.yml").read_text(), Loader=yaml.BaseLoader)

    def reject(self, mutate):
        workflow = copy.deepcopy(self.workflow)
        mutate(workflow)
        with self.assertRaises(ValueError):
            identity.check_signing_setup(workflow)

    def test_manual_only_no_inputs_and_no_nightly_path(self):
        identity.check_signing_setup(self.workflow)
        for event in ("push", "pull_request", "pull_request_target", "schedule", "workflow_call", "workflow_run"):
            with self.subTest(event=event):
                self.reject(lambda w: w["on"].update({event: {}}))
        self.reject(lambda w: w["on"].update(workflow_dispatch={"inputs": {"ref": {"type": "string"}}}))
        self.assertFalse(any("nightly" in p.name for p in (ROOT / ".github/workflows").iterdir()))

    def test_branch_commit_and_action_pin_are_mandatory(self):
        self.reject(lambda w: w["jobs"]["prerequisites"].update({"if": "true"}))
        for change in ({"ref": "main"}, {"ref": "${{ inputs.ref }}"}, {"persist-credentials": "true"}):
            self.reject(lambda w: w["jobs"]["prerequisites"]["steps"][0]["with"].update(change))
        self.reject(lambda w: w["jobs"]["prerequisites"]["steps"][0].update(uses="actions/checkout@v7"))

    def test_concurrency_cannot_be_disabled_or_cancel_inflight_setup(self):
        self.reject(lambda w: w.pop("concurrency"))
        self.reject(lambda w: w["concurrency"].update({"cancel-in-progress": "true"}))
        self.reject(lambda w: w["concurrency"].update(group="${{ github.run_id }}"))

    def test_no_signing_secrets_writes_artifacts_or_extra_steps(self):
        for step in ({"run": "keytool -genkeypair"}, {"uses": "actions/upload-artifact@v7"},
                     {"run": "gh release create v1.0.0"}, {"run": "gh secret set KEYSTORE_BASE64"}):
            self.reject(lambda w: w["jobs"]["prerequisites"]["steps"].append(step))
        self.reject(lambda w: w["permissions"].update(contents="write"))
        self.reject(lambda w: w["permissions"].update(actions="write"))
        self.reject(lambda w: w["jobs"]["prerequisites"].update(environment="production"))
        self.reject(lambda w: w["jobs"]["prerequisites"]["steps"][1]["env"].update(KEYSTORE_BASE64="${{ secrets.KEYSTORE_BASE64 }}"))

    def test_preflight_cannot_be_skipped_or_replaced(self):
        for change in ({"if": "false"}, {"continue-on-error": "true"}, {"run": "true"}):
            self.reject(lambda w: w["jobs"]["prerequisites"]["steps"][1].update(change))

    def test_summary_cannot_expand_inputs_or_terminate_heredoc_early(self):
        for content in ("echo $KEYSTORE_PASSWORD", "echo '${{ secrets.KEYSTORE_PASSWORD }}'",
                        'cat >> "$GITHUB_STEP_SUMMARY" <<\'SUMMARY\'\nSUMMARY\necho secret\nSUMMARY\n'):
            self.reject(lambda w: w["jobs"]["prerequisites"]["steps"][2].update(run=content))


class SetupPreflightTest(unittest.TestCase):
    def setUp(self):
        self.env = {"GITHUB_REPOSITORY": signing.control.REPO, "GITHUB_EVENT_NAME": "workflow_dispatch",
                    "GITHUB_REF": "refs/heads/main", "GITHUB_RUN_ATTEMPT": "1", "GITHUB_SHA": "a" * 40,
                    "GITHUB_RUN_ID": "10", "GITHUB_ACTOR": "owner", "GITHUB_TRIGGERING_ACTOR": "owner",
                    "GITHUB_WORKFLOW_REF": f"{signing.control.REPO}/.github/workflows/setup-production-signing.yml@refs/heads/main"}
        self.environment = {"id": 1, "can_admins_bypass": False,
                            "protection_rules": [{"type": "required_reviewers", "prevent_self_review": True,
                                "reviewers": [{"type": "User", "reviewer": {"login": "approver", "type": "User"}}]}],
                            "deployment_branch_policy": {"custom_branch_policies": True, "protected_branches": False}}
        self.rules = [{"type": "pull_request", "parameters": {"required_approving_review_count": 1,
                          "require_code_owner_review": True, "dismiss_stale_reviews_on_push": True, "require_last_push_approval": True}},
                      {"type": "required_status_checks", "parameters": {"required_status_checks": [
                          {"context": "Compile, test and lint"}, {"context": "Supabase migrations and security"}]}}]
        base = signing.control.BASE
        self.responses = {
            f"{base}/actions/runs/10": {"event": "workflow_dispatch", "head_branch": "main", "head_sha": "a" * 40,
                "status": "in_progress", "conclusion": None, "path": ".github/workflows/setup-production-signing.yml"},
            f"{base}/branches/main": {"protected": True, "commit": {"sha": "a" * 40}},
            f"{base}/rules/branches/main": self.rules,
            f"{base}/rulesets?per_page=100&page=1": [{"id": 1}],
            f"{base}/rulesets/1": {"target": "branch", "enforcement": "active", "bypass_actors": []},
            f"{base}/collaborators/owner/permission": {"permission": "admin"},
            f"{base}/collaborators/approver/permission": {"permission": "write"},
            f"{base}/environments/production": self.environment,
            f"{base}/environments/production/deployment-branch-policies": {"branch_policies": [{"type": "branch", "name": "main"}]},
            f"{base}/releases?per_page=100&page=1": []}
        for scope in ("environments/production", "actions"):
            for kind in ("variables", "secrets"):
                self.responses[f"{base}/{scope}/{kind}?per_page=100&page=1"] = {kind: []}

    def run_preflight(self, pattern):
        def api(path, **kwargs):
            self.assertEqual({}, kwargs, "Setup must use GET only")
            return self.responses[path]
        with patch.dict(os.environ, self.env, clear=True), patch.object(signing.control, "api", side_effect=api), \
             patch.object(signing.control.release, "run", return_value="a" * 40) as tool, \
             self.assertRaisesRegex(ValueError, pattern):
            signing.preflight()
        # No keytool, encryption tool, secret-write CLI or other mutation may run.
        tool.assert_called_once_with("git", "rev-parse", "HEAD")

    def test_even_complete_prerequisites_stop_before_key_generation(self):
        self.run_preflight("no reviewed iPad-accessible encrypted backup")

    def test_untrusted_events_refs_repository_workflow_and_reruns_fail_before_api(self):
        for key, value in [("GITHUB_EVENT_NAME", "pull_request"), ("GITHUB_EVENT_NAME", "push"),
                           ("GITHUB_REF", "refs/heads/feature"), ("GITHUB_REF", "refs/tags/v1.0.0"),
                           ("GITHUB_REPOSITORY", "fork/Nuvix"), ("GITHUB_RUN_ATTEMPT", "2"),
                           ("GITHUB_WORKFLOW_REF", "DisabledAbel/Nuvix/.github/workflows/evil.yml@refs/heads/main")]:
            with self.subTest(key=key, value=value), patch.dict(os.environ, dict(self.env, **{key: value}), clear=True), \
                 patch.object(signing.control, "api") as api, self.assertRaises(ValueError):
                signing.preflight()
            api.assert_not_called()

    def test_stale_or_unprotected_main_and_review_bypass_are_rejected(self):
        for branch in ({"protected": False, "commit": {"sha": "a" * 40}},
                       {"protected": True, "commit": {"sha": "b" * 40}}):
            with self.subTest(branch=branch), patch.dict(os.environ, self.env, clear=True), \
                 patch.object(signing.control, "api", side_effect=lambda path: branch if path.endswith("branches/main") else self.responses[path]), \
                 patch.object(signing.control.release, "run", return_value="a" * 40), self.assertRaises(ValueError):
                signing.preflight()
        self.responses[f"{signing.control.BASE}/rulesets/1"]["bypass_actors"] = [{"actor_type": "RepositoryRole", "actor_id": 5}]
        self.run_preflight("disallow bypass")

    def test_independent_review_and_both_required_checks_cannot_be_omitted(self):
        for rules in ([], self.rules[:1], self.rules[1:]):
            with self.assertRaises(ValueError):
                signing.check_main_protection({"protected": True}, rules)
        self.rules[0]["parameters"]["required_approving_review_count"] = 0
        self.run_preflight("independent PR")

    def test_missing_environment_protection_or_independent_reviewer_blocks_setup(self):
        self.environment["can_admins_bypass"] = True
        self.run_preflight("administrator bypass")
        self.environment["can_admins_bypass"] = False
        self.environment["protection_rules"][0]["reviewers"][0]["reviewer"]["login"] = "owner"
        self.run_preflight("different authorized human")

    def test_every_partial_or_complete_signing_configuration_prevents_regeneration(self):
        for name in signing.SIGNING_NAMES | {signing.BACKUP_RECEIPT}:
            for scope in ("environments/production", "actions"):
                for kind in ("variables", "secrets"):
                    path = f"{signing.control.BASE}/{scope}/{kind}?per_page=100&page=1"
                    self.responses[path] = {kind: [{"name": name}]}
                    with self.subTest(name=name, scope=scope, kind=kind):
                        self.run_preflight("already exists or is partial")
                    self.responses[path] = {kind: []}

    def test_inventory_permission_failure_never_becomes_an_empty_inventory(self):
        with patch.dict(os.environ, self.env, clear=True), patch.object(signing.control, "api", side_effect=RuntimeError("denied")), \
             patch.object(signing, "require_backup_implementation") as backup, self.assertRaises(RuntimeError):
            signing.preflight()
        backup.assert_not_called()

    def test_paginated_inventory_finds_existing_key_on_later_page(self):
        with patch.object(signing.control, "api", side_effect=[{"secrets": [{"name": "OTHER"}] * 100},
                                                               {"secrets": [{"name": "KEYSTORE_BASE64"}]}]) as api:
            secrets = signing.inventory("unused", "secrets")
        self.assertEqual(2, api.call_count)
        with self.assertRaisesRegex(ValueError, "already exists"):
            signing.check_absent_identity([], secrets, [])

    def test_unverified_inventory_history_and_drafts_preserve_existing_identity(self):
        for variables in (None, {}, [{}], [{"name": None}]):
            with self.assertRaises(ValueError):
                signing.check_absent_identity(variables, [], [])
        for item, metadata in [({"tag_name": "v1.0.0"}, {"applicationId": "com.nuvix.player"}),
                               ({"tag_name": "v1.0.0"}, None),
                               ({"tag_name": "v1.0.0"}, {"applicationId": "unknown"}),
                               ({"tag_name": "v1.0.0", "draft": True}, None)]:
            with self.assertRaises(ValueError):
                signing.check_absent_identity([], [], [(item, metadata)])


class ReleaseReadinessTest(unittest.TestCase):
    def test_missing_mismatching_or_malformed_fingerprint_and_receipt_block_publication(self):
        for fingerprint, receipt in [("", ""), ("a" * 64, ""), ("a" * 64, "b" * 64),
                                     ("a" * 64, "true"), ("invalid", "a" * 64)]:
            with self.subTest(fingerprint=fingerprint, receipt=receipt), patch.dict(os.environ, {
                "EXPECTED_SIGNING_CERT_SHA256": fingerprint, signing.BACKUP_RECEIPT: receipt}, clear=True), \
                 self.assertRaises(ValueError):
                signing.release_readiness()

    def test_matching_receipt_cannot_bypass_unimplemented_recovery(self):
        with patch.dict(os.environ, {"EXPECTED_SIGNING_CERT_SHA256": "a" * 64, signing.BACKUP_RECEIPT: "A" * 64}, clear=True), \
             self.assertRaisesRegex(ValueError, "no reviewed iPad-accessible"):
            signing.release_readiness()

    def test_error_output_does_not_disclose_api_or_tool_output(self):
        canary = "DO-NOT-LOG-PRIVATE-MATERIAL"
        with patch("sys.argv", ["production-signing.py", "preflight"]), \
             patch.object(signing, "preflight", side_effect=RuntimeError(canary)), \
             self.assertRaises(SystemExit) as error:
            signing.main()
        self.assertNotIn(canary, str(error.exception))

    def test_readiness_cli_fails_closed_without_any_outputs_or_private_keys(self):
        result = subprocess.run(["python3", str(ROOT / "scripts/production-signing.py"), "release-readiness"],
                                env=dict(os.environ, EXPECTED_SIGNING_CERT_SHA256="a" * 64,
                                         SIGNING_KEY_BACKUP_CONFIRMED_SHA256="a" * 64), capture_output=True, text=True)
        self.assertNotEqual(0, result.returncode)
        self.assertEqual("", result.stdout)
        self.assertIn("setup/publication stopped", result.stderr)


if __name__ == "__main__":
    unittest.main()
