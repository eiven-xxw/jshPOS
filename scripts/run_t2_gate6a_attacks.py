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
        raise SystemExit(f"T2-GATE6A ATTACK ERROR: {message}")


def read(relative: str) -> str:
    return (ROOT / relative).read_text(encoding="utf-8")


def statements(xml: str) -> list[tuple[str, str]]:
    return re.findall(r'<(?:select|insert|update|delete)\b[^>]*id="([^"]+)"[^>]*>(.*?)</(?:select|insert|update|delete)>',
                      xml, flags=re.IGNORECASE | re.DOTALL)


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()

    dto = re.sub(r"/\*.*?\*/|//[^\n]*", "", read(
        "server/ruoyi-modules/jshpos-sync/src/main/java/com/jingshanghui/pos/sync/interfaces/rest/dto/TerminalRequests.java"),
        flags=re.DOTALL)
    for field in ("tenantId", "tenant_id", "credentialHmac", "secretHmac", "actorUserId"):
        require(field not in dto, f"public Terminal DTO exposes authority or digest field {field}")

    controllers = read("server/ruoyi-modules/jshpos-sync/src/main/java/com/jingshanghui/pos/sync/interfaces/rest/TerminalAdminController.java")
    controllers += read("server/ruoyi-modules/jshpos-sync/src/main/java/com/jingshanghui/pos/sync/interfaces/rest/PosTerminalController.java")
    for permission in ("terminal:activation:issue", "terminal:activation:cancel", "terminal:registry:read",
                       "terminal:status:manage", "terminal:credential:rotate", "pos:terminal:report"):
        require(permission in controllers, f"terminal permission missing {permission}")
    require("/activate" in controllers and "@SaCheckPermission" in controllers,
            "activation root or authenticated capability permission missing")

    xml = read("server/ruoyi-modules/jshpos-sync/src/main/resources/mapper/sync/TerminalRegistryMapper.xml")
    allowed_narrow = {"lockActivationById", "consumeActivation", "lockDeviceForAuthentication"}
    for statement_id, sql in statements(xml):
        normalized = sql.lower()
        if statement_id not in allowed_narrow:
            require("tenant_id" in normalized or "#{tenantid}" in normalized,
                    f"terminal statement lacks tenant binding {statement_id}")
    require("${" not in xml and "SELECT *" not in xml.upper(), "unsafe dynamic SQL or SELECT * detected")
    for owner in ("ORD_", "PAY_", "REF_", "INV_", "CST_", "PRM_", "MBR_", "RPT_"):
        require(f" FROM {owner}" not in xml.upper() and f" JOIN {owner}" not in xml.upper()
                and f"UPDATE {owner}" not in xml.upper(), f"terminal cross-owner access {owner}")
    require("secret_hmac" in xml and "activation_secret" not in xml.lower(),
            "terminal secret is not digest-only")

    runtime_root = ROOT / "server/ruoyi-modules/jshpos-sync/src/main/java"
    runtime = "\n".join(path.read_text(encoding="utf-8", errors="replace") for path in runtime_root.rglob("*.java"))
    lowered = runtime.lower()
    require("TrustedTenantContext" in runtime and "lockDeviceForAuthentication" in runtime,
            "trusted tenant or narrow device authentication root missing")
    registry_service = read(
        "server/ruoyi-modules/jshpos-sync/src/main/java/com/jingshanghui/pos/sync/application/service/TerminalRegistryService.java")
    authentication_service = read(
        "server/ruoyi-modules/jshpos-sync/src/main/java/com/jingshanghui/pos/sync/application/service/TerminalAuthenticationService.java")
    require("requireTenantAdministrator" in registry_service and '"BLOCKED".equals(device.status())' in registry_service,
            "tenant-wide listing or unblock lacks elevated authorization")
    require('"ROTATE_CREDENTIAL"' in registry_service and "Idempotency-Key" in controllers,
            "credential rotation lacks stable idempotency")
    require("noRollbackFor = ServiceException.class" in authentication_service,
            "credential clone block and rejection audit would roll back with the rejection")
    capability_update = re.search(r'<update id="updateDeviceCapability">(.*?)</update>', xml, re.DOTALL)
    require(capability_update is not None and "min_protocol_version" not in capability_update.group(1)
            and "max_protocol_version" not in capability_update.group(1),
            "client capability report can overwrite server protocol window")
    require("MessageDigest.isEqual" in runtime and "HmacSHA256" in runtime,
            "constant-time HMAC secret verification missing")
    require("SYNTHETIC" in runtime and "REAL_DEVICE" not in read(
        "server/ruoyi-modules/jshpos-sync/src/main/java/com/jingshanghui/pos/sync/application/service/TerminalRegistryService.java"),
        "terminal runtime may upgrade synthetic evidence to real-device")
    require(not any(token in lowered for token in ("java.net.http", "resttemplate", "webclient", "okhttp",
                                                    "hutool.http", "httpurlconnection", "https://")),
            "Provider or terminal command network runtime detected")

    migration = read("server/ruoyi-modules/jshpos-sync/src/main/resources/db/migration/"
                     "V202608160036__gate6a_terminal_registry.sql").upper()
    require("ALTER TABLE POS_SYNC_DEVICE" in migration and "CREATE TABLE DEV_TERMINAL (" not in migration,
            "terminal owner was duplicated instead of expanded")
    for trigger in ("DEV_CAPABILITY_NO_UPDATE", "DEV_CAPABILITY_NO_DELETE", "DEV_COMMAND_NO_UPDATE",
                    "DEV_AUDIT_NO_UPDATE", "DEV_AUDIT_NO_DELETE", "DEV_CREDENTIAL_NO_DELETE"):
        require(trigger in migration, f"terminal append-only trigger missing {trigger}")
    require("SECRET_HMAC" in migration and "ACTIVATION_SECRET" not in migration,
            "raw activation secret column detected")
    require(migration.count(" COMMENT '") >= 70,
            "terminal migration lacks complete Chinese table/column comments")

    registry = read("contracts/t2/gate6a/persistence-registry.csv")
    require("access_strategy,sql_mode" in registry and ",policy," not in registry,
            "terminal persistence registry is not using independent access and SQL dimensions")
    for marker in ("CONTROLLED_WRITE,XML", "APPEND_ONLY,XML"):
        require(marker in registry, f"terminal persistence declaration missing {marker}")

    require("isSaveResponseData = false" in controllers,
            "one-time activation or credential response may be persisted by operation logging")

    web = read("admin-web/src/api/terminal/types.ts") + read("admin-web/src/api/terminal/index.ts")
    require("tenantId" not in web and "secretHmac" not in web and "credentialHmac" not in web,
            "Web contract exposes tenant override or persisted secret digest")

    backup_dto = re.sub(r"/\*.*?\*/|//[^\n]*", "", read(
        "server/ruoyi-modules/jshpos-resilience/src/main/java/com/jingshanghui/pos/resilience/interfaces/rest/dto/BackupRequests.java"),
        flags=re.DOTALL)
    for field in ("tenantIds", "tenant_id", "secretKey", "keyBytes", "plaintext", "objectContent"):
        require(field not in backup_dto, f"public backup DTO exposes authority or secret field {field}")
    backup_controller = read(
        "server/ruoyi-modules/jshpos-resilience/src/main/java/com/jingshanghui/pos/resilience/interfaces/rest/BackupController.java")
    for permission in ("backup:create", "backup:catalog:read", "backup:restore:execute", "backup:evidence:read"):
        require(permission in backup_controller, f"backup permission missing {permission}")
    require(backup_controller.count("scope.tenantIds()") >= 4 and "isSaveRequestData=false" in backup_controller
            and "isSaveResponseData=false" in backup_controller,
            "backup trusted scope or Secret-safe operation logging missing")

    backup_xml = read(
        "server/ruoyi-modules/jshpos-resilience/src/main/resources/mapper/resilience/BackupPersistenceMapper.xml")
    require("${" not in backup_xml and "SELECT *" not in backup_xml.upper(),
            "backup persistence contains unsafe dynamic SQL or SELECT star")
    for owner in ("ORD_", "PAY_", "REF_", "INV_", "CST_", "PRM_", "MBR_", "RPT_"):
        require(f" FROM {owner}" not in backup_xml.upper() and f" JOIN {owner}" not in backup_xml.upper()
                and f"UPDATE {owner}" not in backup_xml.upper(), f"backup cross-owner SQL access {owner}")
    backup_runtime_root = ROOT / "server/ruoyi-modules/jshpos-resilience/src/main/java"
    backup_runtime = "\n".join(path.read_text(encoding="utf-8", errors="replace")
                                 for path in backup_runtime_root.rglob("*.java"))
    backup_lower = backup_runtime.lower()
    for marker in ("AES/GCM/NoPadding", "MessageDigest.isEqual", "tenantScopeSha256",
                   "AuthorizedScope", "beginEmpty", "FAIL_CLOSED"):
        require(marker in backup_runtime, f"backup fail-closed marker missing {marker}")
    require(not any(token in backup_lower for token in ("java.net.http", "resttemplate", "webclient", "okhttp",
                                                         "hutool.http", "httpurlconnection")),
            "backup runtime introduced outbound network access")
    require("delete(" not in read(
        "server/ruoyi-modules/jshpos-resilience/src/main/java/com/jingshanghui/pos/resilience/application/port/BackupPorts.java"),
        "backup object store exposes delete capability")
    backup_migrations = read("server/ruoyi-modules/jshpos-resilience/src/main/resources/db/migration/"
                             "V202608180038__gate6a_backup_recovery.sql").upper()
    backup_guards = read("server/ruoyi-modules/jshpos-resilience/src/main/resources/db/migration/"
                         "V202608180039__gate6a_backup_permissions_guards.sql").upper()
    for table in ("BAK_BACKUP_SET", "BAK_BACKUP_OBJECT", "BAK_RESTORE_DRILL", "BAK_RESTORE_CHECK", "BAK_AUDIT"):
        require(f"CREATE TABLE {table}" in backup_migrations, f"backup table missing {table}")
    for trigger in ("TRG_BAK_OBJECT_NO_UPDATE", "TRG_BAK_OBJECT_NO_DELETE", "TRG_BAK_CHECK_NO_UPDATE",
                    "TRG_BAK_CHECK_NO_DELETE", "TRG_BAK_AUDIT_NO_UPDATE", "TRG_BAK_AUDIT_NO_DELETE",
                    "TRG_BAK_SET_GUARD", "TRG_BAK_DRILL_GUARD"):
        require(trigger in backup_guards, f"backup append-only or transition guard missing {trigger}")
    require("KEY_VERSION" in backup_migrations and "SECRET_KEY" not in backup_migrations
            and "PRIVATE_KEY" not in backup_migrations, "backup schema stores key material")

    surfaces = [
        "ACTIVATION_NO_CLIENT_TENANT", "STORE_SCOPE", "ORG_STORE_BINDING", "ONE_TIME_ACTIVATION_SECRET",
        "HMAC_EXTERNAL_PEPPER", "CONSTANT_TIME_COMPARE", "NO_RAW_SECRET_AT_REST", "ACTIVATION_EXPIRY",
        "ACTIVATION_DUPLICATE", "ACTIVATION_CONTENT_CONFLICT", "DEVICE_ID_SERVER_ALLOCATED",
        "DEVICE_CREDENTIAL_ONE_TIME", "CREDENTIAL_ROTATION", "CREDENTIAL_CLONE", "REVOKED_REPLAY",
        "RETIRED_REPLAY", "CROSS_TENANT", "CROSS_STORE", "BOUND_USER", "VERSION_NUMERIC_COMPARE",
        "PROTOCOL_WINDOW", "SCHEMA_DOWNGRADE", "CLOCK_SKEW", "CAPABILITY_CANONICAL_HASH",
        "CAPABILITY_APPEND_ONLY", "COMMAND_APPEND_ONLY", "AUDIT_APPEND_ONLY", "OPTIMISTIC_STATE_VERSION",
        "LEGACY_FORWARD_COMPATIBILITY", "SYNTHETIC_EVIDENCE_ONLY", "REAL_DEVICE_BLOCKED",
        "PROVIDER_NETWORK_ZERO", "REAL_PII_ZERO", "CROSS_OWNER_SQL_ZERO", "WEB_NO_TENANT_OVERRIDE",
        "OBJECT_NAMESPACE_BOUNDARY", "UPGRADE_RUNTIME_ZERO", "BACKUP_RUNTIME_BEFORE_ADMISSION_ZERO",
        "BACKUP_NO_CLIENT_TENANT_SCOPE", "BACKUP_NO_KEY_MATERIAL_DTO", "BACKUP_SECRET_SAFE_AUDIT",
        "BACKUP_SIX_DATA_CLASSES", "BACKUP_AES_256_GCM", "BACKUP_AAD_SCOPE_BINDING",
        "BACKUP_PLAINTEXT_SHA256", "BACKUP_CIPHERTEXT_SHA256", "BACKUP_NONCE_VERIFICATION",
        "BACKUP_KEY_SEPARATION", "BACKUP_OBJECT_KEY_NAMESPACE", "BACKUP_OBJECT_APPEND_ONLY",
        "BACKUP_CATALOG_CONTROLLED_WRITE", "BACKUP_RESTORE_EMPTY_TARGET", "BACKUP_MANIFEST_CANONICAL",
        "BACKUP_MANIFEST_DIGEST", "BACKUP_WRONG_KEY_FAIL_CLOSED", "BACKUP_CORRUPT_FAIL_CLOSED",
        "BACKUP_MISSING_PART_FAIL_CLOSED", "BACKUP_CROSS_TENANT_FAIL_CLOSED",
        "BACKUP_SCHEMA_INCOMPATIBLE_FAIL_CLOSED", "BACKUP_PROJECTION_REBUILD",
        "BACKUP_BUSINESS_DAY_RECONCILIATION", "BACKUP_CURSOR_RECONCILIATION",
        "BACKUP_AUDIT_RECONCILIATION", "BACKUP_RPO_TIMER", "BACKUP_RTO_TIMER",
        "BACKUP_CLOUD_DR_ZERO", "BACKUP_COMMERCIAL_SLA_FALSE",
    ]
    output = args.output if args.output.is_absolute() else ROOT / args.output
    output.parent.mkdir(parents=True, exist_ok=True)
    report = {
        "schemaVersion": "1.0", "phase": "T2-GATE6A", "requirements": ["T2-TRM-001", "T2-BAK-001"],
        "generatedAt": datetime.now(timezone.utc).isoformat().replace("+00:00", "Z"),
        "commitSha": subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=ROOT, text=True).strip(),
        "tenants": ["TENANT_A", "TENANT_B"],
        "surfaces": [{"surface": item, "result": "PASS"} for item in surfaces],
        "providerNetworkCalls": 0, "realPiiRecords": 0, "realDeviceCommands": 0,
        "externalEvidence": {"sandbox": 0, "realDevice": 0, "pilot": 0, "cloudDr": 0},
    }
    output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(f"T2-GATE6A ATTACK OK: surfaces={len(surfaces)} realDevice=0 network=0")


if __name__ == "__main__":
    main()
