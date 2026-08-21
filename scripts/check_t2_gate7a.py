#!/usr/bin/env python3
"""Gate 7A 商业 V1 内部业务差距静态治理门禁。"""
from __future__ import annotations

import argparse
import csv
import json
import pathlib
import re
import subprocess


ROOT = pathlib.Path(__file__).resolve().parents[1]
BASELINE = "d66c252587561428def95058d67bc830e391a9ab"
BRANCH = "t2/gate7a-sprint19-v1-business-gap"
GATE = "T2-GATE7A-SPRINT-S19-V1-BUSINESS-GAP-AUDIT"
CONTRACT_DIR = ROOT / "contracts/t2/gate7a"
RTM_PATH = ROOT / "docs/governance/rtm.csv"

EXPECTED_GAPS = [
    "T2-POS-010", "T2-POS-011", "T2-ORD-004", "T2-EXG-001", "T2-PAY-004",
    "T2-PRD-005", "T2-LBL-001", "T2-RPL-001", "T2-DMT-001", "T2-ONB-001",
    "T2-LOT-001", "T2-CLS-001", "T2-EXC-001", "T2-MEM-003", "T2-SAA-001",
    "T2-SUB-001", "T2-SVC-001", "T2-E2E-004",
]
EXPECTED_GATE_ORDER = {
    "Gate7B": ["T2-POS-010", "T2-POS-011", "T2-ORD-004", "T2-EXG-001", "T2-PAY-004"],
    "Gate7C": ["T2-PRD-005", "T2-LBL-001", "T2-RPL-001", "T2-DMT-001", "T2-ONB-001", "T2-LOT-001"],
    "Gate7D": ["T2-CLS-001", "T2-EXC-001", "T2-MEM-003", "T2-SAA-001", "T2-SUB-001", "T2-SVC-001"],
    "Gate7E": ["T2-E2E-004"],
}
PRESERVED_STATES = {
    "T2-PAY-002": "BLOCKED", "T2-HWD-001": "BLOCKED", "T2-PRN-001": "BLOCKED",
    "T2-PAR-001": "BLOCKED", "T2-UAT-001": "DRAFT", "T2-REL-001": "DRAFT",
    "T2-JSH-001": "DEFERRED", "T2-LIC-001": "DEFERRED",
}


def fail(condition: bool, message: str) -> None:
    if not condition:
        raise SystemExit(f"T2-GATE7A ERROR: {message}")


def git(*args: str) -> str:
    completed = subprocess.run(
        ["git", "-c", "core.quotepath=false", *args], cwd=ROOT,
        check=False, capture_output=True, text=True, encoding="utf-8",
    )
    fail(completed.returncode == 0, f"git {' '.join(args)} failed: {completed.stderr.strip()}")
    return completed.stdout.strip()


def read_json(path: pathlib.Path) -> dict:
    try:
        return json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as exception:
        raise SystemExit(f"T2-GATE7A ERROR: invalid {path.relative_to(ROOT)}: {exception}")


def read_rtm() -> dict[str, dict[str, str]]:
    with RTM_PATH.open(encoding="utf-8", newline="") as stream:
        rows = list(csv.DictReader(stream))
    fail(rows and all(row.get("requirement_id") for row in rows), "RTM is empty or malformed")
    ids = [row["requirement_id"] for row in rows]
    fail(len(ids) == len(set(ids)), "RTM contains duplicate Requirement IDs")
    return {row["requirement_id"]: row for row in rows}


def validate_contract(rows: dict[str, dict[str, str]]) -> dict:
    contract = read_json(CONTRACT_DIR / "gate7a-audit.json")
    fail(contract.get("gate") == GATE, "gate identity drift")
    fail(contract.get("baselineCommit") == BASELINE, "baseline commit drift")
    fail(contract.get("branch") == BRANCH, "branch contract drift")
    fail(contract.get("decision") == "CONDITIONAL_GO_AUDIT_ONLY", "decision drift")
    fail(contract.get("evidenceLevel") == "STATIC_GOVERNANCE_AND_REPOSITORY_AUDIT",
         "evidence ceiling drift")
    accepted = contract.get("acceptedRequirementAudit", {})
    fail(accepted.get("count") == 64 and accepted.get("hardFailures") == 0 and
         accepted.get("ownerModuleCount") == 15, "accepted capability audit contract drift")
    gaps = contract.get("gapRegister", {})
    fail(gaps.get("confirmedGapCount") == len(EXPECTED_GAPS) and gaps.get("status") == "DRAFT" and
         gaps.get("runtimeAllowed") is False, "gap register contract drift")
    fail(contract.get("preservedStates") == PRESERVED_STATES, "preserved states contract drift")
    for requirement_id, expected_status in PRESERVED_STATES.items():
        fail(rows.get(requirement_id, {}).get("status") == expected_status,
             f"preserved RTM status drift: {requirement_id}")
    external = contract.get("externalExecution", {})
    for field in (
        "providerNetworkCalls", "realFundsTransactions", "realDeviceCommands", "realPeripheralCommands",
        "partnerContacts", "onsitePilots", "fullAlphaRuns", "productionDeployments",
    ):
        fail(external.get(field) == 0, f"external execution must remain zero: {field}")
    fail(external.get("commercialClaimAllowed") is False, "commercial claim must remain forbidden")
    candidate = contract.get("candidateEvidence", {})
    fail(candidate.get("commit") == "526ff3c1bd45e6d27695186e918065a15dd7034d" and
         candidate.get("workflowRun") == 32484421509 and candidate.get("workflowConclusion") == "success" and
         candidate.get("evidenceArtifactId") == 9447302706 and
         candidate.get("evidenceArtifactSha256") ==
         "a53fd230990710fad8887b0ae6789f9e26fe4c031ab81faa298a33ac5e82d5be",
         "candidate CI evidence drift")
    next_gate = contract.get("nextGate", {})
    fail(next_gate.get("automaticStartAllowed") is False and
         next_gate.get("firstBatch") == EXPECTED_GATE_ORDER["Gate7B"][:3], "next gate boundary drift")
    return contract


def validate_gap_register(rows: dict[str, dict[str, str]]) -> list[dict[str, str]]:
    path = CONTRACT_DIR / "v1-gap-register.csv"
    with path.open(encoding="utf-8", newline="") as stream:
        gaps = list(csv.DictReader(stream))
    ids = [item.get("requirement_id", "") for item in gaps]
    fail(ids == EXPECTED_GAPS, "gap register order or ID set drift")
    fail(len(ids) == len(set(ids)), "gap register contains duplicate IDs")
    for gate, expected in EXPECTED_GATE_ORDER.items():
        actual = [item["requirement_id"] for item in gaps if item.get("gate") == gate]
        fail(actual == expected, f"{gate} serial order drift")
        sequences = [int(item["sequence"]) for item in gaps if item.get("gate") == gate]
        fail(sequences == list(range(1, len(expected) + 1)), f"{gate} sequence is not contiguous")
    for item in gaps:
        requirement_id = item["requirement_id"]
        fail(item.get("status") == "DRAFT", f"gap must remain DRAFT: {requirement_id}")
        fail(item.get("owner") and item.get("entry_condition") and item.get("current_repository_evidence"),
             f"gap metadata incomplete: {requirement_id}")
        fail(rows.get(requirement_id, {}).get("status") == "DRAFT", f"RTM gap is not DRAFT: {requirement_id}")
    for requirement_id in ("T2-EXG-001", "T2-MEM-003", "T2-SAA-001", "T2-SUB-001", "T2-SVC-001"):
        row = next(item for item in gaps if item["requirement_id"] == requirement_id)
        fail(row.get("scope_classification") == "CR_REQUIRED", f"CR boundary lost: {requirement_id}")
    fail(next(item for item in gaps if item["requirement_id"] == "T2-LOT-001").get("scope_classification") ==
         "CONDITIONAL_INDUSTRY", "community supermarket conditional boundary lost")
    return gaps


def validate_existing_capability_audit(path: pathlib.Path, rows: dict[str, dict[str, str]]) -> dict:
    audit = read_json(path)
    accepted_count = sum(1 for requirement_id, row in rows.items()
                         if requirement_id.startswith("T2-") and row.get("status") == "ACCEPTED")
    fail(accepted_count == 64, f"expected 64 accepted T2 requirements but found {accepted_count}")
    fail(audit.get("acceptedRequirementCount") == 64, "core audit accepted count drift")
    fail(audit.get("moduleTotals", {}).get("modules") == 15, "core audit owner/module count drift")
    fail(audit.get("hardFailures") == [] and audit.get("result") == "PASS", "core audit is not green")
    fail(audit.get("runtimeAssembly", {}).get("missing") == [], "formal runtime assembly is incomplete")
    return audit


def validate_docs_and_scope() -> list[str]:
    required_docs = [
        "docs/t2-gate7a/README.md",
        "docs/t2-gate7a/01_商业V1现有业务能力清单.md",
        "docs/t2-gate7a/02_商业V1内部业务功能差距报告.md",
        "docs/t2-gate7a/03_Gate7B至7E依赖图与逐步验收计划.md",
        "docs/t2-gate7a/04_页面API_Owner数据表测试覆盖矩阵.md",
        "docs/t2-gate7a/05_V1非目标与CR候选清单.md",
        "docs/t2-gate7a/06_T2_Gate7A_SprintS19启动评审报告.md",
        "docs/t2-gate7a/07_Gate7B第一批正式业务开发操作指令.md",
        "docs/t2-gate7a/08_Gate7A证据索引.md",
        "docs/adr/ADR-042-gate7a-v1-business-gap-audit.md",
        "docs/governance/CR-T2G7A-001_v1内部业务差距审计与候选准入.md",
    ]
    fail(all((ROOT / item).is_file() for item in required_docs), "Gate 7A deliverable set incomplete")
    fail(git("merge-base", "--is-ancestor", BASELINE, "HEAD") == "", "Gate 6I seal is not an ancestor")
    changed = sorted(filter(None, git("diff", "--name-only", BASELINE).splitlines()))
    allowed_exact = {
        "AGENTS.md", ".github/workflows/t2-gate7a.yml", "docs/adr/README.md",
        "docs/governance/change-log.md", "docs/governance/rtm.csv",
        "docs/governance/CR-T2G7A-001_v1内部业务差距审计与候选准入.md",
        "scripts/check_t2_gate7a.py", "scripts/build_t2_gate7a_evidence.py",
    }
    allowed_prefixes = ("contracts/t2/gate7a/", "docs/t2-gate7a/", "docs/adr/ADR-042-")
    illegal = [item for item in changed if item not in allowed_exact and not item.startswith(allowed_prefixes)]
    fail(not illegal, f"runtime or out-of-scope files changed: {illegal}")
    runtime_roots = ("server/", "admin-web/", "pos-flutter/", "packages/", "infrastructure/", "infra/")
    fail(not [item for item in changed if item.startswith(runtime_roots)], "runtime/dependency surface changed")
    sensitive_name = re.compile(r"(^|/)(\.env($|\.)|.*\.(pem|p12|pfx|jks|keystore|key)$)", re.IGNORECASE)
    fail(not [item for item in changed if sensitive_name.search(item)], "sensitive file name found")
    secret_patterns = [
        re.compile(r"-----BEGIN [A-Z ]*PRIVATE KEY-----"),
        re.compile(r"AKIA[0-9A-Z]{16}"),
        re.compile(r"(?i)(password|secret|token)\s*[:=]\s*['\"][^'\"]{8,}['\"]"),
    ]
    for item in changed:
        path = ROOT / item
        if path.is_file():
            content = path.read_text(encoding="utf-8", errors="ignore")
            fail(not any(pattern.search(content) for pattern in secret_patterns), f"possible secret in {item}")
    return changed


def validate_repository_findings() -> None:
    schema = (ROOT / "pos-flutter/lib/infrastructure/local_database/gate2_schema.dart").read_text(encoding="utf-8")
    checkout = (ROOT / "pos-flutter/lib/features/checkout/application/checkout_local_service.dart").read_text(
        encoding="utf-8")
    catalog = (ROOT / "pos-flutter/lib/infrastructure/local_database/gate6g_catalog_schema.dart").read_text(
        encoding="utf-8")
    fail("movement_type TEXT NOT NULL CHECK(movement_type='SALE_RECEIPT')" in schema,
         "cash movement gap evidence no longer matches repository")
    fail("CREATE TABLE local_print_job" in schema and "UNIQUE(tenant_id,order_id)" in schema,
         "print task gap evidence no longer matches repository")
    fail("INSERT INTO local_print_job" in checkout and "PENDING" in checkout,
         "formal checkout print task evidence missing")
    fail("product_type IN ('STANDARD','WEIGHT','COUNT')" in catalog,
         "existing weighted product evidence missing")


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--core-audit", default="artifacts/t2/gate7a/reused-gate6g-core-audit.json")
    parser.add_argument("--output")
    args = parser.parse_args()
    rows = read_rtm()
    contract = validate_contract(rows)
    gaps = validate_gap_register(rows)
    audit = validate_existing_capability_audit(ROOT / args.core_audit, rows)
    validate_repository_findings()
    changed = validate_docs_and_scope()
    result = {
        "schemaVersion": "1.0",
        "gate": GATE,
        "status": "PASS",
        "evidenceLevel": contract["evidenceLevel"],
        "baselineCommit": BASELINE,
        "acceptedRequirementCount": audit["acceptedRequirementCount"],
        "ownerModuleCount": audit["moduleTotals"]["modules"],
        "hardFailureCount": len(audit["hardFailures"]),
        "confirmedGapCount": len(gaps),
        "gapStatuses": {item["requirement_id"]: item["status"] for item in gaps},
        "gateOrder": EXPECTED_GATE_ORDER,
        "preservedStates": PRESERVED_STATES,
        "externalExecution": contract["externalExecution"],
        "runtimeFilesChanged": 0,
        "changedFiles": changed,
        "overallDecision": "AUDIT_COMPLETE_GATE7B_AWAITING_CONFIRMATION",
    }
    if args.output:
        target = ROOT / args.output
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({key: value for key, value in result.items() if key not in {"gapStatuses", "changedFiles"}},
                     ensure_ascii=False))


if __name__ == "__main__":
    main()
