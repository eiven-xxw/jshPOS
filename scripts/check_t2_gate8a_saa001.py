#!/usr/bin/env python3
"""T2-SAA-001 串行准入、Owner、租户、迁移、Secret 与外部零执行门禁。"""
from __future__ import annotations

import argparse
import csv
import json
import pathlib
import re
import subprocess


ROOT = pathlib.Path(__file__).resolve().parents[1]
BASELINE = "bcfcaa4621ea55c61bd1cd22fc355b5f74d8dae4"
BRANCH = "t2/gate8a-sprint24a-saa001-runtime"
GATE = "T2-GATE8A-SPRINT-S24A-SAA001"
CONTRACT = ROOT / "contracts/t2/gate8a-saa001"
PRESERVED = {
    "T2-SUB-001": "DRAFT", "T2-SVC-001": "DRAFT",
    "T2-PAY-002": "BLOCKED", "T2-HWD-001": "BLOCKED",
    "T2-PRN-001": "BLOCKED", "T2-PAR-001": "BLOCKED",
    "T2-UAT-001": "DRAFT", "T2-REL-001": "DRAFT",
    "T2-LIC-001": "DEFERRED", "T2-JSH-001": "DEFERRED",
}


def require(condition: bool, message: str) -> None:
    if not condition:
        raise SystemExit(f"T2-GATE8A-SAA001 ERROR: {message}")


def git(*args: str) -> str:
    result = subprocess.run(
        ["git", "-c", "core.quotepath=false", *args], cwd=ROOT, capture_output=True,
        text=True, encoding="utf-8", check=False,
    )
    require(result.returncode == 0, f"git {' '.join(args)} failed: {result.stderr.strip()}")
    return result.stdout.strip()


def read(path: str) -> str:
    return (ROOT / path).read_text(encoding="utf-8")


def load_json(name: str) -> dict:
    return json.loads((CONTRACT / name).read_text(encoding="utf-8"))


def rtm_rows() -> dict[str, dict[str, str]]:
    with (ROOT / "docs/governance/rtm.csv").open(encoding="utf-8-sig", newline="") as handle:
        return {row["requirement_id"]: row for row in csv.DictReader(handle)}


def validate_admission(rows: dict[str, dict[str, str]]) -> tuple[dict, str]:
    admission = load_json("saa001-admission.json")
    require(admission["gate"] == GATE, "Gate 身份漂移")
    require(admission["baselineCommit"] == BASELINE and admission["branch"] == BRANCH,
            "基线或分支漂移")
    require(admission["requirementId"] == "T2-SAA-001", "出现越界 Requirement")
    status = rows["T2-SAA-001"]["status"]
    require(status in {"IN_PROGRESS", "VERIFIED"}, f"T2-SAA-001 状态非法: {status}")
    require(admission["status"] in {"IN_PROGRESS", "VERIFIED"}, "准入契约状态非法")
    if status == "VERIFIED":
        require(admission["status"] == "VERIFIED", "RTM VERIFIED 但准入契约未同步")
    require(admission["preservedStates"] == PRESERVED, "保留状态契约漂移")
    for requirement, expected in PRESERVED.items():
        require(rows[requirement]["status"] == expected, f"{requirement} 状态漂移")
    require(all(value == 0 for value in admission["externalExecution"].values()), "外部执行必须为0")
    require(admission["serialOrder"] == [
        "MODULE_AND_OWNER_BOUNDARY", "APPLICATION_AND_TENANT_PROVISIONING",
        "ENTITLEMENT_AND_QUOTA", "INITIALIZATION_AND_LIFECYCLE",
        "PERSISTENCE_AUDIT_OUTBOX", "WEB_AND_FLUTTER_CONTRACT",
        "FAULT_VECTORS_AND_COMPLETE_CI"], "串行实现顺序漂移")
    return admission, status


def validate_contracts() -> dict[str, int]:
    events = load_json("saas-events-v1.schema.json")
    require(events["$schema"] == "https://json-schema.org/draft/2020-12/schema", "事件 Schema 版本错误")
    require(events["xContractStatus"] == "ACTIVE_INTERNAL", "事件契约状态错误")
    require(len(events["properties"]["eventType"]["enum"]) == 3, "事件类型不完整")
    errors = load_json("error-codes.json")["codes"]
    codes = [item["code"] for item in errors]
    require(len(codes) >= 20 and len(codes) == len(set(codes)), "错误码不足或重复")
    vectors = load_json("fixed-vectors.json")["vectors"]
    vector_ids = [item["id"] for item in vectors]
    require(len(vectors) >= 30 and len(vector_ids) == len(set(vector_ids)), "固定向量不足或重复")
    areas = {item["area"] for item in vectors}
    require({"APPLICATION", "PROVISION", "ENTITLEMENT", "QUOTA", "LIFECYCLE",
             "TENANT_ATTACK", "MIGRATION", "WEB", "FLUTTER", "EXTERNAL_BOUNDARY",
             "RECOVERY"} <= areas, "故障向量覆盖面不足")

    api = read("contracts/t2/gate8a-saa001/openapi-saas-v1.yaml")
    for token in ("openapi: 3.1.0", "version: 1.0.0-gate8a-saa001",
                  "x-server-allocated-tenant-id: true", "x-request-response-logging: disabled",
                  "/applications:", "/entitlement-decisions/{featureCode}/consume:"):
        require(token in api, f"OpenAPI 缺少 {token}")
    require("tenantId:" not in api and "tenant_id:" not in api, "OpenAPI 请求模型暴露 tenant_id")
    operation_ids = re.findall(r"^\s+operationId:\s*(\S+)", api, re.MULTILINE)
    require(len(operation_ids) >= 12 and len(operation_ids) == len(set(operation_ids)),
            "OpenAPI operationId 不足或重复")

    with (CONTRACT / "persistence-registry.csv").open(encoding="utf-8-sig", newline="") as handle:
        registry = list(csv.DictReader(handle))
    require(len(registry) == 14, "持久化登记数量错误")
    require(sum(row["sql_mode"] == "MP" for row in registry) == 1, "MyBatis-Plus 边界漂移")
    require(sum(row["sql_mode"] == "XML" for row in registry) >= 10, "复杂 SQL XML 边界不足")
    require({row["migration"] for row in registry if row["migration"].startswith("V")} == {"V81", "V82"},
            "迁移登记版本漂移")
    return {"events": 3, "errors": len(errors), "vectors": len(vectors),
            "openApiOperations": len(operation_ids), "persistenceObjects": len(registry)}


def validate_runtime() -> None:
    required = [
        "server/ruoyi-modules/jshpos-saas/pom.xml",
        "server/ruoyi-modules/jshpos-saas/src/main/java/com/jingshanghui/pos/saas/application/service/SaasApplicationService.java",
        "server/ruoyi-modules/jshpos-saas/src/main/java/com/jingshanghui/pos/saas/application/service/SaasEntitlementService.java",
        "server/ruoyi-modules/jshpos-saas/src/main/java/com/jingshanghui/pos/saas/domain/SaasStates.java",
        "server/ruoyi-modules/jshpos-saas/src/main/resources/mapper/saas/SaasPersistenceMapper.xml",
        "server/ruoyi-modules/jshpos-saas/src/main/resources/db/migration/V202608230081__gate8a_saas_onboarding_entitlement.sql",
        "server/ruoyi-modules/jshpos-saas/src/main/resources/db/migration/V202608230082__gate8a_saas_permissions.sql",
        "server/ruoyi-modules/jshpos-foundation/src/main/java/com/jingshanghui/pos/foundation/application/port/TenantProvisioningPort.java",
        "admin-web/src/views/saas/operations/index.vue",
        "pos-flutter/lib/features/session/domain/saas_restriction_notice.dart",
        "docs/adr/ADR-057-gate8a-saa-runtime.md",
    ]
    require(all((ROOT / item).is_file() for item in required), "运行时必要文件不完整")
    adr = read("docs/adr/ADR-057-gate8a-saa-runtime.md")
    require("状态：Accepted" in adr and BASELINE in adr, "运行时 ADR 未 Accepted 或基线错误")

    controller = read("server/ruoyi-modules/jshpos-saas/src/main/java/com/jingshanghui/pos/saas/interfaces/rest/SaasOperationsController.java")
    dto = read("server/ruoyi-modules/jshpos-saas/src/main/java/com/jingshanghui/pos/saas/interfaces/rest/dto/SaasRequests.java")
    service = read("server/ruoyi-modules/jshpos-saas/src/main/java/com/jingshanghui/pos/saas/application/service/SaasApplicationService.java")
    mapper = read("server/ruoyi-modules/jshpos-saas/src/main/java/com/jingshanghui/pos/saas/infrastructure/persistence/mapper/SaasPersistenceMapper.java")
    plan = read("server/ruoyi-modules/jshpos-saas/src/main/java/com/jingshanghui/pos/saas/infrastructure/persistence/mapper/SaasPlanMapper.java")
    require(not re.search(r"(?:String|Long)\s+tenantId\b", dto), "客户端 DTO 暴露 tenant_id")
    require("requirePlatformAdministrator" in service, "租户前平台开户未强制平台管理员")
    require("TenantProvisioningPort" in service and "org.dromara.system" not in service,
            "SaaS Application Service 绕过 Foundation 端口")
    require("isSaveRequestData=false" in controller and "isSaveResponseData=false" in controller,
            "一次性凭据请求响应日志未关闭")
    require("@InterceptorIgnore(tenantLine = \"true\", dataPermission = \"true\")" in mapper
            and "@InterceptorIgnore(tenantLine = \"true\", dataPermission = \"true\")" in plan,
            "平台范围 Mapper 边界未显式声明")
    require("BaseMapper<SaasPlanEntity>" in plan and "<mapper namespace=" in read(
        "server/ruoyi-modules/jshpos-saas/src/main/resources/mapper/saas/SaasPersistenceMapper.xml"),
        "MyBatis-Plus/XML 双边界不完整")

    migration = read("server/ruoyi-modules/jshpos-saas/src/main/resources/db/migration/V202608230081__gate8a_saas_onboarding_entitlement.sql")
    require(migration.count("CREATE TABLE saas_") == 12, "SaaS Owner 表数量错误")
    require(migration.count("COMMENT='") >= 12 and "COMMENT ''" not in migration, "中文表 COMMENT 不完整")
    require(migration.count("CREATE TRIGGER trg_saas_") >= 6, "只追加历史触发器不完整")
    require("FLOAT" not in migration.upper() and "DOUBLE" not in migration.upper(), "迁移中出现浮点数")
    require(not re.search(r"\b(DELETE|TRUNCATE)\s+(FROM\s+)?(ord_|pay_|inv_|cst_|sys_tenant)", migration, re.I),
            "迁移越权删除其他 Owner 事实")


def changed_files() -> list[str]:
    require(git("merge-base", "--is-ancestor", BASELINE, "HEAD") == "", "准备封存提交不是当前分支祖先")
    branch = git("branch", "--show-current")
    require(branch in {BRANCH, ""}, f"当前分支错误: {branch}")
    changed = set(filter(None, git("diff", "--name-only", BASELINE).splitlines()))
    changed.update(filter(None, git("ls-files", "--others", "--exclude-standard").splitlines()))
    changed = {item.replace("\\", "/") for item in changed}
    allowed_exact = {
        "AGENTS.md", "admin-web/.eslintrc-auto-import.json", "docs/adr/README.md",
        "docs/governance/change-log.md", "docs/governance/rtm.csv", "server/pom.xml",
        "server/ruoyi-admin/pom.xml", "server/ruoyi-modules/pom.xml",
        "server/ruoyi-modules/jshpos-foundation/pom.xml",
        ".github/workflows/t2-gate8a-saa001.yml",
        "scripts/check_t2_gate8a_saa001.py", "scripts/build_t2_gate8a_saa001_evidence.py",
    }
    prefixes = (
        "contracts/t2/gate8a-saa001/", "docs/t2-gate8a-saa001/", "docs/adr/ADR-057-",
        "server/ruoyi-modules/jshpos-saas/", "admin-web/src/api/saas/",
        "admin-web/src/views/saas/", "pos-flutter/test/gate8a/",
        "pos-flutter/lib/features/session/domain/saas_restriction_notice.dart",
        "server/ruoyi-modules/jshpos-foundation/src/main/java/com/jingshanghui/pos/foundation/",
        "server/ruoyi-modules/jshpos-foundation/src/test/java/com/jingshanghui/pos/foundation/",
        "server/ruoyi-admin/src/test/java/org/dromara/test/MemberBenefitMigrationMySqlIT.java",
    )
    illegal = sorted(item for item in changed if item not in allowed_exact and not item.startswith(prefixes))
    require(not illegal, f"出现越界文件: {illegal}")
    migrations = sorted(item for item in changed if "/db/migration/" in item)
    require(migrations == [
        "server/ruoyi-modules/jshpos-saas/src/main/resources/db/migration/V202608230081__gate8a_saas_onboarding_entitlement.sql",
        "server/ruoyi-modules/jshpos-saas/src/main/resources/db/migration/V202608230082__gate8a_saas_permissions.sql"],
        f"迁移文件越界: {migrations}")
    runtime_files = [ROOT / item for item in changed if item.startswith(("server/", "admin-web/", "pos-flutter/"))]
    forbidden = re.compile(r"(?i)(okhttp|retrofit|webclient|resttemplate|provider[_-]?url|merchant[_-]?secret|-----BEGIN [A-Z ]*PRIVATE KEY-----)")
    for path in runtime_files:
        if path.is_file():
            require(not forbidden.search(path.read_text(encoding="utf-8", errors="ignore")),
                    f"出现 Provider 网络或 Secret 模式: {path.relative_to(ROOT)}")
    return sorted(changed)


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--output", type=pathlib.Path)
    args = parser.parse_args()
    rows = rtm_rows()
    admission, status = validate_admission(rows)
    contract_counts = validate_contracts()
    validate_runtime()
    changed = changed_files()
    result = {
        "gate": GATE, "status": "PASS", "requirementStatus": status,
        "evidenceLevel": admission["evidenceLevel"], "baselineCommit": BASELINE,
        "branch": BRANCH, "preservedStates": PRESERVED,
        "externalExecution": admission["externalExecution"], "contractCounts": contract_counts,
        "changedFiles": len(changed),
        "decision": "SAA001_VERIFIED_AWAITING_SPONSOR_ACCEPTANCE" if status == "VERIFIED"
                    else "SAA001_RUNTIME_CANDIDATE_IN_PROGRESS",
    }
    if args.output:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(result, ensure_ascii=False))


if __name__ == "__main__":
    main()
