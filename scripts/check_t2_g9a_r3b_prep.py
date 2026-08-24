#!/usr/bin/env python3
"""校验 G9A-R3B 准备阶段基线、范围、RTM 和外部证据守恒。"""
from __future__ import annotations

import csv
import json
import pathlib
import subprocess


ROOT = pathlib.Path(__file__).resolve().parents[1]
BASELINE = "838bdacdfdd3e4113c6cf7d43cf4ad024d63ff14"
CONTRACT = ROOT / "contracts/t2/gate9b-r3b-prep"
RUNTIME_PREFIXES = ("server/", "admin-web/src/", "pos-flutter/", "packages/", "infra/")
ALLOWED_PREFIXES = (
    "AGENTS.md",
    ".github/workflows/t2-g9a-r3b-prep.yml",
    "contracts/t2/gate9b-r3b-prep/",
    "docs/governance/CR-T2G9R3-005_",
    "docs/governance/change-log.md",
    "docs/t2-gate9b-r3b-prep/",
    "scripts/audit_t2_g9a_r3b_pages.py",
    "scripts/check_t2_g9a_r3b_prep.py",
    "scripts/build_t2_g9a_r3b_prep_evidence.py",
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
    return ({row["requirement_id"]: row["status"] for row in rows},
            sum(row["phase"] == "T2" and row["status"] == "ACCEPTED" for row in rows))


def main() -> int:
    if subprocess.run(["git", "merge-base", "--is-ancestor", BASELINE, "HEAD"], cwd=ROOT).returncode != 0:
        fail("G9A-R3A final governance commit is not an ancestor")
    admission = json.loads((CONTRACT / "gate-admission-v1.json").read_text(encoding="utf-8"))
    if admission["baselineCommit"] != BASELINE or admission["stage"] != "STATIC_GOVERNANCE_AND_REPOSITORY_AUDIT":
        fail("R3B prep admission does not match the authorized baseline or stage")
    if admission["r3bRuntimeAuthorized"] or admission["findingState"] != "OPEN":
        fail("R3B runtime was incorrectly authorized or finding was closed")

    states, accepted_count = rtm()
    if accepted_count != admission["expectedAcceptedT2Requirements"]:
        fail(f"T2 ACCEPTED count drift: {accepted_count}")
    drift = {key: states.get(key) for key, expected in admission["preservedStates"].items() if states.get(key) != expected}
    if drift:
        fail(f"external/UAT/license status drift: {drift}")

    changed = [path for path in git("diff", "--name-only", BASELINE).splitlines() if path]
    runtime = [path for path in changed if path.startswith(RUNTIME_PREFIXES)]
    if runtime:
        fail(f"unauthorized runtime change during prep: {runtime}")
    unexpected = [path for path in changed if not any(path == prefix or path.startswith(prefix) for prefix in ALLOWED_PREFIXES)]
    if unexpected:
        fail(f"file outside prep allowlist: {unexpected}")

    immutable = (
        "docs/governance/rtm.csv",
        "contracts/t2/gate9a-prep/defect-register-v1.json",
        "contracts/t2/gate9b-r3-prep/surface-freeze-v1.json",
        "contracts/t2/gate9b-r3a/",
        "docs/adr/ADR-071-g9a-r3-page-state-permission-recovery.md",
    )
    changed_immutable = [path for path in immutable if subprocess.run(
        ["git", "diff", "--quiet", BASELINE, "--", path], cwd=ROOT
    ).returncode != 0]
    if changed_immutable:
        fail(f"historical evidence, RTM, or accepted ADR was rewritten: {changed_immutable}")

    freeze = json.loads((CONTRACT / "surface-freeze-v1.json").read_text(encoding="utf-8"))
    if freeze["surfaceCount"] != 11 or [item["surfaceId"] for item in freeze["surfaces"]] != [f"VUE-{i:02d}" for i in range(5, 16)]:
        fail("R3B surface freeze is not exactly VUE-05..VUE-15")
    seeds = json.loads((CONTRACT / "failure-seeds-v1.json").read_text(encoding="utf-8"))
    if seeds["state"] != "OPEN" or seeds["summary"] != {"openP0": 0, "openP1": 7, "auditCorrections": 1}:
        fail("R3B failure seed register drift")
    if any(admission["externalExecution"].values()):
        fail("prep stage elevated external evidence")

    print(
        "G9A-R3B PREP SCOPE OK: baseline=838bdacd accepted=88 runtimeChanges=0 "
        "surfaces=11 p1Seeds=7 finding=OPEN externalExecution=0"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
