#!/usr/bin/env python3
"""聚合 RPT-SALES V88 索引完整 CI 制品摘要。"""
from __future__ import annotations

import argparse
import hashlib
import json
import pathlib


def sha256(path: pathlib.Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--bundle-dir", required=True)
    parser.add_argument("--output", required=True)
    parser.add_argument("--commit", required=True)
    args = parser.parse_args()
    bundle = pathlib.Path(args.bundle_dir)
    files = sorted(path for path in bundle.rglob("*") if path.is_file())
    producers = sorted({path.relative_to(bundle).parts[0] for path in files})
    expected = sorted(["android", "governance-ubuntu", "governance-windows", "mysql84",
                       "security", "server", "web", "flutter-ubuntu", "flutter-windows"])
    if producers != expected:
        raise SystemExit(f"producer mismatch: {producers} != {expected}")
    result_file = next((path for path in files if path.name == "rpt-sales-keyset-results.json"), None)
    red_file = next((path for path in files if path.name == "v87-red-plan.json"), None)
    if result_file is None or red_file is None:
        raise SystemExit("missing V87 red or V88 result")
    plans = json.loads(result_file.read_text(encoding="utf-8"))
    red = json.loads(red_file.read_text(encoding="utf-8"))
    green = all(not item["fullScanObserved"] and not item["filesortObserved"]
                and item["approvedIndexObserved"] and not item["indexCrRequired"] for item in plans)
    if not (red["fullScanObserved"] and red["filesortObserved"] and green):
        raise SystemExit("red/green plan boundary not satisfied")
    payload = {
        "schemaVersion": "1.0",
        "gate": "T2_GATE10A_R2_R2_R2_R1_INDEX",
        "commit": args.commit,
        "decision": "INDEX_VERIFIED_AWAITING_SPONSOR_CONFIRMATION",
        "findingState": "OPEN",
        "resourceFindingState": "PREPARED",
        "migrationVersion": "202608260088",
        "v87RedPlan": True,
        "v88PlanGreen": True,
        "externalExecution": 0,
        "producers": producers,
        "files": [{"path": path.relative_to(bundle).as_posix(), "size": path.stat().st_size,
                   "sha256": sha256(path)} for path in files],
    }
    output = pathlib.Path(args.output)
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(payload, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


if __name__ == "__main__":
    main()
