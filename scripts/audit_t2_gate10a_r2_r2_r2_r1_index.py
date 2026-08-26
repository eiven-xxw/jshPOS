#!/usr/bin/env python3
"""生成 RPT-SALES V88 索引专项机器审计摘要。"""
from __future__ import annotations

import argparse
import hashlib
import json
import pathlib


ROOT = pathlib.Path(__file__).resolve().parents[1]
CONTRACT = ROOT / "contracts/t2/gate10a-r2-r2-r2-r1-index"
MIGRATION = ROOT / ("server/ruoyi-modules/jshpos-reporting/src/main/resources/db/migration/"
                    "V202608260088__reporting_sales_keyset_index.sql")


def sha256(path: pathlib.Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--output", required=True)
    args = parser.parse_args()
    files = sorted(path for path in CONTRACT.iterdir() if path.is_file())
    payload = {
        "schemaVersion": "1.0",
        "gate": "T2_GATE10A_R2_R2_R2_R1_INDEX",
        "decision": "IN_PROGRESS_AWAITING_MYSQL84_AND_FULL_CI",
        "finding": {"id": "G10A-SQL-P2-001", "state": "OPEN"},
        "resourceFinding": {"id": "G10A-RES-P2-001", "state": "PREPARED"},
        "changeRequest": "CR-T2G10A-018",
        "changes": {"index": 1, "migration": 1, "sqlMapperApi": 0, "dependency": 0},
        "migration": {"version": "202608260088", "sha256": sha256(MIGRATION)},
        "externalExecution": 0,
        "contracts": [{"path": path.relative_to(ROOT).as_posix(), "sha256": sha256(path)}
                      for path in files],
    }
    output = pathlib.Path(args.output)
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(payload, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


if __name__ == "__main__":
    main()
