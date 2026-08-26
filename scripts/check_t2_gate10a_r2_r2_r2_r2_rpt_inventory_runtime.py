#!/usr/bin/env python3
"""校验 RPT-INVENTORY 精确整改的运行时范围、兼容性与停止线。"""
from __future__ import annotations

import json
import pathlib
import re
import subprocess


ROOT = pathlib.Path(__file__).resolve().parents[1]
BASE = "f36df63b21bd3bb98ea0d5022f8fe5fac5def72f"
BRANCH = "t2/gate10a-r2-r2-r2-r2-rpt-inventory-runtime"
CONTRACT = ROOT / "contracts/t2/gate10a-r2-r2-r2-r2-rpt-inventory-runtime"
MAPPER = "server/ruoyi-modules/jshpos-reporting/src/main/resources/mapper/reporting/ReportingPersistenceMapper.xml"


def fail(message: str) -> None:
    raise SystemExit("RPT-INVENTORY RUNTIME ERROR: " + message)


def git(*args: str) -> str:
    result = subprocess.run(["git", "-c", "core.quotepath=false", *args], cwd=ROOT,
                            capture_output=True, text=True, encoding="utf-8")
    if result.returncode:
        fail(result.stderr.strip())
    return result.stdout.strip()


def require(condition: bool, message: str) -> None:
    if not condition:
        fail(message)


def select(xml: str, statement_id: str) -> str:
    match = re.search(rf'<select\s+id="{re.escape(statement_id)}"[^>]*>(.*?)</select>', xml, re.S)
    if not match:
        fail("缺少 Mapper statement " + statement_id)
    return re.sub(r"\s+", " ", match.group(1)).strip()


def require_text(path: str, *tokens: str) -> str:
    source = (ROOT / path).read_text(encoding="utf-8")
    for token in tokens:
        require(token in source, f"{path} 缺少 {token}")
    return source


def main() -> None:
    require(not git("merge-base", "--is-ancestor", BASE, "HEAD"), "准备封存提交不是当前分支祖先")
    require(git("branch", "--show-current") in {BRANCH, ""}, "当前分支不是独立运行分支")

    changed = set(filter(None, git("diff", "--name-only", BASE).splitlines()))
    changed.update(filter(None, git("ls-files", "--others", "--exclude-standard").splitlines()))
    allowed_prefixes = (
        "contracts/t2/gate10a-r2-r2-r2-r2-rpt-inventory-runtime/",
        "docs/t2-gate10a-r2-r2-r2-r2-rpt-inventory-runtime/",
        "server/ruoyi-modules/jshpos-reporting/",
    )
    allowed_exact = {
        "AGENTS.md",
        ".github/workflows/t2-gate10a-r2-r2-r2-r2-rpt-inventory-runtime.yml",
        "contracts/t2/gate5d/openapi-reporting-v1.yaml",
        "docs/governance/CR-T2G10A-014_RPT-INVENTORY分页导出兼容性准备.md",
        "docs/governance/change-log.md",
        "docs/governance/rtm.csv",
        "server/ruoyi-modules/jshpos-release/src/test/java/com/jingshanghui/pos/release/performance/SqlBaselineQueries.java",
        "server/ruoyi-modules/jshpos-release/src/test/java/com/jingshanghui/pos/release/performance/InventoryKeysetRemediationMySqlIT.java",
        "scripts/check_t2_gate10a_r2_r2_r2_r2_rpt_inventory_runtime.py",
        "scripts/audit_t2_gate10a_r2_r2_r2_r2_rpt_inventory_runtime.py",
        "scripts/build_t2_gate10a_r2_r2_r2_r2_rpt_inventory_runtime_evidence.py",
    }
    illegal = sorted(path for path in changed if path not in allowed_exact
                     and not path.startswith(allowed_prefixes))
    require(not illegal, "存在越界文件: " + ", ".join(illegal))
    require(not git("diff", "--name-only", BASE, "--", "server/**/db/migration/**"),
            "未经独立索引 CR 禁止迁移变化")
    require(not any(path.endswith(("pom.xml", "pnpm-lock.yaml", "pubspec.lock", "gradle.lockfile"))
                    for path in changed), "本批禁止依赖或锁文件变化")

    admission = json.loads((CONTRACT / "admission-v1.json").read_text(encoding="utf-8"))
    status = json.loads((CONTRACT / "status-boundary-v1.json").read_text(encoding="utf-8"))
    require(admission["startCommit"] == BASE and admission["finding"]["state"] == "OPEN",
            "准入起点或 SQL Finding 状态漂移")
    require(status["states"]["G10A-RES-P2-001"] == "PREPARED" and status["externalExecution"] == 0,
            "资源 Finding 或外部执行边界漂移")
    require(not status["indexChangeAuthorized"] and not status["migrationChangeAuthorized"],
            "索引或迁移被越权准入")

    mapper = (ROOT / MAPPER).read_text(encoding="utf-8")
    baseline_mapper = git("show", f"{BASE}:{MAPPER}")
    require(select(mapper, "queryInventoryCost") == select(baseline_mapper, "queryInventoryCost"),
            "v1 queryInventoryCost 被修改")
    page_sql = select(mapper, "queryInventoryCostPage")
    for token in ("tenant_id=#{tenantId}", "projection_version=#{projectionVersion}", "store_id IN",
                  "business_date,store_id,warehouse_id,sku_id,currency", "LIMIT #{limit}"):
        require(token in page_sql, "v2 keyset SQL 缺少 " + token)

    require_text("server/ruoyi-modules/jshpos-reporting/src/main/java/com/jingshanghui/pos/reporting/application/port/ReportingBatchReadPort.java",
                 "readInventoryCost", "InventoryCostBatchRequest", "MAX_INTERACTIVE_ROWS = 500")
    require_text("server/ruoyi-modules/jshpos-reporting/src/main/java/com/jingshanghui/pos/reporting/infrastructure/security/HmacInventoryCostPageCursorCodec.java",
                 "HmacSHA256", "MessageDigest.isEqual", "RPT-R2R2-022")
    require_text("server/ruoyi-modules/jshpos-reporting/src/main/java/com/jingshanghui/pos/reporting/interfaces/rest/ReportingV2Controller.java",
                 '@GetMapping("/reports/inventory-cost-daily")', "report:operation:read")
    export = require_text("server/ruoyi-modules/jshpos-reporting/src/main/java/com/jingshanghui/pos/reporting/application/service/ReportExportService.java",
                          "writeInventoryArtifact", "writeResumable", "MAX_EXPORT_CHUNK_ROWS")
    require("stores.stream().flatMap(storeId -> persistence.queryInventoryCost" not in export,
            "库存成本导出仍按门店线性查询")

    openapi = require_text("contracts/t2/gate5d/openapi-reporting-v1.yaml",
                           "/api/v1/reports/inventory-cost-daily:",
                           "/api/v2/reports/inventory-cost-daily:",
                           "operationId: queryInventoryCostDailyReport",
                           "operationId: queryInventoryCostDailyReportV2")
    require(openapi.count("operationId: queryInventoryCostDailyReportV2") == 1, "v2 operationId 必须唯一")
    mysql = require_text("server/ruoyi-modules/jshpos-release/src/test/java/com/jingshanghui/pos/release/performance/InventoryKeysetRemediationMySqlIT.java",
                         "SMOKE_10K", "BASELINE_100K", "twelveFieldConservationPassed",
                         "STOP_AND_REQUEST_INDEPENDENT_INDEX_CR", "schemaOrIndexChanged")
    require("ALTER TABLE" not in mysql.upper() and "CREATE INDEX" not in mysql.upper(),
            "MySQL 探针不得修改索引或数据库对象")
    print(f"RPT-INVENTORY RUNTIME OK: changed={len(changed)} v1=frozen migration=0 dependency=0 external=0 finding=OPEN")


if __name__ == "__main__":
    main()
