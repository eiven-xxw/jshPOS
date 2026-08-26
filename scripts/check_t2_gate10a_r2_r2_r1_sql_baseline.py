#!/usr/bin/env python3
"""校验 Gate 10A-R2-R2-R1 只新增测试范围的 SQL 可执行红基线。"""
from __future__ import annotations

import csv
import json
import pathlib
import re
import subprocess


ROOT = pathlib.Path(__file__).resolve().parents[1]
BASE = "8eb77ec855b7bf89f93eedf4c01f7681465f0544"
BRANCH = "t2/gate10a-r2-r2-r1-sql-executable-baseline"
CONTRACT = ROOT / "contracts/t2/gate10a-r2-r2-r1-sql-baseline"
TEST_PREFIX = "server/ruoyi-modules/jshpos-release/src/test/java/com/jingshanghui/pos/release/performance/"
PRESERVED = {
    "T2-PAY-002": "BLOCKED", "T2-HWD-001": "BLOCKED", "T2-PRN-001": "BLOCKED",
    "T2-PAR-001": "BLOCKED", "T2-UAT-001": "DRAFT", "T2-REL-001": "DRAFT",
    "T2-LIC-001": "DEFERRED", "T2-JSH-001": "DEFERRED",
}


def fail(message: str) -> None:
    raise SystemExit("T2 Gate10A R2-R2-R1 SQL BASELINE ERROR: " + message)


def git(*args: str) -> str:
    result = subprocess.run(["git", "-c", "core.quotepath=false", *args], cwd=ROOT,
                            capture_output=True, text=True, encoding="utf-8")
    if result.returncode:
        fail(result.stderr.strip())
    return result.stdout.strip()


def load(name: str) -> dict:
    return json.loads((CONTRACT / name).read_text(encoding="utf-8"))


def main() -> None:
    if git("merge-base", "--is-ancestor", BASE, "HEAD"):
        fail("R2-R2 Prep 最终治理提交不是当前分支祖先")
    if git("branch", "--show-current") not in {BRANCH, ""}:
        fail("当前分支不属于 Gate10A-R2-R2-R1 SQL baseline")

    changed = set(filter(None, git("diff", "--name-only", BASE).splitlines()))
    changed.update(filter(None, git("ls-files", "--others", "--exclude-standard").splitlines()))
    allowed_prefixes = (
        "contracts/t2/gate10a-r2-r2-r1-sql-baseline/",
        "docs/t2-gate10a-r2-r2-r1-sql-baseline/",
        TEST_PREFIX,
    )
    allowed_exact = {
        "AGENTS.md", ".github/workflows/t2-gate10a-r2-r2-r1-sql-baseline.yml",
        "docs/governance/CR-T2G10A-010_Gate10A-R2-R2-R1_SQL可执行红基线.md",
        "docs/governance/change-log.md",
        "scripts/check_t2_gate10a_r2_r2_r1_sql_baseline.py",
        "scripts/audit_t2_gate10a_r2_r2_r1_sql_baseline.py",
        "scripts/build_t2_gate10a_r2_r2_r1_sql_evidence.py",
    }
    illegal = sorted(path for path in changed if path not in allowed_exact
                     and not path.startswith(allowed_prefixes))
    if illegal:
        fail("存在越界文件: " + ", ".join(illegal))
    forbidden = sorted(path for path in changed if (
        "/src/main/" in path or path.endswith("pom.xml") or "/db/migration/" in path
        or path.endswith(("Mapper.xml", "Mapper.java"))
    ))
    if forbidden:
        fail("生产SQL、Mapper、索引、依赖或迁移发生变化: " + ", ".join(forbidden))
    test_java = sorted(path for path in changed if path.startswith(TEST_PREFIX))
    expected_test_java = sorted(TEST_PREFIX + name for name in (
        "SqlBaselineFixture.java", "SqlBaselineQueries.java", "SqlExecutableBaselineMySqlIT.java"
    ))
    if test_java != expected_test_java:
        fail(f"测试范围文件漂移: {test_java}")

    admission = load("admission-v1.json")
    if admission["runtimeSqlRemediationAuthorized"] or admission["externalExecution"] != 0:
        fail("SQL运行时整改或外部执行被提前准入")
    workload = load("workload-execution-v1.json")
    tiers = {item["id"]: item for item in workload["tiers"]}
    if set(tiers) != {"SMOKE_10K", "BASELINE_100K", "TREND_1M"}:
        fail("数据层级漂移")
    if tiers["TREND_1M"]["queryIds"] != ["RPT-SALES"]:
        fail("1m 趋势白名单漂移")
    matrix = load("test-matrix-v1.json")["required"]
    if matrix["required10k"] != 12 or matrix["required100k"] != 12 or matrix["queryTierExecutions"] != 25:
        fail("12条查询与层级执行矩阵不完整")
    if len(load("failure-seeds-v1.json")["seeds"]) != 10:
        fail("失败 seed 必须为10项")
    execution = load("execution-summary-v1.json")
    if len(execution["queryResults"]) != 12:
        fail("可执行摘要必须包含12条查询")
    summary = execution["summary"]
    expected_summary = {
        "queryTierExecutions": 25,
        "compatibilityCrRequired": 3,
        "runtimePlanReviewRequired": 9,
        "goWithoutReview": 0,
        "productionChanges": 0,
        "publishedMigrationChanges": 0,
        "findingClosed": False,
    }
    if summary != expected_summary:
        fail(f"可执行摘要结论漂移: {summary}")
    permission = execution["permissionBoundary"]
    if permission != {
        "selectGranted": True,
        "writeDenied": True,
        "tenantAttackRows": 0,
        "credentialPersisted": False,
    }:
        fail(f"租户或只读权限证据漂移: {permission}")
    if [item["jdbcQueryCount"] for item in execution["jdbcJourneys"]] != [150, 501, 501]:
        fail("三条 JDBC 查询数红基线漂移")
    ci_evidence = load("ci-evidence-v1.json")
    if ci_evidence["workflowRun"]["conclusion"] != "success" or len(ci_evidence["jobs"]) != 11:
        fail("最终可执行 CI 证据不完整")
    if any(job["conclusion"] != "success" for job in ci_evidence["jobs"]):
        fail("最终可执行 CI 存在非绿色 Job")

    query_source = (ROOT / (TEST_PREFIX + "SqlBaselineQueries.java")).read_text(encoding="utf-8")
    integration_source = (ROOT / (TEST_PREFIX + "SqlExecutableBaselineMySqlIT.java")).read_text(encoding="utf-8")
    if len(re.findall(r'\bspec\("[A-Z0-9-]+"', query_source)) != 12:
        fail("JDBC 查询适配器必须恰好12项")
    required_tokens = (
        "EXPLAIN FORMAT=JSON", "EXPLAIN ANALYZE FORMAT=TREE", "SHOW INDEX",
        "10_000", "100_000", "1_000_000", "ABSENT_TENANT", "JdbcQueryCounter",
        "CREATE USER", "GRANT SELECT", "DROP USER", "RPT-SALES"
    )
    missing = [token for token in required_tokens if token not in integration_source]
    if missing:
        fail("可执行证据能力缺失: " + ", ".join(missing))

    with (ROOT / "contracts/t2/gate10a-r2-r2-sql-prep/query-catalog-v1.csv").open(
        encoding="utf-8-sig", newline="") as stream:
        queries = list(csv.DictReader(stream))
    if len(queries) != 12 or len({row["query_id"] for row in queries}) != 12:
        fail("准备阶段冻结查询目录漂移")

    with (ROOT / "docs/governance/rtm.csv").open(encoding="utf-8-sig", newline="") as stream:
        rows = {row["requirement_id"]: row for row in csv.DictReader(stream)}
    accepted = sum(row["phase"] == "T2" and row["status"] == "ACCEPTED" for row in rows.values())
    if accepted != 88:
        fail(f"ACCEPTED需求漂移: {accepted}")
    for requirement, state in PRESERVED.items():
        if rows[requirement]["status"] != state:
            fail(f"{requirement}状态漂移")

    global_findings = json.loads(
        (ROOT / "contracts/t2/gate10a-prep/findings-register-v1.json").read_text(encoding="utf-8"))
    states = {item["findingId"]: item["state"] for item in global_findings["findings"]}
    if states["G10A-SQL-P2-001"] != "OPEN" or states["G10A-RES-P2-001"] != "OPEN":
        fail("SQL或RES全局Finding被基线阶段提前关闭")
    print(f"T2 Gate10A R2-R2-R1 SQL BASELINE OK: changed={len(changed)} queries=12 tiers=25 "
          f"cr=3 noGo=9 go=0 jdbc=150/501/501 testJava=3 accepted={accepted} "
          f"production=0 migration=0 external=0")


if __name__ == "__main__":
    main()
