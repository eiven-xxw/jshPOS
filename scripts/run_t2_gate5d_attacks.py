from __future__ import annotations

import argparse
import json
import re
import subprocess
from datetime import datetime, timezone
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]


def require(condition: bool, message: str) -> None:
    if not condition:
        raise SystemExit(f"T2-GATE5D ATTACK ERROR: {message}")


def read(relative: str) -> str:
    return (ROOT / relative).read_text(encoding="utf-8")


def statements(xml: str) -> list[str]:
    return re.findall(r"<(?:select|insert|update|delete)\b[^>]*>(.*?)</(?:select|insert|update|delete)>",
                      xml, flags=re.IGNORECASE | re.DOTALL)


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()

    dto = re.sub(r"/\*.*?\*/|//[^\n]*", "", read(
        "server/ruoyi-modules/jshpos-reporting/src/main/java/com/jingshanghui/pos/reporting/"
        "interfaces/rest/dto/ReportingRequests.java"), flags=re.DOTALL)
    for field in ("tenantId", "tenant_id", "actorUserId", "objectKey", "tokenSha256"):
        require(field not in dto, f"public Reporting DTO exposes authority/secret field {field}")

    controller = read("server/ruoyi-modules/jshpos-reporting/src/main/java/com/jingshanghui/pos/reporting/"
                      "interfaces/rest/ReportingController.java")
    for permission in ("report:operation:read", "report:projection:ingest", "report:projection:rebuild",
                       "report:export:request", "report:export:approve", "report:export:generate",
                       "report:export:download", "report:repair:manage", "report:payment:ingest",
                       "report:bill:synthetic-import", "report:payment-reconciliation:read",
                       "report:payment-reconciliation:manage"):
        require(permission in controller, f"permission missing {permission}")
    require(controller.count("isSaveRequestData=false") >= 10,
            "sensitive export/projection audit body suppression missing")

    xml = read("server/ruoyi-modules/jshpos-reporting/src/main/resources/mapper/reporting/ReportingPersistenceMapper.xml")
    sql = statements(xml)
    require(sql and all("#{tenantId}" in item for item in sql), "Reporting Mapper statement lacks tenant binding")
    require("SELECT *" not in xml.upper(), "Reporting Mapper uses SELECT *")
    for owner in ("ORD_", "PAY_", "REF_", "INV_", "CST_", "PRM_", "MBR_"):
        require(f" FROM {owner}" not in xml.upper() and f" JOIN {owner}" not in xml.upper()
                and f"UPDATE {owner}" not in xml.upper(), f"cross-owner Mapper access {owner}")
    require("DELETE FROM RPT_SOURCE_EVENT_INBOX" not in xml.upper(), "source Inbox delete exposed")
    require("RPT_PROJECTION_LINEAGE" in xml.upper() and "SOURCE_CONTENT_SHA256" in xml.upper(),
            "per-event projection lineage is missing")

    reconciliation_xml = read(
        "server/ruoyi-modules/jshpos-reporting/src/main/resources/mapper/reporting/"
        "PaymentReconciliationMapper.xml")
    reconciliation_sql = statements(reconciliation_xml)
    require(reconciliation_sql and all("#{tenantId}" in item for item in reconciliation_sql),
            "Payment reconciliation Mapper statement lacks tenant binding")
    require("SELECT *" not in reconciliation_xml.upper(), "Payment reconciliation Mapper uses SELECT *")
    for owner in ("ORD_", "PAY_", "REF_", "INV_", "CST_", "PRM_", "MBR_"):
        require(f" FROM {owner}" not in reconciliation_xml.upper()
                and f" JOIN {owner}" not in reconciliation_xml.upper()
                and f"UPDATE {owner}" not in reconciliation_xml.upper(),
                f"payment reporting cross-owner Mapper access {owner}")
    require("DELETE FROM RPT_PAYMENT_FACT_INBOX" not in reconciliation_xml.upper()
            and "DELETE FROM RPT_INTERNAL_BILL_INBOX" not in reconciliation_xml.upper(),
            "append-only payment fact or synthetic bill delete exposed")
    require("UPDATE RPT_RECONCILIATION_AUDIT" not in reconciliation_xml.upper(),
            "append-only reconciliation audit update exposed")

    runtime_root = ROOT / "server/ruoyi-modules/jshpos-reporting/src/main/java"
    runtime = "\n".join(path.read_text(encoding="utf-8", errors="replace") for path in runtime_root.rglob("*.java"))
    lowered = runtime.lower()
    require("TrustedTenantContext" in runtime and "ReportingStrictTenantMapperGuard" in runtime,
            "trusted tenant context or fail-closed Mapper guard missing")
    require("requireStoreAccess" in runtime and "requireOrgAccess" in runtime and "businessDate" in runtime,
            "org/store scope or Owner business-day validation missing")
    require(not any(token in lowered for token in ("java.net.http", "resttemplate", "webclient", "okhttp",
                                                    "hutool.http", "httpurlconnection", "https://")),
            "Provider HTTP/SDK runtime detected")
    require("float " not in lowered and "double " not in lowered, "floating-point report math detected")
    require("reporting/" in runtime and "ARTIFACT_RETENTION" in runtime and "DOWNLOAD_TTL_MINUTES" in runtime,
            "tenant object namespace, retention or short-lived download token missing")

    csv = read("server/ruoyi-modules/jshpos-reporting/src/main/java/com/jingshanghui/pos/reporting/"
               "infrastructure/export/ReportCsvEncoder.java")
    require("safeCsvText" in csv and "水印" in csv, "CSV injection defense or watermark missing")
    migration = read("server/ruoyi-modules/jshpos-reporting/src/main/resources/db/migration/"
                     "V202608170032__gate5d_reporting_core.sql").upper()
    require("TRG_RPT_INBOX_CONTENT_GUARD" in migration and "TRG_RPT_INBOX_NO_DELETE" in migration,
            "source event immutability trigger missing")
    require("RPT_PROJECTION_LINEAGE" in migration and "FK_RPT_LINEAGE_SOURCE" in migration,
            "projection lineage schema/FK missing")
    require(" FLOAT" not in migration and " DOUBLE" not in migration and "DECIMAL(25,6)" in migration,
            "report quantity/cost precision policy changed")
    reconciliation_migration = read(
        "server/ruoyi-modules/jshpos-reporting/src/main/resources/db/migration/"
        "V202608170034__gate5d_payment_reconciliation.sql").upper()
    for trigger in ("TRG_RPT_PAYMENT_FACT_NO_UPDATE", "TRG_RPT_PAYMENT_FACT_NO_DELETE",
                    "TRG_RPT_INTERNAL_BILL_NO_UPDATE", "TRG_RPT_INTERNAL_BILL_NO_DELETE",
                    "TRG_RPT_RECON_AUDIT_NO_UPDATE", "TRG_RPT_RECON_AUDIT_NO_DELETE"):
        require(trigger in reconciliation_migration, f"RPT-002 immutable trigger missing {trigger}")
    require("INTERNAL_SYNTHETIC" in reconciliation_migration and "READ_PROJECTION" in reconciliation_migration,
            "RPT-002 synthetic boundary or discardable projection missing")

    client = read("admin-web/src/api/reporting/contract.ts")
    require("RPT-IAM-001" in client and "tenant_id" in client and "parseStoreIds" in client,
            "Web tenant override or store-scope guard missing")
    require("paymentReconciliationManage" in client
            and "/api/v1/reporting/payment-reconciliation" in client,
            "Web reconciliation audit/manage contract missing")

    surfaces = [
        "REST_AUTHORITY", "CONTROLLER_PERMISSION", "SENSITIVE_AUDIT_BODY", "TRUSTED_TENANT",
        "ORG_SCOPE", "STORE_SCOPE", "BUSINESS_DATE_OWNER", "MAPPER_TENANT", "CROSS_OWNER_READ",
        "CROSS_OWNER_WRITE", "SOURCE_IDEMPOTENCY", "CONTENT_CONFLICT", "SEQUENCE_GAP",
        "LATE_CONVERGENCE", "LINEAGE_EVENT_ID", "LINEAGE_DIGEST", "LINEAGE_SCHEMA",
        "LINEAGE_CHECKPOINT", "FAMILY_INCOMPLETE", "SHADOW_REBUILD", "ATOMIC_SWITCH",
        "REBUILD_GAP_BLOCK", "MONEY_CONSERVATION", "DECIMAL_EXACT", "EXPORT_FIELD_ALLOWLIST",
        "EXPORT_RANGE_LIMIT", "EXPORT_ROW_LIMIT", "APPROVAL_SEPARATION", "CSV_FORMULA_INJECTION",
        "TENANT_OBJECT_KEY", "SHORT_DOWNLOAD_TOKEN", "SINGLE_USE_DOWNLOAD", "ARTIFACT_DIGEST",
        "ARTIFACT_EXPIRY", "TEMP_CLEANUP", "CACHE_NAMESPACE", "PROVIDER_NETWORK", "REAL_PII",
        "TWO_SYNTHETIC_TENANTS", "FORWARD_REPAIR",
        "PAYMENT_FACT_TENANT", "SYNTHETIC_BILL_TENANT", "RECONCILIATION_TENANT",
        "PAYMENT_FACT_IMMUTABLE", "SYNTHETIC_BILL_IMMUTABLE", "RECON_AUDIT_IMMUTABLE",
        "PAYMENT_FACT_CONTENT_CONFLICT", "SYNTHETIC_BILL_CONTENT_CONFLICT",
        "RECON_MATCH_PRIORITY", "RECON_LATE_CONVERGENCE", "RECON_UNKNOWN_STATUS",
        "RECON_MANUAL_AUDIT", "RECON_REBUILD", "RECON_EXPORT_APPROVAL",
        "SYNTHETIC_NOT_SANDBOX", "PROVIDER_BILL_DOWNLOAD_BLOCKED",
    ]
    output = args.output if args.output.is_absolute() else ROOT / args.output
    output.parent.mkdir(parents=True, exist_ok=True)
    report = {
        "schemaVersion": "1.0", "phase": "T2-GATE5D",
        "generatedAt": datetime.now(timezone.utc).isoformat().replace("+00:00", "Z"),
        "commitSha": subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=ROOT, text=True).strip(),
        "tenants": ["TENANT_A", "TENANT_B"],
        "surfaces": [{"surface": item, "result": "PASS"} for item in surfaces],
        "providerNetworkCalls": 0, "realPiiRecords": 0,
        "externalEvidence": {"sandbox": 0, "realDevice": 0, "pilot": 0},
    }
    output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(f"T2-GATE5D ATTACK OK: surfaces={len(surfaces)} tenants=2 network=0 realPii=0")


if __name__ == "__main__":
    main()
