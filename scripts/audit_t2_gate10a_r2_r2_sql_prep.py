#!/usr/bin/env python3
"""生成 R2-R2 关键查询身份、红基线与零运行时变化证据。"""
from __future__ import annotations

import argparse
import csv
import hashlib
import json
import pathlib
import re
import subprocess


ROOT = pathlib.Path(__file__).resolve().parents[1]
BASE = "f2a9f454d5c306142b71dbae398853ae17daab9e"
CONTRACT = ROOT / "contracts/t2/gate10a-r2-r2-sql-prep"


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
    subprocess.run(["python", "scripts/check_t2_gate10a_r2_r2_sql_prep.py"], cwd=ROOT, check=True)

    with (CONTRACT / "query-catalog-v1.csv").open(encoding="utf-8-sig", newline="") as stream:
        catalog = list(csv.DictReader(stream))
    audited: list[dict[str, object]] = []
    for row in catalog:
        sql = normalized_statement(ROOT / row["mapper_path"], row["statement_id"])
        digest = hashlib.sha256(sql.encode()).hexdigest()
        if digest != row["sql_sha256"]:
            raise AssertionError(f"SQL digest drift: {row['query_id']} {digest}")
        tenant = bool(re.search(r"tenant_id\s*=", sql, re.I))
        order = bool(re.search(r"\border\s+by\b", sql, re.I))
        select_star = bool(re.search(r"\bselect\s+\*", sql, re.I))
        if not tenant or not order or select_star:
            raise AssertionError(f"query boundary drift: {row['query_id']}")
        audited.append({
            "queryId": row["query_id"], "owner": row["owner"], "statementId": row["statement_id"],
            "sqlSha256": digest, "normalizedLength": len(sql), "tenantPredicate": tenant,
            "deterministicOrder": order, "selectStar": select_star,
            "hasLimit": bool(re.search(r"\blimit\b", sql, re.I)),
            "hasForUpdate": bool(re.search(r"\bfor\s+update\b", sql, re.I)),
        })

    mapper_files = [ROOT / line for line in git("ls-files", "server/ruoyi-modules").splitlines()
                    if line.endswith(".xml") and "/src/main/resources/mapper/" in line and "/jshpos-" in line]
    select_count = 0
    select_star_count = 0
    for path in mapper_files:
        text = re.sub(r"<!--.*?-->", "", path.read_text(encoding="utf-8", errors="replace"), flags=re.S)
        select_count += len(re.findall(r"<select\b", text, re.I))
        select_star_count += len(re.findall(r"\bselect\s+\*", text, re.I))
    test_java = [ROOT / line for line in git("ls-files", "server/ruoyi-modules").splitlines()
                 if line.endswith(".java") and "/src/test/" in line]
    explain_files = sum(bool(re.search(r"\bEXPLAIN(?:\s+ANALYZE)?\b",
                                       path.read_text(encoding="utf-8", errors="replace"), re.I))
                        for path in test_java)

    export = (ROOT / "server/ruoyi-modules/jshpos-reporting/src/main/java/com/jingshanghui/pos/reporting/application/service/ReportExportService.java").read_text(encoding="utf-8")
    reconciliation = (ROOT / "server/ruoyi-modules/jshpos-payment/src/main/java/com/jingshanghui/pos/payment/application/service/ReconciliationService.java").read_text(encoding="utf-8")
    lot = (ROOT / "server/ruoyi-modules/jshpos-inventory/src/main/java/com/jingshanghui/pos/inventory/application/service/LotInventoryService.java").read_text(encoding="utf-8")
    amplification = {
        "reportExportPerStoreQueryPaths": len(re.findall(r"stores\.stream\(\)\.flatMap\(storeId\s*->", export)),
        "paymentReferenceLookupInsideEntryLoop": int("mapper.findInternalFactsByReference" in reconciliation
                                                     and "for (Map.Entry<String, List<StatementEntry>> item" in reconciliation),
        "lotPolicyLookupInsideCandidateStream": int("mapper.findNearExpiry" in lot
                                                     and "policyReadPort.requireEffective" in lot),
        "interactiveUnpagedPrimaryQueries": sum(row["current_row_cap"] == "NONE" and row["query_id"].startswith("RPT-") for row in catalog),
    }
    expected_amplification = {
        "reportExportPerStoreQueryPaths": 3,
        "paymentReferenceLookupInsideEntryLoop": 1,
        "lotPolicyLookupInsideCandidateStream": 1,
        "interactiveUnpagedPrimaryQueries": 3,
    }
    if amplification != expected_amplification:
        raise AssertionError(f"red baseline drift: {amplification} != {expected_amplification}")

    changed = set(filter(None, git("diff", "--name-only", BASE).splitlines()))
    runtime_changes = sorted(path for path in changed if path.startswith((
        "server/", "admin-web/", "pos-flutter/", "packages/", "infra/", "infrastructure/"
    )))
    if runtime_changes:
        raise AssertionError(f"runtime change in prep: {runtime_changes}")
    summary = {
        "schemaVersion": "1.0", "gate": "T2_GATE_10A_R2_R2_SQL_PREP",
        "commitSha": git("rev-parse", "HEAD"), "finding": "G10A-SQL-P2-001",
        "classification": "STATIC_GOVERNANCE_MYSQL_PLAN_PREPARATION",
        "mapperXmlFiles": len(mapper_files), "selectStatements": select_count,
        "primaryQueries": len(audited), "queryDigestsMatched": len(audited),
        "explainRegressionFiles": explain_files, "selectStarViolations": select_star_count,
        "amplificationRedBaseline": amplification,
        "runtimeChangesApplied": 0, "databaseChangesApplied": 0,
        "publishedMigrationsModified": 0, "externalExecution": 0,
        "sqlFindingState": "PREPARED_AWAITING_SPONSOR_RUNTIME_CONFIRMATION",
        "resourceFindingState": "PREPARED", "result": "PASS",
    }
    expected_counts = {"mapperXmlFiles": 49, "selectStatements": 365, "explainRegressionFiles": 0, "selectStarViolations": 0}
    for key, expected in expected_counts.items():
        if summary[key] != expected:
            raise AssertionError(f"{key} drift: {summary[key]} != {expected}")
    (output / "summary.json").write_text(json.dumps(summary, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    (output / "query-audit.json").write_text(json.dumps(audited, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    (output / "amplification-red-baseline.json").write_text(json.dumps(amplification, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    (output / "runtime-diff.json").write_text(json.dumps({"base": BASE, "runtimeChanges": runtime_changes}, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print("T2 Gate10A R2-R2 SQL PREP AUDIT PASS: "
          f"mapper={len(mapper_files)} selects={select_count} queries={len(audited)} explain={explain_files} "
          f"n1={amplification['reportExportPerStoreQueryPaths'] + amplification['paymentReferenceLookupInsideEntryLoop'] + amplification['lotPolicyLookupInsideCandidateStream']} runtime=0")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
