#!/usr/bin/env python3
"""校验 G9A-R2 准备阶段的范围、状态和历史证据守恒。"""
from __future__ import annotations

import csv
import json
import pathlib
import subprocess


ROOT = pathlib.Path(__file__).resolve().parents[1]
BASELINE = "53e540fd14559e7ae0f907b244b0dbac37167cfe"
CONTRACT = ROOT / "contracts/t2/gate9b-r2-prep"
RUNTIME_PREFIXES = ("server/", "admin-web/", "pos-flutter/", "packages/", "infra/")
ALLOWED_PREFIXES = (
    "AGENTS.md",
    ".github/workflows/t2-g9a-r2-prep.yml",
    "contracts/t2/gate9b/defect-closure-v1.json",
    "contracts/t2/gate9b-r2-prep/",
    "docs/adr/ADR-070-",
    "docs/adr/README.md",
    "docs/governance/CR-T2G9R2-",
    "docs/governance/change-log.md",
    "docs/t2-gate9b-r2-prep/",
    "scripts/check_t2_g9a_r2_prep.py",
    "scripts/audit_t2_g9a_r2_assembly.py",
    "scripts/build_t2_g9a_r2_prep_evidence.py",
)


def fail(message: str) -> None:
    raise AssertionError(message)


def git(*args: str) -> str:
    return subprocess.check_output(
        ["git", "-c", "core.quotepath=false", *args], cwd=ROOT, text=True, encoding="utf-8"
    ).strip()


def rtm_states() -> dict[str, str]:
    with (ROOT / "docs/governance/rtm.csv").open(encoding="utf-8-sig", newline="") as handle:
        return {row["requirement_id"]: row["status"] for row in csv.DictReader(handle)}


def main() -> int:
    if subprocess.run(
        ["git", "merge-base", "--is-ancestor", BASELINE, "HEAD"], cwd=ROOT
    ).returncode != 0:
        fail("Gate 9B 最终封板提交不是当前分支祖先")

    admission = json.loads((CONTRACT / "gate-admission-v1.json").read_text(encoding="utf-8"))
    if admission["baselineCommit"] != BASELINE or admission["findingId"] != "G9A-ASM-P1-001":
        fail("G9A-R2 准入契约与授权基线不一致")

    states = rtm_states()
    expected = {
        "T2-CORE-001": "ACCEPTED",
        "T2-SEC-002": "ACCEPTED",
        "T2-RDY-001": "ACCEPTED",
        "T2-CMP-001": "ACCEPTED",
        "T2-API-001": "ACCEPTED",
        **admission["preservedStates"],
    }
    drift = {key: states.get(key) for key, value in expected.items() if states.get(key) != value}
    if drift:
        fail(f"RTM 状态漂移: {drift}")

    closure = json.loads(
        (ROOT / "contracts/t2/gate9b/defect-closure-v1.json").read_text(encoding="utf-8")
    )
    if closure.get("state") != "VERIFIED" or closure.get("closureState") != "CLOSED_IN_GATE9B":
        fail("G9A-R1 发起人确认状态未正确记录")
    if closure.get("projectSponsorConfirmationRequired") is not False:
        fail("G9A-R1 仍错误地等待发起人确认")

    changed = [path for path in git("diff", "--name-only", BASELINE).splitlines() if path]
    runtime = [path for path in changed if path.startswith(RUNTIME_PREFIXES)]
    if runtime:
        fail(f"准备阶段出现未授权运行时变更: {runtime}")
    unexpected = [
        path for path in changed if not any(path == prefix or path.startswith(prefix) for prefix in ALLOWED_PREFIXES)
    ]
    if unexpected:
        fail(f"准备阶段出现未准入文件: {unexpected}")

    immutable = (
        "contracts/t2/gate9a-prep/defect-register-v1.json",
        "scripts/audit_t2_gate6g_api.py",
        "scripts/audit_t2_gate9a_product_completeness.py",
    )
    changed_immutable = [
        path for path in immutable
        if subprocess.run(["git", "diff", "--quiet", BASELINE, "--", path], cwd=ROOT).returncode != 0
    ]
    if changed_immutable:
        fail(f"历史审计或原始缺陷账被改写: {changed_immutable}")

    assembly = json.loads((CONTRACT / "assembly-baseline-v1.json").read_text(encoding="utf-8"))
    retention = json.loads((CONTRACT / "retention-decision-v1.json").read_text(encoding="utf-8"))
    matrix = json.loads((CONTRACT / "test-matrix-v1.json").read_text(encoding="utf-8"))
    if assembly.get("findingState") != "OPEN" or assembly.get("classification") != "CONFIRMED_PRODUCTION_ASSEMBLY_GAP":
        fail("准备阶段不得关闭或淡化 G9A-ASM-P1-001")
    if retention.get("status") != "PROPOSED_AWAITING_PROJECT_SPONSOR":
        fail("保留决策必须保持 Proposed 等待确认")
    if len(matrix.get("tests", [])) != 10 or not matrix.get("runtimeAdmissionRequired"):
        fail("正式整改测试矩阵不完整")
    if admission.get("externalExecution") != {
        "providerNetwork": 0,
        "realFunds": 0,
        "realDeviceCommands": 0,
        "realPeripheralCommands": 0,
        "partnerFieldExecution": 0,
        "fullAlpha": 0,
        "productionDeployment": 0,
    }:
        fail("准备阶段提升了外部执行证据")

    print(
        "G9A-R2 PREP SCOPE OK: baseline=53e540f runtimeChanges=0 "
        "finding=OPEN decisions=PROPOSED externalExecution=0"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
