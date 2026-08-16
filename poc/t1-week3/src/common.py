from __future__ import annotations

import hashlib
import json
import subprocess
from dataclasses import asdict, dataclass
from datetime import datetime, timezone
from pathlib import Path
from typing import Any


ROOT = Path(__file__).resolve().parents[3]
POC_ROOT = ROOT / "poc" / "t1-week3"
FIXTURE_ROOT = POC_ROOT / "fixtures"
BASELINE_TAG = "t0-baseline-2026-08-16"


@dataclass(frozen=True)
class ProbeResult:
    requirementId: str
    domain: str
    result: str
    assertions: int
    iterations: int
    metrics: dict[str, Any]
    fixtureDigests: list[str]


def load_json(path: Path) -> dict[str, Any]:
    with path.open(encoding="utf-8") as handle:
        return json.load(handle)


def sha256_file(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def fixture_digest(path: Path) -> str:
    return "sha256:" + sha256_file(path)


def canonical_hash(value: Any) -> str:
    encoded = json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":")).encode("utf-8")
    return hashlib.sha256(encoded).hexdigest()


def require(condition: bool, message: str) -> None:
    if not condition:
        raise AssertionError(message)


def current_commit() -> str:
    completed = subprocess.run(
        ["git", "rev-parse", "HEAD"],
        cwd=ROOT,
        check=True,
        capture_output=True,
        text=True,
        encoding="utf-8",
    )
    return completed.stdout.strip()


def failed_seed_summary() -> dict[str, int]:
    ledger = load_json(FIXTURE_ROOT / "failed-seed-ledger.json")
    observed = set(ledger["observedFailedSeeds"])
    fixed = set(ledger["fixedFailedSeeds"])
    return {
        "observed": len(observed),
        "fixed": len(fixed),
        "untracked": len(observed - fixed),
    }


def build_evidence(results: list[ProbeResult], evidence_level: str, limitations: list[str]) -> dict[str, Any]:
    return {
        "schemaVersion": "3.0",
        "phase": "T1-WEEK3",
        "scope": "INTERNAL_SYNTHETIC_CROSS_FAULT_ONLY",
        "evidenceLevel": evidence_level,
        "generatedAt": datetime.now(timezone.utc).isoformat().replace("+00:00", "Z"),
        "baselineTag": BASELINE_TAG,
        "commitSha": current_commit(),
        "results": [asdict(result) for result in results],
        "failedSeedSummary": failed_seed_summary(),
        "limitations": limitations,
    }
