"""Exercise database startup and failure handling without requiring Docker."""

import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]

# Model the official image's socket-only initialization server and later TCP server.
FAKE_DOCKER = r'''
import json
import os
from pathlib import Path
import sys

directory = Path(os.environ["FAKE_DOCKER_DIR"])
mode = os.environ["FAKE_DOCKER_MODE"]
args = sys.argv[1:]
polls_file = directory / "polls"

def record(event):
    with (directory / "events").open("a") as log:
        log.write(json.dumps(event) + "\n")

if args[0] == "run":
    polls_file.write_text("0")
    record("run")
elif args[0] == "exec":
    if "pg_isready" in args:
        # Socket readiness succeeds too early, during initialization.
        record("temporary socket ready")
        sys.exit(0)
    if "--host" not in args or args[args.index("--host") + 1] != "127.0.0.1":
        sys.exit("connection to server on socket failed")
    if "PGPASSWORD=nuvix-isolated-test" not in args:
        sys.exit("TCP password authentication failed")
    if "--command" in args:
        polls = int(polls_file.read_text()) + 1
        polls_file.write_text(str(polls))
        record("probe")
        sys.exit(0 if polls >= 3 and mode != "timeout" else 2)
    if int(polls_file.read_text()) < 3:
        sys.exit("final server is not ready")
    sql = sys.stdin.read()
    record("sql")
    if mode == "sql_error":
        sys.exit("SQL validation failed")
elif args[0] == "logs":
    record("logs")
    print("database startup diagnostics")
elif args[0] == "rm":
    record("cleanup")
else:
    sys.exit("unexpected Docker command")
'''


class SupabaseRunnerTest(unittest.TestCase):
    def run_script(self, mode="success"):
        with tempfile.TemporaryDirectory() as temporary:
            directory = Path(temporary)
            docker = directory / "docker"
            docker.write_text(f"#!{sys.executable}\n" + FAKE_DOCKER)
            docker.chmod(0o755)
            # Keep the production retry bound while making timeout tests fast.
            sleep = directory / "sleep"
            sleep.write_text("#!/bin/sh\nexit 0\n")
            sleep.chmod(0o755)
            result = subprocess.run(
                ["bash", str(ROOT / "scripts/test-supabase.sh")],
                env={**os.environ, "PATH": f"{directory}{os.pathsep}{os.environ['PATH']}",
                     "FAKE_DOCKER_DIR": str(directory), "FAKE_DOCKER_MODE": mode},
                text=True, capture_output=True, timeout=30,
            )
            events = [json.loads(line) for line in (directory / "events").read_text().splitlines()]
            return result, events

    def test_waits_for_final_server_before_running_sql(self):
        result, events = self.run_script()
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(events[:4], ["run", "probe", "probe", "probe"])
        self.assertIn("sql", events[4:])
        self.assertEqual(events[-1], "cleanup")
        self.assertIn("Supabase migrations, RLS, typed validation and concurrent merges passed.", result.stdout)

    def test_startup_timeout_prints_logs_and_cleans_up_without_sql(self):
        result, events = self.run_script("timeout")
        self.assertNotEqual(result.returncode, 0)
        self.assertNotIn("sql", events)
        self.assertEqual(events[-2:], ["logs", "cleanup"])
        self.assertIn("database startup diagnostics", result.stderr)

    def test_sql_error_fails_immediately_and_cleans_up(self):
        result, events = self.run_script("sql_error")
        self.assertNotEqual(result.returncode, 0)
        self.assertEqual(events.count("sql"), 1)
        self.assertEqual(events[-1], "cleanup")
        self.assertIn("SQL validation failed", result.stderr)
        self.assertNotIn("concurrent merges passed", result.stdout)


if __name__ == "__main__":
    unittest.main()
