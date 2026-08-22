#!/usr/bin/env python3
"""T2-RPL-001 准入、Owner 边界、确定性规则和外部证据边界门禁。"""
from __future__ import annotations

import argparse
import csv
import json
import pathlib

ROOT = pathlib.Path(__file__).resolve().parents[1]


def fail(condition: bool, message: str) -> None:
    if not condition:
        raise SystemExit(f"T2-GATE7C-RPL001 ERROR: {message}")


def read(path: str) -> str:
    return (ROOT / path).read_text(encoding="utf-8", errors="strict")


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--output")
    args = parser.parse_args()
    with (ROOT / "docs/governance/rtm.csv").open(encoding="utf-8", newline="") as handle:
        rows = {row["requirement_id"]: row for row in csv.DictReader(handle)}
    fail(rows["T2-LBL-001"]["status"] == "ACCEPTED", "LBL001 未按项目发起人确认接受")
    fail(rows["T2-RPL-001"]["status"] in {"IN_PROGRESS", "VERIFIED"}, "RPL001 未准入或状态越界")
    for requirement in ("T2-DMT-001", "T2-ONB-001", "T2-LOT-001"):
        fail(rows[requirement]["status"] == "DRAFT", f"{requirement} 被提前准入")
    for requirement in ("T2-PAY-002", "T2-HWD-001", "T2-PRN-001", "T2-PAR-001"):
        fail(rows[requirement]["status"] == "BLOCKED", f"{requirement} 阻断状态漂移")

    admission = json.loads(read("contracts/t2/gate7c-rpl001/rpl001-admission.json"))
    fail(admission["requirement"]["status"] == rows["T2-RPL-001"]["status"], "RTM/准入状态不一致")
    fail(all(value == 0 for value in admission["externalExecution"].values() if isinstance(value, int)), "出现外部执行")
    fail(admission["externalExecution"].get("commercialClaimAllowed") is False, "商用声明边界漂移")
    calculation = admission.get("calculation", {})
    fail(calculation.get("quantityScale") == 6 and calculation.get("multipleRounding") == "CEILING"
         and calculation.get("predictionAllowed") is False
         and calculation.get("automaticOrderingAllowed") is False, "算法边界漂移")
    vectors = json.loads(read("contracts/t2/gate7c-rpl001/rpl001-fault-vectors.json"))
    fail(len(vectors.get("vectors", [])) >= 24, "故障向量少于 24")

    required = [
        "contracts/t2/gate7c-rpl001/openapi-replenishment-v1.yaml",
        "contracts/t2/gate7c-rpl001/replenishment-events-v1.schema.json",
        "server/ruoyi-modules/jshpos-procurement/src/main/resources/db/migration/V202608220062__gate7c_replenishment.sql",
        "server/ruoyi-modules/jshpos-procurement/src/main/java/com/jingshanghui/pos/procurement/domain/ReplenishmentRules.java",
        "server/ruoyi-modules/jshpos-procurement/src/main/java/com/jingshanghui/pos/procurement/application/service/ReplenishmentService.java",
        "server/ruoyi-modules/jshpos-inventory/src/main/java/com/jingshanghui/pos/inventory/application/port/ReplenishmentInventorySnapshotPort.java",
        "server/ruoyi-modules/jshpos-procurement/src/main/java/com/jingshanghui/pos/procurement/application/port/ReplenishmentPurchaseDraftPort.java",
        "server/ruoyi-modules/jshpos-procurement/src/main/resources/mapper/procurement/ReplenishmentMapper.xml",
        "admin-web/src/views/operations/components/ReplenishmentPanel.vue",
        "server/ruoyi-modules/jshpos-procurement/src/test/java/com/jingshanghui/pos/procurement/domain/ReplenishmentMillionTrendTest.java",
    ]
    for item in required:
        fail((ROOT / item).is_file(), f"缺少正式实现或证据文件: {item}")

    rpl_mapper = read("server/ruoyi-modules/jshpos-procurement/src/main/resources/mapper/procurement/ReplenishmentMapper.xml").lower()
    fail("tenant_id=#{tenantid}" in rpl_mapper and "for update" in rpl_mapper, "可信租户或并发锁边界缺失")
    for token in ("inv_stock_", "sup_supplier", "cat_sku", "insert into pur_purchase_order", "update pur_purchase_order"):
        fail(token not in rpl_mapper, f"Replenishment Mapper 跨 Owner: {token}")
    service = read("server/ruoyi-modules/jshpos-procurement/src/main/java/com/jingshanghui/pos/procurement/application/service/ReplenishmentService.java").lower()
    fail("replenishmentinventorysnapshotport" in service and "replenishmentpurchasedraftport" in service,
         "库存/采购正式端口装配缺失")
    for token in ("tensorflow", "onnx", "machinelearning", "autoapprove", "autoorder"):
        fail(token not in service, f"发现预测或自动下单实现: {token}")
    web = read("admin-web/src/views/operations/components/ReplenishmentPanel.vue").lower()
    fail("suggestedpurchasequantity" in web and "reasoncode" in web, "前端未展示服务端建议与解释")
    fail("math.ceil" not in web and "tenantid" not in web, "前端重复计算或租户自报")

    result = {
        "gate": "T2-GATE7C-SPRINT-S21C-RPL001",
        "status": "PASS",
        "requirementStatus": rows["T2-RPL-001"]["status"],
        "faultVectorCount": len(vectors["vectors"]),
        "calculation": calculation,
        "preservedStates": admission["preservedStates"],
        "externalExecution": admission["externalExecution"],
    }
    if args.output:
        target = pathlib.Path(args.output)
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(result, ensure_ascii=False))


if __name__ == "__main__":
    main()
