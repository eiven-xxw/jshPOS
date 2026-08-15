from __future__ import annotations

import csv
import sys
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
RTM = ROOT / "docs" / "governance" / "rtm.csv"
REQUIRED_COLUMNS = {
    "requirement_id",
    "phase",
    "domain",
    "priority",
    "source",
    "acceptance",
    "status",
    "implementation",
    "test_evidence",
    "owner",
    "notes",
}
ALLOWED_STATUSES = {
    "DRAFT",
    "READY",
    "IN_PROGRESS",
    "IMPLEMENTED",
    "VERIFIED",
    "ACCEPTED",
    "BLOCKED",
    "DEFERRED",
}


def fail(message: str) -> None:
    print(f"RTM ERROR: {message}", file=sys.stderr)
    raise SystemExit(1)


def main() -> None:
    if not RTM.exists():
        fail(f"missing {RTM.relative_to(ROOT)}")

    with RTM.open(encoding="utf-8-sig", newline="") as handle:
        reader = csv.DictReader(handle)
        columns = set(reader.fieldnames or [])
        if columns != REQUIRED_COLUMNS:
            fail(f"columns mismatch: expected {sorted(REQUIRED_COLUMNS)}, got {sorted(columns)}")

        rows = list(reader)

    if not rows:
        fail("no requirements")

    seen: set[str] = set()
    errors: list[str] = []
    for line, row in enumerate(rows, start=2):
        requirement_id = row["requirement_id"].strip()
        if not requirement_id:
            errors.append(f"line {line}: requirement_id is empty")
        elif requirement_id in seen:
            errors.append(f"line {line}: duplicate {requirement_id}")
        seen.add(requirement_id)

        for field in ("phase", "domain", "priority", "source", "acceptance", "status", "owner"):
            if not row[field].strip():
                errors.append(f"line {line} {requirement_id}: {field} is empty")
        if row["status"].strip() not in ALLOWED_STATUSES:
            errors.append(f"line {line} {requirement_id}: invalid status {row['status']!r}")

    if errors:
        fail("\n".join(errors))

    t0 = [row for row in rows if row["phase"] == "T0"]
    if len(t0) < 10:
        fail("T0 baseline must contain at least 10 traceable requirements")

    print(f"RTM OK: {len(rows)} requirements, {len(t0)} T0 requirements")


if __name__ == "__main__":
    main()
