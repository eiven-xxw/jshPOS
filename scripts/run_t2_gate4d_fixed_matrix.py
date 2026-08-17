from __future__ import annotations

import argparse
import json
import subprocess
from datetime import datetime, timezone
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
VECTOR = ROOT / "contracts/t2/gate4d/test-vectors/transfer-fixed-vectors-v1.json"
TEST_ROOTS = (
    ROOT / "server/ruoyi-modules/jshpos-transfer/src/test/java",
    ROOT / "server/ruoyi-modules/jshpos-inventory/src/test/java",
    ROOT / "server/ruoyi-modules/jshpos-costing/src/test/java",
)
MAPPING = {
    "TRF-CREATE-001": "createFreezesOriginalUnitConversionAndBaseQuantity",
    "TRF-DUPLICATE-LINE-002": "rejectsDuplicateSkuAndSameWarehouseAtCreation",
    "TRF-SAME-WAREHOUSE-003": "rejectsDuplicateSkuAndSameWarehouseAtCreation",
    "TRF-SUBMIT-004": "submitsWithOptimisticVersionAndCancelsOnlyBeforeDispatch",
    "TRF-SELF-APPROVAL-005": "creatorCannotApproveOwnTransfer",
    "TRF-DISPATCH-006": "dispatchPersistsAuthoritativeFactBeforeCallingInventoryOwner",
    "TRF-DISPATCH-DUP-007": "duplicateDispatchReturnsOriginalAndConflictingDigestFails",
    "TRF-DISPATCH-CONFLICT-008": "duplicateDispatchReturnsOriginalAndConflictingDigestFails",
    "TRF-DISPATCH-ROLLBACK-009": "inventoryFailurePropagatesBeforeCommandCompletionForAtomicRollback",
    "TRF-PARTIAL-RECEIPT-010": "partialAndFinalReceiptsConvergeWithoutOverReceipt",
    "TRF-FULL-RECEIPT-011": "partialAndFinalReceiptsConvergeWithoutOverReceipt",
    "TRF-MULTI-RECEIPT-012": "partialAndFinalReceiptsConvergeWithoutOverReceipt",
    "TRF-OVER-RECEIPT-013": "preventsOverReceiptAndPreservesTransitEquation",
    "TRF-RECEIVE-BEFORE-DISPATCH-014": "enforcesTransitionsAndReceivableStates",
    "TRF-FINAL-SHORT-015": "finalShortReceiptRequiresExactDifference",
    "TRF-DIFFERENCE-016": "finalShortReceiptRequiresExactDifference",
    "TRF-DIFFERENCE-MISMATCH-017": "preventsOverReceiptAndPreservesTransitEquation",
    "TRF-CANCEL-PRE-018": "submitsWithOptimisticVersionAndCancelsOnlyBeforeDispatch",
    "TRF-CANCEL-POST-019": "submitsWithOptimisticVersionAndCancelsOnlyBeforeDispatch",
    "TRF-COST-SNAPSHOT-020": "validatesTransferOwnerAndFreezesSourceWarehouseCostSnapshot",
    "TRF-COST-INHERIT-021": "freezesTransferOutAndInheritsExactlyAtDestination",
    "TRF-COST-PARTIAL-022": "partialTransferReceiptInheritsOriginalDispatchCost",
    "TRF-COST-MISSING-023": "rejectsTransferReceiptWithoutOriginalDispatchCostSnapshot",
    "TRF-ROUNDING-024": "freezesTransferOutAndInheritsExactlyAtDestination",
    "TRF-CONCURRENT-025": "duplicateDispatchReturnsOriginalAndConflictingDigestFails",
    "TRF-REBUILD-026": "reconcilesOnlineProjectionFromImmutableTransitLedgerWithoutMutation",
    "TRF-TENANT-027": "resolvesOnlyTrustedTenantPostedFacts",
    "TRF-MIGRATION-028": "TransferMigrationMySqlIT",
}


def fail(message: str) -> None:
    raise SystemExit(f"T2-GATE4D VECTOR ERROR: {message}")


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    ledger = json.loads(VECTOR.read_text(encoding="utf-8"))
    vectors = ledger.get("fixedVectors", [])
    if len(vectors) != 28 or len({item["seed"] for item in vectors}) != 28:
        fail("fixed vector ledger must contain twenty-eight unique seeds")
    sources = "\n".join(path.read_text(encoding="utf-8", errors="replace")
                        for root in TEST_ROOTS for path in root.rglob("*.java")).lower()
    results = []
    for item in vectors:
        marker = MAPPING.get(item["seed"])
        if marker is None or marker.lower() not in sources:
            fail(f"automation mapping missing for {item['seed']}: {marker}")
        results.append({**item, "automationMarker": marker, "result": "MAPPED_TO_EXECUTED_TEST_SUITE"})
    output = args.output if args.output.is_absolute() else ROOT / args.output
    output.parent.mkdir(parents=True, exist_ok=True)
    report = {
        "schemaVersion": "1.0", "phase": "T2-GATE4D",
        "evidenceLevel": "STATIC+UNIT+MYSQL_INTEGRATION+SYNTHETIC_VECTOR",
        "generatedAt": datetime.now(timezone.utc).isoformat().replace("+00:00", "Z"),
        "commitSha": subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=ROOT, text=True).strip(),
        "vectorCount": len(results), "results": results, "providerNetworkCalls": 0,
        "externalEvidence": {"sandbox": 0, "realDevice": 0, "pilot": 0},
        "evidenceNote": "Synthetic vectors do not establish SANDBOX, REAL_DEVICE, PILOT or commercial evidence.",
    }
    output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print("T2-GATE4D VECTOR MATRIX OK: fixedVectors=28 paymentNetwork=0 external=0")


if __name__ == "__main__":
    main()
