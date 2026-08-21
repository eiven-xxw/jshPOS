#!/usr/bin/env python3
"""生成不携带正文的内部诊断清单，命中 Secret/PII 时失败关闭。"""
from __future__ import annotations

import argparse
import hashlib
import json
import pathlib
import re


SENSITIVE = re.compile(
    r"(?i)(password\s*[:=]|secret\s*[:=]|authorization\s*[:=]|bearer\s+[a-z0-9._-]+|"
    r"-----BEGIN .*PRIVATE KEY-----|merchant[_-]?id\s*[:=]|terminal[_-]?no\s*[:=]|"
    r"\b1[3-9]\d{9}\b|\b\d{17}[0-9Xx]\b)"
)
ALLOWED = {".log", ".txt", ".json", ".yml", ".yaml"}


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--input-dir", required=True, type=pathlib.Path)
    parser.add_argument("--output", required=True, type=pathlib.Path)
    args = parser.parse_args()
    if not args.input_dir.is_dir():
        raise SystemExit("T2-GATE6H OPS ERROR: diagnostic input directory missing")
    files = []
    for path in sorted(item for item in args.input_dir.rglob("*") if item.is_file()):
        if path.suffix.lower() not in ALLOWED:
            raise SystemExit(f"T2-GATE6H OPS ERROR: unsupported diagnostic file {path.name}")
        raw = path.read_bytes()
        text = raw.decode("utf-8", errors="replace")
        if SENSITIVE.search(text):
            raise SystemExit(f"T2-GATE6H OPS ERROR: sensitive diagnostic content in {path.name}")
        files.append({
            "path": path.relative_to(args.input_dir).as_posix(),
            "size": len(raw), "sha256": hashlib.sha256(raw).hexdigest(),
            "lines": len(text.splitlines()),
            "errorLines": sum("ERROR" in line or "FAIL_CLOSED" in line for line in text.splitlines()),
        })
    if not files:
        raise SystemExit("T2-GATE6H OPS ERROR: empty diagnostic input")
    report = {
        "schemaVersion": "1.0", "requirementId": "T2-OPS-001", "status": "PASS",
        "classification": "INTERNAL_SYNTHETIC_OPERATIONS", "contentExported": False,
        "retentionDays": 7, "files": files,
        "externalExecution": {"providerNetworkCalls": 0, "realDeviceCommands": 0, "productionDeployments": 0},
    }
    target = args.output
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(f"T2-GATE6H OPS DIAGNOSTICS OK: files={len(files)} contentExported=false")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
