#!/usr/bin/env python3
"""Gate 6E 分阶段证据索引；只索引生产 Job 制品，禁止用占位文件制造绿色证据。"""
from __future__ import annotations

import argparse
import hashlib
import json
import pathlib


REQUIRED = {"governance", "server", "mysql", "pos-linux", "pos-windows", "web", "security"}


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--bundle-dir", required=True)
    args = parser.parse_args()
    root = pathlib.Path(args.bundle_dir)
    missing = [name for name in sorted(REQUIRED) if not (root / name).is_dir()]
    if missing:
        raise SystemExit(f"missing evidence producers: {missing}")
    governance_reports = list((root / "governance").rglob("gate6e-governance.json"))
    if len(governance_reports) != 1:
        raise SystemExit("Gate 6E governance result missing or duplicated")
    governance = json.loads(governance_reports[0].read_text(encoding="utf-8"))
    if governance.get("result") != "PASS" or governance.get("externalExecution", {}).get("providerNetworkCalls") != 0:
        raise SystemExit("Gate 6E governance or external zero-execution boundary invalid")
    files = []
    for path in sorted(item for item in root.rglob("*") if item.is_file() and item.name != "t2-gate6e-evidence-index.json"):
        if path.stat().st_size <= 0:
            raise SystemExit(f"empty evidence file: {path.relative_to(root)}")
        files.append({
            "path": path.relative_to(root).as_posix(),
            "size": path.stat().st_size,
            "sha256": hashlib.sha256(path.read_bytes()).hexdigest(),
        })
    if len(files) < 10:
        raise SystemExit("insufficient independent Gate 6E evidence files")
    payload = {
        "schemaVersion": "1.0",
        "gate": "T2-GATE6E-S16",
        "phaseStatuses": governance["statuses"],
        "status": "PASS",
        "evidenceCeiling": ["STATIC", "UNIT", "WIDGET", "COMPONENT", "SOFTWARE_EXECUTION"],
        "sandbox": 0,
        "realDevice": 0,
        "pilot": 0,
        "fullAlpha": 0,
        "production": 0,
        "fileCount": len(files),
        "files": files,
    }
    canonical = json.dumps(payload, sort_keys=True, separators=(",", ":"), ensure_ascii=False).encode()
    payload["indexSha256"] = hashlib.sha256(canonical).hexdigest()
    target = root / "t2-gate6e-evidence-index.json"
    target.write_text(json.dumps(payload, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({"status": "PASS", "files": len(files), "sha256": payload["indexSha256"]}))


if __name__ == "__main__":
    main()
