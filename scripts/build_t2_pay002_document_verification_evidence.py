#!/usr/bin/env python3
"""生成 T2-PAY-002 离线验真缺件证据索引。"""
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
        raise SystemExit(f"missing PAY-002 evidence producers: {missing}")
    reports = []
    for path in files:
        if path.suffix == ".json":
            try:
                report = json.loads(path.read_text(encoding="utf-8-sig"))
            except json.JSONDecodeError:
                continue
            if report.get("gate") == "T2-PAY002-CONTROLLED-DOCUMENT-VERIFICATION":
                reports.append(report)
    if len(reports) < 3 or any(report.get("status") != "PASS" for report in reports):
        raise SystemExit("PAY-002 producer reports missing or non-green")
    for report in reports:
        if (report.get("requirementStatus") != "BLOCKED" or
                report.get("receivedMaterialCount") != 0 or
                report.get("verifiedDocumentStatus") != "NOT_ACHIEVED" or
                report.get("executionAdmission") != "NO_GO" or
                any(report.get("counters", {}).values())):
            raise SystemExit("PAY-002 evidence boundary drift")
    entries = [{
        "path": path.relative_to(bundle).as_posix(),
        "size": path.stat().st_size,
        "sha256": hashlib.sha256(path.read_bytes()).hexdigest(),
    } for path in files]
    result = {
        "schemaVersion": "1.0",
        "gate": "T2-PAY002-CONTROLLED-DOCUMENT-VERIFICATION",
        "status": "PASS",
        "evidenceLevel": "STATIC_GOVERNANCE_AND_MISSING_INTAKE",
        "decision": "NO_GO_MATERIAL_IDS_MISSING",
        "producers": sorted(producers),
        "fileCount": len(entries),
        "files": entries,
        "receivedMaterialCount": 0,
        "requiredMaterialCount": 11,
        "providerNetworkCalls": 0,
        "limitations": [
            "No controlled material ID was supplied.",
            "No VERIFIED_DOCUMENT evidence.",
            "No SANDBOX network or real-funds evidence.",
            "T2-PAY-002 remains BLOCKED."
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
