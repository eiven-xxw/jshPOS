#!/usr/bin/env python3
"""生成 Gate 7E 多执行器不可变 SHA-256 证据索引。"""
from __future__ import annotations

import argparse
import hashlib
import json
import pathlib


REQUIRED = {
    "governance-ubuntu", "governance-windows", "server", "mysql-runtime", "web",
    "flutter-ubuntu", "flutter-windows", "runtime-stack", "internal-e2e", "security",
}
EVIDENCE_LEVEL = "INTERNAL_V1_BUSINESS_COMPLETE_CANDIDATE"


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--bundle-dir", required=True, type=pathlib.Path)
    parser.add_argument("--output", required=True, type=pathlib.Path)
    args = parser.parse_args()
    output = args.output.resolve()
    files = sorted(path for path in args.bundle_dir.rglob("*") if path.is_file() and path.resolve() != output)
    producers = {
        path.relative_to(args.bundle_dir).parts[0]
        for path in files if len(path.relative_to(args.bundle_dir).parts) > 1
    }
    missing = sorted(REQUIRED - producers)
    if missing:
        raise SystemExit(f"Gate7E evidence producers missing: {missing}")
    governance_reports = list((args.bundle_dir / "governance-ubuntu").rglob("gate7e-governance.json")) + \
        list((args.bundle_dir / "governance-windows").rglob("gate7e-governance.json"))
    if len(governance_reports) != 2:
        raise SystemExit("Ubuntu/Windows Gate7E governance reports missing or duplicated")
    governance = [json.loads(path.read_text(encoding="utf-8")) for path in governance_reports]
    if any(report.get("status") != "PASS" or report.get("requirementStatus") != "VERIFIED"
           for report in governance):
        raise SystemExit("Gate7E governance state must be VERIFIED and green")
    candidate_reports = list((args.bundle_dir / "internal-e2e").rglob("internal-v1-business-complete-report.json"))
    if len(candidate_reports) != 1:
        raise SystemExit("Gate7E internal business complete report missing or duplicated")
    candidate = json.loads(candidate_reports[0].read_text(encoding="utf-8"))
    if (candidate.get("status") != "PASS" or candidate.get("evidenceLevel") != EVIDENCE_LEVEL
            or candidate.get("internalDecision") != "CONDITIONAL_PASS_AWAITING_SPONSOR_ACCEPTANCE"
            or candidate.get("openP0") != 0 or candidate.get("openP1") != 0
            or candidate.get("commercialClaimAllowed") is not False
            or any(value != 0 for value in candidate.get("externalExecution", {}).values())):
        raise SystemExit("Gate7E candidate evidence boundary invalid")
    digest = candidate.pop("evidenceSha256", None)
    canonical = json.dumps(candidate, sort_keys=True, separators=(",", ":"), ensure_ascii=False).encode()
    if digest != hashlib.sha256(canonical).hexdigest():
        raise SystemExit("Gate7E candidate report self-digest invalid")
    seed_reports = list((args.bundle_dir / "internal-e2e").rglob("failure-seed-ledger.json"))
    defect_reports = list((args.bundle_dir / "internal-e2e").rglob("defect-ledger.json"))
    if len(seed_reports) != 1 or len(defect_reports) != 1:
        raise SystemExit("Gate7E seed or defect ledger missing or duplicated")
    seed = json.loads(seed_reports[0].read_text(encoding="utf-8"))
    defect = json.loads(defect_reports[0].read_text(encoding="utf-8"))
    if seed.get("status") != "PASS" or len(seed.get("fixedSeeds", [])) != 16 or seed.get("failedSeeds"):
        raise SystemExit("Gate7E fixed failure seed evidence is not green")
    if defect.get("status") != "PASS" or defect.get("p0") or defect.get("p1"):
        raise SystemExit("Gate7E P0/P1 defect ledger is not empty")
    entries = [{
        "path": path.relative_to(args.bundle_dir).as_posix(), "size": path.stat().st_size,
        "sha256": hashlib.sha256(path.read_bytes()).hexdigest(),
    } for path in files]
    result = {
        "schemaVersion": "1.0", "gate": "T2-GATE7E-S23A", "status": "PASS",
        "evidenceLevel": EVIDENCE_LEVEL,
        "decision": "T2_E2E004_VERIFIED_AWAITING_SPONSOR_ACCEPTANCE",
        "requirementStatus": "VERIFIED", "journeyCount": candidate["journeyCount"],
        "fixedFailureSeedCount": candidate["fixedFailureSeedCount"], "openP0": 0, "openP1": 0,
        "externalExecution": 0, "producers": sorted(producers), "fileCount": len(entries), "files": entries,
        "limitations": [
            "Evidence is limited to fictional tenants, formal local components and synthetic external boundaries.",
            "Payment sandbox, real funds, REAL_DEVICE, peripherals, partner pilot, full Alpha and production remain zero.",
            "Internal performance observations are executor-specific trends and are not a commercial SLA.",
            "T2-E2E-004 is not ACCEPTED until project sponsor confirmation.",
        ],
    }
    canonical_index = json.dumps(result, sort_keys=True, separators=(",", ":"), ensure_ascii=False).encode()
    result["indexSha256"] = hashlib.sha256(canonical_index).hexdigest()
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(f"Gate7E evidence index OK: {len(entries)} files")


if __name__ == "__main__":
    main()
