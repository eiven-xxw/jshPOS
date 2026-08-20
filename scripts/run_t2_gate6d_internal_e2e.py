#!/usr/bin/env python3
"""Gate 6D 内部合成现金闭环：组合正式 Owner 单测证据并逐旅程核对守恒。"""
from __future__ import annotations

import argparse
import hashlib
import json
import pathlib
import subprocess
import xml.etree.ElementTree as ET
from decimal import Decimal, InvalidOperation


ROOT = pathlib.Path(__file__).resolve().parents[1]
FIXTURE = ROOT / "contracts/t2/gate6d/test-vectors/internal-cash-e2e-v1.json"
INDUSTRIES = {"CONVENIENCE", "SNACK_DISCOUNT", "COMMUNITY_SUPERMARKET"}
SERVER_TESTS = {
    "OrgAndStoreServiceTest": {"createsUpdatesAndCalculatesStoreBusinessDate"},
    "CatalogApplicationServiceTest": {"injectsTrustedTenantAcrossDefinitionsAndProductAggregate"},
    "PriceBookServiceTest": {"publishesCanonicalStoreVersionOnlyAfterDataScopeAuthorization"},
    "CatalogPackageServiceTest": {"requiresExternalPortsAndUsesTrustedTenantNamespaceWhenConfigured"},
    "ShiftServiceTest": {"opensShiftOnlyForTheTrustedCashierAndStore", "closesOnlyWithAnApprovalMatchingTheExactCashCount"},
    "PromotedCashOrderServiceTest": {"verifiesPromotionOwnerSnapshotAndWritesOnlyOrderOwnedFacts"},
    "SyncInboxReceiverTest": {"commitsNewInboxBeforeReturningForProcessing", "sameIdentityAndHashIsDuplicateButDifferentHashBlocksDevice"},
    "PromotedOrderEventDispatcherTest": {"mapsFullFrozenPayloadWithoutAcceptingClientTenant"},
    "InventoryLedgerServiceTest": {"appliesSaleAtomicallyFromAuthoritativeSnapshot"},
    "CostingRulesTest": {"freezesOutboundCostAndClosesRoundingResidueAtZero"},
    "ReportingProjectionServiceTest": {"appliesSalesOnceAndFreezesCheckpoint", "returnsOriginalForDuplicateSameHashAndRejectsDifferentHash"},
    "ReportQueryServiceTest": {"queriesOnlyActiveVersionInsideTrustedStoreScope"},
}
FLUTTER_TESTS = {
    "可信虚构终端进入员工登录并可安全退出",
    "SQLite V7 preserves signed packages and transaction allocation schema",
    "suspend and resume preserve the same draft and exact lines",
    "freezes quote, snapshot, order, cash and outbox atomically",
    "ACK lost resends the original identity and converges as duplicate",
    "现金成交使用稳定幂等键并只生成打印预览",
}
WEB_TESTS = {
    "provides a business operations home instead of the framework demo home",
    "uses formal foundation APIs for staff scopes and preserves trusted tenant context",
    "supports brand multi-unit import errors and price publication through catalog APIs",
}


def fail(message: str) -> None:
    raise SystemExit(f"T2-GATE6D E2E ERROR: {message}")


def exact(value: str, field: str) -> Decimal:
    try:
        number = Decimal(value)
    except InvalidOperation as exception:
        raise SystemExit(f"T2-GATE6D E2E ERROR: invalid decimal {field}") from exception
    if not number.is_finite() or number.as_tuple().exponent < -6:
        fail(f"{field} exceeds six decimal places")
    return number


def validate_journeys(document: dict) -> list[dict]:
    journeys = document.get("journeys", [])
    if len(journeys) != 6:
        fail("exactly six two-tenant/three-industry journeys are required")
    tenants = {item["tenantId"] for item in journeys}
    if tenants != {"TENANT_A", "TENANT_B"}:
        fail("journeys must use exactly two fictional tenants")
    for tenant in tenants:
        if {item["industry"] for item in journeys if item["tenantId"] == tenant} != INDUSTRIES:
            fail(f"tenant {tenant} does not cover all three industries")
    for field in ("id", "storeId", "terminalId", "shift", "order", "outbox"):
        values = []
        for item in journeys:
            if field == "shift": values.append(item[field]["shiftId"])
            elif field == "order": values.append(item[field]["orderId"])
            elif field == "outbox": values.append(item[field]["eventId"])
            else: values.append(item[field])
        if len(values) != len(set(values)):
            fail(f"duplicate journey identity: {field}")

    results = []
    for item in journeys:
        prefix = item["id"]
        package = item["package"]
        expected_prefix = f'{item["tenantId"]}|{item["storeId"]}|'
        if not package["payload"].startswith(expected_prefix) or package["signatureVerified"] is not True:
            fail(f"{prefix} package tenant/store/signature mismatch")
        package_sha = hashlib.sha256(package["payload"].encode()).hexdigest()
        if item["shift"]["statusBeforeSale"] != "OPEN" or item["close"]["state"] != "CLOSED":
            fail(f"{prefix} shift state chain is incomplete")

        quantity = exact(item["basket"]["quantity"], f"{prefix}.quantity")
        gross = item["promotion"]["grossMinor"]
        discount = item["promotion"]["discountMinor"]
        surcharge = item["promotion"]["surchargeMinor"]
        receivable = item["promotion"]["receivableMinor"]
        if quantity * item["basket"]["unitPriceMinor"] != Decimal(gross):
            fail(f"{prefix} line gross is not exact")
        if gross - discount + surcharge != receivable or receivable < 0:
            fail(f"{prefix} gross-discount+surcharge invariant failed")
        if item["cash"]["tenderedMinor"] - receivable != item["cash"]["changeMinor"]:
            fail(f"{prefix} cash change invariant failed")
        if item["order"]["state"] != "COMPLETED" or item["order"]["paymentState"] != "PAID":
            fail(f"{prefix} server order did not converge")
        if item["outbox"]["eventType"] != "order.submitted.v2" or item["outbox"]["ackState"] != "ACKED":
            fail(f"{prefix} sync did not converge with original event")

        opening = exact(item["inventory"]["openingOnHand"], f"{prefix}.opening")
        sale_out = exact(item["inventory"]["saleOut"], f"{prefix}.saleOut")
        closing = exact(item["inventory"]["closingOnHand"], f"{prefix}.closing")
        if sale_out != quantity or opening - sale_out != closing:
            fail(f"{prefix} inventory ledger does not reconcile")
        unit_cost = exact(item["cost"]["unitCostMinor"], f"{prefix}.unitCost")
        sale_cost = exact(item["cost"]["saleCostMinor"], f"{prefix}.saleCost")
        if unit_cost * quantity != sale_cost:
            fail(f"{prefix} frozen sale cost does not reconcile")

        report = item["report"]
        expected_report = {
            "orderCount": 1, "grossMinor": gross, "discountMinor": discount,
            "surchargeMinor": surcharge, "receivableMinor": receivable,
            "cashMinor": receivable, "saleQuantity": item["basket"]["quantity"],
            "saleCostMinor": item["cost"]["saleCostMinor"],
        }
        if report != expected_report:
            fail(f"{prefix} reporting projection diverges from authoritative facts")
        close = item["close"]
        theoretical = item["shift"]["openingCashMinor"] + receivable
        if close["theoreticalCashMinor"] != theoretical:
            fail(f"{prefix} shift theoretical cash mismatch")
        if close["countedCashMinor"] - theoretical != close["varianceMinor"]:
            fail(f"{prefix} shift variance mismatch")
        canonical = json.dumps(item, sort_keys=True, separators=(",", ":"), ensure_ascii=False).encode()
        results.append({"journeyId": prefix, "result": "PASS", "packageSha256": package_sha,
                        "journeySha256": hashlib.sha256(canonical).hexdigest()})
    return results


def read_xml_tests(bundle: pathlib.Path, producer: str, pattern: str = "TEST-*.xml") -> dict[str, set[str]]:
    root = bundle / producer
    if not root.exists():
        fail(f"missing CI evidence producer {producer}")
    suites: dict[str, set[str]] = {}
    for path in root.rglob(pattern):
        node = ET.parse(path).getroot()
        if any(int(float(node.attrib.get(key, "0"))) for key in ("failures", "errors", "skipped")):
            fail(f"non-green test report {path.name}")
        class_name = node.attrib.get("name", "").split(".")[-1]
        suites.setdefault(class_name, set()).update(case.attrib.get("name", "") for case in node.findall(".//testcase"))
    return suites


def validate_ci_bundle(bundle: pathlib.Path) -> dict:
    suites = read_xml_tests(bundle, "server")
    for class_name, methods in SERVER_TESTS.items():
        if class_name not in suites or not methods.issubset(suites[class_name]):
            fail(f"missing successful Owner runtime evidence {class_name}: {sorted(methods)}")
    mysql = read_xml_tests(bundle, "mysql")
    if "ReleaseMigrationMySqlIT" not in mysql:
        fail("missing complete MySQL migration evidence")
    # Vitest 的 JUnit 文件名不是 Maven Surefire 的 TEST-*.xml，仍必须逐份解析并校验失败、错误和跳过数。
    web = read_xml_tests(bundle, "web", "*.xml")
    web_names = set().union(*web.values()) if web else set()
    missing_web = {name for name in WEB_TESTS if not any(name in actual for actual in web_names)}
    if missing_web:
        fail(f"missing admin component evidence: {sorted(missing_web)}")

    flutter_names: set[str] = set()
    for producer in ("pos-linux", "pos-windows"):
        files = list((bundle / producer).rglob("flutter-tests.jsonl"))
        if not files:
            fail(f"missing Flutter machine evidence {producer}")
        for line in files[0].read_text(encoding="utf-8", errors="replace").splitlines():
            try: event = json.loads(line)
            except json.JSONDecodeError: continue
            if event.get("type") == "testStart":
                flutter_names.add(event.get("test", {}).get("name", ""))
    missing_flutter = {name for name in FLUTTER_TESTS if not any(name in actual for actual in flutter_names)}
    if missing_flutter:
        fail(f"missing cross-platform POS evidence: {sorted(missing_flutter)}")

    governance_files = list((bundle / "governance").rglob("gate6d-governance.json"))
    if not governance_files:
        fail("missing Gate 6D governance evidence")
    governance = json.loads(governance_files[0].read_text(encoding="utf-8"))
    if governance["requirements"]["T2-E2E-001"] != "IN_PROGRESS":
        fail("E2E CI ran without sequential admission")
    return {"serverOwnerSuites": len(SERVER_TESTS), "flutterRequiredTests": len(FLUTTER_TESTS),
            "webRequiredTests": len(WEB_TESTS), "mysqlMigration": "PASS"}


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--bundle-dir", type=pathlib.Path)
    parser.add_argument("--output", type=pathlib.Path, required=True)
    args = parser.parse_args()
    document = json.loads(FIXTURE.read_text(encoding="utf-8"))
    if document.get("requirementId") != "T2-E2E-001" or document.get("evidenceLevel") != "SYNTHETIC_E2E":
        fail("fixture evidence identity mismatch")
    if set(document.get("externalExecution", {}).values()) != {0}:
        fail("external execution must remain zero")
    attacks = document.get("attacks", [])
    if len(attacks) < 6 or any(item.get("expected") not in {"REJECT", "RETURN_ORIGINAL"} for item in attacks):
        fail("negative and replay matrix is incomplete")
    journeys = validate_journeys(document)
    bundle_evidence = validate_ci_bundle(args.bundle_dir) if args.bundle_dir else {"mode": "SOURCE_CONTRACT"}
    output = args.output if args.output.is_absolute() else ROOT / args.output
    output.parent.mkdir(parents=True, exist_ok=True)
    report = {
        "schemaVersion": "1.0", "requirementId": "T2-E2E-001", "status": "PASS",
        "evidenceLevel": "SYNTHETIC_E2E", "commitSha": subprocess.check_output(
            ["git", "rev-parse", "HEAD"], cwd=ROOT, text=True).strip(),
        "journeyCount": len(journeys), "tenantCount": 2, "storeCount": 6,
        "terminalCount": 6, "industries": sorted(INDUSTRIES), "attackCount": len(attacks),
        "journeys": journeys, "componentEvidence": bundle_evidence,
        "providerNetworkCalls": 0, "realDeviceCommands": 0, "onsitePilots": 0,
        "fullAlphaRuns": 0, "commercialClaimAllowed": False,
        "evidenceNote": "Contract-composed SYNTHETIC_E2E; not SANDBOX, REAL_DEVICE, PILOT, FULL_ALPHA or PRODUCTION evidence.",
    }
    encoded = json.dumps(report, sort_keys=True, separators=(",", ":"), ensure_ascii=False).encode()
    report["evidenceSha256"] = hashlib.sha256(encoded).hexdigest()
    output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(f"T2-GATE6D INTERNAL E2E OK: journeys={len(journeys)} attacks={len(attacks)} external=0")


if __name__ == "__main__":
    main()
