#!/usr/bin/env python3
"""聚合 Gate 8D-Prep 双平台离线复核证据并生成 SHA-256 索引。"""
from __future__ import annotations

import argparse
import hashlib
import json
import pathlib


GATE = "T2-GATE8D-PREP-EXTERNAL-P0-LICENSE-ALPHA-RELEASE-ADMISSION"


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--bundle-dir", required=True)
    parser.add_argument("--output", required=True)
    args = parser.parse_args()
    bundle = pathlib.Path(args.bundle_dir)
    target = pathlib.Path(args.output).resolve()
    files = sorted(path for path in bundle.rglob("*") if path.is_file() and path.resolve() != target)
    producers = {path.relative_to(bundle).parts[0] for path in files
                 if len(path.relative_to(bundle).parts) > 1}
    required = {"governance-ubuntu", "governance-windows", "offline-boundary"}
    missing = sorted(required - producers)
    if missing:
        raise SystemExit(f"missing Gate 8D evidence producers: {missing}")
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
        raise SystemExit("Gate 8D producer reports missing or non-green")
    for report in reports:
        if any(value != 0 for value in report.get("externalExecution", {}).values()):
            raise SystemExit("Gate 8D external execution boundary violated")
        if report.get("overallDecision") != "PREPARED_NO_GO_EXTERNAL_EXECUTION_FULL_ALPHA_AND_RELEASE":
            raise SystemExit("Gate 8D NO-GO boundary drift")
    entries = [
        {
            "path": path.relative_to(bundle).as_posix(),
            "size": path.stat().st_size,
            "sha256": hashlib.sha256(path.read_bytes()).hexdigest(),
        }
        for path in files
    ]
    result = {
        "schemaVersion": "1.0",
        "gate": GATE,
        "status": "PASS",
        "evidenceLevel": "STATIC_GOVERNANCE_AND_OFFLINE_METADATA_REVIEW",
        "decision": "PREPARED_NO_GO_EXTERNAL_EXECUTION_FULL_ALPHA_AND_RELEASE",
        "producers": sorted(producers),
        "fileCount": len(entries),
        "files": entries,
        "externalExecution": {
            "providerNetworkCalls": 0,
            "realFundsTransactions": 0,
            "realDeviceCommands": 0,
            "realPeripheralCommands": 0,
            "partnerContacts": 0,
            "onsitePilots": 0,
            "fullAlphaRuns": 0,
            "productionDeployments": 0,
            "commercialTags": 0,
            "commercialClaims": 0,
        },
        "limitations": [
            "PAY 0/11", "HWD 0/2", "PRN 0/6", "PAR 0/5 AND WRITTEN INTENT 0/3",
            "LIC 0/3", "NO SANDBOX REAL_DEVICE REAL_PERIPHERAL PILOT FULL_ALPHA PRODUCTION OR COMMERCIAL",
        ],
    }
    canonical = json.dumps(result, sort_keys=True, separators=(",", ":"), ensure_ascii=False).encode()
    result["indexSha256"] = hashlib.sha256(canonical).hexdigest()
    output = pathlib.Path(args.output)
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({key: value for key, value in result.items() if key != "files"}, ensure_ascii=False))


if __name__ == "__main__":
    main()
