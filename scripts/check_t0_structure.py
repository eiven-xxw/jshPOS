from __future__ import annotations

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
    "server/pom.xml",
    "admin-web/package.json",
    "admin-web/pnpm-lock.yaml",
    "pos-flutter/pubspec.yaml",
    "pos-flutter/pubspec.lock",
    "packages/pos_device_adapter/pubspec.yaml",
    "packages/pos_device_adapter/android/src/main/kotlin/com/jingshanghui/pos/pos_device_adapter/PosDeviceAdapterPlugin.kt",
    "contracts/openapi/openapi.yaml",
    "infra/compose/compose.yaml",
    ".github/workflows/ci.yml",
    "ci/codeup/t0-flow.yml",
    "ci/codeup/README.md",
]


def main() -> None:
    missing = [relative for relative in REQUIRED if not (ROOT / relative).exists()]
    nested_git = [path for path in ROOT.glob("*/.git") if path.is_dir()]
    ci = (ROOT / ".github" / "workflows" / "ci.yml").read_text(encoding="utf-8")
    mutable_actions = [line.strip() for line in ci.splitlines() if "uses:" in line and "@v" in line]
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
    if missing or nested_git or mutable_actions or missing_flow_jobs or mutable_flow_images or unsafe_flow_controls:
        if missing:
            print("STRUCTURE ERROR: missing " + ", ".join(missing), file=sys.stderr)
        if nested_git:
            print("STRUCTURE ERROR: nested git " + ", ".join(map(str, nested_git)), file=sys.stderr)
        if mutable_actions:
            print("STRUCTURE ERROR: GitHub Actions must use immutable SHAs: " + ", ".join(mutable_actions), file=sys.stderr)
        if missing_flow_jobs:
            print("STRUCTURE ERROR: Codeup Flow jobs missing: " + ", ".join(missing_flow_jobs), file=sys.stderr)
        if mutable_flow_images:
            print("STRUCTURE ERROR: Codeup Flow images must use immutable digests: " + ", ".join(mutable_flow_images), file=sys.stderr)
        if unsafe_flow_controls:
            print("STRUCTURE ERROR: Codeup Flow gates cannot continue on failure: " + ", ".join(unsafe_flow_controls), file=sys.stderr)
        raise SystemExit(1)
    print(f"STRUCTURE OK: {len(REQUIRED)} required paths, no nested Git repositories, CI images pinned")


if __name__ == "__main__":
    main()
