#!/usr/bin/env python3
"""校验 Gate 10A-R2-R2 仅准备 SQL 性能整改，不触碰运行时。"""
from __future__ import annotations

import csv
import json
import pathlib
import subprocess


ROOT = pathlib.Path(__file__).resolve().parents[1]
BASE = "f2a9f454d5c306142b71dbae398853ae17daab9e"
BRANCH = "t2/gate10a-r2-r2-sql-prep"
CONTRACT = ROOT / "contracts/t2/gate10a-r2-r2-sql-prep"
PRESERVED = {
    "T2-PAY-002": "BLOCKED", "T2-HWD-001": "BLOCKED", "T2-PRN-001": "BLOCKED",
    "T2-PAR-001": "BLOCKED", "T2-UAT-001": "DRAFT", "T2-REL-001": "DRAFT",
    "T2-LIC-001": "DEFERRED", "T2-JSH-001": "DEFERRED",
}


def fail(message: str) -> None:
    raise SystemExit("T2 Gate10A R2-R2 SQL PREP ERROR: " + message)


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
        fail("R2-R1 最终治理提交不是当前分支祖先")
    if git("branch", "--show-current") not in {BRANCH, ""}:
        fail("当前分支不属于 Gate10A-R2-R2 SQL Prep")

    changed = set(filter(None, git("diff", "--name-only", BASE).splitlines()))
    changed.update(filter(None, git("ls-files", "--others", "--exclude-standard").splitlines()))
    allowed_prefixes = (
        "contracts/t2/gate10a-r2-r2-sql-prep/", "docs/t2-gate10a-r2-r2-sql-prep/",
    )
    allowed_exact = {
        "AGENTS.md", ".github/workflows/t2-gate10a-r2-r2-sql-prep.yml",
        "contracts/t2/gate10a-prep/findings-register-v1.json",
        "contracts/t2/gate10a-r2-r1-mtn/findings-register-v1.json",
        "docs/governance/CR-T2G10A-009_Gate10A-R2-R2_SQL性能整改准备.md",
        "docs/governance/change-log.md",
        "scripts/check_t2_gate10a_r2_r2_sql_prep.py",
        "scripts/audit_t2_gate10a_r2_r2_sql_prep.py",
        "scripts/build_t2_gate10a_r2_r2_sql_prep_evidence.py",
    }
    illegal = sorted(path for path in changed if path not in allowed_exact and not path.startswith(allowed_prefixes))
    if illegal:
        fail("存在越界文件: " + ", ".join(illegal))
    forbidden = sorted(path for path in changed if path.startswith((
        "server/", "admin-web/", "pos-flutter/", "packages/", "infra/", "infrastructure/"
    )))
    if forbidden:
        fail("准备阶段禁止运行时、SQL、索引、配置或迁移变化: " + ", ".join(forbidden))

    admission = load("admission-v1.json")
    if admission["runtimeAdmission"] != "NOT_AUTHORIZED" or admission["externalExecution"] != 0:
        fail("运行时或外部执行被提前准入")
    findings = load("findings-register-v1.json")
    states = {item["findingId"]: item["state"] for item in findings["findings"]}
    expected = {
        "G10A-MTN-P2-001": "CLOSED_IN_GATE10A_R2_R1",
        "G10A-SQL-P2-001": "PREPARED_AWAITING_SPONSOR_RUNTIME_CONFIRMATION",
        "G10A-RES-P2-001": "PREPARED",
    }
    if states != expected:
        fail(f"Finding 状态漂移: {states}")
    with (CONTRACT / "query-catalog-v1.csv").open(encoding="utf-8-sig", newline="") as stream:
        queries = list(csv.DictReader(stream))
    if len(queries) != 12 or len({row["query_id"] for row in queries}) != 12:
        fail("关键查询目录必须为12项且身份唯一")
    if any(row["tenant_scope"] != "REQUIRED" for row in queries):
        fail("关键查询缺少可信租户范围")

    global_findings = json.loads((ROOT / "contracts/t2/gate10a-prep/findings-register-v1.json").read_text(encoding="utf-8"))
    global_states = {item["findingId"]: item["state"] for item in global_findings["findings"]}
    if global_states["G10A-MTN-P2-001"] != "CLOSED_IN_GATE10A_R2_R1":
        fail("已确认 MTN Finding 未关闭")
    if global_states["G10A-SQL-P2-001"] != "OPEN" or global_states["G10A-RES-P2-001"] != "OPEN":
        fail("全局 SQL/RES Finding 被准备阶段提前关闭")

    with (ROOT / "docs/governance/rtm.csv").open(encoding="utf-8-sig", newline="") as stream:
        rows = {row["requirement_id"]: row for row in csv.DictReader(stream)}
    accepted = sum(row["phase"] == "T2" and row["status"] == "ACCEPTED" for row in rows.values())
    if accepted != 88:
        fail(f"ACCEPTED 需求漂移: {accepted}")
    for requirement, state in PRESERVED.items():
        if rows[requirement]["status"] != state:
            fail(f"{requirement} 状态漂移")
    print(f"T2 Gate10A R2-R2 SQL PREP OK: changed={len(changed)} queries=12 accepted={accepted} runtime=0 external=0")


if __name__ == "__main__":
    main()
