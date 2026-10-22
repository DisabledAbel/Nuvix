#!/usr/bin/env python3
"""Secret-free PR checks for the permanent production package and release gate.

These repository contracts catch accidental changes. Required code-owner review and
protected environments are the independent controls against deliberate policy edits.
"""
import argparse
import importlib.util
import json
import os
from pathlib import Path
import re
import subprocess
import xml.etree.ElementTree as ET

import yaml

ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location("identity_control", ROOT / "scripts/release-control.py")
control = importlib.util.module_from_spec(spec)
spec.loader.exec_module(control)
require = control.require
PRODUCTION_ID = "com.nuvix.player"
ANDROID = "{http://schemas.android.com/apk/res/android}"


def check_source(root=ROOT):
    gradle = (root / "app/build.gradle.kts").read_text()
    require(re.findall(r'^\s*applicationId = "([^"]+)"\s*$', gradle, re.M) == [PRODUCTION_ID],
            "Permanent production applicationId must be com.nuvix.player")
    require(control.release.APPLICATION_ID == PRODUCTION_ID, "Release verifier must retain permanent applicationId")
    require('signingConfig = if (signingConfigured) signingConfigs.getByName("upload") else null' in gradle,
            "Release builds must use the production key or stay unsigned")
    require(not re.search(r'signingConfigs(?:\.getByName\(|\[)"debug"', gradle), "Release must never use debug signing")
    for path in ("scripts/create-release.py", "scripts/release-control.py", "scripts/release-identity.py"):
        require((root / path).is_file(), "Required release verifier missing: " + path)
    manifest = ET.parse(root / "app/src/main/AndroidManifest.xml").getroot()
    app = manifest.find("application")
    require(app is not None and app.get(ANDROID + "name") == "app.opentv.OpenTvApp", "Application startup class changed")
    providers = app.findall("provider")
    require(any(p.get(ANDROID + "name") == "androidx.core.content.FileProvider" and
                p.get(ANDROID + "authorities") == "${applicationId}.fileprovider" and
                p.get(ANDROID + "exported") == "false" for p in providers), "FileProvider must follow applicationId")


def check_workflows(root=ROOT):
    paths = list((root / ".github/workflows").iterdir())
    require({p.name for p in paths} == {"build.yml", "release.yml", "pages.yml"},
            "Workflow inventory changed; review release capability before updating the allowlist")
    workflows = {p.name: yaml.load(p.read_text(), Loader=yaml.BaseLoader) for p in paths}
    for name, workflow in workflows.items():
        require("schedule" not in workflow.get("on", {}) and "pull_request_target" not in workflow.get("on", {}),
                "Scheduled/nightly or privileged PR workflows are prohibited")
        require(workflow.get("permissions", {}).get("contents") == "read", "Workflow default contents must be read-only")
        require("secrets." not in json.dumps(workflow.get("env", {})), "No global signing secrets")
        for job_id, job in workflow.get("jobs", {}).items():
            publisher = name == "release.yml" and job_id == "publish"
            require(not job.get("uses"), "Reusable workflows require explicit release-policy review")
            permissions = job.get("permissions", workflow["permissions"])
            require(permissions.get("contents", "none") == ("write" if publisher else "read"),
                    "Only the approved publisher may write repository contents")
            if not publisher:
                require("secrets." not in json.dumps(job), "Validation/PR jobs must not access secrets")
            for step in job.get("steps", []):
                command = step.get("run", "")
                require(publisher or not re.search(r'gh\s+release|action-gh-release|release-control\.py\s+(?:sign|publish)',
                                                  command + " " + step.get("uses", "")), "Alternate release publisher detected")
    release = workflows["release.yml"]
    require(set(release["on"]) == {"workflow_dispatch"}, "Release must be explicitly dispatched")
    require(release.get("concurrency") == {"group": "nuvix-release-publication", "cancel-in-progress": "false"},
            "Stable and RC publication must serialize")
    publisher = release["jobs"]["publish"]
    require(publisher.get("environment") == {"name": "production"} and
            publisher.get("needs") == ["validate", "security"], "Publisher must require production approval and CI")
    for job in release["jobs"].values():
        condition = job.get("if", "")
        require(condition == "github.repository == 'DisabledAbel/Nuvix' && github.ref == 'refs/heads/main' && github.event_name == 'workflow_dispatch'",
                "Release jobs must run only for manual main requests")
        checkout = [s for s in job["steps"] if s.get("uses", "").startswith("actions/checkout@")]
        require(len(checkout) == 1 and checkout[0].get("with", {}).get("ref") == "${{ github.sha }}" and
                checkout[0]["with"].get("persist-credentials") == "false", "Release must use exact commit without persisted credentials")
    steps = publisher["steps"]
    commands = [s for s in steps if s.get("run", "").startswith("python3 scripts/release-control.py ")]
    require([s["run"].split()[2] for s in commands] == ["verify", "sign", "publish"], "Keep all release verification stages in order")
    require(publisher.get("env", {}).get("EXPECTED_SIGNING_CERT_SHA256") == "${{ vars.SIGNING_CERT_SHA256 }}",
            "Production environment fingerprint is the independent trust baseline")
    require("GH_TOKEN" not in publisher.get("env", {}), "Publisher token must be step-scoped")
    require(not publisher.get("continue-on-error"), "Publisher failure must block publication")
    for step in commands:
        require(not step.get("if") and not step.get("continue-on-error"), "Release verification stages cannot be skipped")
        require(step.get("env", {}).get("GH_TOKEN") == "${{ github.token }}" and
                '--expected-plan-sha256 "$EXPECTED_PLAN_SHA256"' in step["run"], "Verify exact prepared artifacts before publication")
    for step in steps:
        require("EXPECTED_SIGNING_CERT_SHA256" not in step.get("env", {}), "Steps cannot override the independent signing baseline")
        uses = step.get("uses", "")
        if uses.split("@")[0] in ("actions/setup-java", "android-actions/setup-android"):
            require(re.fullmatch(r"[^@]+@[a-f0-9]{40}", uses), "Signing setup actions must use full commit pins")
        require("upload-artifact@" not in uses, "Signed material must not be uploaded as a CI artifact")
    for name in ("build.yml", "release.yml"):
        validate = workflows[name]["jobs"]["build" if name == "build.yml" else "validate"]
        require(not validate.get("if") if name == "build.yml" else True, "PR identity validation must run")
        require(not validate.get("continue-on-error"), "Validation failure must block publication")
        runs = [s.get("run", "") for s in validate["steps"]]
        inspect = 'python3 scripts/release-identity.py --metadata app/build/outputs/apk/release/output-metadata.json --android-sdk "$ANDROID_HOME"'
        if name == "build.yml":
            inspect += " --history"
        for required in ("python3 scripts/release-identity.py", inspect):
            matches = [s for s in validate["steps"] if s.get("run") == required]
            require(len(matches) == 1 and not matches[0].get("if") and not matches[0].get("continue-on-error"),
                    "PR and release identity checks must run directly and cannot be skipped")
        require(any(run.startswith("./gradlew ") and ":app:assembleRelease" in run for run in runs),
                "Validation must build the actual unsigned release APK")


def check_unsigned(metadata_path, tools, root=ROOT):
    metadata = json.loads(metadata_path.read_text())
    require(metadata.get("applicationId") == PRODUCTION_ID and len(metadata.get("elements", [])) == 1,
            "Expected one production APK in Gradle metadata")
    element = metadata["elements"][0]
    filename = element.get("outputFile", "")
    require(filename and Path(filename).name == filename and not element.get("filters"), "Expected a universal APK")
    gradle = (root / "app/build.gradle.kts").read_text()
    codes = re.findall(r'^\s*versionCode = ([0-9]+)\s*$', gradle, re.M)
    names = re.findall(r'^\s*versionName = "([^"]+)"\s*$', gradle, re.M)
    require(len(codes) == len(names) == 1, "Ambiguous committed version")
    code, version = int(codes[0]), names[0]
    require(type(element.get("versionCode")) is int and element["versionCode"] == code and
            element.get("versionName") == version and 1 <= code <= 2_147_483_647, "Generated version differs from committed version")
    apk = metadata_path.parent / filename
    require(apk.is_file() and apk.stat().st_size > 0, "Unsigned release APK missing")
    control.release.verify_package(apk, tools, PRODUCTION_ID, code, version)
    # No signing key (including the debug key) belongs in the validation job.
    result = subprocess.run([str(tools / "apksigner"), "verify", "--print-certs", str(apk)], capture_output=True)
    require(result.returncode != 0 and b"DOES NOT VERIFY" in result.stderr,
            "Validation release must be unsigned; signing is reserved for approved production")
    return version, code


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--metadata", type=Path)
    parser.add_argument("--android-sdk", type=Path)
    parser.add_argument("--history", action="store_true")
    args = parser.parse_args()
    # Fail if credentials accidentally leak into an ordinary PR job.
    require(not any(os.environ.get(n) for n in ("KEYSTORE_BASE64", "KEYSTORE_PATH", "KEYSTORE_PASSWORD", "KEY_ALIAS", "KEY_PASSWORD")),
            "Identity validation must run without signing credentials")
    check_source()
    check_workflows()
    if args.metadata:
        require(args.android_sdk is not None, "--android-sdk required for actual APK inspection")
        version, code = check_unsigned(args.metadata, args.android_sdk / "build-tools/36.0.0")
        if args.history:
            tag = control.version_tag(version, "rc" if "-rc." in version else "stable")
            control.check_history(tag, code, control.published_history())
    else:
        require(not args.history, "History validation requires inspected APK metadata")
    print("Production identity, unsigned build and release policy checks passed")


if __name__ == "__main__":
    main()
