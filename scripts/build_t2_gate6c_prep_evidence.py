#!/usr/bin/env python3
"""汇总 Gate 6C-Prep 双平台治理证据并生成可校验索引。"""
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
    files = sorted(path for path in bundle.rglob("*") if path.is_file() and path.resolve() != pathlib.Path(args.output).resolve())
    producers = {path.relative_to(bundle).parts[0] for path in files if len(path.relative_to(bundle).parts) > 1}
    required = {"governance-ubuntu", "governance-windows", "security-boundary"}
    missing = sorted(required - producers)
    if missing:
        raise SystemExit(f"missing Gate 6C evidence producers: {missing}")
    entries = [{
        "path": path.relative_to(bundle).as_posix(),
        "size": path.stat().st_size,
        "sha256": hashlib.sha256(path.read_bytes()).hexdigest(),
    } for path in files]
    result = {
        "gate": "T2-GATE6C-PREP",
        "status": "PASS",
        "evidenceLevel": "STATIC_GOVERNANCE",
        "producers": sorted(producers),
        "fileCount": len(entries),
        "files": entries,
        "limitations": [
            "No SANDBOX evidence.", "No REAL_DEVICE evidence.", "No PARTNER_VERIFIED evidence.",
            "No Alpha or commercial claim."
        ],
    }
    target = pathlib.Path(args.output)
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({key: value for key, value in result.items() if key != "files"}, ensure_ascii=False))


if __name__ == "__main__":
    main()
