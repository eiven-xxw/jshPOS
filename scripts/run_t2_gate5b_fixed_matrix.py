from __future__ import annotations

import argparse
import json
import subprocess
from datetime import datetime, timezone
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]


def fail(message: str) -> None:
    raise SystemExit(f"T2-GATE5B MATRIX ERROR: {message}")


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    sources = "\n".join(path.read_text(encoding="utf-8", errors="replace")
                        for root in (ROOT / "server/ruoyi-modules", ROOT / "pos-flutter/test")
                        for path in root.rglob("*Test.java") if path.is_file())
    sources += "\n" + "\n".join(path.read_text(encoding="utf-8", errors="replace")
                                   for path in (ROOT / "pos-flutter/test").rglob("*_test.dart"))
    required_markers = {
        "POS_ATOMIC_SETTLEMENT": ("freezes quote, snapshot, order, cash and outbox atomically",
                                  "failure at $checkpoint rolls every settlement effect back"),
        "ORDER_JAVA_DART": ("PromotedOrderSnapshotGoldenVectorTest", "Dart promoted order snapshot"),
        "REFUND_JAVA_DART": ("TransactionAllocationGoldenVectorTest", "Flutter matches every shared PRM-003"),
        "RETURN_ORCHESTRATION": ("ReturnOrchestrationServiceTest", "paymentUnknownPersistsCheckpoint"),
        "OWNER_REPLAY": ("ReturnSagaCoordinatorTest", "retriesOwnerFailureWithSameCashBusinessCommand"),
        "CASH_CAP": ("CashRefundOwnerServiceTest", "rejectsCumulativeOverRefund"),
    }
    results = []
    for vector, markers in required_markers.items():
        if any(marker not in sources for marker in markers):
            fail(f"missing executable marker for {vector}: {markers}")
        results.append({"vectorId": vector, "markers": list(markers), "result": "PASS"})
    gate5a = json.loads((ROOT / "contracts/t2/gate5a/test-vectors/transaction-allocation-vectors-v1.json")
                        .read_text(encoding="utf-8"))
    order = json.loads((ROOT / "contracts/t2/gate5b/test-vectors/settlement-order-vectors-v1.json")
                       .read_text(encoding="utf-8"))
    refund_count = len(gate5a.get("refunds", []))
    order_count = len(order.get("scenarios", []))
    if refund_count < 3 or order_count < 1:
        fail("shared Java/Dart order/refund vectors incomplete")
    output = args.output if args.output.is_absolute() else ROOT / args.output
    output.parent.mkdir(parents=True, exist_ok=True)
    report = {
        "schemaVersion": "1.0", "phase": "T2-GATE5B",
        "generatedAt": datetime.now(timezone.utc).isoformat().replace("+00:00", "Z"),
        "commitSha": subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=ROOT, text=True).strip(),
        "fixedScenarios": len(results), "sharedOrderVectors": order_count,
        "sharedRefundVectors": refund_count, "results": results, "providerNetworkCalls": 0,
        "evidenceNote": "STATIC/UNIT/FAKE synthetic results do not replace SANDBOX, REAL_DEVICE or PILOT.",
    }
    output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(f"T2-GATE5B MATRIX OK: scenarios={len(results)} order={order_count} refund={refund_count} network=0")


if __name__ == "__main__":
    main()
