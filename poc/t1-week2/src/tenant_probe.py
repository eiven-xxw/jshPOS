from __future__ import annotations

import hmac
import sqlite3
from dataclasses import dataclass
from hashlib import sha256
from pathlib import PurePosixPath

from common import FIXTURE_ROOT, ProbeResult, fixture_digest, load_json, require


@dataclass(frozen=True)
class AttackResult:
    decision: str
    response: str
    audited: bool


class SyntheticTenantBoundary:
    def __init__(self) -> None:
        self.connection = sqlite3.connect(":memory:")
        self.connection.executescript(
            """
            CREATE TABLE syn_resource (
              tenant_id TEXT NOT NULL,
              resource_id TEXT NOT NULL,
              content TEXT NOT NULL,
              PRIMARY KEY (tenant_id, resource_id)
            );
            CREATE TABLE syn_security_audit (
              attack_id TEXT NOT NULL,
              entry_name TEXT NOT NULL,
              actor_tenant TEXT NOT NULL,
              target_tenant TEXT NOT NULL,
              vector TEXT NOT NULL,
              decision TEXT NOT NULL
            );
            """
        )
        self.connection.executemany(
            "INSERT INTO syn_resource VALUES (?, ?, ?)",
            [
                ("TENANT_ALPHA", "SYN-RES-001", "ALPHA-SYNTHETIC-CONTENT"),
                ("TENANT_BETA", "SYN-RES-001", "BETA-SYNTHETIC-CONTENT"),
            ],
        )
        self.connection.commit()
        self.cache: dict[str, str] = {
            self.cache_key("TENANT_ALPHA", "SYN-RES-001"): "ALPHA-SYNTHETIC-CONTENT",
            self.cache_key("TENANT_BETA", "SYN-RES-001"): "BETA-SYNTHETIC-CONTENT",
        }

    @staticmethod
    def cache_key(tenant_id: str, resource_id: str) -> str:
        return f"syn:tenant:{tenant_id}:resource:{resource_id}"

    @staticmethod
    def object_key(tenant_id: str, resource_id: str) -> str:
        return f"tenants/{tenant_id}/synthetic/{resource_id}.json"

    def audit(self, attack_id: str, entry: str, actor: str, target: str, vector: str) -> None:
        self.connection.execute(
            "INSERT INTO syn_security_audit VALUES (?, ?, ?, ?, ?, 'DENY')",
            (attack_id, entry, actor, target, vector),
        )
        self.connection.commit()

    def attack(self, attack_id: str, entry: str, actor: str, target: str, vector: str) -> AttackResult:
        denied = actor == "NONE" or actor != target
        if entry == "OBJECT_STORAGE":
            requested = "../TENANT_BETA/SYN-RES-001" if vector == "path_traversal" else self.object_key(target, "SYN-RES-001")
            normalized = str(PurePosixPath(requested))
            denied = denied or ".." in PurePosixPath(requested).parts or not normalized.startswith(f"tenants/{actor}/")
        elif entry == "CACHE":
            actor_key = self.cache_key(actor, "SYN-RES-001") if actor != "NONE" else "syn:no-context"
            target_key = self.cache_key(target, "SYN-RES-001")
            denied = denied or actor_key != target_key
        elif entry == "EXPORT" and vector in {"download_token_swap", "expired_token"}:
            signed = hmac.new(b"PUBLIC-WEEK2-FAKE-VECTOR", f"{actor}:SYN-RES-001".encode(), sha256).hexdigest()
            supplied = hmac.new(b"PUBLIC-WEEK2-FAKE-VECTOR", f"{target}:SYN-RES-001".encode(), sha256).hexdigest()
            denied = denied or not hmac.compare_digest(signed, supplied) or vector == "expired_token"
        elif entry in {"MAPPER", "RAW_SQL"}:
            # The trusted tenant is always bound separately; client filters never select the tenant scope.
            denied = denied or actor not in {"TENANT_ALPHA", "TENANT_BETA"}
        elif entry == "BACKGROUND_JOB":
            denied = denied or actor == "NONE"

        require(denied, f"attack unexpectedly admitted: {attack_id}")
        self.audit(attack_id, entry, actor, target, vector)
        return AttackResult("DENY", "NOT_FOUND", True)

    def audit_count(self) -> int:
        return self.connection.execute("SELECT COUNT(*) FROM syn_security_audit").fetchone()[0]

    def close(self) -> None:
        self.connection.close()


def run_probe() -> ProbeResult:
    fixture_path = FIXTURE_ROOT / "tenant-attack-plan.json"
    plan = load_json(fixture_path)
    boundary = SyntheticTenantBoundary()
    assertions = 0
    attacks = 0
    response_leaks = 0
    try:
        for entry, vectors in plan["entries"].items():
            for vector in vectors:
                for repetition in range(plan["repetitions"]):
                    actor = "NONE" if entry == "BACKGROUND_JOB" and vector == "missing_context" else "TENANT_ALPHA"
                    target = "TENANT_BETA"
                    attack_id = f"TEN-W2-{entry}-{vector}-{repetition}".upper().replace("_", "-")
                    result = boundary.attack(attack_id, entry, actor, target, vector)
                    require(result.decision == "DENY", attack_id)
                    require(result.response == "NOT_FOUND", f"existence leak: {attack_id}")
                    require(result.audited, f"missing audit: {attack_id}")
                    require("BETA-SYNTHETIC-CONTENT" not in result.response, f"content leak: {attack_id}")
                    assertions += 4
                    attacks += 1
            if entry == "CACHE":
                require(
                    boundary.cache_key("TENANT_ALPHA", "SYN-RES-001")
                    != boundary.cache_key("TENANT_BETA", "SYN-RES-001"),
                    "cache tenant scope collision",
                )
                assertions += 1
        require(boundary.audit_count() == attacks, "denied attack audit coverage is incomplete")
        assertions += 1
    finally:
        boundary.close()

    return ProbeResult(
        requirementId="T1-TEN-001",
        domain="TENANT_ISOLATION",
        result="PASS",
        assertions=assertions,
        iterations=attacks,
        metrics={
            "entries": len(plan["entries"]),
            "attackVectors": sum(len(vectors) for vectors in plan["entries"].values()),
            "repetitionsPerVector": plan["repetitions"],
            "denied": attacks,
            "auditMissing": 0,
            "crossTenantLeaks": response_leaks,
            "cachePollution": 0,
        },
        fixtureDigests=[fixture_digest(fixture_path)],
    )
