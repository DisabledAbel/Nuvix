"""Security boundaries and actual loopback behavior for the IPTV testing system."""
import importlib.util
import io
import json
from pathlib import Path
import sys
import tempfile
import time
import unittest
import urllib.error
import urllib.request
from unittest.mock import patch

SCRIPTS = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(SCRIPTS))
from iptv.fixtures import serve
from iptv.probe import Client, ProbeFailure, playlist, probe_hls, validate_url

spec = importlib.util.spec_from_file_location("iptv_runner", SCRIPTS / "test-iptv-playback.py")
runner = importlib.util.module_from_spec(spec)
spec.loader.exec_module(runner)


class PolicyTest(unittest.TestCase):
    def test_public_allowlist_rejects_credentials_queries_ports_and_traversal(self):
        allowed = "https://test-streams.mux.dev/x36xhzz/x36xhzz.m3u8"
        self.assertEqual(allowed, validate_url(allowed))
        for url in ("http://test-streams.mux.dev/x36xhzz/a", "https://test-streams.mux.dev.evil.test/x36xhzz/a",
                    "https://user:secret@test-streams.mux.dev/x36xhzz/a", allowed + "?token=secret",
                    allowed + "#secret", "https://test-streams.mux.dev:444/x36xhzz/a",
                    "https://test-streams.mux.dev/x36xhzz/%2e%2e/private", "http://127.0.0.1/a",
                    "https://test-streams.mux.dev/x36xhzz/%252e%252e/private",
                    "https://test-streams.mux.dev/other/a", allowed + "\r\nX:secret"):
            with self.subTest(url=url), self.assertRaises(ProbeFailure):
                validate_url(url)

    def test_fixture_allowlist_is_exact_origin(self):
        origin = "http://127.0.0.1:8123"
        validate_url(origin + "/a", origin)
        for url in ("http://localhost:8123/a", "http://127.0.0.1:8124/a", "https://127.0.0.1:8123/a"):
            with self.assertRaises(ProbeFailure):
                validate_url(url, origin)

    def test_hls_uri_attributes_are_validated_before_any_segment_io(self):
        client = Client(local_origin="http://127.0.0.1:1")
        with patch.object(client, "get", return_value=(b'#EXTM3U\n#EXTINF:1,\na.ts\n#EXT-X-MAP:URI="http://private.test/key"',
                                                       "http://127.0.0.1:1/a.m3u8")) as request:
            with self.assertRaisesRegex(ProbeFailure, "source_policy"):
                probe_hls("http://127.0.0.1:1/a.m3u8", client)
            self.assertEqual(1, request.call_count)

    def test_encrypted_stream_is_not_decrypted_or_fetched(self):
        client = Client(local_origin="http://127.0.0.1:1")
        with patch.object(client, "get", return_value=(b'#EXTM3U\n#EXT-X-KEY:METHOD=AES-128,URI="key"\n#EXTINF:1,\na.ts',
                                                       "http://127.0.0.1:1/a.m3u8")) as request:
            with self.assertRaisesRegex(ProbeFailure, "encrypted_fixture_unsupported"):
                probe_hls("http://127.0.0.1:1/a.m3u8", client)
            self.assertEqual(1, request.call_count)

    def test_redirect_cannot_escape_allowlist(self):
        client = Client(local_origin="http://127.0.0.1:1")
        error = urllib.error.HTTPError("", 302, "", {"Location": "http://10.0.0.1/private?token=secret"}, io.BytesIO())
        with patch.object(client.opener, "open", side_effect=error) as request:
            with self.assertRaisesRegex(ProbeFailure, "source_policy"):
                client.get("http://127.0.0.1:1/a")
            self.assertEqual(1, request.call_count)

    def test_redirect_cycle_is_bounded(self):
        client = Client(local_origin="http://127.0.0.1:1")
        def redirect(*_, **__):
            raise urllib.error.HTTPError("", 302, "", {"Location": "/again"}, io.BytesIO())
        with patch.object(client.opener, "open", side_effect=redirect) as request:
            with self.assertRaisesRegex(ProbeFailure, "redirect_budget"):
                client.get("http://127.0.0.1:1/a")
            self.assertEqual(4, request.call_count)

    def test_private_dns_result_is_rejected_before_io(self):
        client = Client()
        with patch("socket.getaddrinfo", return_value=[(0, 0, 0, "", ("127.0.0.1", 443))]), \
                patch.object(client.opener, "open") as request:
            with self.assertRaisesRegex(ProbeFailure, "source_policy"):
                client.get("https://test-streams.mux.dev/x36xhzz/a")
            request.assert_not_called()

    def test_request_and_wall_clock_budgets(self):
        for limit in (0, 12):
            client = Client(local_origin="http://127.0.0.1:1", request_limit=limit)
            if limit:
                client.deadline = time.monotonic() - 1
            with patch.object(client.opener, "open") as request:
                with self.assertRaisesRegex(ProbeFailure, "request_budget"):
                    client.get("http://127.0.0.1:1/a")
                request.assert_not_called()

    def test_playlist_requires_header_and_paired_extinf(self):
        self.assertEqual(["http://local/a"], playlist("#EXTM3U\n#EXTINF:-1,A\nhttp://local/a"))
        for text in ("oops", "#EXTM3U\nhttp://local/a", "#EXTM3U\n#EXTINF:-1,A", "#EXTM3U"):
            with self.assertRaises(ProbeFailure):
                playlist(text)


class LocalProviderTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        root = Path(self.directory.name)
        (root / "a.ts").write_bytes(b"\x47" + bytes(187 * 100))
        (root / "large.m3u8").write_bytes(b"a" * 2048)
        self.context = serve(root)
        self.server = self.context.__enter__()
        self.client = Client(2, self.server.base)

    def tearDown(self):
        self.context.__exit__(None, None, None)
        self.directory.cleanup()

    def test_m3u_xmltv_and_fake_auth(self):
        body, _ = self.client.get(self.server.base + "/playlist.m3u")
        self.assertEqual(2, len(playlist(body.decode())))
        body, _ = self.client.get(self.server.base + "/epg.xml")
        self.assertIn(b"Synthetic programme", body)
        with self.assertRaisesRegex(ProbeFailure, "http_unavailable"):
            self.client.get(self.server.base + "/player_api.php")
        # Direct requests to the loopback fixture may intentionally carry only known fake auth.
        with urllib.request.build_opener(urllib.request.ProxyHandler({})).open(
                self.server.base + "/player_api.php?username=fixture&password=fixture") as response:
            self.assertEqual(1, json.load(response)["user_info"]["auth"])

    def test_stalker_handshake_requires_synthetic_bearer_for_profile(self):
        opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
        with opener.open(self.server.base + "/portal.php?action=handshake") as response:
            self.assertEqual("synthetic-only-token", json.load(response)["js"]["token"])
        with self.assertRaises(urllib.error.HTTPError):
            opener.open(self.server.base + "/portal.php?action=get_profile")
        request = urllib.request.Request(self.server.base + "/portal.php?action=get_profile",
                                         headers={"Authorization": "Bearer synthetic-only-token"})
        with opener.open(request) as response:
            self.assertEqual("0", json.load(response)["js"]["blocked"])

    def test_limits_and_cleanup(self):
        opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
        with opener.open(self.server.base + "/limited.ts") as first:
            self.assertEqual(200, first.status)
            with self.assertRaises(urllib.error.HTTPError) as denied:
                opener.open(self.server.base + "/limited.ts")
            self.assertEqual(429, denied.exception.code)
        deadline = time.monotonic() + 3
        while self.server.active and time.monotonic() < deadline:
            time.sleep(.02)
        self.assertEqual(0, self.server.active)
        self.assertEqual(1, self.server.peak)

    def test_manifest_size_and_media_read_limits(self):
        with self.assertRaisesRegex(ProbeFailure, "response_too_large"):
            self.client.get(self.server.base + "/large.m3u8", limit=100)
        data, _ = self.client.get(self.server.base + "/a.ts", limit=188, segment=True)
        self.assertEqual(188, len(data))

    def test_slow_auth_and_interrupted_connections_are_classified(self):
        with self.assertRaisesRegex(ProbeFailure, "network_unavailable"):
            Client(.05, self.server.base).get(self.server.base + "/fault/slow")
        with self.assertRaisesRegex(ProbeFailure, "http_unavailable"):
            self.client.get(self.server.base + "/fault/auth")
        # EOF before the advertised content length must be classified, not accepted as success.
        with self.assertRaises(ProbeFailure):
            self.client.get(self.server.base + "/fault/interrupt")


class ReportTest(unittest.TestCase):
    def test_junit_failure_details_never_expose_urls_tokens_or_raw_messages(self):
        with tempfile.TemporaryDirectory() as folder:
            Path(folder, "TEST-fixture.xml").write_text('''<testsuite><testcase
              classname="app.opentv.iptv.IptvPlaybackTest" name="hlsOneConnectionSwitch">
              <failure message="Media3 must release the previous player">https://private.invalid/?token=secret</failure>
              </testcase><testcase classname="other.private.Class" name="private-name"><failure>secret</failure>
              </testcase></testsuite>''')
            result = runner.junit_failures(folder)
            self.assertEqual([{"id": "hls-one", "classification": "previous_media3_not_released"}], result)
            self.assertNotIn("secret", json.dumps(result))

    def test_instrumentation_command_failure_overrides_successful_measurements(self):
        with tempfile.TemporaryDirectory() as folder, patch.object(runner, "fingerprint", return_value={}), \
                patch.object(runner, "fixture_checks", return_value=[]), \
                patch.object(runner, "android_results", return_value=[{"id": "rapid", "status": "passed", "metrics": {}}]), \
                patch("builtins.print"):
            output = Path(folder, "report.json")
            result = runner.main(["--android-report-dir", folder, "--android-exit-code", "1", "--output", str(output)])
            self.assertEqual(1, result)
            report = json.loads(output.read_text())
            self.assertEqual("failed", report["status"])
            self.assertEqual("instrumentation_command_failed", report["android"][-1]["classification"])

    def test_public_failures_are_separate_from_app_regressions(self):
        with patch.object(runner, "probe_hls", side_effect=ProbeFailure("network_unavailable")):
            results = runner.public_checks(1)
        self.assertTrue(all(row["status"] == "external_unavailable" for row in results))
        self.assertNotIn("https://", json.dumps(results))

    def test_partial_instrumentation_reports_do_not_pass(self):
        with tempfile.TemporaryDirectory() as folder:
            Path(folder, "one.json").write_text(json.dumps({"schema_version": 1, "suite": "media3-local-v1", "results": [
                {"scenario": "rapid", "passed": True, "metrics": {}}]}))
            with self.assertRaisesRegex(ProbeFailure, "android_report_incomplete"):
                runner.android_results(folder)

    def test_device_report_rejects_sensitive_unknown_fields(self):
        with tempfile.TemporaryDirectory() as folder:
            Path(folder, "one.json").write_text(json.dumps({"schema_version": 1, "suite": "media3-local-v1", "results": [
                {"scenario": "rapid", "passed": True, "metrics": {"url": "https://private?token=secret"}}]}))
            with self.assertRaises(ProbeFailure):
                runner.android_results(folder)

    def test_comparison_requires_successful_matching_environment(self):
        current = {"environment": {"cpu": "a"}, "fixture": [], "public": [], "android": [
            {"id": "rapid", "status": "passed", "metrics": {"READY": 80}}]}
        previous = {**current, "status": "passed", "android": [
            {"id": "rapid", "status": "passed", "metrics": {"READY": 100}}]}
        self.assertEqual(-20, runner.comparison(current, previous)["changes"][0]["delta"])
        previous["environment"] = {"cpu": "b"}
        self.assertEqual("not_comparable", runner.comparison(current, previous)["status"])


if __name__ == "__main__":
    unittest.main()
