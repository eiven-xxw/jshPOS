#!/usr/bin/env python3
"""Gate 6F 外部 P0、完整 Alpha UAT 与发布准备的离线治理门禁。"""
from __future__ import annotations

import argparse
import csv
import json
import pathlib
import re
import subprocess


ROOT = pathlib.Path(__file__).resolve().parents[1]
BASELINE = "0dd20e90c32914da48b3154f0bf1781ab8f2ba71"
CONTRACT_DIR = ROOT / "contracts/t2/gate6f-prep"
RTM = ROOT / "docs/governance/rtm.csv"


def fail(condition: bool, message: str) -> None:
    if not condition:
        raise SystemExit(f"T2-GATE6F-PREP ERROR: {message}")


def load(name: str) -> dict:
    path = CONTRACT_DIR / name
    try:
        return json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as exception:
        raise SystemExit(f"T2-GATE6F-PREP ERROR: invalid {path.relative_to(ROOT)}: {exception}")


def git(*args: str) -> str:
    completed = subprocess.run(
        ["git", "-c", "core.quotepath=false", *args], cwd=ROOT,
        check=False, capture_output=True, text=True, encoding="utf-8",
    )
    fail(completed.returncode == 0, f"git {' '.join(args)} failed: {completed.stderr.strip()}")
    return completed.stdout.strip()


def validate_admission(rows: dict[str, dict[str, str]]) -> dict:
    admission = load("gate6f-prep-admission.json")
    fail(admission.get("baselineCommit") == BASELINE, "baseline commit drift")
    fail(admission.get("branch") == "t2/gate6f-external-p0-uat-prep-20260821", "branch contract drift")
    fail(admission.get("decision") == "CONDITIONAL_GO_PREP_ONLY", "prep decision drift")
    accepted = {"T2-ADM-002": "ACCEPTED", "T2-POS-009": "ACCEPTED", "T2-E2E-002": "ACCEPTED"}
    preserved = {
        "T2-PAY-002": "BLOCKED", "T2-HWD-001": "BLOCKED", "T2-PAR-001": "BLOCKED",
        "T2-UAT-001": "DRAFT", "T2-REL-001": "DRAFT",
        "T2-JSH-001": "DEFERRED", "T2-LIC-001": "DEFERRED",
    }
    fail(admission.get("acceptedInternalRequirements") == accepted, "Gate 6E acceptance map drift")
    fail(admission.get("preservedStates") == preserved, "external/UAT/release state map drift")
    for requirement_id, status in {**accepted, **preserved}.items():
        fail(rows.get(requirement_id, {}).get("status") == status, f"RTM mismatch: {requirement_id}")
    counters = admission.get("externalExecution", {})
    for field in ("providerNetworkCalls", "realDeviceCommands", "partnerContacts", "onsitePilots",
                  "fullAlphaRuns", "productionDeployments"):
        fail(counters.get(field) == 0, f"external execution must remain zero: {field}")
    fail(counters.get("commercialClaimAllowed") is False, "commercial claim must be forbidden")
    forbidden = set(admission.get("forbiddenActions", []))
    required_forbidden = {
        "PAYMENT_PROVIDER_NETWORK", "REAL_DEVICE_INSTALL_OR_COMMAND",
        "PARTNER_OR_STORE_CONTACT_OR_ONSITE_EXECUTION", "FULL_ALPHA_UAT_EXECUTION",
        "PRODUCTION_DEPLOYMENT", "COMMERCIAL_AVAILABILITY_CLAIM", "SECRET_OR_REAL_PII_IN_REPOSITORY",
    }
    fail(required_forbidden <= forbidden, "forbidden action boundary incomplete")
    return admission


def validate_payment(public_ids: set[str]) -> dict:
    payment = load("payment-execution-readiness.json")
    fail(payment.get("requirementId") == "T2-PAY-002" and payment.get("status") == "BLOCKED",
         "payment identity/status drift")
    selected = payment.get("selectedProvider", {})
    fallback = payment.get("fallbackProvider", {})
    fail(selected.get("code") == "LAKALA" and selected.get("selectionScope") == "DOCUMENT_VERIFICATION_CANDIDATE_ONLY",
         "first Provider candidate drift")
    fail(selected.get("contractOrCommercialCommitment") is False, "candidate must not imply commitment")
    fail(fallback.get("code") == "HUIFU", "payment fallback candidate drift")
    referenced = set(selected.get("publicEvidence", [])) | set(fallback.get("publicEvidence", []))
    fail(referenced <= public_ids, "payment references unknown public evidence")
    items = payment.get("mandatoryItems", [])
    fail(len(items) >= 10 and len({item.get("id") for item in items}) == len(items), "payment RFI incomplete")
    fail(all(item.get("status") == "MISSING" and item.get("controlledReference") is None for item in items),
         "missing controlled payment evidence must not be green")
    fail(payment.get("controlledBundleStatus") == "INCOMPLETE" and
         payment.get("verifiedDocumentStatus") == "NOT_ACHIEVED", "payment document grade drift")
    fail(payment.get("providerNetworkAllowed") is False and payment.get("providerNetworkCalls") == 0,
         "Provider network must remain zero")
    fail(payment.get("realFundsAllowed") is False and payment.get("executionAdmission") == "NO_GO",
         "payment execution must be NO-GO")
    return payment


def validate_hardware(public_ids: set[str]) -> dict:
    hardware = load("hardware-execution-readiness.json")
    fail(hardware.get("requirementId") == "T2-HWD-001" and hardware.get("status") == "BLOCKED",
         "hardware identity/status drift")
    hosts = hardware.get("hosts", [])
    fail([host.get("role") for host in hosts] == ["PRIMARY_DOCUMENT_CANDIDATE", "COMPATIBLE_DOCUMENT_CANDIDATE"],
         "primary/compatible host order drift")
    fail(hosts[0].get("vendor") == "商米" and "T3 PRO MAX" in str(hosts[0].get("modelFamily")),
         "primary host candidate drift")
    fail(hosts[1].get("vendor") == "iMin" and hosts[1].get("modelFamily") == "D4 Pro",
         "compatible host candidate drift")
    fail(all(set(host.get("publicEvidence", [])) <= public_ids for host in hosts), "unknown hardware public evidence")
    peripherals = hardware.get("peripheralCandidates", [])
    expected = {"PRINTER_A", "PRINTER_B", "SCANNER", "SCALE", "CASH_DRAWER", "CUSTOMER_DISPLAY"}
    fail({item.get("type") for item in peripherals} == expected, "peripheral candidate coverage incomplete")
    cash_drawer = next(item for item in peripherals if item.get("type") == "CASH_DRAWER")
    fail(cash_drawer.get("model") is None and cash_drawer.get("status") == "MISSING_EXACT_MODEL",
         "cash drawer gap must remain explicit")
    fail(len(hardware.get("mandatoryMissing", [])) >= 10, "hardware controlled gap list incomplete")
    fail(hardware.get("controlledBundleStatus") == "INCOMPLETE" and
         hardware.get("verifiedDocumentStatus") == "NOT_ACHIEVED", "hardware document grade drift")
    fail(hardware.get("realDeviceCommandsAllowed") is False and hardware.get("realDeviceCommands") == 0,
         "real device execution must remain zero")
    fail(hardware.get("executionAdmission") == "NO_GO", "hardware execution must be NO-GO")
    return hardware


def validate_partner() -> dict:
    partner = load("partner-execution-readiness.json")
    fail(partner.get("requirementId") == "T2-PAR-001" and partner.get("status") == "BLOCKED",
         "partner identity/status drift")
    fail(partner.get("targetRequired") == 5 and partner.get("verifiedRealTargetCount") == 0,
         "real partner target count must remain 0/5")
    fail(partner.get("verifiedWrittenIntentRequired") == 3 and partner.get("verifiedWrittenIntentCount") == 0,
         "written intent count must remain 0/3")
    for field in ("authorizedMaskedSampleCount", "retentionDeletionAgreementCount",
                  "legacyReconciliationReadyCount", "onsiteBoundaryConfirmedCount",
                  "partnerContacts", "onsitePilots"):
        fail(partner.get(field) == 0, f"partner external fact must remain zero: {field}")
    slots = partner.get("slots", [])
    fail([slot.get("opaqueId") for slot in slots] == [f"PAR-0{i}" for i in range(1, 6)], "partner slots drift")
    fail(all(slot.get("identity") == "MISSING" and slot.get("writtenIntent") == "MISSING" for slot in slots),
         "partner evidence must not be fabricated")
    fail(partner.get("contactAllowed") is False and partner.get("onsitePilotAllowed") is False and
         partner.get("executionAdmission") == "NO_GO", "partner execution must be NO-GO")
    return partner


def validate_uat_release() -> tuple[dict, dict, dict]:
    uat = load("alpha-uat-prep-matrix.json")
    fail(uat.get("requirementId") == "T2-UAT-001" and uat.get("status") == "DRAFT",
         "UAT identity/status drift")
    fail(uat.get("decision") == "NO_GO_FULL_ALPHA" and uat.get("fullAlphaAllowed") is False,
         "full Alpha must remain NO-GO")
    fail(uat.get("entryBlockers") == ["T2-PAY-002", "T2-HWD-001", "T2-PAR-001"], "UAT blockers drift")
    domains = uat.get("testDomains", [])
    fail(len(domains) >= 14 and len({item.get("id") for item in domains}) == len(domains), "UAT matrix incomplete")
    fail(any(item.get("caseStatus", "").startswith("BLOCKED") for item in domains), "UAT must expose blocked domains")
    fail(all(value == 0 for value in uat.get("externalExecution", {}).values()), "UAT execution must remain zero")

    release = load("release-readiness-gap.json")
    fail(release.get("requirementId") == "T2-REL-001" and release.get("status") == "DRAFT",
         "release identity/status drift")
    fail(release.get("decision") == "NO_GO_RELEASE" and release.get("productionDeploymentAllowed") is False,
         "release must remain NO-GO")
    fail(len(release.get("gaps", [])) >= 7 and any(item.get("id") == "REL-LIC" and item.get("status") == "BLOCKED"
                                                    for item in release.get("gaps", [])), "release gaps incomplete")
    fail(len(release.get("preparedTemplates", [])) >= 10, "release template list incomplete")
    fail(release.get("productionDeployments") == 0 and release.get("commercialClaimAllowed") is False,
         "production/commercial boundary drift")

    license_plan = load("license-closure-plan.json")
    fail(license_plan.get("requirementId") == "T2-LIC-001" and license_plan.get("status") == "DEFERRED",
         "license identity/status drift")
    names = {item.get("name") for item in license_plan.get("components", [])}
    fail(names == {"Aviator", "simple-http", "MySQL Connector/J"}, "license component list drift")
    fail(all(item.get("decision", "").startswith("PENDING_") and item.get("requiredEvidence")
             for item in license_plan.get("components", [])), "license closure cannot be green")
    fail(license_plan.get("overallDecision") == "NO_GO_COMMERCIAL_RELEASE", "license release boundary drift")
    return uat, release, license_plan


def validate_docs_and_scope() -> list[str]:
    required_docs = [
        "docs/t2-gate6f-prep/README.md",
        "docs/t2-gate6f-prep/01_范围证据状态与安全收件规范.md",
        "docs/t2-gate6f-prep/02_T2_PAY002支付沙箱执行准入评审报告.md",
        "docs/t2-gate6f-prep/03_T2_HWD001真实硬件执行准入评审报告.md",
        "docs/t2-gate6f-prep/04_T2_PAR001设计伙伴执行准入评审报告.md",
        "docs/t2-gate6f-prep/05_T2完整AlphaUAT启动评审报告.md",
        "docs/t2-gate6f-prep/06_T2发布准备差距报告.md",
        "docs/t2-gate6f-prep/07_T2_Gate6F_Prep启动评审报告.md",
        "docs/t2-gate6f-prep/08_Gate6F外部资料补件与逐轨解阻下一步操作指令.md",
        "docs/adr/ADR-038-gate6f-external-admission-uat-release-prep.md",
    ]
    fail(all((ROOT / path).is_file() for path in required_docs), "Gate 6F report set incomplete")
    fail(git("merge-base", "--is-ancestor", BASELINE, "HEAD") == "", "Gate 6E seal is not an ancestor")
    changed = git("diff", "--name-only", BASELINE).splitlines()
    untracked = git("ls-files", "--others", "--exclude-standard").splitlines()
    changed = sorted(set(filter(None, changed + untracked)))
    allowed_exact = {
        "AGENTS.md", ".github/workflows/t2-gate6f-prep.yml",
        "contracts/t2/gate6e/gate6e-admission.json",
        "docs/adr/README.md", "docs/governance/change-log.md", "docs/governance/rtm.csv",
        "docs/t2-gate6e/README.md", "scripts/check_t2_gate6e.py",
        "scripts/check_t2_gate6f_prep.py", "scripts/build_t2_gate6f_prep_evidence.py",
    }
    allowed_prefixes = ("contracts/t2/gate6f-prep/", "docs/t2-gate6f-prep/", "docs/adr/ADR-038-")
    illegal = [path for path in changed if path not in allowed_exact and not path.startswith(allowed_prefixes)]
    fail(not illegal, f"runtime or out-of-scope files changed: {illegal}")
    runtime_roots = ("server/", "admin-web/", "pos-flutter/", "packages/", "infrastructure/", "infra/")
    fail(not [path for path in changed if path.startswith(runtime_roots)], "runtime/dependency surface changed")
    sensitive_name = re.compile(r"(^|/)(\.env($|\.)|.*\.(pem|p12|pfx|jks|keystore|key)$)", re.IGNORECASE)
    fail(not [path for path in changed if sensitive_name.search(path)], "sensitive file name found")
    secret_patterns = [
        re.compile(r"-----BEGIN [A-Z ]*PRIVATE KEY-----"),
        re.compile(r"AKIA[0-9A-Z]{16}"),
        re.compile(r"(?i)(password|secret|token)\s*[:=]\s*['\"][^'\"]{8,}['\"]"),
    ]
    for name in changed:
        path = ROOT / name
        if path.is_file():
            content = path.read_text(encoding="utf-8", errors="ignore")
            fail(not any(pattern.search(content) for pattern in secret_patterns), f"possible secret in {name}")
    return changed


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--output", type=pathlib.Path)
    args = parser.parse_args()
    with RTM.open(encoding="utf-8-sig", newline="") as stream:
        rows = {row["requirement_id"]: row for row in csv.DictReader(stream)}
    manifest = json.loads((ROOT / "contracts/t2/gate6c-intake/public-evidence-manifest.json").read_text(encoding="utf-8"))
    public_ids = {item["evidenceId"] for item in manifest.get("sources", [])}
    admission = validate_admission(rows)
    payment = validate_payment(public_ids)
    hardware = validate_hardware(public_ids)
    partner = validate_partner()
    uat, release, license_plan = validate_uat_release()
    changed = validate_docs_and_scope()
    result = {
        "gate": "T2-GATE6F-EXTERNAL-P0-UAT-PREP",
        "status": "PASS",
        "evidenceLevel": "STATIC_GOVERNANCE",
        "acceptedInternalRequirements": admission["acceptedInternalRequirements"],
        "preservedStates": admission["preservedStates"],
        "selectedPaymentCandidate": payment["selectedProvider"]["code"],
        "hostCandidates": len(hardware["hosts"]),
        "peripheralCandidates": len(hardware["peripheralCandidates"]),
        "verifiedRealPartners": partner["verifiedRealTargetCount"],
        "verifiedWrittenIntents": partner["verifiedWrittenIntentCount"],
        "uatDomains": len(uat["testDomains"]),
        "releaseGaps": len(release["gaps"]),
        "licenseComponents": len(license_plan["components"]),
        "externalExecution": admission["externalExecution"],
        "overallDecision": admission["overallDecision"],
        "changedFiles": len(changed),
    }
    if args.output:
        target = ROOT / args.output if not args.output.is_absolute() else args.output
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(result, ensure_ascii=False))


if __name__ == "__main__":
    main()
