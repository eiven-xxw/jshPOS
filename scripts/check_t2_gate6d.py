#!/usr/bin/env python3
"""Gate 6D 串行准入、UI 边界、状态守恒和外部执行零值门禁。"""
from __future__ import annotations

import argparse
import csv
import json
import pathlib
import re
import subprocess


ROOT = pathlib.Path(__file__).resolve().parents[1]
BASELINE = "aece518b9ef1057462e835ad7f98ce1aa2bffbf3"
ORDER = ("T2-POS-007", "T2-POS-008", "T2-ADM-001", "T2-E2E-001")
PRESERVED = {
    "T2-PAY-002": "BLOCKED",
    "T2-HWD-001": "BLOCKED",
    "T2-PAR-001": "BLOCKED",
    "T2-UAT-001": "DRAFT",
    "T2-REL-001": "DRAFT",
    "T2-JSH-001": "DEFERRED",
    "T2-LIC-001": "DEFERRED",
}
RANK = {"DRAFT": 0, "READY": 1, "IN_PROGRESS": 2, "VERIFIED": 3, "ACCEPTED": 4}


def require(condition: bool, message: str) -> None:
    if not condition:
        raise SystemExit(f"GATE6D ERROR: {message}")


def source_text(path: pathlib.Path) -> str:
    return "\n".join(
        item.read_text(encoding="utf-8", errors="ignore")
        for item in path.rglob("*")
        if item.is_file()
    )


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--output", required=True)
    parser.add_argument("--baseline", default=BASELINE)
    args = parser.parse_args()

    with (ROOT / "docs/governance/rtm.csv").open(encoding="utf-8-sig", newline="") as stream:
        rtm = {row["requirement_id"]: row for row in csv.DictReader(stream)}
    admission = json.loads(
        (ROOT / "contracts/t2/gate6d/gate6d-admission.json").read_text(encoding="utf-8")
    )
    require(admission["baselineCommit"] == args.baseline, "baseline commit mismatch")
    require(admission["branch"] == "t2/gate6d-sprint15-internal-productization", "branch contract mismatch")
    contract = {item["id"]: item for item in admission["sequentialRequirements"]}
    require(tuple(item["id"] for item in admission["sequentialRequirements"]) == ORDER, "requirement order changed")
    for requirement_id in ORDER:
        require(requirement_id in rtm, f"missing RTM row {requirement_id}")
        require(rtm[requirement_id]["status"] == contract[requirement_id]["status"], f"RTM/admission mismatch {requirement_id}")
        require(rtm[requirement_id]["status"] in RANK, f"invalid lifecycle state {requirement_id}")
    for index, requirement_id in enumerate(ORDER[1:], start=1):
        if RANK[contract[requirement_id]["status"]] >= RANK["IN_PROGRESS"]:
            require(
                all(RANK[contract[previous]["status"]] >= RANK["VERIFIED"] for previous in ORDER[:index]),
                f"{requirement_id} started before previous independent verification",
            )
    for requirement_id, status in PRESERVED.items():
        require(rtm[requirement_id]["status"] == status, f"preserved RTM state changed: {requirement_id}")
        require(admission["preservedStates"][requirement_id] == status, f"admission state changed: {requirement_id}")
    require(all(value == 0 for key, value in admission["externalExecution"].items() if key != "commercialClaimAllowed"), "external execution must remain zero")
    require(not admission["externalExecution"]["commercialClaimAllowed"], "commercial claim must remain false")

    vectors = json.loads(
        (ROOT / "contracts/t2/gate6d/test-vectors/pos-session-vectors-v1.json").read_text(encoding="utf-8")
    )
    require(vectors["requirementId"] == "T2-POS-007", "session vectors requirement mismatch")
    require(len(vectors["vectors"]) >= 11, "session fixed vectors incomplete")
    require(len({item["id"] for item in vectors["vectors"]}) == len(vectors["vectors"]), "duplicate session vector id")
    require(all(value == 0 for value in vectors["externalExecution"].values()), "vector external execution must remain zero")

    presentation = "\n".join(
        (
            source_text(ROOT / "pos-flutter/lib/features/session/presentation"),
            source_text(ROOT / "pos-flutter/lib/features/sale/presentation"),
        )
    ).lower()
    for forbidden in ("package:sqlite3", "methodchannel", "sqflite", "database.execute", "tenantid:"):
        require(forbidden not in presentation, f"Flutter UI bypass token found: {forbidden}")
    session_source = source_text(ROOT / "pos-flutter/lib/features/session")
    require("PosDeviceGateway" in session_source, "device gateway boundary missing")
    require("LockedPosSessionRepository" in session_source, "fail-closed repository missing")
    require("///" in session_source and re.search(r"[\u4e00-\u9fff]", session_source) is not None, "core Chinese comments missing")
    lower = session_source.lower()
    for forbidden in ("provider_url", "production_key", "okhttpclient", "resttemplate", "webclient.builder"):
        require(forbidden not in lower, f"provider/network runtime token found: {forbidden}")

    sale_source = source_text(ROOT / "pos-flutter/lib/features/sale")
    if RANK[rtm["T2-POS-008"]["status"]] >= RANK["IN_PROGRESS"]:
        require("PosSaleApplicationService" in sale_source, "sale application boundary missing")
        require("PosSaleController" in sale_source, "sale page controller missing")
        require("LockedPosSaleApplicationService" in sale_source, "sale fail-closed adapter missing")
        require("///" in sale_source and re.search(r"[\u4e00-\u9fff]", sale_source) is not None, "sale core Chinese comments missing")
        sale_vectors = json.loads(
            (ROOT / "contracts/t2/gate6d/test-vectors/pos-sale-vectors-v1.json").read_text(encoding="utf-8")
        )
        require(sale_vectors["requirementId"] == "T2-POS-008", "sale vectors requirement mismatch")
        require(len(sale_vectors["vectors"]) >= 16, "sale fixed vectors incomplete")
        require(len({item["id"] for item in sale_vectors["vectors"]}) == len(sale_vectors["vectors"]), "duplicate sale vector id")
        require(all(value == 0 for value in sale_vectors["externalExecution"].values()), "sale vector external execution must remain zero")
        output_sale_vector_count = len(sale_vectors["vectors"])
    else:
        output_sale_vector_count = 0

    admin_source = "\n".join(
        (ROOT / relative).read_text(encoding="utf-8")
        for relative in (
            "admin-web/src/views/index.vue",
            "admin-web/src/views/foundation/index.vue",
            "admin-web/src/views/catalog/index.vue",
            "admin-web/src/views/operations/model.ts",
        )
    )
    if RANK[rtm["T2-ADM-001"]["status"]] >= RANK["IN_PROGRESS"]:
        for required in (
            "经营工作台",
            "normalizeProductUnits",
            "replaceStaffScopes",
            "createBrand",
            "publishPriceBook",
            "rollbackImport",
        ):
            require(required in admin_source, f"admin formal UI token missing: {required}")
        require("RuoYi-Cloud-Plus" not in (ROOT / "admin-web/src/views/index.vue").read_text(encoding="utf-8"), "framework demo home remains")
        for forbidden in ("tenantId:", "tenant_id:", "fetch(", "axios.create("):
            require(forbidden not in admin_source, f"admin UI bypass token found: {forbidden}")
        admin_vectors = json.loads(
            (ROOT / "contracts/t2/gate6d/test-vectors/admin-operations-vectors-v1.json").read_text(encoding="utf-8")
        )
        require(admin_vectors["requirementId"] == "T2-ADM-001", "admin vectors requirement mismatch")
        require(len(admin_vectors["vectors"]) >= 18, "admin fixed vectors incomplete")
        require(len({item["id"] for item in admin_vectors["vectors"]}) == len(admin_vectors["vectors"]), "duplicate admin vector id")
        require(all(value == 0 for value in admin_vectors["externalExecution"].values()), "admin vector external execution must remain zero")
        output_admin_vector_count = len(admin_vectors["vectors"])
    else:
        output_admin_vector_count = 0

    if RANK[rtm["T2-E2E-001"]["status"]] >= RANK["IN_PROGRESS"]:
        e2e_vectors = json.loads(
            (ROOT / "contracts/t2/gate6d/test-vectors/internal-cash-e2e-v1.json").read_text(encoding="utf-8")
        )
        require(e2e_vectors["requirementId"] == "T2-E2E-001", "E2E vectors requirement mismatch")
        require(e2e_vectors["evidenceLevel"] == "SYNTHETIC_E2E", "E2E evidence level changed")
        require(len(e2e_vectors["journeys"]) == 6, "E2E two-tenant/three-industry journeys incomplete")
        require({item["tenantId"] for item in e2e_vectors["journeys"]} == {"TENANT_A", "TENANT_B"}, "E2E tenant set mismatch")
        require(
            all(value == 0 for value in e2e_vectors["externalExecution"].values()),
            "E2E external execution must remain zero",
        )
        require((ROOT / "scripts/run_t2_gate6d_internal_e2e.py").is_file(), "E2E executable verifier missing")
        output_e2e_journey_count = len(e2e_vectors["journeys"])
    else:
        output_e2e_journey_count = 0

    changed = subprocess.check_output(
        ["git", "diff", "--name-only", args.baseline, "HEAD"], cwd=ROOT, text=True
    ).splitlines()
    migration_changes = [item for item in changed if "/db/migration/" in item.replace("\\", "/")]
    require(not migration_changes, f"Gate 6D must not modify published migrations: {migration_changes}")

    output = {
        "schemaVersion": "1.0",
        "gate": "T2-GATE6D-S15",
        "status": "PASS",
        "requirements": {item: rtm[item]["status"] for item in ORDER},
        "preservedStates": PRESERVED,
        "sessionVectorCount": len(vectors["vectors"]),
        "saleVectorCount": output_sale_vector_count,
        "adminVectorCount": output_admin_vector_count,
        "syntheticE2EJourneyCount": output_e2e_journey_count,
        "changedFiles": len(changed),
        "providerNetworkCalls": 0,
        "realDeviceCommands": 0,
        "onsitePilots": 0,
        "fullAlphaRuns": 0,
        "commercialClaimAllowed": False,
    }
    target = ROOT / args.output
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_text(json.dumps(output, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(output, ensure_ascii=False))


if __name__ == "__main__":
    main()
