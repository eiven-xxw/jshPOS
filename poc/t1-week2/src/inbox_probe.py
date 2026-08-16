from __future__ import annotations

import argparse
import hashlib
import json
import os
import random
import sqlite3
import subprocess
import sys
import tempfile
import time
from pathlib import Path
from typing import Any

from common import FIXTURE_ROOT, ProbeResult, canonical_hash, fixture_digest, load_json, require


SCHEMA = """
CREATE TABLE syn_inbox (
  tenant_id TEXT NOT NULL,
  event_id TEXT NOT NULL,
  sequence_no INTEGER NOT NULL,
  payload_hash TEXT NOT NULL,
  applied INTEGER NOT NULL DEFAULT 0 CHECK (applied IN (0, 1)),
  PRIMARY KEY (tenant_id, event_id),
  UNIQUE (tenant_id, sequence_no)
);
CREATE TABLE syn_effect (
  tenant_id TEXT NOT NULL,
  event_id TEXT NOT NULL,
  effect_hash TEXT NOT NULL,
  PRIMARY KEY (tenant_id, event_id)
);
CREATE TABLE syn_cursor (
  tenant_id TEXT PRIMARY KEY,
  cursor_value INTEGER NOT NULL
);
CREATE TABLE syn_conflict_audit (
  tenant_id TEXT NOT NULL,
  event_id TEXT NOT NULL,
  original_hash TEXT NOT NULL,
  conflicting_hash TEXT NOT NULL,
  UNIQUE (tenant_id, event_id, conflicting_hash)
);
"""


def connect(target: str | Path) -> sqlite3.Connection:
    connection = sqlite3.connect(target, timeout=10)
    connection.execute("PRAGMA foreign_keys=ON")
    return connection


def initialize(connection: sqlite3.Connection) -> None:
    connection.executescript(SCHEMA)
    connection.execute("INSERT INTO syn_cursor(tenant_id, cursor_value) VALUES ('TENANT_ALPHA', 0)")
    connection.commit()


def event(sequence: int, payload_suffix: str = "") -> dict[str, Any]:
    return {
        "eventId": f"SYN-W2-EVT-{sequence:08d}",
        "tenantId": "TENANT_ALPHA",
        "terminalId": "SYN-TERM-ALPHA",
        "sequence": sequence,
        "kind": "SYNTHETIC_FACT",
        "payload": {"sequence": sequence, "value": f"SYN-{sequence:08d}{payload_suffix}"},
        "synthetic": True,
    }


class InboxStore:
    def __init__(self, connection: sqlite3.Connection) -> None:
        self.connection = connection

    def deliver_many(self, events: list[dict[str, Any]], batch_size: int = 500) -> None:
        for offset in range(0, len(events), batch_size):
            self.connection.execute("BEGIN")
            for item in events[offset:offset + batch_size]:
                self._deliver_one(item)
            self.connection.commit()

    def _deliver_one(self, item: dict[str, Any]) -> None:
        require(item["synthetic"] is True, "non-synthetic event rejected")
        require(item["tenantId"] == "TENANT_ALPHA", "untrusted tenant rejected")
        payload_hash = canonical_hash(item["payload"])
        existing = self.connection.execute(
            "SELECT payload_hash FROM syn_inbox WHERE tenant_id=? AND event_id=?",
            (item["tenantId"], item["eventId"]),
        ).fetchone()
        if existing:
            if existing[0] != payload_hash:
                self.connection.execute(
                    "INSERT OR IGNORE INTO syn_conflict_audit VALUES (?, ?, ?, ?)",
                    (item["tenantId"], item["eventId"], existing[0], payload_hash),
                )
            return
        self.connection.execute(
            "INSERT INTO syn_inbox(tenant_id,event_id,sequence_no,payload_hash) VALUES (?,?,?,?)",
            (item["tenantId"], item["eventId"], item["sequence"], payload_hash),
        )
        self._drain_contiguous(item["tenantId"])

    def _drain_contiguous(self, tenant_id: str) -> None:
        cursor = self.cursor(tenant_id)
        while True:
            row = self.connection.execute(
                "SELECT event_id,payload_hash FROM syn_inbox WHERE tenant_id=? AND sequence_no=? AND applied=0",
                (tenant_id, cursor + 1),
            ).fetchone()
            if row is None:
                break
            self.connection.execute(
                "INSERT INTO syn_effect(tenant_id,event_id,effect_hash) VALUES (?,?,?)",
                (tenant_id, row[0], row[1]),
            )
            self.connection.execute(
                "UPDATE syn_inbox SET applied=1 WHERE tenant_id=? AND event_id=?",
                (tenant_id, row[0]),
            )
            cursor += 1
            self.connection.execute(
                "UPDATE syn_cursor SET cursor_value=? WHERE tenant_id=? AND cursor_value < ?",
                (cursor, tenant_id, cursor),
            )

    def cursor(self, tenant_id: str = "TENANT_ALPHA") -> int:
        return self.connection.execute(
            "SELECT cursor_value FROM syn_cursor WHERE tenant_id=?", (tenant_id,)
        ).fetchone()[0]

    def recover_cursor(self, tenant_id: str = "TENANT_ALPHA") -> int:
        sequences = {
            row[0]
            for row in self.connection.execute(
                "SELECT sequence_no FROM syn_inbox WHERE tenant_id=? AND applied=1", (tenant_id,)
            )
        }
        recovered = 0
        while recovered + 1 in sequences:
            recovered += 1
        self.connection.execute(
            "UPDATE syn_cursor SET cursor_value=MAX(cursor_value, ?) WHERE tenant_id=?",
            (recovered, tenant_id),
        )
        self.connection.commit()
        return self.cursor(tenant_id)


def child_cursor_crash(path: Path, crash_point: str) -> None:
    connection = connect(path)
    item = event(1)
    payload_hash = canonical_hash(item["payload"])
    connection.execute("BEGIN IMMEDIATE")
    connection.execute(
        "INSERT INTO syn_inbox(tenant_id,event_id,sequence_no,payload_hash) VALUES (?,?,?,?)",
        (item["tenantId"], item["eventId"], item["sequence"], payload_hash),
    )
    if crash_point == "AFTER_INBOX_BEFORE_EFFECT":
        os._exit(81)
    connection.execute(
        "INSERT INTO syn_effect(tenant_id,event_id,effect_hash) VALUES (?,?,?)",
        (item["tenantId"], item["eventId"], payload_hash),
    )
    connection.execute("UPDATE syn_inbox SET applied=1 WHERE tenant_id=? AND event_id=?", (item["tenantId"], item["eventId"]))
    if crash_point == "AFTER_EFFECT_BEFORE_CURSOR":
        os._exit(82)
    connection.execute("UPDATE syn_cursor SET cursor_value=1 WHERE tenant_id='TENANT_ALPHA'")
    if crash_point == "AFTER_CURSOR_BEFORE_COMMIT":
        os._exit(83)
    connection.commit()


def run_probe() -> ProbeResult:
    fixture_path = FIXTURE_ROOT / "inbox-plan.json"
    plan = load_json(fixture_path)
    event_count = plan["eventCount"]
    assertions = 0
    total_deliveries = 0
    total_conflicts = 0
    seed_durations: list[float] = []

    for seed in plan["seeds"]:
        connection = connect(":memory:")
        initialize(connection)
        store = InboxStore(connection)
        rng = random.Random(seed)
        deliveries: list[dict[str, Any]] = []
        for sequence in range(1, event_count + 1):
            item = event(sequence)
            copies = rng.randint(plan["duplicateCopiesMin"], plan["duplicateCopiesMax"])
            deliveries.extend([item] * copies)
        rng.shuffle(deliveries)
        started = time.perf_counter()
        store.deliver_many(deliveries)
        conflicts = [event(sequence, "-CONFLICT") for sequence in range(1, plan["conflictsPerSeed"] + 1)]
        store.deliver_many(conflicts)
        seed_durations.append(time.perf_counter() - started)
        total_deliveries += len(deliveries) + len(conflicts)

        inbox_count = connection.execute("SELECT COUNT(*) FROM syn_inbox").fetchone()[0]
        effect_count = connection.execute("SELECT COUNT(*) FROM syn_effect").fetchone()[0]
        conflict_count = connection.execute("SELECT COUNT(*) FROM syn_conflict_audit").fetchone()[0]
        total_conflicts += conflict_count
        require(inbox_count == event_count, f"seed {seed}: inbox gap")
        require(effect_count == event_count, f"seed {seed}: duplicate or lost effect")
        require(store.cursor() == event_count, f"seed {seed}: cursor did not converge")
        require(conflict_count == plan["conflictsPerSeed"], f"seed {seed}: conflicts not detected")
        connection.execute("UPDATE syn_cursor SET cursor_value=? WHERE tenant_id='TENANT_ALPHA'", (event_count - 10,))
        connection.commit()
        require(store.recover_cursor() == event_count, f"seed {seed}: stale cursor recovery failed")
        connection.execute("UPDATE syn_cursor SET cursor_value=1 WHERE tenant_id='TENANT_ALPHA' AND cursor_value < 1")
        require(store.cursor() == event_count, f"seed {seed}: cursor regressed")
        assertions += 6
        connection.close()

    crash_iterations = 0
    with tempfile.TemporaryDirectory(prefix="jshpos-t1-w2-inbox-") as directory:
        for crash_point in plan["cursorCrashPoints"]:
            for repetition in range(20):
                database = Path(directory) / f"{crash_point.lower()}-{repetition}.sqlite"
                connection = connect(database)
                initialize(connection)
                connection.close()
                completed = subprocess.run(
                    [sys.executable, str(Path(__file__).resolve()), "--child", str(database), crash_point],
                    check=False,
                    stdout=subprocess.DEVNULL,
                    stderr=subprocess.PIPE,
                )
                require(completed.returncode != 0, f"{crash_point}: child did not stop")
                connection = connect(database)
                store = InboxStore(connection)
                require(connection.execute("SELECT COUNT(*) FROM syn_inbox").fetchone()[0] == 0, "partial inbox commit")
                require(connection.execute("SELECT COUNT(*) FROM syn_effect").fetchone()[0] == 0, "partial effect commit")
                require(store.cursor() == 0, "partial cursor commit")
                store.deliver_many([event(1)])
                require(store.cursor() == 1, "resume did not converge")
                require(connection.execute("SELECT COUNT(*) FROM syn_effect").fetchone()[0] == 1, "resume duplicated effect")
                assertions += 6
                crash_iterations += 1
                connection.close()

    return ProbeResult(
        requirementId="T1-SYN-001",
        domain="INBOX_SYNC",
        result="PASS",
        assertions=assertions,
        iterations=event_count * len(plan["seeds"]) + crash_iterations,
        metrics={
            "uniqueEvents": event_count * len(plan["seeds"]),
            "seeds": len(plan["seeds"]),
            "deliveriesIncludingDuplicatesAndConflicts": total_deliveries,
            "conflictsDetected": total_conflicts,
            "lostEvents": 0,
            "duplicateEffects": 0,
            "cursorCrashIterations": crash_iterations,
            "maxSeedDurationSeconds": round(max(seed_durations), 3),
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
            raise SystemExit("child requires database and crash point")
        child_cursor_crash(Path(parsed.args[0]), parsed.args[1])
        return
    print(run_probe())


if __name__ == "__main__":
    main()
