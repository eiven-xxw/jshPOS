#!/usr/bin/env python3
"""Gate 7F-Prep 外部 P0、许可证和完整 Alpha 离线准入门禁。"""
from __future__ import annotations

import argparse
import csv
import json
import pathlib
import re
import subprocess


ROOT = pathlib.Path(__file__).resolve().parents[1]
BASELINE = "3aaa92e9c90d1db540cfb6b70cdf65058c6a118f"
BRANCH = "t2/gate7f-prep-external-p0-alpha-admission"
CONTRACT_DIR = ROOT / "contracts/t2/gate7f-prep"
RTM = ROOT / "docs/governance/rtm.csv"
PRESERVED = {
    "T2-PAY-002": "BLOCKED", "T2-HWD-001": "BLOCKED", "T2-PRN-001": "BLOCKED",
    "T2-PAR-001": "BLOCKED", "T2-UAT-001": "DRAFT", "T2-REL-001": "DRAFT",
    "T2-SAA-001": "DRAFT", "T2-SUB-001": "DRAFT", "T2-SVC-001": "DRAFT",
    "T2-LIC-001": "DEFERRED", "T2-JSH-001": "DEFERRED",
}
ZERO_FIELDS = (
    "providerNetworkCalls", "realFundsTransactions", "realDeviceCommands",
    "realPeripheralCommands", "partnerContacts", "onsitePilots", "fullAlphaRuns",
    "productionDeployments", "commercialClaims",
)


def require(condition: bool, message: str) -> None:
    if not condition:
        raise SystemExit(f"T2-GATE7F-PREP ERROR: {message}")


def load(name: str) -> dict:
    path = CONTRACT_DIR / name
    try:
        return json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as exception:
        raise SystemExit(f"T2-GATE7F-PREP ERROR: invalid {path.relative_to(ROOT)}: {exception}")


def git(*args: str) -> str:
    completed = subprocess.run(
        ["git", "-c", "core.quotepath=false", *args], cwd=ROOT, check=False,
        capture_output=True, text=True, encoding="utf-8",
    )
    require(completed.returncode == 0, f"git {' '.join(args)} failed: {completed.stderr.strip()}")
    return completed.stdout.strip()


def load_rtm() -> dict[str, dict[str, str]]:
    with RTM.open(encoding="utf-8-sig", newline="") as handle:
        return {row["requirement_id"]: row for row in csv.DictReader(handle)}


def validate_admission(rows: dict[str, dict[str, str]]) -> dict:
    admission = load("gate7f-prep-admission.json")
    require(admission["baselineCommit"] == BASELINE, "Gate 7E 封存基线漂移")
    require(admission["branch"] == BRANCH, "准备分支契约漂移")
    require(admission["decision"] == "CONDITIONAL_GO_PREP_AND_OFFLINE_VERIFICATION_ONLY",
            "准备阶段授权漂移")
    require(admission["evidenceLevel"] == "STATIC_GOVERNANCE_AND_OFFLINE_METADATA",
            "证据等级越界")
    require(admission["acceptedRequirement"] == {"T2-E2E-004": "ACCEPTED"},
            "Gate 7E 接受状态漂移")
    require(rows["T2-E2E-004"]["status"] == "ACCEPTED", "RTM 未接受 T2-E2E-004")
    require(admission["preservedStates"] == PRESERVED, "保留状态契约漂移")
    for requirement, state in PRESERVED.items():
        require(rows[requirement]["status"] == state, f"RTM 状态漂移: {requirement}")
    required_forbidden = {
        "PAYMENT_PROVIDER_NETWORK", "REAL_FUNDS", "REAL_DEVICE_INSTALL_RESTART_OR_COMMAND",
        "REAL_PERIPHERAL_COMMAND", "PARTNER_OR_STORE_CONTACT", "ONSITE_PILOT",
        "FULL_ALPHA_UAT_EXECUTION", "PRODUCTION_DEPLOYMENT", "COMMERCIAL_AVAILABILITY_CLAIM",
        "SECRET_REAL_PII_OR_CONTROLLED_ORIGINAL_IN_REPOSITORY",
    }
    require(required_forbidden <= set(admission["forbiddenActions"]), "禁止动作不完整")
    execution = admission["externalExecution"]
    require(all(execution.get(field) == 0 for field in ZERO_FIELDS), "外部执行必须全部为0")
    require(admission["overallDecision"] ==
            "PREPARED_NO_GO_EXTERNAL_EXECUTION_FULL_ALPHA_AND_RELEASE", "总体 NO-GO 漂移")
    return admission


def validate_payment() -> dict:
    payment = load("payment-execution-admission.json")
    require(payment["requirementId"] == "T2-PAY-002" and payment["status"] == "BLOCKED",
            "支付身份或状态漂移")
    expected = {"PAY-AUTH", "PAY-MERCHANT", "PAY-TERMINAL", "PAY-API", "PAY-SIGN",
                "PAY-CALLBACK", "PAY-BILL", "PAY-LIMIT", "PAY-NETWORK", "PAY-SECRET", "PAY-CONTACT"}
    require({item["id"] for item in payment["mandatoryItems"]} == expected, "支付11项材料不完整")
    metadata = payment["metadataTemplate"]
    required_metadata = {"state", "opaqueReference", "source", "authorizationSigner",
                         "applicableProductOrModel", "version", "scope", "sha256", "custodian",
                         "expiresAt", "rotationRule", "deletionRule", "revocationRule"}
    require(required_metadata <= set(metadata), "支付受控元数据字段不完整")
    require(metadata["state"] == "MISSING" and all(metadata[field] is None
            for field in required_metadata - {"state"}), "未收到的支付材料被错误提升")
    require((payment["receivedItemCount"], payment["verifiedItemCount"], payment["requiredItemCount"])
            == (0, 0, 11), "支付材料计数漂移")
    require(payment["verifiedDocumentStatus"] == "NOT_ACHIEVED" and
            payment["executionAdmission"] == "NO_GO", "支付准入必须 NO-GO")
    require(payment["providerNetworkAllowed"] is False and payment["providerNetworkCalls"] == 0
            and payment["realFundsAllowed"] is False and payment["realFundsTransactions"] == 0,
            "支付网络或真实资金边界漂移")
    return payment


def validate_hardware() -> dict:
    hardware = load("hardware-execution-admission.json")
    require(hardware["requirementId"] == "T2-HWD-001" and hardware["status"] == "BLOCKED",
            "硬件身份或状态漂移")
    candidates = hardware["candidates"]
    require(len(candidates) == 2 and {item["role"] for item in candidates} == {"PRIMARY", "COMPATIBLE"},
            "主认证/兼容候选不完整")
    require(all(item["state"] == "MISSING_CONTROLLED_BUNDLE" and item["exactSku"] is None
                and item["sampleAssetId"] is None for item in candidates), "硬件缺件被错误提升")
    require((hardware["verifiedCandidateCount"], hardware["requiredCandidateCount"]) == (0, 2),
            "硬件候选计数漂移")
    require(hardware["verifiedDocumentStatus"] == "NOT_ACHIEVED" and
            hardware["executionAdmission"] == "NO_GO" and
            hardware["realDeviceCommandsAllowed"] is False and hardware["realDeviceCommands"] == 0,
            "硬件执行边界漂移")
    return hardware


def validate_peripheral() -> dict:
    peripheral = load("peripheral-execution-admission.json")
    require(peripheral["requirementId"] == "T2-PRN-001" and peripheral["status"] == "BLOCKED",
            "外设身份或状态漂移")
    expected = {"PRN-A", "PRN-B", "SCANNER", "SCALE", "CASH-DRAWER", "CUSTOMER-DISPLAY"}
    require({item["id"] for item in peripheral["bundles"]} == expected and
            all(item["state"] == "MISSING" for item in peripheral["bundles"]), "六类外设包不完整")
    require((peripheral["verifiedBundleCount"], peripheral["requiredBundleCount"]) == (0, 6),
            "外设包计数漂移")
    require(peripheral["verifiedDocumentStatus"] == "NOT_ACHIEVED" and
            peripheral["executionAdmission"] == "NO_GO" and
            peripheral["realPeripheralCommandsAllowed"] is False and
            peripheral["realPeripheralCommands"] == 0, "真实外设执行边界漂移")
    return peripheral


def validate_partner() -> dict:
    partner = load("partner-execution-admission.json")
    require(partner["requirementId"] == "T2-PAR-001" and partner["status"] == "BLOCKED",
            "伙伴身份或状态漂移")
    require((partner["verifiedRealTargetCount"], partner["targetRequired"]) == (0, 5)
            and (partner["verifiedWrittenIntentCount"], partner["verifiedWrittenIntentRequired"]) == (0, 3),
            "伙伴目标或意愿计数漂移")
    require(len(partner["slots"]) == 5 and all(all(value == "MISSING" for key, value in item.items()
            if key not in {"opaqueId", "industry"}) for item in partner["slots"]), "伙伴缺件被错误提升")
    require(partner["verifiedDocumentStatus"] == "NOT_ACHIEVED" and
            partner["executionAdmission"] == "NO_GO" and partner["contactAllowed"] is False
            and partner["partnerContacts"] == 0 and partner["onsitePilotAllowed"] is False
            and partner["onsitePilots"] == 0, "伙伴执行边界漂移")
    return partner


def validate_license_and_uat() -> tuple[dict, dict]:
    license_plan = load("license-closure-plan.json")
    require(license_plan["requirementId"] == "T2-LIC-001" and license_plan["status"] == "DEFERRED",
            "许可证状态漂移")
    require({item["name"] for item in license_plan["components"]} ==
            {"Aviator", "simple-http", "MySQL Connector/J"}, "许可证组件不完整")
    require(all(item["closureState"] == "OPEN" and item["ownerRoles"] and item["requiredEvidence"]
                for item in license_plan["components"]), "许可证关闭条件不完整")
    require((license_plan["closedComponentCount"], license_plan["requiredClosedComponentCount"]) == (0, 3)
            and license_plan["commercialReleaseDecision"] == "NO_GO", "许可证 NO-GO 漂移")
    uat = load("full-alpha-uat-admission.json")
    require(uat["requirementId"] == "T2-UAT-001" and uat["status"] == "DRAFT"
            and uat["decision"] == "NO_GO_FULL_ALPHA" and uat["fullAlphaAllowed"] is False,
            "完整 Alpha 状态漂移")
    require(uat["softwareCandidate"]["commit"] == BASELINE and
            uat["softwareCandidate"]["classification"] == "INTERNAL_V1_BUSINESS_COMPLETE_CANDIDATE",
            "内部候选身份漂移")
    require(uat["entryBlockers"] == ["T2-PAY-002", "T2-HWD-001", "T2-PRN-001", "T2-PAR-001"],
            "完整 Alpha 入口阻断不完整")
    require(len(uat["raciRoles"]) >= 9 and len(uat["testDomains"]) >= 16, "RACI 或测试域不完整")
    require(all(value == 0 for value in uat["externalExecution"].values()), "UAT 外部执行必须为0")
    return license_plan, uat


def validate_scope_and_secrets() -> list[str]:
    required_docs = [
        "docs/t2-gate7e/06_T2_E2E004项目发起人接受记录.md",
        "docs/t2-gate7f-prep/README.md",
        "docs/t2-gate7f-prep/01_范围证据等级与受控收件规范.md",
        "docs/t2-gate7f-prep/02_T2_PAY002支付沙箱执行准入报告.md",
        "docs/t2-gate7f-prep/03_T2_HWD001真实硬件执行准入报告.md",
        "docs/t2-gate7f-prep/04_T2_PRN001真实外设执行准入报告.md",
        "docs/t2-gate7f-prep/05_T2_PAR001设计伙伴执行准入报告.md",
        "docs/t2-gate7f-prep/06_T2_LIC001商业许可证关闭计划.md",
        "docs/t2-gate7f-prep/07_T2完整AlphaUAT启动评审报告.md",
        "docs/t2-gate7f-prep/08_T2_Gate7F_Prep启动评审报告.md",
        "docs/t2-gate7f-prep/09_逐轨收件与执行准入下一步操作指令.md",
        "docs/adr/ADR-055-gate7f-external-p0-license-alpha-admission-prep.md",
        "docs/governance/CR-T2G7F-001_external-p0-license-alpha-admission-prep.md",
    ]
    require(all((ROOT / path).is_file() for path in required_docs), "Gate 7F-Prep 文档集不完整")
    require(git("merge-base", "--is-ancestor", BASELINE, "HEAD") == "", "Gate 7E 封存提交不是祖先")
    changed = sorted(set(filter(None, git("diff", "--name-only", BASELINE).splitlines()
                                    + git("ls-files", "--others", "--exclude-standard").splitlines())))
    allowed_exact = {
        "AGENTS.md", ".github/workflows/t2-gate7f-prep.yml", "docs/adr/README.md",
        "docs/governance/change-log.md", "docs/governance/rtm.csv",
        "docs/governance/CR-T2G7F-001_external-p0-license-alpha-admission-prep.md",
        "contracts/t2/gate7e/e2e004-admission.json", "docs/t2-gate7e/README.md",
        "scripts/check_t2_gate7e.py", "scripts/check_t2_gate7f_prep.py",
        "scripts/build_t2_gate7f_prep_evidence.py",
    }
    prefixes = ("contracts/t2/gate7f-prep/", "docs/t2-gate7f-prep/", "docs/t2-gate7e/06_",
                "docs/adr/ADR-055-")
    illegal = [path for path in changed if path not in allowed_exact and not path.startswith(prefixes)]
    require(not illegal, f"出现运行时或越界文件: {illegal}")
    runtime_roots = ("server/", "admin-web/", "pos-flutter/", "packages/", "infrastructure/", "infra/")
    require(not [path for path in changed if path.startswith(runtime_roots)], "运行时目录发生变化")
    dependency_names = {"pom.xml", "package.json", "pnpm-lock.yaml", "pubspec.yaml", "pubspec.lock",
                        "build.gradle", "build.gradle.kts", "settings.gradle", "settings.gradle.kts"}
    require(not [path for path in changed if pathlib.PurePosixPath(path).name in dependency_names],
            "依赖清单发生变化")
    sensitive_name = re.compile(r"(^|/)(\.env($|\.)|.*\.(pem|p12|pfx|jks|keystore|key)$)", re.I)
    require(not [path for path in changed if sensitive_name.search(path)], "发现敏感文件名")
    secret_patterns = [
        re.compile(r"-----BEGIN [A-Z ]*PRIVATE KEY-----"),
        re.compile(r"AKIA[0-9A-Z]{16}"),
        re.compile(r"(?i)(password|secret|token)\s*[:=]\s*['\"][^'\"]{8,}['\"]"),
    ]
    for item in changed:
        path = ROOT / item
        if path.is_file():
            content = path.read_text(encoding="utf-8", errors="ignore")
            require(not any(pattern.search(content) for pattern in secret_patterns), f"疑似 Secret: {item}")
    return changed


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--output", type=pathlib.Path)
    args = parser.parse_args()
    rows = load_rtm()
    admission = validate_admission(rows)
    payment = validate_payment()
    hardware = validate_hardware()
    peripheral = validate_peripheral()
    partner = validate_partner()
    license_plan, uat = validate_license_and_uat()
    changed = validate_scope_and_secrets()
    result = {
        "gate": admission["phase"], "status": "PASS", "evidenceLevel": admission["evidenceLevel"],
        "acceptedRequirement": admission["acceptedRequirement"], "preservedStates": admission["preservedStates"],
        "paymentMaterials": f"{payment['verifiedItemCount']}/{payment['requiredItemCount']}",
        "hardwareCandidates": f"{hardware['verifiedCandidateCount']}/{hardware['requiredCandidateCount']}",
        "peripheralBundles": f"{peripheral['verifiedBundleCount']}/{peripheral['requiredBundleCount']}",
        "partners": f"{partner['verifiedRealTargetCount']}/{partner['targetRequired']}",
        "writtenIntents": f"{partner['verifiedWrittenIntentCount']}/{partner['verifiedWrittenIntentRequired']}",
        "licenseClosures": f"{license_plan['closedComponentCount']}/{license_plan['requiredClosedComponentCount']}",
        "uatTestDomains": len(uat["testDomains"]), "externalExecution": admission["externalExecution"],
        "overallDecision": admission["overallDecision"], "changedFiles": len(changed),
    }
    if args.output:
        target = args.output if args.output.is_absolute() else ROOT / args.output
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(result, ensure_ascii=False))


if __name__ == "__main__":
    main()
