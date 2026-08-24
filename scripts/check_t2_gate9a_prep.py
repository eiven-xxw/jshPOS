#!/usr/bin/env python3
"""校验 Gate 9A-Prep 治理、范围、状态和缺陷账守恒。"""
from __future__ import annotations

import csv
import json
import pathlib
import subprocess
import sys


ROOT = pathlib.Path(__file__).resolve().parents[1]
BASELINE = "4ba20f8bb9bfdc36f4fee1b831ca35c7b54b9533"
FORBIDDEN_RUNTIME_PREFIXES = ("server/", "admin-web/", "pos-flutter/", "packages/", "infra/")
REQUIRED = (
    "AGENTS.md",
    "docs/adr/ADR-068-gate9a-internal-product-completeness-audit.md",
    "docs/governance/CR-T2G9A-001_internal-product-completeness-audit.md",
    "docs/t2-gate9a-prep/README.md",
    "docs/t2-gate9a-prep/01_审计范围方法与判定规则.md",
    "docs/t2-gate9a-prep/02_商业V1内部产品完整性审计报告.md",
    "docs/t2-gate9a-prep/03_缺陷账.md",
    "docs/t2-gate9a-prep/04_页面API_Owner数据与测试覆盖矩阵.md",
    "docs/t2-gate9a-prep/05_分批修复计划.md",
    "docs/t2-gate9a-prep/06_第一批正式修复启动指令.md",
    "docs/t2-gate9a-prep/07_T2_Gate9A_Prep启动评审报告.md",
    "docs/t2-gate9a-prep/08_证据索引.md",
    "contracts/t2/gate9a-prep/gate9a-admission.json",
    "contracts/t2/gate9a-prep/owner-catalog-v1.json",
    "contracts/t2/gate9a-prep/ui-surface-catalog-v1.json",
    "contracts/t2/gate9a-prep/defect-register-v1.json",
    "contracts/t2/gate9a-prep/repair-batches-v1.json",
    "scripts/audit_t2_gate9a_product_completeness.py",
    "scripts/build_t2_gate9a_prep_evidence.py",
    ".github/workflows/t2-gate9a-prep.yml",
)


def run(*args: str) -> str:
    return subprocess.check_output(args, cwd=ROOT, text=True, encoding="utf-8").strip()


def fail(message: str) -> None:
    print(f"T2 Gate9A PREP ERROR: {message}", file=sys.stderr)
    raise SystemExit(1)


def main() -> None:
    missing = [path for path in REQUIRED if not (ROOT / path).is_file()]
    if missing:
        fail(f"required artifacts missing: {missing}")

    if subprocess.run(["git", "merge-base", "--is-ancestor", BASELINE, "HEAD"], cwd=ROOT).returncode != 0:
        fail("Gate 8D final commit is not an ancestor")

    changed = run("git", "diff", "--name-only", BASELINE, "HEAD").splitlines()
    runtime_changes = [path for path in changed if path.startswith(FORBIDDEN_RUNTIME_PREFIXES)]
    if runtime_changes:
        fail(f"runtime changes are forbidden in prep: {runtime_changes}")
    historical = (
        "scripts/audit_t2_gate6g_core.py",
        "scripts/audit_t2_gate6g_api.py",
        "scripts/audit_t2_gate6g_integration.py",
    )
    historical_drift = [
        path for path in historical
        if subprocess.run(["git", "diff", "--quiet", BASELINE, "HEAD", "--", path], cwd=ROOT).returncode != 0
    ]
    if historical_drift:
        fail(f"historical auditors must remain immutable: {historical_drift}")

    with (ROOT / "docs/governance/rtm.csv").open(encoding="utf-8-sig", newline="") as handle:
        rows = list(csv.DictReader(handle))
    t2 = {row["requirement_id"]: row for row in rows if row["phase"] == "T2"}
    accepted = [row for row in t2.values() if row["status"] == "ACCEPTED"]
    if len(accepted) != 87:
        fail(f"accepted T2 requirement count drift: {len(accepted)}")
    if t2.get("T2-CMP-001", {}).get("status") not in {"IN_PROGRESS", "VERIFIED"}:
        fail("T2-CMP-001 must remain IN_PROGRESS or VERIFIED before sponsor acceptance")
    expected_states = {
        "T2-PAY-002": "BLOCKED", "T2-HWD-001": "BLOCKED", "T2-PRN-001": "BLOCKED",
        "T2-PAR-001": "BLOCKED", "T2-UAT-001": "DRAFT", "T2-REL-001": "DRAFT",
        "T2-LIC-001": "DEFERRED", "T2-JSH-001": "DEFERRED",
    }
    drift = {key: t2.get(key, {}).get("status") for key, value in expected_states.items()
             if t2.get(key, {}).get("status") != value}
    if drift:
        fail(f"preserved state drift: {drift}")

    owners = json.loads((ROOT / "contracts/t2/gate9a-prep/owner-catalog-v1.json").read_text(encoding="utf-8"))["owners"]
    modules = [item["module"] for item in owners]
    if len(modules) != 22 or len(set(modules)) != 22:
        fail("owner catalog must contain 22 unique modules")
    defects = json.loads((ROOT / "contracts/t2/gate9a-prep/defect-register-v1.json").read_text(encoding="utf-8"))
    open_p0 = sum(1 for item in defects["findings"] if item["severity"] == "P0" and item["state"] == "OPEN")
    open_p1 = sum(1 for item in defects["findings"] if item["severity"] == "P1" and item["state"] == "OPEN")
    if (open_p0, open_p1) != (0, 4) or defects["summary"]["runtimeFixesApplied"] != 0:
        fail(f"defect register drift: P0={open_p0} P1={open_p1}")
    batches = json.loads((ROOT / "contracts/t2/gate9a-prep/repair-batches-v1.json").read_text(encoding="utf-8"))["batches"]
    if [item["batchId"] for item in batches] != ["G9A-R1", "G9A-R2", "G9A-R3", "G9A-R4"]:
        fail("repair batch order drift")

    admission = json.loads((ROOT / "contracts/t2/gate9a-prep/gate9a-admission.json").read_text(encoding="utf-8"))
    if any(admission["externalExecution"].values()):
        fail("external execution must stay zero")
    print(f"T2 Gate9A PREP OK: accepted={len(accepted)} owners={len(modules)} P0={open_p0} P1={open_p1} runtimeChanges=0")


if __name__ == "__main__":
    main()
