#!/usr/bin/env python3
"""聚合 Gate 8C-Prep 双平台治理与仓库审计证据。"""
from __future__ import annotations

import argparse
import hashlib
import json
import pathlib


GATE = "T2-GATE8C-PREP"
REQUIRED_PRODUCERS = {"governance-ubuntu", "governance-windows", "repository-audit"}


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--bundle-dir", required=True, type=pathlib.Path)
    parser.add_argument("--output", required=True, type=pathlib.Path)
    args = parser.parse_args()
    bundle = args.bundle_dir.resolve()
    output = args.output.resolve()
    files = sorted(path for path in bundle.rglob("*") if path.is_file() and path.resolve() != output)
    producers = {path.relative_to(bundle).parts[0] for path in files if len(path.relative_to(bundle).parts) > 1}
    missing = sorted(REQUIRED_PRODUCERS - producers)
    if missing:
        raise SystemExit("missing Gate 8C-Prep producers: " + ", ".join(missing))
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
        raise SystemExit("Gate 8C-Prep reports missing or non-green")
    for report in reports:
        if report.get("openP0") != 3 or report.get("openP1") != 11 or report.get("internalCodeP0") != 1:
            raise SystemExit("Gate 8C-Prep finding counts drift")
        if report.get("runtimeFilesChanged") != 0 or report.get("databaseMigrationsChanged") != 0:
            raise SystemExit("Gate 8C-Prep runtime boundary violated")
        if report.get("decision") != "PREP_CONDITIONAL_PASS_GATE8C_RUNTIME_AWAITING_SPONSOR":
            raise SystemExit("Gate 8C-Prep confirmation boundary drift")
        if any(report.get("externalExecution", {}).values()):
            raise SystemExit("Gate 8C-Prep external execution boundary violated")
    entries = [{
        "path": path.relative_to(bundle).as_posix(),
        "size": path.stat().st_size,
        "sha256": hashlib.sha256(path.read_bytes()).hexdigest(),
    } for path in files]
    result = {
        "schemaVersion": "1.0",
        "gate": GATE,
        "status": "PASS",
        "evidenceCeiling": "STATIC_GOVERNANCE_AND_REPOSITORY_AUDIT",
        "decision": "PREP_CONDITIONAL_PASS_GATE8C_RUNTIME_AWAITING_SPONSOR",
        "producers": sorted(producers),
        "fileCount": len(entries),
        "files": entries,
        "openP0": 3,
        "openP1": 11,
        "internalCodeP0": 1,
        "runtimeFilesChanged": 0,
        "externalExecution": reports[0]["externalExecution"],
        "limitations": [
            "Open findings are audited, not remediated.",
            "T2-SEC-002, T2-MTN-001, T2-PERF-002 and T2-RDY-001 remain DRAFT.",
            "No SANDBOX, REAL_DEVICE, REAL_PERIPHERAL, PILOT, FULL_ALPHA or PRODUCTION evidence was produced.",
            "Gate 8C runtime still requires project sponsor confirmation."
        ],
    }
    canonical = json.dumps(result, sort_keys=True, separators=(",", ":"), ensure_ascii=False).encode()
    result["indexSha256"] = hashlib.sha256(canonical).hexdigest()
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({key: value for key, value in result.items() if key != "files"}, ensure_ascii=False))


if __name__ == "__main__":
    main()
