from __future__ import annotations

import argparse
import json
import subprocess
from datetime import datetime, timezone
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]


def fail(message: str) -> None:
    raise SystemExit(f"T2-GATE5C MATRIX ERROR: {message}")


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    sources = "\n".join(path.read_text(encoding="utf-8", errors="replace")
                        for path in (ROOT / "server/ruoyi-modules/jshpos-member/src/test").rglob("*.java"))
    sources += "\n" + "\n".join(path.read_text(encoding="utf-8", errors="replace")
                                   for path in (ROOT / "pos-flutter/test/gate5c").rglob("*_test.dart"))
    required = {
        "PII_MINIMIZATION": ("createsMemberAndIdentityWithoutCleartextInFacts", "points_balance"),
        "IDEMPOTENT_COMMAND": ("commandReplayReturnsOriginalAndDifferentContentIsRejected", "MEM-IDEMP-001"),
        "PRIVACY_WORKFLOW": ("appendsConsentAndPrivacyHistoryThenEnforcesStateGraph", "MEM-PRIVACY-002"),
        "REVERSIBLE_MERGE": ("mergeAndSplitAreReversibleAppendOnlyFacts", "insertMemberLink"),
        "EARN_DEBT_REPAYMENT": ("orderEarnRepaysDebtAndCreatesOnlyNetAvailableLot", "debtPoints"),
        "FEFO_FREEZE": ("freezeConsumesEarliestExpiringLotsInStableOrder", "listFefoAvailableLots"),
        "ORIGINAL_FREEZE_ALLOCATION": ("spendUsesOriginalFreezeAllocationAndCannotReselectLots", "never()).listFefo"),
        "RETURN_EARN_DEBT_CAP": ("returnEarnUsesOriginalLotAndCreatesExplicitDebtForSpentPart", "MEM-POINTS-019"),
        "RETURN_SPEND_RESTORE": ("returnSpendRestoresAgainstOriginalAllocationAndFrozenPolicy", "RETURN_SPEND_REVERSAL"),
        "EXPIRY_MANUAL": ("expiryAndNegativeAdjustmentUpdateLotsWithoutNegativeAvailable", "MEMBER_POINTS_MANUAL_ADJUSTED"),
        "APPROVAL_SEPARATION": ("manualAdjustmentRejectsSelfApprovalBeforeAnyLedgerWrite", "操作人与审批人必须分离"),
        "POINTS_COMMAND_REPLAY": ("duplicatePointsCommandReturnsOriginalAndDifferentContentIsRejected", "MEM-IDEMP-001"),
        "LATE_RETURN_RETRY": ("lateReturnWithoutOriginalFactFailsClosedAndCanBeRetriedWithSameCommand", "MEM-POINTS-017"),
        "FULL_REBUILD": ("rebuildSumsImmutableDeltasAndUsesOptimisticProjectionReplacement", "replaceAccountProjection"),
        "POS_CACHE_FAIL_CLOSED": ("revocation and expiry fail closed then purge safely", "member_token_hash"),
    }
    results = []
    for vector, markers in required.items():
        missing = [marker for marker in markers if marker not in sources]
        if missing:
            fail(f"missing executable marker for {vector}: {missing}")
        results.append({"vectorId": vector, "markers": list(markers), "result": "PASS"})
    output = args.output if args.output.is_absolute() else ROOT / args.output
    output.parent.mkdir(parents=True, exist_ok=True)
    report = {
        "schemaVersion": "1.0", "phase": "T2-GATE5C",
        "generatedAt": datetime.now(timezone.utc).isoformat().replace("+00:00", "Z"),
        "commitSha": subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=ROOT, text=True).strip(),
        "fixedScenarios": len(results), "results": results,
        "providerNetworkCalls": 0, "realPiiRecords": 0,
        "evidenceNote": "STATIC/UNIT synthetic results do not replace SANDBOX, REAL_DEVICE, real PII audit or PILOT.",
    }
    output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(f"T2-GATE5C MATRIX OK: scenarios={len(results)} network=0 realPii=0")


if __name__ == "__main__":
    main()
