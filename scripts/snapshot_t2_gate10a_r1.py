#!/usr/bin/env python3
"""为 R1 单个技术栈生成可复核的依赖与工具链证据摘要。"""
from __future__ import annotations

import argparse
import hashlib
import json
import pathlib
import subprocess


ROOT = pathlib.Path(__file__).resolve().parents[1]
VALID = {"MAVEN", "PNPM", "FLUTTER_PUB", "KOTLIN_GRADLE"}


def digest(path: pathlib.Path) -> str:
    value = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            value.update(chunk)
    return value.hexdigest()


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--ecosystem", required=True, choices=sorted(VALID))
    parser.add_argument("--output", required=True, type=pathlib.Path)
    parser.add_argument("--tool-version", required=True)
    parser.add_argument("--evidence", action="append", default=[], type=pathlib.Path)
    args = parser.parse_args()
    baseline = json.loads(
        (ROOT / "contracts/t2/gate10a-r1/ecosystem-baseline-v1.json").read_text(
            encoding="utf-8"
        )
    )
    input_set = baseline["inputSets"][args.ecosystem]
    files = sorted(
        {
            path
            for pattern in input_set["patterns"]
            for path in ROOT.glob(pattern)
            if path.is_file()
        },
        key=lambda path: path.relative_to(ROOT).as_posix(),
    )
    aggregate = hashlib.sha256()
    owned = []
    for path in files:
        relative = path.relative_to(ROOT).as_posix()
        actual = digest(path)
        aggregate.update(relative.encode("utf-8"))
        aggregate.update(b"\0")
        aggregate.update(path.read_bytes())
        aggregate.update(b"\0")
        owned.append({"path": relative, "sha256": actual, "size": path.stat().st_size})
    if len(files) != input_set["fileCount"] or aggregate.hexdigest() != input_set["aggregateSha256"]:
        raise AssertionError(f"dependency input set drift: {args.ecosystem}")
    evidence = []
    for item in args.evidence:
        path = item if item.is_absolute() else ROOT / item
        if not path.is_file() or path.stat().st_size == 0:
            raise AssertionError(f"missing dependency evidence: {item}")
        evidence.append({"path": path.name, "sha256": digest(path), "size": path.stat().st_size})
    result = {
        "schemaVersion": "1.0",
        "gate": "T2_GATE_10A_R1",
        "ecosystem": args.ecosystem,
        "commit": subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=ROOT, text=True).strip(),
        "toolVersion": args.tool_version,
        "decision": "KEEP_CURRENT_APPLICATION_DEPENDENCIES",
        "dependencyInputs": owned,
        "evidence": evidence,
        "externalExecution": 0,
    }
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(f"T2 Gate10A R1 snapshot OK: ecosystem={args.ecosystem} evidence={len(evidence)}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
