#!/usr/bin/env python3
"""Gate 6G 串行准入、外部零执行和内部产品化边界门禁。"""
from __future__ import annotations

import argparse
import csv
import json
import pathlib
import re
import subprocess


ROOT = pathlib.Path(__file__).resolve().parents[1]
BASELINE = "a6c91a1d66857583ce7e498541b63bcf0b81dc52"
BRANCH = "t2/gate6g-sprint17-core-productization"
ADMISSION = ROOT / "contracts/t2/gate6g/gate6g-admission.json"
RTM = ROOT / "docs/governance/rtm.csv"
SEQUENCE = (
    "T2-CORE-001",
    "T2-API-001",
    "T2-DAT-001",
    "T2-INT-001",
    "T2-E2E-003",
)
ALLOWED_STATUSES = {
    ("IN_PROGRESS", "DRAFT", "DRAFT", "DRAFT", "DRAFT"),
    ("VERIFIED", "DRAFT", "DRAFT", "DRAFT", "DRAFT"),
    ("VERIFIED", "IN_PROGRESS", "DRAFT", "DRAFT", "DRAFT"),
    ("VERIFIED", "VERIFIED", "DRAFT", "DRAFT", "DRAFT"),
    ("VERIFIED", "VERIFIED", "IN_PROGRESS", "DRAFT", "DRAFT"),
    ("VERIFIED", "VERIFIED", "VERIFIED", "DRAFT", "DRAFT"),
    ("VERIFIED", "VERIFIED", "VERIFIED", "IN_PROGRESS", "DRAFT"),
    ("VERIFIED", "VERIFIED", "VERIFIED", "VERIFIED", "DRAFT"),
    ("VERIFIED", "VERIFIED", "VERIFIED", "VERIFIED", "IN_PROGRESS"),
    ("VERIFIED", "VERIFIED", "VERIFIED", "VERIFIED", "VERIFIED"),
}
PRESERVED = {
    "T2-PAY-002": "BLOCKED",
    "T2-HWD-001": "BLOCKED",
    "T2-PAR-001": "BLOCKED",
    "T2-PRN-001": "BLOCKED",
    "T2-UAT-001": "DRAFT",
    "T2-REL-001": "DRAFT",
    "V1-SAA-001": "DRAFT",
    "V1-PRD-001": "DRAFT",
    "V1-POS-001": "DRAFT",
    "V1-PAY-001": "DRAFT",
    "V1-INV-001": "DRAFT",
    "V1-PRM-001": "DRAFT",
    "V1-SYN-001": "DRAFT",
    "T2-LIC-001": "DEFERRED",
    "T2-JSH-001": "DEFERRED",
}


def fail(message: str) -> None:
    raise SystemExit(f"T2-GATE6G ERROR: {message}")


def git(*args: str) -> str:
    completed = subprocess.run(
        ["git", *args], cwd=ROOT, check=False, capture_output=True, text=True
    )
    if completed.returncode:
        fail(f"git {' '.join(args)} failed: {completed.stderr.strip()}")
    return completed.stdout.strip()


def load_json(path: pathlib.Path) -> dict:
    try:
        return json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as exception:
        fail(f"invalid JSON {path.relative_to(ROOT)}: {exception}")


def validate_serial(document: dict, rows: dict[str, dict[str, str]]) -> tuple[str, ...]:
    if document.get("baselineCommit") != BASELINE:
        fail("baseline commit drift")
    if document.get("branch") != BRANCH:
        fail("branch contract drift")
    requirements = document.get("sequentialRequirements", [])
    if tuple(item.get("id") for item in requirements) != SEQUENCE:
        fail("serial requirement order drift")
    statuses = tuple(str(item.get("status")) for item in requirements)
    if statuses not in ALLOWED_STATUSES:
        fail(f"illegal serial status tuple {statuses}")
    for item in requirements:
        requirement_id = item["id"]
        if rows.get(requirement_id, {}).get("status") != item["status"]:
            fail(f"RTM/admission status mismatch for {requirement_id}")
    return statuses


def validate_preserved(document: dict, rows: dict[str, dict[str, str]]) -> None:
    if document.get("preservedStates") != PRESERVED:
        fail("preserved state contract drift")
    for requirement_id, expected in PRESERVED.items():
        if rows.get(requirement_id, {}).get("status") != expected:
            fail(f"preserved RTM status changed: {requirement_id}")
    external = document.get("externalExecution", {})
    fields = (
        "providerNetworkCalls",
        "realFunds",
        "realDeviceCommands",
        "partnerContacts",
        "onsitePilots",
        "fullAlphaRuns",
        "productionDeployments",
    )
    for field in fields:
        if external.get(field) != 0:
            fail(f"external execution must remain zero: {field}")
    if external.get("commercialClaimAllowed") is not False:
        fail("commercial claim must remain forbidden")


def validate_branch() -> None:
    current = git("branch", "--show-current")
    if current and current != BRANCH:
        fail(f"unexpected branch {current}")
    completed = subprocess.run(
        ["git", "merge-base", "--is-ancestor", BASELINE, "HEAD"], cwd=ROOT
    )
    if completed.returncode:
        fail("baseline is not an ancestor of HEAD")


def validate_forbidden_boundaries() -> None:
    payment_main = ROOT / "server/ruoyi-modules/jshpos-payment/src/main"
    text = "\n".join(
        path.read_text(encoding="utf-8", errors="replace")
        for path in payment_main.rglob("*")
        if path.is_file()
    )
    for expression in (
        r"org\.springframework\.web\.client",
        r"java\.net\.http",
        r"okhttp",
        r"retrofit",
        r"https?://(?!mybatis\.org/dtd/)",
        r"拉卡拉|易宝|汇付|银联商务|乐刷",
    ):
        if re.search(expression, text, re.IGNORECASE):
            fail(f"payment Provider network boundary appeared: {expression}")
    ownership = {
        "catalog": ("cat_", "prc_", "dpk_"), "costing": ("inv_cost_",),
        "foundation": ("jsh_",), "inventory": ("inv_stock_", "inv_audit_", "inv_event_"),
        "member": ("mem_",), "order": ("ord_", "shf_"), "payment": ("pay_",),
        "procurement": ("sup_", "pur_"), "promotion": ("prm_",), "release": ("rel_",),
        "reporting": ("rpt_",), "resilience": ("bak_",), "returns": ("ret_",),
        "sync": ("pos_sync_",), "transfer": ("inv_transfer_",),
    }
    table_pattern = re.compile(r"\b(?:FROM|JOIN|INTO|UPDATE|DELETE\s+FROM)\s+([a-z][a-z0-9_]*)", re.I)
    for path in ROOT.glob("server/ruoyi-modules/jshpos-*/src/main/**/*Mapper.*"):
        source = path.read_text(encoding="utf-8", errors="replace")
        owner = path.parts[path.parts.index("ruoyi-modules") + 1].removeprefix("jshpos-")
        for table in table_pattern.findall(source):
            table_owner = next((candidate for candidate, prefixes in ownership.items()
                                if any(table.lower().startswith(prefix) for prefix in prefixes)), None)
            if table_owner and table_owner != owner:
                fail(f"cross-owner Mapper reference: {path.relative_to(ROOT)} -> {table}")


def validate_e2e_materials(statuses: tuple[str, ...]) -> None:
    if statuses[-1] not in {"IN_PROGRESS", "VERIFIED"}:
        return
    required = (
        "contracts/t2/gate6g/test-vectors/internal-v1-core-candidate-v1.json",
        "scripts/run_t2_gate6g_internal_v1_candidate.py",
        "scripts/build_t2_gate6g_runtime_stack_smoke.py",
        "docs/t2-gate6g/11_T2_E2E003设计准入与独立验证.md",
        "docs/t2-gate6g/12_T2_E2E003可重复运行手册.md",
        "docs/t2-gate6g/13_Gate6G缺陷账与性能基线.md",
        "docs/t2-gate6g/14_Gate6G证据索引.md",
        "docs/t2-gate6g/15_T2_Gate6G_SprintS17周门禁报告.md",
        "docs/t2-gate6g/16_Gate6H下一步操作指令.md",
        "pos-flutter/lib/app/pos_application_bootstrap.dart",
        "pos-flutter/lib/infrastructure/runtime/session_bound_pos_runtime.dart",
        "pos-flutter/test/gate6g/formal_pos_runtime_e2e_test.dart",
    )
    missing = [item for item in required if not (ROOT / item).is_file()]
    if missing:
        fail(f"E2E-003 admitted materials missing: {missing}")
    vector = load_json(ROOT / required[0])
    if (vector.get("requirementId") != "T2-E2E-003" or
            vector.get("evidenceLevel") != "INTERNAL_V1_CORE_CANDIDATE" or
            any(value != 0 for value in vector.get("externalExecution", {}).values()) or
            vector.get("commercialClaimAllowed") is not False):
        fail("E2E-003 vector identity or external evidence boundary drift")
    workflow = (ROOT / ".github/workflows/t2-gate6g.yml").read_text(encoding="utf-8")
    for token in ("runtime-stack-smoke:", "internal-v1-core-candidate:",
                  "run_t2_gate6g_internal_v1_candidate.py", "build_t2_gate6g_evidence.py"):
        if token not in workflow:
            fail(f"Gate6G workflow missing E2E token: {token}")


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--output", type=pathlib.Path)
    args = parser.parse_args()
    with RTM.open(encoding="utf-8-sig", newline="") as handle:
        rows = {row["requirement_id"]: row for row in csv.DictReader(handle)}
    document = load_json(ADMISSION)
    statuses = validate_serial(document, rows)
    validate_preserved(document, rows)
    validate_branch()
    validate_forbidden_boundaries()
    validate_e2e_materials(statuses)
    result = {
        "gate": "T2-GATE6G-S17",
        "baseline": BASELINE,
        "branch": BRANCH,
        "statuses": dict(zip(SEQUENCE, statuses)),
        "preservedStates": PRESERVED,
        "externalExecution": document["externalExecution"],
        "result": "PASS",
    }
    if args.output:
        output = args.output if args.output.is_absolute() else ROOT / args.output
        output.parent.mkdir(parents=True, exist_ok=True)
        output.write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print("T2 Gate 6G governance OK: " + ", ".join(f"{key}={value}" for key, value in result["statuses"].items()))


if __name__ == "__main__":
    main()
