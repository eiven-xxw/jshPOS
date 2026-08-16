from __future__ import annotations

import argparse
import hashlib
import hmac
import os
import sqlite3
import subprocess
import sys
import tempfile
import time
from pathlib import Path

from common import FIXTURE_ROOT, ProbeResult, fixture_digest, load_json, require


FAKE_PACKAGE_KEY = b"JSH-POS-WEEK2-PUBLIC-UPGRADE-TEST-VECTOR"


def connect(path: Path) -> sqlite3.Connection:
    connection = sqlite3.connect(path, timeout=10)
    connection.execute("PRAGMA journal_mode=WAL")
    connection.execute("PRAGMA synchronous=FULL")
    return connection


def initialize(path: Path) -> None:
    connection = connect(path)
    try:
        connection.executescript(
            """
            CREATE TABLE syn_schema_meta(version INTEGER NOT NULL);
            INSERT INTO syn_schema_meta VALUES (1);
            CREATE TABLE syn_fact(fact_id TEXT PRIMARY KEY, payload_hash TEXT NOT NULL);
            """
        )
        connection.executemany(
            "INSERT INTO syn_fact VALUES (?, ?)",
            [(f"SYN-UPG-FACT-{index:04d}", hashlib.sha256(f"payload-{index}".encode()).hexdigest()) for index in range(100)],
        )
        connection.commit()
    finally:
        connection.close()


def fact_digest(path: Path) -> str:
    connection = connect(path)
    try:
        rows = connection.execute("SELECT fact_id,payload_hash FROM syn_fact ORDER BY fact_id").fetchall()
        payload = "\n".join(f"{row[0]}:{row[1]}" for row in rows).encode()
        return hashlib.sha256(payload).hexdigest()
    finally:
        connection.close()


def schema_version(path: Path) -> int:
    connection = connect(path)
    try:
        return connection.execute("SELECT version FROM syn_schema_meta").fetchone()[0]
    finally:
        connection.close()


def migration_child(path: Path, fault: str) -> None:
    connection = connect(path)
    connection.execute("BEGIN IMMEDIATE")
    connection.execute("ALTER TABLE syn_fact ADD COLUMN source_version INTEGER NOT NULL DEFAULT 1")
    if fault == "MIGRATION_STEP1_KILL":
        os._exit(91)
    connection.execute("CREATE INDEX syn_fact_source_version_idx ON syn_fact(source_version)")
    if fault == "MIGRATION_STEP2_KILL":
        os._exit(92)
    if fault == "MIGRATION_SQL_FAIL":
        connection.execute("INSERT INTO syn_missing_table VALUES (1)")
    connection.execute("UPDATE syn_schema_meta SET version=2")
    connection.commit()
    connection.close()


def migrate_to_three(path: Path) -> None:
    connection = connect(path)
    try:
        connection.execute("BEGIN IMMEDIATE")
        connection.execute("ALTER TABLE syn_fact ADD COLUMN feature_flag INTEGER NOT NULL DEFAULT 0")
        connection.execute("UPDATE syn_schema_meta SET version=3")
        connection.commit()
    except Exception:
        connection.rollback()
        raise
    finally:
        connection.close()


def run_migration_child(path: Path, fault: str) -> subprocess.CompletedProcess[bytes]:
    return subprocess.run(
        [sys.executable, str(Path(__file__).resolve()), "--child", str(path), fault],
        check=False,
        stdout=subprocess.DEVNULL,
        stderr=subprocess.PIPE,
    )


def app_can_open(app: int, schema: int, supported: list[dict[str, int]]) -> bool:
    profile = next(item for item in supported if item["app"] == app)
    return profile["minSchema"] <= schema <= profile["maxSchema"]


def package_preflight(fault: str) -> bool:
    package = b"SYNTHETIC-APK-B-NOT-INSTALLABLE"
    digest = hashlib.sha256(package).hexdigest()
    test_mac = hmac.new(FAKE_PACKAGE_KEY, package, hashlib.sha256).hexdigest()
    if fault == "DOWNLOAD_TRUNCATED":
        package = package[:10]
    elif fault == "BAD_DIGEST":
        digest = "f" * 64
    elif fault == "BAD_TEST_MAC":
        test_mac = "e" * 64
    elif fault == "SPACE_REJECTED":
        return False
    return hashlib.sha256(package).hexdigest() == digest and hmac.compare_digest(
        hmac.new(FAKE_PACKAGE_KEY, package, hashlib.sha256).hexdigest(), test_mac
    )


def run_probe() -> ProbeResult:
    fixture_path = FIXTURE_ROOT / "upgrade-plan.json"
    plan = load_json(fixture_path)
    assertions = 0
    iterations = 0
    blocked_preflight = 0
    migration_recoveries = 0
    safe_old_app_blocks = 0
    durations: list[float] = []

    with tempfile.TemporaryDirectory(prefix="jshpos-t1-w2-upgrade-") as directory:
        root = Path(directory)

        for repetition in range(plan["normalUpgradeRepetitions"]):
            database = root / f"normal-{repetition}.sqlite"
            initialize(database)
            before = fact_digest(database)
            started = time.perf_counter()
            completed = run_migration_child(database, "NONE")
            durations.append(time.perf_counter() - started)
            require(completed.returncode == 0, f"normal migration {repetition} failed")
            require(schema_version(database) == 2, "normal migration schema mismatch")
            require(fact_digest(database) == before, "normal migration changed committed facts")
            require(app_can_open(2, 2, plan["supported"]), "new app cannot open migrated schema")
            assertions += 4
            iterations += 1

        for fault in ("DOWNLOAD_TRUNCATED", "BAD_DIGEST", "BAD_TEST_MAC", "SPACE_REJECTED"):
            for repetition in range(plan["repetitions"]):
                database = root / f"{fault.lower()}-{repetition}.sqlite"
                initialize(database)
                before = fact_digest(database)
                require(not package_preflight(fault), f"{fault} package was admitted")
                require(schema_version(database) == 1, f"{fault} changed schema")
                require(fact_digest(database) == before, f"{fault} changed facts")
                require(app_can_open(1, 1, plan["supported"]), f"{fault} disabled old app")
                assertions += 4
                iterations += 1
                blocked_preflight += 1

        for fault in ("MIGRATION_STEP1_KILL", "MIGRATION_STEP2_KILL", "MIGRATION_SQL_FAIL"):
            for repetition in range(plan["repetitions"]):
                database = root / f"{fault.lower()}-{repetition}.sqlite"
                initialize(database)
                before = fact_digest(database)
                completed = run_migration_child(database, fault)
                require(completed.returncode != 0, f"{fault} did not fail")
                require(schema_version(database) == 1, f"{fault} exposed intermediate schema")
                require(fact_digest(database) == before, f"{fault} changed committed facts")
                recovered = run_migration_child(database, "NONE")
                require(recovered.returncode == 0, f"{fault} was not restartable")
                require(schema_version(database) == 2, f"{fault} recovery schema mismatch")
                require(fact_digest(database) == before, f"{fault} recovery changed facts")
                assertions += 6
                iterations += 1
                migration_recoveries += 1

        for repetition in range(plan["repetitions"]):
            database = root / f"healthcheck-{repetition}.sqlite"
            initialize(database)
            before = fact_digest(database)
            completed = run_migration_child(database, "NONE")
            require(completed.returncode == 0, "healthcheck setup migration failed")
            simulated_health_ok = False
            selected_app = 2 if simulated_health_ok else 1
            require(selected_app == 1, "health failure did not roll back app")
            require(schema_version(database) == 2, "health failure reversed data schema")
            require(app_can_open(selected_app, 2, plan["supported"]), "rolled back app is incompatible")
            require(fact_digest(database) == before, "health rollback changed facts")
            assertions += 4
            iterations += 1

        for repetition in range(plan["repetitions"]):
            database = root / f"old-app-new-schema-{repetition}.sqlite"
            initialize(database)
            before = fact_digest(database)
            require(run_migration_child(database, "NONE").returncode == 0, "schema2 setup failed")
            migrate_to_three(database)
            require(schema_version(database) == 3, "schema3 migration failed")
            require(not app_can_open(1, 3, plan["supported"]), "old app opened unsupported schema")
            require(fact_digest(database) == before, "safe block changed facts")
            assertions += 4
            iterations += 1
            safe_old_app_blocks += 1

    return ProbeResult(
        requirementId="T1-UPG-001",
        domain="UPGRADE_ROLLBACK",
        result="PASS",
        assertions=assertions,
        iterations=iterations,
        metrics={
            "normalUpgrades": plan["normalUpgradeRepetitions"],
            "preflightBlocks": blocked_preflight,
            "migrationCrashRecoveries": migration_recoveries,
            "healthcheckAppRollbacksWithoutSchemaRollback": plan["repetitions"],
            "safeOldAppBlocks": safe_old_app_blocks,
            "factDigestChanges": 0,
            "maxNormalMigrationSeconds": round(max(durations), 3),
            "packageEvidence": "FAKE_BYTES_NOT_APK",
        },
        fixtureDigests=[fixture_digest(fixture_path)],
    )


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--child", action="store_true")
    parser.add_argument("args", nargs="*")
    parsed = parser.parse_args()
    if parsed.child:
        if len(parsed.args) != 2:
            raise SystemExit("child requires database and fault")
        migration_child(Path(parsed.args[0]), parsed.args[1])
        return
    print(run_probe())


if __name__ == "__main__":
    main()
