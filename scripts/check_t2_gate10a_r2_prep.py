#!/usr/bin/env python3
"""校验 Gate 10A-R2 仅包含 Server/数据库/资源整改准备材料。"""
from __future__ import annotations

import csv
import json
import pathlib
import subprocess


ROOT = pathlib.Path(__file__).resolve().parents[1]
BASE = "19c4ef804dc45fca8a17fd378881bbec75b29419"
BRANCH = "t2/gate10a-r2-prep-server-db-maintainability"
CONTRACT = ROOT / "contracts/t2/gate10a-r2-prep"
FINDINGS = {"G10A-MTN-P2-001", "G10A-SQL-P2-001", "G10A-RES-P2-001"}
PRESERVED = {
    "T2-PAY-002": "BLOCKED", "T2-HWD-001": "BLOCKED", "T2-PRN-001": "BLOCKED",
    "T2-PAR-001": "BLOCKED", "T2-UAT-001": "DRAFT", "T2-REL-001": "DRAFT",
    "T2-LIC-001": "DEFERRED", "T2-JSH-001": "DEFERRED",
}


def fail(message: str) -> None:
    raise SystemExit("T2 Gate10A R2 PREP ERROR: " + message)


def git(*args: str) -> str:
    result = subprocess.run(
        ["git", "-c", "core.quotepath=false", *args], cwd=ROOT,
        capture_output=True, text=True, encoding="utf-8",
    )
    if result.returncode:
        fail(result.stderr.strip())
    return result.stdout.strip()


def read_json(path: pathlib.Path) -> dict:
    return json.loads(path.read_text(encoding="utf-8"))


def main() -> None:
    if git("merge-base", "--is-ancestor", BASE, "HEAD"):
        fail("R1 最终治理提交不是当前分支祖先")
    if git("branch", "--show-current") not in {BRANCH, ""}:
        fail("当前分支不属于 Gate10A-R2 准备")

    changed = set(filter(None, git("diff", "--name-only", BASE).splitlines()))
    changed.update(filter(None, git("ls-files", "--others", "--exclude-standard").splitlines()))
    exact = {
        "AGENTS.md", ".github/workflows/t2-gate10a-r2-prep.yml", "docs/adr/README.md",
        "docs/adr/ADR-073-gate10a-internal-quality-hardening-sequence.md",
        "docs/adr/ADR-074-gate10a-r2-server-database-resource-remediation.md",
        "docs/governance/CR-T2G10A-005_Gate10A-R1接受与R2准备准入.md",
        "docs/governance/change-log.md",
        "contracts/t2/gate10a-prep/findings-register-v1.json",
        "contracts/t2/gate10a-prep/batch-plan-v1.json",
        "contracts/t2/gate10a-r1/findings-register-v1.json",
        "docs/t2-gate10a-prep/03_P2_Finding账与影响分析.md",
        "docs/t2-gate10a-prep/04_Gate10A_R1至R4分批修复计划.md",
        "scripts/check_t2_gate10a_r2_prep.py", "scripts/audit_t2_gate10a_r2_prep.py",
        "scripts/build_t2_gate10a_r2_prep_evidence.py",
    }
    prefixes = ("contracts/t2/gate10a-r2-prep/", "docs/t2-gate10a-r2-prep/")
    illegal = sorted(path for path in changed if path not in exact and not path.startswith(prefixes))
    if illegal:
        fail("存在越界文件: " + ", ".join(illegal))
    if any(path.startswith(("server/", "admin-web/", "pos-flutter/", "packages/", "infrastructure/")) for path in changed):
        fail("准备阶段禁止运行时、依赖、配置或基础设施变更")
    if any("/db/migration/" in path or "/migrations/" in path for path in changed):
        fail("准备阶段禁止数据库迁移变更")

    admission = read_json(CONTRACT / "r2-prep-admission-v1.json")
    register = read_json(CONTRACT / "findings-register-v1.json")
    if admission["baseCommit"] != BASE or admission["branch"] != BRANCH:
        fail("准入基线或分支漂移")
    if set(admission["findings"]) != FINDINGS:
        fail("R2 Finding 集合漂移")
    forbidden_flags = (
        admission["runtimeChangesAllowed"], admission["sqlChangesAllowed"],
        admission["dependencyChangesAllowed"], admission["configurationChangesAllowed"],
        admission["publishedMigrationChangesAllowed"],
    )
    if any(forbidden_flags) or admission["newBusinessCapabilities"]:
        fail("准备阶段权限被扩大")
    if any(admission["externalExecution"].values()):
        fail("外部执行必须为零")
    if set(item["findingId"] for item in register["findings"]) != FINDINGS:
        fail("R2 Finding 账不完整")
    if any(item["state"] != "PREPARED_AWAITING_SPONSOR_CONFIRMATION" for item in register["findings"]):
        fail("准备阶段不得把 R2 Finding 标为 VERIFIED 或 CLOSED")

    r1 = read_json(ROOT / "contracts/t2/gate10a-r1/findings-register-v1.json")
    if any(item["state"] != "CLOSED_IN_GATE10A_R1" for item in r1["findings"]):
        fail("R1 Finding 关闭账未固化")

    with (ROOT / "docs/governance/rtm.csv").open(encoding="utf-8-sig", newline="") as stream:
        rows = {row["requirement_id"]: row for row in csv.DictReader(stream)}
    for requirement, expected in PRESERVED.items():
        if rows.get(requirement, {}).get("status") != expected:
            fail(f"外部状态漂移: {requirement}")
    accepted = sum(key.startswith("T2-") and row["status"] == "ACCEPTED" for key, row in rows.items())
    if accepted != 88:
        fail(f"ACCEPTED 需求数量漂移: {accepted}")

    adr = (ROOT / "docs/adr/ADR-074-gate10a-r2-server-database-resource-remediation.md").read_text(encoding="utf-8")
    if "状态：Proposed" not in adr:
        fail("ADR-074 在运行时准入前必须保持 Proposed")
    required_docs = [
        "01_R1关闭与R2范围边界.md", "02_Server可维护性审计与影响分析.md",
        "03_SQL查询计划索引与N加1审计.md", "04_长期资源边界与长稳设计.md",
        "05_失败Seed测试矩阵与验收标准.md", "06_R2串行整改计划.md",
        "07_Gate10A_R2启动评审报告.md", "08_下一步操作指令.md", "09_证据索引.md",
    ]
    if any(not (ROOT / "docs/t2-gate10a-r2-prep" / name).is_file() for name in required_docs):
        fail("R2 准备文档不完整")
    print(f"T2 Gate10A R2 PREP SCOPE OK: changed={len(changed)} runtime=0 sql=0 dependency=0 migration=0 external=0 accepted={accepted}")


if __name__ == "__main__":
    main()
