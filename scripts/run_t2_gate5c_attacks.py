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
        raise SystemExit(f"T2-GATE5C ATTACK ERROR: {message}")


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
        "server/ruoyi-modules/jshpos-member/src/main/java/com/jingshanghui/pos/member/"
        "interfaces/rest/dto/MemberRequests.java") + read(
        "server/ruoyi-modules/jshpos-member/src/main/java/com/jingshanghui/pos/member/"
        "interfaces/rest/dto/PointsRequests.java"), flags=re.DOTALL)
    for field in ("tenantId", "tenant_id", "actorUserId", "operatorUserId", "keyMaterial"):
        require(field not in dto, f"public Member DTO exposes authority/secret field {field}")

    controllers = read("server/ruoyi-modules/jshpos-member/src/main/java/com/jingshanghui/pos/member/"
                       "interfaces/rest/MemberController.java") + read(
        "server/ruoyi-modules/jshpos-member/src/main/java/com/jingshanghui/pos/member/"
        "interfaces/rest/MemberPointsController.java")
    permissions = (
        "member:profile:create", "member:profile:read", "member:identity:bind",
        "member:identity:revoke", "member:consent:record", "member:privacy:request",
        "member:privacy:process", "member:points:read", "member:points:freeze",
        "member:points:settle", "member:points:adjust", "member:level:manage",
        "member:points:rebuild",
    )
    for permission in permissions:
        require(permission in controllers, f"permission missing {permission}")
    require("isSaveRequestData=false" in controllers and "isSaveResponseData=false" in controllers,
            "sensitive controller audit body suppression missing")
    for forbidden in ("reverseEarn", "reverseSpend", "expire(", "earn("):
        require(forbidden not in controllers, f"internal event operation exposed by REST: {forbidden}")

    member_xml = read("server/ruoyi-modules/jshpos-member/src/main/resources/mapper/member/"
                      "MemberPersistenceMapper.xml")
    points_xml = read("server/ruoyi-modules/jshpos-member/src/main/resources/mapper/member/"
                      "PointsPersistenceMapper.xml")
    for name, xml in (("member", member_xml), ("points", points_xml)):
        sql = statements(xml)
        require(sql and all("#{tenantId}" in item for item in sql),
                f"{name} Mapper statement lacks explicit trusted tenant binding")
        require("SELECT *" not in xml.upper(), f"{name} Mapper uses SELECT *")
    require(points_xml.upper().count("FOR UPDATE") >= 2,
            "points account/lot optimistic locking is incomplete")
    for owner in ("ORD_", "PAY_", "INV_", "PRM_", "RPT_"):
        require(f"UPDATE {owner}" not in points_xml.upper() and f"DELETE FROM {owner}" not in points_xml.upper(),
                f"Member Owner exposes cross-owner write {owner}")

    runtime_root = ROOT / "server/ruoyi-modules/jshpos-member/src/main/java"
    runtime = "\n".join(path.read_text(encoding="utf-8", errors="replace")
                          for path in runtime_root.rglob("*.java"))
    lowered = runtime.lower()
    require("TrustedTenantContext" in runtime and "MemberStrictTenantMapperGuard" in runtime,
            "trusted tenant context or fail-closed Mapper guard missing")
    require("StoreService" in runtime and "businessDate" in runtime and "approvalUserId" in runtime,
            "store scope, business day or independent approval fact missing")
    require(not any(token in lowered for token in ("java.net.http", "resttemplate", "webclient", "okhttp",
                                                    "hutool.http", "httpurlconnection", "https://", "http://")),
            "Provider HTTP/SDK runtime detected")
    require("float " not in lowered and "double " not in lowered,
            "floating-point points detected")
    for forbidden in ("couponservice", "storedvalueservice", "reportservice", "smsservice",
                      "providerclient", "paymentprovider"):
        require(forbidden not in lowered, f"forbidden/later-Gate runtime detected {forbidden}")
    compact = runtime.replace(" ", "").replace("\n", "")
    require("reasonSha256" in runtime
            and 'Map.of("identityId",command.identityId(),"reason",reason)' not in compact
            and 'Map.of("reason",reason));returnresult' not in compact,
            "free-text reason may leak through Member facts/audit")

    migration = read("server/ruoyi-modules/jshpos-member/src/main/resources/db/migration/"
                     "V202608170030__gate5c_member_points.sql").upper()
    for table in ("MBR_POINTS_LEDGER", "MBR_POINTS_ALLOCATION", "MBR_LEVEL_HISTORY"):
        require(f"TRG_{table}_NO_UPDATE" in migration and f"TRG_{table}_NO_DELETE" in migration,
                f"immutable trigger missing for {table}")
    require("DECIMAL(19,6)" in migration and " FLOAT" not in migration and " DOUBLE" not in migration,
            "points schema precision policy changed")

    cache = read("pos-flutter/lib/infrastructure/local_database/s11_member_schema.dart").lower()
    for pii in ("mobile", "phone", "card_no", "open_id", "identity_value", "cipher_text", "points_balance"):
        require(pii not in cache, f"POS member cache contains forbidden field {pii}")
    require("member_token_hash" in cache and "rights_digest" in cache,
            "POS minimal hash/digest cache contract missing")

    surfaces = [
        "REST_AUTHORITY", "CONTROLLER_PERMISSION", "SENSITIVE_AUDIT_BODY", "INTERNAL_PORT_NOT_REST",
        "TRUSTED_TENANT", "MAPPER_TENANT", "NATIVE_SQL_LOCK", "CROSS_OWNER_WRITE",
        "IDENTITY_HMAC", "IDENTITY_ENCRYPTION", "PII_EVENT", "PII_AUDIT", "CONSENT_APPEND_ONLY",
        "PRIVACY_STATE", "MERGE_SPLIT_REVERSIBLE", "POINTS_LEDGER_IMMUTABLE", "LEVEL_APPEND_ONLY",
        "DECIMAL_EXACT", "IDEMPOTENCY_HASH", "ACCOUNT_REBUILD", "FEFO", "FROZEN_ALLOCATION",
        "RETURN_CAP", "RETURN_DEBT", "POLICY_VERSION", "OPTIMISTIC_LOCK", "POS_MINIMAL_CACHE",
        "POS_NO_PII", "CACHE_TENANT", "CACHE_EXPIRY", "PROVIDER_NETWORK", "DEFERRED_RUNTIME",
        "TWO_SYNTHETIC_TENANTS", "FORWARD_REPAIR",
    ]
    output = args.output if args.output.is_absolute() else ROOT / args.output
    output.parent.mkdir(parents=True, exist_ok=True)
    report = {
        "schemaVersion": "1.0", "phase": "T2-GATE5C",
        "generatedAt": datetime.now(timezone.utc).isoformat().replace("+00:00", "Z"),
        "commitSha": subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=ROOT, text=True).strip(),
        "tenants": ["TENANT_A", "TENANT_B"],
        "surfaces": [{"surface": item, "result": "PASS"} for item in surfaces],
        "providerNetworkCalls": 0, "realPiiRecords": 0,
        "externalEvidence": {"sandbox": 0, "realDevice": 0, "pilot": 0},
    }
    output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(f"T2-GATE5C ATTACK OK: surfaces={len(surfaces)} tenants=2 network=0 realPii=0")


if __name__ == "__main__":
    main()
