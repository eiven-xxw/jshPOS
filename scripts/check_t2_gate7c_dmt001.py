#!/usr/bin/env python3
"""T2-DMT-001 准入、Owner 边界、文件安全和外部证据边界门禁。"""
from __future__ import annotations

import argparse
import csv
import json
import pathlib
import re

ROOT = pathlib.Path(__file__).resolve().parents[1]


def fail(condition: bool, message: str) -> None:
    if not condition:
        raise SystemExit(f"T2-GATE7C-DMT001 ERROR: {message}")


def read(path: str) -> str:
    return (ROOT / path).read_text(encoding="utf-8", errors="strict")


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--output")
    args = parser.parse_args()
    with (ROOT / "docs/governance/rtm.csv").open(encoding="utf-8", newline="") as handle:
        rows = {row["requirement_id"]: row for row in csv.DictReader(handle)}
    fail(rows["T2-RPL-001"]["status"] == "ACCEPTED", "RPL001 未按发起人确认接受")
    fail(rows["T2-DMT-001"]["status"] in {"IN_PROGRESS", "VERIFIED"}, "DMT001 未准入或状态越界")
    for requirement in ("T2-ONB-001", "T2-LOT-001"):
        fail(rows[requirement]["status"] == "DRAFT", f"{requirement} 被提前准入")
    for requirement in ("T2-PAY-002", "T2-HWD-001", "T2-PRN-001", "T2-PAR-001"):
        fail(rows[requirement]["status"] == "BLOCKED", f"{requirement} 阻断状态漂移")
    for requirement in ("T2-JSH-001", "T2-LIC-001"):
        fail(rows[requirement]["status"] == "DEFERRED", f"{requirement} 延后状态漂移")

    admission = json.loads(read("contracts/t2/gate7c-dmt001/dmt001-admission.json"))
    fail(admission["requirement"]["status"] == rows["T2-DMT-001"]["status"], "RTM/准入状态不一致")
    fail(admission.get("dataTypes") == ["CATALOG", "SUPPLIER", "OPENING_INVENTORY", "MEMBER"], "资料类型越界")
    fail(admission.get("ownerOrder") == ["CATALOG", "PROCUREMENT", "MEMBER", "INVENTORY"], "Owner 顺序漂移")
    persistence = {item["table"]: item for item in admission.get("persistence", [])}
    expected_tables = {"cat_migration_product", "mig_batch", "mig_file", "mig_staging_row",
                       "mig_preflight_error", "mig_approval", "mig_owner_checkpoint",
                       "mig_reconciliation", "mig_state_event", "mig_audit_event", "mig_outbox"}
    fail(set(persistence) == expected_tables, "新增表访问策略登记不完整")
    fail(all(item.get("sqlMode") == "XML" for item in persistence.values()), "新增正式业务 SQL 未全部使用 XML")
    fail(all(item.get("accessPolicy") in {"CONTROLLED_WRITE", "APPEND_ONLY"}
             for item in persistence.values()), "新增表暴露了未批准的 CRUD 访问策略")
    security = admission.get("security", {})
    fail(security.get("rawFilePersisted") is False and security.get("stagingEncryption") == "AES-256-GCM"
         and security.get("tenantFromTrustedContextOnly") is True, "文件/租户/加密边界漂移")
    external = admission.get("externalExecution", {})
    fail(all(value == 0 for value in external.values() if isinstance(value, int)), "出现外部执行")
    fail(external.get("commercialClaimAllowed") is False, "商业声明边界漂移")
    multipart = read("server/ruoyi-admin/src/main/resources/application.yml")
    fail("max-file-size: 64MB" in multipart and "max-request-size: 66MB" in multipart,
         "Spring Multipart 上限与 64 MiB 迁移契约不一致")

    vectors = json.loads(read("contracts/t2/gate7c-dmt001/dmt001-fault-vectors.json"))
    fail(len(vectors.get("vectors", [])) >= 36, "固定故障向量少于 36")
    required = [
        "contracts/t2/gate7c-dmt001/openapi-business-migration-v1.yaml",
        "contracts/t2/gate7c-dmt001/business-migration-events-v1.schema.json",
        "server/ruoyi-modules/jshpos-migration/src/main/resources/db/migration/V202608220063__gate7c_catalog_business_migration_binding.sql",
        "server/ruoyi-modules/jshpos-migration/src/main/resources/db/migration/V202608220064__gate7c_business_data_migration.sql",
        "server/ruoyi-modules/jshpos-migration/src/main/resources/db/migration/V202608220065__gate7c_business_migration_permissions.sql",
        "server/ruoyi-modules/jshpos-migration/src/main/java/com/jingshanghui/pos/migration/application/service/BusinessMigrationService.java",
        "server/ruoyi-modules/jshpos-migration/src/main/java/com/jingshanghui/pos/migration/infrastructure/file/MigrationFileInspector.java",
        "server/ruoyi-modules/jshpos-migration/src/main/java/com/jingshanghui/pos/migration/infrastructure/security/AesGcmMigrationStagingCipher.java",
        "server/ruoyi-modules/jshpos-migration/src/main/resources/mapper/migration/BusinessMigrationMapper.xml",
        "server/ruoyi-modules/jshpos-catalog/src/main/resources/mapper/catalog/CatalogMigrationMapper.xml",
        "admin-web/src/views/operations/business-migration/index.vue",
        "server/ruoyi-modules/jshpos-migration/src/test/java/com/jingshanghui/pos/migration/domain/MigrationCapacityTrendTest.java",
        "server/ruoyi-modules/jshpos-migration/src/test/java/com/jingshanghui/pos/migration/interfaces/rest/BusinessMigrationControllerTest.java",
    ]
    for item in required:
        fail((ROOT / item).is_file(), f"缺少正式实现或证据文件: {item}")

    module = ROOT / "server/ruoyi-modules/jshpos-migration/src/main"
    java = "\n".join(path.read_text(encoding="utf-8", errors="strict") for path in module.rglob("*.java"))
    lower = java.lower()
    fail(not re.search(r"@(select|insert|update|delete)\b", java, re.I),
         "Migration 正式业务模块新增了 SQL 注解")
    fail("tenantid" not in read("contracts/t2/gate7c-dmt001/openapi-business-migration-v1.yaml").lower(),
         "OpenAPI 暴露 tenantId")
    for token in ("resttemplate", "webclient", "okhttp", "provider sdk", "payment provider"):
        fail(token not in lower, f"发现外部网络/支付实现: {token}")
    service = read("server/ruoyi-modules/jshpos-migration/src/main/java/com/jingshanghui/pos/migration/application/service/BusinessMigrationService.java")
    for port in ("BusinessMigrationCatalogPort", "BusinessMigrationSupplierPort",
                 "BusinessMigrationMemberPort", "BusinessMigrationInventoryPort"):
        fail(port in service, f"Owner 受控端口缺失: {port}")
    fail(not re.search(r"(?:insert|update|delete)\s+(?:cat_|sup_|pur_|inv_|mem_)", service, re.I),
         "Migration Service 疑似跨 Owner SQL")
    catalog_service = read("server/ruoyi-modules/jshpos-catalog/src/main/java/com/jingshanghui/pos/catalog/application/service/CatalogBusinessMigrationService.java")
    fail("infrastructure.persistence" not in catalog_service and "CatalogMigrationPersistencePort" in catalog_service,
         "Catalog 迁移应用服务越过端口依赖基础设施 Mapper")
    sql = read("server/ruoyi-modules/jshpos-migration/src/main/resources/db/migration/V202608220064__gate7c_business_data_migration.sql").lower()
    for token in ("fk_mig_stage_file", "fk_mig_error_file", "fk_mig_checkpoint_row",
                  "trg_mig_stage_guard", "trg_mig_error_no_update"):
        fail(token in sql, f"数据库失败关闭约束缺失: {token}")
    web = read("admin-web/src/views/operations/business-migration/index.vue").lower()
    fail("tenantid" not in web and "identityciphertext" not in web and "fetch(" not in web,
         "Vue 存在租户自报、会员密文或绕过正式 API")

    result = {
        "gate": "T2-GATE7C-SPRINT-S21D-DMT001",
        "status": "PASS",
        "requirementStatus": rows["T2-DMT-001"]["status"],
        "faultVectorCount": len(vectors["vectors"]),
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
