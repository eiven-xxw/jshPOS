#!/usr/bin/env python3
"""T2-CLS-001 日结数据主权、状态、不变量、外部边界与证据门禁。"""
from __future__ import annotations

import argparse
import csv
import json
import pathlib
import re

ROOT = pathlib.Path(__file__).resolve().parents[1]


def fail(condition: bool, message: str) -> None:
    if not condition:
        raise SystemExit(f"T2-GATE7D-CLS001 ERROR: {message}")


def read(path: str) -> str:
    return (ROOT / path).read_text(encoding="utf-8", errors="strict")


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--output")
    args = parser.parse_args()

    with (ROOT / "docs/governance/rtm.csv").open(encoding="utf-8", newline="") as handle:
        rows = {row["requirement_id"]: row for row in csv.DictReader(handle)}
    fail(rows["T2-LOT-001"]["status"] == "ACCEPTED", "LOT001 未按发起人确认接受")
    fail(rows["T2-CLS-001"]["status"] in {"IN_PROGRESS", "VERIFIED"}, "CLS001 状态越界")
    fail(rows["T2-EXC-001"]["status"] == "DRAFT", "EXC001 被提前准入")
    for requirement in ("T2-PAY-002", "T2-HWD-001", "T2-PRN-001", "T2-PAR-001"):
        fail(rows[requirement]["status"] == "BLOCKED", f"{requirement} 阻断状态漂移")
    for requirement in ("T2-JSH-001", "T2-LIC-001"):
        fail(rows[requirement]["status"] == "DEFERRED", f"{requirement} 延后状态漂移")

    admission = json.loads(read("contracts/t2/gate7d-cls001/cls001-admission.json"))
    fail(admission["requirement"]["status"] == rows["T2-CLS-001"]["status"], "RTM/准入状态不一致")
    expected_states = {"DRAFT", "PREFLIGHTING", "PREFLIGHT_FAILED", "READY", "APPROVED",
                       "CLOSING", "CLOSED", "FAILED", "CORRECTION_REQUIRED", "COMPENSATION_REQUIRED"}
    fail(set(admission["states"]) == expected_states, "日结状态集合漂移")
    fail(admission["requiredReadOwners"] == ["FOUNDATION", "SHIFT_ORDER", "PAYMENT_REFUND", "SYNC", "REPORTING"],
         "权威 Owner 清单漂移")
    fail(admission["moneyInvariant"] == "grossMinor-discountMinor+surchargeMinor=receivableMinor",
         "金额守恒规则漂移")
    fail(all(admission["makerChecker"].values()), "职责分离未冻结")
    fail(admission["externalProviderReconciliation"] == "BLOCKED_UNAVAILABLE_NOT_GREEN",
         "外部渠道对账被错误置绿")
    external = admission["externalExecution"]
    fail(all(value == 0 for value in external.values() if isinstance(value, int)), "出现外部执行")
    fail(external.get("commercialClaimAllowed") is False, "商业声明边界漂移")

    vectors = json.loads(read("contracts/t2/gate7d-cls001/cls001-fault-vectors.json"))
    items = vectors.get("vectors", [])
    fail(len(items) >= 40, "固定故障向量少于 40")
    expected_vectors = {"NORMAL_CASH_CLOSE", "UNCLOSED_SHIFT", "MAKER_APPROVES", "SOURCE_CHANGED_BEFORE_SIGN",
                        "PAYMENT_UNKNOWN", "SYNC_DEAD_LETTER", "OWNER_TIMEOUT", "CROSS_TENANT_READ",
                        "DST_FALL_BACK", "LATE_SALE", "MILLION_FACT_TREND", "EXTERNAL_PROVIDER_BLOCKED"}
    fail(expected_vectors.issubset(set(items)), "关键故障向量缺失")

    required = [
        "docs/adr/ADR-051-gate7d-store-business-day-close.md",
        "docs/t2-gate7d-cls001/01_T2_CLS001设计准入与验收冻结.md",
        "contracts/t2/gate7d-cls001/openapi-daily-close-v1.yaml",
        "contracts/t2/gate7d-cls001/daily-close-events-v1.schema.json",
        "server/ruoyi-modules/jshpos-operations/src/main/resources/db/migration/V202608230071__gate7d_daily_close.sql",
        "server/ruoyi-modules/jshpos-operations/src/main/resources/db/migration/V202608230072__gate7d_daily_close_permissions.sql",
        "server/ruoyi-modules/jshpos-operations/src/main/java/com/jingshanghui/pos/operations/application/service/DailyCloseService.java",
        "server/ruoyi-modules/jshpos-operations/src/main/java/com/jingshanghui/pos/operations/infrastructure/owner/DefaultDailyCloseOwnerGateway.java",
        "server/ruoyi-modules/jshpos-operations/src/main/resources/mapper/operations/DailyCloseMapper.xml",
        "server/ruoyi-modules/jshpos-operations/src/test/java/com/jingshanghui/pos/operations/migration/DailyCloseMySqlIT.java",
        "admin-web/src/views/operations/daily-close/index.vue",
        "admin-web/src/api/daily-close/contract.ts",
    ]
    for item in required:
        fail((ROOT / item).is_file(), f"缺少正式实现或证据文件: {item}")

    module = ROOT / "server/ruoyi-modules/jshpos-operations/src/main"
    java = "\n".join(path.read_text(encoding="utf-8", errors="strict") for path in module.rglob("*.java"))
    fail(not re.search(r"@(select|insert|update|delete)\b", java, re.I), "Operations 出现 SQL 注解")
    fail(not re.search(r"\b(float|double)\b", java, re.I), "日结代码出现浮点数")
    for token in ("resttemplate", "webclient", "okhttp", "provider sdk"):
        fail(token not in java.lower(), f"发现外部网络实现: {token}")

    mapper = read("server/ruoyi-modules/jshpos-operations/src/main/resources/mapper/operations/DailyCloseMapper.xml").lower()
    fail("tenant_id=#{tenantid}" in mapper and "for update" in mapper, "可信租户或并发锁边界缺失")
    for prefix in ("ord_", "pay_", "inv_", "rpt_", "syn_"):
        fail(f"update {prefix}" not in mapper and f"insert into {prefix}" not in mapper,
             f"Operations Mapper 越权写入 {prefix} Owner")

    migration = read("server/ruoyi-modules/jshpos-operations/src/main/resources/db/migration/V202608230071__gate7d_daily_close.sql").lower()
    expected_tables = {"ops_daily_close", "ops_daily_close_snapshot", "ops_daily_close_checkpoint",
                       "ops_daily_close_preflight", "ops_daily_close_difference", "ops_daily_close_approval",
                       "ops_daily_close_signature", "ops_daily_close_command_result", "ops_daily_close_state_event",
                       "ops_daily_close_audit", "ops_daily_close_outbox"}
    for table in expected_tables:
        fail(f"create table {table}" in migration, f"缺少 Operations 表: {table}")
    for token in ("electronic_received_minor", "electronic_refunded_minor", "unknown_payment_count",
                  "unknown_refund_count", "gross_minor-discount_minor+surcharge_minor=receivable_minor",
                  "trg_ops_close_guard", "append-only"):
        fail(token in migration, f"MySQL 失败关闭约束缺失: {token}")

    owner_mappers = [
        "server/ruoyi-modules/jshpos-order/src/main/resources/mapper/order/OrderDailyCloseMapper.xml",
        "server/ruoyi-modules/jshpos-payment/src/main/resources/mapper/payment/PaymentDailyCloseMapper.xml",
        "server/ruoyi-modules/jshpos-sync/src/main/resources/mapper/sync/SyncDailyCloseMapper.xml",
        "server/ruoyi-modules/jshpos-reporting/src/main/resources/mapper/reporting/ReportingDailyCloseMapper.xml",
    ]
    for item in owner_mappers:
        xml = read(item).lower()
        fail("tenant_id=#{tenantid}" in xml and "store_id=#{storeid}" in xml,
             f"Owner 窄读端口缺少租户/门店约束: {item}")

    mysql_test = read("server/ruoyi-modules/jshpos-operations/src/test/java/com/jingshanghui/pos/operations/migration/DailyCloseMySqlIT.java")
    fail("millionSyntheticFactsUseExactIntegerAggregation" in mysql_test
         and "fact_count\")).isEqualTo(1_000_000L)" in mysql_test
         and "SYNTHETIC_NOT_SLA" in mysql_test,
         "百万级合成事实缺少实际 MySQL 执行、守恒断言或证据边界")

    service = read("server/ruoyi-modules/jshpos-operations/src/main/java/com/jingshanghui/pos/operations/application/service/DailyCloseService.java")
    fail("SOURCE_CHANGED_BEFORE_SIGNATURE" in service and "LATE_FACT_REQUIRES_CORRECTION" in service,
         "签署漂移或晚到事实更正路径缺失")
    fail("makerChecker" in service and "insertSignature" in service, "职责分离或只追加签署缺失")
    fail("Long trustedStoreId = DailyCloseRules.store(storeId)" in service
         and "authorization.requireStoreAccess(trustedStoreId)" in service,
         "日结列表未强制指定并校验门店数据范围")

    openapi = read("contracts/t2/gate7d-cls001/openapi-daily-close-v1.yaml")
    fail("operationId: listDailyCloses" in openapi
         and re.search(r"StoreId:\s*\{?\s*name:\s*storeId,\s*in:\s*query,\s*required:\s*true", openapi),
         "OpenAPI 未将门店范围冻结为必填参数")

    web = read("admin-web/src/views/operations/daily-close/index.vue").lower()
    fail("tenantid" not in web and "fetch(" not in web and "math." not in web,
         "Vue 存在租户自报、绕过正式 API 或前端金额计算")
    fail("blocked/unavailable" in web and "晚到" in web and "独立审批" in web,
         "日结工作台的阻断、审批或更正呈现不完整")

    result = {
        "gate": "T2-GATE7D-SPRINT-S22A-CLS001",
        "status": "PASS",
        "requirementStatus": rows["T2-CLS-001"]["status"],
        "faultVectorCount": len(items),
        "tableCount": len(expected_tables),
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
