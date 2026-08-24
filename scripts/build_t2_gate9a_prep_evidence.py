#!/usr/bin/env python3
"""聚合 Gate 9A-Prep 双平台原始审计证据并生成 SHA-256 索引。"""
from __future__ import annotations

import argparse
import hashlib
import json
import pathlib


def sha256(path: pathlib.Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--bundle-dir", required=True, type=pathlib.Path)
    parser.add_argument("--output", required=True, type=pathlib.Path)
    args = parser.parse_args()
    bundle = args.bundle_dir.resolve()
    summaries = sorted(bundle.glob("**/summary.json"))
    if len(summaries) < 2:
        raise SystemExit("Gate9A evidence requires Ubuntu and Windows summaries")
    semantic = []
    for source in summaries:
        value = json.loads(source.read_text(encoding="utf-8"))
        semantic.append({key: value[key] for key in (
            "acceptedRequirementCount", "ownerModuleCount", "moduleTotals", "uiTotals",
            "apiTotals", "productionMarkers", "findings", "hardFailures", "result", "recommendation"
        )})
    if any(item != semantic[0] for item in semantic[1:]):
        raise SystemExit("Ubuntu and Windows normalized Gate9A evidence differ")
    if semantic[0]["result"] != "PASS" or semantic[0]["hardFailures"]:
        raise SystemExit("Gate9A raw audit did not pass")
    files = sorted(path for path in bundle.rglob("*") if path.is_file() and path != args.output.resolve())
    result = {
        "schemaVersion": "1.0",
        "requirementId": "T2-CMP-001",
        "evidenceLevel": "STATIC_GOVERNANCE_AND_REPOSITORY_AUDIT",
        "normalizedSummary": semantic[0],
        "files": [
            {"path": path.relative_to(bundle).as_posix(), "size": path.stat().st_size, "sha256": sha256(path)}
            for path in files
        ],
        "result": "PASS",
        "recommendation": "CONDITIONAL_PASS_AWAITING_SPONSOR_CONFIRMATION"
    }
    output = args.output.resolve()
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(f"T2 Gate9A EVIDENCE OK: files={len(files)} P1={semantic[0]['findings']['openP1']}")


if __name__ == "__main__":
    main()
