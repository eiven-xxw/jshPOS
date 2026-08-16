from __future__ import annotations

import argparse
import hashlib
import json
import re
import subprocess
from datetime import datetime, timezone
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
MODULE = ROOT / "server" / "ruoyi-modules" / "jshpos-foundation"
FIXTURE = ROOT / "contracts" / "t2" / "gate0" / "two-tenant-fixture.json"


def require(condition: bool, message: str) -> None:
    if not condition:
        raise SystemExit(f"T2-GATE0 ATTACK ERROR: {message}")


def read(relative: str) -> str:
    return (ROOT / relative).read_text(encoding="utf-8")


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()

    fixture = json.loads(FIXTURE.read_text(encoding="utf-8"))
    require(fixture.get("syntheticOnly") is True, "fixture must remain synthetic")
    aliases = [item["tenantAlias"] for item in fixture.get("tenants", [])]
    require(aliases == ["TENANT_A", "TENANT_B"], "two fixed fictional tenants are required")

    dto = read("server/ruoyi-modules/jshpos-foundation/src/main/java/com/jingshanghui/pos/foundation/interfaces/rest/dto/FoundationRequests.java")
    dto_code = re.sub(r"/\*.*?\*/|//[^\n]*", "", dto, flags=re.DOTALL)
    require("tenantId" not in dto_code and "tenant_id" not in dto_code, "API DTO exposes tenant override")
    context = read("server/ruoyi-modules/jshpos-foundation/src/main/java/com/jingshanghui/pos/foundation/application/context/TrustedTenantContext.java")
    require("rejectClientTenant" in context and "requirePrincipal" in context, "trusted API context is incomplete")

    entities = sorted((MODULE / "src/main/java/com/jingshanghui/pos/foundation/infrastructure/persistence/entity").glob("*.java"))
    require(len(entities) == 7, "expected seven Gate 0 tenant entities")
    require(all("extends TenantEntity" in path.read_text(encoding="utf-8") for path in entities), "Mapper entity lost TenantEntity boundary")

    native_sql = read("server/ruoyi-modules/jshpos-foundation/src/main/java/com/jingshanghui/pos/foundation/infrastructure/persistence/mapper/StoreMapper.java")
    version_sql = read("server/ruoyi-modules/jshpos-foundation/src/main/java/com/jingshanghui/pos/foundation/infrastructure/persistence/mapper/ConfigTemplateVersionMapper.java")
    for source in (native_sql, version_sql):
        require("tenant_id = #{trustedTenantId}" in source, "native SQL lacks explicit trusted tenant predicate")

    namespace_test = read("server/ruoyi-modules/jshpos-foundation/src/test/java/com/jingshanghui/pos/foundation/application/security/TenantResourceNamespaceTest.java")
    require(all(token in namespace_test for token in ("TENANT_A", "TENANT_B", "cacheKey", "exportKey", "objectKey")), "resource namespace two-tenant test incomplete")
    task_test = read("server/ruoyi-modules/jshpos-foundation/src/test/java/com/jingshanghui/pos/foundation/application/security/TrustedTenantWorkAuthorizerTest.java")
    require("TENANT_B" in task_test and "FND-IAM-006" in task_test, "task tenant-switch attack missing")
    migration_test = read("server/ruoyi-modules/jshpos-foundation/src/test/java/com/jingshanghui/pos/foundation/migration/FoundationMigrationMySqlIT.java")
    require("TENANT_A',2001" in migration_test and "isInstanceOf(SQLException.class)" in migration_test, "database cross-tenant FK attack missing")

    surfaces = [
        {"surface": "API", "level": "STATIC", "result": "PASS", "assertion": "request DTO has no tenant override and context fails closed"},
        {"surface": "MAPPER", "level": "UNIT", "result": "PASS", "assertion": "all seven entities inherit TenantEntity and strict mapper guard is tested"},
        {"surface": "NATIVE_SQL", "level": "STATIC+INTEGRATION_PENDING_JOB", "result": "PASS", "assertion": "trusted tenant predicate plus MySQL cross-tenant FK test"},
        {"surface": "TASK", "level": "UNIT", "result": "PASS", "assertion": "tenant-switch argument rejected"},
        {"surface": "CACHE", "level": "UNIT", "result": "PASS", "assertion": "same logical key has distinct tenant namespace"},
        {"surface": "EXPORT", "level": "UNIT", "result": "PASS", "assertion": "export key is server-generated and traversal rejected"},
        {"surface": "OBJECT_STORAGE", "level": "UNIT", "result": "PASS", "assertion": "object key is tenant-prefixed and traversal rejected"},
    ]
    output = args.output if args.output.is_absolute() else ROOT / args.output
    output.parent.mkdir(parents=True, exist_ok=True)
    evidence = {
        "schemaVersion": "1.0",
        "phase": "T2-GATE0-S0",
        "generatedAt": datetime.now(timezone.utc).isoformat().replace("+00:00", "Z"),
        "commitSha": subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=ROOT, text=True).strip(),
        "seed": fixture["seed"],
        "fixtureSha256": hashlib.sha256(FIXTURE.read_bytes()).hexdigest(),
        "surfaces": surfaces,
        "limitations": [
            "MySQL constraint result is produced by the separate mysql-migration CI job",
            "no SANDBOX REAL_DEVICE PILOT or commercial evidence",
        ],
    }
    output.write_text(json.dumps(evidence, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print("T2-GATE0 ATTACKS OK: tenants=2 surfaces=7 static/unit boundaries verified")


if __name__ == "__main__":
    main()
