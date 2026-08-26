#!/usr/bin/env python3
"""生成 R2-R2-R2 准备阶段机器可读审计摘要。"""
from __future__ import annotations

import argparse
import csv
import hashlib
import json
import pathlib


ROOT = pathlib.Path(__file__).resolve().parents[1]
CONTRACT = ROOT / "contracts/t2/gate10a-r2-r2-r2-sql-remediation-prep"


def sha256(path: pathlib.Path) -> str:
    digest = hashlib.sha256()
    digest.update(path.read_bytes())
    return digest.hexdigest()


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--output", required=True)
    args = parser.parse_args()

    with (CONTRACT / "query-remediation-candidates-v1.csv").open(encoding="utf-8-sig", newline="") as stream:
        candidates = list(csv.DictReader(stream))
    crs = json.loads((CONTRACT / "report-compatibility-cr-register-v1.json").read_text(encoding="utf-8"))["items"]
    ports = json.loads((CONTRACT / "owner-batch-port-design-v1.json").read_text(encoding="utf-8"))["designs"]
    tests = json.loads((CONTRACT / "failure-tests-v1.json").read_text(encoding="utf-8"))["tests"]

    files = sorted(path for path in CONTRACT.iterdir() if path.is_file())
    payload = {
        "schemaVersion": "1.0",
        "decision": "REMEDIATION_PREP_CONDITIONAL_PASS_AWAITING_SPONSOR_CONFIRMATION",
        "finding": {"id": "G10A-SQL-P2-001", "state": "OPEN"},
        "resourceFinding": {"id": "G10A-RES-P2-001", "state": "PREPARED"},
        "counts": {"reportCrs": len(crs), "queryCandidates": len(candidates), "ownerBatchPorts": len(ports), "failureSeeds": len(tests)},
        "amplificationBaseline": [150, 501, 501],
        "candidateQueryTargets": [3, 4, 2],
        "productionChanges": {"java": 0, "mapper": 0, "sql": 0, "index": 0, "databaseObject": 0, "migration": 0},
        "externalExecution": 0,
        "contractFiles": [{"path": path.relative_to(ROOT).as_posix(), "sha256": sha256(path)} for path in files],
    }
    output = pathlib.Path(args.output)
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(payload, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


if __name__ == "__main__":
    main()
