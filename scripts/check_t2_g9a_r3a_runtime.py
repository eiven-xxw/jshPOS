#!/usr/bin/env python3
"""校验 G9A-R3A 授权基线、七页运行时范围、状态和证据边界。"""
from __future__ import annotations

import csv
import json
import pathlib
import subprocess


ROOT = pathlib.Path(__file__).resolve().parents[1]
BASELINE = "5302e2f8a020b5a058807d53312741e433c3ddde"
CONTRACT = ROOT / "contracts/t2/gate9b-r3a"
EXPECTED_SURFACES = {"VUE-01", "VUE-02", "VUE-03", "VUE-04", "FLT-03", "FLT-04", "FLT-06"}
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
    ".github/workflows/t2-g9a-r3a-runtime.yml",
    "admin-web/package.json",
    "admin-web/pnpm-lock.yaml",
    "admin-web/src/composables/useRecoverablePage.ts",
    "admin-web/vite/plugins/auto-import.ts",
    "admin-web/vite/plugins/components.ts",
    "admin-web/src/views/catalog/index.vue",
    "admin-web/src/views/catalog/components/ShelfLabelPanel.vue",
    "admin-web/src/views/foundation/index.vue",
    "admin-web/src/views/operations/advanced/index.vue",
    "admin-web/src/views/g9a-r3a/",
    "contracts/t2/gate9b-r3a/",
    "docs/adr/ADR-071-",
    "docs/adr/README.md",
    "docs/governance/CR-T2G9R3-004_",
    "docs/governance/change-log.md",
    "docs/t2-gate9b-r3a/",
    "pos-flutter/lib/app/jshpos_app.dart",
    "pos-flutter/lib/features/session/presentation/pos_session_shell.dart",
    "pos-flutter/lib/features/tender/application/pos_tender_controller.dart",
    "pos-flutter/lib/features/tender/presentation/pos_tender_page.dart",
    "pos-flutter/test/gate7b/tender_plan_test.dart",
    "pos-flutter/test/widget_test.dart",
    "scripts/check_t2_g9a_r3a_runtime.py",
    "scripts/build_t2_g9a_r3a_evidence.py",
)


def fail(message: str) -> None:
    raise AssertionError(message)


def git(*args: str) -> str:
    return subprocess.check_output(
        ["git", "-c", "core.quotepath=false", *args], cwd=ROOT, text=True, encoding="utf-8"
    ).strip()


def rtm_states() -> tuple[dict[str, str], int]:
    with (ROOT / "docs/governance/rtm.csv").open(encoding="utf-8-sig", newline="") as handle:
        rows = list(csv.DictReader(handle))
    return (
        {row["requirement_id"]: row["status"] for row in rows},
        sum(1 for row in rows if row["phase"] == "T2" and row["status"] == "ACCEPTED"),
    )


def main() -> int:
    if subprocess.run(["git", "merge-base", "--is-ancestor", BASELINE, "HEAD"], cwd=ROOT).returncode != 0:
        fail("G9A-R3 准备封存提交不是当前分支祖先")

    admission = json.loads((CONTRACT / "gate-admission-v1.json").read_text(encoding="utf-8"))
    if admission["baselineCommit"] != BASELINE or admission["stage"] != "AUTHORIZED_RUNTIME_UI_REPAIR":
        fail("G9A-R3A 准入契约或授权基线不正确")
    if set(admission["surfaces"]) != EXPECTED_SURFACES:
        fail("G9A-R3A 不是授权的七个页面")

    states, accepted = rtm_states()
    if accepted != admission["expectedAcceptedT2Requirements"]:
        fail(f"T2 ACCEPTED 数量漂移: {accepted}")
    drift = {key: states.get(key) for key, value in PRESERVED.items() if states.get(key) != value}
    if drift:
        fail(f"外部阻断或 UAT/REL/DEFERRED 状态漂移: {drift}")
    if git("diff", "--name-only", BASELINE, "--", "docs/governance/rtm.csv"):
        fail("G9A-R3A 不得修改 RTM 或分配新 Requirement ID")

    adr = (ROOT / "docs/adr/ADR-071-g9a-r3-page-state-permission-recovery.md").read_text(encoding="utf-8")
    if "状态：Accepted" not in adr:
        fail("ADR-071 尚未更新为 Accepted")

    changed = [item for item in git("diff", "--name-only", BASELINE).splitlines() if item]
    unexpected = [
        item for item in changed if not any(item == prefix or item.startswith(prefix) for prefix in ALLOWED_PREFIXES)
    ]
    if unexpected:
        fail(f"出现未准入文件: {unexpected}")
    forbidden_roots = ("server/", "packages/", "infra/")
    if any(item.startswith(forbidden_roots) for item in changed):
        fail("本批不得修改服务端、设备适配或基础设施运行时")
    migration_changes = [
        item for item in changed
        if "/db/migration/" in item or item.startswith("pos-flutter/lib/infrastructure/local_database/")
    ]
    if migration_changes:
        fail(f"本批修改了已发布迁移或 SQLite 数据模型: {migration_changes}")

    package = json.loads((ROOT / "admin-web/package.json").read_text(encoding="utf-8"))
    if package["devDependencies"].get("@vue/test-utils") != "2.4.11":
        fail("@vue/test-utils 未锁定为已评审版本 2.4.11")
    if package["devDependencies"].get("happy-dom") != "20.11.6":
        fail("happy-dom 未锁定为已评审版本 20.11.6")
    if package["pnpm"]["overrides"].get("glob@>=10.2.0 <10.5.0") != "10.5.0":
        fail("glob 安全覆盖未锁定为 10.5.0")

    acceptance = json.loads((CONTRACT / "surface-acceptance-v1.json").read_text(encoding="utf-8"))
    if set(item["surfaceId"] for item in acceptance["surfaces"]) != EXPECTED_SURFACES:
        fail("七页验收矩阵缺页或越界")
    dimensions = acceptance["dimensions"]
    if len(dimensions) != 12 or len(set(dimensions)) != 12:
        fail("七页验收矩阵不是十二个唯一维度")
    for surface in acceptance["surfaces"]:
        if len(surface["statuses"]) != len(dimensions):
            fail(f"{surface['surfaceId']} 验收维度数量不正确")
        invalid = set(surface["statuses"]) - {"PASS", "NOT_APPLICABLE"}
        if invalid or surface["statuses"][dimensions.index("directTest")] != "PASS":
            fail(f"{surface['surfaceId']} 尚未完成直接交互验收: {invalid}")
        for path in surface["evidence"]:
            if not (ROOT / path).is_file():
                fail(f"{surface['surfaceId']} 证据文件不存在: {path}")

    progress = json.loads((CONTRACT / "defect-progress-v1.json").read_text(encoding="utf-8"))
    if progress["overallState"] != "OPEN" or progress["batchState"] != "VERIFIED_CANDIDATE":
        fail("G9A-UI 总缺陷或 R3A 批次状态错误")
    if progress["nextBatchAutomaticallyAuthorized"] is not False:
        fail("G9A-R3B 被错误地自动授权")
    seeds = json.loads((CONTRACT / "failure-seeds-v1.json").read_text(encoding="utf-8"))
    if seeds["openP0"] != 0 or seeds["openP1InBatch"] != 0:
        fail("R3A 仍有未关闭 P0/P1")
    if seeds["autoRetryUsed"] or seeds["testsSkippedToPass"]:
        fail("R3A 通过自动重跑或跳过测试伪造绿色结果")

    if admission["externalExecution"] != {key: 0 for key in admission["externalExecution"]}:
        fail("R3A 提升了外部执行证据")

    print(
        "G9A-R3A SCOPE OK: baseline=5302e2f surfaces=7 dimensions=12 "
        "accepted=88 migrations=0 providerNetwork=0 overallFinding=OPEN"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
