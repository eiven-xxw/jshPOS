#!/usr/bin/env python3
"""聚合 G9A-R3B 多执行器证据并生成 SHA-256 索引。"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
import pathlib


EXPECTED_PRODUCERS = {
    "governance-ubuntu",
    "governance-windows",
    "server",
    "web",
    "flutter-ubuntu",
    "flutter-windows",
    "android-database",
    "security",
}


def digest(path: pathlib.Path) -> str:
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
    missing = sorted(EXPECTED_PRODUCERS - producers)
    if missing:
        raise AssertionError(f"缺少 G9A-R3B 证据生产者: {missing}")
    files = [
        {"path": path.relative_to(bundle).as_posix(), "size": path.stat().st_size, "sha256": digest(path)}
        for path in sorted(item for item in bundle.rglob("*") if item.is_file())
    ]
    if not files:
        raise AssertionError("G9A-R3B 证据包为空")
    result = {
        "schemaVersion": "1.0",
        "gate": "T2-GATE9B-G9A-R3B",
        "findingId": "G9A-UI-P1-001",
        "batchState": "VERIFIED_CANDIDATE",
        "overallFindingState": "OPEN",
        "evidenceLevel": "INTERNAL_STATIC_AND_SOFTWARE_UI_INTERACTION",
        "commitSha": os.getenv("GITHUB_SHA", "LOCAL_UNCOMMITTED"),
        "runId": os.getenv("GITHUB_RUN_ID", "LOCAL"),
        "producers": sorted(EXPECTED_PRODUCERS),
        "fileCount": len(files),
        "files": files,
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
    print(f"G9A-R3B EVIDENCE OK: producers={len(EXPECTED_PRODUCERS)} files={len(files)}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
