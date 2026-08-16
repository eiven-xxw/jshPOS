from __future__ import annotations

import argparse
import os
import sqlite3
import subprocess
import sys
import tempfile
import time
from pathlib import Path

from common import FIXTURE_ROOT, ProbeResult, fixture_digest, load_json, require


SCHEMA = """
CREATE TABLE syn_fact (
  fact_id TEXT PRIMARY KEY,
  tenant_id TEXT NOT NULL,
  payload_hash TEXT NOT NULL
);
CREATE TABLE syn_intent (
  intent_id TEXT PRIMARY KEY,
  fact_id TEXT NOT NULL UNIQUE REFERENCES syn_fact(fact_id),
  state TEXT NOT NULL CHECK (state IN ('SYN_PENDING'))
);
CREATE TABLE syn_outbox (
  event_id TEXT PRIMARY KEY,
  fact_id TEXT NOT NULL UNIQUE REFERENCES syn_fact(fact_id),
  payload_hash TEXT NOT NULL
);
"""


def connect(path: Path) -> sqlite3.Connection:
    connection = sqlite3.connect(path, timeout=10)
    connection.execute("PRAGMA journal_mode=WAL")
    connection.execute("PRAGMA synchronous=FULL")
    connection.execute("PRAGMA foreign_keys=ON")
    return connection


def initialize(path: Path) -> None:
    connection = connect(path)
    try:
        connection.executescript(SCHEMA)
        connection.commit()
    finally:
        connection.close()


def child_write(path: Path, crash_point: str, token: str) -> None:
    if crash_point == "BEFORE_BEGIN":
        os._exit(71)
    connection = connect(path)
    connection.execute("BEGIN IMMEDIATE")
    connection.execute(
        "INSERT INTO syn_fact(fact_id, tenant_id, payload_hash) VALUES (?, ?, ?)",
        (f"SYN-FACT-{token}", "TENANT_ALPHA", f"hash-{token}"),
    )
    if crash_point == "AFTER_FACT":
        os._exit(72)
    connection.execute(
        "INSERT INTO syn_intent(intent_id, fact_id, state) VALUES (?, ?, 'SYN_PENDING')",
        (f"SYN-INTENT-{token}", f"SYN-FACT-{token}"),
    )
    if crash_point == "AFTER_INTENT":
        os._exit(73)
    connection.execute(
        "INSERT INTO syn_outbox(event_id, fact_id, payload_hash) VALUES (?, ?, ?)",
        (f"SYN-OUTBOX-{token}", f"SYN-FACT-{token}", f"event-hash-{token}"),
    )
    if crash_point == "AFTER_OUTBOX":
        os._exit(74)
    if crash_point == "BEFORE_COMMIT":
        os._exit(75)
    connection.commit()
    if crash_point == "AFTER_COMMIT":
        os._exit(76)
    connection.close()


def table_counts(path: Path) -> tuple[int, int, int, str]:
    connection = connect(path)
    try:
        counts = tuple(
            connection.execute(f"SELECT COUNT(*) FROM {table}").fetchone()[0]
            for table in ("syn_fact", "syn_intent", "syn_outbox")
        )
        integrity = connection.execute("PRAGMA integrity_check").fetchone()[0]
    finally:
        connection.close()
    return counts[0], counts[1], counts[2], integrity


def run_probe() -> ProbeResult:
    fixture_path = FIXTURE_ROOT / "offline-plan.json"
    plan = load_json(fixture_path)
    repetitions = plan["repetitions"]
    crash_points = plan["crashPoints"]
    assertions = 0
    durations_ms: list[float] = []

    with tempfile.TemporaryDirectory(prefix="jshpos-t1-w2-sqlite-") as directory:
        root = Path(directory)
        for crash_point in crash_points:
            for repetition in range(repetitions):
                database = root / f"{crash_point.lower()}-{repetition}.sqlite"
                initialize(database)
                token = f"{crash_point}-{repetition}"
                started = time.perf_counter()
                completed = subprocess.run(
                    [sys.executable, str(Path(__file__).resolve()), "--child", str(database), crash_point, token],
                    check=False,
                    stdout=subprocess.DEVNULL,
                    stderr=subprocess.PIPE,
                )
                durations_ms.append((time.perf_counter() - started) * 1000)
                require(completed.returncode != 0, f"{crash_point} did not terminate the child")
                fact, intent, outbox, integrity = table_counts(database)
                expected = (1, 1, 1) if crash_point == "AFTER_COMMIT" else (0, 0, 0)
                require((fact, intent, outbox) == expected, f"{crash_point}/{repetition}: partial transaction")
                require(integrity == "ok", f"{crash_point}/{repetition}: integrity={integrity}")
                assertions += 2

    ordered = sorted(durations_ms)
    p95 = ordered[min(len(ordered) - 1, int(len(ordered) * 0.95))]
    return ProbeResult(
        requirementId="T1-OFF-001",
        domain="SQLITE_ATOMIC",
        result="PASS",
        assertions=assertions,
        iterations=len(crash_points) * repetitions,
        metrics={
            "crashPoints": len(crash_points),
            "repetitionsPerPoint": repetitions,
            "partialTransactions": 0,
            "integrityFailures": 0,
            "childRecoveryP95Ms": round(p95, 3),
            "journalMode": "WAL",
            "synchronous": "FULL",
        },
        fixtureDigests=[fixture_digest(fixture_path)],
    )


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--child", action="store_true")
    parser.add_argument("args", nargs="*")
    parsed = parser.parse_args()
    if parsed.child:
        if len(parsed.args) != 3:
            raise SystemExit("child requires database, crash point and token")
        child_write(Path(parsed.args[0]), parsed.args[1], parsed.args[2])
        return
    print(run_probe())


if __name__ == "__main__":
    main()
