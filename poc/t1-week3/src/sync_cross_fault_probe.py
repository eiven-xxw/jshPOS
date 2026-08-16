from __future__ import annotations

import random
import sqlite3
import tempfile
from pathlib import Path

from common import FIXTURE_ROOT, ProbeResult, canonical_hash, fixture_digest, load_json, require


FIXTURE = FIXTURE_ROOT / "sync-cross-fault-plan.json"


def connect(path: Path) -> sqlite3.Connection:
    connection = sqlite3.connect(path)
    connection.execute("PRAGMA journal_mode=WAL")
    connection.execute("PRAGMA synchronous=FULL")
    connection.execute("PRAGMA foreign_keys=ON")
    return connection


def create_schema(connection: sqlite3.Connection) -> None:
    connection.executescript(
        """
        CREATE TABLE syn_outbox (
            event_id TEXT PRIMARY KEY,
            sequence_no INTEGER NOT NULL UNIQUE,
            payload_hash TEXT NOT NULL,
            state TEXT NOT NULL CHECK (state IN ('PENDING','INFLIGHT','ACKED')),
            attempts INTEGER NOT NULL DEFAULT 0
        );
        CREATE TABLE syn_server_inbox (
            event_id TEXT PRIMARY KEY,
            payload_hash TEXT NOT NULL,
            sequence_no INTEGER NOT NULL UNIQUE
        );
        CREATE TABLE syn_server_effect (
            event_id TEXT PRIMARY KEY,
            effect_count INTEGER NOT NULL CHECK (effect_count = 1)
        );
        CREATE TABLE syn_cursor (
            singleton INTEGER PRIMARY KEY CHECK (singleton = 1),
            contiguous_sequence INTEGER NOT NULL
        );
        INSERT INTO syn_cursor(singleton, contiguous_sequence) VALUES (1, 0);
        """
    )
    connection.commit()


def seed_events(connection: sqlite3.Connection, count: int, seed: int) -> None:
    rows = []
    for sequence in range(1, count + 1):
        event_id = f"SYN-{seed}-{sequence:06d}"
        payload_hash = canonical_hash({"synthetic": True, "seed": seed, "sequence": sequence})
        rows.append((event_id, sequence, payload_hash, "PENDING"))
    connection.executemany(
        "INSERT INTO syn_outbox(event_id,sequence_no,payload_hash,state) VALUES (?,?,?,?)",
        rows,
    )
    connection.commit()


def recover_inflight(connection: sqlite3.Connection) -> int:
    changed = connection.execute("UPDATE syn_outbox SET state='PENDING' WHERE state='INFLIGHT'").rowcount
    connection.commit()
    return changed


def receive_server(
    connection: sqlite3.Connection,
    event_id: str,
    sequence: int,
    payload_hash: str,
) -> bool:
    existing = connection.execute(
        "SELECT payload_hash FROM syn_server_inbox WHERE event_id=?", (event_id,)
    ).fetchone()
    if existing:
        require(existing[0] == payload_hash, "same event id arrived with a conflicting payload")
        return False
    with connection:
        connection.execute(
            "INSERT INTO syn_server_inbox(event_id,payload_hash,sequence_no) VALUES (?,?,?)",
            (event_id, payload_hash, sequence),
        )
        connection.execute(
            "INSERT INTO syn_server_effect(event_id,effect_count) VALUES (?,1)", (event_id,)
        )
    return True


def update_cursor(connection: sqlite3.Connection) -> None:
    current = connection.execute(
        "SELECT contiguous_sequence FROM syn_cursor WHERE singleton=1"
    ).fetchone()[0]
    while connection.execute(
        "SELECT 1 FROM syn_outbox WHERE sequence_no=? AND state='ACKED'", (current + 1,)
    ).fetchone():
        current += 1
    connection.execute(
        "UPDATE syn_cursor SET contiguous_sequence=? WHERE singleton=1", (current,)
    )
    connection.commit()


def deliver(connection: sqlite3.Connection, row: tuple[str, int, str], fault: str) -> tuple[int, int]:
    event_id, sequence, payload_hash = row
    with connection:
        changed = connection.execute(
            "UPDATE syn_outbox SET state='INFLIGHT', attempts=attempts+1 "
            "WHERE event_id=? AND state='PENDING'",
            (event_id,),
        ).rowcount
    if not changed:
        return 0, 0
    if fault == "AFTER_CLAIM_BEFORE_SEND":
        return 1, 0

    accepted = receive_server(connection, event_id, sequence, payload_hash)
    duplicates = 0 if accepted else 1
    if fault == "DUPLICATE_DELIVERY":
        receive_server(connection, event_id, sequence, payload_hash)
        duplicates += 1
    if fault in {"SERVER_ACCEPTED_ACK_LOST", "AFTER_ACK_BEFORE_LOCAL_COMMIT"}:
        return 1, duplicates

    with connection:
        connection.execute("UPDATE syn_outbox SET state='ACKED' WHERE event_id=?", (event_id,))
    return 0, duplicates


def run_seed(seed: int, plan: dict[str, object]) -> dict[str, int]:
    event_count = int(plan["eventCount"])
    faults = list(plan["faults"])
    rng = random.Random(seed)
    order = list(range(1, event_count + 1))
    rng.shuffle(order)
    restarts = 0
    duplicate_deliveries = 0
    restart_due = False

    with tempfile.TemporaryDirectory(prefix="jshpos-w3-sync-") as directory:
        db_path = Path(directory) / "synthetic-sync.sqlite"
        connection = connect(db_path)
        create_schema(connection)
        seed_events(connection, event_count, seed)

        for index, sequence in enumerate(order):
            row = connection.execute(
                "SELECT event_id,sequence_no,payload_hash FROM syn_outbox WHERE sequence_no=?",
                (sequence,),
            ).fetchone()
            fault = faults[index % len(faults)]
            pending_restart, duplicates = deliver(connection, row, fault)
            duplicate_deliveries += duplicates
            restart_due = restart_due or bool(pending_restart) or fault == "PROCESS_RESTART"
            batch_boundary = (index + 1) % int(plan["batchSize"]) == 0 or index + 1 == len(order)
            if restart_due and batch_boundary:
                connection.close()
                connection = connect(db_path)
                recover_inflight(connection)
                restarts += 1
                restart_due = False

        rounds = 0
        while True:
            recover_inflight(connection)
            pending = connection.execute(
                "SELECT event_id,sequence_no,payload_hash FROM syn_outbox "
                "WHERE state='PENDING' ORDER BY sequence_no LIMIT ?",
                (int(plan["batchSize"]),),
            ).fetchall()
            if not pending:
                break
            rng.shuffle(pending)
            for row in pending:
                _, duplicates = deliver(connection, row, "DUPLICATE_DELIVERY")
                duplicate_deliveries += duplicates
            rounds += 1
            require(rounds <= int(plan["maxDrainRounds"]), "backlog did not drain within the limit")

        update_cursor(connection)
        acked = connection.execute("SELECT COUNT(*) FROM syn_outbox WHERE state='ACKED'").fetchone()[0]
        server_inbox = connection.execute("SELECT COUNT(*) FROM syn_server_inbox").fetchone()[0]
        effects = connection.execute("SELECT COUNT(*) FROM syn_server_effect").fetchone()[0]
        bad_effects = connection.execute("SELECT COUNT(*) FROM syn_server_effect WHERE effect_count<>1").fetchone()[0]
        cursor = connection.execute("SELECT contiguous_sequence FROM syn_cursor WHERE singleton=1").fetchone()[0]
        max_attempts = connection.execute("SELECT MAX(attempts) FROM syn_outbox").fetchone()[0]
        pending = connection.execute("SELECT COUNT(*) FROM syn_outbox WHERE state<>'ACKED'").fetchone()[0]
        integrity = connection.execute("PRAGMA integrity_check").fetchone()[0]
        connection.close()

    require(acked == event_count, f"seed {seed}: local outbox did not converge")
    require(server_inbox == event_count, f"seed {seed}: server inbox lost events")
    require(effects == event_count and bad_effects == 0, f"seed {seed}: duplicate business effects")
    require(cursor == event_count, f"seed {seed}: cursor did not recover")
    require(pending == 0, f"seed {seed}: pending backlog remains")
    require(max_attempts >= 2, f"seed {seed}: retry path was not exercised")
    require(restarts > 0 and duplicate_deliveries > 0, f"seed {seed}: restart/duplicate path missing")
    require(integrity == "ok", f"seed {seed}: SQLite integrity failure")
    return {
        "events": event_count,
        "restarts": restarts,
        "duplicates": duplicate_deliveries,
        "drainRounds": rounds,
        "maxAttempts": max_attempts,
    }


def run_probe() -> list[ProbeResult]:
    plan = load_json(FIXTURE)
    metrics = [run_seed(seed, plan) for seed in plan["seeds"]]
    seed_count = len(metrics)
    total_events = sum(item["events"] for item in metrics)
    total_restarts = sum(item["restarts"] for item in metrics)
    total_duplicates = sum(item["duplicates"] for item in metrics)
    shared = {
        "uniqueEvents": total_events,
        "restarts": total_restarts,
        "duplicateDeliveries": total_duplicates,
        "lostEvents": 0,
        "duplicateEffects": 0,
        "remainingBacklog": 0,
        "maxDrainRounds": max(item["drainRounds"] for item in metrics),
        "failedSeeds": 0,
    }
    digest = [fixture_digest(FIXTURE)]
    return [
        ProbeResult("T1-OFF-001", "OUTBOX_RECOVERY", "PASS", seed_count * 8, total_events, shared, digest),
        ProbeResult("T1-SYN-001", "INBOX_OUTBOX_CONVERGENCE", "PASS", seed_count * 8, total_events + total_duplicates, shared, digest),
    ]
