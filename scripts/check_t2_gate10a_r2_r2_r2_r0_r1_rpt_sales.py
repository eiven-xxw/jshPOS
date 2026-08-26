#!/usr/bin/env python3
"""校验 Gate 10A R2-R2-R2 R0/R1 只整改 Reporting/RPT-SALES。"""
from __future__ import annotations

import json
import pathlib
import re
import subprocess


ROOT = pathlib.Path(__file__).resolve().parents[1]
BASE = "972fcfafdf131a555e47f5644e0ae914ce6297fb"
BRANCH = "t2/gate10a-r2-r2-r2-r0-r1-rpt-sales-runtime"
CONTRACT = ROOT / "contracts/t2/gate10a-r2-r2-r2-r0-r1-rpt-sales"
MAPPER = "server/ruoyi-modules/jshpos-reporting/src/main/resources/mapper/reporting/ReportingPersistenceMapper.xml"


def fail(message: str) -> None:
    raise SystemExit("T2 Gate10A R0/R1 RPT-SALES ERROR: " + message)


def git(*args: str) -> str:
    result = subprocess.run(["git", "-c", "core.quotepath=false", *args], cwd=ROOT,
                            capture_output=True, text=True, encoding="utf-8")
    if result.returncode:
        fail(result.stderr.strip())
    return result.stdout.strip()


def select(xml: str, statement_id: str) -> str:
    match = re.search(rf'<select\s+id="{re.escape(statement_id)}"[^>]*>(.*?)</select>', xml, re.S)
    if not match:
        fail(f"缺少 Mapper statement {statement_id}")
    return re.sub(r"\s+", " ", match.group(1)).strip()


def require_text(path: str, *tokens: str) -> str:
    source = (ROOT / path).read_text(encoding="utf-8")
    for token in tokens:
        if token not in source:
            fail(f"{path} 缺少 {token}")
    return source


def main() -> None:
    if git("merge-base", "--is-ancestor", BASE, "HEAD"):
        fail("准备阶段最终提交不是当前分支祖先")
    if git("branch", "--show-current") not in {BRANCH, ""}:
        fail("当前分支不是 R0/R1 独立分支")

    changed = set(filter(None, git("diff", "--name-only", BASE).splitlines()))
    changed.update(filter(None, git("ls-files", "--others", "--exclude-standard").splitlines()))
    allowed_prefixes = (
        "contracts/t2/gate10a-r2-r2-r2-r0-r1-rpt-sales/",
        "docs/t2-gate10a-r2-r2-r2-r0-r1-rpt-sales/",
        "server/ruoyi-modules/jshpos-reporting/",
    )
    allowed_exact = {
        "AGENTS.md", ".github/workflows/t2-gate10a-r2-r2-r2-r0-r1-rpt-sales.yml",
        "admin-web/src/api/reporting/contract.ts", "admin-web/src/api/reporting/index.ts",
        "admin-web/src/api/reporting/types.ts", "admin-web/src/api/reporting/__tests__/contract.spec.ts",
        "admin-web/src/views/reporting/operation/index.vue",
        "admin-web/src/views/g9a-r3c/__tests__/reporting-page.spec.ts",
        "admin-web/src/views/g9a-r3d/__tests__/joint-main-operations.spec.ts",
        "contracts/t2/gate5d/openapi-reporting-v1.yaml", "docs/governance/change-log.md",
        "docs/governance/CR-T2G10A-013_RPT-SALES分页导出兼容性准备.md",
        "docs/governance/CR-T2G10A-014_RPT-INVENTORY分页导出兼容性准备.md",
        "docs/governance/CR-T2G10A-015_RPT-PAY-REC分页导出兼容性准备.md",
        "docs/governance/CR-T2G10A-017_RPT-SALES精确整改运行时准入.md",
        "docs/governance/rtm.csv",
        "server/ruoyi-modules/jshpos-release/src/test/java/com/jingshanghui/pos/release/performance/SqlBaselineQueries.java",
        "server/ruoyi-modules/jshpos-release/src/test/java/com/jingshanghui/pos/release/performance/SalesKeysetRemediationMySqlIT.java",
        "scripts/check_t2_gate10a_r2_r2_r2_r0_r1_rpt_sales.py",
        "scripts/audit_t2_gate10a_r2_r2_r2_r0_r1_rpt_sales.py",
        "scripts/build_t2_gate10a_r2_r2_r2_r0_r1_rpt_sales_evidence.py",
    }
    illegal = sorted(path for path in changed if path not in allowed_exact
                     and not path.startswith(allowed_prefixes))
    if illegal:
        fail("存在越界文件: " + ", ".join(illegal))
    migration = sorted(path for path in changed if "/db/migration/" in path or "/sqlite/migrations/" in path)
    if migration:
        fail("未经索引CR禁止迁移变化: " + ", ".join(migration))
    if any(path.endswith(("pom.xml", "pnpm-lock.yaml", "pubspec.lock", "gradle.lockfile")) for path in changed):
        fail("本批禁止依赖或锁文件变化")

    admission = json.loads((CONTRACT / "admission-v1.json").read_text(encoding="utf-8"))
    if admission["startCommit"] != BASE or admission["finding"]["state"] != "OPEN":
        fail("准入起点或 Finding 状态漂移")
    statuses = json.loads((CONTRACT / "status-boundary-v1.json").read_text(encoding="utf-8"))["states"]
    if statuses["G10A-SQL-P2-001"] != "OPEN" or statuses["G10A-RES-P2-001"] != "PREPARED":
        fail("SQL/RES Finding 状态漂移")

    mapper = (ROOT / MAPPER).read_text(encoding="utf-8")
    baseline_mapper = git("show", f"{BASE}:{MAPPER}")
    for statement_id in ("querySales", "queryInventoryCost"):
        if select(mapper, statement_id) != select(baseline_mapper, statement_id):
            fail(f"冻结旧查询被改写: {statement_id}")
    query_page = select(mapper, "querySalesPage")
    for token in ("tenant_id=#{tenantId}", "projection_version=#{projectionVersion}",
                  "store_id IN", "business_date,store_id,terminal_id,cashier_id,currency", "LIMIT #{limit}"):
        if token not in query_page:
            fail("RPT-SALES v2 keyset SQL 缺少 " + token)

    require_text("server/ruoyi-modules/jshpos-reporting/src/main/java/com/jingshanghui/pos/reporting/application/port/ReportingBatchReadPort.java",
                 "MAX_INTERACTIVE_ROWS = 500", "MAX_EXPORT_CHUNK_ROWS = 10_000", "SalesBatchRequest")
    require_text("server/ruoyi-modules/jshpos-reporting/src/main/java/com/jingshanghui/pos/reporting/interfaces/rest/ReportingV2Controller.java",
                 '@RequestMapping("/api/v2")', '@GetMapping("/reports/sales-daily")', "report:operation:read")
    require_text("server/ruoyi-modules/jshpos-reporting/src/main/java/com/jingshanghui/pos/reporting/infrastructure/security/HmacSalesPageCursorCodec.java",
                 "HmacSHA256", "MessageDigest.isEqual", "RPT-R2R2-008")
    export = require_text("server/ruoyi-modules/jshpos-reporting/src/main/java/com/jingshanghui/pos/reporting/application/service/ReportExportService.java",
                          "writeSalesArtifact", "writeResumable", "MAX_EXPORT_CHUNK_ROWS")
    if "stores.stream().flatMap(storeId -> persistence.querySales" in export:
        fail("销售导出仍按门店线性查询")
    require_text("server/ruoyi-modules/jshpos-reporting/src/test/java/com/jingshanghui/pos/reporting/gate10a/ReportingR2R2R2RedBaselineTest.java",
                 "f01SalesInteractiveQuery", "f04SalesExport", "f12StreamingExport")
    require_text("server/ruoyi-modules/jshpos-release/src/test/java/com/jingshanghui/pos/release/performance/SalesKeysetRemediationMySqlIT.java",
                 "SMOKE_10K", "BASELINE_100K", "EXPLAIN FORMAT=JSON", "STOP_AND_REQUEST_INDEPENDENT_INDEX_CR")

    openapi = require_text("contracts/t2/gate5d/openapi-reporting-v1.yaml",
                           "/api/v1/reports/sales-daily:", "/api/v2/reports/sales-daily:",
                           "operationId: querySalesDailyReport", "operationId: querySalesDailyReportV2")
    if openapi.count("operationId: querySalesDailyReportV2") != 1:
        fail("v2 operationId 必须唯一")
    vue = require_text("admin-web/src/views/reporting/operation/index.vue",
                       "querySalesDailyPage", "reporting-sales-load-more", "salesCursor")
    if "querySalesDaily(" in vue:
        fail("销售页面仍调用无界 v1 列表")

    log = (ROOT / "docs/governance/change-log.md").read_text(encoding="utf-8")
    if "CR-T2G10A-017" not in log or "## 4.84" not in (ROOT / "AGENTS.md").read_text(encoding="utf-8"):
        fail("治理准入未记录")
    print(f"T2 Gate10A R0/R1 RPT-SALES OK: changed={len(changed)} migration=0 dependency=0 external=0 finding=OPEN")


if __name__ == "__main__":
    main()
