#!/usr/bin/env python3
"""Authorized transport checks plus orchestration/ingestion of real Android playback tests."""
import argparse
import hashlib
import json
import os
import platform
from pathlib import Path
import subprocess
import tempfile
import time
import xml.etree.ElementTree as ET

from iptv.fixtures import generate, serve
from iptv.probe import Client, ProbeFailure, playlist, probe_hls, validate_url

ROOT = Path(__file__).resolve().parents[1]
ANDROID_SCENARIOS = {"hls-one", "hls-spare", "ts-unknown", "stalker-one", "rapid", "recovery", "providers", "dvr",
                     "auto-disconnect", "auto-stall", "auto-reconnect", "auto-offline", "auto-rate", "auto-slow"}
PHASES = {"SELECTED", "SUBMITTED", "CLOSE_STARTED", "CLOSE_COMPLETED", "RESERVATION_ACQUIRED",
          "MEDIA_SET", "PREPARE_STARTED", "FIRST_FRAME", "READY", "FAILED"}


def junit_failures(directory):
    """Extract fixed diagnostic codes from our test class; never print raw JUnit/logcat text."""
    methods = {"hlsOneConnectionSwitch": "hls-one", "hlsSpareConnectionSwitch": "hls-spare",
               "transportStreamUnknownAllowanceSwitch": "ts-unknown", "stalkerOneConnectionSwitch": "stalker-one",
               "rapidUpDownLatestRequestRenders": "rapid", "failedMediaThenExplicitRecoveryRendersNewChannel": "recovery",
               "syntheticProvidersPassProductionImportersAndAuthentication": "providers",
               "dvrCapturesSyntheticTransportAndStopsAtDeadline": "dvr",
               "automaticDisconnectRecoveryRenders": "auto-disconnect", "automaticStallRecoveryRenders": "auto-stall",
               "automaticControlledReconnectRenders": "auto-reconnect", "automaticOfflineRecoveryExhausts": "auto-offline",
               "automaticRateLimitRecoveryRenders": "auto-rate", "delayedResponseDoesNotReconnect": "auto-slow"}
    codes = {"Media3 must release the previous player": "previous_media3_not_released",
             "Stopping must release the replacement Media3 player": "stopped_media3_not_released",
             "Replacement must render synthetic blue video": "replacement_pixels_mismatch",
             "Initial channel must render synthetic red video": "initial_pixels_mismatch",
             "Local Media3 milestone timed out": "media3_milestone_timeout"}
    results = []
    for file in Path(directory).rglob("TEST-*.xml"):
        try:
            if file.stat().st_size > 10 * 1024 * 1024:
                continue
            root = ET.parse(file).getroot()
        except (OSError, ET.ParseError):
            continue
        for case in root.iter("testcase"):
            if case.get("classname") != "app.opentv.iptv.IptvPlaybackTest" or case.get("name") not in methods:
                continue
            failure = case.find("failure")
            if failure is None:
                failure = case.find("error")
            if failure is None:
                continue
            private_text = failure.get("message", "") + (failure.text or "")
            code = next((value for pattern, value in codes.items() if pattern in private_text), "test_assertion_or_error")
            results.append({"id": methods[case.get("name")], "classification": code})
    return results


def fixture_checks(timeout):
    results = []
    with tempfile.TemporaryDirectory(prefix="nuvix-iptv-") as directory:
        generate(directory)
        with serve(directory) as server:
            started = time.monotonic()
            client = Client(timeout, server.base)
            body, _ = client.get(server.base + "/playlist.m3u")
            entries = playlist(body.decode())
            for url in entries:
                validate_url(url, server.base)
            results.append({"id": "playlist", "status": "passed", "channels": len(entries),
                            "duration_ms": round((time.monotonic() - started) * 1000, 3)})
            body, _ = client.get(server.base + "/epg.xml")
            assert len(ET.fromstring(body).findall("programme")) == 1
            results.append({"id": "xmltv", "status": "passed"})
            for channel in ("a", "b"):
                stats = probe_hls(server.base + f"/{channel}/index.m3u8", Client(timeout, server.base))
                results.append({"id": f"hls-{channel}", "status": "passed", **stats})
            data, _ = client.get(server.base + "/b.ts", limit=256 * 1024, segment=True)
            assert data[0] == 0x47
            results.append({"id": "transport-stream", "status": "passed", "bytes_checked": len(data)})
    return results


def public_checks(timeout):
    manifest = json.loads((ROOT / "scripts/iptv/streams.json").read_text())
    results = []
    for fixture in manifest["stable_public"] + manifest["optional_live"]:
        started = time.monotonic()
        try:
            stats = probe_hls(fixture["url"], Client(timeout))
            result = {"status": "passed", **stats}
        except ProbeFailure as error:
            result = {"status": "external_unavailable", "classification": str(error)}
        results.append({"id": fixture["id"], "duration_ms": round((time.monotonic() - started) * 1000, 3), **result})
    return results


def android_results(directory):
    """Accept only bounded numbers and fixed phases, never raw device log text or URLs."""
    files = sorted(Path(directory).glob("*.json"))
    if not files:
        raise ProbeFailure("android_report_missing")
    results = []
    for path in files:
        if path.stat().st_size > 512 * 1024:
            raise ProbeFailure("android_report_invalid")
        data = json.loads(path.read_text())
        if data.get("schema_version") != 1 or data.get("suite") != "media3-local-v1":
            raise ProbeFailure("android_report_invalid")
        for row in data["results"]:
            scenario = row["scenario"]
            if scenario not in ANDROID_SCENARIOS:
                raise ProbeFailure("android_report_invalid")
            metrics = {}
            for key, value in row.get("metrics", {}).items():
                if key not in PHASES | {"import_ms", "provider_ms", "release_ms", "prepare_ms", "request_ms",
                                       "reservations", "failures", "media3_state", "rendered_frames", "error_code", "surface_bound", "attempts", "recovery_ms", "stall_detected"}:
                    raise ProbeFailure("android_report_invalid")
                if type(value) not in (int, float) or not 0 <= value <= 900000:
                    raise ProbeFailure("android_report_invalid")
                metrics[key] = value
            results.append({"id": scenario, "status": "passed" if row["passed"] is True else "application_regression",
                            "metrics": metrics})
    if not results:
        raise ProbeFailure("android_report_missing")
    required = ANDROID_SCENARIOS
    if {row["id"] for row in results} != required or len(results) != len(required):
        raise ProbeFailure("android_report_incomplete")
    for row in results:
        if row["status"] == "passed" and row["id"].startswith("auto-"):
            if not {"attempts", "rendered_frames", "recovery_ms", "reservations"} <= row["metrics"].keys():
                raise ProbeFailure("android_report_incomplete")
            if row["id"] != "auto-offline" and row["metrics"]["rendered_frames"] <= 0:
                raise ProbeFailure("android_report_incomplete")
            if row["id"] == "auto-stall" and row["metrics"].get("stall_detected") != 1:
                raise ProbeFailure("android_report_incomplete")
        if row["status"] == "passed" and row["id"] in {"hls-one", "hls-spare", "ts-unknown", "stalker-one"} and not {"FIRST_FRAME", "READY", "release_ms"} <= row["metrics"].keys():
            raise ProbeFailure("android_report_incomplete")
    return results


def fingerprint():
    digest = hashlib.sha256()
    digest.update(Path(__file__).read_bytes())
    for file in sorted((ROOT / "scripts/iptv").glob("*.py")):
        digest.update(file.read_bytes())
    for file in sorted((ROOT / "app/src/androidTest").rglob("*.kt")):
        digest.update(file.read_bytes())
    try:
        media_generator = subprocess.run(["ffmpeg", "-version"], capture_output=True, text=True,
                                         check=True, timeout=3).stdout.splitlines()[0]
    except (OSError, subprocess.SubprocessError, IndexError):
        media_generator = "unavailable"
    cpu = platform.processor()
    if Path("/proc/cpuinfo").is_file():
        cpu = next((line.split(":", 1)[1].strip() for line in Path("/proc/cpuinfo").read_text().splitlines()
                    if line.startswith("model name")), cpu)
    return {"platform": platform.system(), "machine": platform.machine(), "fixtures": digest.hexdigest(),
            "android": "api-35-x86_64-default-320x180-v1",
            "cpu": cpu, "generator": media_generator, "runner_image": os.environ.get("ImageVersion", "local")}


def comparison(report, baseline):
    if baseline.get("status") != "passed" or baseline.get("environment") != report["environment"]:
        return {"status": "not_comparable"}
    previous = {(group, row["id"]): row for group in ("fixture", "public", "android")
                for row in baseline.get(group, []) if row.get("status") == "passed"}
    changes = []
    for group in ("fixture", "public", "android"):
        for row in report[group]:
            old = previous.get((group, row["id"]), {})
            for metric, value in row.get("metrics", {}).items():
                before = old.get("metrics", {}).get(metric)
                if before is not None and row["status"] == "passed":
                    changes.append({"group": group, "id": row["id"], "metric": metric,
                                    "previous": before, "current": value, "delta": round(value - before, 3)})
    return {"status": "comparable", "changes": changes,
            "note": "Observations only; shared runners and public networks do not imply timing guarantees."}


def markdown(report):
    lines = ["# IPTV Playback Tests", "", f"Result: **{report['status']}**", "",
             "| Group | Scenario | Status | Measurements |", "| --- | --- | --- | --- |"]
    for group in ("fixture", "public", "android"):
        for row in report[group]:
            metrics = dict(row.get("metrics", {}))
            for key in ("duration_ms", "requests", "bytes_checked", "segments", "manifests", "channels"):
                if key in row:
                    metrics[key] = row[key]
            detail = ", ".join(f"{key}={value}" for key, value in metrics.items())
            if row.get("classification"):
                detail = ", ".join(filter(None, (row["classification"], detail)))
            lines.append(f"| {group} | {row['id']} | {row['status']} | {detail} |")
    if not report["android"]:
        lines += ["", "Real Media3 decoding: **not run in this invocation**. The emulator CI job is required on PRs."]
    lines += ["", "Public results measure bounded transport availability separately from app playback.",
              "Only synthetic local media is recorded. No URL, credential, or raw exception text is included.",
              "", f"Baseline comparison: {report['comparison']['status']}."]
    for row in report["comparison"].get("changes", []):
        lines.append(f"- {row['id']} {row['metric']}: {row['previous']} → {row['current']} ({row['delta']:+}).")
    for row in report.get("test_failures", []):
        lines.append(f"- Failed device case: {row['id']} ({row['classification']}).")
    return "\n".join(lines) + "\n"


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--mode", choices=("fixture", "public", "all"), default="fixture")
    parser.add_argument("--output", type=Path, default=Path("work/iptv-report.json"))
    parser.add_argument("--timeout", type=float, default=10, help="HTTP timeout in seconds (0 < value <= 30)")
    parser.add_argument("--android-report-dir", type=Path)
    parser.add_argument("--android-junit-dir", type=Path, help="Extract only fixed failure codes for this suite")
    parser.add_argument("--android-exit-code", type=int, default=0, help="Propagate the instrumentation command result into JSON")
    parser.add_argument("--run-android", action="store_true", help="Run connectedDebugAndroidTest against an already started emulator")
    parser.add_argument("--baseline", type=Path)
    args = parser.parse_args(argv)
    if not 0 < args.timeout <= 30:
        parser.error("--timeout must be greater than zero and at most 30")
    report = {"schema_version": 1, "environment": fingerprint(), "fixture": [], "public": [], "android": [],
              "status": "passed", "comparison": {"status": "no_baseline"}}
    if args.mode in ("fixture", "all"):
        try:
            report["fixture"] = fixture_checks(args.timeout)
        except (OSError, AssertionError, subprocess.SubprocessError, ProbeFailure, ET.ParseError, UnicodeError):
            report["fixture"] = [{"id": "local-fixtures", "status": "fixture_failure"}]
            report["status"] = "failed"
    if args.mode in ("public", "all"):
        report["public"] = public_checks(args.timeout)
    if args.run_android:
        try:
            subprocess.run(["bash", str(ROOT / "scripts/run-iptv-android.sh")],
                           cwd=ROOT, check=True, timeout=900, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        except (OSError, subprocess.SubprocessError):
            report["android"] = [{"id": "instrumentation", "status": "application_regression"}]
            report["status"] = "failed"
        if not args.android_report_dir:
            args.android_report_dir = ROOT / "work/iptv-device/iptv-reports"
    if args.android_report_dir:
        try:
            report["android"] += android_results(args.android_report_dir)
            if any(row["status"] != "passed" for row in report["android"]):
                report["status"] = "failed"
        except (OSError, ValueError, KeyError, TypeError, ProbeFailure):
            report["android"].append({"id": "instrumentation-report", "status": "application_regression",
                                      "classification": "android_report_invalid_or_missing"})
            report["status"] = "failed"
    elif args.run_android:
        report["android"].append({"id": "instrumentation", "status": "not_collected"})
    if args.android_exit_code:
        report["android"].append({"id": "instrumentation", "status": "application_regression",
                                  "classification": "instrumentation_command_failed"})
        report["status"] = "failed"
    if args.android_junit_dir:
        report["test_failures"] = junit_failures(args.android_junit_dir)
    if args.baseline and args.baseline.is_file():
        try:
            report["comparison"] = comparison(report, json.loads(args.baseline.read_text()))
        except (ValueError, KeyError, TypeError):
            report["comparison"] = {"status": "invalid_baseline"}
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(report, indent=2) + "\n")
    args.output.with_suffix(".md").write_text(markdown(report))
    print(f"IPTV checks: {report['status']}; sanitized JSON and Markdown reports written.")
    for row in report["android"]:
        print("Media3 result: " + json.dumps(row, sort_keys=True))
    for row in report.get("test_failures", []):
        print(f"Device case failed: {row['id']} ({row['classification']})")
    return 0 if report["status"] == "passed" else 1


if __name__ == "__main__":
    raise SystemExit(main())
