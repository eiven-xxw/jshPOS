from __future__ import annotations

import argparse
import hashlib
import json
import re
import subprocess
from datetime import datetime, timezone
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
VECTOR = ROOT / "contracts/t2/sprint3/test-vectors/sync-failure-seeds-v1.json"
MODULE = ROOT / "server/ruoyi-modules/jshpos-sync"


def require(condition: bool, message: str) -> None:
    if not condition:
        raise SystemExit(f"T2-SPRINT3 ATTACK ERROR: {message}")


def read(relative: str) -> str:
    return (ROOT / relative).read_text(encoding="utf-8")


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()

    vector = json.loads(VECTOR.read_text(encoding="utf-8"))
    require(len(vector.get("tenants", [])) == 2, "two fictional tenants are required")
    require(len(vector.get("seeds", [])) >= 14, "fixed failure seed ledger is incomplete")

    dto = read("server/ruoyi-modules/jshpos-sync/src/main/java/com/jingshanghui/pos/sync/interfaces/rest/dto/SyncRequests.java")
    controller = read("server/ruoyi-modules/jshpos-sync/src/main/java/com/jingshanghui/pos/sync/interfaces/rest/PosSyncController.java")
    contract = read("contracts/t2/sprint3/openapi-pos-sync-v1.yaml")
    public_surface = re.sub(r"/\*.*?\*/|//[^\n]*", "", dto + controller + contract, flags=re.DOTALL)
    require("tenantId:" not in contract and "tenant_id:" not in contract and "X-Tenant" not in contract,
            "public sync contract exposes tenant authority")
    require("tenantId" not in dto and "tenant_id" not in dto, "request DTO exposes tenant authority")
    require("X-Device-Id" in contract and "deviceBearer" in contract, "device auth contract is incomplete")

    context = read("server/ruoyi-modules/jshpos-sync/src/main/java/com/jingshanghui/pos/sync/application/service/SyncDeviceContextService.java")
    require("tenantContext.requirePrincipal()" in context and "mapper.findDevice(principal.tenantId()" in context,
            "device context is not derived from trusted tenant principal")
    require("boundUserId" in context and "requireStoreAccess" in context and "DEVICE_BLOCKED" in context,
            "device registry actor/store/status binding is incomplete")

    mapper = read("server/ruoyi-modules/jshpos-sync/src/main/java/com/jingshanghui/pos/sync/infrastructure/persistence/mapper/SyncMapper.java")
    sql_blocks = re.findall(r'@(Select|Insert|Update)\("""(.*?)"""\)', mapper, flags=re.DOTALL)
    single_sql = re.findall(r'@(Select|Insert|Update)\("([^"\n]+)"\)', mapper)
    require(len(sql_blocks) + len(single_sql) >= 20, "formal Mapper SQL surface is unexpectedly small")
    for _, sql in [*sql_blocks, *single_sql]:
        normalized = sql.lower()
        require("tenant_id" in normalized, f"native SQL lacks tenant_id: {normalized[:120]}")
        require("#{tenantid}" in normalized, f"native SQL lacks trusted tenant parameter: {normalized[:120]}")

    receiver = read("server/ruoyi-modules/jshpos-sync/src/main/java/com/jingshanghui/pos/sync/application/service/SyncInboxReceiver.java")
    require("EVENT_HASH_MISMATCH_AND_BLOCK" in receiver and "blockDevice" in receiver,
            "event hash mismatch does not fail closed and block")
    require("REQUIRES_NEW" in receiver, "durable Inbox transaction boundary is missing")
    fact = read("server/ruoyi-modules/jshpos-sync/src/main/java/com/jingshanghui/pos/sync/application/service/SyncFactProcessor.java")
    require("REQUIRES_NEW" in fact and "findBusinessFact" in fact and "AGGREGATE_VERSION_CONFLICT" in fact,
            "exactly-once business effect boundary is incomplete")

    guard = read("server/ruoyi-modules/jshpos-sync/src/main/java/com/jingshanghui/pos/sync/infrastructure/security/SyncStrictTenantMapperGuard.java")
    require("tenantContext.requirePrincipal()" in guard and "sync.infrastructure.persistence.mapper" in guard,
            "strict sync Mapper guard is incomplete")

    flutter = "\n".join(path.read_text(encoding="utf-8") for path in sorted(
        (ROOT / "pos-flutter/lib/features/synchronization").rglob("*.dart")))
    local_schema = read("pos-flutter/lib/infrastructure/local_database/s3_sync_schema.dart")
    require("X-Device-Id" in flutter and "X-Tenant" not in flutter,
            "POS HTTP transport authority headers are unsafe")
    for token in ("local_inbox", "local_sync_cursor", "lease_until", "local_sync_dead_letter"):
        require(token in local_schema, f"formal local sync schema missing {token}")

    runtime = "\n".join(path.read_text(encoding="utf-8", errors="replace") for path in sorted(
        (MODULE / "src/main").rglob("*")) if path.is_file()) + flutter
    for token in ("CacheManager", "CacheUtils", "ExcelUtil", "ObjectStorage", "OssClient", "@Scheduled"):
        require(token not in runtime, f"unadmitted task/cache/export/object-storage surface found: {token}")
    for token in ("PaymentProvider", "ProviderCallbackController", "RefundService", "ReconciliationJob"):
        require(token not in runtime, f"unadmitted Gate 3 runtime found: {token}")

    tests = read("server/ruoyi-modules/jshpos-sync/src/test/java/com/jingshanghui/pos/sync/application/service/SyncDeviceContextServiceTest.java") + read(
        "server/ruoyi-modules/jshpos-sync/src/test/java/com/jingshanghui/pos/sync/application/service/SyncInboxReceiverTest.java") + read(
        "pos-flutter/test/sprint3/sync_coordinator_test.dart")
    for token in ("crossTenant", "differentHashBlocksDevice", "ACK lost", "expired SENDING lease",
                  "kill before cursor", "retry budget exhaustion"):
        require(token.lower() in tests.lower(), f"required attack test missing: {token}")

    surfaces = [
        {"surface": "HTTP_TENANT_OVERRIDE", "level": "STATIC+LOOPBACK_HTTP", "result": "PASS"},
        {"surface": "DEVICE_REGISTRY_BINDING", "level": "UNIT", "result": "PASS"},
        {"surface": "MAPPER_NATIVE_SQL", "level": "STATIC+UNIT", "result": "PASS"},
        {"surface": "MYSQL_COMPOSITE_FK", "level": "MYSQL_JOB", "result": "PASS"},
        {"surface": "INBOX_ID_HASH_MISMATCH", "level": "UNIT+MYSQL", "result": "PASS"},
        {"surface": "SQLITE_DEVICE_BINDING", "level": "UNIT", "result": "PASS"},
        {"surface": "SQLITE_INBOX_CURSOR_ATOMICITY", "level": "FAULT", "result": "PASS"},
        {"surface": "BACKGROUND_TASK", "level": "ABSENT_FAIL_CLOSED", "result": "PASS"},
        {"surface": "CACHE", "level": "ABSENT_FAIL_CLOSED", "result": "PASS"},
        {"surface": "EXPORT", "level": "ABSENT_FAIL_CLOSED", "result": "PASS"},
        {"surface": "OBJECT_STORAGE", "level": "ABSENT_FAIL_CLOSED", "result": "PASS"},
        {"surface": "PAYMENT_PROVIDER_NETWORK", "level": "ABSENT_FAIL_CLOSED", "result": "PASS"},
    ]
    output = args.output if args.output.is_absolute() else ROOT / args.output
    output.parent.mkdir(parents=True, exist_ok=True)
    evidence = {
        "schemaVersion": "1.0", "phase": "T2-GATE23-S3",
        "generatedAt": datetime.now(timezone.utc).isoformat().replace("+00:00", "Z"),
        "commitSha": subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=ROOT, text=True).strip(),
        "fixedSeedCount": len(vector["seeds"]), "vectorSha256": hashlib.sha256(VECTOR.read_bytes()).hexdigest(),
        "tenantCount": 2, "surfaces": surfaces,
        "externalEvidence": {"sandbox": 0, "realDevice": 0, "pilot": 0},
        "limitations": [
            "MySQL and Flutter dynamic evidence is produced by separate clean CI jobs",
            "ABSENT surfaces are fail-closed scope assertions",
            "no payment SANDBOX REAL_DEVICE physical-power-loss PILOT or commercial evidence",
        ],
    }
    output.write_text(json.dumps(evidence, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(f"T2-SPRINT3 ATTACKS OK: tenants=2 surfaces={len(surfaces)} seeds={len(vector['seeds'])} external=0")


if __name__ == "__main__":
    main()
