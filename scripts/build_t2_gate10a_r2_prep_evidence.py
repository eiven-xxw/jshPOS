#!/usr/bin/env python3
"""聚合 Gate 10A-R2 准备阶段跨执行器证据。"""
from __future__ import annotations

import argparse
import hashlib
import json
import pathlib


def sha256(path: pathlib.Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--bundle-dir", required=True, type=pathlib.Path)
    parser.add_argument("--output", required=True, type=pathlib.Path)
    parser.add_argument("--commit", required=True)
    args = parser.parse_args()
    files = sorted(path for path in args.bundle_dir.rglob("*") if path.is_file())
    if not files:
        raise SystemExit("Gate10A-R2 PREP evidence bundle is empty")
    summaries = [path for path in files if path.name == "summary.json"]
    if len(summaries) < 3:
        raise SystemExit("Gate10A-R2 PREP cross-runner audit summaries missing")
    for path in summaries:
        if json.loads(path.read_text(encoding="utf-8"))["result"] != "PASS":
            raise SystemExit(f"Gate10A-R2 PREP audit failed: {path}")
    result = {
        "schemaVersion": "1.0", "gate": "T2_GATE_10A_R2_PREP", "commitSha": args.commit,
        "result": "PASS", "evidenceCeiling": "INTERNAL_SERVER_DATABASE_MAINTAINABILITY_PREPARED",
        "findingState": "PREPARED_AWAITING_SPONSOR_CONFIRMATION",
        "files": [{"path": path.relative_to(args.bundle_dir).as_posix(), "size": path.stat().st_size, "sha256": sha256(path)} for path in files],
        "runtimeChangesApplied": 0, "databaseChangesApplied": 0, "externalExecution": 0,
        "recommendation": "CONDITIONAL_PASS_AWAITING_SPONSOR_RUNTIME_ADMISSION",
    }
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(f"T2 Gate10A R2 PREP EVIDENCE PASS: files={len(files)}")


if __name__ == "__main__":
    main()
