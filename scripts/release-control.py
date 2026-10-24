#!/usr/bin/env python3
"""Fail-closed release policy and atomic draft-to-public publication (manual runs only)."""
from __future__ import annotations

import argparse
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import re
import subprocess
from urllib.parse import quote

ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location("create_release", ROOT / "scripts/create-release.py")
release = importlib.util.module_from_spec(spec)
spec.loader.exec_module(release)
REPO = "DisabledAbel/Nuvix"
BASE = f"repos/{REPO}"


def require(condition, message):
    if not condition:
        raise ValueError(message)


def version_tag(version, channel):
    number = r"(?:0|[1-9][0-9]*)"
    suffix = r"-rc\.[1-9][0-9]*" if channel == "rc" else ""
    require(channel in ("rc", "stable") and len(version) <= 100 and
            re.fullmatch(rf"{number}\.{number}\.{number}{suffix}", version, re.ASCII),
            "Version must be X.Y.Z for stable or X.Y.Z-rc.N for rc (N >= 1; no leading zeros). "
            "Enter the committed versionName without a leading 'v'; "
            "the release tag adds that prefix automatically.")
    return "v" + version


def api(path, *, method="GET", data=None, missing=False, binary=False):
    """Use only repository-relative API paths; never execute shell input or follow asset URLs."""
    require(path.startswith(BASE + "/"), "Unexpected API path")
    args = ["gh", "api", path, "--method", method]
    if binary:
        args += ["-H", "Accept: application/octet-stream"]
    if data is not None:
        args += ["--input", "-"]
    result = subprocess.run(args, input=json.dumps(data).encode() if data is not None else None,
                            capture_output=True)
    if result.returncode:
        if missing and b"(HTTP 404)" in result.stderr:
            return None
        # Report only the status, never raw API responses or tokens.
        status = re.search(rb"\(HTTP ([0-9]{3})\)", result.stderr)
        detail = f" (HTTP {status[1].decode('ascii')})" if status else ""
        raise RuntimeError(f"GitHub API {method} {path.split('?')[0]} failed{detail}; check permissions/service status")
    return result.stdout if binary else json.loads(result.stdout or b"null")


def pages(path):
    output = []
    for page in range(1, 1001):
        values = api(f"{path}?per_page=100&page={page}")
        require(isinstance(values, list), "Expected API collection")
        output.extend(values)
        if len(values) < 100:
            return output
    raise ValueError("API pagination limit exceeded; refusing incomplete history")


def check_environment(environment, policies):
    rules = environment.get("protection_rules", [])
    reviewers = next((r for r in rules if r.get("type") == "required_reviewers"), {})
    require(reviewers.get("reviewers") and reviewers.get("prevent_self_review") is True,
            "production requires required reviewers and Prevent self-review")
    require(environment.get("can_admins_bypass") is False,
            "Disable administrator bypass for production")
    restriction = environment.get("deployment_branch_policy") or {}
    require(restriction.get("custom_branch_policies") is True and
            restriction.get("protected_branches") is False,
            "production must use selected deployment branches/tags")
    require([(p.get("type"), p.get("name")) for p in policies] == [("branch", "main")],
            "production must allow only branch main, with no tag deployment rules")


def check_approval(reviews, environment_id, initiator):
    relevant = [r for r in reviews if any(e.get("id") == environment_id for e in r.get("environments", []))]
    require(relevant and not any(r.get("state") == "rejected" for r in relevant),
            "No unrejected production approval is recorded for this run")
    approvers = [r["user"]["login"] for r in relevant if r.get("state") == "approved"
                 and r.get("user", {}).get("type") == "User"
                 and r["user"]["login"].lower() != initiator.lower()]
    require(approvers, "Production requires approval by another authorized human")
    return approvers


def approved_run():
    environment = api(f"{BASE}/environments/production")
    reviews = api(f"{BASE}/actions/runs/{int(os.environ['GITHUB_RUN_ID'])}/approvals")
    approvers = check_approval(reviews, environment["id"], os.environ["GITHUB_ACTOR"])
    authorized = {r["reviewer"]["login"].lower() for rule in environment["protection_rules"]
                  if rule["type"] == "required_reviewers" for r in rule["reviewers"] if r["type"] == "User"}
    for login in approvers:
        permission = api(f"{BASE}/collaborators/{quote(login, safe='')}/permission")
        if login.lower() in authorized and permission.get("permission") in ("admin", "maintain", "write"):
            return
    raise ValueError("Production approval must come from a configured user reviewer with repository write access")


def check_context(env, run):
    require(env.get("GITHUB_REPOSITORY") == REPO and env.get("GITHUB_EVENT_NAME") == "workflow_dispatch"
            and env.get("GITHUB_REF") == "refs/heads/main", "Release dispatch must target this repository's main")
    require(env.get("GITHUB_RUN_ATTEMPT") == "1", "Redispatch instead of rerunning a release")
    require(run.get("event") == "workflow_dispatch" and run.get("head_branch") == "main"
            and run.get("head_sha") == env.get("GITHUB_SHA")
            and run.get("status") == "in_progress" and run.get("conclusion") is None,
            "Run canceled, completed or inconsistent with the requested commit")


def source_metadata(version):
    gradle = (ROOT / "app/build.gradle.kts").read_text()
    names = re.findall(r'^\s*versionName = "([^"]+)"\s*$', gradle, re.M)
    codes = re.findall(r'^\s*versionCode = ([0-9]+)\s*$', gradle, re.M)
    require(names == [version] and len(codes) == 1, "Commit versionName must match dispatch version")
    code = int(codes[0])
    require(1 <= code <= 2_147_483_647, "Invalid Android versionCode")
    changelog = ROOT / "CHANGELOG.md"
    require(changelog.is_file(), "CHANGELOG.md is required before release approval")
    require(re.search(r"^## \[?" + re.escape(version) + r"\]?(?:\s|$)", changelog.read_text(), re.M),
            "CHANGELOG.md must contain a heading for the requested release version")
    return code


def version_key(tag):
    match = re.fullmatch(r"v(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)(?:-rc\.([1-9][0-9]*))?", tag, re.ASCII)
    require(match, "Published release has an invalid stable/RC tag")
    return (*map(int, match.group(1, 2, 3)), 0 if match[4] else 1, int(match[4] or 0))


def check_history(tag, code, history, expected_certificate=None):
    current = version_key(tag)
    for item, metadata in history:
        require(item.get("tag_name") != tag, "Release version already exists (including drafts)")
        if item.get("draft") or item.get("tag_name", "").startswith("nightly-"):
            continue
        require(isinstance(metadata, dict) and metadata.get("applicationId") in
                (release.APPLICATION_ID, release.LEGACY_APPLICATION_ID) and
                type(metadata.get("versionCode")) is int and 1 <= metadata["versionCode"] <= 2_147_483_647,
                "Published release has missing/invalid update metadata")
        previous = version_key(item.get("tag_name", ""))
        require(metadata.get("versionName") == item["tag_name"][1:], "Published tag and update versionName differ")
        # A different Android package cannot be an in-place upgrade predecessor.
        # Still validate its metadata; never skip unknown or malformed history.
        if metadata["applicationId"] == release.LEGACY_APPLICATION_ID:
            continue
        require(code > metadata["versionCode"], "versionCode must exceed every published stable and RC version")
        require(current > previous, "Release version must follow every published stable and RC version")
        if expected_certificate is not None:
            require(metadata.get("signingCertificateSha256", "").lower() == expected_certificate.lower(),
                    "Published production certificate differs from the independently configured signing baseline")


def published_history():
    history = []
    for item in pages(f"{BASE}/releases"):
        metadata = None
        if not item.get("draft") and not item.get("tag_name", "").startswith("nightly-"):
            assets = [a for a in item.get("assets", []) if a["name"] == "update.json"]
            require(len(assets) == 1 and 1 <= assets[0]["size"] <= 16384, "Published release lacks bounded update metadata")
            metadata = json.loads(api(f"{BASE}/releases/assets/{int(assets[0]['id'])}", binary=True))
        history.append((item, metadata))
    return history


def preflight(version, channel):
    tag = version_tag(version, channel)
    env = os.environ
    run = api(f"{BASE}/actions/runs/{int(env['GITHUB_RUN_ID'])}")
    check_context(env, run)
    permission = api(f"{BASE}/collaborators/{quote(env['GITHUB_TRIGGERING_ACTOR'], safe='')}/permission")
    require(permission.get("permission") in ("admin", "maintain", "write"), "Release actor needs repository write access")
    head = api(f"{BASE}/git/ref/heads/main")["object"]["sha"]
    require(head == env["GITHUB_SHA"] == release.run("git", "rev-parse", "HEAD").strip(),
            "main changed; dispatch a fresh release from the reviewed commit")
    environment = api(f"{BASE}/environments/production")
    policies = api(f"{BASE}/environments/production/deployment-branch-policies")["branch_policies"]
    check_environment(environment, policies)
    require(api(f"{BASE}/git/ref/tags/{tag}", missing=True) is None, "Tag already exists; never overwrite it")
    code = source_metadata(version)
    check_history(tag, code, published_history(), os.environ.get("EXPECTED_SIGNING_CERT_SHA256"))
    return tag, code


def digest(path):
    with path.open("rb") as source:
        return hashlib.file_digest(source, "sha256").hexdigest()


def prepare(version, channel):
    tag, code = preflight(version, channel)
    directory = ROOT / "app/build/outputs/apk/release"
    metadata = json.loads((directory / "output-metadata.json").read_text())
    elements = metadata.get("elements", [])
    require(metadata.get("applicationId") == release.APPLICATION_ID and len(elements) == 1,
            "Expected one release APK for the production application")
    element = elements[0]
    filename = element["outputFile"]
    require(Path(filename).name == filename and not element.get("filters") and
            element.get("versionCode") == code and element.get("versionName") == version,
            "Build metadata must match committed version and universal APK")
    output = ROOT / "prepared"
    output.mkdir()
    import shutil
    shutil.copyfile(directory / filename, output / "unsigned.apk")
    shutil.copyfile(directory / "output-metadata.json", output / "output-metadata.json")
    plan = {"tag": tag, "channel": channel, "commit": os.environ["GITHUB_SHA"], "versionCode": code,
            "unsignedSha256": digest(output / "unsigned.apk"),
            "metadataSha256": digest(output / "output-metadata.json")}
    (output / "plan.json").write_text(json.dumps(plan, sort_keys=True) + "\n")
    # Bind artifacts to this run's read-only validation job, not to a caller-supplied run ID.
    with open(os.environ["GITHUB_OUTPUT"], "a") as stream:
        stream.write(f"plan_sha256={digest(output / 'plan.json')}\n")
    with open(os.environ["GITHUB_STEP_SUMMARY"], "a") as stream:
        stream.write(f"Prepared {tag}, versionCode {code}, commit `{plan['commit']}`. "
                     "Unsigned artifact only. Production approval is required to sign and publish.\n")


def verify_prepared(version, channel, expected):
    tag, code = preflight(version, channel)
    approved_run()
    directory = ROOT / "prepared"
    require(re.fullmatch(r"[a-f0-9]{64}", expected), "Missing validation-job artifact digest")
    require(digest(directory / "plan.json") == expected, "Prepared plan checksum mismatch")
    plan = json.loads((directory / "plan.json").read_text())
    require(plan == {"tag": tag, "channel": channel, "commit": os.environ["GITHUB_SHA"], "versionCode": code,
                     "unsignedSha256": digest(directory / "unsigned.apk"),
                     "metadataSha256": digest(directory / "output-metadata.json")},
            "Artifact identity/checksum mismatch")
    return tag, code


def sign(version, channel, expected):
    tag, _ = verify_prepared(version, channel, expected)
    names = ("KEYSTORE_BASE64", "KEYSTORE_PASSWORD", "KEY_ALIAS", "KEY_PASSWORD")
    require(all(os.environ.get(name, "").strip() for name in names), "Required signing credentials missing")
    fingerprint = os.environ.get("EXPECTED_SIGNING_CERT_SHA256", "")
    require(re.fullmatch(r"[a-fA-F0-9]{64}", fingerprint), "Missing approved signing certificate fingerprint")
    import base64
    tools = Path(os.environ["ANDROID_HOME"]) / "build-tools/36.0.0"
    keystore = Path(os.environ["RUNNER_TEMP"]) / "nuvix-release.jks"
    signed = ROOT / "prepared/signed.apk"
    try:
        with keystore.open("xb") as stream:
            keystore.chmod(0o600)
            stream.write(base64.b64decode("".join(os.environ["KEYSTORE_BASE64"].split()), validate=True))
        # Password values never appear in argv or logs. apksigner output is captured.
        release.run(str(tools / "apksigner"), "sign", "--ks", str(keystore),
                    "--ks-key-alias", os.environ["KEY_ALIAS"], "--ks-pass", "env:KEYSTORE_PASSWORD",
                    "--key-pass", "env:KEY_PASSWORD", "--out", str(signed), str(ROOT / "prepared/unsigned.apk"))
        metadata = json.loads((ROOT / "prepared/output-metadata.json").read_text())
        manifest = release.verify_apk(signed, tools, metadata, tag, fingerprint, channel)
        output = ROOT / "dist"
        output.mkdir()
        import shutil
        shutil.copyfile(signed, output / "nuvix.apk")
        (output / "update.json").write_text(json.dumps(manifest, indent=2) + "\n")
        (output / "release-notes.md").write_text(release.release_notes(ROOT / "CHANGELOG.md", version))
        with (output / f"nuvix-{tag}-source.tar.gz").open("wb") as stream:
            subprocess.run(["git", "archive", "--format=tar.gz", f"--prefix=nuvix-{tag}/", "HEAD"],
                           stdout=stream, check=True)
        files = sorted(output.iterdir())
        (output / "SHA256SUMS").write_text("".join(f"{digest(p)}  {p.name}\n" for p in files))
    finally:
        keystore.unlink(missing_ok=True)


def verify_distribution(version, channel):
    tag = version_tag(version, channel)
    output = ROOT / "dist"
    expected_names = {"nuvix.apk", "update.json", "release-notes.md", f"nuvix-{tag}-source.tar.gz"}
    lines = (output / "SHA256SUMS").read_text().splitlines()
    require(len(lines) == len(expected_names), "Unexpected checksum manifest")
    seen = set()
    for line in lines:
        match = re.fullmatch(r"([a-f0-9]{64})  ([a-zA-Z0-9.\-]+)", line)
        require(match and match[2] in expected_names and match[2] not in seen, "Unsafe/duplicate checksum entry")
        seen.add(match[2])
        require(digest(output / match[2]) == match[1], "Distribution checksum mismatch")
    metadata = json.loads((ROOT / "prepared/output-metadata.json").read_text())
    manifest = release.verify_apk(output / "nuvix.apk", Path(os.environ["ANDROID_HOME"]) / "build-tools/36.0.0",
                                  metadata, tag, os.environ["EXPECTED_SIGNING_CERT_SHA256"], channel)
    require(manifest == json.loads((output / "update.json").read_text()), "Signed APK manifest mismatch")


def publish(version, channel, expected):
    tag, _ = verify_prepared(version, channel, expected)
    verify_distribution(version, channel)
    # From here onward, failures reserve this version. No automatic delete, overwrite or retry.
    api(f"{BASE}/git/refs", method="POST", data={"ref": f"refs/tags/{tag}", "sha": os.environ["GITHUB_SHA"]})
    draft = api(f"{BASE}/releases", method="POST", data={"tag_name": tag,
                "target_commitish": os.environ["GITHUB_SHA"],
                "name": os.environ.get("RELEASE_NAME") or f"Nuvix {version}",
                "body": (ROOT / "dist/release-notes.md").read_text(), "draft": True,
                "prerelease": channel == "rc", "make_latest": "false"})
    release_id = int(draft["id"])
    assets = sorted((ROOT / "dist").iterdir())
    # gh release upload is passed explicit paths and never --clobber. No shell interpolation.
    subprocess.run(["gh", "release", "upload", tag, "--repo", REPO, *map(str, assets)], check=True,
                   capture_output=True)
    uploaded = api(f"{BASE}/releases/{release_id}")
    require(uploaded["draft"] and uploaded["prerelease"] == (channel == "rc") and uploaded["tag_name"] == tag,
            "Draft release identity changed")
    remote = {a["name"]: a for a in uploaded["assets"]}
    require(len(remote) == len(uploaded["assets"]) and set(remote) == {p.name for p in assets},
            "Release assets incomplete or duplicated")
    for path in assets:
        asset = remote[path.name]
        require(asset["state"] == "uploaded" and asset["size"] == path.stat().st_size and
                asset.get("digest") == "sha256:" + digest(path), "Uploaded asset checksum mismatch")
    # Check cancellation, main movement and environment protection once more before going public.
    check_context(os.environ, api(f"{BASE}/actions/runs/{int(os.environ['GITHUB_RUN_ID'])}"))
    require(api(f"{BASE}/git/ref/heads/main")["object"]["sha"] == os.environ["GITHUB_SHA"], "main changed during publishing")
    require(api(f"{BASE}/git/ref/tags/{tag}")["object"]["sha"] == os.environ["GITHUB_SHA"], "Release tag moved")
    check_environment(api(f"{BASE}/environments/production"),
                      api(f"{BASE}/environments/production/deployment-branch-policies")["branch_policies"])
    approved_run()
    result = api(f"{BASE}/releases/{release_id}", method="PATCH", data={"draft": False,
                 "make_latest": "true" if channel == "stable" else "false"})
    with open(os.environ["GITHUB_STEP_SUMMARY"], "a") as stream:
        stream.write(f"Published [{tag}]({result['html_url']}) from `{os.environ['GITHUB_SHA']}` after production approval.\n")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("command", choices=("preflight", "prepare", "verify", "sign", "publish"))
    parser.add_argument("--version", required=True)
    parser.add_argument("--channel", choices=("stable", "rc"), required=True)
    parser.add_argument("--expected-plan-sha256", default="")
    args = parser.parse_args()
    if args.command == "preflight":
        preflight(args.version, args.channel)
    elif args.command == "prepare":
        prepare(args.version, args.channel)
    else:
        {"verify": verify_prepared, "sign": sign, "publish": publish}[args.command](
            args.version, args.channel, args.expected_plan_sha256)


if __name__ == "__main__":
    try:
        main()
    except subprocess.CalledProcessError:
        raise SystemExit("Release tool failed; publication stopped. Inspect run stage; never overwrite a reserved version.")
