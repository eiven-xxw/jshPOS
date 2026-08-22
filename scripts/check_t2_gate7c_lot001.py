#!/usr/bin/env python3
"""T2-LOT-001 行业开关、不可变批次、FEFO、离线包与证据边界门禁。"""
from __future__ import annotations

import argparse
import csv
import json
import pathlib
import re

ROOT = pathlib.Path(__file__).resolve().parents[1]


def fail(condition: bool, message: str) -> None:
    if not condition:
        raise SystemExit(f"T2-GATE7C-LOT001 ERROR: {message}")


def read(path: str) -> str:
    return (ROOT / path).read_text(encoding="utf-8", errors="strict")


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--output")
    args = parser.parse_args()

    with (ROOT / "docs/governance/rtm.csv").open(encoding="utf-8", newline="") as handle:
        rows = {row["requirement_id"]: row for row in csv.DictReader(handle)}
    fail(rows["T2-ONB-001"]["status"] == "ACCEPTED", "ONB001 未按项目发起人确认接受")
    fail(rows["T2-LOT-001"]["status"] in {"IN_PROGRESS", "VERIFIED"}, "LOT001 未准入或状态越界")
    for requirement in ("T2-CLS-001", "T2-EXC-001"):
        fail(rows[requirement]["status"] == "DRAFT", f"{requirement} 被提前准入")
    for requirement in ("T2-PAY-002", "T2-HWD-001", "T2-PRN-001", "T2-PAR-001"):
        fail(rows[requirement]["status"] == "BLOCKED", f"{requirement} 阻断状态漂移")
    for requirement in ("T2-JSH-001", "T2-LIC-001"):
        fail(rows[requirement]["status"] == "DEFERRED", f"{requirement} 延后状态漂移")

    admission = json.loads(read("contracts/t2/gate7c-lot001/lot001-admission.json"))
    fail(admission["requirement"]["status"] == rows["T2-LOT-001"]["status"], "RTM/准入状态不一致")
    fail(admission["industryPolicy"] == {
        "enabled": ["COMMUNITY_SUPERMARKET"],
        "disabled": ["CONVENIENCE", "SNACK_DISCOUNT"],
    }, "行业能力边界漂移")
    fail(admission.get("industryTransitionPolicy") == "BLOCK_NON_COMMUNITY_SWITCH_AFTER_ANY_LOT_FACT",
         "存在批次事实后的行业切换守卫缺失")
    fail(admission["fefoOrder"] == ["expiry_date ASC", "received_date ASC", "lot_id ASC"],
         "FEFO 稳定顺序漂移")
    fail(set(admission.get("frozenLotPolicyFields", [])) ==
         {"policyVersionId", "nearExpiryDays", "expiryDate"}, "批次策略冻结字段不完整")
    fail(admission["limits"] == {
        "maxCommandLines": 500, "maxAllocationsPerLine": 100,
        "maxPackageLots": 100000, "quantityScale": 6,
    }, "容量或精度边界漂移")
    persistence = {item["table"]: item for item in admission["persistence"]}
    expected_tables = {
        "cat_lot_policy_version", "inv_lot_identity", "inv_lot_command", "inv_lot_ledger",
        "inv_lot_balance", "inv_lot_allocation", "inv_lot_expiry_projection",
        "inv_lot_audit_event", "inv_lot_outbox", "inv_lot_package_release",
    }
    fail(set(persistence) == expected_tables, "正式表访问策略登记不完整")
    fail(all(item.get("sqlMode") == "XML" for item in persistence.values()), "正式 SQL 未全部使用 XML")
    fail(persistence["inv_lot_ledger"]["accessPolicy"] == "APPEND_ONLY" and
         persistence["inv_lot_package_release"]["accessPolicy"] == "APPEND_ONLY",
         "批次或数据包不可变策略漂移")
    external = admission["externalExecution"]
    fail(all(value == 0 for value in external.values() if isinstance(value, int)), "出现外部执行")
    fail(external.get("commercialClaimAllowed") is False, "商业声明边界漂移")

    vectors = json.loads(read("contracts/t2/gate7c-lot001/lot001-fault-vectors.json"))
    vector_items = vectors.get("vectors", [])
    fail(len(vector_items) >= 50, "固定故障向量少于 50")
    expected = {"LOT-007", "LOT-011", "LOT-017", "LOT-018", "LOT-025", "LOT-031",
                "LOT-034", "LOT-039", "LOT-041", "LOT-043", "LOT-046", "LOT-050"}
    fail(expected.issubset({item.get("id") for item in vector_items}), "关键故障向量缺失")

    required = [
        "contracts/t2/gate7c-lot001/openapi-lot-expiry-v1.yaml",
        "contracts/t2/gate7c-lot001/lot-expiry-events-v1.schema.json",
        "server/ruoyi-modules/jshpos-catalog/src/main/resources/db/migration/V202608230068__gate7c_lot_policy.sql",
        "server/ruoyi-modules/jshpos-inventory/src/main/resources/db/migration/V202608230069__gate7c_lot_inventory.sql",
        "server/ruoyi-modules/jshpos-inventory/src/main/resources/db/migration/V202608230070__gate7c_lot_permissions.sql",
        "server/ruoyi-modules/jshpos-catalog/src/main/java/com/jingshanghui/pos/catalog/domain/LotExpiryRules.java",
        "server/ruoyi-modules/jshpos-inventory/src/main/java/com/jingshanghui/pos/inventory/domain/LotInventoryRules.java",
        "server/ruoyi-modules/jshpos-inventory/src/main/java/com/jingshanghui/pos/inventory/application/service/LotInventoryService.java",
        "server/ruoyi-modules/jshpos-inventory/src/main/java/com/jingshanghui/pos/inventory/application/service/LotDataPackageService.java",
        "server/ruoyi-modules/jshpos-foundation/src/main/java/com/jingshanghui/pos/foundation/application/port/StoreIndustryTransitionGuardPort.java",
        "server/ruoyi-modules/jshpos-inventory/src/main/java/com/jingshanghui/pos/inventory/application/service/LotIndustryTransitionGuardService.java",
        "server/ruoyi-modules/jshpos-inventory/src/main/resources/mapper/inventory/LotInventoryMapper.xml",
        "server/ruoyi-modules/jshpos-inventory/src/test/java/com/jingshanghui/pos/inventory/migration/InventoryMigrationMySqlIT.java",
        "pos-flutter/lib/infrastructure/local_database/gate7c_lot_expiry_schema.dart",
        "pos-flutter/lib/features/catalog/infrastructure/lot_package_installer.dart",
        "pos-flutter/lib/features/checkout/application/lot_checkout_allocator.dart",
        "pos-flutter/test/gate7c/lot_expiry_runtime_test.dart",
        "admin-web/src/views/operations/lot-expiry/index.vue",
    ]
    for item in required:
        fail((ROOT / item).is_file(), f"缺少正式实现或证据文件: {item}")

    inventory_java = "\n".join(path.read_text(encoding="utf-8", errors="strict")
                               for path in (ROOT / "server/ruoyi-modules/jshpos-inventory/src/main").rglob("*.java"))
    catalog_java = "\n".join(path.read_text(encoding="utf-8", errors="strict")
                             for path in (ROOT / "server/ruoyi-modules/jshpos-catalog/src/main").rglob("*.java"))
    lot_java = "\n".join(line for line in (inventory_java + "\n" + catalog_java).splitlines()
                         if "Lot" in line or "lot" in line)
    fail(not re.search(r"@(select|insert|update|delete)\b", lot_java, re.I), "LOT001 新增 SQL 注解")
    fail(not re.search(r"\b(float|double)\b", lot_java, re.I), "批次代码出现浮点数")
    for token in ("resttemplate", "webclient", "okhttp", "provider sdk", "payment provider"):
        fail(token not in lot_java.lower(), f"发现外部网络或 Provider 实现: {token}")

    sql = read("server/ruoyi-modules/jshpos-inventory/src/main/resources/db/migration/V202608230069__gate7c_lot_inventory.sql").lower()
    for token in ("decimal(19,6)", "near_expiry_days", "uk_inv_lot_ledger_sequence",
                  "trg_inv_lot_identity_no_update", "trg_inv_lot_ledger_no_update",
                  "trg_inv_lot_allocation_no_update", "trg_inv_lot_package_no_update"):
        fail(token in sql, f"MySQL 批次失败关闭约束缺失: {token}")
    mapper = read("server/ruoyi-modules/jshpos-inventory/src/main/resources/mapper/inventory/LotInventoryMapper.xml").lower()
    fail("tenant_id=#{tenantid}" in mapper and "for update" in mapper, "可信租户或并发锁边界缺失")
    fail("order by i.expiry_date asc,i.received_date asc,i.lot_id asc" in mapper, "服务端 FEFO 顺序漂移")
    fail("update inv_stock_balance" not in mapper and "update inv_stock_ledger" not in mapper,
         "批次 Mapper 越权修改仓级库存事实")

    dart = read("pos-flutter/lib/features/checkout/application/lot_checkout_allocator.dart").lower()
    schema = read("pos-flutter/lib/infrastructure/local_database/gate7c_lot_expiry_schema.dart").lower()
    installer = read("pos-flutter/lib/features/catalog/infrastructure/lot_package_installer.dart").lower()
    fail("order by expiry_date,received_date,lot_id" in dart and "float" not in dart and "double" not in dart,
         "POS FEFO 或精度边界漂移")
    fail("local_order_lot_snapshot" in dart and "inventory.lot-sale.requested.v1" in
         read("pos-flutter/lib/features/checkout/application/checkout_local_service.dart"),
         "POS 原子批次快照或 Outbox 缺失")
    for token in ("local_lot_package_identity_no_update", "local_lot_binding_transition",
                  "local_lot_balance_identity_no_update", "local_lot_ledger_no_update"):
        fail(token in schema, f"SQLite 不可变/单调约束缺失: {token}")
    fail("ed25519" in installer and "digest mismatch" in installer and "binding is corrupted" in installer
         and "timezone binding mismatch" in installer,
         "数据包签名、摘要或绑定失败关闭缺失")

    web = read("admin-web/src/views/operations/lot-expiry/index.vue").lower()
    fail("tenantid" not in web and "fetch(" not in web and "math." not in web,
         "Vue 存在租户自报、绕过正式 API 或前端领域计算")
    fail("社区超市" in web and "临期" in web and "policyversionid" in web,
         "批次策略与预警运营界面不完整")

    result = {
        "gate": "T2-GATE7C-SPRINT-S21F-LOT001",
        "status": "PASS",
        "requirementStatus": rows["T2-LOT-001"]["status"],
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
