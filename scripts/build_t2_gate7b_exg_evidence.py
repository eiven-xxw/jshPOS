#!/usr/bin/env python3
"""生成 T2-EXG-001 跨平台、不可变 SHA-256 证据索引。"""
from __future__ import annotations

import argparse
import hashlib
import json
import pathlib


GATE = "T2-GATE7B-SPRINT-S20B-EXG001-RUNTIME"
REQUIRED = {
    "governance-ubuntu", "governance-windows", "server", "mysql",
    "flutter-ubuntu", "flutter-windows", "web", "security",
}


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--bundle-dir", required=True)
    parser.add_argument("--output", required=True)
    args = parser.parse_args()
    bundle = pathlib.Path(args.bundle_dir)
    output = pathlib.Path(args.output).resolve()
    files = sorted(path for path in bundle.rglob("*") if path.is_file() and path.resolve() != output)
    producers = {path.relative_to(bundle).parts[0] for path in files if len(path.relative_to(bundle).parts) > 1}
    missing = sorted(REQUIRED - producers)
    if missing:
        raise SystemExit(f"missing EXG evidence producers: {missing}")
    reports = []
    for path in files:
        if path.suffix != ".json":
            continue
        try:
            report = json.loads(path.read_text(encoding="utf-8"))
        except (UnicodeDecodeError, json.JSONDecodeError):
            continue
        if report.get("gate") == GATE:
            reports.append(report)
    if len(reports) < 2 or any(report.get("status") != "PASS" for report in reports):
        raise SystemExit("Ubuntu/Windows EXG governance reports missing or non-green")
    requirement_statuses = {report.get("requirementStatus") for report in reports}
    if len(requirement_statuses) != 1 or not requirement_statuses.issubset({"IN_PROGRESS", "VERIFIED"}):
        raise SystemExit("EXG governance requirement status missing or inconsistent")
    requirement_status = requirement_statuses.pop()
    for report in reports:
        if report.get("preservedStates", {}).get("T2-PAY-004") != "DRAFT":
            raise SystemExit("PAY004 serial boundary drift")
        external = report.get("externalExecution", {})
        if any(external.get(field) != 0 for field in (
            "providerNetworkCalls", "realFundsTransactions", "realDeviceCommands",
            "realPeripheralCommands", "partnerContacts", "onsitePilots",
            "fullAlphaRuns", "productionDeployments",
        )) or external.get("commercialClaimAllowed") is not False:
            raise SystemExit("external evidence boundary drift")
    entries = [{
        "path": path.relative_to(bundle).as_posix(),
        "size": path.stat().st_size,
        "sha256": hashlib.sha256(path.read_bytes()).hexdigest(),
    } for path in files]
    result = {
        "schemaVersion": "1.0", "gate": GATE, "status": "PASS",
        "evidenceLevel": "INTERNAL_SOFTWARE_EXECUTION",
        "decision": "EXG001_VERIFIED_AWAITING_SPONSOR_ACCEPTANCE"
                    if requirement_status == "VERIFIED"
                    else "EXG001_CANDIDATE_GREEN_AWAITING_RTM_VERIFICATION",
        "requirementStatus": requirement_status, "producers": sorted(producers),
        "fileCount": len(entries), "files": entries,
        "limitations": [
            "T2-PAY-004 remains DRAFT and T2-PAY-002 remains BLOCKED.",
            "No Provider, real funds, REAL_DEVICE, peripheral, PILOT, FULL_ALPHA or production evidence was produced.",
            "T2-EXG-001 is not ACCEPTED until project sponsor confirmation.",
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
