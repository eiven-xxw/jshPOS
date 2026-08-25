#!/usr/bin/env python3
"""校验 G9A-R3D 准备阶段范围、状态和历史证据守恒。"""
from __future__ import annotations

import csv
import hashlib
import json
import pathlib
import subprocess


ROOT = pathlib.Path(__file__).resolve().parents[1]
CONTRACT = ROOT / "contracts/t2/gate9b-r3d-prep"
BASELINE = "f5909b6094d004e4320d3afaa1dce7a1f170485b"
RUNTIME_PREFIXES = ("server/", "admin-web/", "pos-flutter/", "packages/", "infra/", "infrastructure/")
ALLOWED_PREFIXES = (
    "AGENTS.md",
    ".github/workflows/t2-g9a-r3d-prep.yml",
    "contracts/t2/gate9b-r3d-prep/",
    "docs/governance/CR-T2G9R3-019_G9A-R3D全部26页联合验收准备.md",
    "docs/governance/change-log.md",
    "docs/t2-gate9b-r3d-prep/",
    "scripts/audit_t2_g9a_r3d_joint_pages.py",
    "scripts/build_t2_g9a_r3d_prep_evidence.py",
    "scripts/check_t2_g9a_r3d_prep.py",
)


def fail(message: str) -> None:
    raise AssertionError(message)


def git(*args: str) -> str:
    return subprocess.check_output(
        ["git", "-c", "core.quotepath=false", *args], cwd=ROOT, text=True, encoding="utf-8"
    ).strip()


def git_blob_digest(path: pathlib.Path) -> str:
    """按 Git Blob 原始字节校验封存来源，避免 Windows 检出换行改变证据摘要。"""
    relative = path.relative_to(ROOT).as_posix()
    content = subprocess.check_output(["git", "show", f"HEAD:{relative}"], cwd=ROOT)
    return hashlib.sha256(content).hexdigest()


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
        fail("G9A-R3C final governance commit is not an ancestor")
    admission = json.loads((CONTRACT / "gate-admission-v1.json").read_text(encoding="utf-8"))
    if admission["baselineCommit"] != BASELINE or admission["stage"] != "JOINT_UI_ACCEPTANCE_PREPARATION":
        fail("R3D prep admission does not match the authorized baseline or stage")
    if admission["runtimeJointAcceptanceAuthorized"] or admission["findingState"] != "OPEN":
        fail("R3D runtime was incorrectly authorized or G9A-UI finding was closed")

    states, accepted_count = rtm()
    if accepted_count != admission["expectedAcceptedT2Requirements"]:
        fail(f"T2 ACCEPTED count drift: {accepted_count}")
    drift = {
        key: states.get(key)
        for key, expected in admission["preservedStates"].items()
        if states.get(key) != expected
    }
    if drift:
        fail(f"external/UAT/license state drift: {drift}")

    paths = [path for path in git("diff", "--name-only", BASELINE).splitlines() if path]
    runtime = [path for path in paths if path.startswith(RUNTIME_PREFIXES)]
    if runtime:
        fail(f"unauthorized runtime change during R3D prep: {runtime}")
    unexpected = [
        path for path in paths
        if not any(path == prefix or path.startswith(prefix) for prefix in ALLOWED_PREFIXES)
    ]
    if unexpected:
        fail(f"file outside R3D prep allowlist: {unexpected}")

    immutable = (
        "docs/governance/rtm.csv",
        "docs/adr/ADR-071-g9a-r3-page-state-permission-recovery.md",
        "contracts/t2/gate9b-r3a/",
        "contracts/t2/gate9b-r3b-runtime/",
        "contracts/t2/gate9b-r3c-runtime/",
        "docs/t2-gate9b-r3a/",
        "docs/t2-gate9b-r3b-runtime/",
        "docs/t2-gate9b-r3c-runtime/",
    )
    rewritten = [path for path in immutable if changed(path)]
    if rewritten:
        fail(f"historical evidence, RTM, or accepted ADR was rewritten: {rewritten}")

    rollup = json.loads((CONTRACT / "surface-rollup-v1.json").read_text(encoding="utf-8"))
    all_ids: list[str] = []
    for source in rollup["sources"]:
        source_path = ROOT / source["path"]
        if git_blob_digest(source_path) != source["sha256"]:
            fail(f"historical source digest drift: {source['path']}")
        document = json.loads(source_path.read_text(encoding="utf-8"))
        ids = [item["surfaceId"] for item in document["surfaces"]]
        if ids != source["surfaceIds"] or document["batchResult"] != "VERIFIED_CANDIDATE":
            fail(f"batch source drift: {source['batch']}")
        if document["overallFindingState"] != "OPEN":
            fail(f"source batch closed overall finding: {source['batch']}")
        for item in document["surfaces"]:
            if len(item["statuses"]) != 12 or item["statuses"][-1] != "PASS":
                fail(f"direct component/widget evidence incomplete: {item['surfaceId']}")
            if any(not (ROOT / evidence).is_file() for evidence in item["evidence"]):
                fail(f"referenced evidence missing: {item['surfaceId']}")
        all_ids.extend(ids)
    if len(all_ids) != 26 or len(set(all_ids)) != 26 or set(all_ids) != set(rollup["expectedSurfaceIds"]):
        fail("26-page identity closure failed")

    journeys = json.loads((CONTRACT / "cross-page-journeys-v1.json").read_text(encoding="utf-8"))
    journey_ids = [item for journey in journeys["journeys"] for item in journey["surfaces"]]
    if len(journeys["journeys"]) != 3 or len(journey_ids) != 26 or set(journey_ids) != set(all_ids):
        fail("three joint journeys do not cover all 26 surfaces exactly once")
    seeds = json.loads((CONTRACT / "joint-failure-seeds-v1.json").read_text(encoding="utf-8"))
    if seeds["state"] != "OPEN" or seeds["summary"] != {"openP0": 0, "openP1": 3}:
        fail("joint failure seed register drift")
    matrix = json.loads((CONTRACT / "acceptance-matrix-v1.json").read_text(encoding="utf-8"))
    if len(matrix["dimensions"]) != 12 or matrix["findingStateAfterPrep"] != "OPEN":
        fail("R3D acceptance matrix drift")
    if any(admission["externalExecution"].values()):
        fail("R3D prep elevated external evidence")

    print(
        "G9A-R3D PREP SCOPE OK: baseline=f5909b60 accepted=88 runtimeChanges=0 "
        "surfaces=26 journeys=3 openP0=0 openP1=3 finding=OPEN externalExecution=0"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
