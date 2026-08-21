#!/usr/bin/env python3
"""汇总 Gate 7B S20-B 多平台准备证据并生成 SHA-256 索引。"""
from __future__ import annotations

import argparse
import hashlib
import json
import pathlib


GATE = "T2-GATE7B-SPRINT-S20B-SECOND-BATCH-PREP"
REQUIRED_PRODUCERS = {"governance-ubuntu", "governance-windows", "scope-boundary"}


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--bundle-dir", required=True)
    parser.add_argument("--output", required=True)
    args = parser.parse_args()
    bundle = pathlib.Path(args.bundle_dir)
    output = pathlib.Path(args.output).resolve()
    files = sorted(path for path in bundle.rglob("*") if path.is_file() and path.resolve() != output)
    producers = {path.relative_to(bundle).parts[0] for path in files if len(path.relative_to(bundle).parts) > 1}
    missing = sorted(REQUIRED_PRODUCERS - producers)
    if missing:
        raise SystemExit(f"missing S20-B evidence producers: {missing}")
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
        raise SystemExit("S20-B producer reports missing or non-green")
    for report in reports:
        if report.get("prepRequirements") != {"T2-EXG-001": "DRAFT", "T2-PAY-004": "DRAFT"}:
            raise SystemExit("S20-B DRAFT requirement boundary drift")
        if report.get("runtimeFilesChanged") != 0 or report.get("databaseMigrationsAdded") != 0:
            raise SystemExit("S20-B runtime or migration boundary violated")
        if report.get("overallDecision") != "PREP_COMPLETE_SECOND_BATCH_AWAITING_CONFIRMATION":
            raise SystemExit("S20-B confirmation boundary drift")
        external = report.get("externalExecution", {})
        for field in ("providerNetworkCalls", "realFundsTransactions", "realDeviceCommands",
                      "realPeripheralCommands", "partnerContacts", "onsitePilots", "fullAlphaRuns",
                      "productionDeployments"):
            if external.get(field) != 0:
                raise SystemExit(f"S20-B external boundary violated: {field}")
        if external.get("commercialClaimAllowed") is not False:
            raise SystemExit("S20-B commercial claim boundary violated")
    entries = [{
        "path": path.relative_to(bundle).as_posix(), "size": path.stat().st_size,
        "sha256": hashlib.sha256(path.read_bytes()).hexdigest(),
    } for path in files]
    result = {
        "schemaVersion": "1.0", "gate": GATE, "status": "PASS",
        "evidenceLevel": "STATIC_DESIGN_AND_REPOSITORY_AUDIT",
        "decision": "PREP_COMPLETE_SECOND_BATCH_AWAITING_CONFIRMATION",
        "producers": sorted(producers), "fileCount": len(entries), "files": entries,
        "prepRequirements": {"T2-EXG-001": "DRAFT", "T2-PAY-004": "DRAFT"},
        "runtimeFilesChanged": 0, "databaseMigrationsAdded": 0,
        "externalExecution": reports[0]["externalExecution"],
        "limitations": [
            "T2-EXG-001 and T2-PAY-004 remain DRAFT.",
            "No formal runtime or database migration was added.",
            "No Provider, REAL_DEVICE, PILOT, FULL_ALPHA or PRODUCTION evidence was produced.",
            "Second-batch implementation requires sponsor confirmation and serial admission.",
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
