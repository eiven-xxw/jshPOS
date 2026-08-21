#!/usr/bin/env python3
"""Gate 6G：核对正式组件执行证据、既有三业态旅程及固定失败 seed。"""
from __future__ import annotations

import argparse
import hashlib
import json
import pathlib
import subprocess
import time

from run_t2_gate6d_internal_e2e import (
    read_flutter_successful_tests,
    read_xml_tests,
    validate_journeys,
)
from run_t2_gate6e_internal_alpha_candidate import (
    FLUTTER_TESTS as GATE6E_FLUTTER_TESTS,
    SERVER_TESTS as GATE6E_SERVER_TESTS,
    WEB_TESTS as GATE6E_WEB_TESTS,
    validate_candidate,
)


ROOT = pathlib.Path(__file__).resolve().parents[1]
VECTOR = ROOT / "contracts/t2/gate6g/test-vectors/internal-v1-core-candidate-v1.json"
BASE = ROOT / "contracts/t2/gate6d/test-vectors/internal-cash-e2e-v1.json"
ALPHA = ROOT / "contracts/t2/gate6e/test-vectors/internal-alpha-candidate-v1.json"
REQUIRED_COMPONENTS = {"server", "adminWeb", "flutterPos", "mysql", "sqlite"}
RUNTIME_ASSERTIONS = {
    "TRUSTED_TERMINAL_AND_EMPLOYEE_SESSION", "SIGNED_CATALOG_PACKAGE",
    "SIGNED_PROMOTION_PACKAGE", "FILE_SQLITE_FORWARD_MIGRATION", "SHIFT_OPEN",
    "SCAN_AND_PROMOTION_QUOTE", "AUTHORIZED_MANUAL_ADJUSTMENT",
    "ATOMIC_CASH_SETTLEMENT", "PRINT_TASK_PREVIEW_ONLY", "SHIFT_CLOSE",
    "SESSION_LOGOUT", "NO_CLIENT_TENANT_AUTHORITY",
}
FLUTTER_TESTS = set(GATE6E_FLUTTER_TESTS) | {
    "正式组合根经 HTTP 签名包和文件 SQLite 完成现金交易并安全注销",
    "服务端登录成功但本地运行时装配失败时撤销会话并保持失败关闭",
    "退货仓库必须使用与服务端契约一致的规范 ULID",
    "设备秘密默认提供者明确保持 HWD 阻断",
}
SEED_EVIDENCE = {
    "G6G-0001": "flutter:服务端登录成功但本地运行时装配失败时撤销会话并保持失败关闭",
    "G6G-0002": "flutter:非回环明文服务端和半配置均失败关闭",
    "G6G-0003": "flutter:退货仓库必须使用与服务端契约一致的规范 ULID",
    "G6G-0004": "flutter:设备秘密默认提供者明确保持 HWD 阻断",
    "G6G-0005": "server:ReturnsStrictTenantMapperGuardTest#requiresTrustedPrincipalBeforeEveryReturnMapperInvocation",
    "G6G-0006": "flutter:ACK lost resends the original identity and converges as duplicate",
    "G6G-0007": "server:SyncInboxReceiverTest#sameIdentityAndHashIsDuplicateButDifferentHashBlocksDevice",
    "G6G-0008": "server:ReturnSagaCoordinatorTest#unknownPaymentQueriesExistingRefundAndNeverRegeneratesCommand",
    "G6G-0009": "flutter:freezes quote, snapshot, order, cash and outbox atomically",
    "G6G-0010": "server:ReturnRulesTest#rejectsUntrustedIdentifiersAndDigests",
    "G6G-0011": "server:ReportingProjectionServiceTest#returnsExistingRebuildAndRejectsSameIdDifferentScope",
    "G6G-0012": "server:ReleaseGovernanceServiceTest#failsClosedForPendingOutboxDigestMismatchAndIdempotencyConflict",
}


def fail(message: str) -> None:
    raise SystemExit(f"T2-GATE6G E2E ERROR: {message}")


def load(path: pathlib.Path) -> dict:
    try:
        return json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as exception:
        fail(f"invalid JSON {path.relative_to(ROOT)}: {exception}")


def validate_vector(document: dict) -> list[dict]:
    if document.get("requirementId") != "T2-E2E-003":
        fail("requirement identity drift")
    if document.get("evidenceLevel") != "INTERNAL_V1_CORE_CANDIDATE":
        fail("evidence ceiling drift")
    if set(document.get("runtimeComponents", {})) != REQUIRED_COMPONENTS:
        fail("five formal runtime component declaration incomplete")
    if set(document.get("requiredRuntimeAssertions", [])) != RUNTIME_ASSERTIONS:
        fail("formal runtime assertion set drift")
    if set(document.get("industries", [])) != {
        "CONVENIENCE", "SNACK_DISCOUNT", "COMMUNITY_SUPERMARKET"
    }:
        fail("three-industry scope drift")
    seeds = document.get("failureSeeds", [])
    if len(seeds) != len(SEED_EVIDENCE) or {item.get("seed") for item in seeds} != set(SEED_EVIDENCE):
        fail("fixed failure seed ledger incomplete")
    allowed = {
        "LOGOUT_AND_FAIL_CLOSED", "REJECT", "BLOCKED_UNAVAILABLE",
        "RETURN_ORIGINAL", "QUERY_ORIGINAL", "ROLLBACK_ALL", "RECONCILE",
        "FAIL_CLOSED",
    }
    if any(item.get("expected") not in allowed for item in seeds):
        fail("unsupported fixed seed outcome")
    if document.get("defectLedger") != {"p0": [], "p1": []}:
        fail("open P0/P1 defects force NO-GO")
    if any(value != 0 for value in document.get("externalExecution", {}).values()):
        fail("external execution must remain zero")
    if document.get("commercialClaimAllowed") is not False:
        fail("commercial claim must remain forbidden")
    return seeds


def suite_elapsed(suites: dict[str, set[str]], root: pathlib.Path) -> float:
    """JUnit 时间只作为同次 CI 趋势，不作为商业 SLA。"""
    import xml.etree.ElementTree as etree

    elapsed = 0.0
    for report in root.rglob("*.xml"):
        try:
            node = etree.parse(report).getroot()
            elapsed += float(node.attrib.get("time", "0"))
        except (ValueError, etree.ParseError):
            continue
    return round(elapsed, 3)


def validate_bundle(bundle: pathlib.Path, vector: dict) -> dict:
    server = read_xml_tests(bundle, "server")
    for class_name, methods in GATE6E_SERVER_TESTS.items():
        if class_name not in server or not methods.issubset(server[class_name]):
            fail(f"server Owner evidence missing: {class_name} {sorted(methods)}")
    mysql = read_xml_tests(bundle, "mysql")
    if "ReleaseMigrationMySqlIT" not in mysql:
        fail("empty MySQL forward migration evidence missing")
    web = read_xml_tests(bundle, "web", "*.xml")
    web_names = set().union(*web.values()) if web else set()
    missing_web = {
        name for name in GATE6E_WEB_TESTS if not any(name in actual for actual in web_names)
    }
    if missing_web:
        fail(f"formal Web component evidence missing: {sorted(missing_web)}")
    flutter_counts: dict[str, int] = {}
    for producer in ("pos-linux", "pos-windows"):
        reports = list((bundle / producer).rglob("flutter-tests.jsonl"))
        if len(reports) != 1:
            fail(f"Flutter evidence missing or duplicated: {producer}")
        successful = read_flutter_successful_tests(reports[0])
        missing = {
            name for name in FLUTTER_TESTS if not any(name in actual for actual in successful)
        }
        if missing:
            fail(f"formal POS evidence missing in {producer}: {sorted(missing)}")
        flutter_counts[producer] = len(successful)
    governance = list((bundle / "governance").rglob("gate6g-governance.json"))
    if len(governance) != 1:
        fail("Gate 6G governance evidence missing or duplicated")
    statuses = load(governance[0]).get("statuses", {})
    if statuses.get("T2-E2E-003") != "VERIFIED":
        fail("same-commit E2E status must be VERIFIED")
    runtime_reports = list((bundle / "runtime-stack").rglob("runtime-stack-smoke.json"))
    if len(runtime_reports) != 1:
        fail("simultaneous runtime stack evidence missing or duplicated")
    runtime = load(runtime_reports[0])
    if (runtime.get("status") != "PASS" or runtime.get("simultaneousProcessWindow") is not True or
            runtime.get("syntheticBoundary") is not True or runtime.get("providerNetworkCalls") != 0 or
            runtime.get("realDeviceCommands") != 0 or runtime.get("commercialClaimAllowed") is not False):
        fail("simultaneous runtime stack evidence boundary invalid")
    targets = vector["performanceTargets"]
    performance = {
        "classification": targets["classification"],
        "serverJUnitSeconds": suite_elapsed(server, bundle / "server"),
        "mysqlJUnitSeconds": suite_elapsed(mysql, bundle / "mysql"),
        "note": "JUnit aggregated execution time and CI job duration are internal trend data only",
    }
    return {
        "executionModel": "FORMAL_COMPONENT_EXECUTION_PLUS_CONTRACT_RECONCILIATION",
        "serverOwnerSuites": len(GATE6E_SERVER_TESTS),
        "serverRequiredTests": sum(len(value) for value in GATE6E_SERVER_TESTS.values()),
        "mysqlMigration": "PASS",
        "webRequiredTests": len(GATE6E_WEB_TESTS),
        "flutterRequiredTestsPerPlatform": len(FLUTTER_TESTS),
        "flutterSuccessfulTests": flutter_counts,
        "performanceBaseline": performance,
        "runtimeStack": runtime,
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
    started = time.monotonic()
    vector = load(VECTOR)
    seeds = validate_vector(vector)
    base = load(BASE)
    alpha = load(ALPHA)
    base_results = validate_journeys(base)
    alpha_results, _ = validate_candidate(alpha, base)
    formal = args.bundle_dir is not None
    component = validate_bundle(args.bundle_dir, vector) if formal else {"mode": "SOURCE_CONTRACT"}
    commit = subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=ROOT, text=True).strip()
    report = {
        "schemaVersion": "1.0", "requirementId": "T2-E2E-003", "status": "PASS",
        "evidenceLevel": "INTERNAL_V1_CORE_CANDIDATE",
        "executionMode": "SAME_RUN_FORMAL_COMPONENT_EVIDENCE" if formal else "SOURCE_CONTRACT",
        "internalDecision": "CONDITIONAL_GO_INTERNAL_ONLY" if formal else "DESIGN_VALIDATED_ONLY",
        "commitSha": commit, "tenantCount": 2, "industryCount": 3,
        "baseSaleJourneyCount": len(base_results), "returnJourneyCount": len(alpha_results),
        "fixedFailureSeedCount": len(seeds), "openP0": 0, "openP1": 0,
        "componentEvidence": component, "runnerSeconds": round(time.monotonic() - started, 3),
        "externalExecution": vector["externalExecution"], "commercialClaimAllowed": False,
        "evidenceNote": (
            "Formal components executed and reconciled in one CI run; synthetic HTTP/device/payment "
            "boundaries remain non-external evidence."
            if formal else
            "Only source contracts and fixed vectors were validated locally; formal same-run component "
            "execution remains pending CI."
        ),
    }
    canonical = json.dumps(report, sort_keys=True, separators=(",", ":"), ensure_ascii=False).encode()
    report["evidenceSha256"] = hashlib.sha256(canonical).hexdigest()
    write(args.output, report)
    if args.seed_ledger:
        write(args.seed_ledger, {
            "schemaVersion": "1.0", "requirementId": "T2-E2E-003", "commitSha": commit,
            "status": "PASS" if formal else "DESIGN_VALIDATED_ONLY",
            "fixedSeeds": [{**item, "evidenceRef": SEED_EVIDENCE[item["seed"]],
                            "result": "PASS" if formal else "PENDING_CI_EXECUTION"} for item in seeds],
            "failedSeeds": [], "externalExecution": vector["externalExecution"],
        })
    if args.defect_ledger:
        write(args.defect_ledger, {
            "schemaVersion": "1.0", "requirementId": "T2-E2E-003", "commitSha": commit,
            "status": "PASS", "p0": [], "p1": [],
            "goNoGo": "CONDITIONAL_GO_INTERNAL_ONLY" if formal else "DESIGN_VALIDATED_ONLY",
        })
    print(f"T2-GATE6G INTERNAL V1 CORE OK: sales={len(base_results)} returns={len(alpha_results)} seeds={len(seeds)} external=0")


if __name__ == "__main__":
    main()
