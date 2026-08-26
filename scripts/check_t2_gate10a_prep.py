#!/usr/bin/env python3
"""校验 Gate 10A-Prep 只有治理、审计、测试设计与分批计划。"""
from __future__ import annotations

import csv
import json
import pathlib
import subprocess


ROOT = pathlib.Path(__file__).resolve().parents[1]
BASELINE = "9ca6778f315e4d702af704be3c0bad2de3d2e8bb"
ALLOWED = (
    ".github/workflows/t2-gate10a-prep.yml", "AGENTS.md", "contracts/t2/gate10a-prep/",
    "docs/adr/ADR-073-", "docs/adr/README.md", "docs/governance/CR-T2G10A-", "docs/governance/change-log.md",
    "docs/t2-gate10a-prep/", "scripts/audit_t2_gate10a_prep.py",
    "scripts/build_t2_gate10a_prep_evidence.py", "scripts/check_t2_gate10a_prep.py",
)


def git(*args: str) -> str:
    return subprocess.check_output(["git", *args], cwd=ROOT, text=True, encoding="utf-8").strip()


def main() -> int:
    admission = json.loads((ROOT / "contracts/t2/gate10a-prep/gate-admission-v1.json").read_text(encoding="utf-8"))
    if admission["baselineCommit"] != BASELINE:
        raise AssertionError("Gate10A baseline drift")
    changed = set(filter(None, git("-c", "core.quotepath=false", "diff", "--name-only", BASELINE).splitlines()))
    changed.update(filter(None, git("-c", "core.quotepath=false", "ls-files", "--others", "--exclude-standard").splitlines()))
    illegal = sorted(path for path in changed if not any(path == prefix or path.startswith(prefix) for prefix in ALLOWED))
    if illegal:
        raise AssertionError(f"Gate10A prep scope escaped: {illegal}")
    protected = [path for path in changed if path.startswith(("server/", "admin-web/", "pos-flutter/", "packages/", "infra/", "infrastructure/"))]
    if protected:
        raise AssertionError(f"runtime/dependency/infrastructure changed: {protected}")
    if git("tag", "-l", "t2-internal-product-completeness-seal-2026-08-26"):
        raise AssertionError("proposed tag must not exist before sponsor confirmation")
    with (ROOT / "docs/governance/rtm.csv").open(encoding="utf-8-sig", newline="") as handle:
        states = {row["requirement_id"]: row["status"] for row in csv.DictReader(handle)}
    for requirement_id, expected in admission["externalStates"].items():
        if states.get(requirement_id) != expected:
            raise AssertionError(f"external state drift: {requirement_id}={states.get(requirement_id)}")
    if admission["runtimeChangesAllowed"] or admission["dependencyChangesAllowed"] or admission["publishedMigrationChangesAllowed"] or admission["tagCreationAllowed"]:
        raise AssertionError("preparation-only permissions drift")
    if any(admission["externalExecution"].values()):
        raise AssertionError("external execution must remain zero")
    print(f"T2 Gate10A PREP SCOPE OK: files={len(changed)} runtime=0 dependency=0 migration=0 tag=0 external=0 baseline={BASELINE[:8]}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
