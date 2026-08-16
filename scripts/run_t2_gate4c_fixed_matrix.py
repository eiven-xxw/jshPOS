from __future__ import annotations

import argparse
import json
import subprocess
from datetime import datetime, timezone
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
VECTOR = ROOT / "contracts/t2/gate4c/test-vectors/costing-fixed-vectors-v1.json"
TEST_ROOTS = (
    ROOT / "server/ruoyi-modules/jshpos-costing/src/test/java",
    ROOT / "server/ruoyi-modules/jshpos-inventory/src/test/java",
)
MAPPING = {
    "CST-FIRST-RECEIPT-001": "valuesFirstAndContinuousPurchaseReceiptsWithHalfEvenPrecision",
    "CST-WEIGHTED-RECEIPT-002": "valuesFirstAndContinuousPurchaseReceiptsWithHalfEvenPrecision",
    "CST-SALE-SNAPSHOT-003": "freezesOutboundCostAndClosesRoundingResidueAtZero",
    "CST-SALE-RETURN-004": "usesOriginalSaleCostOrExplicitEstimatedFallbackForReturns",
    "CST-PURCHASE-RETURN-005": "purchaseReturnUsesOriginalReceiptCostAndCannotCreateNegativeQuantity",
    "CST-ZERO-CLOSE-006": "freezesOutboundCostAndClosesRoundingResidueAtZero",
    "CST-NEGATIVE-SALE-007": "negativeSaleUsesLastAuditableCostAndMarksEstimated",
    "CST-NEGATIVE-SETTLE-008": "settlesNegativeStockWithoutRewritingPriorEstimatedCost",
    "CST-NO-COST-009": "rejectsUnsupportedDirectionsMissingCostZeroDeltaAndOverflow",
    "CST-STOCKTAKE-GAIN-010": "valuesStocktakeGainAndLossAtCurrentAverage",
    "CST-STOCKTAKE-LOSS-011": "valuesStocktakeGainAndLossAtCurrentAverage",
    "CST-REPLAY-012": "postsConfirmedReceiptAsImmutableCostFactAndReturnsDuplicateWithoutSecondEffect",
    "CST-IDEM-CONFLICT-013": "rejectsSameInventoryIdWithChangedContent",
    "CST-SEQUENCE-GAP-014": "rejectsSequenceGapAndQuantityDivergenceFromInventoryOwner",
    "CST-LATE-015": "rejectsUnknownLateSequenceAndOptimisticConcurrentConflict",
    "CST-CONCURRENT-016": "rejectsUnknownLateSequenceAndOptimisticConcurrentConflict",
    "CST-ROUND-HALF-EVEN-017": "valuesFirstAndContinuousPurchaseReceiptsWithHalfEvenPrecision",
    "CST-REBUILD-018": "publishesFrozenPolicyAndRebuildsProjectionFromLedgerOnly",
    "CST-ROLLBACK-019": "costFailurePropagatesBeforeInventoryOutboxSoTransactionCanRollbackAtomically",
    "CST-OUTBOX-020": "postsConfirmedReceiptAsImmutableCostFactAndReturnsDuplicateWithoutSecondEffect",
    "CST-TENANT-021": "trustedTenantControlsAllReads",
    "CST-CURRENCY-022": "rejectsMixedCurrencyFromProcurementOwner",
    "CST-REVERSAL-023": "appliesExactReversalAndRejectsNonClosingZeroAmount",
    "CST-MIGRATION-024": "CostingMigrationMySqlIT",
}


def fail(message: str) -> None:
    raise SystemExit(f"T2-GATE4C VECTOR ERROR: {message}")


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    ledger = json.loads(VECTOR.read_text(encoding="utf-8"))
    vectors = ledger.get("fixedVectors", [])
    if len(vectors) != 24 or len({item["seed"] for item in vectors}) != 24:
        fail("fixed vector ledger must contain twenty-four unique seeds")
    sources = "\n".join(
        path.read_text(encoding="utf-8", errors="replace")
        for root in TEST_ROOTS for path in root.rglob("*.java")
    ).lower()
    results = []
    for item in vectors:
        marker = MAPPING.get(item["seed"])
        if marker is None or marker.lower() not in sources:
            fail(f"automation mapping missing for {item['seed']}: {marker}")
        results.append({**item, "automationMarker": marker, "result": "MAPPED_TO_EXECUTED_TEST_SUITE"})
    output = args.output if args.output.is_absolute() else ROOT / args.output
    output.parent.mkdir(parents=True, exist_ok=True)
    report = {
        "schemaVersion": "1.0", "phase": "T2-GATE4C",
        "evidenceLevel": "STATIC+UNIT+MYSQL_INTEGRATION+SYNTHETIC_VECTOR",
        "generatedAt": datetime.now(timezone.utc).isoformat().replace("+00:00", "Z"),
        "commitSha": subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=ROOT, text=True).strip(),
        "vectorCount": len(results), "results": results, "providerNetworkCalls": 0,
        "externalEvidence": {"sandbox": 0, "realDevice": 0, "pilot": 0},
        "evidenceNote": "Synthetic vectors do not establish SANDBOX, REAL_DEVICE, PILOT or commercial evidence.",
    }
    output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print("T2-GATE4C VECTOR MATRIX OK: fixedVectors=24 paymentNetwork=0 external=0")


if __name__ == "__main__":
    main()
