#!/usr/bin/env python3
"""聚合 Gate 8A-Prep 双平台和范围门禁证据并生成 SHA-256 索引。"""
from __future__ import annotations

import argparse
import hashlib
import json
import pathlib


GATE = "T2-GATE8A-PREP-COMMERCIAL-SAAS-OPERATIONS"


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
        raise SystemExit(f"missing Gate 8A-Prep evidence producers: {missing}")
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
        raise SystemExit("Gate 8A-Prep producer reports missing or non-green")
    for report in reports:
        if any(report.get("runtimeFlags", {}).values()):
            raise SystemExit("Gate 8A-Prep runtime boundary violated")
        if any(value != 0 for value in report.get("externalExecution", {}).values()):
            raise SystemExit("Gate 8A-Prep external execution boundary violated")
        if report.get("requirements") != {
            "T2-SAA-001": "DRAFT", "T2-SUB-001": "DRAFT", "T2-SVC-001": "DRAFT"}:
            raise SystemExit("Gate 8A-Prep requirement state drift")
    entries = [{"path": path.relative_to(bundle).as_posix(), "size": path.stat().st_size,
                "sha256": hashlib.sha256(path.read_bytes()).hexdigest()} for path in files]
    result = {
        "schemaVersion": "1.0", "gate": GATE, "status": "PASS",
        "evidenceLevel": "STATIC_DESIGN_AND_CONTRACT_PREP",
        "decision": "PREPARED_CONDITIONAL_PASS_AWAITING_SPONSOR_NO_RUNTIME_ADMISSION",
        "producers": sorted(producers), "fileCount": len(entries), "files": entries,
        "requirements": {"T2-SAA-001": "DRAFT", "T2-SUB-001": "DRAFT", "T2-SVC-001": "DRAFT"},
        "runtime": {"implementation":0,"migrationFile":0,"controller":0,"vueBusinessPage":0,
                    "flutterBusinessPage":0,"backgroundJob":0,"dependencyChange":0},
        "externalExecution": {"providerNetwork":0,"realFunds":0,"realDevice":0,
                              "realPeripheral":0,"partnerExecution":0,"fullAlpha":0,
                              "production":0,"commercialClaim":0},
        "limitations": ["STATIC design only", "SAA/SUB/SVC remain DRAFT",
                        "SUB depends on accepted SAA", "SVC depends on accepted SAA and SUB",
                        "No production tenant onboarding billing commercial SLA or external execution"],
    }
    canonical = json.dumps(result, sort_keys=True, separators=(",", ":"), ensure_ascii=False).encode()
    result["indexSha256"] = hashlib.sha256(canonical).hexdigest()
    target = pathlib.Path(args.output)
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({key: value for key, value in result.items() if key != "files"}, ensure_ascii=False))


if __name__ == "__main__":
    main()
