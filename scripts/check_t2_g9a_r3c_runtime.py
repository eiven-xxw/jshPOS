#!/usr/bin/env python3
"""校验 G9A-R3C 授权基线、八页运行时范围、串行证据和外部边界。"""
from __future__ import annotations

import csv
import json
import pathlib
import subprocess


ROOT = pathlib.Path(__file__).resolve().parents[1]
BASELINE = "35cc22316faf5389844dfeeee3dfa840ce06cdf8"
CONTRACT = ROOT / "contracts/t2/gate9b-r3c-runtime"
EXPECTED_SURFACES = {"VUE-16", "VUE-17", "VUE-18", "VUE-19", "VUE-20", "FLT-01", "FLT-02", "FLT-05"}
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
    ".github/workflows/t2-g9a-r3c-runtime.yml",
    "admin-web/src/api/terminal/index.ts",
    "admin-web/src/composables/useStableOperationIdentity.ts",
    "admin-web/src/composables/__tests__/useStableOperationIdentity.spec.ts",
    "admin-web/src/views/g9a-r3c/",
    "admin-web/src/views/reporting/operation/index.vue",
    "admin-web/src/views/saas/operations/index.vue",
    "admin-web/src/views/service/operations/index.vue",
    "admin-web/src/views/subscription/operations/index.vue",
    "admin-web/src/views/terminal/registry/index.vue",
    "pos-flutter/lib/features/exchange/presentation/pos_exchange_page.dart",
    "pos-flutter/lib/features/session/presentation/pos_session_shell.dart",
    "pos-flutter/lib/features/shift/presentation/pos_cash_management_page.dart",
    "pos-flutter/test/gate6e/pos_return_page_test.dart",
    "pos-flutter/test/gate7b/exchange_runtime_test.dart",
    "pos-flutter/test/gate9b/g9a_r3c_cash_management_page_test.dart",
    "contracts/t2/gate9b-r3c-runtime/",
    "docs/governance/CR-T2G9R3-011_",
    "docs/governance/change-log.md",
    "docs/t2-gate9b-r3c-runtime/",
    "scripts/check_t2_g9a_r3c_runtime.py",
    "scripts/build_t2_g9a_r3c_runtime_evidence.py",
)


def fail(message: str) -> None:
    raise AssertionError(message)


def git(*args: str) -> str:
    return subprocess.check_output(
        ["git", "-c", "core.quotepath=false", *args], cwd=ROOT, text=True, encoding="utf-8"
    ).strip()


def is_ancestor(older: str, newer: str = "HEAD") -> bool:
    return subprocess.run(
        ["git", "merge-base", "--is-ancestor", older, newer], cwd=ROOT, capture_output=True
    ).returncode == 0


def rtm_states() -> tuple[dict[str, str], int]:
    with (ROOT / "docs/governance/rtm.csv").open(encoding="utf-8-sig", newline="") as handle:
        rows = list(csv.DictReader(handle))
    return (
        {row["requirement_id"]: row["status"] for row in rows},
        sum(1 for row in rows if row["phase"] == "T2" and row["status"] == "ACCEPTED"),
    )


def main() -> int:
    if not is_ancestor(BASELINE):
        fail("G9A-R3C 准备封存提交不是当前分支祖先")

    admission = json.loads((CONTRACT / "gate-admission-v1.json").read_text(encoding="utf-8"))
    if admission["baselineCommit"] != BASELINE or admission["stage"] != "AUTHORIZED_REPORTING_TERMINAL_COMMERCIAL_POS_AUX_RUNTIME_REPAIR":
        fail("G9A-R3C 准入契约或授权基线不正确")
    if admission["branch"] != "t2/gate9b-sprint27e-g9a-r3c-runtime":
        fail("G9A-R3C 分支名称不正确")
    if set(admission["surfaces"]) != EXPECTED_SURFACES:
        fail("G9A-R3C 不是授权的八页")
    for boundary in ("newRequirementIds", "serverRuntimeChanges", "serverApiEndpointChanges", "dependencyChanges", "migrationChanges"):
        if admission[boundary] != 0:
            fail(f"G9A-R3C 越过授权边界: {boundary}")
    if admission["clientApiSignatureExtensions"] != 1:
        fail("终端凭据轮换的可选原幂等键兼容扩展未登记")
    if admission["externalExecution"] != {key: 0 for key in admission["externalExecution"]}:
        fail("G9A-R3C 提升了外部执行证据")

    states, accepted = rtm_states()
    if accepted != admission["expectedAcceptedT2Requirements"]:
        fail(f"T2 ACCEPTED 数量漂移: {accepted}")
    drift = {key: states.get(key) for key, value in PRESERVED.items() if states.get(key) != value}
    if drift:
        fail(f"外部阻断或 UAT/REL/DEFERRED 状态漂移: {drift}")
    if git("diff", "--name-only", BASELINE, "--", "docs/governance/rtm.csv"):
        fail("G9A-R3C 不得修改 RTM 或分配新 Requirement ID")

    changed = [item for item in git("diff", "--name-only", BASELINE).splitlines() if item]
    unexpected = [
        item for item in changed if not any(item == prefix or item.startswith(prefix) for prefix in ALLOWED_PREFIXES)
    ]
    if unexpected:
        fail(f"出现未准入文件: {unexpected}")
    if any(item.startswith(("server/", "packages/", "infra/")) for item in changed):
        fail("本批不得修改服务端、设备适配或基础设施运行时")
    if any(item in changed for item in ("admin-web/package.json", "admin-web/pnpm-lock.yaml", "pos-flutter/pubspec.yaml", "pos-flutter/pubspec.lock")):
        fail("本批不得修改 Web/Flutter 依赖")
    if any("/db/migration/" in item or "local_database" in item for item in changed):
        fail("本批不得修改 MySQL/SQLite 迁移")

    acceptance = json.loads((CONTRACT / "surface-acceptance-v1.json").read_text(encoding="utf-8"))
    if set(item["surfaceId"] for item in acceptance["surfaces"]) != EXPECTED_SURFACES:
        fail("八页验收矩阵缺页或越界")
    dimensions = acceptance["dimensions"]
    if len(dimensions) != 12 or len(set(dimensions)) != 12:
        fail("八页验收矩阵不是十二个唯一维度")
    for surface in acceptance["surfaces"]:
        if len(surface["statuses"]) != 12:
            fail(f"{surface['surfaceId']} 验收维度数量不正确")
        invalid = set(surface["statuses"]) - {"PASS", "NOT_APPLICABLE"}
        if invalid or surface["statuses"][dimensions.index("directTest")] != "PASS":
            fail(f"{surface['surfaceId']} 尚未完成直接交互验收: {invalid}")
        source = ROOT / surface["evidence"][0]
        if not source.is_file() or surface["marker"] not in source.read_text(encoding="utf-8"):
            fail(f"{surface['surfaceId']} 未装配具名页面状态面")
        for evidence in surface["evidence"]:
            if not (ROOT / evidence).is_file():
                fail(f"{surface['surfaceId']} 证据不存在: {evidence}")

    shared = (ROOT / "admin-web/src/composables/useStableOperationIdentity.ts").read_text(encoding="utf-8")
    if not all(token in shared for token in ("const get = (operation", "const complete = (operation", "const peek = (operation")):
        fail("Vue 原操作身份共享能力不完整")
    cash = (ROOT / "pos-flutter/lib/features/shift/presentation/pos_cash_management_page.dart").read_text(encoding="utf-8")
    if not all(token in cash for token in ("required this.businessDate", "BLOCKED_EXTERNAL", "idempotencyKey")):
        fail("FLT-05 未冻结业务日、原幂等键或真实钱箱阻断")

    seeds = json.loads((CONTRACT / "failure-seed-closure-v1.json").read_text(encoding="utf-8"))
    expected_seeds = {
        "R3C-P1-TEST-001", "R3C-P1-TEST-002", "R3C-P1-STATE-001", "R3C-P1-RECOVERY-001",
        "R3C-P1-CONFIRM-001", "R3C-P1-SCOPE-001", "R3C-P1-DATA-ACCESS-001", "R3C-P1-OFFLINE-DAY-001",
    }
    if {item["seedId"] for item in seeds["closures"]} != expected_seeds:
        fail("八组准备阶段失败 seed 未完整关闭")
    if any(item["state"] != "CLOSED_IN_BATCH" for item in seeds["closures"]):
        fail("存在未关闭的 R3C 失败 seed")
    if seeds["openP0"] != 0 or seeds["openP1InBatch"] != 0 or seeds["autoRetryUsed"] or seeds["testsSkippedToPass"]:
        fail("R3C 仍有 P0/P1，或通过重跑/跳过伪造绿色")

    serial = json.loads((CONTRACT / "serial-verification-v1.json").read_text(encoding="utf-8"))
    if not serial["serial"] or [item["order"] for item in serial["steps"]] != list(range(10)):
        fail("R3C-R0 至 R9 串行顺序不正确")
    commits = [item["commit"] for item in serial["steps"]]
    for index, commit in enumerate(commits):
        if not is_ancestor(commit):
            fail(f"串行提交不是当前分支祖先: {commit}")
        if index and not is_ancestor(commits[index - 1], commit):
            fail(f"串行提交顺序断裂: {commits[index - 1]} -> {commit}")
    if serial["batchState"] != "VERIFIED_CANDIDATE" or serial["overallFindingState"] != "OPEN":
        fail("R3C 批次或整体 Finding 状态错误")
    if serial["nextBatchAutomaticallyAuthorized"] is not False:
        fail("G9A-R3D 被错误自动授权")

    print(
        "G9A-R3C SCOPE OK: baseline=35cc223 surfaces=8 dimensions=12 seeds=8 "
        "serialSteps=10 accepted=88 migrations=0 providerNetwork=0 overallFinding=OPEN"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
