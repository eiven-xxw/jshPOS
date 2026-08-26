#!/usr/bin/env python3
"""生成 RPT-INVENTORY 运行时整改的机器可读审计摘要。"""
from __future__ import annotations

import argparse
import hashlib
import json
import pathlib


ROOT = pathlib.Path(__file__).resolve().parents[1]
CONTRACT = ROOT / "contracts/t2/gate10a-r2-r2-r2-r2-rpt-inventory-runtime"


def sha256(path: pathlib.Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--output", required=True)
    args = parser.parse_args()
    files = sorted(path for path in CONTRACT.iterdir() if path.is_file())
    seeds = json.loads((CONTRACT / "failure-seeds-v1.json").read_text(encoding="utf-8"))["seeds"]
    payload = {
        "schemaVersion": "1.0",
        "gate": "T2_GATE10A_R2_R2_R2_R2_RPT_INVENTORY_RUNTIME",
        "decision": "CONDITIONAL_NO_GO_PENDING_INDEX_CR",
        "finding": {"id": "G10A-SQL-P2-001", "state": "OPEN"},
        "resourceFinding": {"id": "G10A-RES-P2-001", "state": "PREPARED"},
        "query": "RPT-INVENTORY",
        "controls": {"v1Frozen": True, "interactiveLimit": 500, "signedCursor": "HMAC-SHA256",
                     "resumableStreamingExport": True, "conservedFields": 12,
                     "indexOrMigrationChanged": False,
                     "indexProposalCr": "CR-T2G10A-024"},
        "failureSeedCount": len(seeds),
        "externalExecution": 0,
        "contractFiles": [{"path": path.relative_to(ROOT).as_posix(), "sha256": sha256(path)}
                          for path in files],
    }
    output = pathlib.Path(args.output)
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(payload, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


if __name__ == "__main__":
    main()
