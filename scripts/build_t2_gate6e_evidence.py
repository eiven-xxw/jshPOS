#!/usr/bin/env python3
"""Gate 6E 分阶段证据索引；只索引生产 Job 制品，禁止用占位文件制造绿色证据。"""
from __future__ import annotations

import argparse
import hashlib
import json
import pathlib
import subprocess


REQUIRED = {"governance", "server", "mysql", "pos-linux", "pos-windows", "web", "internal-alpha", "security"}


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--bundle-dir", required=True)
    args = parser.parse_args()
    root = pathlib.Path(args.bundle_dir)
    missing = [name for name in sorted(REQUIRED) if not (root / name).is_dir()]
    if missing:
        raise SystemExit(f"missing evidence producers: {missing}")
    governance_reports = list((root / "governance").rglob("gate6e-governance.json"))
    if len(governance_reports) != 1:
        raise SystemExit("Gate 6E governance result missing or duplicated")
    governance = json.loads(governance_reports[0].read_text(encoding="utf-8"))
    if governance.get("result") != "PASS" or governance.get("externalExecution", {}).get("providerNetworkCalls") != 0:
        raise SystemExit("Gate 6E governance or external zero-execution boundary invalid")
    candidate_reports = list((root / "internal-alpha").rglob("internal-alpha-candidate-report.json"))
    if len(candidate_reports) != 1:
        raise SystemExit("Gate 6E internal Alpha candidate report missing or duplicated")
    candidate = json.loads(candidate_reports[0].read_text(encoding="utf-8"))
    if (candidate.get("status") != "PASS" or candidate.get("executionMode") != "SAME_RUN_CI_EVIDENCE" or
            candidate.get("internalDecision") != "CONDITIONAL_GO_INTERNAL_ONLY" or
            candidate.get("evidenceLevel") != "INTERNAL_ALPHA_CANDIDATE" or
            candidate.get("openP0") != 0 or candidate.get("openP1") != 0 or
            any(candidate.get(field) != 0 for field in
                ("providerNetworkCalls", "realDeviceCommands", "onsitePilots", "fullAlphaRuns", "productionDeployments"))):
        raise SystemExit("Gate 6E internal Alpha candidate evidence boundary invalid")
    candidate_digest = candidate.pop("evidenceSha256", None)
    canonical_candidate = json.dumps(candidate, sort_keys=True, separators=(",", ":"), ensure_ascii=False).encode()
    if not candidate_digest or hashlib.sha256(canonical_candidate).hexdigest() != candidate_digest:
        raise SystemExit("Gate 6E internal Alpha candidate self-digest invalid")
    candidate["evidenceSha256"] = candidate_digest
    seed_reports = list((root / "internal-alpha").rglob("failure-seed-ledger.json"))
    defect_reports = list((root / "internal-alpha").rglob("defect-ledger.json"))
    if len(seed_reports) != 1 or len(defect_reports) != 1:
        raise SystemExit("Gate 6E failure seed or defect ledger missing or duplicated")
    seed_ledger = json.loads(seed_reports[0].read_text(encoding="utf-8"))
    defect_ledger = json.loads(defect_reports[0].read_text(encoding="utf-8"))
    if (seed_ledger.get("status") != "PASS" or seed_ledger.get("failedSeeds") or
            len(seed_ledger.get("fixedSeeds", [])) < 12 or
            any(seed.get("result") != "PASS" for seed in seed_ledger.get("fixedSeeds", []))):
        raise SystemExit("Gate 6E fixed failure seed ledger is non-green")
    if (defect_ledger.get("status") != "PASS" or defect_ledger.get("p0") or defect_ledger.get("p1") or
            defect_ledger.get("goNoGo") != "CONDITIONAL_GO_INTERNAL_ONLY"):
        raise SystemExit("Gate 6E P0/P1 defect ledger forces NO-GO")
    current_commit = subprocess.check_output(["git", "rev-parse", "HEAD"], text=True).strip()
    if candidate.get("commitSha") != current_commit:
        raise SystemExit("Gate 6E candidate report does not belong to the checked-out commit")
    if seed_ledger.get("commitSha") != current_commit or defect_ledger.get("commitSha") != current_commit:
        raise SystemExit("Gate 6E seed or defect ledger does not belong to the checked-out commit")
    files = []
    for path in sorted(item for item in root.rglob("*") if item.is_file() and item.name != "t2-gate6e-evidence-index.json"):
        if path.stat().st_size <= 0:
            raise SystemExit(f"empty evidence file: {path.relative_to(root)}")
        files.append({
            "path": path.relative_to(root).as_posix(),
            "size": path.stat().st_size,
            "sha256": hashlib.sha256(path.read_bytes()).hexdigest(),
        })
    if len(files) < 10:
        raise SystemExit("insufficient independent Gate 6E evidence files")
    payload = {
        "schemaVersion": "1.0",
        "gate": "T2-GATE6E-S16",
        "phaseStatuses": governance["statuses"],
        "status": "PASS",
        "evidenceCeiling": ["STATIC", "UNIT", "WIDGET", "COMPONENT", "SYNTHETIC_RESTORE", "SOFTWARE_EXECUTION", "INTERNAL_ALPHA_CANDIDATE"],
        "sandbox": 0,
        "realDevice": 0,
        "pilot": 0,
        "fullAlpha": 0,
        "production": 0,
        "internalAlphaCandidate": {
            "status": candidate["status"],
            "decision": candidate["internalDecision"],
            "journeyCount": candidate["journeyCount"],
            "failureSeedCount": candidate["failureSeedCount"],
            "openP0": candidate["openP0"],
            "openP1": candidate["openP1"],
            "evidenceSha256": candidate["evidenceSha256"],
        },
        "fileCount": len(files),
        "files": files,
    }
    canonical = json.dumps(payload, sort_keys=True, separators=(",", ":"), ensure_ascii=False).encode()
    payload["indexSha256"] = hashlib.sha256(canonical).hexdigest()
    target = root / "t2-gate6e-evidence-index.json"
    target.write_text(json.dumps(payload, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({"status": "PASS", "files": len(files), "sha256": payload["indexSha256"]}))


if __name__ == "__main__":
    main()
