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
    require(rtm[REQUIREMENT]["status"] == "VERIFIED", "收口提交必须保持汇总需求为 VERIFIED")
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
        "server/ruoyi-common/ruoyi-common-core/src/main/java/org/dromara/common/core/constant/SystemConstants.java",
        "server/ruoyi-modules/jshpos-saas/src/test/java/com/jingshanghui/pos/saas/security/SensitiveRequestLoggingContractTest.java",
        "server/script/sql/ry_workflow.sql",
        "server/ruoyi-modules/ruoyi-system/src/main/java/org/dromara/system/service/impl/SysPermissionServiceImpl.java",
        "server/ruoyi-modules/jshpos-saas/src/test/java/com/jingshanghui/pos/saas/security/SuperAdminPermissionPatternContractTest.java",
        "server/ruoyi-modules/jshpos-foundation/src/main/java/com/jingshanghui/pos/foundation/application/audit/AuditSanitizer.java",
        "server/ruoyi-modules/jshpos-service/src/main/java/com/jingshanghui/pos/service/config/ServiceAutoConfiguration.java",
        "server/ruoyi-modules/jshpos-service/src/test/java/com/jingshanghui/pos/service/config/ServiceAutoConfigurationTest.java",
        "docs/governance/CR-T2G8B-006_gate8b-closure.md",
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
    require('"applicationCode": "GATE8B_APP_001"' in runtime_script,
            "商户申请编码未遵守正式大写字母、数字与下划线契约")
    require('TENANT_ADMIN_ROLE_KEY = "admin"' in runtime_script,
            "正式旅程未遵守 RuoYi 租户管理员角色键契约")
    require('"roleKey": TENANT_ADMIN_ROLE_KEY' in runtime_script
            and 'find_by(tenant_role_rows, "roleKey", TENANT_ADMIN_ROLE_KEY' in runtime_script,
            "租户复核账号必须经正式角色查询 API 解析租户管理员角色")
    forbidden_runtime = ("pymysql", "mysql.connector", "redis.Redis", "sqlalchemy", "jdbc:", "testbackdoor")
    require(not any(marker in runtime_script for marker in forbidden_runtime), "旅程脚本存在数据库、Redis 或测试后门")

    privilege = (ROOT / required[8]).read_text(encoding="utf-8")
    require("DEFAULT_TENANT_ID.equals(tenantId)" in privilege, "平台角色未限定默认租户")
    require("platform_admin" in privilege, "平台职责分离角色缺失")
    workflow = (ROOT / ".github/workflows/t2-gate8b.yml").read_text(encoding="utf-8")
    base_schema = workflow.find("server/script/sql/ry_vue_5.X.sql")
    workflow_schema = workflow.find("server/script/sql/ry_workflow.sql")
    require(0 <= base_schema < workflow_schema, "正式空环境必须按顺序装配 RuoYi 基础与工作流 Schema")
    require("Gate 8B runtime log contains a controlled credential" in workflow,
            "正式运行日志未执行受控凭据泄漏门禁")
    require("schema-bootstrap.log" in workflow, "Schema 初始化失败未保留可审计证据")
    sensitive_constants = (ROOT / required[10]).read_text(encoding="utf-8")
    require('"bootstrapPassword"' in sensitive_constants, "SaaS 开户凭据未加入请求日志排除契约")
    workflow_schema_sql = (ROOT / required[12]).read_text(encoding="utf-8").lower()
    require("insert into sys_menu values" not in workflow_schema_sql,
            "工作流菜单种子禁止依赖 sys_menu 的隐式列顺序")
    require("insert into sys_menu (menu_id, menu_name" in workflow_schema_sql,
            "工作流菜单种子必须使用显式列清单")
    permission_source = (ROOT / required[13]).read_text(encoding="utf-8")
    require("SystemConstants.ALL_PERMISSION" in permission_source
            and "SystemConstants.DOMAIN_ALL_PERMISSION" in permission_source,
            "平台超管必须同时覆盖 RuoYi 三段权限与 Owner 两段权限")
    audit_sanitizer = (ROOT / required[15]).read_text(encoding="utf-8")
    require("objectMapper.copy()" in audit_sanitizer and "new ObjectMapper()" not in audit_sanitizer,
            "审计摘要器必须复用应用 Java Time 模块且不得修改全局 ObjectMapper")
    service_configuration = (ROOT / required[16]).read_text(encoding="utf-8")
    require("@AutoConfiguration" in service_configuration
            and '@ComponentScan("com.jingshanghui.pos.service")' in service_configuration,
            "Service Owner 必须注册为正式自动配置并扫描运行时组件")
    defect_ledger = (ROOT / "docs/t2-gate8b/03_P0P1缺陷账.md").read_text(encoding="utf-8")
    require("FIXED_PENDING_FULL_CI" not in defect_ledger and defect_ledger.count("FIXED_VERIFIED") == 12,
            "P0/P1 缺陷必须由完整候选 CI 验证关闭")
    acceptance_report = (ROOT / required[2]).read_text(encoding="utf-8")
    require("CONDITIONAL PASS / VERIFIED" in acceptance_report
            and "32670082176" in acceptance_report and "9501237609" in acceptance_report,
            "验收报告未回填完整候选 Run 与证据 Artifact")
    vectors = json.loads((ROOT / required[4]).read_text(encoding="utf-8"))["seeds"]
    require(len(vectors) >= 8 and len({v["id"] for v in vectors}) == len(vectors), "失败 seed 不完整")

    changed = set(filter(None, git("diff", "--name-only", BASE).splitlines())) | set(
        filter(None, git("ls-files", "--others", "--exclude-standard").splitlines()))
    allowed_runtime = {required[8], required[10], required[13], required[15], required[16]}
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
