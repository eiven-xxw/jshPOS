#!/usr/bin/env python3
"""聚合 T2-SAA-001 多执行器证据并生成不可变 SHA-256 索引。"""
from __future__ import annotations

import argparse
import hashlib
import json
import pathlib


GATE = "T2-GATE8A-SPRINT-S24A-SAA001"
REQUIRED = {"governance-ubuntu", "governance-windows", "server", "mysql-runtime",
            "web", "flutter-ubuntu", "flutter-windows", "runtime-stack", "security"}
ZERO_FIELDS = ("realBilling", "providerNetwork", "realFunds", "realDevice", "realPeripheral",
               "partnerExecution", "fullAlpha", "production", "commercialClaim")


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
        raise SystemExit(f"missing SAA001 evidence producers: {missing}")
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
        raise SystemExit("Ubuntu/Windows SAA001 governance reports missing or non-green")
    statuses = {report.get("requirementStatus") for report in reports}
    if statuses != {"VERIFIED"}:
        raise SystemExit(f"SAA001 is not consistently VERIFIED: {statuses}")
    for report in reports:
        if any(report.get("externalExecution", {}).get(field) != 0 for field in ZERO_FIELDS):
            raise SystemExit("external execution boundary drift")
        states = report.get("preservedStates", {})
        if states.get("T2-SUB-001") != "DRAFT" or states.get("T2-SVC-001") != "DRAFT":
            raise SystemExit("SUB/SVC state drift")
    entries = [{"path": path.relative_to(bundle).as_posix(), "size": path.stat().st_size,
                "sha256": hashlib.sha256(path.read_bytes()).hexdigest()} for path in files]
    result = {
        "schemaVersion": "1.0", "gate": GATE, "status": "PASS",
        "requirementStatus": "VERIFIED", "evidenceLevel": "INTERNAL_SYNTHETIC_SOFTWARE_ONLY",
        "decision": "SAA001_VERIFIED_AWAITING_SPONSOR_ACCEPTANCE",
        "producers": sorted(producers), "fileCount": len(entries), "files": entries,
        "limitations": [
            "Only fictional tenants and synthetic software execution were used.",
            "No billing, Provider network, real funds, device, peripheral, partner, Full Alpha or production execution occurred.",
            "T2-SUB-001 and T2-SVC-001 remain DRAFT.",
            "T2-SAA-001 is not ACCEPTED until project sponsor confirmation."
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
