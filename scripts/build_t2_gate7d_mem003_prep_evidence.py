#!/usr/bin/env python3
"""汇总 T2-MEM-003 准备阶段多平台证据并生成 SHA-256 索引。"""
from __future__ import annotations

import argparse
import hashlib
import json
import pathlib


GATE = "T2-GATE7D-SPRINT-S22A-MEM003-PREP"
PRODUCERS = {"governance-ubuntu", "governance-windows", "scope-boundary"}


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--bundle-dir", required=True)
    parser.add_argument("--output", required=True)
    args = parser.parse_args()
    bundle = pathlib.Path(args.bundle_dir)
    output = pathlib.Path(args.output).resolve()
    files = sorted(path for path in bundle.rglob("*") if path.is_file() and path.resolve() != output)
    producers = {path.relative_to(bundle).parts[0] for path in files if len(path.relative_to(bundle).parts) > 1}
    missing = sorted(PRODUCERS - producers)
    if missing:
        raise SystemExit(f"missing MEM003 prep producers: {missing}")
    reports = []
    for path in files:
        if path.suffix != ".json":
            continue
        try:
            item = json.loads(path.read_text(encoding="utf-8"))
        except json.JSONDecodeError:
            continue
        if item.get("gate") == GATE:
            reports.append(item)
    if len(reports) < 3 or any(item.get("status") != "PASS" for item in reports):
        raise SystemExit("MEM003 prep producer reports missing or non-green")
    for item in reports:
        if item.get("requirementStatus") != "DRAFT":
            raise SystemExit("MEM003 was admitted before sponsor confirmation")
        if item.get("runtimeFilesChanged") != 0 or item.get("databaseMigrationsAdded") != 0:
            raise SystemExit("MEM003 prep runtime boundary violated")
        if any(value != 0 for value in item.get("externalExecution", {}).values()):
            raise SystemExit("MEM003 prep external boundary violated")
    entries = [{
        "path": path.relative_to(bundle).as_posix(), "size": path.stat().st_size,
        "sha256": hashlib.sha256(path.read_bytes()).hexdigest(),
    } for path in files]
    result = {
        "schemaVersion": "1.0", "gate": GATE, "status": "PASS",
        "decision": "CONDITIONAL_GO_RECOMMENDED_AWAITING_SPONSOR",
        "evidenceLevel": "STATIC_DESIGN_AND_CONTRACT_PREP",
        "requirementStatus": "DRAFT", "runtimeFilesChanged": 0,
        "databaseMigrationsAdded": 0, "externalExecution": reports[0]["externalExecution"],
        "producers": sorted(producers), "fileCount": len(entries), "files": entries,
    }
    canonical = json.dumps(result, sort_keys=True, separators=(",", ":"), ensure_ascii=False).encode()
    result["indexSha256"] = hashlib.sha256(canonical).hexdigest()
    target = pathlib.Path(args.output)
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({key: value for key, value in result.items() if key != "files"}, ensure_ascii=False))


if __name__ == "__main__":
    main()
