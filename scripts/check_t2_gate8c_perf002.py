#!/usr/bin/env python3
"""T2-PERF-002 准入、范围、性能证据和外部零执行门禁。"""
from __future__ import annotations

import argparse
import csv
import json
import pathlib
import subprocess


ROOT = pathlib.Path(__file__).resolve().parents[1]
BASE = "262099bf788bcb6916af2480644883fa6c5aed49"
BRANCH = "t2/gate8c-sprint26c-perf002-runtime"
CONTRACT_DIR = ROOT / "contracts/t2/gate8c-perf002"
FINDINGS = {"G8C-PERF-P1-001", "G8C-PERF-P1-002"}
PRESERVED = {
    "T2-RDY-001": "DRAFT",
    "T2-PAY-002": "BLOCKED", "T2-HWD-001": "BLOCKED",
    "T2-PRN-001": "BLOCKED", "T2-PAR-001": "BLOCKED",
    "T2-UAT-001": "DRAFT", "T2-REL-001": "DRAFT",
    "T2-LIC-001": "DEFERRED", "T2-JSH-001": "DEFERRED",
}


def require(condition: bool, message: str) -> None:
    if not condition:
        raise SystemExit("T2-PERF-002 ERROR: " + message)


def git(*args: str) -> str:
    process = subprocess.run(
        ["git", "-c", "core.quotepath=false", *args], cwd=ROOT,
        capture_output=True, text=True, encoding="utf-8",
    )
    require(process.returncode == 0, "git failed: " + process.stderr.strip())
    return process.stdout.strip()


def load_json(name: str) -> dict:
    return json.loads((CONTRACT_DIR / name).read_text(encoding="utf-8"))


def rtm() -> dict[str, dict[str, str]]:
    with (ROOT / "docs/governance/rtm.csv").open(encoding="utf-8-sig", newline="") as stream:
        return {row["requirement_id"]: row for row in csv.DictReader(stream)}


def changed_files() -> list[str]:
    committed = set(filter(None, git("diff", "--name-only", BASE).splitlines()))
    untracked = set(filter(None, git("ls-files", "--others", "--exclude-standard").splitlines()))
    return sorted(committed | untracked)


def validate_scope() -> list[str]:
    require(git("merge-base", "--is-ancestor", BASE, "HEAD") == "", "MTN-001 封存提交不是祖先")
    require(git("branch", "--show-current") in {BRANCH, ""}, "当前分支不属于 T2-PERF-002")
    changed = changed_files()
    require(not [path for path in changed if "/db/migration/" in path], "禁止修改或新增数据库迁移")
    dependency_names = {
        "pom.xml", "package.json", "pnpm-lock.yaml", "pubspec.yaml", "pubspec.lock",
        "build.gradle.kts", "settings.gradle.kts", "gradle.properties",
    }
    require(not [path for path in changed if pathlib.PurePosixPath(path).name in dependency_names], "禁止依赖漂移")
    allowed_exact = {
        "AGENTS.md", "docs/adr/README.md", "docs/governance/change-log.md",
        "docs/governance/rtm.csv", "docs/adr/ADR-065-gate8c-formal-runtime-performance-rebaseline.md",
        "docs/governance/CR-T2G8C-011_mtn001-accept-perf002-runtime-admission.md",
        "docs/governance/CR-T2G8C-012_perf002-verified-candidate.md",
        "docs/governance/CR-T2G8C-013_perf002-ci-conditional-pass.md",
        ".github/workflows/t2-gate8c-perf002.yml",
        "scripts/check_t2_gate8c_perf002.py", "scripts/run_t2_gate8c_perf002_runtime.py",
        "scripts/build_t2_gate8c_perf002_evidence.py",
    }
    allowed_prefixes = (
        "docs/t2-gate8c-perf002/", "contracts/t2/gate8c-perf002/",
        "pos-flutter/test/gate8c_perf002/",
    )
    illegal = [path for path in changed if path not in allowed_exact and not path.startswith(allowed_prefixes)]
    require(not illegal, "存在越界变更: " + ", ".join(illegal))
    require(not [path for path in changed if "/src/main/" in path], "不得修改正式业务运行时")
    return changed


def validate_governance(stage: str) -> tuple[dict, dict, dict, dict, dict]:
    admission = load_json("perf002-admission.json")
    workload = load_json("workload-model-v1.json")
    executor = load_json("executor-spec-v1.json")
    thresholds = load_json("thresholds-v1.json")
    faults = load_json("fault-vectors-v1.json")
    rows = rtm()
    expected = "VERIFIED" if stage == "closure" else "IN_PROGRESS"
    require(admission["baseCommit"] == BASE and admission["branch"] == BRANCH, "准入基线漂移")
    require(admission["orderedFindings"] == sorted(FINDINGS), "性能发现集合或顺序漂移")
    require(rows["T2-MTN-001"]["status"] == "ACCEPTED", "T2-MTN-001 必须为 ACCEPTED")
    require(rows["T2-PERF-002"]["status"] == expected, f"T2-PERF-002 必须为 {expected}")
    for requirement, status in PRESERVED.items():
        require(rows[requirement]["status"] == status, requirement + " 状态漂移")
    accepted = sum(key.startswith("T2-") and row["status"] == "ACCEPTED" for key, row in rows.items())
    require(accepted == 85, f"T2 ACCEPTED 数量漂移: {accepted}")
    require(all(value == 0 for value in admission["externalExecution"].values()), "外部执行必须为零")
    require(admission["automaticRetryAllowed"] is False, "禁止自动重跑")
    require(admission["commercialSlaAllowed"] is False, "禁止商业 SLA")
    require(workload["formalRuntime"]["httpConcurrency"] == [1, 8, 16], "HTTP 并发模型漂移")
    require(workload["formalRuntime"]["sustainedSeconds"] >= 120, "持续运行窗口不足")
    require(workload["ownerCapacity"]["reportProjectionRows"] == 1_000_000, "报表容量规模漂移")
    require(workload["posLocal"] == {
        "scanIterations": 1000, "settlementIterations": 200,
        "syncBacklogEvents": 10000, "dataPackageRecords": 100000,
    }, "POS 固定负载漂移")
    require(executor["crossExecutorWallClockComparisonAllowed"] is False, "禁止跨执行器墙钟比较")
    require(len(executor["requiredFingerprintFields"]) >= 10, "执行器指纹字段不足")
    require(thresholds["correctness"]["maxErrorRate"] == 0.0, "错误率必须为零")
    require(thresholds["correctness"]["missingMeasurementAllowed"] is False, "缺失测量必须失败")
    require(thresholds["changeControl"]["sameFailureChangeMayRelaxThreshold"] is False, "失败时禁止放宽阈值")
    require({item["id"] for item in faults["vectors"]} == {f"PERF-F{number:03d}" for number in range(1, 11)},
            "故障向量集合漂移")
    return admission, workload, executor, thresholds, faults


def validate_documents(stage: str) -> None:
    required = [
        "docs/adr/ADR-065-gate8c-formal-runtime-performance-rebaseline.md",
        "docs/governance/CR-T2G8C-011_mtn001-accept-perf002-runtime-admission.md",
        "docs/t2-gate8c-perf002/README.md",
        "docs/t2-gate8c-perf002/01_运行时影响分析与设计准入.md",
        "docs/t2-gate8c-perf002/02_正式栈容量并发与持续运行模型.md",
        "docs/t2-gate8c-perf002/03_执行器规格阈值与可比性.md",
        "docs/t2-gate8c-perf002/04_故障注入退化与恢复准入.md",
    ]
    if stage == "closure":
        required.extend([
            "docs/t2-gate8c-perf002/05_T2_PERF002独立验证报告.md",
            "docs/t2-gate8c-perf002/06_T2_PERF002独立周门禁报告.md",
            "docs/t2-gate8c-perf002/07_下一步操作指令.md",
            "contracts/t2/gate8c-perf002/findings-closure.json",
            ".github/workflows/t2-gate8c-perf002.yml",
        ])
    require(all((ROOT / path).is_file() for path in required), "T2-PERF-002 交付物不完整")
    adr = (ROOT / required[0]).read_text(encoding="utf-8")
    require("状态：Accepted" in adr, "ADR-065 未接受")


def validate_closure() -> dict:
    closure = load_json("findings-closure.json")
    require(set(closure["findings"]) == FINDINGS, "关闭发现集合漂移")
    require(all(item["state"] == "CLOSED_INTERNAL_VERIFIED" for item in closure["findings"].values()),
            "性能发现未全部内部关闭")
    require(closure["openPerformanceP0"] == 0 and closure["openPerformanceP1"] == 0, "仍有开放性能缺陷")
    require(closure["commercialSla"] is False and closure["realDeviceEvidence"] is False, "证据边界漂移")
    return closure


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--stage", choices=("admission", "closure"), default="closure")
    parser.add_argument("--output", type=pathlib.Path)
    args = parser.parse_args()
    changed = validate_scope()
    admission, workload, executor, thresholds, faults = validate_governance(args.stage)
    validate_documents(args.stage)
    closure = validate_closure() if args.stage == "closure" else None
    result = {
        "schemaVersion": "1.0", "gate": "T2-GATE8C-SPRINT-S26C", "status": "PASS",
        "requirementId": "T2-PERF-002", "requirementStatus": "VERIFIED" if args.stage == "closure" else "IN_PROGRESS",
        "baselineCommit": BASE, "branch": BRANCH, "evidenceCeiling": admission["evidenceCeiling"],
        "findingIds": sorted(FINDINGS), "workloadPlanes": ["FORMAL_RUNTIME", "OWNER_CAPACITY", "POS_LOCAL"],
        "httpConcurrency": workload["formalRuntime"]["httpConcurrency"],
        "faultVectorCount": len(faults["vectors"]), "executor": executor["comparableExecutor"],
        "thresholdClassification": thresholds["classification"], "databaseMigrationsChanged": 0,
        "dependenciesChanged": 0, "newBusinessCapabilities": 0, "changedFiles": changed,
        "preservedStates": PRESERVED, "externalExecution": admission["externalExecution"],
        "closure": closure,
        "decision": "CONDITIONAL_PASS_AWAITING_SPONSOR_ACCEPTANCE" if args.stage == "closure" else "ADMITTED_IN_PROGRESS",
    }
    if args.output:
        target = args.output if args.output.is_absolute() else ROOT / args.output
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({key: value for key, value in result.items() if key != "changedFiles"}, ensure_ascii=False))


if __name__ == "__main__":
    main()
