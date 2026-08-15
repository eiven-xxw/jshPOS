from __future__ import annotations

import re
import subprocess
import sys
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
REQUIRED = [
    "AGENTS.md",
    "VERSION_BASELINE.md",
    "THIRD_PARTY_NOTICES.md",
    "docs/governance/rtm.csv",
    "docs/governance/change-log.md",
    "docs/adr/ADR-001-modular-monolith.md",
    "docs/adr/ADR-012-observability-privacy-support.md",
    "docs/adr/ADR-013-github-primary-repository-ci.md",
    "docs/adr/ADR-014-t0-supply-chain-security-overrides.md",
    "docs/adr/ADR-015-restricted-license-policy.md",
    "docs/adr/ADR-016-private-repository-dependency-review.md",
    "docs/compliance/reviewed-license-allowlist.json",
    "docs/evidence/T0-seal-2026-08-16.md",
    "server/pom.xml",
    "server/mvnw",
    "admin-web/package.json",
    "admin-web/pnpm-lock.yaml",
    "pos-flutter/pubspec.yaml",
    "pos-flutter/pubspec.lock",
    "pos-flutter/android/gradlew",
    "pos-flutter/android/gradle/wrapper/gradle-wrapper.jar",
    "packages/pos_device_adapter/pubspec.yaml",
    "packages/pos_device_adapter/pubspec.lock",
    "packages/pos_device_adapter/example/pubspec.lock",
    "packages/pos_device_adapter/android/src/main/kotlin/com/jingshanghui/pos/pos_device_adapter/PosDeviceAdapterPlugin.kt",
    "contracts/openapi/openapi.yaml",
    "infra/compose/compose.yaml",
    ".github/workflows/ci.yml",
    "ci/codeup/t0-flow.yml",
    "ci/codeup/README.md",
    "scripts/check_sbom_licenses.py",
    "scripts/review_dependency_diff.py",
]


def main() -> None:
    missing = [relative for relative in REQUIRED if not (ROOT / relative).exists()]
    tracked_result = subprocess.run(
        ["git", "ls-files", "-z"],
        cwd=ROOT,
        check=True,
        capture_output=True,
    )
    tracked = set(tracked_result.stdout.decode("utf-8").split("\0"))
    untracked_required = [relative for relative in REQUIRED if relative not in tracked]
    mirrored_pub_locks = [
        relative
        for relative in tracked
        if relative.endswith("pubspec.lock")
        and "pub.flutter-io.cn" in (ROOT / relative).read_text(encoding="utf-8")
    ]
    nested_git = [path for path in ROOT.glob("*/.git") if path.is_dir()]
    ci = (ROOT / ".github" / "workflows" / "ci.yml").read_text(encoding="utf-8")
    workflow_text = "\n".join(
        path.read_text(encoding="utf-8")
        for path in sorted((ROOT / ".github" / "workflows").glob("*.yml"))
    )
    action_refs = [
        match.group(1)
        for line in workflow_text.splitlines()
        if (match := re.search(r"\buses:\s*([^\s#]+)", line))
    ]
    mutable_actions = [
        ref
        for ref in action_refs
        if not ref.startswith("./") and not re.fullmatch(r"[^@]+@[0-9a-f]{40}", ref)
    ]
    required_github_jobs = {
        "  governance:",
        "  server:",
        "  admin-web:",
        "  flutter-pos:",
        "  infrastructure:",
        "  supply-chain:",
    }
    missing_github_jobs = sorted(job for job in required_github_jobs if job not in ci)
    flow = (ROOT / "ci" / "codeup" / "t0-flow.yml").read_text(encoding="utf-8")
    required_flow_jobs = {
        "governance_job:",
        "server_job:",
        "admin_job:",
        "flutter_job:",
        "infrastructure_job:",
        "supply_chain_job:",
    }
    missing_flow_jobs = sorted(job for job in required_flow_jobs if job not in flow)
    mutable_flow_images = [
        line.strip()
        for line in flow.splitlines()
        if "container:" in line and "@sha256:" not in line
    ]
    unsafe_flow_controls = [
        line.strip()
        for line in flow.splitlines()
        if "continueOnFail:" in line and line.split("#", 1)[0].rstrip().endswith("true")
    ]
    if (
        missing
        or untracked_required
        or mirrored_pub_locks
        or nested_git
        or mutable_actions
        or missing_github_jobs
        or missing_flow_jobs
        or mutable_flow_images
        or unsafe_flow_controls
    ):
        if missing:
            print("STRUCTURE ERROR: missing " + ", ".join(missing), file=sys.stderr)
        if untracked_required:
            print("STRUCTURE ERROR: required paths are not tracked: " + ", ".join(untracked_required), file=sys.stderr)
        if mirrored_pub_locks:
            print(
                "STRUCTURE ERROR: Pub lockfiles must use the canonical pub.dev registry: "
                + ", ".join(mirrored_pub_locks),
                file=sys.stderr,
            )
        if nested_git:
            print("STRUCTURE ERROR: nested git " + ", ".join(map(str, nested_git)), file=sys.stderr)
        if mutable_actions:
            print("STRUCTURE ERROR: GitHub Actions must use immutable SHAs: " + ", ".join(mutable_actions), file=sys.stderr)
        if missing_github_jobs:
            print("STRUCTURE ERROR: GitHub Actions jobs missing: " + ", ".join(missing_github_jobs), file=sys.stderr)
        if missing_flow_jobs:
            print("STRUCTURE ERROR: Codeup Flow jobs missing: " + ", ".join(missing_flow_jobs), file=sys.stderr)
        if mutable_flow_images:
            print("STRUCTURE ERROR: Codeup Flow images must use immutable digests: " + ", ".join(mutable_flow_images), file=sys.stderr)
        if unsafe_flow_controls:
            print("STRUCTURE ERROR: Codeup Flow gates cannot continue on failure: " + ", ".join(unsafe_flow_controls), file=sys.stderr)
        raise SystemExit(1)
    print(
        f"STRUCTURE OK: {len(REQUIRED)} tracked required paths, no nested Git repositories, CI references pinned"
    )


if __name__ == "__main__":
    main()
