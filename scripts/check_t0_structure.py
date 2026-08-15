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
]


def main() -> None:
    missing = [relative for relative in REQUIRED if not (ROOT / relative).exists()]
    nested_git = [path for path in ROOT.glob("*/.git") if path.is_dir()]
    ci = (ROOT / ".github" / "workflows" / "ci.yml").read_text(encoding="utf-8")
    mutable_actions = [line.strip() for line in ci.splitlines() if "uses:" in line and "@v" in line]
    if missing or nested_git or mutable_actions:
        if missing:
            print("STRUCTURE ERROR: missing " + ", ".join(missing), file=sys.stderr)
        if nested_git:
            print("STRUCTURE ERROR: nested git " + ", ".join(map(str, nested_git)), file=sys.stderr)
        if mutable_actions:
            print("STRUCTURE ERROR: GitHub Actions must use immutable SHAs: " + ", ".join(mutable_actions), file=sys.stderr)
        raise SystemExit(1)
    print(f"STRUCTURE OK: {len(REQUIRED)} required paths, no nested Git repositories")


if __name__ == "__main__":
    main()
