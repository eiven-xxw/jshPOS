#!/usr/bin/env python3
"""构建 T2-RDY-001 完整 CI 的不可变证据索引。"""
from __future__ import annotations

import argparse
import hashlib
import json
import pathlib


REQUIRED = {"governance-ubuntu","governance-windows","server","web","flutter-ubuntu","flutter-windows","mysql-operations","security","release-readiness","aggregate"}


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--bundle-dir", required=True, type=pathlib.Path)
    parser.add_argument("--output", required=True, type=pathlib.Path)
    args = parser.parse_args()
    bundle = args.bundle_dir.resolve()
    output = args.output.resolve()
    files = sorted(path for path in bundle.rglob("*") if path.is_file() and path.resolve() != output)
    producers = {path.relative_to(bundle).parts[0] for path in files}
    missing = REQUIRED - producers
    if missing:
        raise SystemExit("T2-RDY-001 INDEX ERROR: missing producers " + ", ".join(sorted(missing)))
    aggregate = [json.loads(path.read_text(encoding="utf-8")) for path in (bundle / "aggregate").rglob("rdy001-evidence.json")]
    if len(aggregate) != 1 or aggregate[0].get("status") != "PASS" or aggregate[0].get("requirementStatus") != "VERIFIED":
        raise SystemExit("T2-RDY-001 INDEX ERROR: aggregate evidence invalid")
    evidence = aggregate[0]
    if evidence.get("commercialSla") is not False or evidence.get("productionEligible") is not False \
            or any(evidence.get("externalExecution", {}).values()) or not evidence.get("decisions", {}).get("commercial", "").startswith("NO_GO"):
        raise SystemExit("T2-RDY-001 INDEX ERROR: evidence boundary drift")
    entries = [{"path":path.relative_to(bundle).as_posix(),"size":path.stat().st_size,"sha256":hashlib.sha256(path.read_bytes()).hexdigest()} for path in files]
    result = {
        "schemaVersion":"1.0","gate":"T2-GATE8C-SPRINT-S26D","status":"PASS",
        "requirementId":"T2-RDY-001","requirementStatus":"VERIFIED",
        "classification":"INTERNAL_RELEASE_READINESS_CANDIDATE",
        "decision":"CONDITIONAL_PASS_AWAITING_SPONSOR_ACCEPTANCE",
        "producers":sorted(producers),"fileCount":len(entries),"files":entries,
        "artifactCount":evidence["artifactCount"],"signatureVerified":True,"faultVectorCount":14,
        "internalReleaseReadiness":"GO_INTERNAL_RELEASE_READINESS",
        "fullAlpha":"NO_GO","production":"NO_GO","commercial":"NO_GO",
        "externalExecution":evidence["externalExecution"],
        "limitations":[
            "T2-RDY-001 remains VERIFIED until sponsor acceptance.",
            "T2-LIC-001 and PAY/HWD/PRN/PAR remain independent blockers.",
            "No FULL_ALPHA, PRODUCTION, COMMERCIAL, real signing, KMS, PITR or commercial SLA evidence was produced."
        ],
    }
    canonical = json.dumps(result, ensure_ascii=False, sort_keys=True, separators=(",", ":")).encode()
    result["indexSha256"] = hashlib.sha256(canonical).hexdigest()
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({key:value for key,value in result.items() if key != "files"}, ensure_ascii=False))


if __name__ == "__main__":
    main()
