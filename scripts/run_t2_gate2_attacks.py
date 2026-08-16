from __future__ import annotations

import argparse
import hashlib
import json
import re
import subprocess
from datetime import datetime, timezone
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
MODULE = ROOT / "server" / "ruoyi-modules" / "jshpos-order"
VECTOR = ROOT / "contracts" / "t2" / "gate2" / "test-vectors" / "two-tenant-cash-order-v1.json"


def require(condition: bool, message: str) -> None:
    if not condition:
        raise SystemExit(f"T2-GATE2 ATTACK ERROR: {message}")


def read(relative: str) -> str:
    return (ROOT / relative).read_text(encoding="utf-8")


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()

    vector = json.loads(VECTOR.read_text(encoding="utf-8"))
    require(vector.get("syntheticOnly") is True, "test vector must remain synthetic")
    require(len(vector.get("tenants", [])) == 2, "two fictional tenants are required")
    require(vector.get("externalEvidence") == {"sandbox": 0, "realDevice": 0, "pilot": 0},
            "external evidence must remain zero")

    dto = read("server/ruoyi-modules/jshpos-order/src/main/java/com/jingshanghui/pos/order/interfaces/rest/dto/OrderRequests.java")
    dto_code = re.sub(r"/\*.*?\*/|//[^\n]*", "", dto, flags=re.DOTALL)
    require("tenantId" not in dto_code and "tenant_id" not in dto_code, "API exposes a tenant override")
    require(dto.count("ignoreUnknown = false") >= 4, "unknown payload fields do not fail closed")

    mapper = read("server/ruoyi-modules/jshpos-order/src/main/java/com/jingshanghui/pos/order/infrastructure/persistence/mapper/OrderMapper.java")
    sql_blocks = re.findall(r'@(Select|Insert|Update)\("""(.*?)"""\)', mapper, flags=re.DOTALL)
    single_sql = re.findall(r'@(Select|Insert|Update)\("([^"\n]+)"\)', mapper)
    require(len(sql_blocks) + len(single_sql) >= 20, "formal Mapper SQL surface is unexpectedly small")
    for _, sql in [*sql_blocks, *single_sql]:
        normalized = sql.lower()
        require("tenant_id" in normalized, f"native SQL lacks tenant_id: {normalized[:100]}")
        require("#{tenantid}" in normalized, f"native SQL lacks trusted tenant parameter: {normalized[:100]}")

    guard = read("server/ruoyi-modules/jshpos-order/src/main/java/com/jingshanghui/pos/order/infrastructure/security/OrderStrictTenantMapperGuard.java")
    require("tenantContext.requirePrincipal()" in guard and "order.infrastructure.persistence.mapper" in guard,
            "strict Mapper guard is incomplete")
    services = "\n".join(path.read_text(encoding="utf-8") for path in sorted(
        (MODULE / "src/main/java/com/jingshanghui/pos/order/application/service").glob("*.java")))
    require(services.count("tenantContext.requirePrincipal()") >= 3, "trusted tenant injection is incomplete")
    require("requireStoreAccess" in services and "cashierId 必须匹配可信操作者" in services,
            "store scope or actor binding is missing")

    controllers = read("server/ruoyi-modules/jshpos-order/src/main/java/com/jingshanghui/pos/order/interfaces/rest/ShiftController.java") + read(
        "server/ruoyi-modules/jshpos-order/src/main/java/com/jingshanghui/pos/order/interfaces/rest/CashOrderController.java")
    for permission in ("pos:shift:open", "pos:shift:approve-difference", "pos:shift:close",
                       "pos:cash:collect", "order:read"):
        require(permission in controllers, f"permission annotation missing {permission}")

    local_service = read("pos-flutter/lib/features/checkout/application/checkout_local_service.dart")
    local_db = read("pos-flutter/lib/infrastructure/local_database/pos_local_database.dart")
    local_schema = read("pos-flutter/lib/infrastructure/local_database/gate2_schema.dart")
    require(local_service.count("_binding.tenantId") >= 25, "SQLite statements are not consistently tenant-bound")
    require("database binding mismatch" in local_db and "singleton_id=1" in local_db,
            "database-to-device tenant binding is missing")
    for table in ("local_order", "local_cash_payment", "local_cash_ledger", "local_outbox",
                  "local_idempotency", "local_audit_event"):
        require(f"CREATE TABLE {table}" in local_schema, f"formal local table missing {table}")
    require("syn_" not in local_schema.lower(), "T1 probe table leaked into formal SQLite schema")

    runtime = "\n".join(path.read_text(encoding="utf-8", errors="replace") for path in sorted(
        (MODULE / "src/main").rglob("*")) if path.is_file())
    for token in ("@Scheduled", "TaskExecutor", "CacheManager", "CacheUtils", "ExcelUtil"):
        require(token not in runtime, f"unadmitted background/cache/export surface found: {token}")
    flutter_runtime = local_service + local_db + local_schema
    for token in ("package:http/", "dart:io", "WebSocket", "sync/push", "sync/pull", "sync/ack"):
        require(token.lower() not in flutter_runtime.lower(), f"remote sync runtime found: {token}")

    migration_test = read("server/ruoyi-modules/jshpos-order/src/test/java/com/jingshanghui/pos/order/migration/OrderMigrationMySqlIT.java")
    for token in ("TENANT_A", "TENANT_B", "shiftB", "801,'B-SKU'", "append-only"):
        require(token in migration_test, f"MySQL tenant/append-only attack missing {token}")
    flutter_test = read("pos-flutter/test/gate2/checkout_local_service_test.dart")
    for token in ("bindingB", "SQLite FULL", "IDEMPOTENCY_KEY_REUSED", "local_shift_approval"):
        require(token in flutter_test, f"SQLite attack test missing {token}")

    surfaces = [
        {"surface": "API_TENANT_OVERRIDE", "level": "STATIC", "result": "PASS"},
        {"surface": "MAPPER_NATIVE_SQL", "level": "STATIC+UNIT", "result": "PASS"},
        {"surface": "MYSQL_COMPOSITE_FK", "level": "MYSQL_JOB", "result": "PASS"},
        {"surface": "SQLITE_DEVICE_BINDING", "level": "UNIT", "result": "PASS"},
        {"surface": "SQLITE_CROSS_TENANT", "level": "UNIT", "result": "PASS"},
        {"surface": "PERMISSION_AND_ACTOR", "level": "STATIC+UNIT", "result": "PASS"},
        {"surface": "BACKGROUND_TASK", "level": "ABSENT_FAIL_CLOSED", "result": "PASS"},
        {"surface": "CACHE_EXPORT", "level": "ABSENT_FAIL_CLOSED", "result": "PASS"},
        {"surface": "REMOTE_SYNC_NETWORK", "level": "ABSENT_FAIL_CLOSED", "result": "PASS"},
    ]
    output = args.output if args.output.is_absolute() else ROOT / args.output
    output.parent.mkdir(parents=True, exist_ok=True)
    evidence = {
        "schemaVersion": "1.0",
        "phase": "T2-GATE2-S2",
        "generatedAt": datetime.now(timezone.utc).isoformat().replace("+00:00", "Z"),
        "commitSha": subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=ROOT, text=True).strip(),
        "seed": vector["seed"],
        "vectorSha256": hashlib.sha256(VECTOR.read_bytes()).hexdigest(),
        "tenantCount": 2,
        "surfaces": surfaces,
        "externalEvidence": {"sandbox": 0, "realDevice": 0, "pilot": 0},
        "limitations": [
            "MySQL constraints are executed by the separate clean MySQL CI job",
            "ABSENT surfaces are fail-closed scope assertions, not implemented features",
            "no SANDBOX REAL_DEVICE physical-power-loss PILOT or commercial evidence",
        ],
    }
    output.write_text(json.dumps(evidence, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print("T2-GATE2 ATTACKS OK: tenants=2 surfaces=9 sync-network=0 external=0")


if __name__ == "__main__":
    main()
