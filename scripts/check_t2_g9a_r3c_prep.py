#!/usr/bin/env python3
"""校验 G9A-R3C 准备阶段只包含治理、静态审计与门禁材料。"""
from __future__ import annotations

import csv
import json
import pathlib
import subprocess


ROOT = pathlib.Path(__file__).resolve().parents[1]
CONTRACT = ROOT / "contracts/t2/gate9b-r3c-prep"
BASELINE = "2b8e56a22a6a742be73b8055fa2ea5872b628630"
RUNTIME_PREFIXES = ("server/", "admin-web/", "pos-flutter/", "packages/", "infra/", "infrastructure/")
ALLOWED_PREFIXES = (
    "AGENTS.md",
    ".github/workflows/t2-g9a-r3c-prep.yml",
    "contracts/t2/gate9b-r3c-prep/",
    "docs/governance/CR-T2G9R3-009_r3b-acceptance-r3c-prep.md",
    "docs/governance/change-log.md",
    "docs/t2-gate9b-r3c-prep/",
    "scripts/audit_t2_g9a_r3c_pages.py",
    "scripts/build_t2_g9a_r3c_prep_evidence.py",
    "scripts/check_t2_g9a_r3c_prep.py",
)


def fail(message: str) -> None:
    raise AssertionError(message)


def git(*args: str) -> str:
    return subprocess.check_output(
        ["git", "-c", "core.quotepath=false", *args], cwd=ROOT, text=True, encoding="utf-8"
    ).strip()


def rtm() -> tuple[dict[str, str], int]:
    with (ROOT / "docs/governance/rtm.csv").open(encoding="utf-8-sig", newline="") as handle:
        rows = list(csv.DictReader(handle))
    return (
        {row["requirement_id"]: row["status"] for row in rows},
        sum(row["phase"] == "T2" and row["status"] == "ACCEPTED" for row in rows),
    )


def changed(path: str) -> bool:
    return subprocess.run(["git", "diff", "--quiet", BASELINE, "--", path], cwd=ROOT).returncode != 0


def main() -> int:
    if subprocess.run(["git", "merge-base", "--is-ancestor", BASELINE, "HEAD"], cwd=ROOT).returncode != 0:
        fail("G9A-R3B final governance commit is not an ancestor")
    admission = json.loads((CONTRACT / "gate-admission-v1.json").read_text(encoding="utf-8"))
    if admission["baselineCommit"] != BASELINE or admission["stage"] != "STATIC_GOVERNANCE_AND_REPOSITORY_AUDIT":
        fail("R3C prep admission does not match the authorized baseline or stage")
    if admission["r3cRuntimeAuthorized"] or admission["findingState"] != "OPEN":
        fail("R3C runtime was incorrectly authorized or G9A-UI finding was closed")

    states, accepted_count = rtm()
    if accepted_count != admission["expectedAcceptedT2Requirements"]:
        fail(f"T2 ACCEPTED count drift: {accepted_count}")
    drift = {
        key: states.get(key)
        for key, expected in admission["preservedStates"].items()
        if states.get(key) != expected
    }
    if drift:
        fail(f"external/UAT/license status drift: {drift}")

    paths = [path for path in git("diff", "--name-only", BASELINE).splitlines() if path]
    runtime = [path for path in paths if path.startswith(RUNTIME_PREFIXES)]
    if runtime:
        fail(f"unauthorized runtime change during R3C prep: {runtime}")
    unexpected = [
        path for path in paths
        if not any(path == prefix or path.startswith(prefix) for prefix in ALLOWED_PREFIXES)
    ]
    if unexpected:
        fail(f"file outside R3C prep allowlist: {unexpected}")

    immutable = (
        "docs/governance/rtm.csv",
        "contracts/t2/gate9a-prep/defect-register-v1.json",
        "contracts/t2/gate9b-r3-prep/surface-freeze-v1.json",
        "contracts/t2/gate9b-r3b-prep/",
        "contracts/t2/gate9b-r3b-runtime/",
        "docs/adr/ADR-071-g9a-r3-page-state-permission-recovery.md",
    )
    rewritten = [path for path in immutable if changed(path)]
    if rewritten:
        fail(f"historical evidence, RTM, or accepted ADR was rewritten: {rewritten}")

    freeze = json.loads((CONTRACT / "surface-freeze-v1.json").read_text(encoding="utf-8"))
    expected = ["VUE-16", "VUE-17", "VUE-18", "VUE-19", "VUE-20", "FLT-01", "FLT-02", "FLT-05"]
    if freeze["surfaceCount"] != 8 or [item["surfaceId"] for item in freeze["surfaces"]] != expected:
        fail("R3C surface freeze must be exactly VUE-16..20 and FLT-01/02/05")
    seeds = json.loads((CONTRACT / "failure-seeds-v1.json").read_text(encoding="utf-8"))
    if seeds["state"] != "OPEN" or seeds["summary"] != {"openP0": 0, "openP1": 8}:
        fail("R3C failure seed register drift")
    matrix = json.loads((CONTRACT / "test-matrix-v1.json").read_text(encoding="utf-8"))
    if len(matrix["dimensions"]) != 12:
        fail("R3C test matrix must remain twelve-dimensional")
    if any(admission["externalExecution"].values()):
        fail("R3C prep elevated external evidence")

    print(
        "G9A-R3C PREP SCOPE OK: baseline=2b8e56a2 accepted=88 runtimeChanges=0 "
        "surfaces=8 p1Seeds=8 finding=OPEN externalExecution=0"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
