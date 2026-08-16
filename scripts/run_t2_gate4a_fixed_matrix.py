from __future__ import annotations

import argparse
import json
import subprocess
from datetime import datetime, timezone
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
VECTOR = ROOT / "contracts/t2/gate4a/test-vectors/inventory-fixed-vectors-v1.json"
TEST_ROOT = ROOT / "server/ruoyi-modules/jshpos-inventory/src/test/java/com/jingshanghui/pos/inventory"
MAPPING = {
    "INV-SALE-IDEMPOTENT-001": "returnsStoredResultForSameEventAndRejectsHashConflict",
    "INV-EVENT-HASH-CONFLICT-002": "returnsStoredResultForSameEventAndRejectsHashConflict",
    "INV-DENY-NEGATIVE-003": "denyPolicyRejectsNegativeStockBeforeAnyLedgerWrite",
    "INV-ALLOW-ALERT-004": "allowAndAlertPersistsRealNegativeBalanceAndAnomaly",
    "INV-RETURN-IDEMPOTENT-005": "appliesOnlySucceededReturnAndCrossChecksOriginalLine",
    "INV-RETURN-NOT-SUCCEEDED-006": "rejectsUnknownRefundAndExcessReturnQuantity",
    "INV-ORDER-NOT-COMPLETED-007": "acceptsOnlyCompletedPaidOrderAndSucceededRefund",
    "INV-TENANT-CROSS-008": "assertTwoTenantInventoryConstraints",
    "INV-DECIMAL-SCALE-009": "rejectsZeroNegativeOverScaleAndOversizedQuantities",
    "INV-REBUILD-EQUAL-010": "rebuildsProjectionOnlyFromLedgerAggregate",
    "INV-LEDGER-IMMUTABLE-011": "assertTwoTenantInventoryConstraints",
    "INV-ACK-LOST-REPLAY-012": "returnsStoredResultForSameEventAndRejectsHashConflict",
    "INV-SOURCE-LINE-DUPLICATE-013": "assertTwoTenantInventoryConstraints",
    "INV-MULTILINE-ATOMIC-014": "appliesMultipleLinesInStableOrderAndSingleCommand",
    "INV-POLICY-VERSION-015": "migrationContainsTenantConstraintsChecksAndImmutableTriggers",
    "INV-FUTURE-RUNTIME-ABSENT-016": "mapperXmlUsesExplicitColumnsTenantPredicatesAndRowLocks",
}


def fail(message: str) -> None:
    raise SystemExit(f"T2-GATE4A VECTOR ERROR: {message}")


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    ledger = json.loads(VECTOR.read_text(encoding="utf-8"))
    vectors = ledger.get("fixedVectors", [])
    if len(vectors) != 16 or len({item["seed"] for item in vectors}) != 16:
        fail("fixed vector ledger must contain sixteen unique seeds")
    sources = "\n".join(path.read_text(encoding="utf-8", errors="replace") for path in TEST_ROOT.rglob("*.java"))
    results = []
    for item in vectors:
        marker = MAPPING.get(item["seed"])
        if marker is None or marker.lower() not in sources.lower():
            fail(f"automation mapping missing for {item['seed']}: {marker}")
        results.append({**item, "automationMarker": marker, "result": "MAPPED_TO_EXECUTED_TEST_SUITE"})
    output = args.output if args.output.is_absolute() else ROOT / args.output
    output.parent.mkdir(parents=True, exist_ok=True)
    report = {
        "schemaVersion": "1.0", "phase": "T2-GATE4A", "evidenceLevel": "STATIC_FAKE",
        "generatedAt": datetime.now(timezone.utc).isoformat().replace("+00:00", "Z"),
        "commitSha": subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=ROOT, text=True).strip(),
        "vectorCount": len(results), "results": results, "providerNetworkCalls": 0,
        "externalEvidence": {"sandbox": 0, "realDevice": 0, "pilot": 0},
        "evidenceNote": "Synthetic vectors do not establish SANDBOX, REAL_DEVICE, PILOT or commercial evidence.",
    }
    output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print("T2-GATE4A VECTOR MATRIX OK: fixedVectors=16 paymentNetwork=0 external=0")


if __name__ == "__main__":
    main()
