"""Benchmark consumers accept optional schema metadata and additive column defaults."""
import importlib.util
from pathlib import Path
import subprocess
import sys
import unittest

ROOT = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location("sports_benchmark", ROOT / "scripts/benchmark_sports_queries.py")
sports = importlib.util.module_from_spec(spec)
spec.loader.exec_module(sports)


class BenchmarkSchemaTest(unittest.TestCase):
    def test_sports_fixture_applies_room_defaults_without_identity_overrides(self):
        database, _ = sports.fixture(channels=20)
        self.addCleanup(database.close)
        rows = database.execute("""SELECT stalkerRevision, stalkerSerialNumber, stalkerDeviceId1,
            stalkerDeviceId2, stalkerSignature, stalkerAccountKey FROM sources""").fetchall()
        self.assertEqual(rows, [(0, None, None, None, None, None)] * 4)
        indices = {row[1] for row in database.execute("PRAGMA index_list(channels)")}
        self.assertIn("index_channels_epgChannelId", indices)

    def test_sports_queries_still_enforce_bounds_on_latest_exported_schema(self):
        result = sports.validate(channels=40)
        self.assertEqual(result["cards"], 2)
        self.assertEqual(result["streams"], 20)
        self.assertLessEqual(result["batch"], 500)

    def test_epg_fixture_accepts_export_with_omitted_empty_indices(self):
        result = subprocess.run([sys.executable, str(ROOT / "scripts/benchmark_epg_queries.py"),
            "--channels", "20", "--programmes-per-channel", "2"],
            text=True, capture_output=True, check=True)
        self.assertIn("20 channels; 40 programmes", result.stdout)
        self.assertIn("Query plan:", result.stdout)


if __name__ == "__main__":
    unittest.main()
