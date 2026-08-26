#!/usr/bin/env python3
"""聚合 RPT-INVENTORY 准备阶段 CI 制品摘要。"""
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
    expected = sorted(["governance-ubuntu", "governance-windows", "mysql84", "test-baseline"])
    if producers != expected:
        raise SystemExit(f"producer mismatch: {producers} != {expected}")
    mysql_result = next((path for path in files if path.name == "rpt-inventory-red-baseline.json"), None)
    if mysql_result is None:
        raise SystemExit("missing MySQL RPT-INVENTORY red baseline")
    rows = json.loads(mysql_result.read_text(encoding="utf-8"))
    if [row["tier"] for row in rows] != ["SMOKE_10K", "BASELINE_100K"]:
        raise SystemExit("unexpected MySQL tier sequence")
    payload = {
        "schemaVersion": "1.0",
        "gate": "T2_GATE10A_R2_R2_R2_R2_RPT_INVENTORY_PREP",
        "commit": args.commit,
        "decision": "PREP_VERIFIED_AWAITING_SPONSOR_CONFIRMATION",
        "findingState": "OPEN",
        "resourceFindingState": "PREPARED",
        "runtimeChangeAuthorized": False,
        "indexCrRequired": "PENDING_EXECUTABLE_CANDIDATE_COMPARISON",
        "externalExecution": 0,
        "producers": producers,
        "files": [
            {"path": path.relative_to(bundle).as_posix(), "size": path.stat().st_size, "sha256": sha256(path)}
            for path in files
        ],
    }
    output = pathlib.Path(args.output)
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(payload, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


if __name__ == "__main__":
    main()
