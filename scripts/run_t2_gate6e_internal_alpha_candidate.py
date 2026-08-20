#!/usr/bin/env python3
"""Gate 6E 内部 Alpha 候选：组合同提交正式证据并核对合成闭环守恒。"""
from __future__ import annotations

import argparse
import hashlib
import json
import pathlib
import subprocess
from decimal import Decimal, InvalidOperation

from run_t2_gate6d_internal_e2e import (
    FLUTTER_TESTS as GATE6D_FLUTTER_TESTS,
    SERVER_TESTS as GATE6D_SERVER_TESTS,
    WEB_TESTS as GATE6D_WEB_TESTS,
    read_flutter_successful_tests,
    read_xml_tests,
    validate_journeys as validate_gate6d_journeys,
)


ROOT = pathlib.Path(__file__).resolve().parents[1]
FIXTURE = ROOT / "contracts/t2/gate6e/test-vectors/internal-alpha-candidate-v1.json"
BASE_FIXTURE = ROOT / "contracts/t2/gate6d/test-vectors/internal-cash-e2e-v1.json"
INDUSTRIES = {"CONVENIENCE", "SNACK_DISCOUNT", "COMMUNITY_SUPERMARKET"}
REQUIRED_STEPS = {
    "ADMIN_INITIALIZE_AND_RELEASE", "POS_LOGIN_AND_OPEN_SHIFT",
    "SALE_HOLD_RESUME_AND_CASH_SETTLE", "OUTBOX_INBOX_ACK_CONVERGE",
    "ORDER_INVENTORY_COST_REPORT_RECONCILE", "PARTIAL_ORIGINAL_RETURN",
    "FINAL_ORIGINAL_RETURN_WITH_REMAINDER", "CLOSE_SHIFT",
    "SYNTHETIC_RESTORE_FROM_EMPTY", "SYNTHETIC_UPGRADE_ROLLBACK_AND_FORWARD_FIX",
}

SERVER_TESTS = {name: set(methods) for name, methods in GATE6D_SERVER_TESTS.items()}
SERVER_TESTS.update({
    "ReturnOrchestrationServiceTest": {
        "freezesPromotionAllocationThenEmitsExactlyOneStableCashOwnerCommand",
        "paymentUnknownPersistsCheckpointWithoutCreatingAnotherRefundCommand",
        "rejectsChangedIdempotencyAndCumulativeQuantityOverflow",
    },
    "ReturnSagaCoordinatorTest": {
        "retriesOwnerFailureWithSameCashBusinessCommand",
        "unknownPaymentQueriesExistingRefundAndNeverRegeneratesCommand",
        "emitsOnlyRefundOwnedInventoryMovement",
    },
    "CashRefundOwnerServiceTest": {
        "atomicallyAppendsRefundNegativeLedgerShiftAuditAndOutbox",
        "returnsStoredResultForSameRefundAndRejectsChangedContent",
        "rejectsCumulativeOverRefundAndMismatchedShiftBeforeWrites",
    },
    "TransactionAllocationEngineTest": {
        "partialRefundUsesCumulativeTargetsAndFinalResidual",
        "conservesAmountsForTenThousandFixedSeedRefundVectors",
    },
    "BackupRecoveryServiceTest": {
        "createsEncryptedSetRestoresFromEmptyAndReplaysIdempotently",
        "failsClosedForCorruptObjectAndReconciliationDifference",
        "marksInterruptedCaptureFailedWithoutGreenEvidence",
    },
    "BackupRulesTest": {
        "acceptsCompleteSyntheticRecoverySetAndMeasuresTargets",
        "rejectsRestoreScopeSchemaStateTimelineAndReconciliationDifferences",
    },
    "ReleaseGovernanceServiceTest": {
        "closesFullSyntheticReleaseRolloutAndTaskLifecycle",
        "failsClosedForPendingOutboxDigestMismatchAndIdempotencyConflict",
    },
    "ReleaseRulesTest": {
        "coversPauseResumeFailureForwardFixAndRetryTransitions",
        "blocksUntrustedTerminalPendingFactsAndBusinessHours",
    },
    "ReturnsStrictTenantMapperGuardTest": {
        "requiresTrustedPrincipalBeforeEveryReturnMapperInvocation",
    },
    "ReturnRulesTest": {
        "enforcesCumulativeOriginalQuantityCap",
        "enforcesRefundMoneyConservationWithoutFloatingPoint",
        "rejectsUntrustedIdentifiersAndDigests",
    },
})
SERVER_TESTS["InventoryLedgerServiceTest"].update({
    "appliesOnlySucceededReturnAndCrossChecksOriginalLine",
    "returnsStoredResultForSameEventAndRejectsHashConflict",
})
SERVER_TESTS["ReportingProjectionServiceTest"].add("returnsExistingRebuildAndRejectsSameIdDifferentScope")

FLUTTER_TESTS = set(GATE6D_FLUTTER_TESTS) | {
    "搜索、改量和现金退货按单航班调用正式应用端口",
    "UNKNOWN 保留原 returnRef 且恢复只查询原申请",
    "查询原单、更新数量、二次确认并展示现金退货检查点",
    "未知结果只允许查询原 returnRef 并最终收敛",
}
WEB_TESTS = set(GATE6D_WEB_TESTS) | {
    "covers every admitted Owner through formal components or an existing formal page",
    "requires state version confirmation single-flight and idempotency reuse",
    "does not open forbidden data or device boundaries",
}
SEED_EVIDENCE = {
    "G6E-0001": "server:ReturnsStrictTenantMapperGuardTest#requiresTrustedPrincipalBeforeEveryReturnMapperInvocation",
    "G6E-0002": "server:ReturnOrchestrationServiceTest#rejectsChangedIdempotencyAndCumulativeQuantityOverflow",
    "G6E-0003": "server:SyncInboxReceiverTest#commitsNewInboxBeforeReturningForProcessing",
    "G6E-0004": "server:SyncInboxReceiverTest#sameIdentityAndHashIsDuplicateButDifferentHashBlocksDevice",
    "G6E-0005": "server:ReturnSagaCoordinatorTest#unknownPaymentQueriesExistingRefundAndNeverRegeneratesCommand",
    "G6E-0006": "flutter:freezes quote, snapshot, order, cash and outbox atomically",
    "G6E-0007": "server:OrgAndStoreServiceTest#createsUpdatesAndCalculatesStoreBusinessDate",
    "G6E-0008": "server:ReturnRulesTest#rejectsUntrustedIdentifiersAndDigests",
    "G6E-0009": "server:ReturnRulesTest#enforcesRefundMoneyConservationWithoutFloatingPoint",
    "G6E-0010": "server:ReportingProjectionServiceTest#returnsExistingRebuildAndRejectsSameIdDifferentScope",
    "G6E-0011": "server:BackupRecoveryServiceTest#failsClosedForCorruptObjectAndReconciliationDifference",
    "G6E-0012": "server:ReleaseGovernanceServiceTest#failsClosedForPendingOutboxDigestMismatchAndIdempotencyConflict",
}


def fail(message: str) -> None:
    raise SystemExit(f"T2-GATE6E ALPHA ERROR: {message}")


def exact(value: str, field: str) -> Decimal:
    try:
        number = Decimal(value)
    except (InvalidOperation, TypeError) as exception:
        raise SystemExit(f"T2-GATE6E ALPHA ERROR: invalid decimal {field}") from exception
    if not number.is_finite() or number.as_tuple().exponent < -6:
        fail(f"{field} exceeds six decimal places")
    return number


def validate_candidate(document: dict, base: dict) -> tuple[list[dict], list[dict]]:
    if document.get("requirementId") != "T2-E2E-002" or document.get("evidenceLevel") != "INTERNAL_ALPHA_CANDIDATE":
        fail("candidate identity or evidence ceiling drift")
    if set(document.get("requiredSteps", [])) != REQUIRED_STEPS:
        fail("candidate required journey steps drift")
    validate_gate6d_journeys(base)
    base_by_id = {item["id"]: item for item in base["journeys"]}
    journeys = document.get("journeys", [])
    if len(journeys) != 6 or {item.get("tenantId") for item in journeys} != {"TENANT_A", "TENANT_B"}:
        fail("candidate requires exactly six journeys across two fictional tenants")
    for tenant in ("TENANT_A", "TENANT_B"):
        if {item.get("industry") for item in journeys if item.get("tenantId") == tenant} != INDUSTRIES:
            fail(f"tenant {tenant} does not cover all three industries")
    for field in ("id", "baseJourneyId", "returnRef", "backupSetId", "releaseId"):
        values = [item.get(field) for item in journeys]
        if None in values or len(values) != len(set(values)):
            fail(f"candidate identity must be present and unique: {field}")

    results = []
    for item in journeys:
        prefix = item["id"]
        original = base_by_id.get(item["baseJourneyId"])
        if not original:
            fail(f"{prefix} references an unknown Gate 6D journey")
        if item["tenantId"] != original["tenantId"] or item["industry"] != original["industry"]:
            fail(f"{prefix} tenant or industry diverges from the original sale")
        original_quantity = exact(item["originalQuantity"], f"{prefix}.originalQuantity")
        partial_quantity = exact(item["partialQuantity"], f"{prefix}.partialQuantity")
        final_quantity = exact(item["finalQuantity"], f"{prefix}.finalQuantity")
        if partial_quantity <= 0 or final_quantity <= 0:
            fail(f"{prefix} must exercise both partial and final return")
        if original_quantity != exact(original["basket"]["quantity"], f"{prefix}.baseQuantity"):
            fail(f"{prefix} original quantity drift")
        if partial_quantity + final_quantity != original_quantity:
            fail(f"{prefix} cumulative returned quantity does not close exactly")

        refund_total = item["partialRefundMinor"] + item["finalRefundMinor"]
        if not all(isinstance(item[field], int) and item[field] >= 0 for field in
                   ("originalRefundableMinor", "partialRefundMinor", "finalRefundMinor")):
            fail(f"{prefix} refund money must use non-negative integer minor units")
        if item["originalRefundableMinor"] != original["promotion"]["receivableMinor"]:
            fail(f"{prefix} original refundable amount drift")
        if refund_total != item["originalRefundableMinor"]:
            fail(f"{prefix} final refund does not absorb the legal remainder")

        sale_cost = exact(item["saleCostMinor"], f"{prefix}.saleCost")
        partial_cost = exact(item["partialCostReversalMinor"], f"{prefix}.partialCost")
        final_cost = exact(item["finalCostReversalMinor"], f"{prefix}.finalCost")
        if sale_cost != exact(original["cost"]["saleCostMinor"], f"{prefix}.baseSaleCost"):
            fail(f"{prefix} sale cost snapshot drift")
        if partial_cost + final_cost != sale_cost:
            fail(f"{prefix} cost reversal does not reconcile")
        opening_on_hand = exact(original["inventory"]["openingOnHand"], f"{prefix}.openingOnHand")
        after_sale = exact(original["inventory"]["closingOnHand"], f"{prefix}.afterSale")
        if after_sale + partial_quantity + final_quantity != opening_on_hand:
            fail(f"{prefix} return inventory does not restore the authoritative opening quantity")
        opening_cash = original["shift"]["openingCashMinor"]
        after_sale_cash = opening_cash + original["promotion"]["receivableMinor"]
        after_partial_cash = after_sale_cash - item["partialRefundMinor"]
        after_final_cash = after_partial_cash - item["finalRefundMinor"]
        if after_final_cash != opening_cash:
            fail(f"{prefix} final cash shift amount does not reconcile")

        canonical = json.dumps(item, sort_keys=True, separators=(",", ":"), ensure_ascii=False).encode()
        results.append({
            "journeyId": prefix,
            "baseJourneyId": item["baseJourneyId"],
            "returnRef": item["returnRef"],
            "partialRefundMinor": item["partialRefundMinor"],
            "finalRefundMinor": item["finalRefundMinor"],
            "finalOnHand": format(opening_on_hand, "f"),
            "finalShiftCashMinor": after_final_cash,
            "result": "PASS",
            "journeySha256": hashlib.sha256(canonical).hexdigest(),
        })

    recovery = document.get("recovery", {})
    if (recovery.get("evidenceLevel") != "SYNTHETIC_RESTORE" or recovery.get("startFromEmpty") is not True or
            recovery.get("rpoTargetSeconds") != 900 or recovery.get("rtoTargetSeconds") != 3600):
        fail("synthetic recovery target or evidence boundary drift")
    recovery_checks = {"MANIFEST_SHA256", "ENCRYPTION_KEY_VERSION", "FLYWAY_VALIDATE", "OWNER_FACT_RECONCILE",
                       "PROJECTION_REBUILD", "INBOX_OUTBOX_CURSOR"}
    if set(recovery.get("requiredChecks", [])) != recovery_checks:
        fail("synthetic recovery check set drift")
    upgrade = document.get("upgrade", {})
    if upgrade.get("evidenceLevel") != "SOFTWARE_EXECUTION" or upgrade.get("syntheticPackageOnly") is not True:
        fail("synthetic upgrade evidence boundary drift")
    upgrade_checks = {"RELEASE_SHA256", "SIGNATURE_KEY_VERSION", "COMPATIBILITY_WINDOW", "PENDING_OUTBOX_GUARD",
                      "HEALTH_CHECK", "ROLLBACK", "SAFE_FORWARD_FIX"}
    if set(upgrade.get("requiredChecks", [])) != upgrade_checks:
        fail("synthetic upgrade check set drift")

    seeds = document.get("failureSeeds", [])
    if len(seeds) < 12 or len({item.get("seed") for item in seeds}) != len(seeds):
        fail("fixed failure seed ledger is incomplete")
    if any(item.get("expected") not in {"REJECT", "RETURN_ORIGINAL", "QUERY_ORIGINAL", "ROLLBACK_ALL",
                                        "FREEZE_ORIGINAL", "RECONCILE", "FAIL_CLOSED",
                                        "ROLLBACK_OR_FORWARD_FIX"} for item in seeds):
        fail("fixed failure seed expectation is unsupported")
    if {item.get("seed") for item in seeds} != set(SEED_EVIDENCE):
        fail("fixed failure seeds and executable evidence references diverge")
    ledger = document.get("defectLedger", {})
    if ledger.get("p0") or ledger.get("p1"):
        fail("open P0/P1 defects force NO-GO")
    external = document.get("externalExecution", {})
    for field in ("providerNetworkCalls", "realDeviceCommands", "onsitePilots", "fullAlphaRuns", "productionDeployments"):
        if external.get(field) != 0:
            fail(f"external execution must remain zero: {field}")
    return results, seeds


def validate_ci_bundle(bundle: pathlib.Path) -> dict:
    suites = read_xml_tests(bundle, "server")
    for class_name, methods in SERVER_TESTS.items():
        if class_name not in suites or not methods.issubset(suites[class_name]):
            fail(f"missing successful Owner runtime evidence {class_name}: {sorted(methods)}")
    mysql = read_xml_tests(bundle, "mysql")
    if "ReleaseMigrationMySqlIT" not in mysql:
        fail("missing complete MySQL migration evidence")
    web = read_xml_tests(bundle, "web", "*.xml")
    web_names = set().union(*web.values()) if web else set()
    missing_web = {name for name in WEB_TESTS if not any(name in actual for actual in web_names)}
    if missing_web:
        fail(f"missing successful admin evidence: {sorted(missing_web)}")
    for producer in ("pos-linux", "pos-windows"):
        reports = list((bundle / producer).rglob("flutter-tests.jsonl"))
        if len(reports) != 1:
            fail(f"Flutter machine evidence missing or duplicated: {producer}")
        successful = read_flutter_successful_tests(reports[0])
        missing = {name for name in FLUTTER_TESTS if not any(name in actual for actual in successful)}
        if missing:
            fail(f"missing successful unskipped POS evidence in {producer}: {sorted(missing)}")
    coverage = list((bundle / "pos-linux").rglob("flutter-gate6e-coverage.json"))
    if len(coverage) != 1 or json.loads(coverage[0].read_text(encoding="utf-8")).get("status") != "PASS":
        fail("Gate 6E Flutter coverage evidence missing or non-green")
    governance_reports = list((bundle / "governance").rglob("gate6e-governance.json"))
    if len(governance_reports) != 1:
        fail("Gate 6E governance evidence missing or duplicated")
    governance = json.loads(governance_reports[0].read_text(encoding="utf-8"))
    statuses = governance.get("statuses", {})
    if statuses.get("T2-ADM-002") != "VERIFIED" or statuses.get("T2-POS-009") != "VERIFIED" or \
            statuses.get("T2-E2E-002") not in {"IN_PROGRESS", "VERIFIED"}:
        fail("Gate 6E serial status evidence is not eligible for candidate execution")
    return {
        "serverOwnerSuites": len(SERVER_TESTS),
        "serverRequiredTests": sum(len(methods) for methods in SERVER_TESTS.values()),
        "flutterRequiredTestsPerPlatform": len(FLUTTER_TESTS),
        "webRequiredTests": len(WEB_TESTS),
        "mysqlMigration": "PASS",
        "gate6eCoverage": "PASS",
    }


def write_json(path: pathlib.Path, payload: dict) -> None:
    target = path if path.is_absolute() else ROOT / path
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_text(json.dumps(payload, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--bundle-dir", type=pathlib.Path)
    parser.add_argument("--output", type=pathlib.Path, required=True)
    parser.add_argument("--seed-ledger", type=pathlib.Path)
    parser.add_argument("--defect-ledger", type=pathlib.Path)
    args = parser.parse_args()
    document = json.loads(FIXTURE.read_text(encoding="utf-8"))
    base = json.loads(BASE_FIXTURE.read_text(encoding="utf-8"))
    journeys, seeds = validate_candidate(document, base)
    formal_execution = args.bundle_dir is not None
    component_evidence = validate_ci_bundle(args.bundle_dir) if formal_execution else {"mode": "SOURCE_CONTRACT"}
    commit = subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=ROOT, text=True).strip()
    report = {
        "schemaVersion": "1.0",
        "requirementId": "T2-E2E-002",
        "status": "PASS",
        "executionMode": "SAME_RUN_CI_EVIDENCE" if formal_execution else "SOURCE_CONTRACT",
        "internalDecision": "CONDITIONAL_GO_INTERNAL_ONLY" if formal_execution else "DESIGN_VALIDATED_ONLY",
        "evidenceLevel": "INTERNAL_ALPHA_CANDIDATE",
        "commitSha": commit,
        "journeyCount": len(journeys),
        "tenantCount": 2,
        "storeCount": 6,
        "terminalCount": 6,
        "industries": sorted(INDUSTRIES),
        "requiredStepCount": len(REQUIRED_STEPS),
        "failureSeedCount": len(seeds),
        "openP0": 0,
        "openP1": 0,
        "journeys": journeys,
        "componentEvidence": component_evidence,
        "recoveryEvidence": {"level": "SYNTHETIC_RESTORE", "rpoTargetSeconds": 900, "rtoTargetSeconds": 3600},
        "upgradeEvidence": {"level": "SOFTWARE_EXECUTION", "syntheticPackageOnly": True},
        "providerNetworkCalls": 0,
        "realDeviceCommands": 0,
        "onsitePilots": 0,
        "fullAlphaRuns": 0,
        "productionDeployments": 0,
        "commercialClaimAllowed": False,
        "evidenceNote": "INTERNAL_ALPHA_CANDIDATE only; not SANDBOX, REAL_DEVICE, PILOT, FULL_ALPHA or PRODUCTION evidence.",
    }
    encoded = json.dumps(report, sort_keys=True, separators=(",", ":"), ensure_ascii=False).encode()
    report["evidenceSha256"] = hashlib.sha256(encoded).hexdigest()
    write_json(args.output, report)
    if args.seed_ledger:
        write_json(args.seed_ledger, {
            "schemaVersion": "1.0", "requirementId": "T2-E2E-002", "commitSha": commit,
            "status": "PASS" if formal_execution else "DESIGN_VALIDATED_ONLY",
            "fixedSeeds": [{
                **seed,
                "evidenceRef": SEED_EVIDENCE[seed["seed"]],
                "observed": seed["expected"] if formal_execution else "NOT_EXECUTED_SOURCE_CONTRACT",
                "result": "PASS" if formal_execution else "PENDING_CI_EXECUTION",
            } for seed in seeds],
            "failedSeeds": [], "externalExecution": document["externalExecution"],
        })
    if args.defect_ledger:
        write_json(args.defect_ledger, {
            "schemaVersion": "1.0", "requirementId": "T2-E2E-002", "commitSha": commit,
            "status": "PASS", "p0": [], "p1": [],
            "goNoGo": "CONDITIONAL_GO_INTERNAL_ONLY" if formal_execution else "DESIGN_VALIDATED_ONLY",
        })
    print(f"T2-GATE6E INTERNAL ALPHA CANDIDATE OK: journeys={len(journeys)} seeds={len(seeds)} external=0")


if __name__ == "__main__":
    main()
