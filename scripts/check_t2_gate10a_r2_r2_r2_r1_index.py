#!/usr/bin/env python3
"""校验 Gate 10A RPT-SALES V88 索引专项的精确范围。"""
from __future__ import annotations

import json
import pathlib
import re
import subprocess


ROOT = pathlib.Path(__file__).resolve().parents[1]
BASE = "53bcf39d868092142e6464b8c9db730b0074dd2a"
BRANCH = "t2/gate10a-r2-r2-r2-r1-index-runtime"
CONTRACT = ROOT / "contracts/t2/gate10a-r2-r2-r2-r1-index"
MIGRATION = (
    "server/ruoyi-modules/jshpos-reporting/src/main/resources/db/migration/"
    "V202608260088__reporting_sales_keyset_index.sql"
)


def fail(message: str) -> None:
    raise SystemExit("T2 Gate10A RPT-SALES INDEX ERROR: " + message)


def git(*args: str) -> str:
    result = subprocess.run(["git", "-c", "core.quotepath=false", *args], cwd=ROOT,
                            capture_output=True, text=True, encoding="utf-8")
    if result.returncode:
        fail(result.stderr.strip())
    return result.stdout.strip()


def require(condition: bool, message: str) -> None:
    if not condition:
        fail(message)


def main() -> None:
    require(not git("merge-base", "--is-ancestor", BASE, "HEAD"), "起点提交不是当前分支祖先")
    require(git("branch", "--show-current") in {BRANCH, ""}, "当前分支不是 INDEX 独立分支")

    changed = set(filter(None, git("diff", "--name-only", BASE).splitlines()))
    changed.update(filter(None, git("ls-files", "--others", "--exclude-standard").splitlines()))
    allowed_prefixes = (
        "contracts/t2/gate10a-r2-r2-r2-r1-index/",
        "docs/t2-gate10a-r2-r2-r2-r1-index/",
    )
    allowed_exact = {
        "AGENTS.md",
        ".github/workflows/t2-gate10a-r2-r2-r2-r0-r1-rpt-sales.yml",
        "docs/governance/CR-T2G10A-018_RPT-SALES-keyset索引前向迁移提案.md",
        "docs/governance/change-log.md",
        "docs/governance/rtm.csv",
        "contracts/t2/gate10a-r2-r2-r2-r0-r1-rpt-sales/index-cr-proposal-v1.json",
        MIGRATION,
        "server/ruoyi-modules/jshpos-reporting/src/test/java/com/jingshanghui/pos/reporting/migration/ReportingSalesKeysetIndexMigrationPolicyTest.java",
        "server/ruoyi-modules/jshpos-release/src/test/java/com/jingshanghui/pos/release/performance/SalesKeysetRemediationMySqlIT.java",
        "scripts/check_t2_gate10a_r2_r2_r2_r1_index.py",
        "scripts/audit_t2_gate10a_r2_r2_r2_r1_index.py",
        "scripts/build_t2_gate10a_r2_r2_r2_r1_index_evidence.py",
    }
    illegal = sorted(path for path in changed if path not in allowed_exact
                     and not path.startswith(allowed_prefixes))
    require(not illegal, "存在越界文件: " + ", ".join(illegal))

    migration_changes = [line for line in git("diff", "--name-status", BASE, "--",
                                               "server/**/db/migration/**").splitlines() if line]
    require(migration_changes == [f"A\t{MIGRATION}"],
            "迁移变化必须且只能是新增 V88: " + repr(migration_changes))
    require(not any(path.endswith(("pom.xml", "pnpm-lock.yaml", "pubspec.lock", "gradle.lockfile"))
                    for path in changed), "本批禁止依赖或锁文件变化")

    frozen = [
        "server/ruoyi-modules/jshpos-reporting/src/main/resources/mapper/reporting/ReportingPersistenceMapper.xml",
        "contracts/t2/gate5d/openapi-reporting-v1.yaml",
    ]
    for path in frozen:
        require(path not in changed, "冻结 SQL/API 契约发生变化: " + path)
    production_java = [path for path in changed if path.startswith("server/")
                       and "/src/main/java/" in path]
    require(not production_java, "生产 Java 发生变化: " + ", ".join(production_java))

    sql = (ROOT / MIGRATION).read_text(encoding="utf-8").lower()
    normalized = re.sub(r"\s+", " ", sql)
    required = [
        "alter table rpt_sales_daily",
        "add index idx_rpt_sales_keyset",
        "tenant_id, projection_version, business_date, store_id, terminal_id, cashier_id, currency",
        "algorithm=inplace",
        "lock=none",
    ]
    for token in required:
        require(token in normalized, "V88 缺少精确约束: " + token)
    require(not re.search(r"\bdrop\b|\bmodify\b|\bchange\b", normalized),
            "V88 禁止删除或修改既有对象")

    admission = json.loads((CONTRACT / "admission-v1.json").read_text(encoding="utf-8"))
    require(admission["startCommit"] == BASE and admission["changeRequest"] == "CR-T2G10A-018",
            "准入起点或 CR 漂移")
    states = json.loads((CONTRACT / "status-boundary-v1.json").read_text(encoding="utf-8"))["states"]
    require(states["G10A-SQL-P2-001"] == "OPEN" and states["G10A-RES-P2-001"] == "PREPARED",
            "SQL/RES Finding 状态漂移")

    policy = (ROOT / "server/ruoyi-modules/jshpos-reporting/src/test/java/com/jingshanghui/pos/reporting/"
                     "migration/ReportingSalesKeysetIndexMigrationPolicyTest.java").read_text(encoding="utf-8")
    mysql = (ROOT / "server/ruoyi-modules/jshpos-release/src/test/java/com/jingshanghui/pos/release/"
                    "performance/SalesKeysetRemediationMySqlIT.java").read_text(encoding="utf-8")
    for token in ("approved V88 RPT-SALES keyset index migration", "doesNotContain(\"drop index\")"):
        require(token in policy, "静态失败回归缺少 " + token)
    for token in ("202608260087", "202608260088", "captureV87RedPlan", "assertApprovedIndex",
                  "fullScanObserved", "filesortObserved", "approvedIndexObserved", "observesFilesort"):
        require(token in mysql, "MySQL 8.4 专项缺少 " + token)

    agents = (ROOT / "AGENTS.md").read_text(encoding="utf-8")
    log = (ROOT / "docs/governance/change-log.md").read_text(encoding="utf-8")
    cr = (ROOT / "docs/governance/CR-T2G10A-018_RPT-SALES-keyset索引前向迁移提案.md").read_text(encoding="utf-8")
    require("## 4.85" in agents and "CR-T2G10A-021" in log
            and "INDEX_VERIFIED_AWAITING_SPONSOR_CONFIRMATION" in cr,
            "治理批准与验证记录不完整")
    print(f"T2 Gate10A RPT-SALES INDEX OK: changed={len(changed)} migration=V88-only "
          "sqlMapperApi=unchanged finding=OPEN res=PREPARED external=0")


if __name__ == "__main__":
    main()
