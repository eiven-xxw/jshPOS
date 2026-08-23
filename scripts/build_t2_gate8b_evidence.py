#!/usr/bin/env python3
"""聚合 Gate 8B 正式运行时与多平台证据，生成 SHA-256 索引。"""
from __future__ import annotations

import argparse
import hashlib
import json
import pathlib

REQUIRED = {
    "governance-ubuntu", "governance-windows", "server", "mysql-runtime", "web",
    "flutter-ubuntu", "flutter-windows", "runtime-api", "security",
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

    runtime_reports = []
    governance_reports = []
    for path in files:
        if path.suffix != ".json":
            continue
        try:
            report = json.loads(path.read_text(encoding="utf-8"))
        except Exception:
            continue
        if report.get("requirement_id") == "T2-E2E-005" and report.get("journey") == "SAA_TO_SUB_TO_SVC":
            runtime_reports.append(report)
        if report.get("gate") == "T2-GATE8B-SPRINT-S25":
            governance_reports.append(report)
    if len(runtime_reports) != 1 or runtime_reports[0].get("result") != "PASS":
        raise SystemExit("formal MySQL/Redis runtime API journey evidence missing or failed")
    runtime = runtime_reports[0]
    if runtime.get("direct_database_business_writes") != 0 or runtime.get("provider_network_calls") != 0:
        raise SystemExit("runtime journey broke database/provider boundary")
    if runtime.get("p0_open") != 0 or runtime.get("p1_open") != 0:
        raise SystemExit("open P0/P1 blocks Gate8B")
    if len(governance_reports) < 2 or any(item.get("status") != "PASS" for item in governance_reports):
        raise SystemExit("cross-platform governance evidence missing or failed")

    entries = [{
        "path": path.relative_to(bundle).as_posix(), "size": path.stat().st_size,
        "sha256": hashlib.sha256(path.read_bytes()).hexdigest(),
    } for path in files]
    result = {
        "schemaVersion": "1.0", "gate": "T2-GATE8B-SPRINT-S25", "status": "PASS",
        "decision": "CONDITIONAL_PASS_AWAITING_SPONSOR_CONFIRMATION",
        "requirement": "T2-E2E-005", "requirementStatus": "VERIFIED",
        "evidenceLevel": "INTERNAL_COMMERCIAL_OPERATIONS_CANDIDATE",
        "runtime": {
            "journey": runtime["journey"], "observations": runtime["observation_count"],
            "elapsedMs": runtime["elapsed_ms"], "maxApiDurationMs": runtime["max_api_duration_ms"],
            "mysql": "FORMAL_RUNTIME", "redis": "FORMAL_RUNTIME", "transport": "HTTP_REST",
        },
        "defects": {"p0Open": 0, "p1Open": 0},
        "externalExecution": {
            "providerNetwork": 0, "realFunds": 0, "realDevice": 0, "realPeripheral": 0,
            "partnerExecution": 0, "fullAlpha": 0, "production": 0, "commercialClaim": 0,
        },
        "producers": sorted(producers), "fileCount": len(entries), "files": entries,
        "limitations": [
            "Synthetic merchants, tenants and service data only.",
            "No billing, notification, sandbox, funds, devices, partners, Full Alpha or production evidence.",
        ],
    }
    result["indexSha256"] = hashlib.sha256(json.dumps(
        result, sort_keys=True, separators=(",", ":"), ensure_ascii=False).encode("utf-8")).hexdigest()
    target = pathlib.Path(args.output)
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


if __name__ == "__main__":
    main()
