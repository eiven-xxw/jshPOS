#!/usr/bin/env python3
"""Gate 6I-Prep 外部准入快照与完整 Alpha 冻结离线门禁。"""
from __future__ import annotations

import argparse
import csv
import json
import pathlib
import re
import subprocess


ROOT = pathlib.Path(__file__).resolve().parents[1]
BASELINE = "e2bd699ad565845c363ffe478320eb2b3f9a0015"
BRANCH = "codex/t2-gate6i-prep-external-p0-alpha-freeze"
CONTRACT_DIR = ROOT / "contracts/t2/gate6i-prep"
RTM = ROOT / "docs/governance/rtm.csv"


def fail(condition: bool, message: str) -> None:
    if not condition:
        raise SystemExit(f"T2-GATE6I-PREP ERROR: {message}")


def load(name: str) -> dict:
    path = CONTRACT_DIR / name
    try:
        return json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as exception:
        raise SystemExit(f"T2-GATE6I-PREP ERROR: invalid {path.relative_to(ROOT)}: {exception}")


def git(*args: str) -> str:
    completed = subprocess.run(
        ["git", "-c", "core.quotepath=false", *args], cwd=ROOT,
        check=False, capture_output=True, text=True, encoding="utf-8",
    )
    fail(completed.returncode == 0, f"git {' '.join(args)} failed: {completed.stderr.strip()}")
    return completed.stdout.strip()


def validate_admission(rows: dict[str, dict[str, str]]) -> dict:
    admission = load("gate6i-prep-admission.json")
    fail(admission.get("baselineCommit") == BASELINE, "baseline commit drift")
    fail(admission.get("branch") == BRANCH, "branch contract drift")
    fail(admission.get("decision") == "CONDITIONAL_GO_PREP_ONLY", "decision drift")
    fail(admission.get("evidenceLevel") == "STATIC_GOVERNANCE_FREEZE", "evidence ceiling drift")
    accepted = {
        "T2-UX-001": "ACCEPTED", "T2-PERF-001": "ACCEPTED",
        "T2-OPS-001": "ACCEPTED", "T2-RC-001": "ACCEPTED",
    }
    preserved = {
        "T2-PAY-002": "BLOCKED", "T2-HWD-001": "BLOCKED", "T2-PRN-001": "BLOCKED",
        "T2-PAR-001": "BLOCKED", "T2-UAT-001": "DRAFT", "T2-REL-001": "DRAFT",
        "T2-LIC-001": "DEFERRED", "T2-JSH-001": "DEFERRED",
    }
    fail(admission.get("acceptedGate6HRequirements") == accepted, "Gate 6H acceptance map drift")
    fail(admission.get("preservedStates") == preserved, "preserved state map drift")
    for requirement_id, expected in {**accepted, **preserved}.items():
        fail(rows.get(requirement_id, {}).get("status") == expected, f"RTM mismatch: {requirement_id}")
    forbidden = set(admission.get("forbiddenActions", []))
    required_forbidden = {
        "PAYMENT_PROVIDER_NETWORK", "REAL_FUNDS", "REAL_DEVICE_INSTALL_OR_COMMAND",
        "REAL_PRINT_SCAN_SCALE_DRAWER_DISPLAY_EXECUTION", "PARTNER_OR_STORE_CONTACT",
        "ONSITE_PILOT", "FULL_ALPHA_UAT_EXECUTION", "PRODUCTION_DEPLOYMENT",
        "COMMERCIAL_AVAILABILITY_CLAIM", "SECRET_REAL_PII_OR_CONTROLLED_ORIGINAL_IN_REPOSITORY",
    }
    fail(required_forbidden <= forbidden, "forbidden action boundary incomplete")
    counters = admission.get("externalExecution", {})
    for field in (
        "providerNetworkCalls", "realFundsTransactions", "realDeviceCommands", "realPeripheralCommands",
        "partnerContacts", "onsitePilots", "fullAlphaRuns", "productionDeployments",
    ):
        fail(counters.get(field) == 0, f"external execution must remain zero: {field}")
    fail(counters.get("commercialClaimAllowed") is False, "commercial claim must remain forbidden")
    fail(admission.get("overallDecision") == "PREPARED_NO_GO_EXTERNAL_EXECUTION_FULL_ALPHA_AND_RELEASE",
         "overall NO-GO decision drift")
    return admission


def validate_payment() -> dict:
    payment = load("payment-execution-admission.json")
    fail(payment.get("requirementId") == "T2-PAY-002" and payment.get("status") == "BLOCKED",
         "payment identity/status drift")
    fail(payment.get("selectedProvider", {}).get("code") == "LAKALA", "payment candidate drift")
    items = payment.get("mandatoryItems", [])
    fail(len(items) == 11 and len({item.get("id") for item in items}) == 11, "payment item set incomplete")
    fail(all(item.get("state") == "MISSING" and item.get("opaqueReference") is None and item.get("sha256") is None
             for item in items), "payment missing evidence was promoted")
    fail(payment.get("receivedItemCount") == 0 and payment.get("requiredItemCount") == 11,
         "payment controlled material count drift")
    fail(payment.get("verifiedDocumentStatus") == "NOT_ACHIEVED" and
         payment.get("executionAdmission") == "NO_GO", "payment evidence/admission drift")
    fail(payment.get("providerNetworkAllowed") is False and payment.get("providerNetworkCalls") == 0,
         "payment network must remain zero")
    fail(payment.get("realFundsAllowed") is False and payment.get("realFundsTransactions") == 0,
         "real funds must remain zero")
    return payment


def validate_hardware_print() -> dict:
    hardware = load("hardware-print-execution-admission.json")
    fail(hardware.get("requirementIds") == ["T2-HWD-001", "T2-PRN-001"], "hardware/print IDs drift")
    fail(hardware.get("statuses") == {"T2-HWD-001": "BLOCKED", "T2-PRN-001": "BLOCKED"},
         "hardware/print status drift")
    hosts = hardware.get("hostCandidates", [])
    fail(len(hosts) == 2 and hosts[0].get("vendor") == "商米" and hosts[1].get("vendor") == "iMin",
         "host candidate coverage drift")
    fail(all(host.get("exactSku") is None and host.get("firmwareBuild") is None and
             host.get("controlledSdkReference") is None for host in hosts), "unverified host data promoted")
    bundles = hardware.get("capabilityBundles", [])
    expected = {"HWD-PRIMARY", "HWD-COMPATIBLE", "PRN-A", "PRN-B", "SCANNER", "SCALE",
                "CASH-DRAWER", "CUSTOMER-DISPLAY", "APK-UPGRADE", "WARRANTY-RMA"}
    fail({item.get("id") for item in bundles} == expected and all(item.get("state") == "MISSING" for item in bundles),
         "hardware/print controlled bundle coverage drift")
    fail(hardware.get("verifiedBundleCount") == 0 and hardware.get("requiredBundleCount") == 10,
         "hardware/print evidence count drift")
    fail(hardware.get("verifiedDocumentStatus") == "NOT_ACHIEVED" and
         hardware.get("executionAdmission") == "NO_GO", "hardware/print admission drift")
    fail(hardware.get("realDeviceCommandsAllowed") is False and hardware.get("realDeviceCommands") == 0,
         "real device commands must remain zero")
    fail(hardware.get("realPeripheralCommandsAllowed") is False and hardware.get("realPeripheralCommands") == 0,
         "real peripheral commands must remain zero")
    return hardware


def validate_partner() -> dict:
    partner = load("partner-execution-admission.json")
    fail(partner.get("requirementId") == "T2-PAR-001" and partner.get("status") == "BLOCKED",
         "partner identity/status drift")
    fail(partner.get("targetRequired") == 5 and partner.get("verifiedRealTargetCount") == 0,
         "partner target count drift")
    fail(partner.get("verifiedWrittenIntentRequired") == 3 and partner.get("verifiedWrittenIntentCount") == 0,
         "partner intent count drift")
    for field in ("authorizedMaskedSampleCount", "retentionDeletionAgreementCount",
                  "legacyReconciliationReadyCount", "onsiteBoundaryConfirmedCount",
                  "partnerContacts", "onsitePilots"):
        fail(partner.get(field) == 0, f"partner external fact must remain zero: {field}")
    slots = partner.get("slots", [])
    fail([item.get("opaqueId") for item in slots] == [f"PAR-0{index}" for index in range(1, 6)],
         "partner opaque slots drift")
    fail(all(item.get("identityState") == "MISSING" and item.get("writtenIntentState") == "MISSING"
             for item in slots), "partner evidence was fabricated")
    fail(partner.get("verifiedDocumentStatus") == "NOT_ACHIEVED" and
         partner.get("executionAdmission") == "NO_GO", "partner admission drift")
    fail(partner.get("contactAllowed") is False and partner.get("onsitePilotAllowed") is False,
         "partner contact/pilot must remain forbidden")
    return partner


def validate_license() -> dict:
    license_plan = load("license-execution-admission.json")
    fail(license_plan.get("requirementId") == "T2-LIC-001" and license_plan.get("status") == "DEFERRED",
         "license identity/status drift")
    components = license_plan.get("components", [])
    fail({item.get("name") for item in components} == {"Aviator", "simple-http", "MySQL Connector/J"},
         "license component set drift")
    fail(all(item.get("closureState") == "OPEN" and item.get("owners") and item.get("requiredEvidence")
             and item.get("deadlineGate") == "BEFORE_T2_REL_001_READY" for item in components),
         "license closure evidence or deadline incomplete")
    fail(license_plan.get("closedComponentCount") == 0 and license_plan.get("requiredClosedComponentCount") == 3,
         "license closure count drift")
    fail(license_plan.get("executionAdmission") == "NO_GO_COMMERCIAL_RELEASE",
         "commercial license NO-GO drift")
    return license_plan


def validate_uat() -> dict:
    uat = load("full-alpha-uat-freeze.json")
    fail(uat.get("requirementId") == "T2-UAT-001" and uat.get("status") == "DRAFT",
         "UAT identity/status drift")
    fail(uat.get("decision") == "NO_GO_FULL_ALPHA" and uat.get("fullAlphaAllowed") is False,
         "full Alpha decision drift")
    candidate = uat.get("softwareCandidate", {})
    fail(candidate.get("classification") == "INTERNAL_RELEASE_CANDIDATE" and
         candidate.get("commit") == BASELINE and candidate.get("workflowRun") == 32471050486 and
         candidate.get("artifactId") == 9443017031 and
         candidate.get("artifactSha256") == "9cc79a2248d780c4f818ac811014b67c9f0cca066a1022a058b7685696c07418",
         "software candidate identity drift")
    fail(uat.get("entryBlockers") == ["T2-PAY-002", "T2-HWD-001", "T2-PRN-001", "T2-PAR-001"],
         "full Alpha external blockers drift")
    fail(uat.get("releaseBlockers") == ["T2-LIC-001", "T2-REL-001"], "release blockers drift")
    matrix = uat.get("merchantStoreTerminalMatrix", [])
    fail(len(matrix) == 3 and {item.get("industry") for item in matrix} ==
         {"CONVENIENCE", "SNACK_DISCOUNT", "COMMUNITY_SUPERMARKET"}, "industry matrix incomplete")
    fail(all(item.get("state") == "BLOCKED_EXTERNAL_INPUT" for item in matrix),
         "merchant matrix must not be green")
    fail(len(uat.get("raci", [])) >= 8 and all(item.get("assignmentState") == "ROLE_DEFINED_PERSON_UNASSIGNED"
                                               for item in uat.get("raci", [])), "UAT RACI freeze drift")
    domains = uat.get("testDomains", [])
    fail(len(domains) >= 16 and len({item.get("id") for item in domains}) == len(domains),
         "UAT domain matrix incomplete")
    fail(any(item.get("state", "").startswith("BLOCKED") for item in domains), "UAT blocked domains hidden")
    execution = uat.get("externalExecution", {})
    fail(execution and all(value == 0 for value in execution.values()), "UAT external execution must remain zero")
    return uat


def validate_docs_scope_and_secrets() -> list[str]:
    required_docs = [
        "docs/t2-gate6i-prep/README.md",
        "docs/t2-gate6i-prep/01_范围证据等级与零执行边界.md",
        "docs/t2-gate6i-prep/02_T2_PAY002支付沙箱执行准入评审报告.md",
        "docs/t2-gate6i-prep/03_T2_HWD001_PRN001真实硬件打印执行准入评审报告.md",
        "docs/t2-gate6i-prep/04_T2_PAR001设计伙伴执行准入评审报告.md",
        "docs/t2-gate6i-prep/05_T2_LIC001商业许可证关闭执行准入评审报告.md",
        "docs/t2-gate6i-prep/06_T2完整AlphaUAT冻结与启动评审报告.md",
        "docs/t2-gate6i-prep/07_T2_Gate6I_Prep启动评审报告.md",
        "docs/t2-gate6i-prep/08_Gate6I逐轨解阻与完整Alpha下一步操作指令.md",
        "docs/adr/ADR-041-gate6i-external-admission-alpha-freeze.md",
    ]
    fail(all((ROOT / item).is_file() for item in required_docs), "Gate 6I report set incomplete")
    fail(git("merge-base", "--is-ancestor", BASELINE, "HEAD") == "", "Gate 6H seal is not an ancestor")
    changed = git("diff", "--name-only", BASELINE).splitlines()
    untracked = git("ls-files", "--others", "--exclude-standard").splitlines()
    changed = sorted(set(filter(None, changed + untracked)))
    allowed_exact = {
        "AGENTS.md", ".github/workflows/t2-gate6i-prep.yml",
        "contracts/t2/gate6h/gate6h-admission.json", "docs/adr/README.md",
        "docs/governance/change-log.md", "docs/governance/rtm.csv",
        "scripts/check_t2_gate6h.py", "scripts/build_t2_gate6h_internal_rc.py",
        "scripts/check_t2_gate6i_prep.py", "scripts/build_t2_gate6i_prep_evidence.py",
    }
    allowed_prefixes = ("contracts/t2/gate6i-prep/", "docs/t2-gate6i-prep/", "docs/adr/ADR-041-")
    illegal = [item for item in changed if item not in allowed_exact and not item.startswith(allowed_prefixes)]
    fail(not illegal, f"runtime or out-of-scope files changed: {illegal}")
    runtime_roots = ("server/", "admin-web/", "pos-flutter/", "packages/", "infrastructure/", "infra/")
    fail(not [item for item in changed if item.startswith(runtime_roots)], "runtime/dependency surface changed")
    sensitive_name = re.compile(r"(^|/)(\.env($|\.)|.*\.(pem|p12|pfx|jks|keystore|key)$)", re.IGNORECASE)
    fail(not [item for item in changed if sensitive_name.search(item)], "sensitive file name found")
    secret_patterns = [
        re.compile(r"-----BEGIN [A-Z ]*PRIVATE KEY-----"),
        re.compile(r"AKIA[0-9A-Z]{16}"),
        re.compile(r"(?i)(password|secret|token)\s*[:=]\s*['\"][^'\"]{8,}['\"]"),
    ]
    for item in changed:
        path = ROOT / item
        if path.is_file():
            content = path.read_text(encoding="utf-8", errors="ignore")
            fail(not any(pattern.search(content) for pattern in secret_patterns), f"possible secret in {item}")
    return changed


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--output", type=pathlib.Path)
    args = parser.parse_args()
    with RTM.open(encoding="utf-8-sig", newline="") as handle:
        rows = {row["requirement_id"]: row for row in csv.DictReader(handle)}
    admission = validate_admission(rows)
    payment = validate_payment()
    hardware = validate_hardware_print()
    partner = validate_partner()
    license_plan = validate_license()
    uat = validate_uat()
    changed = validate_docs_scope_and_secrets()
    result = {
        "gate": "T2-GATE6I-PREP-EXTERNAL-ADMISSION-ALPHA-FREEZE",
        "status": "PASS",
        "evidenceLevel": admission["evidenceLevel"],
        "acceptedGate6HRequirements": admission["acceptedGate6HRequirements"],
        "preservedStates": admission["preservedStates"],
        "paymentControlledItems": f"{payment['receivedItemCount']}/{payment['requiredItemCount']}",
        "hardwarePrintBundles": f"{hardware['verifiedBundleCount']}/{hardware['requiredBundleCount']}",
        "partners": f"{partner['verifiedRealTargetCount']}/{partner['targetRequired']}",
        "writtenIntents": f"{partner['verifiedWrittenIntentCount']}/{partner['verifiedWrittenIntentRequired']}",
        "licenseClosures": f"{license_plan['closedComponentCount']}/{license_plan['requiredClosedComponentCount']}",
        "uatDomains": len(uat["testDomains"]),
        "externalExecution": admission["externalExecution"],
        "overallDecision": admission["overallDecision"],
        "changedFiles": len(changed),
    }
    if args.output:
        target = args.output if args.output.is_absolute() else ROOT / args.output
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(result, ensure_ascii=False))


if __name__ == "__main__":
    main()
