#!/usr/bin/env python3
"""聚合 RPT-INVENTORY 正式运行时整改 CI 证据。"""
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
    expected = sorted(["governance-ubuntu", "governance-windows", "server", "mysql84", "web",
                       "flutter-ubuntu", "flutter-windows", "android", "security"])
    if producers != expected:
        raise SystemExit(f"producer mismatch: {producers} != {expected}")
    result_path = next((path for path in files if path.name == "rpt-inventory-keyset-results.json"), None)
    if result_path is None:
        raise SystemExit("missing RPT-INVENTORY MySQL result")
    rows = json.loads(result_path.read_text(encoding="utf-8"))
    if [row["tier"] for row in rows] != ["SMOKE_10K", "BASELINE_100K"]:
        raise SystemExit("unexpected MySQL tier sequence")
    index_required = any(row["indexCrRequired"] for row in rows)
    decision = "CONDITIONAL_NO_GO_PENDING_INDEX_CR" if index_required \
        else "CONDITIONAL_PASS_AWAITING_SPONSOR_CONFIRMATION"
    payload = {
        "schemaVersion": "1.0",
        "gate": "T2_GATE10A_R2_R2_R2_R2_RPT_INVENTORY_RUNTIME",
        "commit": args.commit,
        "decision": decision,
        "findingState": "OPEN",
        "resourceFindingState": "PREPARED",
        "indexCrRequired": index_required,
        "migrationChanged": False,
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
