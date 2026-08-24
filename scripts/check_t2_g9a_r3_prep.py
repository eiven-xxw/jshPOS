#!/usr/bin/env python3
"""校验 G9A-R3 准备阶段的授权基线、范围和状态守恒。"""
from __future__ import annotations

import csv
import json
import pathlib
import subprocess


ROOT = pathlib.Path(__file__).resolve().parents[1]
BASELINE = "d1947139a7538b9724dcec236d4ded9255adc74c"
CONTRACT = ROOT / "contracts/t2/gate9b-r3-prep"
RUNTIME_PREFIXES = ("server/", "admin-web/", "pos-flutter/", "packages/", "infra/")
ALLOWED_PREFIXES = (
    "AGENTS.md",
    ".github/workflows/t2-g9a-r3-prep.yml",
    "contracts/t2/gate9b-r2/defect-closure-v1.json",
    "contracts/t2/gate9b-r3-prep/",
    "docs/adr/ADR-071-",
    "docs/adr/README.md",
    "docs/governance/CR-T2G9R3-",
    "docs/governance/change-log.md",
    "docs/t2-gate9b-r2/06_G9A_R2项目发起人接受记录.md",
    "docs/t2-gate9b-r3-prep/",
    "scripts/check_t2_g9a_r3_prep.py",
    "scripts/audit_t2_g9a_r3_pages.py",
    "scripts/build_t2_g9a_r3_prep_evidence.py",
)


def fail(message: str) -> None:
    raise AssertionError(message)


def git(*args: str) -> str:
    return subprocess.check_output(
        ["git", "-c", "core.quotepath=false", *args], cwd=ROOT, text=True, encoding="utf-8"
    ).strip()


def rtm_states() -> tuple[dict[str, str], int]:
    with (ROOT / "docs/governance/rtm.csv").open(encoding="utf-8-sig", newline="") as handle:
        rows = list(csv.DictReader(handle))
    return ({row["requirement_id"]: row["status"] for row in rows},
            sum(1 for row in rows if row["phase"] == "T2" and row["status"] == "ACCEPTED"))


def main() -> int:
    if subprocess.run(["git", "merge-base", "--is-ancestor", BASELINE, "HEAD"], cwd=ROOT).returncode != 0:
        fail("G9A-R2 最终封板提交不是当前分支祖先")

    admission = json.loads((CONTRACT / "gate-admission-v1.json").read_text(encoding="utf-8"))
    if admission["baselineCommit"] != BASELINE or admission["findingId"] != "G9A-UI-P1-001":
        fail("G9A-R3 准入契约与授权基线不一致")
    if admission["stage"] != "STATIC_UI_AUDIT_AND_RUNTIME_ADMISSION_PREP":
        fail("G9A-R3 当前阶段不是准备审计")

    states, accepted_count = rtm_states()
    if accepted_count != admission["expectedAcceptedT2Requirements"]:
        fail(f"T2 ACCEPTED 数量漂移: {accepted_count}")
    expected = {
        "T2-UX-001": "ACCEPTED",
        "T2-ADM-001": "ACCEPTED",
        "T2-ADM-002": "ACCEPTED",
        "T2-POS-007": "ACCEPTED",
        "T2-POS-008": "ACCEPTED",
        "T2-POS-009": "ACCEPTED",
        **admission["preservedStates"],
    }
    drift = {key: states.get(key) for key, value in expected.items() if states.get(key) != value}
    if drift:
        fail(f"RTM 状态漂移: {drift}")

    closure = json.loads((ROOT / "contracts/t2/gate9b-r2/defect-closure-v1.json").read_text(encoding="utf-8"))
    if closure.get("state") != "CLOSED_IN_GATE9B" or closure.get("projectSponsorConfirmationRequired") is not False:
        fail("G9A-R2 项目发起人关闭决定未正确记录")

    changed = [path for path in git("diff", "--name-only", BASELINE).splitlines() if path]
    runtime = [path for path in changed if path.startswith(RUNTIME_PREFIXES)]
    if runtime:
        fail(f"准备阶段出现未授权运行时变更: {runtime}")
    unexpected = [
        path for path in changed
        if not any(path == prefix or path.startswith(prefix) for prefix in ALLOWED_PREFIXES)
    ]
    if unexpected:
        fail(f"准备阶段出现未准入文件: {unexpected}")

    immutable = (
        "contracts/t2/gate9a-prep/defect-register-v1.json",
        "contracts/t2/gate9a-prep/ui-surface-catalog-v1.json",
        "scripts/audit_t2_gate9a_product_completeness.py",
        "docs/governance/rtm.csv",
    )
    changed_immutable = [
        path for path in immutable
        if subprocess.run(["git", "diff", "--quiet", BASELINE, "--", path], cwd=ROOT).returncode != 0
    ]
    if changed_immutable:
        fail(f"历史审计、页面清单或 RTM 被改写: {changed_immutable}")

    freeze = json.loads((CONTRACT / "surface-freeze-v1.json").read_text(encoding="utf-8"))
    if freeze["surfaceCounts"] != {"total": 26, "vue": 20, "flutter": 6}:
        fail("26 个页面冻结计数不正确")
    gaps = json.loads((CONTRACT / "test-gap-register-v1.json").read_text(encoding="utf-8"))
    if gaps["state"] != "OPEN" or gaps["summary"]["openP1"] != 5 or gaps["summary"]["openP0"] != 0:
        fail("G9A-UI 准备缺口状态不正确")
    if admission["externalExecution"] != {
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
        "G9A-R3 PREP SCOPE OK: baseline=d194713 accepted=88 runtimeChanges=0 "
        "surfaces=26 finding=OPEN externalExecution=0"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
