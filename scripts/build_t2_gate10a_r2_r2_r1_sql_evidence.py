#!/usr/bin/env python3
"""聚合 Gate 10A-R2-R2-R1 完整 CI 制品并生成不可变摘要索引。"""
from __future__ import annotations

import argparse
import hashlib
import json
import pathlib


REQUIRED = {
    "governance-ubuntu", "governance-windows", "server", "mysql84-baseline", "web",
    "flutter-ubuntu", "flutter-windows", "android", "security", "gate9c-regression"
}


def sha256(path: pathlib.Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--bundle-dir", required=True, type=pathlib.Path)
    parser.add_argument("--output", required=True, type=pathlib.Path)
    parser.add_argument("--commit", required=True)
    args = parser.parse_args()
    producers = {path.name for path in args.bundle_dir.iterdir() if path.is_dir()}
    if producers != REQUIRED:
        raise SystemExit(f"producer drift: {sorted(producers)}")
    files = []
    for path in sorted(item for item in args.bundle_dir.rglob("*") if item.is_file()):
        files.append({
            "path": path.relative_to(args.bundle_dir).as_posix(),
            "bytes": path.stat().st_size,
            "sha256": sha256(path),
        })
    required_suffixes = (
        "mysql84-baseline/sql-executable/query-results.json",
        "mysql84-baseline/sql-executable/jdbc-query-counts.json",
        "mysql84-baseline/sql-executable/permission-boundary.json",
        "mysql84-baseline/sql-executable/decision-candidates.json",
    )
    paths = {item["path"] for item in files}
    missing = [suffix for suffix in required_suffixes if not any(path.endswith(suffix) for path in paths)]
    if missing:
        raise SystemExit("missing executable evidence: " + ", ".join(missing))
    result = {
        "schemaVersion": "1.0",
        "gate": "T2_GATE_10A_R2_R2_R1_SQL_EXECUTABLE_BASELINE",
        "commit": args.commit,
        "producers": sorted(producers),
        "fileCount": len(files),
        "files": files,
        "result": "PASS",
        "evidenceBoundary": "SYNTHETIC_MYSQL84_NOT_PRODUCTION_SLA",
    }
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(f"SQL executable evidence PASS: producers={len(producers)} files={len(files)}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
