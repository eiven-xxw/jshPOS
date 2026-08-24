#!/usr/bin/env python3
"""构建 T2-PERF-002 跨平台完整 CI 不可变证据索引。"""
from __future__ import annotations

import argparse
import hashlib
import json
import pathlib


GATE = "T2-GATE8C-SPRINT-S26C"
REQUIRED_PRODUCERS = {
    "governance-ubuntu", "governance-windows", "server", "owner-capacity", "web",
    "flutter-ubuntu", "flutter-windows", "pos-performance", "formal-runtime", "security",
}


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
        raise SystemExit("missing T2-PERF-002 producers: " + ", ".join(missing))
    reports = []
    performance = []
    for path in files:
        if path.suffix != ".json":
            continue
        try:
            report = json.loads(path.read_text(encoding="utf-8"))
        except (json.JSONDecodeError, UnicodeDecodeError):
            continue
        if report.get("gate") == GATE and report.get("decision"):
            reports.append(report)
        if report.get("gate") == GATE and report.get("classification") == "INTERNAL_FULL_STACK_PERFORMANCE_CANDIDATE":
            performance.append(report)
    if len(reports) < 3 or any(report.get("status") != "PASS" for report in reports):
        raise SystemExit("T2-PERF-002 governance reports missing or non-green")
    if len(performance) != 1 or performance[0].get("status") != "PASS":
        raise SystemExit("T2-PERF-002 performance evidence missing or non-green")
    for report in reports:
        if report.get("requirementStatus") != "VERIFIED" or report.get("databaseMigrationsChanged") != 0 \
                or report.get("newBusinessCapabilities") != 0 or any(report.get("externalExecution", {}).values()):
            raise SystemExit("T2-PERF-002 closure or scope evidence drift")
    perf = performance[0]
    if perf.get("openPerformanceP0") != 0 or perf.get("openPerformanceP1") != 0 \
            or perf.get("commercialSla") is not False or any(perf.get("externalExecution", {}).values()):
        raise SystemExit("T2-PERF-002 result boundary drift")
    entries = [{
        "path": path.relative_to(bundle).as_posix(), "size": path.stat().st_size,
        "sha256": hashlib.sha256(path.read_bytes()).hexdigest(),
    } for path in files]
    result = {
        "schemaVersion": "1.0", "gate": GATE, "status": "PASS",
        "requirementId": "T2-PERF-002", "requirementStatus": "VERIFIED",
        "evidenceCeiling": "INTERNAL_FULL_STACK_PERFORMANCE_CANDIDATE",
        "decision": "CONDITIONAL_PASS_AWAITING_SPONSOR_ACCEPTANCE",
        "producers": sorted(producers), "fileCount": len(entries), "files": entries,
        "closedFindings": ["G8C-PERF-P1-001", "G8C-PERF-P1-002"],
        "externalExecution": perf["externalExecution"],
        "limitations": [
            "T2-PERF-002 remains VERIFIED until project sponsor acceptance.",
            "T2-RDY-001 remains DRAFT.",
            "No SANDBOX, REAL_DEVICE, REAL_PERIPHERAL, PILOT, FULL_ALPHA, PRODUCTION or commercial SLA evidence was produced.",
        ],
    }
    canonical = json.dumps(result, sort_keys=True, separators=(",", ":"), ensure_ascii=False).encode()
    result["indexSha256"] = hashlib.sha256(canonical).hexdigest()
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({key: value for key, value in result.items() if key != "files"}, ensure_ascii=False))


if __name__ == "__main__":
    main()
