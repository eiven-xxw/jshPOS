#!/usr/bin/env python3
"""校验 RPT-INVENTORY 精确整改准备阶段没有越界修改生产代码。"""
from __future__ import annotations

import hashlib
import json
import pathlib
import re
import subprocess


ROOT = pathlib.Path(__file__).resolve().parents[1]
BASE = "206b07f93014a468102380577a987ac85af1ceff"
BRANCH = "t2/gate10a-r2-r2-r2-r2-rpt-inventory-prep"
CONTRACT = ROOT / "contracts/t2/gate10a-r2-r2-r2-r2-rpt-inventory-prep"


def fail(message: str) -> None:
    raise SystemExit("RPT-INVENTORY PREP ERROR: " + message)


def git(*args: str) -> str:
    result = subprocess.run(
        ["git", "-c", "core.quotepath=false", *args], cwd=ROOT,
        capture_output=True, text=True, encoding="utf-8",
    )
    if result.returncode:
        fail(result.stderr.strip())
    return result.stdout.strip()


def load(name: str) -> dict:
    return json.loads((CONTRACT / name).read_text(encoding="utf-8"))


def sha256(path: pathlib.Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def main() -> None:
    if git("merge-base", "--is-ancestor", BASE, "HEAD"):
        fail("RPT-SALES V88 封存提交不是当前分支祖先")
    if git("branch", "--show-current") not in {BRANCH, ""}:
        fail("当前分支不是 RPT-INVENTORY 独立准备分支")

    changed = set(filter(None, git("diff", "--name-only", BASE).splitlines()))
    changed.update(filter(None, git("ls-files", "--others", "--exclude-standard").splitlines()))
    allowed_prefixes = (
        "contracts/t2/gate10a-r2-r2-r2-r2-rpt-inventory-prep/",
        "docs/t2-gate10a-r2-r2-r2-r2-rpt-inventory-prep/",
    )
    allowed_exact = {
        "AGENTS.md",
        ".github/workflows/t2-gate10a-r2-r2-r2-r2-rpt-inventory-prep.yml",
        "docs/governance/CR-T2G10A-014_RPT-INVENTORY分页导出兼容性准备.md",
        "docs/governance/change-log.md",
        "scripts/check_t2_gate10a_r2_r2_r2_r2_rpt_inventory_prep.py",
        "scripts/audit_t2_gate10a_r2_r2_r2_r2_rpt_inventory_prep.py",
        "scripts/build_t2_gate10a_r2_r2_r2_r2_rpt_inventory_prep_evidence.py",
        "server/ruoyi-modules/jshpos-reporting/src/test/java/com/jingshanghui/pos/reporting/gate10a/ReportingInventoryRemediationPrepRedBaselineTest.java",
        "server/ruoyi-modules/jshpos-release/src/test/java/com/jingshanghui/pos/release/performance/InventoryRemediationPrepMySqlIT.java",
    }
    illegal = sorted(path for path in changed if path not in allowed_exact and not path.startswith(allowed_prefixes))
    if illegal:
        fail("存在越界文件: " + ", ".join(illegal))

    forbidden_fragments = ("/src/main/", "/db/migration/", "pom.xml", "gradle.lockfile", "pnpm-lock.yaml", "pubspec.lock")
    forbidden = sorted(path for path in changed if any(fragment in path for fragment in forbidden_fragments))
    if forbidden:
        fail("准备阶段禁止生产代码/SQL/索引/迁移/依赖变化: " + ", ".join(forbidden))

    frozen_files = (
        "server/ruoyi-modules/jshpos-reporting/src/main/resources/mapper/reporting/ReportingPersistenceMapper.xml",
        "server/ruoyi-modules/jshpos-reporting/src/main/java/com/jingshanghui/pos/reporting/application/service/ReportQueryService.java",
        "server/ruoyi-modules/jshpos-reporting/src/main/java/com/jingshanghui/pos/reporting/application/service/ReportExportService.java",
        "server/ruoyi-modules/jshpos-reporting/src/main/java/com/jingshanghui/pos/reporting/application/port/ReportingPersistencePort.java",
        "server/ruoyi-modules/jshpos-reporting/src/main/java/com/jingshanghui/pos/reporting/interfaces/rest/ReportingController.java",
        "server/ruoyi-modules/jshpos-reporting/src/main/java/com/jingshanghui/pos/reporting/interfaces/rest/ReportingV2Controller.java",
        "contracts/t2/gate5d/openapi-reporting-v1.yaml",
    )
    if git("diff", "--name-only", BASE, "--", *frozen_files):
        fail("当前契约、服务、Mapper 或 OpenAPI 被提前修改")
    if git("diff", "--name-only", BASE, "--", "server/**/db/migration/**"):
        fail("准备阶段出现迁移变化")

    admission = load("admission-v1.json")
    if admission["decision"] != "PREP_IN_PROGRESS_RUNTIME_NOT_ADMITTED":
        fail("准入状态不是 PREP_ONLY")
    status = load("status-boundary-v1.json")
    if admission["finding"]["state"] != "OPEN" or status["states"]["G10A-RES-P2-001"] != "PREPARED":
        fail("SQL/RES Finding 状态漂移")

    current = load("current-contract-v1.json")
    if current["queryId"] != "RPT-INVENTORY" or current["runtimeChangeAuthorized"]:
        fail("当前契约身份或授权边界错误")
    mapper_path = ROOT / current["mapperPath"]
    mapper_text = mapper_path.read_text(encoding="utf-8")
    match = re.search(r'<select\s+id="queryInventoryCost"[^>]*>(.*?)</select>', mapper_text, re.S)
    if not match:
        fail("冻结 RPT-INVENTORY statement 不存在")
    normalized = re.sub(r"\s+", " ", re.sub(r"(?s)<!--.*?-->", "", match.group(1))).strip()
    if hashlib.sha256(normalized.encode("utf-8")).hexdigest() != current["sourceSqlSha256"]:
        fail("冻结 RPT-INVENTORY SQL 摘要漂移")

    candidate = load("candidate-design-v1.json")
    if candidate["state"] != "DESIGN_ONLY_AWAITING_RUNTIME_ADMISSION":
        fail("候选设计被提前准入运行时")
    if candidate["indexChangeAuthorized"] or candidate["migrationChangeAuthorized"]:
        fail("索引或迁移被提前授权")
    if len(candidate["indexCandidates"]) != 2:
        fail("候选索引对比必须保持两项")

    seeds = load("failure-seeds-v1.json")["seeds"]
    if len(seeds) != 7 or len({item["seedId"] for item in seeds}) != 7:
        fail("失败 seed 必须为7项且身份唯一")
    if status["externalExecution"] != 0:
        fail("外部执行边界被改变")

    red_test = (ROOT / next(path for path in allowed_exact if path.endswith("ReportingInventoryRemediationPrepRedBaselineTest.java"))).read_text(encoding="utf-8")
    for token in ("f02CurrentInventoryInteractiveQueryIsUnbounded", "f04CurrentInventoryExportStillReadsEachStoreSeparately", "inventoryBatchPortAndSignedCursorAreNotYetAdmitted"):
        if token not in red_test:
            fail("静态红基线缺少 " + token)
    mysql_test = (ROOT / next(path for path in allowed_exact if path.endswith("InventoryRemediationPrepMySqlIT.java"))).read_text(encoding="utf-8")
    for token in ("SMOKE_10K", "BASELINE_100K", "legacy-export-query-count.json", "runtimeChangeAuthorized"):
        if token not in mysql_test:
            fail("MySQL 红基线缺少 " + token)

    print(
        "RPT-INVENTORY PREP OK: "
        f"changed={len(changed)} seeds=7 production=0 sqlMapperApi=0 indexMigration=0 "
        "finding=OPEN res=PREPARED external=0"
    )


if __name__ == "__main__":
    main()
