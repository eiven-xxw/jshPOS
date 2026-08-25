#!/usr/bin/env python3
"""在授权基线 Blob 上复现 G9A-R4 的四个既有 P1，避免先改代码后补理由。"""
from __future__ import annotations

import argparse
import hashlib
import json
import pathlib
import subprocess


ROOT = pathlib.Path(__file__).resolve().parents[1]
BASELINE = "059f47ebd6877b683345d1e6f7c0cd9a18d712b5"
CONTRACT = ROOT / "contracts/t2/gate9b-r4/r0-red-baseline-v1.json"


def blob(path: str) -> str:
    return subprocess.check_output(
        ["git", "show", f"{BASELINE}:{path}"], cwd=ROOT, text=True, encoding="utf-8"
    )


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--output", required=True, type=pathlib.Path)
    args = parser.parse_args()
    contract = json.loads(CONTRACT.read_text(encoding="utf-8"))
    flutter = blob("pos-flutter/test/gate6g/formal_pos_runtime_e2e_test.dart")
    gate7e = blob(".github/workflows/t2-gate7e.yml")
    gate8b = blob(".github/workflows/t2-gate8b.yml")
    prep_inventory = json.loads(blob("contracts/t2/gate9b-r4-prep/owner-runtime-inventory-v1.json"))
    prep_seeds = json.loads(blob("contracts/t2/gate9b-r4-prep/failure-seeds-v1.json"))
    checks = {
        "G9A-R4-P1-001": "HttpServer.bind" in flutter and "formal_pos_runtime_e2e_test.dart" in gate7e,
        "G9A-R4-P1-002": "run_t2_gate8b_runtime_api_journey.py" in gate8b
        and "run_t2_gate8b_runtime_api_journey.py" not in gate7e,
        "G9A-R4-P1-003": len(prep_inventory["owners"]) == 22
        and all(not item.get("oneUnifiedR4Checkpoint", False) for item in prep_inventory["owners"]),
        "G9A-R4-P1-004": prep_seeds["openP1"] == 4 and prep_seeds["findingClosureAllowed"] is False,
    }
    expected = {item["id"] for item in contract["expectedFailures"]}
    reproduced = sorted(key for key, value in checks.items() if value)
    if set(reproduced) != expected:
        raise SystemExit(f"R4-R0 red regression mismatch: {checks}")
    source_digest = hashlib.sha256((flutter + gate7e + gate8b).encode("utf-8")).hexdigest()
    evidence = {
        "schemaVersion": "1.0",
        "gate": "G9A-R4-R0",
        "baselineCommit": BASELINE,
        "status": "PASS",
        "classification": contract["redEvidenceClassification"],
        "reproducedFailureCount": len(reproduced),
        "reproducedFailureIds": reproduced,
        "baselineSourceSha256": source_digest,
        "findingState": "OPEN",
        "externalExecution": 0,
    }
    output = args.output if args.output.is_absolute() else ROOT / args.output
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(evidence, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print("G9A-R4 R0 RED REGRESSIONS OK: four baseline failures reproduced")


if __name__ == "__main__":
    main()
