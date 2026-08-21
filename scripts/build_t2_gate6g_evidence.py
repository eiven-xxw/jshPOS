#!/usr/bin/env python3
"""为 Gate 6G 同一 run 的独立制品生成去重摘要索引。"""

from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path

REQUIRED_STAGE_DIRS = {"governance", "server", "mysql", "pos-linux", "pos-windows", "web", "security"}


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--bundle-dir", required=True)
    parser.add_argument("--output", required=True)
    args = parser.parse_args()
    bundle = Path(args.bundle_dir)
    output = Path(args.output)
    missing = sorted(name for name in REQUIRED_STAGE_DIRS if not (bundle / name).is_dir())
    if missing:
        raise SystemExit(f"Gate6G evidence directories missing: {missing}")
    files = []
    for source in sorted(path for path in bundle.rglob("*") if path.is_file() and path.resolve() != output.resolve()):
        files.append({
            "path": source.relative_to(bundle).as_posix(),
            "size": source.stat().st_size,
            "sha256": hashlib.sha256(source.read_bytes()).hexdigest(),
        })
    if not files:
        raise SystemExit("Gate6G evidence bundle is empty")
    result = {
        "schemaVersion": "1.0",
        "gate": "T2-GATE6G-S17",
        "evidenceCeiling": "INTERNAL_V1_CORE_CANDIDATE",
        "externalExecution": 0,
        "fileCount": len(files),
        "files": files,
    }
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(f"Gate6G evidence index OK: {len(files)} files")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
