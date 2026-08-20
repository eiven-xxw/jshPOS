#!/usr/bin/env python3
"""Gate 6D 证据摘要索引；只索引各生产 Job 制品，避免重复上传。"""
from __future__ import annotations

import argparse
import hashlib
import json
import pathlib


REQUIRED = {"governance", "server", "mysql", "pos-linux", "pos-windows", "web", "internal-e2e", "security"}


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--bundle-dir", required=True)
    args = parser.parse_args()
    root = pathlib.Path(args.bundle_dir)
    missing = [name for name in sorted(REQUIRED) if not (root / name).exists()]
    if missing:
        raise SystemExit(f"missing evidence producers: {missing}")
    files = []
    for path in sorted(item for item in root.rglob("*") if item.is_file() and item.name != "t2-gate6d-evidence-index.json"):
        files.append(
            {
                "path": path.relative_to(root).as_posix(),
                "size": path.stat().st_size,
                "sha256": hashlib.sha256(path.read_bytes()).hexdigest(),
            }
        )
    if not files:
        raise SystemExit("empty evidence bundle")
    e2e_reports = list((root / "internal-e2e").rglob("internal-cash-e2e-report.json"))
    if len(e2e_reports) != 1:
        raise SystemExit("internal synthetic E2E report missing or duplicated")
    e2e = json.loads(e2e_reports[0].read_text(encoding="utf-8"))
    if (e2e.get("status") != "PASS" or e2e.get("evidenceLevel") != "SYNTHETIC_E2E"
            or e2e.get("journeyCount") != 6 or e2e.get("tenantCount") != 2
            or e2e.get("providerNetworkCalls") != 0 or e2e.get("realDeviceCommands") != 0
            or e2e.get("onsitePilots") != 0 or e2e.get("fullAlphaRuns") != 0
            or e2e.get("commercialClaimAllowed") is not False):
        raise SystemExit("internal synthetic E2E evidence boundary or fixed journey count invalid")
    payload = {
        "schemaVersion": "1.0",
        "gate": "T2-GATE6D-S15",
        "status": "PASS",
        "fileCount": len(files),
        "evidenceCeiling": ["STATIC", "UNIT", "WIDGET", "COMPONENT", "SYNTHETIC_E2E", "SOFTWARE_EXECUTION"],
        "sandbox": 0,
        "realDevice": 0,
        "pilot": 0,
        "fullAlpha": 0,
        "production": 0,
        "syntheticE2EJourneys": e2e["journeyCount"],
        "files": files,
    }
    encoded = json.dumps(payload, sort_keys=True, separators=(",", ":"), ensure_ascii=False).encode()
    payload["indexSha256"] = hashlib.sha256(encoded).hexdigest()
    target = root / "t2-gate6d-evidence-index.json"
    target.write_text(json.dumps(payload, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({"status": "PASS", "files": len(files), "sha256": payload["indexSha256"]}))


if __name__ == "__main__":
    main()
