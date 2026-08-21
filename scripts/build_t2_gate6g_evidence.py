#!/usr/bin/env python3
"""为 Gate 6G 同一 run 的独立制品生成去重摘要索引。"""

from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path

REQUIRED_STAGE_DIRS = {
    "governance", "server", "mysql", "pos-linux", "pos-windows", "web",
    "runtime-stack", "internal-v1-core", "security",
}


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
    reports = list((bundle / "internal-v1-core").rglob("internal-v1-core-candidate-report.json"))
    if len(reports) != 1:
        raise SystemExit("Gate6G internal V1 core report missing or duplicated")
    candidate = json.loads(reports[0].read_text(encoding="utf-8"))
    if (candidate.get("status") != "PASS" or
            candidate.get("evidenceLevel") != "INTERNAL_V1_CORE_CANDIDATE" or
            candidate.get("internalDecision") != "CONDITIONAL_GO_INTERNAL_ONLY" or
            candidate.get("openP0") != 0 or candidate.get("openP1") != 0 or
            candidate.get("commercialClaimAllowed") is not False or
            any(value != 0 for value in candidate.get("externalExecution", {}).values())):
        raise SystemExit("Gate6G internal V1 core evidence boundary invalid")
    digest = candidate.pop("evidenceSha256", None)
    canonical = json.dumps(candidate, sort_keys=True, separators=(",", ":"), ensure_ascii=False).encode()
    if digest != hashlib.sha256(canonical).hexdigest():
        raise SystemExit("Gate6G internal V1 core report self-digest invalid")
    result = {
        "schemaVersion": "1.0",
        "gate": "T2-GATE6G-S17",
        "evidenceCeiling": "INTERNAL_V1_CORE_CANDIDATE",
        "externalExecution": 0,
        "internalV1CoreCandidate": {
            "status": candidate["status"],
            "decision": candidate["internalDecision"],
            "baseSaleJourneys": candidate["baseSaleJourneyCount"],
            "returnJourneys": candidate["returnJourneyCount"],
            "fixedFailureSeeds": candidate["fixedFailureSeedCount"],
            "openP0": candidate["openP0"],
            "openP1": candidate["openP1"],
        },
        "fileCount": len(files),
        "files": files,
    }
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(f"Gate6G evidence index OK: {len(files)} files")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
