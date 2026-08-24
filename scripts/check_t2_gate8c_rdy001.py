#!/usr/bin/env python3
"""T2-RDY-001 准入、发布边界、状态和零外部执行门禁。"""
from __future__ import annotations

import argparse
import csv
import json
import pathlib
import re
import subprocess


ROOT = pathlib.Path(__file__).resolve().parents[1]
BASE = "721130ab57a2fe2b2f024150d85e237491e5b34c"
BRANCH = "t2/gate8c-sprint26d-rdy001-runtime"
CONTRACT_DIR = ROOT / "contracts/t2/gate8c-rdy001"
FINDINGS = {"G8C-REL-P0-001", "G8C-REL-P0-002", "G8C-REL-P1-003", "G8C-REL-P1-004"}
PRESERVED = {
    "T2-PAY-002": "BLOCKED", "T2-HWD-001": "BLOCKED",
    "T2-PRN-001": "BLOCKED", "T2-PAR-001": "BLOCKED",
    "T2-UAT-001": "DRAFT", "T2-REL-001": "DRAFT",
    "T2-LIC-001": "DEFERRED", "T2-JSH-001": "DEFERRED",
}


def require(condition: bool, message: str) -> None:
    if not condition:
        raise SystemExit("T2-RDY-001 ERROR: " + message)


def git(*args: str) -> str:
    process = subprocess.run(
        ["git", "-c", "core.quotepath=false", *args], cwd=ROOT,
        capture_output=True, text=True, encoding="utf-8",
    )
    require(process.returncode == 0, "git failed: " + process.stderr.strip())
    return process.stdout.strip()


def load(name: str) -> dict:
    return json.loads((CONTRACT_DIR / name).read_text(encoding="utf-8"))


def rows() -> dict[str, dict[str, str]]:
    with (ROOT / "docs/governance/rtm.csv").open(encoding="utf-8-sig", newline="") as stream:
        return {row["requirement_id"]: row for row in csv.DictReader(stream)}


def changed_files() -> list[str]:
    committed = set(filter(None, git("diff", "--name-only", BASE).splitlines()))
    untracked = set(filter(None, git("ls-files", "--others", "--exclude-standard").splitlines()))
    return sorted(committed | untracked)


def validate_scope() -> list[str]:
    require(git("merge-base", "--is-ancestor", BASE, "HEAD") == "", "PERF-002 封存提交不是祖先")
    require(git("branch", "--show-current") in {BRANCH, ""}, "当前分支不属于 T2-RDY-001")
    changed = changed_files()
    require(not [path for path in changed if "/db/migration/" in path], "禁止修改数据库迁移")
    dependency_names = {
        "pom.xml", "package.json", "pnpm-lock.yaml", "pubspec.yaml", "pubspec.lock",
        "build.gradle.kts", "settings.gradle.kts", "gradle.properties",
    }
    require(not [path for path in changed if pathlib.PurePosixPath(path).name in dependency_names], "禁止依赖漂移")
    allowed_exact = {
        "AGENTS.md", "docs/adr/README.md", "docs/governance/change-log.md",
        "docs/governance/rtm.csv", "docs/adr/ADR-066-gate8c-internal-release-readiness.md",
        "docs/governance/CR-T2G8C-019_perf002-accept-rdy001-runtime-admission.md",
        "docs/governance/CR-T2G8C-020_rdy001-verified-candidate.md",
        "docs/governance/CR-T2G8C-021_rdy001-first-candidate-migration-catalog-failure.md",
        "docs/governance/CR-T2G8C-022_rdy001-second-candidate-framework-fixture-failure.md",
        "docs/governance/CR-T2G8C-023_rdy001-ci-conditional-pass.md",
        ".github/workflows/t2-gate8c-rdy001.yml",
        "scripts/check_t2_gate8c_rdy001.py", "scripts/build_t2_gate8c_rdy001_release.py",
        "scripts/run_t2_gate8c_rdy001_faults.py", "scripts/build_t2_gate8c_rdy001_evidence.py",
        "scripts/build_t2_gate8c_rdy001_evidence_index.py",
        "packages/pos_device_adapter/LICENSE", "packages/pos_device_adapter/CHANGELOG.md",
        "server/ruoyi-modules/jshpos-release/src/test/java/com/jingshanghui/pos/release/migration/ReleaseMigrationMySqlIT.java",
    }
    allowed_prefixes = ("docs/t2-gate8c-rdy001/", "contracts/t2/gate8c-rdy001/")
    illegal = [path for path in changed if path not in allowed_exact and not path.startswith(allowed_prefixes)]
    require(not illegal, "存在越界变更: " + ", ".join(illegal))
    require(not [path for path in changed if "/src/main/" in path], "禁止修改正式业务运行时")
    return changed


def validate_governance(stage: str) -> tuple[dict, dict, dict, dict, dict, dict]:
    admission = load("rdy001-admission.json")
    catalog = load("artifact-catalog-v1.json")
    signing = load("signing-policy-v1.json")
    deployment = load("deployment-profile-v1.json")
    disposition = load("findings-disposition.json")
    decision = load("go-no-go-v1.json")
    rtm = rows()
    expected = "VERIFIED" if stage == "closure" else "IN_PROGRESS"
    require(admission["baseCommit"] == BASE and admission["branch"] == BRANCH, "准入基线漂移")
    require(admission["orderedFindings"] == ["G8C-REL-P0-001", "G8C-REL-P0-002", "G8C-REL-P1-003", "G8C-REL-P1-004"], "发现顺序漂移")
    require(rtm["T2-PERF-002"]["status"] == "ACCEPTED", "T2-PERF-002 必须为 ACCEPTED")
    require(rtm["T2-RDY-001"]["status"] == expected, f"T2-RDY-001 必须为 {expected}")
    for requirement, status in PRESERVED.items():
        require(rtm[requirement]["status"] == status, requirement + " 状态漂移")
    accepted = sum(key.startswith("T2-") and row["status"] == "ACCEPTED" for key, row in rtm.items())
    require(accepted == 86, f"T2 ACCEPTED 数量漂移: {accepted}")
    require(all(value == 0 for value in admission["externalExecution"].values()), "外部执行必须为零")
    require(admission["newBusinessCapabilities"] == admission["databaseMigrationsChanged"] == admission["dependenciesChanged"] == 0, "实现范围漂移")
    artifacts = catalog["requiredArtifacts"]
    require(len(artifacts) == 10 and len({item["artifactId"] for item in artifacts}) == 10, "发布物目录不完整或重复")
    require(all(item["productionEligible"] is False for item in artifacts), "内部制品不得标记生产可用")
    require(signing["keyClass"] == "SYNTHETIC_EPHEMERAL_CI_ONLY" and signing["privateKeyArtifactAllowed"] is False, "签名边界漂移")
    require(deployment["productionDeployAllowed"] is False and deployment["unauthorizedCloudWrites"] == 0, "部署边界漂移")
    require(set(disposition["findings"]) == FINDINGS and not any(item["closed"] for item in disposition["findings"].values()), "外部发现被错误关闭")
    require(disposition["commercialReleaseDecision"] == "NO_GO", "商业发布必须 NO_GO")
    require(decision["licenseClosure"] == {"closed": 0, "required": 3, "requirementId": "T2-LIC-001"}, "许可证阻断漂移")
    require(decision["decisions"]["production"].startswith("NO_GO") and decision["decisions"]["commercial"].startswith("NO_GO"), "生产商业决策漂移")
    return admission, catalog, signing, deployment, disposition, decision


def validate_documents(stage: str) -> None:
    required = [
        "docs/adr/ADR-066-gate8c-internal-release-readiness.md",
        "docs/governance/CR-T2G8C-019_perf002-accept-rdy001-runtime-admission.md",
        "docs/t2-gate8c-rdy001/README.md",
        "docs/t2-gate8c-rdy001/01_运行时影响分析与设计准入.md",
        "docs/t2-gate8c-rdy001/02_发布物清单签名与供应链设计.md",
        "docs/t2-gate8c-rdy001/03_部署配置许可证与运维证据设计.md",
        "docs/t2-gate8c-rdy001/04_GoNoGo故障向量与验收标准.md",
    ]
    if stage == "closure":
        required.extend([
            "docs/t2-gate8c-rdy001/05_T2_RDY001独立验证报告.md",
            "docs/t2-gate8c-rdy001/06_T2_RDY001独立周门禁报告.md",
            "docs/t2-gate8c-rdy001/07_下一步操作指令.md",
            ".github/workflows/t2-gate8c-rdy001.yml",
        ])
    require(all((ROOT / path).is_file() for path in required), "交付文档不完整")
    require("状态：Accepted" in (ROOT / required[0]).read_text(encoding="utf-8"), "ADR-066 未接受")


def validate_internal_package(stage: str) -> None:
    if stage != "closure":
        return
    license_text = (ROOT / "packages/pos_device_adapter/LICENSE").read_text(encoding="utf-8")
    changelog = (ROOT / "packages/pos_device_adapter/CHANGELOG.md").read_text(encoding="utf-8")
    require("TODO" not in license_text and "TODO" not in changelog, "自有设备包仍含许可或变更日志占位")
    require("INTERNAL PROPRIETARY" in license_text and "0.1.0" in changelog, "自有包所有权或版本记录不完整")


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--stage", choices=("admission", "closure"), default="closure")
    parser.add_argument("--output", type=pathlib.Path)
    args = parser.parse_args()
    changed = validate_scope()
    admission, catalog, signing, deployment, disposition, decision = validate_governance(args.stage)
    validate_documents(args.stage)
    validate_internal_package(args.stage)
    vectors = load("test-vectors-v1.json")
    require(len(vectors["vectors"]) == 14 and len({item["id"] for item in vectors["vectors"]}) == 14, "故障向量不完整")
    workflow = ROOT / ".github/workflows/t2-gate8c-rdy001.yml"
    if args.stage == "closure":
        content = workflow.read_text(encoding="utf-8")
        markers = ["governance-ubuntu:", "governance-windows:", "server:", "web:", "flutter:", "mysql-operations:", "security:", "release-readiness:", "evidence:"]
        require(all(marker in content for marker in markers), "完整 CI Job 不完整")
        require(not re.search(r"BEGIN (?:RSA |EC )?PRIVATE KEY", content), "工作流包含私钥")
    result = {
        "schemaVersion": "1.0", "gate": "T2-GATE8C-SPRINT-S26D", "status": "PASS",
        "requirementId": "T2-RDY-001", "requirementStatus": "VERIFIED" if args.stage == "closure" else "IN_PROGRESS",
        "baselineCommit": BASE, "branch": BRANCH, "evidenceCeiling": admission["evidenceCeiling"],
        "findingIds": sorted(FINDINGS), "artifactCount": len(catalog["requiredArtifacts"]),
        "signing": signing, "deployment": deployment, "findingsDisposition": disposition,
        "decisions": decision["decisions"], "licenseClosure": decision["licenseClosure"],
        "newBusinessCapabilities": 0, "databaseMigrationsChanged": 0, "dependenciesChanged": 0,
        "preservedStates": PRESERVED, "externalExecution": admission["externalExecution"],
        "changedFiles": changed,
        "decision": "CONDITIONAL_PASS_AWAITING_SPONSOR_ACCEPTANCE" if args.stage == "closure" else "ADMITTED_IN_PROGRESS",
    }
    if args.output:
        target = args.output if args.output.is_absolute() else ROOT / args.output
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({key: value for key, value in result.items() if key != "changedFiles"}, ensure_ascii=False))


if __name__ == "__main__":
    main()
