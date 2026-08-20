#!/usr/bin/env python3
"""汇总 Gate 6C 首批收件双平台守卫证据并生成索引。"""
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
        raise SystemExit(f"missing Gate 6C intake evidence producers: {missing}")
    entries = [{
        "path": path.relative_to(bundle).as_posix(),
        "size": path.stat().st_size,
        "sha256": hashlib.sha256(path.read_bytes()).hexdigest(),
    } for path in files]
    result = {
        "gate": "T2-GATE6C-EVIDENCE-INTAKE",
        "status": "PASS",
        "evidenceLevel": "PUBLIC_OFFICIAL_DOCUMENT_AND_STATIC_GOVERNANCE",
        "producers": sorted(producers),
        "fileCount": len(entries),
        "files": entries,
        "externalExecution": {
            "providerNetworkCalls": 0,
            "realDeviceCommands": 0,
            "onsitePilots": 0,
            "fullAlphaExecutions": 0
        },
        "limitations": [
            "No authorized SANDBOX execution evidence.",
            "No REAL_DEVICE execution evidence.",
            "No verified partner or PILOT evidence.",
            "No Alpha or commercial claim."
        ],
    }
    target = pathlib.Path(args.output)
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({key: value for key, value in result.items() if key != "files"}, ensure_ascii=False))


if __name__ == "__main__":
    main()
