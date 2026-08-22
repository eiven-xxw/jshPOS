#!/usr/bin/env python3
"""T2-ONB-001 准入、白名单复制、Owner 边界和外部失败关闭门禁。"""
from __future__ import annotations

import argparse
import csv
import json
import pathlib
import re

ROOT = pathlib.Path(__file__).resolve().parents[1]


def fail(condition: bool, message: str) -> None:
    if not condition:
        raise SystemExit(f"T2-GATE7C-ONB001 ERROR: {message}")


def read(path: str) -> str:
    return (ROOT / path).read_text(encoding="utf-8", errors="strict")


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--output")
    args = parser.parse_args()

    with (ROOT / "docs/governance/rtm.csv").open(encoding="utf-8", newline="") as handle:
        rows = {row["requirement_id"]: row for row in csv.DictReader(handle)}
    fail(rows["T2-DMT-001"]["status"] == "ACCEPTED", "DMT001 未按发起人确认接受")
    fail(rows["T2-ONB-001"]["status"] in {"IN_PROGRESS", "VERIFIED"}, "ONB001 未准入或状态越界")
    fail(rows["T2-LOT-001"]["status"] == "DRAFT", "LOT001 被提前准入")
    for requirement in ("T2-PAY-002", "T2-HWD-001", "T2-PRN-001", "T2-PAR-001"):
        fail(rows[requirement]["status"] == "BLOCKED", f"{requirement} 阻断状态漂移")
    for requirement in ("T2-JSH-001", "T2-LIC-001"):
        fail(rows[requirement]["status"] == "DEFERRED", f"{requirement} 延后状态漂移")

    admission = json.loads(read("contracts/t2/gate7c-onb001/onb001-admission.json"))
    fail(admission["requirement"]["status"] == rows["T2-ONB-001"]["status"], "RTM/准入状态不一致")
    fail(admission.get("industries") == ["CONVENIENCE", "SNACK_DISCOUNT", "COMMUNITY_SUPERMARKET"],
         "三业态模板边界漂移")
    expected_whitelist = {"business.time", "business.day", "ui.layout", "device.expectation",
                          "industry.template", "catalog.scope", "pricing.scope", "permission.template",
                          "receipt.template", "approval.policy"}
    fail(set(admission.get("copyWhitelist", [])) == expected_whitelist, "可复制白名单漂移")
    forbidden = set(admission.get("forbiddenFacts", []))
    fail({"SECRET", "TERMINAL_CREDENTIAL", "MEMBER_IDENTITY", "ORDER", "PAYMENT", "REFUND",
          "INVENTORY_BALANCE", "COST", "POINTS", "AUDIT", "INBOX", "OUTBOX"}.issubset(forbidden),
         "禁止复制事实清单不完整")
    persistence = {item["table"]: item for item in admission.get("persistence", [])}
    expected_tables = {"onb_plan", "onb_config_snapshot", "onb_approval", "onb_step_checkpoint",
                       "onb_check_result", "onb_state_event", "onb_command_result",
                       "onb_audit_event", "onb_outbox"}
    fail(set(persistence) == expected_tables, "Onboarding 表访问策略登记不完整")
    fail(all(item.get("sqlMode") == "XML" for item in persistence.values()), "正式 SQL 未全部使用 XML")
    fail(all(item.get("accessPolicy") in {"CONTROLLED_WRITE", "APPEND_ONLY"}
             for item in persistence.values()), "出现未批准的通用 CRUD 策略")
    policy = admission.get("externalEvidencePolicy", {})
    fail(policy.get("blockedMustNotPass") is True and policy.get("syntheticPassDoesNotUnblock") is True
         and policy.get("commercialOpenedEvidenceAllowed") is False, "外部 P0 失败关闭策略漂移")
    external = admission.get("externalExecution", {})
    fail(all(value == 0 for value in external.values() if isinstance(value, int)), "出现外部执行")
    fail(external.get("commercialClaimAllowed") is False, "商业声明边界漂移")

    vectors = json.loads(read("contracts/t2/gate7c-onb001/onb001-fault-vectors.json"))
    vector_items = vectors.get("vectors", [])
    fail(len(vector_items) >= 46, "固定故障向量少于 46")
    fail(all(item.get("expected") != "OPENED_SYNTHETIC_ONLY" for item in vector_items),
         "合成证据错误解除外部开店阻断")

    required = [
        "contracts/t2/gate7c-onb001/openapi-store-onboarding-v1.yaml",
        "contracts/t2/gate7c-onb001/store-onboarding-events-v1.schema.json",
        "server/ruoyi-modules/jshpos-onboarding/src/main/resources/db/migration/V202608230066__gate7c_store_onboarding.sql",
        "server/ruoyi-modules/jshpos-onboarding/src/main/resources/db/migration/V202608230067__gate7c_store_onboarding_permissions.sql",
        "server/ruoyi-modules/jshpos-onboarding/src/main/java/com/jingshanghui/pos/onboarding/application/service/OnboardingService.java",
        "server/ruoyi-modules/jshpos-onboarding/src/main/java/com/jingshanghui/pos/onboarding/infrastructure/owner/FoundationOnboardingOwnerGateway.java",
        "server/ruoyi-modules/jshpos-onboarding/src/main/resources/mapper/onboarding/OnboardingMapper.xml",
        "server/ruoyi-modules/jshpos-onboarding/src/test/java/com/jingshanghui/pos/onboarding/migration/OnboardingMySqlIT.java",
        "server/ruoyi-modules/jshpos-foundation/src/main/java/com/jingshanghui/pos/foundation/application/port/StoreOnboardingPort.java",
        "server/ruoyi-modules/jshpos-catalog/src/main/java/com/jingshanghui/pos/catalog/application/port/StoreOnboardingCatalogPort.java",
        "server/ruoyi-modules/jshpos-inventory/src/main/java/com/jingshanghui/pos/inventory/application/port/StoreOnboardingInventoryPort.java",
        "server/ruoyi-modules/jshpos-order/src/main/java/com/jingshanghui/pos/order/application/port/StoreOnboardingShiftPort.java",
        "server/ruoyi-modules/jshpos-resilience/src/main/java/com/jingshanghui/pos/resilience/application/port/StoreOnboardingBackupPort.java",
        "admin-web/src/api/onboarding/contract.ts",
        "admin-web/src/views/operations/store-onboarding/index.vue",
        "admin-web/src/views/operations/__tests__/store-onboarding-view.spec.ts",
    ]
    for item in required:
        fail((ROOT / item).is_file(), f"缺少正式实现或证据文件: {item}")

    module = ROOT / "server/ruoyi-modules/jshpos-onboarding/src/main"
    java = "\n".join(path.read_text(encoding="utf-8", errors="strict") for path in module.rglob("*.java"))
    lower = java.lower()
    fail(not re.search(r"@(select|insert|update|delete)\b", java, re.I), "Onboarding 模块新增 SQL 注解")
    for token in ("resttemplate", "webclient", "okhttp", "provider sdk", "payment provider"):
        fail(token not in lower, f"发现外部网络或 Provider 实现: {token}")
    openapi = read("contracts/t2/gate7c-onb001/openapi-store-onboarding-v1.yaml").lower()
    fail("tenantid" not in openapi and "tenant_id" not in openapi, "OpenAPI 暴露客户端 tenant_id")
    gateway = read("server/ruoyi-modules/jshpos-onboarding/src/main/java/com/jingshanghui/pos/onboarding/infrastructure/owner/FoundationOnboardingOwnerGateway.java")
    for port in ("StoreOnboardingPort", "StoreOnboardingCatalogPort", "StoreOnboardingInventoryPort",
                 "StoreOnboardingShiftPort", "StoreOnboardingBackupPort"):
        fail(port in gateway, f"Owner 正式只读端口缺失: {port}")
    fail("CheckStatus.BLOCKED" in gateway and "OnboardingRules.EXTERNAL_CHECKS" in gateway,
         "外部 P0 未在正式网关失败关闭")
    fail(not re.search(r"(?:insert|update|delete)\s+(?:jsh_|cat_|ord_|inv_|pay_|mem_|mig_)", java, re.I),
         "Onboarding Java 疑似跨 Owner SQL")

    sql = read("server/ruoyi-modules/jshpos-onboarding/src/main/resources/db/migration/V202608230066__gate7c_store_onboarding.sql").lower()
    for token in ("fk_onb_plan_source", "fk_onb_plan_target", "fk_onb_plan_template",
                  "trg_onb_plan_guard", "trg_onb_snapshot_no_delete", "trg_onb_approval_no_delete",
                  "trg_onb_checkpoint_no_delete", "trg_onb_check_no_delete", "trg_onb_command_no_delete",
                  "trg_onb_state_no_delete", "trg_onb_audit_no_delete"):
        fail(token in sql, f"数据库失败关闭约束缺失: {token}")
    fail("insert into ord_" not in sql and "insert into inv_" not in sql and "insert into pay_" not in sql,
         "Onboarding 迁移写入其他 Owner 私表")
    permissions = read("server/ruoyi-modules/jshpos-onboarding/src/main/resources/db/migration/V202608230067__gate7c_store_onboarding_permissions.sql")
    for permission in ("create", "read", "preflight", "approve", "apply", "check", "open", "cancel"):
        fail(f"onboarding:plan:{permission}" in permissions, f"缺少最小权限: {permission}")
    web = read("admin-web/src/views/operations/store-onboarding/index.vue").lower()
    fail("tenantid" not in web and "fetch(" not in web and "math." not in web,
         "Vue 存在租户自报、绕过正式 API 或前端算法")
    fail("blocked/unavailable" in web and "ready_to_open" in web, "Vue 未明确展示外部阻断边界")

    result = {
        "gate": "T2-GATE7C-SPRINT-S21E-ONB001",
        "status": "PASS",
        "requirementStatus": rows["T2-ONB-001"]["status"],
        "faultVectorCount": len(vector_items),
        "limits": admission["limits"],
        "preservedStates": admission["preservedStates"],
        "externalExecution": external,
    }
    if args.output:
        target = pathlib.Path(args.output)
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(result, ensure_ascii=False))


if __name__ == "__main__":
    main()
