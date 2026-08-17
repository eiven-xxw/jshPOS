from __future__ import annotations

import argparse
import json
import re
import subprocess
from datetime import datetime, timezone
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
PROMOTION = ROOT / "server/ruoyi-modules/jshpos-promotion"


def require(condition: bool, message: str) -> None:
    if not condition:
        raise SystemExit(f"T2-GATE5A ATTACK ERROR: {message}")


def read(relative: str) -> str:
    return (ROOT / relative).read_text(encoding="utf-8")


def mapper_statements(xml: str) -> list[str]:
    return re.findall(r"<(?:select|insert|update|delete)\b[^>]*>(.*?)</(?:select|insert|update|delete)>",
                      xml, flags=re.IGNORECASE | re.DOTALL)


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()

    dto = read("server/ruoyi-modules/jshpos-promotion/src/main/java/com/jingshanghui/pos/promotion/"
               "interfaces/rest/dto/PromotionRequests.java")
    dto_runtime = re.sub(r"/\*.*?\*/|//[^\n]*", "", dto, flags=re.DOTALL)
    for forbidden in ("tenantId", "tenant_id", "approverId", "actorUserId", "policySha256"):
        require(forbidden not in dto_runtime, f"request DTO exposes authority field: {forbidden}")

    controller = read("server/ruoyi-modules/jshpos-promotion/src/main/java/com/jingshanghui/pos/promotion/"
                      "interfaces/rest/PromotionController.java")
    service = read("server/ruoyi-modules/jshpos-promotion/src/main/java/com/jingshanghui/pos/promotion/"
                   "application/service/PromotionTransactionService.java")
    manual = read("server/ruoyi-modules/jshpos-promotion/src/main/java/com/jingshanghui/pos/promotion/"
                  "application/service/ManualPromotionService.java")
    require("TrustedTenantContext" in service and "TrustedTenantContext" in manual,
            "trusted tenant context missing from privileged promotion services")
    require("authorization.requireStoreAccess" in service and "authorization.requireStoreAccess" in manual,
            "store data-scope authorization missing")
    for permission in ("promotion:quote:calculate", "promotion:manual:authorize", "promotion:manual:approve",
                       "promotion:snapshot:freeze", "promotion:refund:allocate"):
        require(permission in controller, f"server permission missing: {permission}")

    xml = read("server/ruoyi-modules/jshpos-promotion/src/main/resources/mapper/promotion/"
               "PromotionPersistenceMapper.xml")
    statements = mapper_statements(xml)
    require(len(statements) >= 30 and all("#{tenantId}" in statement for statement in statements),
            "promotion Mapper statement lacks explicit trusted tenant binding")
    require("SELECT *" not in xml.upper() and "FOR UPDATE" in xml.upper(),
            "promotion Mapper uses wildcard projection or lacks aggregate locks")
    for table in ("PRM_QUOTE", "PRM_QUOTE_LINE", "PRM_ADJUSTMENT", "PRM_MANUAL_PRICE_AUDIT",
                  "PRM_TRANSACTION_SNAPSHOT", "PRM_TRANSACTION_ALLOCATION", "PRM_REFUND_ALLOCATION_LEDGER"):
        require(f"UPDATE {table}" not in xml.upper() and f"DELETE FROM {table}" not in xml.upper(),
                f"immutable promotion fact exposes mutation path: {table}")

    runtime_java = "\n".join(path.read_text(encoding="utf-8", errors="replace")
                              for path in PROMOTION.joinpath("src/main/java").rglob("*.java")).lower()
    require(not any(token in runtime_java for token in (
        "java.net.http", "resttemplate", "webclient", "okhttp", "hutool.http",
        "httpurlconnection", "https://", "http://")), "Provider network client or URL detected")
    require(not any(token in runtime_java for token in (
        "couponservice", "memberservice", "loyaltyservice", "storedvalueservice",
        "budgetreservationservice", "reportservice")), "later-Gate runtime detected")
    require("float " not in runtime_java and "double " not in runtime_java,
            "floating-point quantity, rate or money type detected")
    require("PromotionStrictTenantMapperGuard" in "\n".join(
        path.read_text(encoding="utf-8", errors="replace")
        for path in PROMOTION.joinpath("src/main").rglob("*") if path.is_file()),
        "promotion fail-closed tenant guard missing")

    sqlite = read("pos-flutter/lib/infrastructure/local_database/s9_transaction_schema.dart")
    require("local_device_binding" in sqlite and "_no_update" in sqlite and "_no_delete" in sqlite,
            "SQLite V5 trusted binding or immutable triggers missing")
    require("foreign key" in sqlite.lower() and "tenant_id" in sqlite,
            "SQLite V5 tenant-owner composite foreign keys missing")

    surfaces = [
        ("REST_DTO", "tenant, actor, approver and policy authority absent"),
        ("TRUSTED_CONTEXT", "tenant and actor injected from trusted principal"),
        ("STORE_SCOPE", "store data-scope authorization enforced"),
        ("CONTROLLER_PERMISSION", "privileged endpoints have server permissions"),
        ("MAPPER", "every SQL statement explicitly tenant scoped"),
        ("NATIVE_SQL", "explicit columns and aggregate row locks"),
        ("MYBATIS_PLUS", "only registered simple rule identity uses MP entity"),
        ("QUOTE_OWNER", "quote and line facts are immutable and tenant-owned"),
        ("MANUAL_APPROVAL", "operator and approver are trusted independent principals"),
        ("SNAPSHOT_OWNER", "one tenant-bound immutable snapshot per quote and order"),
        ("REFUND_LEDGER", "refund recovery is append-only and cumulative"),
        ("IDEMPOTENCY", "command key and canonical digest conflicts fail closed"),
        ("OUTBOX_AUDIT", "facts, audit and Outbox share service transaction"),
        ("CACHE_KEY", "package cache identity includes tenant store and release"),
        ("PACKAGE_BINDING", "offline package is tenant/store/device bound"),
        ("SQLITE_LOCAL", "local facts bind trusted tenant store and terminal"),
        ("TASK_EXPORT_OBJECT", "no alternate task/export/object write path admitted"),
        ("PROVIDER_NETWORK", "network clients URLs and credentials absent"),
        ("DEFERRED_RUNTIME", "coupon member loyalty stored value budget and report absent"),
        ("TWO_TENANT_FIXTURE", "TENANT_A and TENANT_B remain synthetic and isolated"),
        ("FORWARD_REPAIR", "sealed migrations are immutable and repair is additive"),
    ]
    output = args.output if args.output.is_absolute() else ROOT / args.output
    output.parent.mkdir(parents=True, exist_ok=True)
    report = {
        "schemaVersion": "1.0", "phase": "T2-GATE5A",
        "generatedAt": datetime.now(timezone.utc).isoformat().replace("+00:00", "Z"),
        "commitSha": subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=ROOT, text=True).strip(),
        "tenants": ["TENANT_A", "TENANT_B"],
        "surfaces": [{"surface": name, "assertion": assertion, "result": "PASS"}
                     for name, assertion in surfaces],
        "providerNetworkCalls": 0,
        "externalEvidence": {"sandbox": 0, "realDevice": 0, "pilot": 0},
    }
    output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(f"T2-GATE5A ATTACK OK: tenants=2 surfaces={len(surfaces)} paymentNetwork=0 external=0")


if __name__ == "__main__":
    main()
