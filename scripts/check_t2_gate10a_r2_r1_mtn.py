#!/usr/bin/env python3
"""校验 Gate 10A-R2-R1 只做 Server 可维护性最小整改。"""
from __future__ import annotations

import csv
import hashlib
import json
import pathlib
import re
import subprocess


ROOT = pathlib.Path(__file__).resolve().parents[1]
BASE = "5b02eebe165a6151b08f3d27fb64ec58210e3adf"
BRANCH = "t2/gate10a-r2-r1-mtn-runtime"
CONTRACT = ROOT / "contracts/t2/gate10a-r2-r1-mtn"
PRESERVED = {
    "T2-PAY-002": "BLOCKED", "T2-HWD-001": "BLOCKED", "T2-PRN-001": "BLOCKED",
    "T2-PAR-001": "BLOCKED", "T2-UAT-001": "DRAFT", "T2-REL-001": "DRAFT",
    "T2-LIC-001": "DEFERRED", "T2-JSH-001": "DEFERRED",
}


def fail(message: str) -> None:
    raise SystemExit("T2 Gate10A R2-R1 MTN ERROR: " + message)


def git(*args: str) -> str:
    result = subprocess.run(["git", "-c", "core.quotepath=false", *args], cwd=ROOT,
                            capture_output=True, text=True, encoding="utf-8")
    if result.returncode:
        fail(result.stderr.strip())
    return result.stdout.strip()


def load(name: str) -> dict:
    return json.loads((CONTRACT / name).read_text(encoding="utf-8"))


def public_methods(source: str) -> set[str]:
    return set(re.findall(r"(?m)^\s+public\s+(?!class\b|record\b|interface\b)[\w<>, ?.\[\]]+\s+(\w+)\s*\(", source))


def transactional_methods(source: str) -> set[str]:
    pattern = r"@Transactional(?:\([^)]*\))?\s+(?:@Override\s+)?public\s+[\w<>, ?.\[\]]+\s+(\w+)\s*\("
    return set(re.findall(pattern, source, re.MULTILINE))


def main() -> None:
    if git("merge-base", "--is-ancestor", BASE, "HEAD"):
        fail("R2-Prep 最终封存提交不是当前分支祖先")
    if git("branch", "--show-current") not in {BRANCH, ""}:
        fail("当前分支不属于 Gate10A-R2-R1")

    changed = set(filter(None, git("diff", "--name-only", BASE).splitlines()))
    changed.update(filter(None, git("ls-files", "--others", "--exclude-standard").splitlines()))
    allowed_prefixes = (
        "contracts/t2/gate10a-r2-r1-mtn/", "docs/t2-gate10a-r2-r1-mtn/",
    )
    allowed_exact = {
        "AGENTS.md", ".github/workflows/t2-gate10a-r2-r1-mtn.yml", "docs/adr/README.md",
        "docs/adr/ADR-074-gate10a-r2-server-database-resource-remediation.md",
        "docs/governance/CR-T2G10A-007_Gate10A-R2-R1可维护性正式整改准入.md",
        "docs/governance/change-log.md", "contracts/t2/gate10a-prep/findings-register-v1.json",
        "scripts/check_t2_gate10a_r2_r1_mtn.py", "scripts/audit_t2_gate10a_r2_r1_mtn.py",
        "scripts/build_t2_gate10a_r2_r1_mtn_evidence.py",
    }
    runtime_roots = (
        "server/ruoyi-modules/jshpos-procurement/src/main/java/com/jingshanghui/pos/procurement/application/service/",
        "server/ruoyi-modules/jshpos-procurement/src/test/java/com/jingshanghui/pos/procurement/application/service/",
        "server/ruoyi-modules/jshpos-inventory/src/main/java/com/jingshanghui/pos/inventory/application/service/",
        "server/ruoyi-modules/jshpos-inventory/src/test/java/com/jingshanghui/pos/inventory/application/service/",
        "server/ruoyi-modules/jshpos-transfer/src/main/java/com/jingshanghui/pos/transfer/application/service/",
        "server/ruoyi-modules/jshpos-transfer/src/test/java/com/jingshanghui/pos/transfer/application/service/",
        "server/ruoyi-modules/jshpos-returns/src/main/java/com/jingshanghui/pos/returns/application/service/",
        "server/ruoyi-modules/jshpos-returns/src/test/java/com/jingshanghui/pos/returns/application/service/",
    )
    illegal = sorted(path for path in changed if path not in allowed_exact
                     and not path.startswith(allowed_prefixes + runtime_roots))
    if illegal:
        fail("存在越界文件: " + ", ".join(illegal))
    forbidden_suffixes = ("pom.xml", ".xml", ".sql", ".yml", ".yaml", ".properties")
    forbidden = sorted(path for path in changed if path.endswith(forbidden_suffixes)
                       and path != ".github/workflows/t2-gate10a-r2-r1-mtn.yml")
    if forbidden:
        fail("禁止 SQL/XML/依赖/配置/迁移变化: " + ", ".join(forbidden))
    if any("/interfaces/" in path or "/controller/" in path or "/mapper/" in path for path in changed):
        fail("禁止把整改移动到 Controller 或 Mapper")

    api = load("behavior-golden-v1.json")
    for service in api["services"]:
        source = (ROOT / service["path"]).read_text(encoding="utf-8")
        expected = set(service["publicMethods"])
        actual = public_methods(source)
        if actual != expected:
            fail(f'{service["class"]} 公开 API 漂移: expected={sorted(expected)} actual={sorted(actual)}')
        if transactional_methods(source) != expected:
            fail(f'{service["class"]} @Transactional 边界漂移')
    for family in api["errorCodeFamilies"]:
        codes: list[str] = []
        for path in sorted((ROOT / family["root"]).glob("*.java")):
            codes.extend(re.findall(r'new ServiceException\("([A-Z0-9-]+):', path.read_text(encoding="utf-8")))
        digest = hashlib.sha256("\n".join(sorted(codes)).encode()).hexdigest()
        if len(codes) != family["count"] or digest != family["sha256"]:
            fail(f'错误码金标漂移: {family["root"]}')

    budget = load("class-budget-v1.json")
    whitelist = {item["path"] for item in budget["existingLargeClasses"]}
    for item in budget["existingLargeClasses"]:
        lines = len((ROOT / item["path"]).read_text(encoding="utf-8").splitlines())
        if lines > item["baseline"]:
            fail(f'既有大类行数增加: {item["path"]} {lines}>{item["baseline"]}')
        if lines > item["targetMaximum"]:
            fail(f'风险类尚未达到本批预算: {item["path"]} {lines}>{item["targetMaximum"]}')
    production = ROOT / "server/ruoyi-modules"
    for path in production.glob("jshpos-*/src/main/java/**/*.java"):
        relative = path.relative_to(ROOT).as_posix()
        lines = len(path.read_text(encoding="utf-8").splitlines())
        if relative not in whitelist and lines > budget["newProductionClassMaximum"]:
            fail(f'非白名单生产类超过400行: {relative}={lines}')

    with (ROOT / "docs/governance/rtm.csv").open(encoding="utf-8-sig", newline="") as stream:
        rows = {row["requirement_id"]: row for row in csv.DictReader(stream)}
    accepted = sum(row["status"] == "ACCEPTED" for row in rows.values())
    if accepted != 88:
        fail(f"ACCEPTED 需求漂移: {accepted}")
    for requirement, state in PRESERVED.items():
        if rows[requirement]["status"] != state:
            fail(f"{requirement} 状态漂移")
    if "- 状态：Accepted" not in (ROOT / "docs/adr/ADR-074-gate10a-r2-server-database-resource-remediation.md").read_text(encoding="utf-8"):
        fail("ADR-074 尚未 Accepted")
    findings = load("findings-register-v1.json")
    if findings["findings"][0]["state"] not in {"IN_PROGRESS", "VERIFIED_AWAITING_SPONSOR_CONFIRMATION"}:
        fail("MTN Finding 状态非法")
    if findings["preserved"] != {"G10A-SQL-P2-001": "PREPARED", "G10A-RES-P2-001": "PREPARED"}:
        fail("SQL/RES Finding 被提前改变")
    print(f"T2 Gate10A R2-R1 MTN OK: changed={len(changed)} accepted={accepted} external=0")


if __name__ == "__main__":
    main()
