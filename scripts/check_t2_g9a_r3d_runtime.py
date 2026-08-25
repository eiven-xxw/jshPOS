#!/usr/bin/env python3
"""校验 G9A-R3D 26 页联合验收的范围、串行证据和状态边界。"""
from __future__ import annotations

import csv
import hashlib
import json
import pathlib
import subprocess


ROOT = pathlib.Path(__file__).resolve().parents[1]
BASELINE = "b0762440c13dac5197cf5b716081278b0484407f"
CONTRACT = ROOT / "contracts/t2/gate9b-r3d-runtime"
EXPECTED_SURFACES = {
    *(f"VUE-{index:02d}" for index in range(1, 21)),
    *(f"FLT-{index:02d}" for index in range(1, 7)),
}
PRESERVED = {
    "T2-PAY-002": "BLOCKED",
    "T2-HWD-001": "BLOCKED",
    "T2-PRN-001": "BLOCKED",
    "T2-PAR-001": "BLOCKED",
    "T2-UAT-001": "DRAFT",
    "T2-REL-001": "DRAFT",
    "T2-LIC-001": "DEFERRED",
    "T2-JSH-001": "DEFERRED",
}
ALLOWED_PREFIXES = (
    "AGENTS.md",
    ".github/workflows/t2-g9a-r3d-runtime.yml",
    "admin-web/src/views/catalog/components/ShelfLabelPanel.vue",
    "admin-web/src/views/g9a-r3d/",
    "pos-flutter/lib/features/session/presentation/pos_session_shell.dart",
    "pos-flutter/lib/features/shift/presentation/pos_cash_management_page.dart",
    "pos-flutter/test/gate9b/g9a_r3d_trusted_session_journey_test.dart",
    "contracts/t2/gate9b-r3d-runtime/",
    "docs/governance/CR-T2G9R3-023_",
    "docs/governance/CR-T2G9R3-026_",
    "docs/governance/CR-T2G9R3-027_",
    "docs/governance/change-log.md",
    "docs/t2-gate9b-r3d-runtime/",
    "scripts/check_t2_g9a_r3d_runtime.py",
    "scripts/build_t2_g9a_r3d_runtime_evidence.py",
)


def fail(message: str) -> None:
    raise AssertionError(message)


def git(*args: str) -> str:
    return subprocess.check_output(
        ["git", "-c", "core.quotepath=false", *args],
        cwd=ROOT,
        text=True,
        encoding="utf-8",
    ).strip()


def is_ancestor(older: str, newer: str = "HEAD") -> bool:
    return subprocess.run(
        ["git", "merge-base", "--is-ancestor", older, newer],
        cwd=ROOT,
        capture_output=True,
    ).returncode == 0


def git_blob_sha256(path: str) -> str:
    content = subprocess.check_output(["git", "show", f"HEAD:{path}"], cwd=ROOT)
    return hashlib.sha256(content).hexdigest()


def rtm_states() -> tuple[dict[str, str], int]:
    with (ROOT / "docs/governance/rtm.csv").open(
        encoding="utf-8-sig", newline=""
    ) as handle:
        rows = list(csv.DictReader(handle))
    return (
        {row["requirement_id"]: row["status"] for row in rows},
        sum(row["phase"] == "T2" and row["status"] == "ACCEPTED" for row in rows),
    )


def main() -> int:
    if not is_ancestor(BASELINE):
        fail("G9A-R3D 准备封存提交不是当前分支祖先")

    admission = json.loads((CONTRACT / "gate-admission-v1.json").read_text(encoding="utf-8"))
    if (
        admission["baselineCommit"] != BASELINE
        or admission["stage"] != "AUTHORIZED_ALL_26_SURFACES_JOINT_ACCEPTANCE"
        or admission["branch"] != "t2/gate9b-sprint27g-g9a-r3d-runtime"
    ):
        fail("G9A-R3D 准入基线、阶段或分支不正确")
    for boundary in (
        "newRequirementIds",
        "serverRuntimeChanges",
        "serverApiEndpointChanges",
        "dependencyChanges",
        "migrationChanges",
    ):
        if admission[boundary] != 0:
            fail(f"G9A-R3D 越过授权边界: {boundary}")
    if any(admission["externalExecution"].values()):
        fail("G9A-R3D 提升了外部执行证据")

    states, accepted = rtm_states()
    if accepted != admission["expectedAcceptedT2Requirements"]:
        fail(f"T2 ACCEPTED 数量漂移: {accepted}")
    drift = {
        key: states.get(key)
        for key, expected in PRESERVED.items()
        if states.get(key) != expected
    }
    if drift:
        fail(f"外部阻断或 UAT/REL/DEFERRED 状态漂移: {drift}")
    if git("diff", "--name-only", BASELINE, "--", "docs/governance/rtm.csv"):
        fail("G9A-R3D 不得修改 RTM 或分配新 Requirement ID")

    changed = [item for item in git("diff", "--name-only", BASELINE).splitlines() if item]
    unexpected = [
        item
        for item in changed
        if not any(item == prefix or item.startswith(prefix) for prefix in ALLOWED_PREFIXES)
    ]
    if unexpected:
        fail(f"出现未准入文件: {unexpected}")
    if any(item.startswith(("server/", "packages/", "infra/", "infrastructure/")) for item in changed):
        fail("本批不得修改服务端、设备适配或基础设施运行时")
    dependency_files = {
        "admin-web/package.json",
        "admin-web/pnpm-lock.yaml",
        "pos-flutter/pubspec.yaml",
        "pos-flutter/pubspec.lock",
        "server/pom.xml",
    }
    if dependency_files.intersection(changed):
        fail("本批不得修改依赖")
    if any("/db/migration/" in item or "local_database" in item for item in changed):
        fail("本批不得修改 MySQL/SQLite 迁移")

    source = json.loads((CONTRACT / "source-freeze-v1.json").read_text(encoding="utf-8"))
    if source["sourcePrepCommit"] != BASELINE or source["surfaceCount"] != 26:
        fail("26 页冻结来源漂移")
    for item in source["sources"]:
        if git_blob_sha256(item["path"]) != item["sha256"]:
            fail(f"历史页面证据摘要漂移: {item['path']}")

    acceptance = json.loads((CONTRACT / "joint-acceptance-v1.json").read_text(encoding="utf-8"))
    if set(acceptance["surfaceIds"]) != EXPECTED_SURFACES or len(acceptance["surfaceIds"]) != 26:
        fail("联合验收页面缺失、重复或越界")
    if len(acceptance["dimensions"]) != 12 or len(set(acceptance["dimensions"])) != 12:
        fail("联合验收不是 12 个唯一维度")
    if (
        acceptance["jointJourneyResult"] != "3_OF_3_PASS"
        or acceptance["jointFailureSeedResult"] != "3_OF_3_CLOSED_IN_BATCH"
        or acceptance["openP0"] != 0
        or acceptance["openP1"] != 0
        or acceptance["testBypassUsed"]
        or acceptance["automaticRetryUsed"]
        or acceptance["findingState"] != "OPEN_AWAITING_SPONSOR_CONFIRMATION"
    ):
        fail("R3D 汇总结果、缺陷或 Finding 状态不正确")

    journeys = json.loads((CONTRACT / "journey-acceptance-v1.json").read_text(encoding="utf-8"))
    surface_traversal = [surface for journey in journeys["journeys"] for surface in journey["surfaces"]]
    if (
        len(journeys["journeys"]) != 3
        or len(surface_traversal) != 26
        or set(surface_traversal) != EXPECTED_SURFACES
        or any(journey["status"] != "PASS" for journey in journeys["journeys"])
    ):
        fail("三条联合旅程未完整且不重复地覆盖 26 页")
    for journey in journeys["journeys"]:
        if not (ROOT / journey["evidence"]).is_file():
            fail(f"联合旅程证据缺失: {journey['journeyId']}")

    seeds = json.loads((CONTRACT / "failure-seed-closure-v1.json").read_text(encoding="utf-8"))
    if (
        {item["seedId"] for item in seeds["closures"]}
        != {"R3D-JNT-P1-001", "R3D-JNT-P1-002", "R3D-JNT-P1-003"}
        or any(item["state"] != "CLOSED_IN_BATCH" for item in seeds["closures"])
        or seeds["openP0"] != 0
        or seeds["openP1"] != 0
        or seeds["findingState"] != "OPEN"
        or seeds["autoRetryUsed"]
        or seeds["testsSkippedToPass"]
    ):
        fail("联合失败 seed 未完整关闭，或 Finding 被提前关闭")

    serial = json.loads((CONTRACT / "serial-verification-v1.json").read_text(encoding="utf-8"))
    commits = [item["commit"] for item in serial["steps"]]
    if not serial["serial"] or [item["step"] for item in serial["steps"]] != [
        "R3D-R0",
        "R3D-R1",
        "R3D-R2",
        "R3D-R3",
    ]:
        fail("R3D-R0 至 R3D-R3 串行总账不正确")
    for index, commit in enumerate(commits):
        if not is_ancestor(commit):
            fail(f"串行提交不是当前分支祖先: {commit}")
        if index and not is_ancestor(commits[index - 1], commit):
            fail(f"串行提交顺序断裂: {commits[index - 1]} -> {commit}")
    if serial["overallFindingState"] != "OPEN" or serial["nextBatchAutomaticallyAuthorized"]:
        fail("整体 Finding 被提前关闭或后续批次被自动授权")

    web_main = (ROOT / "admin-web/src/views/g9a-r3d/__tests__/joint-main-operations.spec.ts").read_text(encoding="utf-8")
    web_commercial = (ROOT / "admin-web/src/views/g9a-r3d/__tests__/joint-commercial-operations.spec.ts").read_text(encoding="utf-8")
    pos_test = (ROOT / "pos-flutter/test/gate9b/g9a_r3d_trusted_session_journey_test.dart").read_text(encoding="utf-8")
    registry = (ROOT / "pos-flutter/lib/features/shift/presentation/pos_cash_management_page.dart").read_text(encoding="utf-8")
    if not all(token in web_main for token in ("createRouter", "KeepAlive", "VUE-16", "UNKNOWN")):
        fail("后台主路径联合测试不完整")
    if not all(token in web_commercial for token in ("createRouter", "VUE-17", "VUE-20", "buttonPermission")):
        fail("商业运营发布联合测试不完整")
    if not all(token in pos_test for token in ("六个正式页面", "cashKeys[2]", "cashKeys[1]", "2026-08-25")):
        fail("可信 POS 会话联合测试不完整")
    if not all(token in registry for token in ("ShiftOperationIdentityRegistry", "businessDate", "operation")):
        fail("班次现金原操作会话身份修复不完整")

    print(
        "G9A-R3D SCOPE OK: baseline=b076244 surfaces=26 dimensions=12 journeys=3 "
        "closedSeeds=3 accepted=88 migrations=0 providerNetwork=0 finding=OPEN"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
