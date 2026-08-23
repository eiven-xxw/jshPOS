#!/usr/bin/env python3
"""聚合 T2-SVC-001 多执行器证据并生成不可变 SHA-256 索引。"""
from __future__ import annotations
import argparse, hashlib, json, pathlib

GATE = "T2-GATE8A-SPRINT-S24C-SVC001"
REQUIRED = {"governance-ubuntu", "governance-windows", "server", "mysql-runtime", "web",
            "flutter-ubuntu", "flutter-windows", "runtime-stack", "security"}

def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--bundle-dir", required=True)
    parser.add_argument("--output", required=True)
    args = parser.parse_args()
    bundle = pathlib.Path(args.bundle_dir)
    files = sorted(path for path in bundle.rglob("*") if path.is_file())
    producers = {path.relative_to(bundle).parts[0] for path in files if len(path.relative_to(bundle).parts) > 1}
    missing = REQUIRED - producers
    if missing:
        raise SystemExit(f"missing SVC001 evidence producers: {sorted(missing)}")
    reports = []
    for path in files:
        if path.suffix != ".json":
            continue
        try:
            report = json.loads(path.read_text(encoding="utf-8"))
        except Exception:
            continue
        if report.get("gate") == GATE:
            reports.append(report)
    if len(reports) < 2 or any(report.get("status") != "PASS" or any(report.get("externalExecution", {}).values()) for report in reports):
        raise SystemExit("SVC001 governance or zero-execution evidence failed")
    if {report.get("requirementStatus") for report in reports} != {"VERIFIED"}:
        raise SystemExit("SVC001 is not consistently VERIFIED")
    entries = [{"path": path.relative_to(bundle).as_posix(), "size": path.stat().st_size,
                "sha256": hashlib.sha256(path.read_bytes()).hexdigest()} for path in files]
    result = {
        "schemaVersion": "1.0", "gate": GATE, "status": "PASS", "requirementStatus": "VERIFIED",
        "evidenceLevel": "INTERNAL_SYNTHETIC_SOFTWARE_ONLY",
        "decision": "SVC001_VERIFIED_AWAITING_SPONSOR_ACCEPTANCE", "producers": sorted(producers),
        "fileCount": len(entries), "files": entries,
        "limitations": [
            "No contract SLA, real notification, Provider, funds, device, peripheral, partner, Full Alpha or production execution.",
            "Attachment body requires an authorized production object-storage configuration; tests use only controlled adapters and metadata evidence."
        ]
    }
    result["indexSha256"] = hashlib.sha256(json.dumps(result, sort_keys=True, separators=(",", ":"),
                                                        ensure_ascii=False).encode()).hexdigest()
    target = pathlib.Path(args.output)
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")

if __name__ == "__main__":
    main()
