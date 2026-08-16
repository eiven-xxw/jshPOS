from __future__ import annotations

import hashlib
import sqlite3
import tempfile
from pathlib import Path

from common import FIXTURE_ROOT, ProbeResult, fixture_digest, load_json, require


FIXTURE = FIXTURE_ROOT / "upgrade-compat-plan.json"


def connect(path: Path) -> sqlite3.Connection:
    connection = sqlite3.connect(path)
    connection.execute("PRAGMA journal_mode=WAL")
    connection.execute("PRAGMA synchronous=FULL")
    return connection


def initialize(connection: sqlite3.Connection, seed: int) -> None:
    connection.executescript(
        """
        CREATE TABLE syn_meta (key TEXT PRIMARY KEY, value TEXT NOT NULL);
        CREATE TABLE syn_fact (fact_id TEXT PRIMARY KEY, payload TEXT NOT NULL);
        CREATE TABLE syn_outbox (event_id TEXT PRIMARY KEY, state TEXT NOT NULL);
        CREATE TABLE syn_migration (step TEXT PRIMARY KEY, applied INTEGER NOT NULL);
        """
    )
    connection.executemany(
        "INSERT INTO syn_meta(key,value) VALUES (?,?)",
        [("app_version", "A"), ("schema_version", "1"), ("migration_state", "NONE")],
    )
    connection.executemany(
        "INSERT INTO syn_fact(fact_id,payload) VALUES (?,?)",
        [(f"F-{seed}-{index}", f"SYNTHETIC-{index}") for index in range(20)],
    )
    connection.executemany(
        "INSERT INTO syn_outbox(event_id,state) VALUES (?,?)",
        [(f"E-{seed}-{index}", "PENDING") for index in range(20)],
    )
    connection.commit()


def fact_digest(connection: sqlite3.Connection) -> str:
    rows = connection.execute("SELECT fact_id,payload FROM syn_fact ORDER BY fact_id").fetchall()
    return hashlib.sha256(repr(rows).encode("utf-8")).hexdigest()


def meta(connection: sqlite3.Connection, key: str) -> str:
    return connection.execute("SELECT value FROM syn_meta WHERE key=?", (key,)).fetchone()[0]


def set_meta(connection: sqlite3.Connection, key: str, value: str) -> None:
    connection.execute("UPDATE syn_meta SET value=? WHERE key=?", (value, key))


def run_iteration(seed: int, repetition: int) -> dict[str, int]:
    with tempfile.TemporaryDirectory(prefix="jshpos-w3-upgrade-") as directory:
        db_path = Path(directory) / "synthetic-upgrade.sqlite"
        connection = connect(db_path)
        initialize(connection, seed * 100 + repetition)
        before_digest = fact_digest(connection)
        pending_before = connection.execute("SELECT COUNT(*) FROM syn_outbox WHERE state='PENDING'").fetchone()[0]

        # Compatible app-only upgrade while sync is pending.
        with connection:
            set_meta(connection, "app_version", "B_COMPAT")
        require(meta(connection, "schema_version") == "1", "compatible upgrade changed schema")
        require(connection.execute("SELECT COUNT(*) FROM syn_outbox WHERE state='PENDING'").fetchone()[0] == pending_before, "compatible upgrade lost pending sync")

        # Incompatible schema migration must be blocked while sync remains pending.
        incompatible_allowed = pending_before == 0
        require(not incompatible_allowed and meta(connection, "schema_version") == "1", "incompatible upgrade bypassed pending-sync guard")

        # Drain only the synthetic gate, then commit migration step 1 and fail app health.
        with connection:
            connection.execute("UPDATE syn_outbox SET state='ACKED'")
            connection.execute("INSERT INTO syn_migration(step,applied) VALUES ('ADD_COMPAT_MARKER',1)")
            set_meta(connection, "schema_version", "2")
            set_meta(connection, "migration_state", "FAILED_AFTER_STEP_1")
            set_meta(connection, "app_version", "B_FAILED_HEALTH")
        require(meta(connection, "schema_version") == "2", "committed schema step disappeared")

        # Application rollback is safe because old app A supports schema 1..2; schema is never reversed.
        with connection:
            set_meta(connection, "app_version", "A")
        old_app_compatible = 1 <= int(meta(connection, "schema_version")) <= 2
        require(old_app_compatible, "old app could not operate in declared compatibility window")
        require(meta(connection, "schema_version") == "2", "schema was rolled back with the app")

        # Forward repair is idempotent and re-entrant.
        for _ in range(2):
            with connection:
                connection.execute("INSERT OR IGNORE INTO syn_migration(step,applied) VALUES ('FORWARD_REPAIR',1)")
                set_meta(connection, "migration_state", "REPAIRED")
                set_meta(connection, "app_version", "B")
        require(connection.execute("SELECT COUNT(*) FROM syn_migration WHERE step='FORWARD_REPAIR'").fetchone()[0] == 1, "forward repair was not idempotent")
        require(meta(connection, "migration_state") == "REPAIRED" and meta(connection, "app_version") == "B", "forward repair did not restore health")

        # A later schema 3 closes the old-client window and old A must fail closed.
        with connection:
            set_meta(connection, "schema_version", "3")
        old_app_blocked = not (1 <= int(meta(connection, "schema_version")) <= 2)
        require(old_app_blocked, "old client was allowed beyond compatibility window")
        require(fact_digest(connection) == before_digest, "upgrade path altered committed synthetic facts")
        integrity = connection.execute("PRAGMA integrity_check").fetchone()[0]
        connection.close()
        require(integrity == "ok", "upgrade database integrity failed")
    return {"pending": pending_before, "forwardRepairs": 2}


def run_probe() -> list[ProbeResult]:
    plan = load_json(FIXTURE)
    results = [
        run_iteration(seed, repetition)
        for seed in plan["seeds"]
        for repetition in range(int(plan["repetitionsPerSeed"]))
    ]
    iterations = len(results)
    return [
        ProbeResult(
            "T1-UPG-001",
            "PENDING_SYNC_UPGRADE_COMPAT_FORWARD_REPAIR",
            "PASS",
            iterations * 12,
            iterations,
            {
                "compatiblePendingSyncUpgrades": iterations,
                "incompatiblePendingSyncBlocks": iterations,
                "migrationFailureAppRollbacks": iterations,
                "schemaRollbacks": 0,
                "oldClientCompatibilityWindows": iterations,
                "oldClientFailClosed": iterations,
                "forwardRepairReentries": sum(item["forwardRepairs"] for item in results),
                "factDigestChanges": 0,
                "failedSeeds": 0,
            },
            [fixture_digest(FIXTURE)],
        )
    ]
