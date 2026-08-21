#!/usr/bin/env python3
"""汇总 Gate 7A 多平台静态审计证据并生成 SHA-256 索引。"""
from __future__ import annotations

import argparse
import hashlib
import json
import pathlib


GATE = "T2-GATE7A-SPRINT-S19-V1-BUSINESS-GAP-AUDIT"


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--bundle-dir", required=True)
    parser.add_argument("--output", required=True)
    args = parser.parse_args()
    bundle = pathlib.Path(args.bundle_dir)
    output = pathlib.Path(args.output).resolve()
    files = sorted(path for path in bundle.rglob("*") if path.is_file() and path.resolve() != output)
    producers = {path.relative_to(bundle).parts[0] for path in files if len(path.relative_to(bundle).parts) > 1}
    required = {"governance-ubuntu", "governance-windows", "scope-boundary"}
    missing = sorted(required - producers)
    if missing:
        raise SystemExit(f"missing Gate 7A evidence producers: {missing}")
    reports = []
    for path in files:
        if path.suffix != ".json":
            continue
        try:
            report = json.loads(path.read_text(encoding="utf-8"))
        except json.JSONDecodeError:
            continue
        if report.get("gate") == GATE:
            reports.append(report)
    if len(reports) < 3 or any(report.get("status") != "PASS" for report in reports):
        raise SystemExit("Gate 7A producer reports missing or non-green")
    for report in reports:
        if report.get("acceptedRequirementCount") != 64 or report.get("confirmedGapCount") != 18:
            raise SystemExit("Gate 7A accepted/gap count drift")
        if report.get("runtimeFilesChanged") != 0:
            raise SystemExit("Gate 7A runtime boundary violated")
        if report.get("overallDecision") != "AUDIT_COMPLETE_GATE7B_AWAITING_CONFIRMATION":
            raise SystemExit("Gate 7A confirmation boundary drift")
        execution = report.get("externalExecution", {})
        numeric_fields = (
            "providerNetworkCalls", "realFundsTransactions", "realDeviceCommands", "realPeripheralCommands",
            "partnerContacts", "onsitePilots", "fullAlphaRuns", "productionDeployments",
        )
        if any(execution.get(field) != 0 for field in numeric_fields):
            raise SystemExit("Gate 7A external execution boundary violated")
        if execution.get("commercialClaimAllowed") is not False:
            raise SystemExit("Gate 7A commercial claim boundary violated")
    entries = [{
        "path": path.relative_to(bundle).as_posix(),
        "size": path.stat().st_size,
        "sha256": hashlib.sha256(path.read_bytes()).hexdigest(),
    } for path in files]
    result = {
        "schemaVersion": "1.0",
        "gate": GATE,
        "status": "PASS",
        "evidenceLevel": "STATIC_GOVERNANCE_AND_REPOSITORY_AUDIT",
        "decision": "AUDIT_COMPLETE_GATE7B_AWAITING_CONFIRMATION",
        "producers": sorted(producers),
        "fileCount": len(entries),
        "files": entries,
        "acceptedRequirementCount": 64,
        "confirmedGapCount": 18,
        "runtimeFilesChanged": 0,
        "externalExecution": reports[0]["externalExecution"],
        "limitations": [
            "All 18 gap requirements remain DRAFT.",
            "No formal business runtime or database migration was added.",
            "No SANDBOX, REAL_DEVICE, PILOT, FULL_ALPHA or PRODUCTION evidence was produced.",
            "Gate 7B still requires project initiator confirmation.",
        ],
    }
    canonical = json.dumps(result, sort_keys=True, separators=(",", ":"), ensure_ascii=False).encode()
    result["indexSha256"] = hashlib.sha256(canonical).hexdigest()
    target = pathlib.Path(args.output)
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({key: value for key, value in result.items() if key != "files"}, ensure_ascii=False))


if __name__ == "__main__":
    main()
