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

    gate2_openapi = (ROOT / "contracts" / "t2" / "gate2" / "openapi-pos-order-v1.yaml").read_text(encoding="utf-8")
    for requirement_id in (
        "T2-POS-001", "T2-POS-002", "T2-POS-003", "T2-POS-005", "T2-ORD-001", "T2-ORD-002",
    ):
        if requirement_id not in gate2_openapi:
            raise SystemExit(f"CONTRACT ERROR: Gate 2 OpenAPI missing {requirement_id}")
    for token in ("version: 1.0.0-gate2", "/cash-orders:", "/shifts/{shiftId}/close:", "Idempotency-Key", "additionalProperties: false"):
        if token not in gate2_openapi:
            raise SystemExit(f"CONTRACT ERROR: Gate 2 formal OpenAPI missing {token}")
    if "tenantId:" in gate2_openapi or "tenant_id:" in gate2_openapi:
        raise SystemExit("CONTRACT ERROR: Gate 2 client contract exposes a tenant override")

    sync_design = (ROOT / "contracts" / "t2" / "gate2" / "sync-design-only-v1.yaml").read_text(encoding="utf-8")
    for token in ("requirement: T2-SYN-001", "runtimeAllowed: false", "transportCallsAllowed: 0"):
        if token not in sync_design:
            raise SystemExit(f"CONTRACT ERROR: Gate 2 sync design boundary missing {token}")

    sprint3_openapi = (ROOT / "contracts" / "t2" / "sprint3" / "openapi-pos-sync-v1.yaml").read_text(encoding="utf-8")
    for token in (
        "version: 1.0.0-sprint3", "T2-SYN-001", "/sync/bootstrap:", "/sync/push:",
        "/sync/results/{eventId}:", "/sync/pull:", "/sync/ack:", "X-Device-Id",
        "ACCEPTED_PENDING", "DEVICE_BLOCKED",
    ):
        if token not in sprint3_openapi:
            raise SystemExit(f"CONTRACT ERROR: Sprint S3 formal sync OpenAPI missing {token}")
    if "tenantId:" in sprint3_openapi or "tenant_id:" in sprint3_openapi or "X-Tenant" in sprint3_openapi:
        raise SystemExit("CONTRACT ERROR: Sprint S3 client contract exposes a tenant override")
    payment_prep = (ROOT / "contracts" / "t2" / "sprint3" / "payment-gate3-prep.yaml").read_text(encoding="utf-8")
    for token in ("runtimeAllowed: false", "providerNetworkCallsAllowed: 0", "T2-PAY-002: BLOCKED"):
        if token not in payment_prep:
            raise SystemExit(f"CONTRACT ERROR: Gate 3 payment preparation boundary missing {token}")

    gate4d_openapi = (ROOT / "contracts" / "t2" / "gate4d" / "openapi-transfer-v1.yaml").read_text(encoding="utf-8")
    for token in (
        "version: 1.0.0", "T2-TRF-001", "/inventory/transfers:",
        "/inventory/transfers/{transferId}/transit-reconciliation:",
        "differenceReason:", "TransitReconciliation:", "additionalProperties: false",
    ):
        if token not in gate4d_openapi:
            raise SystemExit(f"CONTRACT ERROR: Gate 4D formal transfer OpenAPI missing {token}")
    if "tenantId:" in gate4d_openapi or "tenant_id:" in gate4d_openapi or "X-Tenant" in gate4d_openapi:
        raise SystemExit("CONTRACT ERROR: Gate 4D client contract exposes a tenant override")
    if "businessDate:" in gate4d_openapi:
        raise SystemExit("CONTRACT ERROR: Gate 4D client contract exposes server-owned businessDate")

    gate5d_openapi = (ROOT / "contracts" / "t2" / "gate5d" / "openapi-reporting-v1.yaml").read_text(encoding="utf-8")
    for token in (
        "version: 1.0.0-gate5d", "T2-RPT-001", "/api/v1/reports/sales-daily:",
        "/api/v1/reports/inventory-cost-daily:", "/api/v1/report-exports:",
        "/api/v1/reporting/rebuilds:", "report:export:approve", "report:repair:manage",
    ):
        if token not in gate5d_openapi:
            raise SystemExit(f"CONTRACT ERROR: Gate 5D formal Reporting OpenAPI missing {token}")
    if "tenantId:" in gate5d_openapi or "tenant_id:" in gate5d_openapi or "X-Tenant" in gate5d_openapi:
        raise SystemExit("CONTRACT ERROR: Gate 5D client contract exposes a tenant override")
    gate5d_events = (ROOT / "contracts" / "t2" / "gate5d" / "reporting-events-v1.yaml").read_text(encoding="utf-8")
    for token in ("tenantAuthority: trusted_context_only", "providerNetworkCallsAllowed: 0", "T2-PAY-002: BLOCKED"):
        if token not in gate5d_events:
            raise SystemExit(f"CONTRACT ERROR: Gate 5D Reporting event boundary missing {token}")

    print(f"CONTRACTS OK: {len(json_contracts)} JSON schemas and T0/T2 OpenAPI contracts")


if __name__ == "__main__":
    try:
        main()
    except (OSError, json.JSONDecodeError) as exc:
        print(f"CONTRACT ERROR: {exc}", file=sys.stderr)
        raise SystemExit(1) from exc
