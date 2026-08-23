#!/usr/bin/env python3
"""T2 Gate 8B 正式运行时汇总验收、范围和外部零执行门禁。"""
from __future__ import annotations

import argparse
import csv
import json
import pathlib
import subprocess

ROOT = pathlib.Path(__file__).resolve().parents[1]
BASE = "72ff758f1b61e5638664c758cf5ca479b512ddf5"
BRANCH = "t2/gate8b-sprint25-commercial-saas-operations-acceptance"
REQUIREMENT = "T2-E2E-005"
PRESERVED = {
    "T2-PAY-002": "BLOCKED", "T2-HWD-001": "BLOCKED", "T2-PRN-001": "BLOCKED",
    "T2-PAR-001": "BLOCKED", "T2-UAT-001": "DRAFT", "T2-REL-001": "DRAFT",
    "T2-LIC-001": "DEFERRED", "T2-JSH-001": "DEFERRED",
}


def require(value: bool, message: str) -> None:
    if not value:
        raise SystemExit("T2-GATE8B ERROR: " + message)


def git(*args: str) -> str:
    result = subprocess.run(["git", "-c", "core.quotepath=false", *args], cwd=ROOT,
                            capture_output=True, text=True, encoding="utf-8")
    require(result.returncode == 0, "git failed: " + result.stderr.strip())
    return result.stdout.strip()


def rows() -> dict[str, dict[str, str]]:
    with (ROOT / "docs/governance/rtm.csv").open(encoding="utf-8-sig", newline="") as source:
        return {row["requirement_id"]: row for row in csv.DictReader(source)}


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--output", type=pathlib.Path)
    args = parser.parse_args()

    admission = json.loads((ROOT / "contracts/t2/gate8b/gate8b-admission.json").read_text(encoding="utf-8"))
    require(admission["base_commit"] == BASE and admission["branch"] == BRANCH, "基线或分支漂移")
    require(admission["requirement"] == REQUIREMENT, "唯一 Requirement 漂移")
    require(admission["evidence_ceiling"] == "INTERNAL_COMMERCIAL_OPERATIONS_CANDIDATE", "证据等级漂移")
    require(all(value == 0 for value in admission["external_execution"].values()), "外部执行必须为零")
    require(admission["runtime"]["direct_database_business_writes"] == 0, "业务旅程禁止直接数据库写入")

    rtm = rows()
    require(rtm[REQUIREMENT]["status"] in {"IN_PROGRESS", "VERIFIED"}, "汇总需求状态非法")
    for requirement in ("T2-SAA-001", "T2-SUB-001", "T2-SVC-001"):
        require(rtm[requirement]["status"] == "ACCEPTED", requirement + " 状态漂移")
    for requirement, status in PRESERVED.items():
        require(rtm[requirement]["status"] == status, requirement + " 状态漂移")

    required = [
        "docs/adr/ADR-061-gate8b-runtime-api-acceptance.md",
        "docs/governance/CR-T2G8B-005_runtime-commercial-operations-acceptance.md",
        "docs/t2-gate8b/06_T2_Gate8B_SprintS25商业SaaS运营内部汇总验收报告.md",
        "contracts/t2/gate8b/runtime-journey-v1.json",
        "contracts/t2/gate8b/failure-seeds-v1.json",
        "scripts/run_t2_gate8b_runtime_api_journey.py",
        "scripts/build_t2_gate8b_evidence.py",
        ".github/workflows/t2-gate8b.yml",
        "server/ruoyi-modules/jshpos-foundation/src/main/java/com/jingshanghui/pos/foundation/infrastructure/security/RuoYiPlatformPrivilegeSource.java",
        "server/ruoyi-modules/jshpos-foundation/src/test/java/com/jingshanghui/pos/foundation/infrastructure/security/RuoYiPlatformPrivilegeSourceTest.java",
    ]
    require(all((ROOT / path).is_file() for path in required), "必要文件缺失")
    require(git("merge-base", "--is-ancestor", BASE, "HEAD") == "", "Gate8B-Prep 封存提交不是祖先")
    require(git("branch", "--show-current") in {BRANCH, ""}, "当前分支错误")

    runtime_script = (ROOT / required[5]).read_text(encoding="utf-8")
    for path in ("/auth/login", "/api/v1/saas", "/api/v1/subscriptions", "/api/v1/service"):
        require(path in runtime_script, "正式 API 旅程缺少 " + path)
    for marker in ("urllib.request", "FORMAL_RUNTIME", "direct_database_business_writes", "provider_network_calls"):
        require(marker in runtime_script, "运行时证据契约缺少 " + marker)
    require('"clientid": CLIENT_ID' in runtime_script, "正式 API 请求未携带 RuoYi 客户端身份 Header")
    require('%Y-%m-%d %H:%M:%S' in runtime_script, "正式 API LocalDateTime 格式未遵守服务端契约")
    forbidden_runtime = ("pymysql", "mysql.connector", "redis.Redis", "sqlalchemy", "jdbc:", "testbackdoor")
    require(not any(marker in runtime_script for marker in forbidden_runtime), "旅程脚本存在数据库、Redis 或测试后门")

    privilege = (ROOT / required[8]).read_text(encoding="utf-8")
    require("DEFAULT_TENANT_ID.equals(tenantId)" in privilege, "平台角色未限定默认租户")
    require("platform_admin" in privilege, "平台职责分离角色缺失")
    vectors = json.loads((ROOT / required[4]).read_text(encoding="utf-8"))["seeds"]
    require(len(vectors) >= 8 and len({v["id"] for v in vectors}) == len(vectors), "失败 seed 不完整")

    changed = set(filter(None, git("diff", "--name-only", BASE).splitlines())) | set(
        filter(None, git("ls-files", "--others", "--exclude-standard").splitlines()))
    allowed_runtime = {required[8]}
    for name in changed:
        normalized = name.replace("\\", "/")
        require("/db/migration/" not in normalized, "本阶段禁止新增或修改迁移: " + normalized)
        if "/src/main/" in normalized:
            require(normalized in allowed_runtime, "未经 P0/P1 准入的正式运行时变更: " + normalized)
        require(not normalized.startswith(("admin-web/src/", "pos-flutter/lib/", "pos-flutter/android/")),
                "本阶段禁止新增前端或设备运行时: " + normalized)

    result = {
        "gate": "T2-GATE8B-SPRINT-S25", "status": "PASS", "requirement": REQUIREMENT,
        "baseline": BASE, "evidenceLevel": admission["evidence_ceiling"], "fixedSeeds": len(vectors),
        "preservedStates": PRESERVED, "externalExecution": admission["external_execution"],
        "changedFiles": len(changed), "runtimeBusinessDatabaseWrites": 0,
    }
    if args.output:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(result, ensure_ascii=False))


if __name__ == "__main__":
    main()
