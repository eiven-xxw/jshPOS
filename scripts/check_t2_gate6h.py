#!/usr/bin/env python3
"""Gate 6H 串行准入、证据上限和外部零执行门禁。"""
from __future__ import annotations

import argparse
import csv
import json
import pathlib
import subprocess

ROOT = pathlib.Path(__file__).resolve().parents[1]
BASELINE = "c2db49fb47db5fe30fe01515b60ee6f054b214e3"
BRANCH = "codex/t2-gate6h-sprint18-internal-release-candidate"
SEQUENCE = ("T2-UX-001", "T2-PERF-001", "T2-OPS-001", "T2-RC-001")
PRESERVED = {
    "T2-PAY-002": "BLOCKED", "T2-HWD-001": "BLOCKED", "T2-PAR-001": "BLOCKED", "T2-PRN-001": "BLOCKED",
    "T2-UAT-001": "DRAFT", "T2-REL-001": "DRAFT", "T2-JSH-001": "DEFERRED", "T2-LIC-001": "DEFERRED",
    "V1-SAA-001": "DRAFT", "V1-PRD-001": "DRAFT", "V1-POS-001": "DRAFT", "V1-PAY-001": "DRAFT",
    "V1-INV-001": "DRAFT", "V1-PRM-001": "DRAFT", "V1-SYN-001": "DRAFT",
}


def fail(message: str) -> None:
    raise SystemExit(f"T2-GATE6H ERROR: {message}")


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--output", type=pathlib.Path)
    args = parser.parse_args()
    admission = json.loads((ROOT / "contracts/t2/gate6h/gate6h-admission.json").read_text(encoding="utf-8"))
    with (ROOT / "docs/governance/rtm.csv").open(encoding="utf-8-sig", newline="") as handle:
        rows = {row["requirement_id"]: row for row in csv.DictReader(handle)}
    if admission.get("baselineCommit") != BASELINE or admission.get("branch") != BRANCH:
        fail("baseline or branch drift")
    requirements = admission.get("sequentialRequirements", [])
    statuses = tuple(item.get("status") for item in requirements)
    if tuple(item.get("id") for item in requirements) != SEQUENCE:
        fail("requirement sequence drift")
    first_unverified = next((index for index, value in enumerate(statuses) if value != "VERIFIED"), len(statuses))
    allowed = all(value == "VERIFIED" for value in statuses[:first_unverified]) and (
        first_unverified == len(statuses)
        or statuses[first_unverified] == "IN_PROGRESS" and all(value == "DRAFT" for value in statuses[first_unverified + 1:])
    )
    if not allowed:
        fail(f"illegal serial statuses {statuses}")
    for requirement_id, status in zip(SEQUENCE, statuses):
        if rows.get(requirement_id, {}).get("status") != status:
            fail(f"RTM/admission mismatch: {requirement_id}")
    if admission.get("preservedStates") != PRESERVED:
        fail("preserved state contract drift")
    for requirement_id, expected in PRESERVED.items():
        if rows.get(requirement_id, {}).get("status") != expected:
            fail(f"preserved RTM state changed: {requirement_id}")
    external = admission.get("externalExecution", {})
    if any(value != 0 for key, value in external.items() if key != "commercialClaimAllowed") or external.get("commercialClaimAllowed") is not False:
        fail("external execution or commercial claim boundary changed")
    current = subprocess.run(["git", "branch", "--show-current"], cwd=ROOT, capture_output=True, text=True, check=True).stdout.strip()
    if current and current != BRANCH:
        fail(f"unexpected branch {current}")
    if subprocess.run(["git", "merge-base", "--is-ancestor", BASELINE, "HEAD"], cwd=ROOT).returncode:
        fail("baseline is not an ancestor")
    result = {"gate": "T2-GATE6H-S18", "statuses": dict(zip(SEQUENCE, statuses)), "externalExecution": external, "result": "PASS"}
    if args.output:
        output = args.output if args.output.is_absolute() else ROOT / args.output
        output.parent.mkdir(parents=True, exist_ok=True)
        output.write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print("T2 Gate 6H governance OK: " + ", ".join(f"{key}={value}" for key, value in result["statuses"].items()))


if __name__ == "__main__":
    main()
