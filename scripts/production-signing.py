#!/usr/bin/env python3
"""Fail-closed preparation for first production signing; no key-generation path.

Only public configuration and secret *names* are inspected. No secret value is
requested, generated, printed, uploaded or written. Reuse release-control's API,
environment checks and published-history implementation rather than fork them.
"""
import argparse
import importlib.util
import os
from pathlib import Path
import re
from urllib.parse import quote

ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location("signing_control", ROOT / "scripts/release-control.py")
control = importlib.util.module_from_spec(spec)
spec.loader.exec_module(control)
require = control.require
SIGNING_NAMES = {"KEYSTORE_BASE64", "KEYSTORE_PASSWORD", "KEY_ALIAS", "KEY_PASSWORD", "SIGNING_CERT_SHA256"}
BACKUP_RECEIPT = "SIGNING_KEY_BACKUP_CONFIRMED_SHA256"
BACKUP_BLOCKER = (
    "Production signing disabled: no reviewed iPad-accessible encrypted backup and "
    "tested recovery implementation. See docs/releases.md; no key has been generated."
)


def require_backup_implementation():
    # Deliberate hard stop, not a dispatch input or an administrator-set boolean.
    # Replace only in a separately reviewed implementation with a recovery drill.
    raise ValueError(BACKUP_BLOCKER)


def check_absent_identity(variables, secrets, history):
    """Partial configuration also reserves an identity; never repair by regeneration."""
    for items in (variables, secrets):
        require(isinstance(items, list) and all(isinstance(i, dict) and isinstance(i.get("name"), str)
                                               for i in items), "Signing inventory could not be verified")
        require(not any(i["name"] in SIGNING_NAMES | {BACKUP_RECEIPT} for i in items),
                "Signing setup already exists or is partial; preserve the existing key and recover it")
    for item, metadata in history:
        # Any public release with missing metadata is ambiguous. Drafts reserve a
        # release too; do not bootstrap over a partially completed publication.
        require(not item.get("draft"), "Existing draft release requires independent signing-identity review")
        if item.get("tag_name", "").startswith("nightly-"):
            continue
        require(isinstance(metadata, dict) and metadata.get("applicationId") == control.release.LEGACY_APPLICATION_ID,
                "Existing or unknown production release identity must be preserved; setup refused")


def check_main_protection(branch, rules):
    require(branch.get("protected") is True, "Signing setup requires protected main")
    pull = next((r.get("parameters", {}) for r in rules if r.get("type") == "pull_request"), {})
    require(pull.get("required_approving_review_count", 0) >= 1 and
            all(pull.get(key) is True for key in ("require_code_owner_review", "dismiss_stale_reviews_on_push",
                                                "require_last_push_approval")),
            "main requires independent PR and Code Owner review, stale-review dismissal and latest-push approval")
    checks = {c.get("context") for rule in rules if rule.get("type") == "required_status_checks"
              for c in rule.get("parameters", {}).get("required_status_checks", [])}
    require({"Compile, test and lint", "Supabase migrations and security"} <= checks,
            "Both Android and Supabase CI checks must be required on main")


def check_no_branch_bypass(rulesets):
    active = [r for r in rulesets if r.get("target") == "branch" and r.get("enforcement") == "active"]
    require(active and all(r.get("bypass_actors") == [] for r in active),
            "Active branch rulesets must disallow bypass before production signing setup")


def inventory(path, key):
    """Read all names, with bounded pagination. Permission failures are blockers."""
    result = []
    for page in range(1, 1001):
        response = control.api(f"{path}?per_page=100&page={page}")
        items = response.get(key)
        require(isinstance(items, list), "Signing inventory could not be verified")
        result.extend(items)
        if len(items) < 100:
            return result
    raise ValueError("Signing inventory pagination incomplete; setup refused")


def preflight():
    env = os.environ
    # Validate before any API access; no unreviewed branch/ref/run inputs.
    require(env.get("GITHUB_REPOSITORY") == control.REPO and env.get("GITHUB_EVENT_NAME") == "workflow_dispatch"
            and env.get("GITHUB_REF") == "refs/heads/main" and env.get("GITHUB_RUN_ATTEMPT") == "1",
            "Signing setup requires a fresh manual dispatch from this repository's protected main")
    require(env.get("GITHUB_WORKFLOW_REF") ==
            f"{control.REPO}/.github/workflows/setup-production-signing.yml@refs/heads/main",
            "Untrusted signing workflow revision")
    run = control.api(f"{control.BASE}/actions/runs/{int(env['GITHUB_RUN_ID'])}")
    control.check_context(env, run)
    require(run.get("path") == ".github/workflows/setup-production-signing.yml", "Unexpected signing workflow")
    branch = control.api(f"{control.BASE}/branches/main")
    require(branch.get("commit", {}).get("sha") == env.get("GITHUB_SHA") ==
            control.release.run("git", "rev-parse", "HEAD").strip(),
            "main changed; signing setup must use the exact current reviewed commit")
    check_main_protection(branch, control.api(f"{control.BASE}/rules/branches/main"))
    # Conservative for this personal repository: require every active branch
    # ruleset to disallow bypass, even if one currently targets another branch.
    rulesets = control.pages(f"{control.BASE}/rulesets")
    check_no_branch_bypass([control.api(f"{control.BASE}/rulesets/{int(r['id'])}") for r in rulesets])
    permission = control.api(f"{control.BASE}/collaborators/{quote(env['GITHUB_TRIGGERING_ACTOR'], safe='')}/permission")
    require(permission.get("permission") in ("admin", "maintain", "write"), "Signing setup actor needs write access")
    environment = control.api(f"{control.BASE}/environments/production")
    policies = control.api(f"{control.BASE}/environments/production/deployment-branch-policies")["branch_policies"]
    control.check_environment(environment, policies)
    reviewers = [r["reviewer"] for rule in environment["protection_rules"] if rule["type"] == "required_reviewers"
                 for r in rule["reviewers"] if r["type"] == "User"]
    independent = False
    for reviewer in reviewers:
        login = reviewer["login"]
        if reviewer.get("type") != "User" or login.lower() == env["GITHUB_ACTOR"].lower():
            continue
        access = control.api(f"{control.BASE}/collaborators/{quote(login, safe='')}/permission")
        independent |= access.get("permission") in ("admin", "maintain", "write")
    require(independent, "A different authorized human production reviewer is required")
    variables = inventory(f"{control.BASE}/environments/production/variables", "variables")
    secrets = inventory(f"{control.BASE}/environments/production/secrets", "secrets")
    # Repository-level signing secrets could otherwise leak to PR workflows.
    variables += inventory(f"{control.BASE}/actions/variables", "variables")
    secrets += inventory(f"{control.BASE}/actions/secrets", "secrets")
    check_absent_identity(variables, secrets, control.published_history())
    require_backup_implementation()


def release_readiness():
    fingerprint = os.environ.get("EXPECTED_SIGNING_CERT_SHA256", "")
    require(re.fullmatch(r"[0-9a-fA-F]{64}", fingerprint), "Missing independently pinned production certificate fingerprint")
    receipt = os.environ.get(BACKUP_RECEIPT, "")
    require(re.fullmatch(r"[0-9a-fA-F]{64}", receipt) and receipt.lower() == fingerprint.lower(),
            "Secure backup and recovery must be independently confirmed for the pinned production certificate")
    # A matching receipt is necessary but never sufficient while recovery is unimplemented.
    require_backup_implementation()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("command", choices=("preflight", "release-readiness"))
    args = parser.parse_args()
    try:
        {"preflight": preflight, "release-readiness": release_readiness}[args.command]()
    except (ValueError, RuntimeError, KeyError, TypeError, control.subprocess.CalledProcessError):
        # API data, subprocess stderr and supplied values never enter logs, even
        # on malformed responses. The summary contains the actionable checklist.
        raise SystemExit("Signing prerequisites not satisfied; setup/publication stopped. See docs/releases.md.") from None


if __name__ == "__main__":
    main()
