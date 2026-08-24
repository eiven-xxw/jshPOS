#!/usr/bin/env python3
"""生成 G9A-R2 准备阶段不可变证据索引。"""
from __future__ import annotations

import argparse
import hashlib
import json
import pathlib
import subprocess


ROOT = pathlib.Path(__file__).resolve().parents[1]
REQUIRED_PRODUCERS = {"governance-ubuntu", "governance-windows", "assembly-baseline", "scope-integrity"}


def sha256(path: pathlib.Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for block in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


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
    entries = []
    for path in sorted(item for item in bundle.rglob("*") if item.is_file()):
        entries.append({
            "path": path.relative_to(bundle).as_posix(),
            "bytes": path.stat().st_size,
            "sha256": sha256(path),
        })
    result = {
        "schemaVersion": "1.0",
        "gate": "T2-GATE9B-G9A-R2-PREP",
        "findingId": "G9A-ASM-P1-001",
        "status": "PASS_PREP_EVIDENCE_COMPLETE",
        "findingState": "OPEN",
        "commitSha": subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=ROOT, text=True).strip(),
        "producerCount": len(REQUIRED_PRODUCERS),
        "fileCount": len(entries),
        "entries": entries,
        "decision": "PREP_READY_AWAITING_PROJECT_SPONSOR",
        "externalExecution": {
            "providerNetwork": 0,
            "realFunds": 0,
            "realDeviceCommands": 0,
            "realPeripheralCommands": 0,
            "partnerFieldExecution": 0,
            "fullAlpha": 0,
            "productionDeployment": 0,
        },
    }
    output = pathlib.Path(args.output)
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(f"G9A-R2 PREP EVIDENCE OK: producers=4 files={len(entries)} finding=OPEN")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
