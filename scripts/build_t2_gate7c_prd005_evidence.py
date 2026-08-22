#!/usr/bin/env python3
"""生成 T2-PRD-005 跨平台、不可变 SHA-256 证据索引。"""
from __future__ import annotations

import argparse
import hashlib
import json
import pathlib


GATE = "T2-GATE7C-SPRINT-S21A-PRD005"
REQUIRED = {
    "governance-ubuntu", "governance-windows", "server", "mysql", "mysql-order",
    "flutter-ubuntu", "flutter-windows", "web", "security",
}
ZERO_FIELDS = (
    "providerNetworkCalls", "realFundsTransactions", "realDeviceCommands",
    "realPeripheralCommands", "fullAlphaRuns", "productionDeployments",
)


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--bundle-dir", required=True)
    parser.add_argument("--output", required=True)
    args = parser.parse_args()
    bundle = pathlib.Path(args.bundle_dir)
    output = pathlib.Path(args.output).resolve()
    files = sorted(path for path in bundle.rglob("*") if path.is_file() and path.resolve() != output)
    producers = {
        path.relative_to(bundle).parts[0]
        for path in files
        if len(path.relative_to(bundle).parts) > 1
    }
    missing = sorted(REQUIRED - producers)
    if missing:
        raise SystemExit(f"missing PRD005 evidence producers: {missing}")
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
        raise SystemExit("Ubuntu/Windows PRD005 governance reports missing or non-green")
    statuses = {report.get("requirementStatus") for report in reports}
    if len(statuses) != 1 or not statuses.issubset({"IN_PROGRESS", "VERIFIED"}):
        raise SystemExit("PRD005 requirement status missing or inconsistent")
    status = statuses.pop()
    for report in reports:
        external = report.get("externalExecution", {})
        if any(external.get(field) != 0 for field in ZERO_FIELDS) \
                or external.get("commercialClaimAllowed") is not False:
            raise SystemExit("external evidence boundary drift")
        preserved = report.get("preservedStates", {})
        if preserved.get("T2-HWD-001") != "BLOCKED" or preserved.get("T2-LBL-001") != "DRAFT":
            raise SystemExit("hardware or next-requirement boundary drift")
    entries = [{
        "path": path.relative_to(bundle).as_posix(),
        "size": path.stat().st_size,
        "sha256": hashlib.sha256(path.read_bytes()).hexdigest(),
    } for path in files]
    result = {
        "schemaVersion": "1.0",
        "gate": GATE,
        "status": "PASS",
        "evidenceLevel": "INTERNAL_SOFTWARE_EXECUTION",
        "decision": "PRD005_VERIFIED_AWAITING_SPONSOR_ACCEPTANCE" if status == "VERIFIED"
                    else "PRD005_CANDIDATE_GREEN_AWAITING_RTM_VERIFICATION",
        "requirementStatus": status,
        "producers": sorted(producers),
        "fileCount": len(entries),
        "files": entries,
        "limitations": [
            "T2-HWD-001 remains BLOCKED; only keyboard-wedge and synthetic scan input were exercised.",
            "No electronic scale, vendor scanner SDK, REAL_DEVICE, Provider, FULL_ALPHA or production evidence was produced.",
            "T2-PRD-005 is not ACCEPTED until project sponsor confirmation.",
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
