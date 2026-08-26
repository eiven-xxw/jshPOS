#!/usr/bin/env python3
"""生成 RPT-PAY-REC 准备阶段机器可读审计摘要。"""
from __future__ import annotations

import argparse
import hashlib
import json
import pathlib


ROOT = pathlib.Path(__file__).resolve().parents[1]
CONTRACT = ROOT / "contracts/t2/gate10a-r2-r2-r2-r3-rpt-pay-rec-prep"


def sha256(path: pathlib.Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--output", required=True)
    args = parser.parse_args()
    files = sorted(path for path in CONTRACT.iterdir() if path.is_file())
    seeds = json.loads((CONTRACT / "failure-seeds-v1.json").read_text(encoding="utf-8"))["seeds"]
    candidate = json.loads((CONTRACT / "candidate-design-v1.json").read_text(encoding="utf-8"))
    payload = {
        "schemaVersion": "1.0",
        "gate": "T2_GATE10A_R2_R2_R2_R3_RPT_PAY_REC_PREP",
        "decision": "PREP_IN_PROGRESS_RUNTIME_NOT_ADMITTED",
        "finding": {"id": "G10A-SQL-P2-001", "state": "OPEN"},
        "resourceFinding": {"id": "G10A-RES-P2-001", "state": "PREPARED"},
        "query": "RPT-PAY-REC",
        "currentRed": {
            "unbounded": True,
            "legacyExportQueryCountAt50Stores": 50,
            "paymentReferenceQueryCountAt500References": 501,
            "signedCursor": False,
        },
        "counts": {"failureSeeds": len(seeds), "indexComparisons": len(candidate["indexCandidates"]),
                   "differenceInvariants": 12},
        "productionChanges": {"java": 0, "mapper": 0, "sql": 0, "index": 0,
                              "databaseObject": 0, "migration": 0, "dependency": 0},
        "providerNetwork": 0,
        "externalExecution": 0,
        "contractFiles": [
            {"path": path.relative_to(ROOT).as_posix(), "sha256": sha256(path)} for path in files
        ],
    }
    output = pathlib.Path(args.output)
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(payload, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


if __name__ == "__main__":
    main()
