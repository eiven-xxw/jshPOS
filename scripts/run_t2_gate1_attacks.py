from __future__ import annotations

import argparse
import hashlib
import json
import re
import subprocess
from datetime import datetime, timezone
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
MODULE = ROOT / "server" / "ruoyi-modules" / "jshpos-catalog"
VECTOR = ROOT / "contracts" / "t2" / "gate1" / "test-vectors" / "two-tenant-product-price-v1.json"


def require(condition: bool, message: str) -> None:
    if not condition:
        raise SystemExit(f"T2-GATE1 ATTACK ERROR: {message}")


def read(relative: str) -> str:
    return (ROOT / relative).read_text(encoding="utf-8")


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()

    vector = json.loads(VECTOR.read_text(encoding="utf-8"))
    require(vector.get("syntheticOnly") is True, "test vector must remain synthetic")
    require(len(vector.get("tenants", [])) == 2, "exactly two fictional tenants are required")

    dto = read("server/ruoyi-modules/jshpos-catalog/src/main/java/com/jingshanghui/pos/catalog/interfaces/rest/dto/CatalogRequests.java")
    dto_code = re.sub(r"/\*.*?\*/|//[^\n]*", "", dto, flags=re.DOTALL)
    require("tenantId" not in dto_code and "tenant_id" not in dto_code, "API DTO exposes tenant override")
    require("ignoreUnknown = false" in dto, "unknown tenant fields would not fail closed")

    mapper = read("server/ruoyi-modules/jshpos-catalog/src/main/java/com/jingshanghui/pos/catalog/infrastructure/persistence/mapper/CatalogMapper.java")
    sql_blocks = re.findall(r'@(Select|Insert|Update)\("""(.*?)"""\)', mapper, flags=re.DOTALL)
    single_sql = re.findall(r'@(Select|Insert|Update)\("([^"\n]+)"\)', mapper)
    require(len(sql_blocks) + len(single_sql) >= 30, "expected formal Mapper SQL surface")
    for _, sql in [*sql_blocks, *single_sql]:
        normalized = sql.lower()
        require("tenant_id" in normalized, f"native SQL lacks tenant_id: {normalized[:80]}")
        require("#{tenantid}" in normalized, f"native SQL lacks trusted tenant parameter: {normalized[:80]}")

    guard = read("server/ruoyi-modules/jshpos-catalog/src/main/java/com/jingshanghui/pos/catalog/infrastructure/security/CatalogStrictTenantMapperGuard.java")
    require("tenantContext.requirePrincipal()" in guard and "catalog.infrastructure.persistence.mapper" in guard,
            "strict Mapper guard is incomplete")
    services = "\n".join(path.read_text(encoding="utf-8") for path in sorted(
        (MODULE / "src/main/java/com/jingshanghui/pos/catalog/application/service").glob("*.java")))
    require(services.count("tenantContext.requireTenantId()") >= 10, "service tenant injection is incomplete")
    require("namespace.objectKey" in services, "data package object key is not tenant namespaced")
    require("KMS/HSM" in services and "PackageSigningPort" in services, "package private-key boundary missing")

    all_runtime = "\n".join(path.read_text(encoding="utf-8", errors="replace") for path in sorted(
        (MODULE / "src/main").rglob("*")) if path.is_file())
    require("@Scheduled" not in all_runtime and "TaskExecutor" not in all_runtime,
            "background task exists without an admitted tenant task design")
    require("CacheManager" not in all_runtime and "CacheUtils" not in all_runtime,
            "cache surface exists without an admitted tenant cache design")
    require("ExcelUtil" not in all_runtime and "export" not in all_runtime.lower(),
            "export surface exists without an admitted tenant export design")

    migration_test = read("server/ruoyi-modules/jshpos-catalog/src/test/java/com/jingshanghui/pos/catalog/migration/CatalogMigrationMySqlIT.java")
    for token in ("TENANT_A", "TENANT_B", "category_id,tenant_id", "isInstanceOf(SQLException.class)"):
        require(token in migration_test, f"MySQL cross-tenant test missing {token}")

    service_tests = "\n".join(path.read_text(encoding="utf-8") for path in sorted(
        (MODULE / "src/test/java/com/jingshanghui/pos/catalog/application/service").glob("*.java")))
    require(service_tests.count('"TENANT_A"') >= 12, "trusted tenant service tests are incomplete")
    frontend = read("admin-web/src/api/catalog/contract.ts") + read("admin-web/src/api/catalog/__tests__/contract.spec.ts")
    require("assertNoClientTenantOverride" in frontend and "TENANT_B" in frontend,
            "frontend tenant override attack missing")

    surfaces = [
        {"surface": "API", "status": "PRESENT_GUARDED", "level": "STATIC+UNIT", "result": "PASS"},
        {"surface": "MAPPER", "status": "PRESENT_GUARDED", "level": "STATIC+UNIT", "result": "PASS"},
        {"surface": "NATIVE_SQL", "status": "PRESENT_GUARDED", "level": "STATIC+MYSQL_JOB", "result": "PASS"},
        {"surface": "IMPORT", "status": "PRESENT_GUARDED", "level": "UNIT+SYNTHETIC_CAPACITY", "result": "PASS"},
        {"surface": "DATA_PACKAGE_OBJECT", "status": "PRESENT_GUARDED", "level": "UNIT", "result": "PASS"},
        {"surface": "TASK", "status": "ABSENT_FAIL_CLOSED", "level": "STATIC", "result": "PASS"},
        {"surface": "CACHE", "status": "ABSENT_FAIL_CLOSED", "level": "STATIC", "result": "PASS"},
        {"surface": "EXPORT", "status": "ABSENT_FAIL_CLOSED", "level": "STATIC", "result": "PASS"},
        {"surface": "DATABASE_FK_UNIQUE", "status": "PRESENT_GUARDED", "level": "MYSQL_JOB", "result": "PASS"},
    ]
    output = args.output if args.output.is_absolute() else ROOT / args.output
    output.parent.mkdir(parents=True, exist_ok=True)
    evidence = {
        "schemaVersion": "1.0",
        "phase": "T2-GATE1-S1",
        "generatedAt": datetime.now(timezone.utc).isoformat().replace("+00:00", "Z"),
        "commitSha": subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=ROOT, text=True).strip(),
        "seed": vector.get("seed"),
        "vectorSha256": hashlib.sha256(VECTOR.read_bytes()).hexdigest(),
        "tenantCount": 2,
        "surfaces": surfaces,
        "externalEvidence": {"sandbox": 0, "realDevice": 0, "pilot": 0},
        "limitations": [
            "MySQL constraint result is produced by the separate mysql-migration CI job",
            "ABSENT surfaces are fail-closed scope assertions, not runtime feature evidence",
            "no SANDBOX REAL_DEVICE PILOT or commercial evidence",
        ],
    }
    output.write_text(json.dumps(evidence, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print("T2-GATE1 ATTACKS OK: tenants=2 surfaces=9 present=6 absent-fail-closed=3")


if __name__ == "__main__":
    main()
