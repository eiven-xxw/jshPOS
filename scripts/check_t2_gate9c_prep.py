#!/usr/bin/env python3
"""校验 Gate 9C-Prep 只包含治理、契约、审计与评审材料。"""
from __future__ import annotations

import json
import pathlib
import subprocess


ROOT = pathlib.Path(__file__).resolve().parents[1]
BASELINE = "1e5807691df9f857fc5fc223244e1e97d5363174"
ALLOWED_PREFIXES = (
    ".github/workflows/t2-gate9c-prep.yml",
    "AGENTS.md",
    "contracts/t2/gate9c-prep/",
    "docs/governance/CR-T2G9C-",
    "docs/governance/change-log.md",
    "docs/governance/rtm.csv",
    "docs/t2-gate9c-prep/",
    "scripts/audit_t2_gate9c_product_completeness.py",
    "scripts/build_t2_gate9c_prep_evidence.py",
    "scripts/check_t2_gate9c_prep.py",
)


def git(*args: str) -> str:
    return subprocess.check_output(
        ["git", *args], cwd=ROOT, text=True, encoding="utf-8"
    ).strip()


def main() -> int:
    admission = json.loads(
        (ROOT / "contracts/t2/gate9c-prep/gate-admission-v1.json").read_text(
            encoding="utf-8"
        )
    )
    if admission["baselineCommit"] != BASELINE:
        raise AssertionError("Gate 9C baseline drift")
    changed = [
        line
        for line in git("-c", "core.quotepath=false", "diff", "--name-only", BASELINE).splitlines()
        if line
    ]
    changed.extend(
        line
        for line in git(
            "-c", "core.quotepath=false", "ls-files", "--others", "--exclude-standard"
        ).splitlines()
        if line
    )
    changed = sorted(set(changed))
    illegal = [
        path
        for path in changed
        if not any(path == prefix or path.startswith(prefix) for prefix in ALLOWED_PREFIXES)
    ]
    if illegal:
        raise AssertionError(f"Gate 9C preparation scope escaped: {illegal}")
    protected = [
        path
        for path in changed
        if path.startswith(("server/", "admin-web/", "pos-flutter/", "packages/", "infra/", "infrastructure/"))
    ]
    if protected:
        raise AssertionError(f"runtime or infrastructure changed: {protected}")
    states = admission["externalStates"]
    expected = {
        "T2-PAY-002": "BLOCKED",
        "T2-HWD-001": "BLOCKED",
        "T2-PRN-001": "BLOCKED",
        "T2-PAR-001": "BLOCKED",
        "T2-UAT-001": "DRAFT",
        "T2-REL-001": "DRAFT",
        "T2-LIC-001": "DEFERRED",
        "T2-JSH-001": "DEFERRED",
    }
    if states != expected:
        raise AssertionError("external evidence states drift")
    if any(admission["externalExecution"].values()):
        raise AssertionError("external execution must remain zero")
    print(
        f"T2 Gate9C PREP SCOPE OK: files={len(changed)} runtime=0 external=0 baseline={BASELINE[:8]}"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
