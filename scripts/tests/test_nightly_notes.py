"""Exercise Gemini failure handling and real Git comparison ranges without API calls."""
import importlib.util
import io
import json
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch
import urllib.error

spec = importlib.util.spec_from_file_location("nightly_notes", Path(__file__).parents[1] / "nightly-notes.py")
notes = importlib.util.module_from_spec(spec)
spec.loader.exec_module(notes)


def response(text="### Fixes\n- Fixed guide navigation.", reason="STOP"):
    return io.BytesIO(json.dumps({"candidates": [{
        "finishReason": reason, "content": {"parts": [{"text": text}]},
    }]}).encode())


class GeminiNotesTest(unittest.TestCase):
    def test_request_uses_selected_model_and_keeps_key_out_of_url(self):
        with patch.object(notes.urllib.request, "urlopen", return_value=response()) as send:
            description = notes.generate_description('{"commit_count": 1}', "test-secret", "gemini-2.5-flash")
        request = send.call_args.args[0]
        self.assertIn("/models/gemini-2.5-flash:generateContent", request.full_url)
        self.assertNotIn("test-secret", request.full_url)
        self.assertEqual("test-secret", request.get_header("X-goog-api-key"))
        self.assertIn("commit_count", json.loads(request.data)["contents"][0]["parts"][0]["text"])
        self.assertIn("guide navigation", description)

    def test_missing_key_fails_without_network_request(self):
        with patch.object(notes.urllib.request, "urlopen") as send:
            with self.assertRaisesRegex(ValueError, "GEMINI_API_KEY"):
                notes.generate_description("{}", "", notes.DEFAULT_MODEL)
            send.assert_not_called()

    def test_transient_quota_error_is_retried(self):
        error = urllib.error.HTTPError("https://example.test", 429, "quota", {}, None)
        with patch.object(notes.urllib.request, "urlopen", side_effect=[error, response()]) as send:
            with patch.object(notes.time, "sleep"):
                self.assertTrue(notes.generate_description("{}", "key", notes.DEFAULT_MODEL))
        self.assertEqual(2, send.call_count)

    def test_permanent_api_error_stops_without_exposing_response(self):
        error = urllib.error.HTTPError("https://example.test", 403, "sensitive-body", {}, None)
        with patch.object(notes.urllib.request, "urlopen", side_effect=error) as send:
            with self.assertRaisesRegex(RuntimeError, "HTTP 403") as caught:
                notes.generate_description("{}", "key", notes.DEFAULT_MODEL)
        self.assertNotIn("sensitive-body", str(caught.exception))
        self.assertEqual(1, send.call_count)

    def test_network_retry_exhaustion_fails(self):
        with patch.object(notes.urllib.request, "urlopen", side_effect=urllib.error.URLError("offline")) as send:
            with patch.object(notes.time, "sleep"):
                with self.assertRaisesRegex(RuntimeError, "three network attempts"):
                    notes.generate_description("{}", "key", notes.DEFAULT_MODEL)
        self.assertEqual(3, send.call_count)

    def test_empty_blocked_or_truncated_responses_fail(self):
        for text, reason in [("", "STOP"), ("partial", "MAX_TOKENS"), ("", "SAFETY")]:
            with self.subTest(reason=reason):
                with patch.object(notes.urllib.request, "urlopen", return_value=response(text, reason)):
                    with self.assertRaises(ValueError):
                        notes.generate_description("{}", "key", notes.DEFAULT_MODEL)

    def test_release_body_retains_installation_and_source_information(self):
        body = notes.release_body("nightly-20261015.1.1", "abc123", None, "No code changes.")
        for text in ["Experimental", "No code changes.", "abc123", ".nightly", "GPL-3.0", "Gemini"]:
            self.assertIn(text, body)


class GitContextTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.root = Path(self.directory.name)
        self.run_git("init", "-q")
        self.run_git("config", "user.email", "test@example.test")
        self.run_git("config", "user.name", "Test")
        self.commit("Initial app")

    def run_git(self, *args):
        return subprocess.run(["git", "-C", str(self.root), *args], check=True,
                              capture_output=True, text=True).stdout.strip()

    def commit(self, subject):
        (self.root / "changes.txt").write_text(subject)
        self.run_git("add", ".")
        self.run_git("commit", "-qm", subject)

    def context(self):
        with patch.object(notes, "git", side_effect=self.run_git):
            baseline, context = notes.change_context("nightly-20261015.3.1")
        return baseline, json.loads(context)

    def test_first_nightly_handles_repository_without_tags(self):
        baseline, context = self.context()
        self.assertIsNone(baseline)
        self.assertEqual(1, context["commit_count"])

    def test_latest_nightly_uses_numeric_order_and_only_new_changes(self):
        self.run_git("tag", "nightly-20261015.9.1")
        self.commit("Fix navigation")
        self.run_git("tag", "nightly-20261015.10.1")
        self.commit("Fix guide")
        baseline, context = self.context()
        self.assertEqual("nightly-20261015.10.1", baseline)
        self.assertEqual(1, context["commit_count"])
        self.assertIn("Fix guide", context["commits_most_recent_first"])
        self.assertNotIn("Fix navigation", context["commits_most_recent_first"])

    def test_stable_tag_is_first_nightly_baseline_and_no_changes_is_valid(self):
        self.run_git("tag", "v0.1.0")
        baseline, context = self.context()
        self.assertEqual("v0.1.0", baseline)
        self.assertEqual(0, context["commit_count"])

    def test_tag_on_unmerged_branch_is_ignored(self):
        self.run_git("tag", "v0.1.0")
        original_branch = self.run_git("branch", "--show-current")
        self.run_git("checkout", "-qb", "other-history")
        self.commit("Unmerged work")
        self.run_git("tag", "nightly-20261015.99.1")
        self.run_git("checkout", "-q", original_branch)
        self.commit("Default branch fix")
        baseline, context = self.context()
        self.assertEqual("v0.1.0", baseline)
        self.assertIn("Default branch fix", context["commits_most_recent_first"])
        self.assertNotIn("Unmerged work", context["commits_most_recent_first"])


if __name__ == "__main__":
    unittest.main()
