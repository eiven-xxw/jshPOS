#!/usr/bin/env python3
"""T2-MTN-001 五项可维护性整改、行为保持与外部零执行门禁。"""
from __future__ import annotations

import argparse
import csv
import json
import pathlib
import re
import subprocess

from audit_t2_gate6g_api import historical_openapi_files, openapi_operations
from audit_t2_gate6g_data import classify_flyway_file_name


ROOT = pathlib.Path(__file__).resolve().parents[1]
BASE = "eb2a8fec7c102da2db291c34822a01cced768c5d"
BRANCH = "t2/gate8c-sprint26b-mtn001-runtime"
GATE = "T2-GATE8C-SPRINT-S26B"
CONTRACT_DIR = ROOT / "contracts/t2/gate8c-mtn001"
FINDINGS = {f"G8C-MTN-P1-00{number}" for number in range(1, 6)}
PRESERVED = {
    "T2-PERF-002": "DRAFT", "T2-RDY-001": "DRAFT",
    "T2-PAY-002": "BLOCKED", "T2-HWD-001": "BLOCKED", "T2-PRN-001": "BLOCKED",
    "T2-PAR-001": "BLOCKED", "T2-UAT-001": "DRAFT", "T2-REL-001": "DRAFT",
    "T2-LIC-001": "DEFERRED", "T2-JSH-001": "DEFERRED",
}


def require(condition: bool, message: str) -> None:
    if not condition:
        raise SystemExit("T2-MTN-001 ERROR: " + message)


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
    require(git("merge-base", "--is-ancestor", BASE, "HEAD") == "", "SEC-002 封存提交不是祖先")
    require(git("branch", "--show-current") in {BRANCH, ""}, "当前分支不属于 T2-MTN-001")
    changed = changed_files()
    require(not [path for path in changed if "/db/migration/" in path], "禁止修改或新增数据库迁移")
    dependency_names = {"pom.xml", "package.json", "pnpm-lock.yaml", "pubspec.yaml", "pubspec.lock", "build.gradle.kts"}
    require(not [path for path in changed if pathlib.PurePosixPath(path).name in dependency_names], "禁止依赖漂移")
    allowed_exact = {
        "AGENTS.md", "docs/adr/README.md", "docs/governance/change-log.md", "docs/governance/rtm.csv",
        "docs/adr/ADR-064-gate8c-maintainability-hardening.md",
        "docs/governance/CR-T2G8C-008_sec002-accept-mtn001-runtime-admission.md",
        "docs/governance/CR-T2G8C-009_mtn001-verified-candidate.md",
        "docs/governance/CR-T2G8C-010_mtn001-ci-conditional-pass.md",
        ".github/workflows/t2-gate8c-mtn001.yml",
        "scripts/check_t2_gate8c_mtn001.py", "scripts/build_t2_gate8c_mtn001_evidence.py",
        "scripts/audit_t2_gate6g_api.py", "scripts/audit_t2_gate6g_data.py",
        "contracts/t2/gate7b-s20b/openapi-pos-second-batch-v1.yaml",
        "server/ruoyi-modules/jshpos-service/src/main/java/com/jingshanghui/pos/service/application/port/ServiceEntitlementReadPort.java",
        "server/ruoyi-modules/jshpos-service/src/main/java/com/jingshanghui/pos/service/application/service/ServiceApplicationService.java",
        "server/ruoyi-modules/jshpos-service/src/main/java/com/jingshanghui/pos/service/infrastructure/saas/SaasServiceEntitlementReadAdapter.java",
        "server/ruoyi-modules/jshpos-service/src/test/java/com/jingshanghui/pos/service/application/service/ServiceApplicationServiceTest.java",
        "server/ruoyi-modules/jshpos-service/src/test/java/com/jingshanghui/pos/service/infrastructure/saas/SaasServiceEntitlementReadAdapterTest.java",
        "server/ruoyi-modules/jshpos-foundation/src/main/java/com/jingshanghui/pos/foundation/infrastructure/persistence/mapper/ConfigTemplateVersionMapper.java",
        "server/ruoyi-modules/jshpos-foundation/src/main/resources/mapper/foundation/ConfigTemplateVersionMapper.xml",
        "server/ruoyi-modules/jshpos-foundation/src/test/java/com/jingshanghui/pos/foundation/infrastructure/persistence/mapper/ConfigTemplateVersionMapperSqlPolicyTest.java",
        "pos-flutter/lib/features/checkout/application/checkout_local_service.dart",
        "pos-flutter/lib/features/checkout/application/checkout_local_persistence.dart",
        "pos-flutter/lib/features/checkout/application/checkout_local_receipt_operations.dart",
        "pos-flutter/lib/features/checkout/application/checkout_local_settlement_operations.dart",
        "pos-flutter/lib/features/checkout/application/checkout_local_shift_operations.dart",
        "pos-flutter/lib/features/sale/presentation/pos_checkout_page.dart",
        "pos-flutter/lib/features/sale/presentation/pos_checkout_page_components.dart",
        "pos-flutter/lib/features/sale/presentation/pos_checkout_page_dialogs.dart",
    }
    prefixes = ("docs/t2-gate8c-mtn001/", "contracts/t2/gate8c-mtn001/")
    illegal = [path for path in changed if path not in allowed_exact and not path.startswith(prefixes)]
    require(not illegal, "存在越界变更: " + ", ".join(illegal))
    return changed


def validate_governance(stage: str) -> tuple[dict, dict]:
    admission = json.loads((CONTRACT_DIR / "mtn001-admission.json").read_text(encoding="utf-8"))
    closure = json.loads((CONTRACT_DIR / "findings-closure.json").read_text(encoding="utf-8"))
    rows = rtm()
    expected = "VERIFIED" if stage == "closure" else "IN_PROGRESS"
    require(admission["baseCommit"] == BASE and admission["branch"] == BRANCH, "准入基线漂移")
    require(rows["T2-SEC-002"]["status"] == "ACCEPTED", "T2-SEC-002 必须为 ACCEPTED")
    require(rows["T2-MTN-001"]["status"] == expected, f"T2-MTN-001 必须为 {expected}")
    for requirement, status in PRESERVED.items():
        require(rows[requirement]["status"] == status, requirement + " 状态漂移")
    require(set(closure["findings"]) == FINDINGS, "五项可维护性发现集合漂移")
    require(all(item["state"] == "CLOSED_INTERNAL_VERIFIED" for item in closure["findings"].values()), "发现未全部内部关闭")
    require(all(value == 0 for value in admission["externalExecution"].values()), "外部执行必须为零")
    accepted = sum(key.startswith("T2-") and row["status"] == "ACCEPTED" for key, row in rows.items())
    require(accepted == 84, f"T2 ACCEPTED 数量漂移: {accepted}")
    return admission, closure


def validate_owner_boundary() -> dict:
    application = read("server/ruoyi-modules/jshpos-service/src/main/java/com/jingshanghui/pos/service/application/service/ServiceApplicationService.java")
    port = read("server/ruoyi-modules/jshpos-service/src/main/java/com/jingshanghui/pos/service/application/port/ServiceEntitlementReadPort.java")
    adapter = read("server/ruoyi-modules/jshpos-service/src/main/java/com/jingshanghui/pos/service/infrastructure/saas/SaasServiceEntitlementReadAdapter.java")
    require("com.jingshanghui.pos.saas.application" not in application, "Service应用层仍直接依赖SaaS")
    require("ServiceEntitlementReadPort" in application and "record AccessDecision" in port, "Service只读权益端口不完整")
    require("implements ServiceEntitlementReadPort" in adapter and "SaasEntitlementService" in adapter, "SaaS防腐适配器缺失")
    return {"directSaasApplicationImports": 0, "readPort": "ServiceEntitlementReadPort"}


def validate_openapi() -> dict:
    operations, duplicate_ids, files = openapi_operations()
    historical, failures = historical_openapi_files()
    require(not duplicate_ids, "当前OpenAPI operationId重复")
    require(not failures, "历史OpenAPI替代引用无效")
    require(any(item["source"].endswith("gate7b-s20b/openapi-pos-second-batch-v1.yaml") for item in historical), "Gate7B历史草案标记缺失")
    return {"currentOperationCount": len(operations), "currentContractCount": len(files),
            "duplicateMethodPath": 0, "duplicateOperationId": 0, "historicalDraftCount": len(historical)}


def validate_flyway_audit() -> dict:
    callback = "beforeEachMigrate__repair_gate4c_gate7c_menu_ids.sql"
    require(classify_flyway_file_name(callback)["kind"] == "CALLBACK", "合法Flyway callback仍被误判")
    require(classify_flyway_file_name("V202608240086__gate8a_service_permissions.sql")["kind"] == "VERSIONED", "版本迁移分类失败")
    require(classify_flyway_file_name("R__refresh_projection.sql")["kind"] == "REPEATABLE", "可重复迁移分类失败")
    rejected = False
    try:
        classify_flyway_file_name("repair_gate.sql")
    except AssertionError:
        rejected = True
    require(rejected, "非法Flyway命名未失败关闭")
    return {"versioned": "PASS", "repeatable": "PASS", "callback": "PASS", "illegalName": "REJECTED"}


def validate_mapper() -> dict:
    interface = read("server/ruoyi-modules/jshpos-foundation/src/main/java/com/jingshanghui/pos/foundation/infrastructure/persistence/mapper/ConfigTemplateVersionMapper.java")
    xml = read("server/ruoyi-modules/jshpos-foundation/src/main/resources/mapper/foundation/ConfigTemplateVersionMapper.xml")
    require("@Select" not in interface and "SELECT *" not in interface, "Foundation接口仍含复杂注解SQL")
    require("resultMap id=\"configTemplateVersionEntityMap\"" in xml, "显式resultMap缺失")
    require("WHERE tenant_id = #{trustedTenantId}" in xml and "FOR UPDATE" in xml, "可信租户锁定条件缺失")
    require(not re.search(r"SELECT\s+\*", xml, re.IGNORECASE), "Mapper XML禁止星号投影")
    selected = re.search(r"SELECT(.+?)FROM jsh_config_template_version", xml, re.DOTALL)
    require(selected is not None and selected.group(1).count(",") >= 14, "锁定查询显式列不完整")
    return {"annotationSql": 0, "starProjection": 0, "explicitColumnCount": 15, "trustedTenant": True}


def validate_flutter_split() -> dict:
    service_main = ROOT / "pos-flutter/lib/features/checkout/application/checkout_local_service.dart"
    page_main = ROOT / "pos-flutter/lib/features/sale/presentation/pos_checkout_page.dart"
    checkout_parts = sorted(service_main.parent.glob("checkout_local_*operations.dart")) + [service_main.parent / "checkout_local_persistence.dart"]
    page_parts = sorted(page_main.parent.glob("pos_checkout_page_*.dart"))
    service_lines = len(service_main.read_text(encoding="utf-8").splitlines())
    page_lines = len(page_main.read_text(encoding="utf-8").splitlines())
    require(service_lines <= 800 and page_lines <= 800, "Flutter主文件仍超过800行")
    require(len(checkout_parts) == 4 and len(page_parts) == 2, "Flutter职责分部不完整")
    combined = "\n".join(path.read_text(encoding="utf-8") for path in [service_main, *checkout_parts])
    methods = ["openShift", "completeCashSale", "completePromotedCashSale", "requestReceiptReprint",
               "recordShiftCashMovement", "requestNoSaleDrawer", "approveShiftDifference", "closeShift"]
    require(all(re.search(rf"\b{method}\s*\(", combined) for method in methods), "Checkout公共操作丢失")
    require("package:flutter/services.dart" not in combined and not re.search(r"\bMethodChannel\s*\(", combined),
            "Checkout禁止直接访问MethodChannel")
    return {"serviceMainLines": service_lines, "pageMainLines": page_lines,
            "checkoutResponsibilityParts": len(checkout_parts), "pageResponsibilityParts": len(page_parts),
            "preservedPublicOperations": len(methods)}


def validate_documents() -> None:
    required = [
        "docs/adr/ADR-064-gate8c-maintainability-hardening.md",
        "docs/governance/CR-T2G8C-008_sec002-accept-mtn001-runtime-admission.md",
        "docs/governance/CR-T2G8C-009_mtn001-verified-candidate.md",
        "docs/t2-gate8c-mtn001/01_运行时影响分析与设计准入.md",
        "docs/t2-gate8c-mtn001/02_串行整改与验收矩阵.md",
        "docs/t2-gate8c-mtn001/03_T2_MTN001独立周门禁报告.md",
        "docs/t2-gate8c-mtn001/04_下一步操作指令.md",
        ".github/workflows/t2-gate8c-mtn001.yml",
    ]
    require(all((ROOT / path).is_file() for path in required), "T2-MTN-001交付物不完整")


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--stage", choices=("admission", "closure"), default="closure")
    parser.add_argument("--output", type=pathlib.Path)
    args = parser.parse_args()
    changed = validate_scope()
    admission, closure = validate_governance(args.stage)
    runtime = {
        "ownerBoundary": validate_owner_boundary(), "openapi": validate_openapi(),
        "flywayAudit": validate_flyway_audit(), "foundationMapper": validate_mapper(),
        "flutterSplit": validate_flutter_split(),
    }
    if args.stage == "closure":
        validate_documents()
    result = {
        "schemaVersion": "1.0", "gate": GATE, "status": "PASS",
        "requirementId": "T2-MTN-001", "requirementStatus": "VERIFIED" if args.stage == "closure" else "IN_PROGRESS",
        "evidenceCeiling": admission["evidenceCeiling"], "baselineCommit": BASE,
        "closedFindings": sorted(closure["findings"]), "runtime": runtime,
        "databaseMigrationsChanged": 0, "dependenciesChanged": 0, "newBusinessCapabilities": 0,
        "changedFiles": changed, "preservedStates": PRESERVED, "externalExecution": admission["externalExecution"],
        "decision": "CONDITIONAL_PASS_AWAITING_SPONSOR_ACCEPTANCE" if args.stage == "closure" else "ADMITTED_IN_PROGRESS",
    }
    if args.output:
        target = args.output if args.output.is_absolute() else ROOT / args.output
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({key: value for key, value in result.items() if key != "changedFiles"}, ensure_ascii=False))


if __name__ == "__main__":
    main()
