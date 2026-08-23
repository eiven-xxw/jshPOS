#!/usr/bin/env python3
"""聚合 Gate 8B-Prep 多平台证据并生成 SHA-256 索引。"""
from __future__ import annotations

import argparse
import hashlib
import json
import pathlib

GATE = "T2-GATE8B-PREP"
REQUIRED = {
    "governance-ubuntu", "governance-windows", "formal-api", "server", "mysql-runtime",
    "web", "flutter-ubuntu", "flutter-windows", "security"
}


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--bundle-dir", required=True)
    parser.add_argument("--output", required=True)
    args = parser.parse_args()
    bundle = pathlib.Path(args.bundle_dir)
    files = sorted(path for path in bundle.rglob("*") if path.is_file())
    producers = {path.relative_to(bundle).parts[0] for path in files if len(path.relative_to(bundle).parts) > 1}
    missing = REQUIRED - producers
    if missing:
        raise SystemExit("missing Gate8B evidence producers: " + str(sorted(missing)))
    reports = []
    for path in files:
        if path.suffix != ".json":
            continue
        try:
            report = json.loads(path.read_text(encoding="utf-8"))
        except Exception:
            continue
        if report.get("gate") == GATE:
            reports.append(report)
    if len(reports) < 2 or any(report.get("status") != "PASS" for report in reports):
        raise SystemExit("Gate8B governance evidence failed")
    if any(any(report.get("externalExecution", {}).values()) for report in reports):
        raise SystemExit("Gate8B external execution is not zero")
    entries = [{
        "path": path.relative_to(bundle).as_posix(), "size": path.stat().st_size,
        "sha256": hashlib.sha256(path.read_bytes()).hexdigest()
    } for path in files]
    result = {
        "schemaVersion": "1.0", "gate": GATE, "status": "PASS",
        "decision": "GATE8B_PREP_CONDITIONAL_PASS_AWAITING_SPONSOR_CONFIRMATION",
        "evidenceLevel": "INTERNAL_SYNTHETIC_API_JOURNEY", "requirements": {
            "T2-SAA-001": "ACCEPTED", "T2-SUB-001": "ACCEPTED", "T2-SVC-001": "ACCEPTED"
        },
        "externalExecution": {
            "providerNetwork": 0, "realFunds": 0, "realDevice": 0, "realPeripheral": 0,
            "partnerExecution": 0, "fullAlpha": 0, "production": 0, "commercialClaim": 0
        },
        "producers": sorted(producers), "fileCount": len(entries), "files": entries,
        "limitations": [
            "Formal API journey uses synthetic data and accepted application-service boundaries.",
            "No sandbox, real device, real peripheral, partner, Full Alpha, production or commercial evidence."
        ]
    }
    result["indexSha256"] = hashlib.sha256(json.dumps(result, sort_keys=True, separators=(",", ":"),
                                                       ensure_ascii=False).encode()).hexdigest()
    target = pathlib.Path(args.output)
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


if __name__ == "__main__":
    main()
