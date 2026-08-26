#!/usr/bin/env python3
"""校验 Gate 9C 只固化内部产品完整性封板治理与证据。"""
from __future__ import annotations

import csv
import json
import pathlib
import subprocess


ROOT = pathlib.Path(__file__).resolve().parents[1]
BASELINE = "04869b4d983bdea285b758feee08def7a5652dc2"
ALLOWED_PREFIXES = (
    ".github/workflows/t2-gate9c-seal.yml",
    "AGENTS.md",
    "contracts/t2/gate9c/",
    "docs/governance/CR-T2G9C-",
    "docs/governance/change-log.md",
    "docs/governance/rtm.csv",
    "docs/t2-gate9c/",
    "scripts/audit_t2_gate9c_seal.py",
    "scripts/build_t2_gate9c_seal_evidence.py",
    "scripts/check_t2_gate9c_seal.py",
)


def git(*args: str) -> str:
    return subprocess.check_output(
        ["git", *args], cwd=ROOT, text=True, encoding="utf-8"
    ).strip()


def main() -> int:
    admission = json.loads(
        (ROOT / "contracts/t2/gate9c/gate-admission-v1.json").read_text(
            encoding="utf-8"
        )
    )
    if admission["baselineCommit"] != BASELINE:
        raise AssertionError("Gate 9C baseline drift")
    git("cat-file", "-e", f"{BASELINE}^{{commit}}")
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
        raise AssertionError(f"Gate 9C seal scope escaped: {illegal}")
    protected = [
        path
        for path in changed
        if path.startswith(
            ("server/", "admin-web/", "pos-flutter/", "packages/", "infra/", "infrastructure/")
        )
    ]
    if protected:
        raise AssertionError(f"runtime or infrastructure changed: {protected}")
    if admission["newRequirementIds"]:
        raise AssertionError("Gate 9C must not add Requirement IDs")
    if any(
        admission[key]
        for key in (
            "runtimeChangesAllowed",
            "dependencyChangesAllowed",
            "publishedMigrationChangesAllowed",
            "automaticTagCreation",
        )
    ):
        raise AssertionError("Gate 9C immutable boundary drift")
    if any(admission["externalExecution"].values()):
        raise AssertionError("external execution must remain zero")

    with (ROOT / "docs/governance/rtm.csv").open(encoding="utf-8-sig", newline="") as handle:
        states = {
            row["requirement_id"]: row["status"] for row in csv.DictReader(handle)
        }
    for requirement_id, expected in admission["externalStates"].items():
        if states.get(requirement_id) != expected:
            raise AssertionError(
                f"external state drift: {requirement_id}={states.get(requirement_id)} != {expected}"
            )
    print(
        f"T2 Gate9C SEAL SCOPE OK: files={len(changed)} runtime=0 external=0 "
        f"baseline={BASELINE[:8]}"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
