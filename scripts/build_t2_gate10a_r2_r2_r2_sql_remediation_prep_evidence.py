#!/usr/bin/env python3
"""聚合 R2-R2-R2 准备阶段 CI 制品摘要。"""
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
    parser.add_argument("--bundle-dir", required=True)
    parser.add_argument("--output", required=True)
    parser.add_argument("--commit", required=True)
    args = parser.parse_args()

    bundle = pathlib.Path(args.bundle_dir)
    files = sorted(path for path in bundle.rglob("*") if path.is_file())
    producers = sorted({path.relative_to(bundle).parts[0] for path in files})
    expected = ["baseline-contract", "governance-ubuntu", "governance-windows"]
    if producers != expected:
        raise SystemExit(f"producer mismatch: {producers} != {expected}")

    payload = {
        "schemaVersion": "1.0",
        "gate": "T2_GATE_10A_R2_R2_R2_SQL_REMEDIATION_PREP",
        "commit": args.commit,
        "decision": "REMEDIATION_PREP_CONDITIONAL_PASS_AWAITING_SPONSOR_CONFIRMATION",
        "findingState": "OPEN",
        "resourceFindingState": "PREPARED",
        "externalExecution": 0,
        "producers": producers,
        "files": [
            {"path": path.relative_to(bundle).as_posix(), "size": path.stat().st_size, "sha256": sha256(path)}
            for path in files
        ],
    }
    output = pathlib.Path(args.output)
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(payload, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


if __name__ == "__main__":
    main()
