#!/usr/bin/env python3
"""聚合并校验 G9A-R3 准备阶段多执行器证据。"""
from __future__ import annotations

import argparse
import hashlib
import json
import pathlib
import subprocess


REQUIRED_PRODUCERS = {
    "governance-ubuntu",
    "governance-windows",
    "scope-integrity",
    "web-baseline",
    "flutter-ubuntu",
    "flutter-windows",
}


def sha256(path: pathlib.Path) -> str:
    value = hashlib.sha256()
    with path.open("rb") as handle:
        for block in iter(lambda: handle.read(1024 * 1024), b""):
            value.update(block)
    return value.hexdigest()


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--bundle-dir", required=True)
    parser.add_argument("--output", required=True)
    args = parser.parse_args()
    bundle = pathlib.Path(args.bundle_dir)
    producers = {item.name for item in bundle.iterdir() if item.is_dir()}
    missing = REQUIRED_PRODUCERS - producers
    if missing:
        raise AssertionError(f"缺少证据生产者: {sorted(missing)}")

    for producer in ("governance-ubuntu", "governance-windows"):
        summary_path = bundle / producer / "page-audit" / "summary.json"
        summary = json.loads(summary_path.read_text(encoding="utf-8"))
        if summary["result"] != "PASS" or summary["surfaceCount"] != 26 or summary["runtimeReachable"] != 24:
            raise AssertionError(f"{producer} 页面审计不完整")

    entries = []
    for path in sorted(item for item in bundle.rglob("*") if item.is_file()):
        entries.append({
            "path": path.relative_to(bundle).as_posix(),
            "bytes": path.stat().st_size,
            "sha256": sha256(path),
        })
    result = {
        "schemaVersion": "1.0",
        "gate": "T2-GATE9B-G9A-R3-PREP",
        "findingId": "G9A-UI-P1-001",
        "status": "PASS_PREP_EVIDENCE_COMPLETE",
        "findingState": "OPEN",
        "commitSha": subprocess.check_output(["git", "rev-parse", "HEAD"], text=True).strip(),
        "producerCount": len(REQUIRED_PRODUCERS),
        "fileCount": len(entries),
        "entries": entries,
        "decision": "PREP_CONDITIONAL_PASS_AWAITING_PROJECT_SPONSOR",
        "evidenceBoundary": "STATIC_AUDIT_AND_EXISTING_TEST_BASELINE_ONLY",
        "externalExecution": {
            "providerNetwork": 0,
            "realFunds": 0,
            "realDeviceCommands": 0,
            "realPeripheralCommands": 0,
            "partnerFieldExecution": 0,
            "fullAlpha": 0,
            "productionDeployment": 0
        }
    }
    output = pathlib.Path(args.output)
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(f"G9A-R3 PREP EVIDENCE OK: producers={len(REQUIRED_PRODUCERS)} files={len(entries)} finding=OPEN")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
