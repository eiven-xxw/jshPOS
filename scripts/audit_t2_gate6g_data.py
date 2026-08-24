#!/usr/bin/env python3
"""审计 Gate 6G MySQL/SQLite 前向迁移、表元数据和合成种子。"""

from __future__ import annotations

import argparse
import hashlib
import json
import re
import subprocess
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
BASELINE = "a6c91a1d66857583ce7e498541b63bcf0b81dc52"
CONTROL_PLANE_EXCEPTIONS = {
    "bak_backup_set",
    "bak_backup_object",
    "bak_restore_drill",
    "bak_restore_check",
    "bak_audit",
}
SQLITE_SCHEMA_FILES = {
    "pos-flutter/lib/infrastructure/local_database/gate2_schema.dart",
    "pos-flutter/lib/infrastructure/local_database/s3_sync_schema.dart",
    "pos-flutter/lib/infrastructure/local_database/s9_promotion_schema.dart",
    "pos-flutter/lib/infrastructure/local_database/s9_manual_schema.dart",
    "pos-flutter/lib/infrastructure/local_database/s9_transaction_schema.dart",
    "pos-flutter/lib/infrastructure/local_database/s10_settlement_schema.dart",
    "pos-flutter/lib/infrastructure/local_database/s11_member_schema.dart",
}
FLYWAY_CALLBACK_EVENTS = {
    "beforeValidate", "afterValidate", "afterValidateError",
    "beforeMigrate", "beforeEachMigrate", "afterEachMigrate", "afterMigrate", "afterMigrateError",
    "beforeUndo", "beforeEachUndo", "afterEachUndo", "afterUndo", "afterUndoError",
    "beforeClean", "afterClean", "beforeInfo", "afterInfo", "beforeBaseline", "afterBaseline",
    "beforeRepair", "afterRepair",
}


def classify_flyway_file_name(name: str) -> dict:
    """按 Flyway 正式命名区分版本、可重复迁移与 SQL callback；未知命名失败关闭。"""
    versioned = re.fullmatch(r"V([0-9]+(?:\.[0-9]+)*)__[A-Za-z0-9][A-Za-z0-9_-]*\.sql", name)
    if versioned:
        return {"kind": "VERSIONED", "version": versioned.group(1)}
    if re.fullmatch(r"R__[A-Za-z0-9][A-Za-z0-9_-]*\.sql", name):
        return {"kind": "REPEATABLE", "version": None}
    callback = re.fullmatch(r"([A-Za-z]+)(?:__[A-Za-z0-9][A-Za-z0-9_-]*)?\.sql", name)
    if callback and callback.group(1) in FLYWAY_CALLBACK_EVENTS:
        return {"kind": "CALLBACK", "event": callback.group(1), "version": None}
    raise AssertionError(f"非法 Flyway 文件名: {name}")


def git_changed_files() -> list[tuple[str, str]]:
    result = subprocess.run(
        ["git", "diff", "--name-status", BASELINE, "--", "server/ruoyi-modules", "pos-flutter/lib/infrastructure/local_database"],
        cwd=ROOT,
        check=True,
        text=True,
        encoding="utf-8",
        capture_output=True,
    )
    rows = []
    for line in result.stdout.splitlines():
        if not line.strip():
            continue
        status, path = line.split("\t", 1)
        rows.append((status, path.replace("\\", "/")))
    return rows


def migration_files() -> list[Path]:
    return sorted((ROOT / "server" / "ruoyi-modules").glob("jshpos-*/src/main/resources/db/migration/*.sql"))


def mysql_schema() -> tuple[list[dict], list[dict], list[dict]]:
    tables: dict[str, dict] = {}
    alterations: dict[str, str] = {}
    versions: dict[str, list[str]] = {}
    migrations = migration_files()
    classifications: dict[Path, dict] = {}
    for source in migrations:
        relative = source.relative_to(ROOT).as_posix()
        try:
            classification = classify_flyway_file_name(source.name)
        except AssertionError as error:
            raise AssertionError(f"{error.args[0]}: {relative}") from error
        classifications[source] = classification
        if classification["kind"] == "VERSIONED":
            versions.setdefault(classification["version"], []).append(relative)
        text = source.read_text(encoding="utf-8")
        for match in re.finditer(
            r"CREATE\s+TABLE\s+(?:IF\s+NOT\s+EXISTS\s+)?`?([A-Za-z0-9_]+)`?\s*\(", text, re.IGNORECASE
        ):
            end = text.find(";", match.end())
            statement = text[match.start() : end + 1]
            name = match.group(1)
            key_lines = "\n".join(
                line
                for line in statement.splitlines()
                if re.match(r"\s*(PRIMARY|UNIQUE|KEY|CONSTRAINT)", line, re.IGNORECASE)
            )
            comment = re.search(r"\bCOMMENT\s*=\s*'([^']*)'", statement, re.IGNORECASE)
            tables[name] = {
                "table": name,
                "source": relative,
                "primaryKey": "PRIMARY KEY" in statement.upper(),
                "tenantId": bool(re.search(r"\btenant_id\b", statement, re.IGNORECASE)),
                "tenantIndexed": "tenant_id" in key_lines.lower(),
                "uniqueKeyCount": len(re.findall(r"\bUNIQUE\s+KEY\b", statement, re.IGNORECASE)),
                "secondaryIndexCount": len(re.findall(r"(?m)^\s*KEY\s+", statement, re.IGNORECASE)),
                "tableComment": comment.group(1) if comment else "",
                "hasFloatOrDouble": bool(re.search(r"\b(?:FLOAT|DOUBLE)\b", statement, re.IGNORECASE)),
            }
        for match in re.finditer(
            r"ALTER\s+TABLE\s+`?([A-Za-z0-9_]+)`?\s+COMMENT\s*=\s*'([^']+)'", text, re.IGNORECASE
        ):
            alterations[match.group(1)] = match.group(2)
    for name, comment in alterations.items():
        if name in tables:
            tables[name]["tableComment"] = comment
            tables[name]["commentSource"] = "FORWARD_MIGRATION"
    duplicate_versions = [
        {"version": version, "files": files}
        for version, files in sorted(versions.items())
        if len(files) > 1
    ]
    return list(tables.values()), duplicate_versions, [
        {
            "path": source.relative_to(ROOT).as_posix(),
            **classifications[source],
            "sha256": hashlib.sha256(source.read_bytes()).hexdigest(),
        }
        for source in migrations
    ]


def seed_summary() -> tuple[dict, list[str]]:
    source = ROOT / "contracts" / "t2" / "gate6g" / "test-vectors" / "internal-seed-v1.json"
    data = json.loads(source.read_text(encoding="utf-8"))
    failures: list[str] = []
    tenants = data.get("tenants", [])
    stores = [store for tenant in tenants for store in tenant.get("stores", [])]
    terminals = [terminal for tenant in tenants for terminal in tenant.get("terminals", [])]
    industries = {store.get("industryTemplate") for store in stores}
    tenant_ids = [tenant.get("tenantId") for tenant in tenants]
    if data.get("synthetic") is not True or data.get("containsRealPii") is not False or data.get("containsSecret") is not False:
        failures.append("合成种子边界未失败关闭")
    if len(tenants) != 2 or len(set(tenant_ids)) != 2:
        failures.append("必须且只能定义两个互异虚构租户")
    if industries != {"CONVENIENCE_STORE", "SNACK_DISCOUNT", "COMMUNITY_SUPERMARKET"}:
        failures.append("三业态合成模板不完整")
    if len(stores) < 4 or len(terminals) < 4:
        failures.append("多门店多终端合成数据不足")
    encoded = json.dumps(data, ensure_ascii=False, sort_keys=True, separators=(",", ":")).encode("utf-8")
    forbidden = re.compile(
        r"(?:BEGIN [A-Z ]*PRIVATE KEY|\"password\"\s*:|\"merchantNo\"\s*:|\"terminalSecret\"\s*:|\"phone\"\s*:|\"idCard\"\s*:|真实手机号|真实身份证)",
        re.IGNORECASE,
    )
    if forbidden.search(source.read_text(encoding="utf-8")):
        failures.append("种子包含疑似Secret或真实PII标记")
    return {
        "path": source.relative_to(ROOT).as_posix(),
        "seedId": data.get("seedId"),
        "tenantCount": len(tenants),
        "storeCount": len(stores),
        "terminalCount": len(terminals),
        "industryTemplates": sorted(industries),
        "canonicalSha256": hashlib.sha256(encoded).hexdigest(),
        "officialApiStepCount": len(data.get("applicationSequence", [])),
    }, failures


def bootstrap_summary() -> tuple[dict, list[str]]:
    """核对 RuoYi 空环境脚本与正式 Flyway 权限迁移的最小兼容面。"""
    source = ROOT / "server" / "script" / "sql" / "ry_vue_5.X.sql"
    workflow = ROOT / ".github" / "workflows" / "t2-gate6g.yml"
    sql = source.read_text(encoding="utf-8")
    workflow_text = workflow.read_text(encoding="utf-8")
    failures: list[str] = []
    route_column = re.search(
        r"ALTER\s+TABLE\s+sys_menu\s+ADD\s+COLUMN\s+route_name\s+varchar\(100\)",
        sql,
        re.IGNORECASE,
    )
    last_seed = sql.lower().rfind("insert into sys_menu values")
    if route_column is None or route_column.start() <= last_seed:
        failures.append("RuoYi sys_menu 必须在位置参数种子完成后前向补充 route_name")
    if "mysql --default-character-set=utf8mb4" not in workflow_text:
        failures.append("MySQL 空环境导入未显式锁定 utf8mb4 客户端字符集")
    redis_password = "synthetic_gate6g_redis_runtime"
    redis_server_configured = f"CONFIG SET requirepass {redis_password}" in workflow_text
    redis_client_configured = f"--spring.data.redis.password={redis_password}" in workflow_text
    if not redis_server_configured or not redis_client_configured:
        failures.append("运行栈 Redis 服务端与正式客户端的合成认证配置不一致")
    return {
        "path": source.relative_to(ROOT).as_posix(),
        "sha256": hashlib.sha256(source.read_bytes()).hexdigest(),
        "routeNameCompatibility": route_column is not None and route_column.start() > last_seed,
        "utf8mb4ClientImport": "mysql --default-character-set=utf8mb4" in workflow_text,
        "syntheticRedisAuthenticationAligned": redis_server_configured and redis_client_configured,
    }, failures


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--output", required=True)
    args = parser.parse_args()

    tables, duplicate_versions, migrations = mysql_schema()
    changed = git_changed_files()
    changed_published_migrations = [
        {"status": status, "path": path}
        for status, path in changed
        if "/db/migration/" in path and status != "A"
    ]
    changed_sqlite_schemas = [
        {"status": status, "path": path}
        for status, path in changed
        if path in SQLITE_SCHEMA_FILES
    ]
    missing_primary = [item["table"] for item in tables if not item["primaryKey"]]
    tenant_failures = [
        item["table"]
        for item in tables
        if item["table"] not in CONTROL_PLANE_EXCEPTIONS
        and (not item["tenantId"] or not item["tenantIndexed"])
    ]
    unexpected_tenant_exceptions = [
        item["table"] for item in tables if not item["tenantId"] and item["table"] not in CONTROL_PLANE_EXCEPTIONS
    ]
    missing_comments = [
        item["table"]
        for item in tables
        if not item["tableComment"] or not re.search(r"[\u4e00-\u9fff]", item["tableComment"])
    ]
    float_tables = [item["table"] for item in tables if item["hasFloatOrDouble"]]
    seed, seed_failures = seed_summary()
    bootstrap, bootstrap_failures = bootstrap_summary()
    required_sqlite_tests = [
        "pos-flutter/test/gate2/checkout_local_service_test.dart",
        "pos-flutter/test/gate5c/member_cache_store_test.dart",
        "pos-flutter/test/gate6g/data_migration_recovery_test.dart",
    ]
    missing_sqlite_tests = [path for path in required_sqlite_tests if not (ROOT / path).exists()]

    hard_failures = {
        "publishedMigrationModified": changed_published_migrations,
        "publishedSqliteSchemaModified": changed_sqlite_schemas,
        "duplicateFlywayVersion": duplicate_versions,
        "missingPrimaryKey": missing_primary,
        "tenantIsolation": sorted(set(tenant_failures + unexpected_tenant_exceptions)),
        "missingChineseTableComment": missing_comments,
        "floatOrDoubleTable": float_tables,
        "syntheticSeed": seed_failures,
        "ruoyiBootstrapCompatibility": bootstrap_failures,
        "sqliteRecoveryTests": missing_sqlite_tests,
    }
    failure_count = sum(len(value) for value in hard_failures.values())
    result = {
        "requirementId": "T2-DAT-001",
        "evidenceLevel": "STATIC_AND_SOFTWARE_EXECUTION",
        "status": "PASS" if failure_count == 0 else "FAIL",
        "baselineCommit": BASELINE,
        "migrationCount": len(migrations),
        "migrationManifest": migrations,
        "tableCount": len(tables),
        "tables": tables,
        "multiTenantControlPlaneExceptions": sorted(CONTROL_PLANE_EXCEPTIONS),
        "seed": seed,
        "ruoyiBootstrap": bootstrap,
        "sqlite": {"currentVersion": 7, "publishedSchemaFileCount": len(SQLITE_SCHEMA_FILES)},
        "hardFailureCount": failure_count,
        "hardFailures": hard_failures,
    }
    output = ROOT / args.output
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(
        f"T2 DAT audit {result['status']}: migrations={len(migrations)}, tables={len(tables)}, "
        f"seed={seed['tenantCount']} tenants/{seed['storeCount']} stores, hardFailures={failure_count}"
    )
    return 0 if failure_count == 0 else 1


if __name__ == "__main__":
    raise SystemExit(main())
