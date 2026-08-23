#!/usr/bin/env python3
"""Gate 7E：汇总正式组件执行证据并核对商业 V1 内部合成闭环。"""
from __future__ import annotations

import argparse
import hashlib
import json
import pathlib
import subprocess
from decimal import Decimal, InvalidOperation

from run_t2_gate6d_internal_e2e import read_flutter_successful_tests, read_xml_tests
from run_t2_gate6e_internal_alpha_candidate import (
    FLUTTER_TESTS as GATE6E_FLUTTER_TESTS,
    SERVER_TESTS as GATE6E_SERVER_TESTS,
    WEB_TESTS as GATE6E_WEB_TESTS,
)


ROOT = pathlib.Path(__file__).resolve().parents[1]
FIXTURE = ROOT / "contracts/t2/gate7e/internal-v1-business-complete-v1.json"
PERFORMANCE = ROOT / "contracts/t2/gate6h/performance-baseline-v1.json"
EVIDENCE_LEVEL = "INTERNAL_V1_BUSINESS_COMPLETE_CANDIDATE"
INDUSTRIES = {"CONVENIENCE", "SNACK_DISCOUNT", "COMMUNITY_SUPERMARKET"}
SERVER_TESTS = {name: set(methods) for name, methods in GATE6E_SERVER_TESTS.items()}
SERVER_TESTS.update({
    "ShiftServiceTest": {
        "recordsNonSaleCashWithSignedDirectionAndShiftVersionAtomically",
        "recordsDrawerRequestButKeepsRealDeviceExecutionBlocked",
    },
    "ReceiptServiceTest": {
        "appendsAuditedReprintButKeepsExecutionBlocked",
        "rejectsReceiptHashTamperingBeforeAnyFactIsWritten",
    },
    "OrderDispositionServiceTest": {
        "completedOrderUsesOnlyAppendOnlyRouteAndMustMatchAuthoritativeSnapshot",
        "rejectsSnapshotReplacementAndExistingCompletionCancellation",
    },
    "ExchangeSagaCoordinatorTest": {
        "returnUnknownReusesOriginalReturnAggregateAndOnlyObservesIt",
        "saleUnknownQueriesFrozenOrderAndDoesNotTouchReturnOwner",
    },
    "TenderPlanServiceTest": {
        "finalCashSuccessCompletesPlanAndOrderThroughOwnerPorts",
        "recoveryInspectsUnknownWithoutChangingOrReplacingIt",
    },
    "TenderRulesTest": {"matchesSharedJavaDartGoldenDigest"},
    "WeightedBarcodeServiceTest": {"verifiesHistoricalFrozenMeasurementAndRejectsTamperedAmount"},
    "WeightedBarcodeRulesTest": {"matchesSharedJavaDartGoldenVectors"},
    "ShelfLabelServiceTest": {
        "previewsPlainTextThenConfirmsReplacementWithoutPrinterClaim",
        "dispatchAlwaysFailsClosedAndCreatesAuditableException",
    },
    "ReplenishmentServiceTest": {
        "generationUsesFrozenRuleInventoryCheckpointAndConfirmedTransit",
        "draftFailsClosedAsStaleWhenInventoryCheckpointChanged",
    },
    "ReplenishmentMillionTrendTest": {"evaluatesOneMillionSyntheticDimensionsWithinInternalTrendBudget"},
    "BusinessMigrationActivationTest": {
        "resumesOriginalActivationPendingBatchAndCommitsNamedState",
        "leavesPendingForSafeRetryWhenOwnerResultIsUnknown",
    },
    "MigrationCapacityTrendTest": {
        "normalizesOneHundredThousandSyntheticSupplierRowsWithinInternalBudget",
        "hashesOneMillionSyntheticRowsAsStreamingTrendWithoutBuildingFullDataset",
    },
    "OnboardingServiceTest": {
        "preflightDetectsVersionDriftAndFailsClosed",
        "rejectsIncompleteOwnerCheckSetAndBlockedExternalOpen",
    },
    "LotInventoryServiceTest": {
        "nonCommunityTemplateNeverEnablesLotPath",
        "saleKeepsLotFrozenNearExpiryThresholdAfterPolicyChanges",
    },
    "DailyCloseServiceTest": {
        "closesWithIndependentSignatureAndNeverRecomputesFrozenFacts",
        "appendsLateFactDifferenceWithoutReopeningClosedFact",
    },
    "ExceptionCenterServiceTest": {
        "outOfOrderObservationIsAppendedWithoutOverwritingLatestSourceHead",
        "unavailableOwnerRepairRemainsWaitingAndNeverCreatesGreenResult",
    },
    "MemberBenefitServiceTest": {
        "issuesMinimalSnapshotAndResolvesOnlyInsideStoreAndTtl",
        "sameIdempotencyKeyWithDifferentContentFailsClosed",
    },
    "MemberBenefitCrossPlatformVectorTest": {"executesSharedCalculationVectorsAndLocksAllFortyCases"},
    "CommercialV1AssemblyContractTest": {"ownerNamesAndCapabilityTypesAreUnique"},
    "MenuIdCollisionForwardRepairPolicyTest": {"repairIsIdempotentFailsClosedAndPreservesRoleBindings"},
})
FLUTTER_TESTS = set(GATE6E_FLUTTER_TESTS) | {
    "现金存取、钱箱请求和关班使用同一理论现金且可幂等恢复",
    "UNKNOWN preserves original exchange id and refresh never creates replacement",
    "electronic collection fails closed and preserves original allocation",
    "Dart digest matches the shared Java golden vector",
    "Java 与 Dart 共同消费同一份秤码金额金标",
    "签名数据包原子安装模板并冻结成交计量快照",
    "社区超市现金成交在一个事务冻结 FEFO、订单、现金和 lot Outbox",
    "非社区超市模板绝不进入批次路径",
    "Java/Dart common member benefit vectors remain identical",
    "signed member package atomically installs and quote freezes minimal snapshot",
}
WEB_TESTS = set(GATE6E_WEB_TESTS) | {
    "renders the server preview as text and never executes HTML",
    "rejects nested tenant claims and accepts custody metadata",
    "exposes the frozen serial journey and explicit external evidence boundary",
    "does not calculate replenishment or cross owner boundaries in the browser",
    "rejects tenant, inventory, expiry and cost owner facts",
    "shows serial close journey, append-only correction and external boundary",
    "rejects client-authored owner facts",
    "does not calculate prices or submit tenant identity in the Vue layer",
    "pins every supported Owner to a first-party API path",
}
SEED_EVIDENCE = {
    "G7E-0001": "server:ReplenishmentServiceTest#retryAfterLaterStateReturnsCurrentFactButChangedContentFailsClosed",
    "G7E-0002": "server:ExceptionCenterServiceTest#outOfOrderObservationIsAppendedWithoutOverwritingLatestSourceHead",
    "G7E-0003": "flutter:ACK lost resends the original identity and converges as duplicate",
    "G7E-0004": "server:ExchangeSagaCoordinatorTest#returnUnknownReusesOriginalReturnAggregateAndOnlyObservesIt",
    "G7E-0005": "flutter:migration/freeze failure rolls back plan, event and outbox together",
    "G7E-0006": "server:ReturnSagaCoordinatorTest#retriesOwnerFailureWithSameCashBusinessCommand",
    "G7E-0007": "server:DailyCloseServiceTest#appendsLateFactDifferenceWithoutReopeningClosedFact",
    "G7E-0008": "server:ReturnsStrictTenantMapperGuardTest#requiresTrustedPrincipalBeforeEveryReturnMapperInvocation",
    "G7E-0009": "server:MemberBenefitServiceTest#sameIdempotencyKeyWithDifferentContentFailsClosed",
    "G7E-0010": "flutter:signed member package atomically installs and quote freezes minimal snapshot",
    "G7E-0011": "server:ReceiptServiceTest#rejectsReceiptHashTamperingBeforeAnyFactIsWritten",
    "G7E-0012": "server:OnboardingServiceTest#rejectsIncompleteOwnerCheckSetAndBlockedExternalOpen",
    "G7E-0013": "server:ReportingProjectionServiceTest#returnsExistingRebuildAndRejectsSameIdDifferentScope",
    "G7E-0014": "flutter:SQLite迁移中断整体回滚且原文件可安全前向恢复到当前版本",
    "G7E-0015": "server:MenuIdCollisionForwardRepairPolicyTest#repairIsIdempotentFailsClosedAndPreservesRoleBindings",
    "G7E-0016": "server:ShelfLabelServiceTest#dispatchAlwaysFailsClosedAndCreatesAuditableException",
}


def fail(message: str) -> None:
    raise SystemExit(f"T2-GATE7E E2E ERROR: {message}")


def load(path: pathlib.Path) -> dict:
    try:
        return json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as exception:
        fail(f"invalid JSON {path.relative_to(ROOT)}: {exception}")


def exact(value: object, field: str) -> Decimal:
    try:
        number = Decimal(str(value))
    except (InvalidOperation, TypeError) as exception:
        raise SystemExit(f"T2-GATE7E E2E ERROR: invalid decimal {field}") from exception
    if not number.is_finite() or number.as_tuple().exponent < -6:
        fail(f"{field} exceeds six decimal places")
    return number


def validate_journeys(document: dict) -> tuple[list[dict], list[dict]]:
    if document.get("requirementId") != "T2-E2E-004" or document.get("evidenceLevel") != EVIDENCE_LEVEL:
        fail("requirement identity or evidence ceiling drift")
    journeys = document.get("journeys", [])
    if len(journeys) != 6 or {item.get("tenant") for item in journeys} != {"TENANT_A", "TENANT_B"}:
        fail("exactly six journeys across two fictional tenants are required")
    if {item.get("industry") for item in journeys} != INDUSTRIES:
        fail("three-industry coverage is incomplete")
    if len({item.get("store") for item in journeys}) != 6 or len({item.get("terminal") for item in journeys}) != 6:
        fail("six unique stores and terminals are required")
    results = []
    for item in journeys:
        identity = item["id"]
        facts = item["facts"]
        amounts = [facts[key] for key in (
            "grossMinor", "memberDiscountMinor", "promotionDiscountMinor", "surchargeMinor",
            "receivableMinor", "cashMinor", "partialRefundMinor", "finalRefundMinor",
        )]
        if not all(isinstance(value, int) and value >= 0 for value in amounts):
            fail(f"{identity} money must use non-negative integer minor units")
        discount = facts["memberDiscountMinor"] + facts["promotionDiscountMinor"]
        if facts["grossMinor"] - discount + facts["surchargeMinor"] != facts["receivableMinor"]:
            fail(f"{identity} order amount conservation failed")
        if facts["cashMinor"] != facts["receivableMinor"]:
            fail(f"{identity} tender share conservation failed")
        if facts["partialRefundMinor"] + facts["finalRefundMinor"] != facts["receivableMinor"]:
            fail(f"{identity} original snapshot refund conservation failed")
        opening = exact(facts["openingQuantity"], f"{identity}.opening")
        sale = exact(facts["saleQuantity"], f"{identity}.sale")
        returned = exact(facts["returnQuantity"], f"{identity}.return")
        closing = exact(facts["closingQuantity"], f"{identity}.closing")
        if opening - sale + returned != closing:
            fail(f"{identity} inventory ledger conservation failed")
        unit_cost = exact(facts["unitCostMinor"], f"{identity}.unitCost")
        sale_cost = exact(facts["saleCostMinor"], f"{identity}.saleCost")
        return_cost = exact(facts["returnCostMinor"], f"{identity}.returnCost")
        if unit_cost * sale != sale_cost or sale_cost != return_cost:
            fail(f"{identity} original cost snapshot conservation failed")
        lot_quantity = exact(facts["lotAllocationQuantity"], f"{identity}.lot")
        expected_lot = sale if item["industry"] == "COMMUNITY_SUPERMARKET" else Decimal("0")
        if item["lotEnabled"] != (item["industry"] == "COMMUNITY_SUPERMARKET") or lot_quantity != expected_lot:
            fail(f"{identity} lot capability boundary failed")
        canonical = json.dumps(item, sort_keys=True, separators=(",", ":"), ensure_ascii=False).encode()
        results.append({
            "journeyId": identity, "industry": item["industry"], "result": "PASS",
            "receivableMinor": facts["receivableMinor"], "closingQuantity": format(closing, "f"),
            "returnCostMinor": format(return_cost, "f"),
            "journeySha256": hashlib.sha256(canonical).hexdigest(),
        })
    seeds = document.get("failureSeeds", [])
    if {item.get("seed") for item in seeds} != set(SEED_EVIDENCE) or len(seeds) != 16:
        fail("fixed failure seed ledger is incomplete")
    if document.get("defectLedger") != {"p0": [], "p1": []}:
        fail("open P0/P1 defects force NO-GO")
    if any(value != 0 for value in document.get("externalExecution", {}).values()):
        fail("external execution must remain zero")
    if document.get("commercialClaimAllowed") is not False:
        fail("commercial claim must remain forbidden")
    performance = load(PERFORMANCE)
    if performance.get("classification") != "INTERNAL_SYNTHETIC_TREND" or \
            performance.get("policy", {}).get("commercialSlaAllowed") is not False:
        fail("performance evidence boundary drift")
    return results, seeds


def require_server_test(suites: dict[str, set[str]], reference: str) -> None:
    target = reference.removeprefix("server:")
    class_name, method = target.split("#", maxsplit=1)
    if class_name not in suites or method not in suites[class_name]:
        fail(f"fixed seed server evidence missing: {target}")


def require_flutter_test(successful: set[str], reference: str) -> None:
    target = reference.removeprefix("flutter:")
    if not any(target in actual for actual in successful):
        fail(f"fixed seed Flutter evidence missing: {target}")


def validate_bundle(bundle: pathlib.Path) -> dict:
    server = read_xml_tests(bundle, "server")
    for class_name, methods in SERVER_TESTS.items():
        if class_name not in server or not methods.issubset(server[class_name]):
            fail(f"server evidence missing: {class_name} {sorted(methods)}")
    mysql = read_xml_tests(bundle, "mysql-runtime")
    if "MemberBenefitMigrationMySqlIT" not in mysql:
        fail("unified MySQL V1-V80 evidence missing")
    web = read_xml_tests(bundle, "web", "*.xml")
    web_names = set().union(*web.values()) if web else set()
    missing_web = {name for name in WEB_TESTS if not any(name in actual for actual in web_names)}
    if missing_web:
        fail(f"Web evidence missing: {sorted(missing_web)}")
    flutter_by_platform: dict[str, set[str]] = {}
    for producer in ("flutter-ubuntu", "flutter-windows"):
        reports = list((bundle / producer).rglob("flutter-tests.jsonl"))
        if len(reports) != 1:
            fail(f"Flutter evidence missing or duplicated: {producer}")
        successful = read_flutter_successful_tests(reports[0])
        missing = {name for name in FLUTTER_TESTS if not any(name in actual for actual in successful)}
        if missing:
            fail(f"Flutter evidence missing in {producer}: {sorted(missing)}")
        flutter_by_platform[producer] = successful
    governance = list((bundle / "governance-ubuntu").rglob("gate7e-governance.json"))
    if len(governance) != 1:
        fail("Gate 7E governance evidence missing or duplicated")
    gate = load(governance[0])
    if gate.get("requirementStatus") != "VERIFIED" or gate.get("status") != "PASS":
        fail("same-commit E2E004 governance state must be VERIFIED and PASS")
    runtime_reports = list((bundle / "runtime-stack").rglob("runtime-stack-smoke.json"))
    if len(runtime_reports) != 1:
        fail("same-run runtime stack evidence missing or duplicated")
    runtime = load(runtime_reports[0])
    if runtime.get("status") != "PASS" or runtime.get("simultaneousProcessWindow") is not True or \
            runtime.get("syntheticBoundary") is not True or runtime.get("commercialClaimAllowed") is not False:
        fail("runtime stack evidence boundary invalid")
    if any(runtime.get(field) != 0 for field in ("providerNetworkCalls", "realDeviceCommands")):
        fail("runtime stack performed forbidden external execution")
    for reference in SEED_EVIDENCE.values():
        if reference.startswith("server:"):
            require_server_test(server, reference)
        else:
            for successful in flutter_by_platform.values():
                require_flutter_test(successful, reference)
    return {
        "executionModel": "FORMAL_COMPONENT_EXECUTION_PLUS_CONTRACT_RECONCILIATION",
        "serverOwnerSuites": len(SERVER_TESTS),
        "serverRequiredTests": sum(len(methods) for methods in SERVER_TESTS.values()),
        "mysqlMigration": "V1_V80_PASS",
        "webRequiredTests": len(WEB_TESTS),
        "flutterRequiredTestsPerPlatform": len(FLUTTER_TESTS),
        "flutterSuccessfulTests": {key: len(value) for key, value in flutter_by_platform.items()},
        "runtimeStack": runtime,
        "performanceReference": PERFORMANCE.relative_to(ROOT).as_posix(),
        "performanceClassification": "INTERNAL_SYNTHETIC_TREND",
    }


def write(path: pathlib.Path, payload: dict) -> None:
    target = path if path.is_absolute() else ROOT / path
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_text(json.dumps(payload, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--bundle-dir", type=pathlib.Path)
    parser.add_argument("--output", required=True, type=pathlib.Path)
    parser.add_argument("--seed-ledger", type=pathlib.Path)
    parser.add_argument("--defect-ledger", type=pathlib.Path)
    args = parser.parse_args()
    document = load(FIXTURE)
    journeys, seeds = validate_journeys(document)
    formal = args.bundle_dir is not None
    component = validate_bundle(args.bundle_dir) if formal else {"mode": "SOURCE_CONTRACT"}
    commit = subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=ROOT, text=True).strip()
    report = {
        "schemaVersion": "1.0", "requirementId": "T2-E2E-004", "status": "PASS",
        "evidenceLevel": EVIDENCE_LEVEL,
        "executionMode": "SAME_RUN_FORMAL_COMPONENT_EVIDENCE" if formal else "SOURCE_CONTRACT",
        "internalDecision": "CONDITIONAL_PASS_AWAITING_SPONSOR_ACCEPTANCE" if formal else "DESIGN_VALIDATED_ONLY",
        "commitSha": commit, "tenantCount": 2, "storeCount": 6, "terminalCount": 6,
        "industries": sorted(INDUSTRIES), "journeyCount": len(journeys), "journeys": journeys,
        "fixedFailureSeedCount": len(seeds), "openP0": 0, "openP1": 0,
        "componentEvidence": component, "externalExecution": document["externalExecution"],
        "commercialClaimAllowed": False,
        "evidenceNote": (
            "Formal components executed and reconciled in one CI run; all payment, device and partner "
            "boundaries remain blocked and are not external evidence."
            if formal else
            "Only source contracts and exact synthetic vectors were validated; same-run CI evidence is pending."
        ),
    }
    canonical = json.dumps(report, sort_keys=True, separators=(",", ":"), ensure_ascii=False).encode()
    report["evidenceSha256"] = hashlib.sha256(canonical).hexdigest()
    write(args.output, report)
    if args.seed_ledger:
        write(args.seed_ledger, {
            "schemaVersion": "1.0", "requirementId": "T2-E2E-004", "commitSha": commit,
            "status": "PASS" if formal else "DESIGN_VALIDATED_ONLY",
            "fixedSeeds": [{**seed, "evidenceRef": SEED_EVIDENCE[seed["seed"]],
                            "result": "PASS" if formal else "PENDING_CI_EXECUTION"} for seed in seeds],
            "failedSeeds": [], "externalExecution": document["externalExecution"],
        })
    if args.defect_ledger:
        write(args.defect_ledger, {
            "schemaVersion": "1.0", "requirementId": "T2-E2E-004", "commitSha": commit,
            "status": "PASS", "p0": [], "p1": [],
            "goNoGo": "CONDITIONAL_PASS_AWAITING_SPONSOR_ACCEPTANCE" if formal else "DESIGN_VALIDATED_ONLY",
        })
    print(f"T2-GATE7E INTERNAL V1 BUSINESS COMPLETE OK: journeys={len(journeys)} seeds={len(seeds)} external=0")


if __name__ == "__main__":
    main()
