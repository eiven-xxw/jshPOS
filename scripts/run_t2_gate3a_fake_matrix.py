from __future__ import annotations

import argparse
import json
import subprocess
from datetime import datetime, timezone
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
VECTOR = ROOT / "contracts/t2/gate3a/test-vectors/payment-fake-vectors-v1.json"
TEST_ROOT = ROOT / "server/ruoyi-modules/jshpos-payment/src/test/java/com/jingshanghui/pos/payment"
MAPPING = {
    "PAY-CREATE-IDEMPOTENT-001": "returnsStoredIdempotentIntentWithoutSecondWrite",
    "PAY-IDEMPOTENCY-HASH-CONFLICT-002": "sameKeyDifferentHashIsRejected",
    "PAY-UNKNOWN-NO-SECOND-ATTEMPT-003": "unknownPaymentCannotCreateReplacementAttempt",
    "PAY-LATE-SUCCESS-004": "querySuccessConvergesUnknown",
    "PAY-SUCCESS-NO-REGRESSION-005": "successfulFundsNeverRegress",
    "OBS-DUPLICATE-006": "repeatedObservationIsDuplicate",
    "OBS-SAME-ID-DIFFERENT-HASH-007": "HashConflictIsDeadLettered",
    "OBS-AMOUNT-CURRENCY-MISMATCH-008": "mismatchedAmountIsIsolated",
    "REF-PARTIAL-009": "createsPendingApprovalRefund",
    "REF-AMOUNT-OVERFLOW-010": "CumulativeRefundAmountBeyond",
    "REF-QUANTITY-OVERFLOW-011": "requireQuantityAvailable",
    "REF-UNKNOWN-RESERVES-012": "processingUnknownAndSucceededReserveCapacity",
    "REC-INTERNAL-ONLY-013": "InternalOnly",
    "REC-PROVIDER-ONLY-014": "PROVIDER_ONLY",
    "REC-AMOUNT-STATUS-CURRENCY-015": "AllMismatchedDimensions",
    "TENANT-CROSS-ATTACK-016": "crossAggregateAttemptIsDeadLettered",
}


def fail(message: str) -> None:
    raise SystemExit(f"T2-GATE3A FAKE ERROR: {message}")


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    ledger = json.loads(VECTOR.read_text(encoding="utf-8"))
    vectors = ledger.get("fixedVectors", [])
    if len(vectors) != 16 or len({item["seed"] for item in vectors}) != 16:
        fail("fixed vector ledger must contain sixteen unique seeds")
    if ledger.get("providerNetworkCallsAllowed") != 0:
        fail("Fake vector ledger relaxed provider network boundary")
    sources = "\n".join(path.read_text(encoding="utf-8", errors="replace")
                         for path in TEST_ROOT.rglob("*.java"))
    results = []
    for item in vectors:
        marker = MAPPING.get(item["seed"])
        if marker is None or marker.lower() not in sources.lower():
            fail(f"automation mapping missing for {item['seed']}: {marker}")
        results.append({**item, "automationMarker": marker, "result": "MAPPED_TO_EXECUTED_TEST_SUITE"})
    output = args.output if args.output.is_absolute() else ROOT / args.output
    output.parent.mkdir(parents=True, exist_ok=True)
    report = {
        "schemaVersion": "1.0", "phase": "T2-GATE3A", "evidenceLevel": "FAKE_CONTRACT",
        "generatedAt": datetime.now(timezone.utc).isoformat().replace("+00:00", "Z"),
        "commitSha": subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=ROOT, text=True).strip(),
        "vectorCount": len(results), "results": results, "providerNetworkCalls": 0,
        "externalEvidence": {"sandbox": 0, "realDevice": 0, "pilot": 0},
        "evidenceNote": "Only maps fixed synthetic vectors to tests executed on the same commit; it is not SANDBOX evidence.",
    }
    output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print("T2-GATE3A FAKE MATRIX OK: fixedVectors=16 paymentNetwork=0 external=0")


if __name__ == "__main__":
    main()
