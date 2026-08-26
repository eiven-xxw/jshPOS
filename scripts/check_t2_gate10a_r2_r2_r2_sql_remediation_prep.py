#!/usr/bin/env python3
"""校验 Gate 10A-R2-R2-R2 只准备 SQL/分页/N+1 精确整改。"""
from __future__ import annotations

import csv
import json
import pathlib
import subprocess


ROOT = pathlib.Path(__file__).resolve().parents[1]
BASE = "8c65991919757cb52786cdf037b7a44f7f095c53"
BRANCH = "t2/gate10a-r2-r2-r2-sql-remediation-prep"
CONTRACT = ROOT / "contracts/t2/gate10a-r2-r2-r2-sql-remediation-prep"
SOURCE_CATALOG = ROOT / "contracts/t2/gate10a-r2-r2-sql-prep/query-catalog-v1.csv"
SOURCE_SUMMARY = ROOT / "contracts/t2/gate10a-r2-r2-r1-sql-baseline/execution-summary-v1.json"
REPORT_IDS = {"RPT-SALES", "RPT-INVENTORY", "RPT-PAY-REC"}
OTHER_IDS = {
    "INV-FEFO", "INV-EXPIRY", "INV-PACKAGE", "PRM-RULES", "PRM-QUOTE-LINES",
    "PAY-FACTS", "PUR-LINES", "TRF-LINES", "MBR-POINTS-FEFO",
}


def fail(message: str) -> None:
    raise SystemExit("T2 Gate10A R2-R2-R2 PREP ERROR: " + message)


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


def read_csv(path: pathlib.Path) -> list[dict[str, str]]:
    with path.open(encoding="utf-8-sig", newline="") as stream:
        return list(csv.DictReader(stream))


def main() -> None:
    if git("merge-base", "--is-ancestor", BASE, "HEAD"):
        fail("R2-R2-R1 最终提交不是当前分支祖先")
    if git("branch", "--show-current") not in {BRANCH, ""}:
        fail("当前分支不是 R2-R2-R2 独立准备分支")

    changed = set(filter(None, git("diff", "--name-only", BASE).splitlines()))
    changed.update(filter(None, git("ls-files", "--others", "--exclude-standard").splitlines()))
    allowed_prefixes = (
        "contracts/t2/gate10a-r2-r2-r2-sql-remediation-prep/",
        "docs/t2-gate10a-r2-r2-r2-sql-remediation-prep/",
    )
    allowed_exact = {
        "AGENTS.md",
        ".github/workflows/t2-gate10a-r2-r2-r2-sql-remediation-prep.yml",
        "docs/governance/change-log.md",
        "docs/governance/CR-T2G10A-012_Gate10A-R2-R2-R2_SQL精确整改准备.md",
        "docs/governance/CR-T2G10A-013_RPT-SALES分页导出兼容性准备.md",
        "docs/governance/CR-T2G10A-014_RPT-INVENTORY分页导出兼容性准备.md",
        "docs/governance/CR-T2G10A-015_RPT-PAY-REC分页导出兼容性准备.md",
        "scripts/check_t2_gate10a_r2_r2_r2_sql_remediation_prep.py",
        "scripts/audit_t2_gate10a_r2_r2_r2_sql_remediation_prep.py",
        "scripts/build_t2_gate10a_r2_r2_r2_sql_remediation_prep_evidence.py",
    }
    illegal = sorted(path for path in changed if path not in allowed_exact and not path.startswith(allowed_prefixes))
    if illegal:
        fail("存在越界文件: " + ", ".join(illegal))
    forbidden = sorted(path for path in changed if path.startswith((
        "server/", "admin-web/", "pos-flutter/", "packages/", "infra/", "infrastructure/"
    )))
    if forbidden:
        fail("准备阶段禁止生产运行时/SQL/Mapper/索引/迁移变化: " + ", ".join(forbidden))

    admission = load("admission-v1.json")
    if admission["admission"] != "CONDITIONAL_GO_PREP_ONLY":
        fail("准备阶段准入状态错误")
    if admission["findingState"] != "OPEN" or admission["resourceFindingState"] != "PREPARED":
        fail("SQL/RES Finding 状态漂移")

    crs = load("report-compatibility-cr-register-v1.json")["items"]
    if len(crs) != 3 or {item["queryId"] for item in crs} != REPORT_IDS:
        fail("报表兼容性 CR 必须精确覆盖3条查询")
    if any(item["runtimeChangeAuthorized"] for item in crs):
        fail("兼容性 CR 被提前授权运行时")

    candidates = read_csv(CONTRACT / "query-remediation-candidates-v1.csv")
    if len(candidates) != 9 or {row["query_id"] for row in candidates} != OTHER_IDS:
        fail("候选计划必须精确覆盖其余9条查询")

    source_queries = read_csv(SOURCE_CATALOG)
    source_ids = {row["query_id"] for row in source_queries}
    if len(source_queries) != 12 or source_ids != REPORT_IDS | OTHER_IDS:
        fail("12条冻结查询身份漂移")

    summary = json.loads(SOURCE_SUMMARY.read_text(encoding="utf-8"))
    journeys = {item["journey"]: item for item in summary["jdbcJourneys"]}
    expected_counts = {
        "REPORT_EXPORT_50_STORES_3_TYPES": 150,
        "PAYMENT_RECONCILIATION_500_REFERENCES": 501,
        "LOT_EXPIRY_500_CANDIDATES": 501,
    }
    observed = {key: journeys[key]["jdbcQueryCount"] for key in expected_counts}
    if observed != expected_counts:
        fail(f"150/501/501 红基线漂移: {observed}")

    designs = load("owner-batch-port-design-v1.json")["designs"]
    if len(designs) != 3 or {item["baselineQueryCount"] for item in designs} != {150, 501}:
        fail("Owner 批量端口必须覆盖三组放大路径")
    if [item["targetAtFrozenCase"] for item in designs] != [3, 4, 2]:
        fail("批量端口目标查询数必须为3/4/2")

    tests = load("failure-tests-v1.json")["tests"]
    if len(tests) != 12 or len({item["seedId"] for item in tests}) != 12:
        fail("失败 seed 必须为12项且身份唯一")

    findings = {item["findingId"]: item for item in load("findings-register-v1.json")["findings"]}
    if findings["G10A-SQL-P2-001"]["state"] != "OPEN" or findings["G10A-SQL-P2-001"]["closed"]:
        fail("SQL Finding 被提前关闭")
    if findings["G10A-RES-P2-001"]["state"] != "PREPARED":
        fail("RES Finding 状态漂移")

    change_log = (ROOT / "docs/governance/change-log.md").read_text(encoding="utf-8")
    for cr_id in ("CR-T2G10A-012", "CR-T2G10A-013", "CR-T2G10A-014", "CR-T2G10A-015"):
        if cr_id not in change_log:
            fail(f"变更日志缺少 {cr_id}")

    print(
        "T2 Gate10A R2-R2-R2 PREP OK: "
        f"changed={len(changed)} reports=3 candidates=9 ports=3 seeds=12 runtime=0 external=0"
    )


if __name__ == "__main__":
    main()
