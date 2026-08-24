#!/usr/bin/env python3
"""T2-SEC-002 范围、实现、安全不变量与外部零执行门禁。"""
from __future__ import annotations

import argparse
import csv
import json
import pathlib
import subprocess


ROOT = pathlib.Path(__file__).resolve().parents[1]
BASE = "d31bcb325dc94185ed94f87a9028fc381d1932ea"
BRANCH = "t2/gate8c-sprint26a-sec002-runtime"
GATE = "T2-GATE8C-SPRINT-S26A"
CONTRACT_DIR = ROOT / "contracts/t2/gate8c-sec002"
PRESERVED = {
    "T2-MTN-001": "DRAFT", "T2-PERF-002": "DRAFT", "T2-RDY-001": "DRAFT",
    "T2-PAY-002": "BLOCKED", "T2-HWD-001": "BLOCKED", "T2-PRN-001": "BLOCKED",
    "T2-PAR-001": "BLOCKED", "T2-UAT-001": "DRAFT", "T2-REL-001": "DRAFT",
    "T2-LIC-001": "DEFERRED", "T2-JSH-001": "DEFERRED",
}
FINDINGS = {"G8C-SEC-P0-001", "G8C-SEC-P1-002", "G8C-SEC-P1-003"}


def require(condition: bool, message: str) -> None:
    if not condition:
        raise SystemExit("T2-SEC-002 ERROR: " + message)


def git(*args: str) -> str:
    process = subprocess.run(
        ["git", "-c", "core.quotepath=false", *args], cwd=ROOT,
        capture_output=True, text=True, encoding="utf-8",
    )
    require(process.returncode == 0, "git failed: " + process.stderr.strip())
    return process.stdout.strip()


def read(path: str) -> str:
    return (ROOT / path).read_text(encoding="utf-8")


def rtm() -> dict[str, dict[str, str]]:
    with (ROOT / "docs/governance/rtm.csv").open(encoding="utf-8-sig", newline="") as stream:
        return {row["requirement_id"]: row for row in csv.DictReader(stream)}


def changed_files() -> list[str]:
    committed = set(filter(None, git("diff", "--name-only", BASE).splitlines()))
    untracked = set(filter(None, git("ls-files", "--others", "--exclude-standard").splitlines()))
    return sorted(committed | untracked)


def validate_scope() -> list[str]:
    require(git("merge-base", "--is-ancestor", BASE, "HEAD") == "", "Gate 8C-Prep 封存提交不是祖先")
    require(git("branch", "--show-current") in {BRANCH, ""}, "当前分支不属于 T2-SEC-002")
    changed = changed_files()
    require(not [path for path in changed if "/db/migration/" in path], "禁止修改或新增数据库迁移")
    dependency_names = {"pom.xml", "package.json", "pnpm-lock.yaml", "pubspec.yaml", "pubspec.lock", "build.gradle.kts"}
    require(not [path for path in changed if pathlib.PurePosixPath(path).name in dependency_names], "禁止依赖漂移")
    allowed_exact = {
        "AGENTS.md", "README.md", "docs/adr/README.md", "docs/governance/change-log.md",
        "docs/governance/rtm.csv", ".github/workflows/t2-gate8c-sec002.yml",
        "scripts/check_t2_gate8c_sec002.py", "scripts/build_t2_gate8c_sec002_evidence.py",
        "docs/adr/ADR-063-gate8c-production-security-hardening.md",
        "docs/governance/CR-T2G8C-004_sec002-runtime-admission.md",
        "docs/governance/CR-T2G8C-005_sec002-runtime-closure-candidate.md",
        "docs/governance/CR-T2G8C-006_sec002-first-ci-failure.md",
        "docs/governance/CR-T2G8C-007_sec002-verified-conditional-pass.md",
        "server/ruoyi-admin/src/main/resources/application-prod.yml",
        "server/ruoyi-admin/src/main/resources/application.yml",
        "server/ruoyi-admin/src/main/java/org/dromara/web/config/ProductionSecurityConfiguration.java",
        "server/ruoyi-admin/src/test/java/org/dromara/web/config/ProductionSecurityConfigurationTest.java",
        "server/ruoyi-admin/src/test/java/org/dromara/web/config/ProductionConfigurationContractTest.java",
        "server/ruoyi-common/ruoyi-common-security/src/main/java/org/dromara/common/security/config/SecurityConfig.java",
        "server/ruoyi-modules/jshpos-service/src/main/java/com/jingshanghui/pos/service/application/port/ServiceAttachmentStoragePort.java",
        "server/ruoyi-modules/jshpos-service/src/main/java/com/jingshanghui/pos/service/application/service/ServiceApplicationService.java",
        "server/ruoyi-modules/jshpos-service/src/main/java/com/jingshanghui/pos/service/infrastructure/storage/RuoYiServiceAttachmentStorageAdapter.java",
        "server/ruoyi-modules/jshpos-service/src/main/java/com/jingshanghui/pos/service/interfaces/rest/ServiceOperationsController.java",
        "server/ruoyi-modules/jshpos-service/src/test/java/com/jingshanghui/pos/service/application/service/ServiceApplicationServiceTest.java",
        "server/ruoyi-modules/jshpos-service/src/test/java/com/jingshanghui/pos/service/e2e/CommercialSaasOperationsFormalApiE2ETest.java",
        "server/ruoyi-modules/jshpos-service/src/test/java/com/jingshanghui/pos/service/infrastructure/storage/RuoYiServiceAttachmentStorageAdapterTest.java",
        "server/ruoyi-modules/jshpos-service/src/test/java/com/jingshanghui/pos/service/interfaces/rest/ServiceOperationsControllerAttachmentTest.java",
    }
    prefixes = ("docs/t2-gate8c-sec002/", "contracts/t2/gate8c-sec002/")
    illegal = [path for path in changed if path not in allowed_exact and not path.startswith(prefixes)]
    require(not illegal, "存在越界变更: " + ", ".join(illegal))
    return changed


def validate_governance() -> tuple[dict, dict, dict[str, dict[str, str]]]:
    admission = json.loads((CONTRACT_DIR / "sec002-admission.json").read_text(encoding="utf-8"))
    closure = json.loads((CONTRACT_DIR / "findings-closure.json").read_text(encoding="utf-8"))
    rows = rtm()
    require(admission["baseCommit"] == BASE and admission["branch"] == BRANCH, "准入基线漂移")
    require(rows["T2-SEC-002"]["status"] == "VERIFIED", "T2-SEC-002 必须为 VERIFIED")
    for requirement, expected in PRESERVED.items():
        require(rows[requirement]["status"] == expected, requirement + " 状态漂移")
    require(set(closure["findings"].keys()) == FINDINGS, "安全发现集合漂移")
    require(all(item["state"] == "CLOSED_INTERNAL_VERIFIED" for item in closure["findings"].values()), "安全发现未全部内部关闭")
    require(all(value == 0 for value in admission["externalExecution"].values()), "外部执行必须为零")
    accepted = sum(key.startswith("T2-") and row["status"] == "ACCEPTED" for key, row in rows.items())
    require(accepted == 83, f"T2 ACCEPTED 数量漂移: {accepted}")
    return admission, closure, rows


def validate_runtime() -> dict:
    prod = read("server/ruoyi-admin/src/main/resources/application-prod.yml")
    app = read("server/ruoyi-admin/src/main/resources/application.yml")
    gate = read("server/ruoyi-admin/src/main/java/org/dromara/web/config/ProductionSecurityConfiguration.java")
    security = read("server/ruoyi-common/ruoyi-common-security/src/main/java/org/dromara/common/security/config/SecurityConfig.java")
    controller = read("server/ruoyi-modules/jshpos-service/src/main/java/com/jingshanghui/pos/service/interfaces/rest/ServiceOperationsController.java")
    adapter = read("server/ruoyi-modules/jshpos-service/src/main/java/com/jingshanghui/pos/service/infrastructure/storage/RuoYiServiceAttachmentStorageAdapter.java")

    forbidden = ["password: root", "password: ruoyi123", "SJ_cKqBTP", "@monitor.password@", "client-secret: x1Y5"]
    require(not [marker for marker in forbidden if marker in prod], "生产配置仍含静态默认凭据")
    for marker in ("${JSH_DB_PASSWORD}", "${JSH_REDIS_PASSWORD}", "${JSH_JWT_SECRET}", "${JSH_ACTUATOR_PASSWORD}"):
        require(marker in prod, "受控 Secret 引用缺失: " + marker)
    require("${JSH_BOOT_ADMIN_ENABLED:false}" in prod and "${JSH_SNAIL_JOB_ENABLED:false}" in prod,
            "可选集成未默认关闭")
    require("@Profile(\"prod\")" in gate and "SEC-PROD-001" in gate and "requireSecret" in gate,
            "生产启动失败门禁缺失")
    require("include: health,info,prometheus" in prod and "include: health,info,prometheus" in app,
            "Actuator 白名单漂移")
    require("show-details: when_authorized" in prod and "show-details: when_authorized" in app,
            "健康详情授权策略漂移")
    require("管理端点认证失败" in security and "e.getMessage()" not in security,
            "管理端点拒绝响应可能泄漏内部异常")
    require("file.getBytes()" not in controller, "Controller 禁止整包读取附件")
    require(controller.index("file.getSize()") < controller.index("file.getInputStream()"), "附件上限必须先于打开输入流")
    require("BUFFER_BYTES = 64 * 1024" in adapter and "copyBounded" in adapter and "createTempFile" in adapter,
            "受限流式暂存实现缺失")
    require("deleteIfExists" in adapter and "MessageDigest.getInstance(\"SHA-256\")" in adapter,
            "临时文件清理或摘要实现缺失")
    tests = [
        "server/ruoyi-admin/src/test/java/org/dromara/web/config/ProductionSecurityConfigurationTest.java",
        "server/ruoyi-admin/src/test/java/org/dromara/web/config/ProductionConfigurationContractTest.java",
        "server/ruoyi-modules/jshpos-service/src/test/java/com/jingshanghui/pos/service/infrastructure/storage/RuoYiServiceAttachmentStorageAdapterTest.java",
        "server/ruoyi-modules/jshpos-service/src/test/java/com/jingshanghui/pos/service/interfaces/rest/ServiceOperationsControllerAttachmentTest.java",
    ]
    require(all((ROOT / path).is_file() for path in tests), "安全回归测试不完整")
    return {"productionSecretReferences": 4, "actuatorAllowlist": ["health", "info", "prometheus"],
            "attachmentMaximumBytes": 10 * 1024 * 1024, "attachmentBufferBytes": 64 * 1024,
            "securityTestClasses": len(tests)}


def validate_documents() -> None:
    required = [
        "docs/adr/ADR-063-gate8c-production-security-hardening.md",
        "docs/governance/CR-T2G8C-004_sec002-runtime-admission.md",
        "docs/governance/CR-T2G8C-005_sec002-runtime-closure-candidate.md",
        "docs/governance/CR-T2G8C-006_sec002-first-ci-failure.md",
        "docs/governance/CR-T2G8C-007_sec002-verified-conditional-pass.md",
        "docs/t2-gate8c-sec002/01_设计准入与验收冻结.md",
        "docs/t2-gate8c-sec002/02_实现与发现关闭说明.md",
        "docs/t2-gate8c-sec002/03_安全测试矩阵与故障向量.md",
        "docs/t2-gate8c-sec002/04_生产配置与附件资源运行手册.md",
        "docs/t2-gate8c-sec002/05_证据索引.md",
        "docs/t2-gate8c-sec002/06_T2_SEC002独立周门禁报告.md",
        "docs/t2-gate8c-sec002/07_下一步操作指令.md",
        ".github/workflows/t2-gate8c-sec002.yml",
    ]
    require(all((ROOT / path).is_file() for path in required), "T2-SEC-002 交付物不完整")


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--output", type=pathlib.Path)
    args = parser.parse_args()
    changed = validate_scope()
    admission, closure, _ = validate_governance()
    runtime = validate_runtime()
    validate_documents()
    result = {
        "schemaVersion": "1.0", "gate": GATE, "status": "PASS",
        "requirementId": "T2-SEC-002", "requirementStatus": "VERIFIED",
        "evidenceCeiling": admission["evidenceCeiling"], "baselineCommit": BASE,
        "closedFindings": sorted(closure["findings"]), "runtime": runtime,
        "databaseMigrationsChanged": 0, "dependenciesChanged": 0,
        "newBusinessCapabilities": 0, "changedFiles": changed,
        "preservedStates": PRESERVED, "externalExecution": admission["externalExecution"],
        "decision": "CONDITIONAL_PASS_AWAITING_SPONSOR_ACCEPTANCE",
    }
    if args.output:
        target = args.output if args.output.is_absolute() else ROOT / args.output
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({key: value for key, value in result.items() if key != "changedFiles"}, ensure_ascii=False))


if __name__ == "__main__":
    main()
