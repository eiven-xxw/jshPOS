from __future__ import annotations

import json
import sys
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]


def main() -> None:
    json_contracts = [
        ROOT / "contracts" / "events" / "envelope.schema.json",
        ROOT / "contracts" / "connectors" / "manifest.schema.json",
    ]
    json_contracts.extend(sorted((ROOT / "contracts" / "poc" / "t1").rglob("*.schema.json")))
    json_contracts.extend(sorted((ROOT / "contracts" / "t2").rglob("*.schema.json")))
    schema_ids: set[str] = set()
    for path in json_contracts:
        with path.open(encoding="utf-8") as handle:
            document = json.load(handle)
        if document.get("$schema") != "https://json-schema.org/draft/2020-12/schema":
            raise SystemExit(f"CONTRACT ERROR: {path} is not JSON Schema 2020-12")
        if not document.get("$id"):
            raise SystemExit(f"CONTRACT ERROR: {path} has no $id")
        if document["$id"] in schema_ids:
            raise SystemExit(f"CONTRACT ERROR: duplicate $id {document['$id']}")
        schema_ids.add(document["$id"])

    openapi = (ROOT / "contracts" / "openapi" / "openapi.yaml").read_text(encoding="utf-8")
    for token in ("openapi: 3.1.0", "version: 0.0.0-t0", "/internal/health:"):
        if token not in openapi:
            raise SystemExit(f"CONTRACT ERROR: OpenAPI missing {token}")

    gate0_openapi = (ROOT / "contracts" / "t2" / "gate0" / "openapi-foundation-v1.yaml").read_text(encoding="utf-8")
    for token in ("version: 1.0.0-gate0", "/org-units:", "/audit-events:", "SuccessEnvelope:"):
        if token not in gate0_openapi:
            raise SystemExit(f"CONTRACT ERROR: Gate 0 OpenAPI missing {token}")
    gate1_openapi = (ROOT / "contracts" / "t2" / "gate1" / "openapi-product-price-v1.yaml").read_text(encoding="utf-8")
    for requirement_id in (
        "T2-PRD-001", "T2-PRD-002", "T2-PRD-003", "T2-PRD-004",
        "T2-PRC-001", "T2-PRC-002", "T2-DPK-001",
    ):
        if requirement_id not in gate1_openapi:
            raise SystemExit(f"CONTRACT ERROR: Gate 1 OpenAPI missing {requirement_id}")
    if "tenantId:" in gate0_openapi or "tenantId:" in gate1_openapi or "tenant_id:" in gate1_openapi:
        raise SystemExit("CONTRACT ERROR: client contract must not expose tenant override fields")
    for token in ("version: 1.0.0-gate1", "additionalProperties: false", "amountMinor:", "/imports/{batchId}/rollback:"):
        if token not in gate1_openapi:
            raise SystemExit(f"CONTRACT ERROR: Gate 1 formal OpenAPI missing {token}")

    print(f"CONTRACTS OK: {len(json_contracts)} JSON schemas and T0/T2 OpenAPI contracts")


if __name__ == "__main__":
    try:
        main()
    except (OSError, json.JSONDecodeError) as exc:
        print(f"CONTRACT ERROR: {exc}", file=sys.stderr)
        raise SystemExit(1) from exc
