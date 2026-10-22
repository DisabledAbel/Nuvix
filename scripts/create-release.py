#!/usr/bin/env python3
"""Verify a signed APK and generate Nuvix's versionCode-based update manifest."""
from __future__ import annotations

import argparse
import hashlib
import json
import re
import shutil
import subprocess
from pathlib import Path

APPLICATION_ID = "com.nuvix.player"
LEGACY_APPLICATION_ID = "com.disabledabel.nuvix"


def run(*command: str) -> str:
    return subprocess.run(command, check=True, capture_output=True, text=True).stdout


def verify_package(apk: Path, tools: Path, application_id: str, version_code: int, version_name: str):
    """Inspect the actual binary manifest independently of Gradle's output metadata."""
    badging = run(str(tools / "aapt"), "dump", "badging", str(apk))
    package = re.search(r"^package: name='([^']+)' versionCode='(\d+)' versionName='([^']+)'", badging, re.M)
    if not package or package.groups() != (application_id, str(version_code), version_name):
        raise ValueError("APK package identity or version does not match release metadata")
    if re.search(r"^application-debuggable", badging, re.M):
        raise ValueError("A debuggable APK cannot be published")


def verify_apk(apk: Path, tools: Path, metadata: dict, tag: str, expected_certificate: str,
               channel: str = "stable") -> dict:
    if not re.fullmatch(r"[0-9a-fA-F]{64}", expected_certificate):
        raise ValueError("A configured release certificate SHA-256 fingerprint is required")
    application_id = APPLICATION_ID
    if channel == "nightly":
        match = re.fullmatch(r"nightly-(\d{8}\.\d+\.\d+)", tag)
        if not match:
            raise ValueError("A nightly-YYYYMMDD.RUN.ATTEMPT release tag is required")
        application_id = LEGACY_APPLICATION_ID + ".nightly"
    elif channel not in ("stable", "rc") or not re.fullmatch(
        r"v(?:0|[1-9][0-9]*)\.(?:0|[1-9][0-9]*)\.(?:0|[1-9][0-9]*)" +
        (r"-rc\.[1-9][0-9]*" if channel == "rc" else ""), tag, re.ASCII
    ):
        raise ValueError("A matching stable vX.Y.Z or RC vX.Y.Z-rc.N release tag is required")
    if metadata.get("applicationId") != application_id:
        raise ValueError("Release metadata has the wrong application ID")
    elements = metadata.get("elements", [])
    if len(elements) != 1:
        raise ValueError("Exactly one universal release APK is required")
    element = elements[0]
    if element.get("filters"):
        raise ValueError("A universal APK without ABI or density filters is required")
    version_code = element.get("versionCode")
    version_name = element.get("versionName")
    if type(version_code) is not int or not 1 <= version_code <= 2_147_483_647:
        raise ValueError("An Android versionCode between 1 and Int.MAX_VALUE is required")
    if not isinstance(version_name, str) or not 1 <= len(version_name) <= 128:
        raise ValueError("Invalid release versionName")
    version_matches = (
        re.fullmatch(r"\d+\.\d+\.\d+-nightly\." + re.escape(match[1]), version_name)
        if channel == "nightly" else version_name == tag[1:]
    )
    if not version_matches:
        raise ValueError("The release tag must match the APK versionName")
    if not apk.is_file() or not 1 <= apk.stat().st_size <= 512 * 1024 * 1024:
        raise ValueError("The release APK is missing, empty or exceeds 512 MiB")

    verify_package(apk, tools, application_id, version_code, version_name)

    signature = run(str(tools / "apksigner"), "verify", "--verbose", "--print-certs", str(apk))
    if "Android Debug" in signature:
        raise ValueError("The Android debug signing certificate cannot be published")
    certificates = re.findall(r"^Signer #\d+ certificate SHA-256 digest: ([0-9a-fA-F]{64})$", signature, re.M)
    if len(certificates) != 1:
        raise ValueError("Exactly one verified APK signing certificate is required")

    if certificates[0].lower() != expected_certificate.lower():
        raise ValueError("APK certificate differs from the configured long-lived release signing key")

    with apk.open("rb") as source:
        digest = hashlib.file_digest(source, "sha256").hexdigest()
    return {
        "schemaVersion": 1,
        "versionCode": version_code,
        "versionName": version_name,
        "applicationId": application_id,
        "apkAsset": "nuvix.apk",
        "sha256": digest,
        "sizeBytes": apk.stat().st_size,
        "signingCertificateSha256": certificates[0].lower(),
    }


def release_notes(changelog: Path, version: str) -> str:
    text = changelog.read_text() if changelog.exists() else ""
    lines = text.splitlines()
    selected: list[str] = []
    active = False
    for line in lines:
        if line.startswith("## "):
            if active:
                break
            active = bool(re.match(r"^## \[?" + re.escape(version) + r"\]?(?:\s|$)", line))
        elif active:
            selected.append(line)
    notes = "\n".join(selected).strip() or f"Nuvix {version}. See the attached source and repository changelog."
    return notes + "\n\nThe corresponding GPL-3.0-or-later source archive is attached to this release. " \
        "Nuvix is an independent modified version of OpenTV, with no official affiliation with OpenTV or DIRECTV.\n\n" \
        "Requires Android 6.0 (API 23) or newer. Install nuvix.apk using Android's installation prompt; " \
        "updates retain app data only with the same package/signing key and a higher versionCode. " \
        "Verify SHA256SUMS and the independently recorded signing certificate before installing. " \
        "Release candidates are experimental and are not offered to stable users.\n\n" \
        "Nuvix does not provide IPTV channels, playlists, credentials, or copyrighted media. " \
        "Use authorized sources. Playback and recording support depends on the device and provider.\n"


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--tag", required=True)
    parser.add_argument("--channel", choices=("stable", "rc"), default="stable")
    parser.add_argument("--android-sdk", type=Path, required=True)
    parser.add_argument("--expected-certificate-sha256", required=True)
    parser.add_argument("--output", type=Path, default=Path("dist"))
    parser.add_argument("--metadata", type=Path, default=Path("app/build/outputs/apk/release/output-metadata.json"))
    parser.add_argument("--changelog", type=Path, default=Path("CHANGELOG.md"))
    args = parser.parse_args()
    metadata = json.loads(args.metadata.read_text())
    elements = metadata.get("elements", [])
    if len(elements) != 1:
        raise ValueError("Exactly one universal release APK is required")
    filename = elements[0]["outputFile"]
    if Path(filename).name != filename:
        raise ValueError("Unexpected APK output path")
    apk = args.metadata.parent / filename
    # Match the explicitly configured buildToolsVersion, independently of compileSdk.
    tools = args.android_sdk / "build-tools" / "36.0.0"
    manifest = verify_apk(apk, tools, metadata, args.tag, args.expected_certificate_sha256, args.channel)
    args.output.mkdir(parents=True, exist_ok=True)
    shutil.copyfile(apk, args.output / "nuvix.apk")
    (args.output / "update.json").write_text(json.dumps(manifest, indent=2) + "\n")
    (args.output / "SHA256SUMS").write_text(f"{manifest['sha256']}  nuvix.apk\n")
    (args.output / "release-notes.md").write_text(release_notes(args.changelog, manifest["versionName"]))
    print(f"Verified {manifest['applicationId']} versionCode {manifest['versionCode']}; release assets written to {args.output}")


if __name__ == "__main__":
    main()
