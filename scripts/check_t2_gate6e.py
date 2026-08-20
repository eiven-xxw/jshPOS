#!/usr/bin/env python3
"""Gate 6E 串行准入、后台边界、外部零执行和证据向量治理门禁。"""
from __future__ import annotations

import argparse
import csv
import json
import pathlib
import re
import subprocess


ROOT = pathlib.Path(__file__).resolve().parents[1]
BASELINE = "281e98a6b286f1343a012ed289cecb195858dcc7"
ADMISSION = ROOT / "contracts/t2/gate6e/gate6e-admission.json"
ADM_VECTORS = ROOT / "contracts/t2/gate6e/test-vectors/admin-operations-v2.json"
POS_VECTORS = ROOT / "contracts/t2/gate6e/test-vectors/pos-return-ui-v1.json"
RTM = ROOT / "docs/governance/rtm.csv"
ALLOWED_STATUSES = {
    ("IN_PROGRESS", "DRAFT", "DRAFT"),
    ("VERIFIED", "DRAFT", "DRAFT"),
    ("VERIFIED", "IN_PROGRESS", "DRAFT"),
    ("VERIFIED", "VERIFIED", "DRAFT"),
    ("VERIFIED", "VERIFIED", "IN_PROGRESS"),
    ("VERIFIED", "VERIFIED", "VERIFIED"),
}


def fail(message: str) -> None:
    raise SystemExit(f"T2-GATE6E ERROR: {message}")


def load_json(path: pathlib.Path) -> dict:
    try:
        return json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as exception:
        fail(f"invalid JSON {path.relative_to(ROOT)}: {exception}")


def git(*args: str) -> str:
    completed = subprocess.run(["git", *args], cwd=ROOT, check=False, capture_output=True, text=True)
    if completed.returncode:
        fail(f"git {' '.join(args)} failed: {completed.stderr.strip()}")
    return completed.stdout.strip()


def validate_serial(admission: dict, rows: dict[str, dict[str, str]]) -> tuple[str, str, str]:
    if admission.get("baselineCommit") != BASELINE:
        fail("baseline commit drift")
    if admission.get("branch") != "t2/gate6e-sprint16-internal-alpha-candidate":
        fail("branch contract drift")
    requirements = admission.get("sequentialRequirements", [])
    ids = [item.get("id") for item in requirements]
    if ids != ["T2-ADM-002", "T2-POS-009", "T2-E2E-002"]:
        fail("serial requirement order drift")
    statuses = tuple(str(item.get("status")) for item in requirements)
    if statuses not in ALLOWED_STATUSES:
        fail(f"illegal serial status tuple {statuses}")
    for item in requirements:
        requirement_id = item["id"]
        if rows.get(requirement_id, {}).get("status") != item["status"]:
            fail(f"RTM/admission status mismatch for {requirement_id}")
    return statuses  # type: ignore[return-value]


def validate_preserved(admission: dict, rows: dict[str, dict[str, str]]) -> None:
    required = {
        "T2-PAY-002": "BLOCKED", "T2-HWD-001": "BLOCKED", "T2-PAR-001": "BLOCKED",
        "T2-UAT-001": "DRAFT", "T2-REL-001": "DRAFT", "T2-JSH-001": "DEFERRED", "T2-LIC-001": "DEFERRED",
    }
    if admission.get("preservedStates") != required:
        fail("preserved state contract drift")
    for requirement_id, expected in required.items():
        if rows.get(requirement_id, {}).get("status") != expected:
            fail(f"preserved RTM status changed: {requirement_id}")
    external = admission.get("externalExecution", {})
    for field in ("providerNetworkCalls", "realDeviceCommands", "onsitePilots", "fullAlphaRuns"):
        if external.get(field) != 0:
            fail(f"external execution must remain zero: {field}")
    if external.get("commercialClaimAllowed") is not False:
        fail("commercial claim must remain forbidden")


def validate_sources(statuses: tuple[str, str, str]) -> None:
    required_files = [
        "admin-web/src/api/operations/contract.ts",
        "admin-web/src/api/operations/index.ts",
        "admin-web/src/views/operations/advanced/index.vue",
        "admin-web/src/views/operations/useControlledOperation.ts",
        "server/ruoyi-modules/jshpos-foundation/src/main/resources/db/migration/V202608200042__gate6e_advanced_operations_menu.sql",
    ]
    for relative in required_files:
        if not (ROOT / relative).is_file():
            fail(f"missing ADM-002 runtime file: {relative}")
    runtime = "\n".join((ROOT / path).read_text(encoding="utf-8") for path in required_files[:4])
    for token in ("trustedOperationsPayload", "buildOperationConfirmation", "createSingleFlight", "currentVersion", "idempotencyKey"):
        if token not in runtime:
            fail(f"missing controlled UI token: {token}")
    forbidden = [r"https?://", r"axios\.create", r"fetch\(", r"MethodChannel", r"\bMapper\b", r"\bSQLite\b"]
    for expression in forbidden:
        if re.search(expression, runtime, re.IGNORECASE):
            fail(f"forbidden UI boundary token: {expression}")
    migration = "server/ruoyi-modules/jshpos-foundation/src/main/resources/db/migration/V202608200042__gate6e_advanced_operations_menu.sql"
    changed = git("diff", "--name-status", BASELINE, "--", ":(glob)server/ruoyi-modules/*/src/main/resources/db/migration/*.sql")
    if not changed and (ROOT / migration).is_file() and git("status", "--short", "--untracked-files=all", "--", migration).startswith("?? "):
        changed = f"A\t{migration}"
    if changed.strip() != f"A\t{migration}":
        fail(f"published migration drift or unapproved migration set: {changed!r}")
    if statuses[1] == "DRAFT":
        flutter_changes = git("diff", "--name-only", BASELINE, "--", "pos-flutter/lib")
        if flutter_changes:
            fail("POS-009 is DRAFT but Flutter runtime changed")
    else:
        pos_files = [
            "pos-flutter/lib/features/return_refund/domain/pos_return_models.dart",
            "pos-flutter/lib/features/return_refund/application/pos_return_application_service.dart",
            "pos-flutter/lib/features/return_refund/application/pos_return_controller.dart",
            "pos-flutter/lib/features/return_refund/infrastructure/locked_pos_return_application_service.dart",
            "pos-flutter/lib/features/return_refund/presentation/pos_return_page.dart",
        ]
        for relative in pos_files:
            if not (ROOT / relative).is_file():
                fail(f"missing POS-009 runtime file: {relative}")
        pos_runtime = "\n".join((ROOT / path).read_text(encoding="utf-8") for path in pos_files)
        for token in ("PosReturnApplicationService", "refreshReturnStatus", "resultUnknown",
                      "PosPermission.returnCreate", "LockedPosReturnApplicationService"):
            if token not in pos_runtime:
                fail(f"missing POS-009 boundary token: {token}")
        for expression in (r"package:http", r"dart:io", r"sqflite", r"MethodChannel",
                           r"pos_local_database", r"\bMapper\b", r"https?://"):
            if re.search(expression, pos_runtime, re.IGNORECASE):
                fail(f"forbidden POS-009 runtime boundary token: {expression}")
    if statuses[2] == "DRAFT":
        for relative in ("scripts/run_t2_gate6e_internal_alpha.py",
                         "pos-flutter/test/gate6e/internal_alpha_candidate_test.dart"):
            if (ROOT / relative).exists():
                fail(f"E2E-002 is DRAFT but candidate runtime exists: {relative}")


def validate_vectors(document: dict) -> None:
    if document.get("requirementId") != "T2-ADM-002" or document.get("evidenceLevel") != "COMPONENT":
        fail("ADM-002 vector identity/evidence drift")
    if set(document.get("tenants", [])) != {"TENANT_A", "TENANT_B"}:
        fail("vectors must use exactly two fictional tenants")
    if set(document.get("industries", [])) != {"CONVENIENCE", "SNACK_DISCOUNT", "COMMUNITY_SUPERMARKET"}:
        fail("vectors must cover three admitted industries")
    cases = document.get("cases", [])
    if len(cases) < 40 or len({item.get("id") for item in cases}) != len(cases):
        fail("at least forty uniquely identified ADM-002 cases are required")
    owners = {item.get("owner") for item in cases}
    for owner in ("Inventory", "Costing", "Procurement", "Transfer", "Promotion", "Member", "Reporting", "Terminal", "Release", "Security"):
        if owner not in owners:
            fail(f"missing test-vector owner: {owner}")
    external = document.get("externalExecution", {})
    if any(external.get(field) != 0 for field in ("providerNetworkCalls", "realDeviceCommands", "onsitePilots", "fullAlphaRuns")):
        fail("vector external execution must remain zero")


def validate_pos_vectors(document: dict) -> None:
    if document.get("requirementId") != "T2-POS-009" or document.get("evidenceLevel") != "WIDGET":
        fail("POS-009 vector identity/evidence drift")
    if set(document.get("tenants", [])) != {"TENANT_A", "TENANT_B"}:
        fail("POS vectors must use exactly two fictional tenants")
    if set(document.get("industries", [])) != {"CONVENIENCE", "SNACK_DISCOUNT", "COMMUNITY_SUPERMARKET"}:
        fail("POS vectors must cover three admitted industries")
    cases = document.get("cases", [])
    if len(cases) < 40 or len({item.get("id") for item in cases}) != len(cases):
        fail("at least forty uniquely identified POS-009 cases are required")
    external = document.get("externalExecution", {})
    if any(external.get(field) != 0 for field in
           ("providerNetworkCalls", "realDeviceCommands", "onsitePilots", "fullAlphaRuns")):
        fail("POS vector external execution must remain zero")


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--output", type=pathlib.Path)
    args = parser.parse_args()
    with RTM.open(encoding="utf-8-sig", newline="") as handle:
        rows = {row["requirement_id"]: row for row in csv.DictReader(handle)}
    admission = load_json(ADMISSION)
    statuses = validate_serial(admission, rows)
    validate_preserved(admission, rows)
    validate_sources(statuses)
    adm_vectors = load_json(ADM_VECTORS)
    validate_vectors(adm_vectors)
    pos_vectors = load_json(POS_VECTORS)
    if statuses[1] != "DRAFT":
        validate_pos_vectors(pos_vectors)
    result = {
        "gate": "T2-GATE6E-S16",
        "baseline": BASELINE,
        "statuses": dict(zip(("T2-ADM-002", "T2-POS-009", "T2-E2E-002"), statuses)),
        "vectorCount": len(adm_vectors["cases"]) + len(pos_vectors["cases"]),
        "vectorCounts": {
            "T2-ADM-002": len(adm_vectors["cases"]),
            "T2-POS-009": len(pos_vectors["cases"]),
        },
        "externalExecution": admission["externalExecution"],
        "result": "PASS",
    }
    if args.output:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(result, ensure_ascii=False))


if __name__ == "__main__":
    main()
