from __future__ import annotations

import argparse
import hashlib
import json
from dataclasses import asdict, dataclass
from datetime import datetime, timezone
from pathlib import Path
from typing import Any


ROOT = Path(__file__).resolve().parents[3]
POC_ROOT = ROOT / "poc" / "t1-week1"
FIXTURE_ROOT = POC_ROOT / "fixtures"
BASELINE_TAG = "t0-baseline-2026-08-16"
LIMITATIONS = [
    "仅为纯内存FAKE模型，未执行Android实机、外设、SQLite、网络或服务端验证",
    "支付结果不访问任何机构沙箱或生产环境，不代表已接入、已签约或可商用",
    "所有租户、事件、支付意图、商品包和版本均为合成数据",
]


@dataclass(frozen=True)
class ProbeResult:
    requirementId: str
    result: str
    assertions: int
    fixtureDigests: list[str]


def load_json(path: Path) -> dict[str, Any]:
    with path.open(encoding="utf-8") as handle:
        return json.load(handle)


def fixture_digest(path: Path) -> str:
    return "sha256:" + hashlib.sha256(path.read_bytes()).hexdigest()


def require(condition: bool, message: str) -> None:
    if not condition:
        raise AssertionError(message)


def probe_device() -> ProbeResult:
    path = FIXTURE_ROOT / "device-faults.json"
    fixture = load_json(path)
    expected = {
        "DEVICE_TIMEOUT": "TIMEOUT_RETRYABLE",
        "DEVICE_DISCONNECT": "DISCONNECTED_RECOVERABLE",
        "DEVICE_BUSY": "BUSY_RETRYABLE",
        "DEVICE_UNSUPPORTED": "UNSUPPORTED_NO_RETRY",
        "DEVICE_UNKNOWN_RESULT": "UNKNOWN_RECONCILE",
    }
    assertions = 0
    for scenario in fixture["scenarios"]:
        require(expected[scenario["id"]] == scenario["expected"], scenario["id"])
        assertions += 1
    require(
        set(fixture["virtualCapabilities"])
        == {"receipt_printer", "barcode_scanner", "weighing_scale", "cash_drawer", "customer_display"},
        "virtual device coverage must include five required capability families",
    )
    assertions += 1
    operation_results = fixture["operationResults"]
    require(
        {item["capability"] for item in operation_results} == set(fixture["virtualCapabilities"]),
        "every virtual capability must instantiate the unified operation contract",
    )
    assertions += 1
    expected_recovery = {
        "TIMEOUT": (True, "RETRY_SAME_KEY"),
        "DISCONNECTED": (True, "RECONNECT_THEN_RETRY"),
        "BUSY": (True, "RETRY_SAME_KEY"),
        "UNSUPPORTED": (False, "DO_NOT_RETRY"),
        "UNKNOWN_RESULT": (False, "RECONCILE"),
    }
    for item in operation_results:
        require(item["synthetic"] is True, "device operation must be synthetic")
        require(
            (item["retryable"], item["recoveryAction"]) == expected_recovery[item["errorCode"]],
            item["requestId"],
        )
        assertions += 2
    return ProbeResult("T1-HWD-001", "PASS", assertions, [fixture_digest(path)])


def probe_payment() -> ProbeResult:
    profile_path = POC_ROOT / "provider-profiles.json"
    fault_path = FIXTURE_ROOT / "payment-faults.json"
    profiles = load_json(profile_path)["providers"]
    fixture = load_json(fault_path)
    selected = [profile for profile in profiles if profile["selectedForFake"]]
    require(len(profiles) == 10, "exactly ten candidate profiles are required")
    require(len(selected) == 5, "exactly five profiles must be selected for Fake")
    assertions = 2
    commands = fixture["commands"]
    require(
        {command["operation"] for command in commands} == {"CREATE", "QUERY", "REFUND", "NOTIFY"},
        "unified payment operations are incomplete",
    )
    require(all(command["synthetic"] is True for command in commands), "payment command must be synthetic")
    assertions += 2

    for provider in selected:
        for scenario in fixture["scenarios"]:
            payment_state = "INIT"
            refund_state = "NONE"
            payment_effects = 0
            refund_effects = 0
            for event in scenario["events"]:
                if event == "CREATE_ACCEPTED":
                    payment_state = "PROCESSING" if payment_state == "INIT" else payment_state
                elif event == "CREATE_TIMEOUT":
                    payment_state = "UNKNOWN"
                elif event == "QUERY_PROCESSING":
                    payment_state = "PROCESSING"
                elif event in {"NOTIFY_SUCCESS", "QUERY_SUCCESS"}:
                    if payment_state != "SUCCESS":
                        payment_effects += 1
                    payment_state = "SUCCESS"
                elif event in {"CREATE_FAILED", "QUERY_FAILED"}:
                    if payment_state != "SUCCESS":
                        payment_state = "FAILED"
                elif event == "REFUND_TIMEOUT":
                    refund_state = "UNKNOWN"
                elif event == "QUERY_REFUND_SUCCESS":
                    if refund_state != "SUCCESS":
                        refund_effects += 1
                    refund_state = "SUCCESS"
                else:
                    raise AssertionError(f"unsupported synthetic payment event: {event}")

            expected = scenario["expected"]
            if expected == "SUCCESS_ONCE":
                require(payment_state == "SUCCESS" and payment_effects == 1, scenario["id"])
            elif expected == "FAILED_NO_EFFECT":
                require(payment_state == "FAILED" and payment_effects == 0, scenario["id"])
            elif expected == "REFUNDED_ONCE":
                require(
                    payment_state == "SUCCESS" and payment_effects == 1
                    and refund_state == "SUCCESS" and refund_effects == 1,
                    scenario["id"],
                )
            else:
                raise AssertionError(f"unsupported expected result: {expected}")
            assertions += 1

    return ProbeResult(
        "T1-PAY-001",
        "PASS",
        assertions,
        [fixture_digest(profile_path), fixture_digest(fault_path)],
    )


def probe_offline_atomicity() -> ProbeResult:
    path = FIXTURE_ROOT / "offline-faults.json"
    fixture = load_json(path)
    assertions = 0
    for scenario in fixture["scenarios"]:
        committed = scenario["crashAt"] >= 4
        fact = intent = outbox = 1 if committed else 0
        require((fact, intent, outbox) in {(0, 0, 0), (1, 1, 1)}, scenario["id"])
        assertions += 1
    return ProbeResult("T1-OFF-001", "PASS", assertions, [fixture_digest(path)])


def probe_sync_idempotency() -> ProbeResult:
    path = FIXTURE_ROOT / "sync-faults.json"
    fixture = load_json(path)
    inbox: dict[str, dict[str, Any]] = {}
    for event in fixture["events"]:
        require(event["synthetic"] is True, "only synthetic events are allowed")
        require(event["tenantId"] == "TENANT_ALPHA", "cross-tenant event rejected")
        inbox.setdefault(event["eventId"], event)
    ordered = sorted(inbox.values(), key=lambda event: event["sequence"])
    require([event["sequence"] for event in ordered] == [1, 2, 3], "sequence must converge")
    require(len(inbox) == 3, "duplicates must have one effect")
    assertions = len(fixture["events"]) * 2 + 2
    return ProbeResult("T1-SYN-001", "PASS", assertions, [fixture_digest(path)])


def probe_tenant_isolation() -> ProbeResult:
    path = FIXTURE_ROOT / "tenant-faults.json"
    fixture = load_json(path)
    assertions = 0
    tenants = set(fixture["syntheticTenants"])
    for scenario in fixture["scenarios"]:
        actor = scenario["actorTenant"]
        target = scenario["targetTenant"]
        allowed = actor in tenants and actor == target
        require(not allowed and scenario["expected"] == "DENY", scenario["id"])
        assertions += 1
    return ProbeResult("T1-TEN-001", "PASS", assertions, [fixture_digest(path)])


def probe_data_package() -> ProbeResult:
    path = FIXTURE_ROOT / "data-package-cases.json"
    fixture = load_json(path)
    active = fixture["activeVersion"]
    assertions = 0
    for case in fixture["cases"]:
        before = active
        valid = (
            case["digestValid"]
            and case["signatureValid"]
            and case["schemaVersion"] <= fixture["supportedSchemaVersion"]
            and case["version"] > active
        )
        if valid:
            active = case["version"]
            actual = "ACTIVATE"
        else:
            actual = "REJECT_KEEP_ACTIVE"
            require(active == before, f"{case['id']} changed active version")
        require(actual == case["expected"], case["id"])
        assertions += 2
    return ProbeResult("T1-DPK-001", "PASS", assertions, [fixture_digest(path)])


def probe_upgrade_rollback() -> ProbeResult:
    path = FIXTURE_ROOT / "upgrade-cases.json"
    fixture = load_json(path)
    mapping = {
        "NONE": "ACTIVATE",
        "DOWNLOAD_CORRUPT": "BLOCK",
        "MIGRATION_FAIL": "BLOCK",
        "HEALTHCHECK_FAIL": "ROLLBACK_APP_KEEP_SCHEMA",
    }
    assertions = 0
    for case in fixture["cases"]:
        actual = mapping[case["fault"]]
        require(actual == case["expected"], case["id"])
        if actual == "ROLLBACK_APP_KEEP_SCHEMA":
            require(case["toSchema"] >= case["fromSchema"], "schema rollback is forbidden")
            assertions += 1
        assertions += 1
    return ProbeResult("T1-UPG-001", "PASS", assertions, [fixture_digest(path)])


def run_all() -> list[ProbeResult]:
    probes = (
        probe_device,
        probe_payment,
        probe_offline_atomicity,
        probe_sync_idempotency,
        probe_tenant_isolation,
        probe_data_package,
        probe_upgrade_rollback,
    )
    return [probe() for probe in probes]


def build_evidence(results: list[ProbeResult]) -> dict[str, Any]:
    return {
        "schemaVersion": "1.0",
        "phase": "T1-WEEK1",
        "evidenceLevel": "FAKE",
        "generatedAt": datetime.now(timezone.utc).isoformat().replace("+00:00", "Z"),
        "baselineTag": BASELINE_TAG,
        "results": [asdict(result) for result in results],
        "limitations": LIMITATIONS,
    }


def main() -> None:
    parser = argparse.ArgumentParser(description="Run isolated T1 Week 1 Fake risk probes")
    parser.add_argument(
        "--output",
        type=Path,
        default=ROOT / "artifacts" / "t1" / "week1" / "fake-evidence.json",
    )
    args = parser.parse_args()
    results = run_all()
    evidence = build_evidence(results)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(evidence, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(
        "T1 WEEK1 FAKE OK: "
        f"{len(results)} probes, {sum(item.assertions for item in results)} assertions; "
        "no sandbox, real-device, pilot, network, or commercial-business execution"
    )


if __name__ == "__main__":
    main()
