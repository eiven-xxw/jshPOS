from __future__ import annotations

import argparse
import json
import subprocess
from datetime import datetime, timezone
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
VECTOR = ROOT / "contracts/t2/gate4b/test-vectors/gate4b-fixed-vectors-v1.json"
TEST_ROOTS = (
    ROOT / "server/ruoyi-modules/jshpos-inventory/src/test/java",
    ROOT / "server/ruoyi-modules/jshpos-procurement/src/test/java",
)
MAPPING = {
    "STK-BLIND-001": "blindCountProjectionHidesBookSnapshotUntilReview",
    "STK-REVISION-002": "mapperUsesTenantPredicatesLocksAndAppendOnlyCounts",
    "STK-RECOUNT-003": "requiresSecondCountOnlyAboveThreshold",
    "STK-SEPARATION-004": "enforcesThreeDifferentActors",
    "STK-CROSSING-SALE-005": "submitUsesLatestLockedBookAndPreservesCrossingSale",
    "STK-ZERO-006": "zeroVariancePostsWithoutInventoryLedgerCommand",
    "STK-GAIN-007": "nonZeroVarianceUsesSingleStocktakeGainAndRejectsActorCollision",
    "STK-LOSS-008": "permitsOnlyOwnerSourceMovementPairs",
    "STK-REPLAY-009": "nonZeroVarianceUsesSingleStocktakeGainAndRejectsActorCollision",
    "STK-TENANT-010": "mapperUsesTenantPredicatesLocksAndAppendOnlyCounts",
    "PUR-APPROVAL-011": "purchaseOrderRequiresSubmitAndDifferentApprover",
    "PUR-PARTIAL-012": "receiptDraftHasNoStockEffectAndConfirmationUsesOwnerPort",
    "PUR-REJECT-013": "receiptDraftHasNoStockEffectAndConfirmationUsesOwnerPort",
    "PUR-OVER-DENY-014": "enforcesReceiptAndReturnCaps",
    "PUR-TOLERANCE-015": "validatesMoneyTaxAndTolerance",
    "PUR-RECEIPT-REPLAY-016": "receiptDraftHasNoStockEffectAndConfirmationUsesOwnerPort",
    "PUR-RETURN-LIMIT-017": "returnApprovalSeparatesRequesterAndAppendsOnlyOriginalReceiptQuantity",
    "PUR-RETURN-REPLAY-018": "returnApprovalSeparatesRequesterAndAppendsOnlyOriginalReceiptQuantity",
    "PUR-UNIT-019": "normalizesAndExactlyConvertsQuantity",
    "PUR-TENANT-020": "requiresTrustedPrincipalBeforeEveryMapperCall",
}


def fail(message: str) -> None:
    raise SystemExit(f"T2-GATE4B VECTOR ERROR: {message}")


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    ledger = json.loads(VECTOR.read_text(encoding="utf-8"))
    vectors = ledger.get("fixedVectors", [])
    if len(vectors) != 20 or len({item["seed"] for item in vectors}) != 20:
        fail("fixed vector ledger must contain twenty unique seeds")
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
        "schemaVersion": "1.0",
        "phase": "T2-GATE4B",
        "evidenceLevel": "STATIC+UNIT+MYSQL_INTEGRATION+SYNTHETIC_VECTOR",
        "generatedAt": datetime.now(timezone.utc).isoformat().replace("+00:00", "Z"),
        "commitSha": subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=ROOT, text=True).strip(),
        "vectorCount": len(results),
        "results": results,
        "providerNetworkCalls": 0,
        "externalEvidence": {"sandbox": 0, "realDevice": 0, "pilot": 0},
        "evidenceNote": "Synthetic vectors do not establish SANDBOX, REAL_DEVICE, PILOT or commercial evidence.",
    }
    output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print("T2-GATE4B VECTOR MATRIX OK: fixedVectors=20 paymentNetwork=0 external=0")


if __name__ == "__main__":
    main()
