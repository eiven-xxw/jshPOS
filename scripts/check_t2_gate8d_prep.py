#!/usr/bin/env python3
"""Gate 8D-Prep 外部 P0、许可证、完整 Alpha 与发布离线复核门禁。"""
from __future__ import annotations

import argparse
import csv
import json
import pathlib
import re
import subprocess


ROOT = pathlib.Path(__file__).resolve().parents[1]
BASELINE = "bd1dee42bacfb75874d601e16828c8f82720986b"
BRANCH = "t2/gate8d-prep-external-p0-license-alpha-admission"
GATE = "T2-GATE8D-PREP-EXTERNAL-P0-LICENSE-ALPHA-RELEASE-ADMISSION"
CONTRACT_DIR = ROOT / "contracts/t2/gate8d-prep"
RTM = ROOT / "docs/governance/rtm.csv"
PRESERVED = {
    "T2-PAY-002": "BLOCKED",
    "T2-HWD-001": "BLOCKED",
    "T2-PRN-001": "BLOCKED",
    "T2-PAR-001": "BLOCKED",
    "T2-LIC-001": "DEFERRED",
    "T2-JSH-001": "DEFERRED",
    "T2-UAT-001": "DRAFT",
    "T2-REL-001": "DRAFT",
}
ZERO_FIELDS = (
    "providerNetworkCalls", "realFundsTransactions", "realDeviceCommands",
    "realPeripheralCommands", "partnerContacts", "onsitePilots", "fullAlphaRuns",
    "productionDeployments", "commercialTags", "commercialClaims",
)


def require(condition: bool, message: str) -> None:
    if not condition:
        raise SystemExit(f"T2-GATE8D-PREP ERROR: {message}")


def load(name: str) -> dict:
    path = CONTRACT_DIR / name
    try:
        return json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as exception:
        raise SystemExit(f"T2-GATE8D-PREP ERROR: invalid {path.relative_to(ROOT)}: {exception}")


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
    admission = load("gate8d-prep-admission.json")
    require(admission["phase"] == GATE, "Gate 身份漂移")
    require(admission["baselineCommit"] == BASELINE, "RDY001 封存基线漂移")
    require(admission["branch"] == BRANCH, "准备分支漂移")
    require(admission["decision"] == "CONDITIONAL_GO_OFFLINE_REVIEW_ONLY", "授权范围漂移")
    require(admission["evidenceLevel"] == "STATIC_GOVERNANCE_AND_OFFLINE_METADATA_REVIEW",
            "证据等级越界")
    require(admission["acceptedRequirement"] == {"T2-RDY-001": "ACCEPTED"},
            "RDY001 接受合同漂移")
    require(rows["T2-RDY-001"]["status"] == "ACCEPTED", "RTM 未接受 T2-RDY-001")
    require(admission["evidenceCeiling"] == "INTERNAL_RELEASE_READINESS_CANDIDATE",
            "RDY001 证据上限漂移")
    require(admission["preservedStates"] == PRESERVED, "保留状态合同漂移")
    for requirement, state in PRESERVED.items():
        require(rows[requirement]["status"] == state, f"RTM 状态漂移: {requirement}")
    execution = admission["externalExecution"]
    require(all(execution.get(field) == 0 for field in ZERO_FIELDS), "外部执行必须全部为0")
    require(admission["overallDecision"] ==
            "PREPARED_NO_GO_EXTERNAL_EXECUTION_FULL_ALPHA_AND_RELEASE", "总体 NO-GO 漂移")
    return admission


def validate_external_tracks() -> tuple[dict, dict, dict, dict]:
    payment = load("payment-execution-admission.json")
    expected_payment = {
        "PAY-AUTH", "PAY-MERCHANT", "PAY-TERMINAL", "PAY-API", "PAY-SIGN",
        "PAY-CALLBACK", "PAY-BILL", "PAY-LIMIT", "PAY-NETWORK", "PAY-SECRET", "PAY-CONTACT",
    }
    require(payment["requirementId"] == "T2-PAY-002" and payment["status"] == "BLOCKED",
            "支付身份或状态漂移")
    require({item["id"] for item in payment["mandatoryItems"]} == expected_payment and
            all(item["state"] == "MISSING" for item in payment["mandatoryItems"]),
            "支付11项缺件未如实保留")
    require((payment["receivedItemCount"], payment["verifiedItemCount"], payment["requiredItemCount"])
            == (0, 0, 11), "支付材料计数漂移")
    require(payment["verifiedDocumentStatus"] == "NOT_ACHIEVED" and
            payment["executionAdmission"] == "NO_GO" and
            payment["providerNetworkAllowed"] is False and payment["providerNetworkCalls"] == 0 and
            payment["realFundsAllowed"] is False and payment["realFundsTransactions"] == 0,
            "支付执行边界漂移")

    hardware = load("hardware-execution-admission.json")
    require(hardware["requirementId"] == "T2-HWD-001" and hardware["status"] == "BLOCKED",
            "硬件身份或状态漂移")
    require(len(hardware["candidates"]) == 2 and
            {item["role"] for item in hardware["candidates"]} == {"PRIMARY", "COMPATIBLE"} and
            all(item["state"] == "MISSING_CONTROLLED_BUNDLE" and item["exactSku"] is None
                and item["sampleAssetId"] is None for item in hardware["candidates"]),
            "硬件候选缺件被错误提升")
    require((hardware["verifiedCandidateCount"], hardware["requiredCandidateCount"]) == (0, 2) and
            hardware["verifiedDocumentStatus"] == "NOT_ACHIEVED" and
            hardware["executionAdmission"] == "NO_GO" and
            hardware["realDeviceCommandsAllowed"] is False and hardware["realDeviceCommands"] == 0,
            "硬件执行边界漂移")

    peripheral = load("peripheral-execution-admission.json")
    expected_peripheral = {"PRN-A", "PRN-B", "SCANNER", "SCALE", "CASH-DRAWER", "CUSTOMER-DISPLAY"}
    require(peripheral["requirementId"] == "T2-PRN-001" and peripheral["status"] == "BLOCKED",
            "外设身份或状态漂移")
    require({item["id"] for item in peripheral["bundles"]} == expected_peripheral and
            all(item["state"] == "MISSING" for item in peripheral["bundles"]), "外设六包缺件漂移")
    require((peripheral["verifiedBundleCount"], peripheral["requiredBundleCount"]) == (0, 6) and
            peripheral["verifiedDocumentStatus"] == "NOT_ACHIEVED" and
            peripheral["executionAdmission"] == "NO_GO" and
            peripheral["realPeripheralCommandsAllowed"] is False and
            peripheral["realPeripheralCommands"] == 0, "外设执行边界漂移")

    partner = load("partner-execution-admission.json")
    require(partner["requirementId"] == "T2-PAR-001" and partner["status"] == "BLOCKED",
            "伙伴身份或状态漂移")
    require((partner["verifiedRealTargetCount"], partner["targetRequired"]) == (0, 5) and
            (partner["verifiedWrittenIntentCount"], partner["verifiedWrittenIntentRequired"]) == (0, 3),
            "伙伴目标或意愿计数漂移")
    require(len(partner["slots"]) == 5 and all(item["state"] == "MISSING" for item in partner["slots"]),
            "伙伴缺件被错误提升")
    require(partner["verifiedDocumentStatus"] == "NOT_ACHIEVED" and
            partner["executionAdmission"] == "NO_GO" and partner["contactAllowed"] is False and
            partner["partnerContacts"] == 0 and partner["onsitePilotAllowed"] is False and
            partner["onsitePilots"] == 0, "伙伴执行边界漂移")
    return payment, hardware, peripheral, partner


def validate_license_and_entry() -> tuple[dict, dict]:
    license_contract = load("license-closure-admission.json")
    require(license_contract["requirementId"] == "T2-LIC-001" and
            license_contract["status"] == "DEFERRED", "许可证状态漂移")
    require({item["name"] for item in license_contract["components"]} ==
            {"Aviator", "simple-http", "MySQL Connector/J"}, "许可证组件不完整")
    require(all(item["closureState"] == "OPEN" for item in license_contract["components"]),
            "未具备关闭证据的许可证被错误关闭")
    require((license_contract["closedComponentCount"],
             license_contract["requiredClosedComponentCount"]) == (0, 3) and
            license_contract["commercialReleaseDecision"] == "NO_GO", "许可证 NO-GO 漂移")
    root_pom = (ROOT / "server/pom.xml").read_text(encoding="utf-8")
    admin_pom = (ROOT / "server/ruoyi-admin/pom.xml").read_text(encoding="utf-8")
    require("com.googlecode.aviator" in root_pom and "<artifactId>aviator</artifactId>" in root_pom,
            "Aviator 仓库观察失真")
    require("<artifactId>mysql-connector-j</artifactId>" in admin_pom, "MySQL 运行时观察失真")
    server_poms = "\n".join(path.read_text(encoding="utf-8", errors="ignore")
                             for path in (ROOT / "server").rglob("pom.xml"))
    require("simple-http" not in server_poms and "simplehttp" not in server_poms.lower(),
            "simple-http 当前直接声明观察失真")

    entry = load("uat-release-entry-freeze.json")
    require(entry["requirements"] == {"T2-UAT-001": "DRAFT", "T2-REL-001": "DRAFT"},
            "UAT/REL 状态漂移")
    require(entry["internalCandidate"]["status"] == "ACCEPTED" and
            entry["internalCandidate"]["classification"] == "INTERNAL_RELEASE_READINESS_CANDIDATE",
            "内部候选身份漂移")
    require(entry["fullAlpha"]["decision"] == "NO_GO_FULL_ALPHA" and
            entry["fullAlpha"]["allowed"] is False and entry["fullAlpha"]["runs"] == 0 and
            entry["fullAlpha"]["entryBlockers"] ==
            ["T2-PAY-002", "T2-HWD-001", "T2-PRN-001", "T2-PAR-001"],
            "完整 Alpha 入口漂移")
    require(entry["release"]["decision"] == "NO_GO_PRODUCTION_AND_COMMERCIAL" and
            entry["release"]["allowed"] is False and
            all(entry["release"][key] == 0 for key in
                ("productionDeployments", "commercialTags", "commercialClaims")), "发布入口漂移")
    return license_contract, entry


def validate_scope_and_secrets() -> list[str]:
    required_docs = [
        "docs/t2-gate8d-prep/README.md",
        "docs/t2-gate8d-prep/01_范围证据等级与离线验真规则.md",
        "docs/t2-gate8d-prep/02_T2_PAY002支付沙箱执行准入复核报告.md",
        "docs/t2-gate8d-prep/03_T2_HWD001真实硬件执行准入复核报告.md",
        "docs/t2-gate8d-prep/04_T2_PRN001真实外设执行准入复核报告.md",
        "docs/t2-gate8d-prep/05_T2_PAR001设计伙伴执行准入复核报告.md",
        "docs/t2-gate8d-prep/06_T2_LIC001商业许可证关闭复核报告.md",
        "docs/t2-gate8d-prep/07_T2完整AlphaUAT与发布启动条件冻结报告.md",
        "docs/t2-gate8d-prep/08_T2_Gate8D_Prep启动评审报告.md",
        "docs/t2-gate8d-prep/09_Gate8D下一步操作指令.md",
        "docs/t2-gate8d-prep/10_Gate8D_Prep证据索引.md",
        "docs/adr/ADR-067-gate8d-external-p0-license-alpha-release-admission.md",
        "docs/governance/CR-T2G8D-001_rdy001-accept-and-external-admission-review.md",
    ]
    require(all((ROOT / path).is_file() for path in required_docs), "Gate 8D-Prep 文档集不完整")
    require(git("merge-base", "--is-ancestor", BASELINE, "HEAD") == "", "RDY001 封存提交不是祖先")
    changed = sorted(set(filter(None, git("diff", "--name-only", BASELINE).splitlines()
                                    + git("ls-files", "--others", "--exclude-standard").splitlines())))
    allowed_exact = {
        "AGENTS.md", ".github/workflows/t2-gate8d-prep.yml", "docs/adr/README.md",
        "docs/adr/ADR-067-gate8d-external-p0-license-alpha-release-admission.md",
        "docs/governance/change-log.md", "docs/governance/rtm.csv",
        "docs/governance/CR-T2G8D-001_rdy001-accept-and-external-admission-review.md",
        "scripts/check_t2_gate8d_prep.py", "scripts/build_t2_gate8d_prep_evidence.py",
    }
    prefixes = ("contracts/t2/gate8d-prep/", "docs/t2-gate8d-prep/")
    illegal = [path for path in changed if path not in allowed_exact and not path.startswith(prefixes)]
    require(not illegal, f"出现运行时或越界文件: {illegal}")
    dependency_names = {
        "pom.xml", "package.json", "pnpm-lock.yaml", "pubspec.yaml", "pubspec.lock",
        "build.gradle", "build.gradle.kts", "settings.gradle", "settings.gradle.kts",
    }
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
    payment, hardware, peripheral, partner = validate_external_tracks()
    license_contract, entry = validate_license_and_entry()
    changed = validate_scope_and_secrets()
    result = {
        "gate": GATE,
        "status": "PASS",
        "evidenceLevel": admission["evidenceLevel"],
        "acceptedRequirement": admission["acceptedRequirement"],
        "preservedStates": admission["preservedStates"],
        "paymentMaterials": f"{payment['verifiedItemCount']}/{payment['requiredItemCount']}",
        "hardwareCandidates": f"{hardware['verifiedCandidateCount']}/{hardware['requiredCandidateCount']}",
        "peripheralBundles": f"{peripheral['verifiedBundleCount']}/{peripheral['requiredBundleCount']}",
        "partners": f"{partner['verifiedRealTargetCount']}/{partner['targetRequired']}",
        "writtenIntents": f"{partner['verifiedWrittenIntentCount']}/{partner['verifiedWrittenIntentRequired']}",
        "licenseClosures": f"{license_contract['closedComponentCount']}/{license_contract['requiredClosedComponentCount']}",
        "fullAlphaDecision": entry["fullAlpha"]["decision"],
        "releaseDecision": entry["release"]["decision"],
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
