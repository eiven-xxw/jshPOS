#!/usr/bin/env python3
"""聚合 G9A-R4 准备阶段跨平台审计与基线回归证据。"""
from __future__ import annotations

import argparse
import hashlib
import json
import pathlib


def digest(path: pathlib.Path) -> str:
    value = hashlib.sha256()
    with path.open("rb") as handle:
        for block in iter(lambda: handle.read(1024 * 1024), b""):
            value.update(block)
    return value.hexdigest()


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--bundle-dir", required=True, type=pathlib.Path)
    parser.add_argument("--output", required=True, type=pathlib.Path)
    args = parser.parse_args()
    producers = ["governance-ubuntu", "governance-windows", "scope-integrity", "server-baseline", "web-baseline", "flutter-ubuntu", "flutter-windows"]
    missing = [name for name in producers if not (args.bundle_dir / name).is_dir()]
    if missing:
        raise SystemExit(f"missing evidence producers: {missing}")
    audits = list(args.bundle_dir.rglob("formal-stack-audit.json"))
    if len(audits) != 2:
        raise SystemExit(f"expected two platform audit reports, got {len(audits)}")
    reports = [json.loads(path.read_text(encoding="utf-8")) for path in audits]
    for report in reports:
        if report.get("status") != "PASS" or report.get("ownerCount") != 22:
            raise SystemExit("invalid formal-stack audit report")
        finding = report.get("finding", {})
        if finding.get("state") != "OPEN" or finding.get("decomposedOpenP1") != 4 or finding.get("closureAchieved") is not False:
            raise SystemExit("R4 finding boundary drift")
        if report.get("externalExecution") != 0:
            raise SystemExit("external execution must remain zero")
    if reports[0]["existingEvidence"] != reports[1]["existingEvidence"]:
        raise SystemExit("cross-platform evidence classification differs")
    files = sorted(path for path in args.bundle_dir.rglob("*") if path.is_file())
    payload = {
        "schemaVersion": "1.0",
        "gate": "G9A-R4-PREP",
        "decision": "CONDITIONAL_PASS_CANDIDATE_AWAITING_SPONSOR_CONFIRMATION",
        "evidenceLevel": "R4_PREP_STATIC_RUNTIME_TOPOLOGY_AUDIT",
        "ownerCount": 22,
        "industryCount": 3,
        "openP0": 0,
        "openP1": 4,
        "findingState": "OPEN",
        "runtimeRepairPerformed": False,
        "externalExecution": 0,
        "files": [{"path": path.relative_to(args.bundle_dir).as_posix(), "size": path.stat().st_size, "sha256": digest(path)} for path in files],
    }
    canonical = json.dumps(payload, ensure_ascii=False, sort_keys=True, separators=(",", ":")).encode()
    payload["indexSha256"] = hashlib.sha256(canonical).hexdigest()
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(payload, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(f"G9A-R4 PREP EVIDENCE OK: files={len(files)} openP1=4 external=0")


if __name__ == "__main__":
    main()
