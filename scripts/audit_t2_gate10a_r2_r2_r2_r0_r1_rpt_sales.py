#!/usr/bin/env python3
"""生成 RPT-SALES R0/R1 当前态机器审计摘要。"""
from __future__ import annotations

import argparse
import hashlib
import json
import pathlib


ROOT = pathlib.Path(__file__).resolve().parents[1]
CONTRACT = ROOT / "contracts/t2/gate10a-r2-r2-r2-r0-r1-rpt-sales"


def sha256(path: pathlib.Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--output", required=True)
    args = parser.parse_args()
    files = sorted(path for path in CONTRACT.iterdir() if path.is_file())
    payload = {
        "schemaVersion": "1.0",
        "gate": "T2_GATE10A_R2_R2_R2_R0_R1_RPT_SALES",
        "decision": "IN_PROGRESS_AWAITING_MYSQL84_AND_COMPLETE_CI",
        "finding": {"id": "G10A-SQL-P2-001", "state": "OPEN"},
        "resourceFinding": {"id": "G10A-RES-P2-001", "state": "PREPARED"},
        "scope": {"reportingBatchPort": 1, "versionedApi": 1, "streamingExport": 1,
                  "inventoryRuntime": 0, "paymentRuntime": 0},
        "changes": {"index": 0, "migration": 0, "dependency": 0},
        "externalExecution": 0,
        "contracts": [{"path": path.relative_to(ROOT).as_posix(), "sha256": sha256(path)} for path in files],
    }
    output = pathlib.Path(args.output)
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(payload, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


if __name__ == "__main__":
    main()

