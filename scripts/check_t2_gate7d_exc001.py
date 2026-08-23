#!/usr/bin/env python3
"""T2-EXC-001 数据主权、修复编排、租户边界和外部证据门禁。"""
from __future__ import annotations

import argparse
import csv
import json
import pathlib
import re

ROOT = pathlib.Path(__file__).resolve().parents[1]
GATE = "T2-GATE7D-SPRINT-S22A-EXC001"


def fail(condition: bool, message: str) -> None:
    if not condition:
        raise SystemExit(f"T2-GATE7D-EXC001 ERROR: {message}")


def read(path: str) -> str:
    return (ROOT / path).read_text(encoding="utf-8", errors="strict")


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--output")
    args = parser.parse_args()

    with (ROOT / "docs/governance/rtm.csv").open(encoding="utf-8", newline="") as handle:
        rows = {row["requirement_id"]: row for row in csv.DictReader(handle)}
    fail(rows["T2-CLS-001"]["status"] == "ACCEPTED", "CLS001 未按项目发起人确认接受")
    fail(rows["T2-EXC-001"]["status"] in {"IN_PROGRESS", "VERIFIED", "ACCEPTED"}, "EXC001 状态越界")
    for requirement in ("T2-MEM-003", "T2-SAA-001", "T2-SUB-001", "T2-SVC-001"):
        fail(rows[requirement]["status"] == "DRAFT", f"{requirement} 被提前准入")
    for requirement in ("T2-PAY-002", "T2-HWD-001", "T2-PRN-001", "T2-PAR-001"):
        fail(rows[requirement]["status"] == "BLOCKED", f"{requirement} 阻断状态漂移")
    for requirement in ("T2-JSH-001", "T2-LIC-001"):
        fail(rows[requirement]["status"] == "DEFERRED", f"{requirement} 延后状态漂移")

    admission = json.loads(read("contracts/t2/gate7d-exc001/exc001-admission.json"))
    fail(admission["status"] == rows["T2-EXC-001"]["status"], "RTM 与准入状态不一致")
    fail(admission["owner"] == "OPERATIONS", "异常案件数据主权漂移")
    expected_owners = {"SYNC", "DATA_PACKAGE", "PAYMENT_REFUND", "INVENTORY", "COSTING", "REPORTING", "DAILY_CLOSE"}
    fail(set(admission["sourceOwners"]) == expected_owners, "七类来源 Owner 不完整")
    expected_states = {"OPEN", "CLAIMED", "IN_PROGRESS", "WAITING_OWNER", "RESOLVED", "CLOSED", "REOPENED", "FAILED"}
    fail(set(admission["states"]) == expected_states, "异常状态集合漂移")
    fail(admission["dataAccess"]["sqlMode"] == "XML", "复杂 SQL 未冻结在 XML")
    external = admission["externalEvidence"]
    fail(all(value == 0 for value in external.values()), "出现外部执行证据")

    vectors = json.loads(read("contracts/t2/gate7d-exc001/exc001-fault-vectors.json"))["vectors"]
    fail(len(vectors) >= 32, "固定故障向量少于 32")
    vector_cases = {item["case"] for item in vectors}
    for case in ("concurrent-claim", "owner-ack-lost", "payment-unknown", "cross-tenant-list",
                 "source-hash-tamper", "million-open-cases", "external-provider-blocked"):
        fail(case in vector_cases, f"关键故障向量缺失: {case}")

    required = [
        "docs/adr/ADR-052-gate7d-unified-exception-center.md",
        "docs/t2-gate7d-exc001/01_T2_EXC001设计准入与验收冻结.md",
        "contracts/t2/gate7d-exc001/openapi-exception-center-v1.yaml",
        "contracts/t2/gate7d-exc001/exception-events-v1.schema.json",
        "server/ruoyi-modules/jshpos-foundation/src/main/java/com/jingshanghui/pos/foundation/application/port/OperationsExceptionOwnerPort.java",
        "server/ruoyi-modules/jshpos-operations/src/main/java/com/jingshanghui/pos/operations/application/service/ExceptionCenterService.java",
        "server/ruoyi-modules/jshpos-operations/src/main/java/com/jingshanghui/pos/operations/infrastructure/owner/DefaultExceptionOwnerGateway.java",
        "server/ruoyi-modules/jshpos-operations/src/main/resources/db/migration/V202608230073__gate7d_exception_center.sql",
        "server/ruoyi-modules/jshpos-operations/src/main/resources/db/migration/V202608230074__gate7d_exception_center_permissions.sql",
        "server/ruoyi-modules/jshpos-operations/src/main/resources/mapper/operations/ExceptionCenterMapper.xml",
        "server/ruoyi-modules/jshpos-operations/src/test/java/com/jingshanghui/pos/operations/migration/ExceptionCenterMySqlIT.java",
        "admin-web/src/views/operations/exception-center/index.vue",
        "admin-web/src/api/exception-center/contract.ts",
    ]
    for item in required:
        fail((ROOT / item).is_file(), f"缺少正式实现或证据文件: {item}")

    # rglob 使用完整文件名，避免 pathlib 在 POSIX 上把“**”与文件名拼成非法组件。
    adapters = list((ROOT / "server/ruoyi-modules").rglob("*ExceptionOwnerAdapter.java"))
    adapter_text = "\n".join(path.read_text(encoding="utf-8") for path in adapters)
    for owner in expected_owners:
        fail(f'return "{owner}"' in adapter_text, f"缺少 {owner} Owner 窄端口")

    module = ROOT / "server/ruoyi-modules/jshpos-operations/src/main"
    java = "\n".join(path.read_text(encoding="utf-8") for path in module.rglob("*.java"))
    fail(not re.search(r"@(select|insert|update|delete)\b", java, re.I), "Operations 出现 SQL 注解")
    for token in ("resttemplate", "webclient", "okhttp", "provider sdk"):
        fail(token not in java.lower(), f"发现 Provider 网络实现: {token}")
    service = read("server/ruoyi-modules/jshpos-operations/src/main/java/com/jingshanghui/pos/operations/application/service/ExceptionCenterService.java")
    for token in ("findCommand", "requestHash", "requireHolder", "latestApprovedReview", "WAITING_OWNER", "UNAVAILABLE",
                  "OUT_OF_ORDER", "SEQUENCE_CONFLICT"):
        fail(token in service, f"异常幂等、租约、复核或失败关闭缺失: {token}")
    fail("new OwnerRepairCommand" in service and "owners.repair" in service, "修复未通过 Owner 具名端口")

    mapper = read("server/ruoyi-modules/jshpos-operations/src/main/resources/mapper/operations/ExceptionCenterMapper.xml").lower()
    fail(mapper.count("tenant_id=#{tenantid}") == 17, "租户过滤读写点数量漂移")
    fail("for update" in mapper and "record_version=#{expectedversion}" in mapper, "租约并发控制缺失")
    fail("delete from ops_exception" not in mapper and "select *" not in mapper, "异常 Mapper 出现删除或宽查询")
    for prefix in ("syn_", "cat_", "pay_", "inv_", "cost_", "rpt_", "ord_"):
        fail(f"update {prefix}" not in mapper and f"insert into {prefix}" not in mapper,
             f"异常中心越权写入 {prefix} Owner")

    migration = read("server/ruoyi-modules/jshpos-operations/src/main/resources/db/migration/V202608230073__gate7d_exception_center.sql").lower()
    tables = {"ops_exception_case", "ops_exception_observation", "ops_exception_lease_event",
              "ops_exception_action_plan", "ops_exception_repair_command", "ops_exception_review",
              "ops_exception_state_event", "ops_exception_audit_event", "ops_exception_command", "ops_exception_outbox"}
    for table in tables:
        fail(f"create table {table}" in migration, f"缺少异常中心表: {table}")
    for token in ("append_only", "cannot be deleted", "uk_ops_exc_case_dedup", "uk_ops_exc_repair_key",
                  "out_of_order", "sequence_conflict"):
        fail(token in migration, f"只追加、不可删或幂等约束缺失: {token}")

    # Owner 修复必须在可信门店授权后，以 tenant + store + fact 三元组读取来源事实。
    owner_boundaries = (
        ("server/ruoyi-modules/jshpos-inventory/src/main/java/com/jingshanghui/pos/inventory/infrastructure/exception/InventoryExceptionOwnerAdapter.java",
         "server/ruoyi-modules/jshpos-inventory/src/main/resources/mapper/inventory/InventoryExceptionMapper.xml"),
        ("server/ruoyi-modules/jshpos-costing/src/main/java/com/jingshanghui/pos/costing/infrastructure/exception/CostingExceptionOwnerAdapter.java",
         "server/ruoyi-modules/jshpos-costing/src/main/resources/mapper/costing/CostingExceptionMapper.xml"),
    )
    for adapter_path, mapper_path in owner_boundaries:
        adapter = read(adapter_path)
        owner_mapper = re.sub(r"\s+", "", read(mapper_path)).lower()
        fail("authorization.requireStoreAccess(c.storeId())" in adapter,
             f"Owner 修复缺少可信门店授权: {adapter_path}")
        fail("store_id=#{storeid}" in owner_mapper,
             f"Owner 修复查询缺少门店过滤: {mapper_path}")

    web = read("admin-web/src/views/operations/exception-center/index.vue").lower()
    fail("tenantid" not in web and "fetch(" not in web and "math." not in web,
         "Vue 存在租户自报、API 绕过或前端领域重算")
    for token in ("blocked/unavailable", "认领", "转派", "独立复核", "审计时间线"):
        fail(token in web, f"异常工作台呈现缺失: {token}")

    result = {
        "gate": GATE,
        "status": "PASS",
        "requirementStatus": rows["T2-EXC-001"]["status"],
        "sourceOwnerCount": len(expected_owners),
        "faultVectorCount": len(vectors),
        "tableCount": len(tables),
        "externalExecution": external,
        "preservedStates": {key: rows[key]["status"] for key in
                            ("T2-MEM-003", "T2-PAY-002", "T2-HWD-001", "T2-PRN-001", "T2-PAR-001")},
    }
    if args.output:
        target = pathlib.Path(args.output)
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(result, ensure_ascii=False))


if __name__ == "__main__":
    main()
