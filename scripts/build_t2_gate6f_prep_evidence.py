#!/usr/bin/env python3
"""汇总 Gate 6F 双平台离线治理证据并生成 SHA-256 索引。"""
from __future__ import annotations

import argparse
import hashlib
import json
import pathlib


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--bundle-dir", required=True)
    parser.add_argument("--output", required=True)
    args = parser.parse_args()
    bundle = pathlib.Path(args.bundle_dir)
    output = pathlib.Path(args.output).resolve()
    files = sorted(path for path in bundle.rglob("*") if path.is_file() and path.resolve() != output)
    producers = {path.relative_to(bundle).parts[0] for path in files if len(path.relative_to(bundle).parts) > 1}
    required = {"governance-ubuntu", "governance-windows", "offline-boundary"}
    missing = sorted(required - producers)
    if missing:
        raise SystemExit(f"missing Gate 6F evidence producers: {missing}")
    reports = []
    for path in files:
        if path.name.endswith(".json"):
            try:
                report = json.loads(path.read_text(encoding="utf-8"))
            except json.JSONDecodeError:
                continue
            if report.get("gate") == "T2-GATE6F-EXTERNAL-P0-UAT-PREP":
                reports.append(report)
    if len(reports) < 3 or any(report.get("status") != "PASS" for report in reports):
        raise SystemExit("Gate 6F producer reports missing or non-green")
    for report in reports:
        execution = report.get("externalExecution", {})
        if any(execution.get(field) != 0 for field in
               ("providerNetworkCalls", "realDeviceCommands", "partnerContacts", "onsitePilots",
                "fullAlphaRuns", "productionDeployments")):
            raise SystemExit("Gate 6F external execution boundary violated")
        if report.get("overallDecision") != "NO_GO_EXTERNAL_EXECUTION_FULL_ALPHA_AND_RELEASE":
            raise SystemExit("Gate 6F NO-GO boundary drift")
    entries = [{
        "path": path.relative_to(bundle).as_posix(),
        "size": path.stat().st_size,
        "sha256": hashlib.sha256(path.read_bytes()).hexdigest(),
    } for path in files]
    result = {
        "schemaVersion": "1.0",
        "gate": "T2-GATE6F-EXTERNAL-P0-UAT-PREP",
        "status": "PASS",
        "evidenceLevel": "STATIC_GOVERNANCE",
        "decision": "NO_GO_EXTERNAL_EXECUTION_FULL_ALPHA_AND_RELEASE",
        "producers": sorted(producers),
        "fileCount": len(entries),
        "files": entries,
        "externalExecution": {
            "providerNetworkCalls": 0,
            "realDeviceCommands": 0,
            "partnerContacts": 0,
            "onsitePilots": 0,
            "fullAlphaRuns": 0,
            "productionDeployments": 0
        },
        "limitations": [
            "No authorized SANDBOX execution evidence.",
            "No REAL_DEVICE execution evidence.",
            "No verified partner or PILOT evidence.",
            "No FULL_ALPHA, PRODUCTION or commercial evidence."
        ]
    }
    canonical = json.dumps(result, sort_keys=True, separators=(",", ":"), ensure_ascii=False).encode()
    result["indexSha256"] = hashlib.sha256(canonical).hexdigest()
    target = pathlib.Path(args.output)
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({key: value for key, value in result.items() if key != "files"}, ensure_ascii=False))


if __name__ == "__main__":
    main()
