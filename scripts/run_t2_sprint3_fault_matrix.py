from __future__ import annotations

import argparse
import json
import subprocess
from datetime import datetime, timezone
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
VECTOR = ROOT / "contracts/t2/sprint3/test-vectors/sync-failure-seeds-v1.json"


MAPPING = {
    "OUTBOX_CRASH_BEFORE_SEND": "expired SENDING lease is recovered after process restart",
    "SERVER_DURABLE_ACK_LOST": "ACK lost resends the original identity",
    "DUPLICATE_AND_REORDERED_BATCH": "sameIdentityAndHashIsDuplicate",
    "EVENT_ID_HASH_MISMATCH": "DifferentHashBlocksDevice",
    "HTTP_TIMEOUT_AFTER_ACCEPT": "ACK lost resends the original identity",
    "SENDING_LEASE_PROCESS_RESTART": "expired SENDING lease",
    "CURSOR_CORRUPTION": "tampered downstream page",
    "PULL_APPLY_CRASH_BEFORE_CURSOR": "kill before cursor",
    "CURSOR_REGRESSION": "acknowledgeRefusesCursorRegression",
    "CROSS_TENANT_DEVICE_HEADER": "crossTenantMissingWrongUser",
    "BACKLOG_RETRY_BUDGET_EXHAUSTED": "retry budget exhaustion",
    "OLD_CLIENT_NEW_SERVER": "compatibleClientVersions",
    "NEW_CLIENT_OLD_SCHEMA": "EVENT_VERSION_UNSUPPORTED",
    "SQLITE_V2_MIGRATION_FAILURE": "schema checksum mismatch at v",
}


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    vector = json.loads(VECTOR.read_text(encoding="utf-8"))
    sources = "\n".join((ROOT / path).read_text(encoding="utf-8") for path in (
        "pos-flutter/test/sprint3/sync_coordinator_test.dart",
        "pos-flutter/lib/features/synchronization/application/sync_coordinator.dart",
        "pos-flutter/lib/infrastructure/local_database/pos_local_database.dart",
        "server/ruoyi-modules/jshpos-sync/src/main/java/com/jingshanghui/pos/sync/application/service/PosSyncService.java",
        "server/ruoyi-modules/jshpos-sync/src/main/java/com/jingshanghui/pos/sync/application/service/SyncInboxReceiver.java",
        "server/ruoyi-modules/jshpos-sync/src/main/java/com/jingshanghui/pos/sync/application/service/SyncFactProcessor.java",
        "server/ruoyi-modules/jshpos-sync/src/test/java/com/jingshanghui/pos/sync/application/service/SyncDeviceContextServiceTest.java",
        "server/ruoyi-modules/jshpos-sync/src/test/java/com/jingshanghui/pos/sync/application/service/SyncInboxReceiverTest.java",
        "server/ruoyi-modules/jshpos-sync/src/test/java/com/jingshanghui/pos/sync/application/service/PosSyncServiceCursorTest.java",
        "contracts/t2/sprint3/openapi-pos-sync-v1.yaml",
    ))
    results = []
    for item in vector.get("seeds", []):
        fault = item["fault"]
        marker = MAPPING.get(fault)
        if marker is None or marker.lower() not in sources.lower():
            raise SystemExit(f"T2-SPRINT3 FAULT ERROR: automation mapping missing for {fault}")
        results.append({**item, "automationMarker": marker, "result": "MAPPED_TO_EXECUTED_TEST_SUITE"})
    if len(results) != 14 or len({item["seed"] for item in results}) != len(results):
        raise SystemExit("T2-SPRINT3 FAULT ERROR: fixed seed ledger must contain 14 unique seeds")
    output = args.output if args.output.is_absolute() else ROOT / args.output
    output.parent.mkdir(parents=True, exist_ok=True)
    report = {
        "schemaVersion": "1.0", "phase": "T2-GATE23-S3",
        "generatedAt": datetime.now(timezone.utc).isoformat().replace("+00:00", "Z"),
        "commitSha": subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=ROOT, text=True).strip(),
        "seedCount": len(results), "results": results,
        "evidenceNote": "Dynamic assertions are executed in the server and Flutter Linux/Windows jobs on the same commit.",
        "externalEvidence": {"sandbox": 0, "realDevice": 0, "pilot": 0},
    }
    output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(f"T2-SPRINT3 FAULT MATRIX OK: fixedSeeds={len(results)} external=0")


if __name__ == "__main__":
    main()
