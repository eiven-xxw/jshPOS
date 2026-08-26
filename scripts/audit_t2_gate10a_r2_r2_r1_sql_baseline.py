#!/usr/bin/env python3
"""生成 R2-R2-R1 查询身份、测试范围与证据完整性审计。"""
from __future__ import annotations

import argparse
import csv
import hashlib
import json
import pathlib
import re
import subprocess


ROOT = pathlib.Path(__file__).resolve().parents[1]
BASE = "8eb77ec855b7bf89f93eedf4c01f7681465f0544"
CATALOG = ROOT / "contracts/t2/gate10a-r2-r2-sql-prep/query-catalog-v1.csv"


def git(*args: str) -> str:
    return subprocess.check_output(["git", "-c", "core.quotepath=false", *args], cwd=ROOT,
                                   text=True, encoding="utf-8").strip()


def normalized_statement(path: pathlib.Path, statement_id: str) -> str:
    text = path.read_text(encoding="utf-8")
    match = re.search(r'<select\s+id="' + re.escape(statement_id) + r'"[^>]*>(.*?)</select>', text, re.S)
    if not match:
        raise AssertionError(f"missing mapper statement: {path}:{statement_id}")
    sql = re.sub(r"<!--.*?-->", "", match.group(1), flags=re.S)
    return re.sub(r"\s+", " ", sql).strip()


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--output-dir", required=True, type=pathlib.Path)
    args = parser.parse_args()
    output = args.output_dir if args.output_dir.is_absolute() else ROOT / args.output_dir
    output.mkdir(parents=True, exist_ok=True)
    subprocess.run(["python", "scripts/check_t2_gate10a_r2_r2_r1_sql_baseline.py"], cwd=ROOT, check=True)

    with CATALOG.open(encoding="utf-8-sig", newline="") as stream:
        catalog = list(csv.DictReader(stream))
    query_audit = []
    for row in catalog:
        sql = normalized_statement(ROOT / row["mapper_path"], row["statement_id"])
        digest = hashlib.sha256(sql.encode()).hexdigest()
        if digest != row["sql_sha256"]:
            raise AssertionError(f"SQL digest drift: {row['query_id']}")
        query_audit.append({
            "queryId": row["query_id"], "owner": row["owner"], "statementId": row["statement_id"],
            "sourceSqlSha256": digest, "tenantPredicate": bool(re.search(r"tenant_id\s*=", sql, re.I)),
            "selectStar": bool(re.search(r"\bselect\s+\*", sql, re.I)),
        })
    if not all(item["tenantPredicate"] and not item["selectStar"] for item in query_audit):
        raise AssertionError("query tenant or select-star boundary drift")

    # `git diff` 不包含尚未进入候选提交的测试夹具；准备阶段本地审计也必须
    # 将未跟踪文件计入范围，避免把“尚未 git add”误报为测试文件缺失。
    changed_set = set(filter(None, git("diff", "--name-only", BASE).splitlines()))
    changed_set.update(filter(None, git("ls-files", "--others", "--exclude-standard").splitlines()))
    changed = sorted(path.replace("\\", "/") for path in changed_set)
    production = [path for path in changed if "/src/main/" in path or path.endswith("pom.xml")
                  or "/db/migration/" in path or path.endswith(("Mapper.xml", "Mapper.java"))]
    test_java = [path for path in changed if "/src/test/" in path and path.endswith(".java")]
    summary = {
        "schemaVersion": "1.0",
        "gate": "T2_GATE_10A_R2_R2_R1_SQL_EXECUTABLE_BASELINE",
        "commitSha": git("rev-parse", "HEAD"),
        "classification": "MYSQL84_SYNTHETIC_EXECUTABLE_RED_BASELINE",
        "queryCount": len(query_audit),
        "tierExecutionCount": 25,
        "testJavaFiles": len(test_java),
        "productionChanges": len(production),
        "publishedMigrationChanges": 0,
        "runtimeSqlRemediationAuthorized": False,
        "externalExecution": 0,
        "result": "PASS" if len(query_audit) == 12 and len(test_java) == 3 and not production else "FAIL",
    }
    if summary["result"] != "PASS":
        raise AssertionError(summary)
    (output / "summary.json").write_text(json.dumps(summary, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    (output / "query-source-audit.json").write_text(
        json.dumps(query_audit, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    (output / "scope-diff.json").write_text(json.dumps({
        "base": BASE, "changed": changed, "testJava": test_java, "production": production
    }, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print("T2 Gate10A R2-R2-R1 SQL BASELINE AUDIT PASS: queries=12 tiers=25 testJava=3 production=0 migration=0")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
