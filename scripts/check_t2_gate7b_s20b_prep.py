#!/usr/bin/env python3
"""Gate 7B S20-B 第二批准入准备专用治理、仓库事实与范围门禁。"""
from __future__ import annotations

import argparse
import csv
import json
import pathlib
import re
import subprocess


ROOT = pathlib.Path(__file__).resolve().parents[1]
BASELINE = "a9e368a2f09dcfb4565f4bc392e4f77d5c664805"
BRANCH = "t2/gate7b-sprint20b-pos-operations"
GATE = "T2-GATE7B-SPRINT-S20B-SECOND-BATCH-PREP"
CONTRACT_DIR = ROOT / "contracts/t2/gate7b-s20b"
RTM_PATH = ROOT / "docs/governance/rtm.csv"
FIRST_BATCH_ACCEPTED = ("T2-POS-010", "T2-POS-011", "T2-ORD-004")
PREP_REQUIREMENTS = ("T2-EXG-001", "T2-PAY-004")
PRESERVED_STATES = {
    "T2-PAY-002": "BLOCKED", "T2-HWD-001": "BLOCKED", "T2-PRN-001": "BLOCKED",
    "T2-PAR-001": "BLOCKED", "T2-UAT-001": "DRAFT", "T2-REL-001": "DRAFT",
    "T2-JSH-001": "DEFERRED", "T2-LIC-001": "DEFERRED",
}
ZERO_FIELDS = (
    "providerNetworkCalls", "realFundsTransactions", "realDeviceCommands", "realPeripheralCommands",
    "partnerContacts", "onsitePilots", "fullAlphaRuns", "productionDeployments",
)


def fail(condition: bool, message: str) -> None:
    if not condition:
        raise SystemExit(f"T2-GATE7B-S20B ERROR: {message}")


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
        raise SystemExit(f"T2-GATE7B-S20B ERROR: invalid {path.relative_to(ROOT)}: {exception}")


def read_rtm() -> dict[str, dict[str, str]]:
    with RTM_PATH.open(encoding="utf-8", newline="") as stream:
        rows = list(csv.DictReader(stream))
    fail(rows and all(row.get("requirement_id") for row in rows), "RTM empty or malformed")
    ids = [row["requirement_id"] for row in rows]
    fail(len(ids) == len(set(ids)), "RTM contains duplicate Requirement IDs")
    return {row["requirement_id"]: row for row in rows}


def validate_admission(rows: dict[str, dict[str, str]]) -> dict:
    contract = read_json(CONTRACT_DIR / "gate7b-s20b-prep-admission.json")
    fail(contract.get("gate") == GATE, "gate identity drift")
    fail(contract.get("baselineCommit") == BASELINE and contract.get("branch") == BRANCH,
         "baseline or branch drift")
    fail(contract.get("decision") == "CONDITIONAL_GO_PREP_ONLY", "prep-only decision drift")
    fail(contract.get("evidenceLevel") == "STATIC_DESIGN_AND_REPOSITORY_AUDIT",
         "evidence ceiling drift")
    fail(tuple(contract.get("serialOrder", [])) == PREP_REQUIREMENTS, "serial order drift")
    for requirement_id in FIRST_BATCH_ACCEPTED:
        fail(rows.get(requirement_id, {}).get("status") == "ACCEPTED",
             f"first batch sponsor acceptance missing: {requirement_id}")
        fail(contract.get("firstBatchAccepted", {}).get(requirement_id) == "ACCEPTED",
             f"first batch contract drift: {requirement_id}")
    for requirement_id in PREP_REQUIREMENTS:
        prepared = contract.get("requirements", {}).get(requirement_id, {})
        fail(rows.get(requirement_id, {}).get("status") == "DRAFT", f"prep item not DRAFT: {requirement_id}")
        fail(prepared.get("status") == "DRAFT" and prepared.get("runtimeAllowed") is False,
             f"runtime admission leaked: {requirement_id}")
    fail(contract.get("preservedStates") == PRESERVED_STATES, "preserved state contract drift")
    for requirement_id, expected in PRESERVED_STATES.items():
        fail(rows.get(requirement_id, {}).get("status") == expected,
             f"preserved RTM state drift: {requirement_id}")
    scope = contract.get("scope", {})
    for field in ("runtimeFilesChanged", "databaseMigrationsAdded", "providerAdaptersAdded",
                  "deviceAdaptersChanged"):
        fail(scope.get(field) == 0, f"scope must remain zero: {field}")
    fail(scope.get("gate7cStarted") is False, "Gate 7C must not start")
    external = contract.get("externalExecution", {})
    for field in ZERO_FIELDS:
        fail(external.get(field) == 0, f"external execution must remain zero: {field}")
    fail(external.get("commercialClaimAllowed") is False, "commercial claim boundary drift")
    candidate = contract.get("candidateEvidence")
    if candidate is not None:
        fail(re.fullmatch(r"[a-f0-9]{40}", str(candidate.get("commit", ""))) is not None,
             "candidate evidence commit invalid")
        fail(isinstance(candidate.get("workflowRun"), int) and candidate["workflowRun"] > 0,
             "candidate workflow run invalid")
        fail(candidate.get("workflowConclusion") == "success", "candidate CI not green")
        fail(isinstance(candidate.get("evidenceArtifactId"), int) and candidate["evidenceArtifactId"] > 0,
             "candidate artifact ID invalid")
        fail(re.fullmatch(r"[a-f0-9]{64}", str(candidate.get("evidenceArtifactSha256", ""))) is not None,
             "candidate artifact digest invalid")
    return contract


def validate_contracts_and_vectors() -> dict[str, int]:
    findings = read_json(CONTRACT_DIR / "repository-findings.json")
    fail(findings.get("baselineCommit") == BASELINE and findings.get("result") == "PASS_WITH_CONFIRMED_GAPS",
         "repository finding baseline/result drift")
    finding_ids = [item.get("id") for item in findings.get("findings", [])]
    fail(finding_ids == [f"S20B-REP-{index:03d}" for index in range(1, 7)], "repository finding set drift")
    rules = findings.get("hardRules", {})
    fail(all(rules.get(key) is True for key in (
        "exchangeCreatesNoMoneyOrInventoryStateMachine", "orderCompletionRequiresExactSucceededTenderSum",
        "unknownNeverCreatesReplacementCommand", "electronicRuntimeBlockedUntilPay002",
        "publishedMigrationsRemainImmutable",
    )), "hard rule drift")

    with (CONTRACT_DIR / "persistence-migration-register.csv").open(encoding="utf-8", newline="") as stream:
        migrations = list(csv.DictReader(stream))
    fail(len(migrations) == 15, "planned persistence object count drift")
    fail(len({(row["store"], row["object"]) for row in migrations}) == len(migrations),
         "duplicate planned persistence object")
    fail(sum(row["requirement_id"] == "T2-EXG-001" for row in migrations) == 5, "EXG migration plan drift")
    fail(sum(row["requirement_id"] == "T2-PAY-004" for row in migrations) == 10, "PAY migration plan drift")
    fail(all(row["migration_action"] in {"ADD_NEW_TABLE", "ALTER_FORWARD_ONLY"} for row in migrations),
         "non-forward migration planned")
    fail(all(row["sql_mode"] in {"XML", "SQL"} for row in migrations), "SQL boundary drift")

    vectors = read_json(CONTRACT_DIR / "test-vectors/s20b-fault-vectors.json")
    items = vectors.get("vectors", [])
    fail(len(items) == 17 and len({item.get("id") for item in items}) == 17, "fault vector set drift")
    fail(sum(item.get("requirementId") == "T2-EXG-001" for item in items) == 7, "EXG vector count drift")
    fail(sum(item.get("requirementId") == "T2-PAY-004" for item in items) == 10, "PAY vector count drift")
    for field in ("providerNetworkCalls", "realFundsTransactions", "realDeviceCommands"):
        fail(vectors.get("externalExecution", {}).get(field) == 0, f"vector external boundary drift: {field}")
    fail(vectors.get("externalExecution", {}).get("commercialClaimAllowed") is False,
         "vector commercial evidence boundary drift")

    exchange_schema = read_json(CONTRACT_DIR / "exchange-orchestration-events-v1.schema.json")
    tender_schema = read_json(CONTRACT_DIR / "tender-plan-events-v1.schema.json")
    fail(exchange_schema.get("additionalProperties") is False and tender_schema.get("additionalProperties") is False,
         "event schema must fail closed")
    fail("tenantId" in exchange_schema.get("required", []) and "payloadSha256" in exchange_schema.get("required", []),
         "exchange tenant/hash envelope incomplete")
    fail("tenantId" in tender_schema.get("required", []) and "planSha256" in tender_schema.get("required", []),
         "tender tenant/hash envelope incomplete")
    openapi = (CONTRACT_DIR / "openapi-pos-second-batch-v1.yaml").read_text(encoding="utf-8")
    for token in ("openapi: 3.1.0", "x-runtime-admission: DRAFT_DESIGN_ONLY", "T2-EXG-001",
                  "T2-PAY-004", "PAYMENT_EXTERNAL_BLOCKED", "Idempotency-Key"):
        fail(token in openapi, f"OpenAPI token missing: {token}")
    return {"findingCount": len(finding_ids), "migrationPlanCount": len(migrations), "faultVectorCount": len(items)}


def validate_repository_facts() -> None:
    returns_root = ROOT / "server/ruoyi-modules/jshpos-returns/src/main/java"
    return_text = "\n".join(path.read_text(encoding="utf-8", errors="ignore") for path in returns_root.rglob("*.java"))
    fail("class ReturnOrchestrationService" in return_text and "class ReturnSagaCoordinator" in return_text,
         "accepted return orchestration evidence missing")
    payment_sql = (ROOT / "server/ruoyi-modules/jshpos-payment/src/main/resources/db/migration/"
                   "V202608160009__gate3a_payment_refund_reconciliation.sql").read_text(encoding="utf-8")
    order_sql = (ROOT / "server/ruoyi-modules/jshpos-order/src/main/resources/db/migration/"
                 "V202608160005__gate2_order_shift_cash.sql").read_text(encoding="utf-8")
    fail("uk_pay_intent_order" in payment_sql, "one-intent-per-order gap evidence missing")
    fail("uk_cash_payment_order" in order_sql and "net_amount_minor = receivable_amount_minor" in order_sql,
         "full-cash-only gap evidence missing")
    sql_roots = [ROOT / item for item in ("server", "pos-flutter")]
    sql_text = "\n".join(
        path.read_text(encoding="utf-8", errors="ignore")
        for root in sql_roots if root.exists() for path in root.rglob("*.sql")
    )
    fail("CREATE TABLE ret_exchange" not in sql_text and "CREATE TABLE pay_tender_plan" not in sql_text,
         "second-batch runtime persistence already exists unexpectedly")


def validate_docs_and_scope() -> list[str]:
    required = [
        "docs/governance/CR-T2G7B-009_exchange-orchestration-scope.md",
        "docs/adr/ADR-044-gate7b-second-batch-exchange-tender-prep.md",
        "docs/t2-gate7b/09_第一批项目发起人接受记录.md",
        *[f"docs/t2-gate7b-s20b/{name}" for name in (
            "README.md", "01_仓库事实审计与范围边界.md", "02_换货CR业务价值与影响摘要.md",
            "03_T2_EXG001设计准入包.md", "04_T2_PAY004设计准入包.md",
            "05_持久化迁移容量兼容与回退设计.md", "06_页面API事件错误码与离线行为.md",
            "07_测试矩阵故障注入与量化验收.md", "08_T2_Gate7B_S20B正式开发启动评审报告.md",
            "09_第二批正式开发操作指令.md",
        )],
    ]
    fail(all((ROOT / item).is_file() for item in required), "required prep deliverables incomplete")
    fail(git("merge-base", "--is-ancestor", BASELINE, "HEAD") == "", "first-batch seal is not ancestor")
    changed = sorted(filter(None, git("diff", "--name-only", BASELINE, "HEAD").splitlines()))
    allowed_exact = {
        "AGENTS.md", "README.md", ".github/workflows/t2-gate7b-s20b-prep.yml",
        "contracts/t2/gate7b/gate7b-admission.json", "docs/adr/README.md",
        "docs/governance/change-log.md", "docs/governance/rtm.csv",
        "docs/governance/CR-T2G7B-009_exchange-orchestration-scope.md",
        "docs/t2-gate7b/README.md", "docs/t2-gate7b/09_第一批项目发起人接受记录.md",
        "scripts/check_t2_gate7b.py", "scripts/check_t2_gate7b_s20b_prep.py",
        "scripts/build_t2_gate7b_s20b_evidence.py",
    }
    prefixes = ("contracts/t2/gate7b-s20b/", "docs/t2-gate7b-s20b/", "docs/adr/ADR-044-")
    illegal = [item for item in changed if item not in allowed_exact and not item.startswith(prefixes)]
    fail(not illegal, f"out-of-scope files changed: {illegal}")
    runtime_prefixes = ("server/", "admin-web/", "pos-flutter/", "packages/", "infrastructure/", "infra/")
    fail(not [item for item in changed if item.startswith(runtime_prefixes)], "formal runtime/dependency surface changed")
    migration_tokens = ("/db/migration/", "pos-flutter/lib/infrastructure/local_database/migrations/")
    fail(not [item for item in changed if any(token in item for token in migration_tokens)], "database migration added")
    sensitive = re.compile(r"(^|/)(\.env($|\.)|.*\.(pem|p12|pfx|jks|keystore|key)$)", re.IGNORECASE)
    fail(not [item for item in changed if sensitive.search(item)], "sensitive file name detected")
    private_key_marker = "-----BEGIN " + "PRIVATE KEY-----"
    access_key_pattern = re.compile("AKIA" + r"[0-9A-Z]{16}")
    for item in changed:
        path = ROOT / item
        if path.is_file():
            content = path.read_text(encoding="utf-8", errors="ignore")
            fail(private_key_marker not in content and not access_key_pattern.search(content),
                 f"possible secret in {item}")
    return changed


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--output")
    args = parser.parse_args()
    rows = read_rtm()
    contract = validate_admission(rows)
    metrics = validate_contracts_and_vectors()
    validate_repository_facts()
    changed = validate_docs_and_scope()
    result = {
        "schemaVersion": "1.0", "gate": GATE, "status": "PASS",
        "evidenceLevel": contract["evidenceLevel"], "baselineCommit": BASELINE,
        "firstBatchAccepted": list(FIRST_BATCH_ACCEPTED),
        "prepRequirements": {requirement_id: "DRAFT" for requirement_id in PREP_REQUIREMENTS},
        **metrics, "preservedStates": PRESERVED_STATES,
        "externalExecution": contract["externalExecution"], "runtimeFilesChanged": 0,
        "databaseMigrationsAdded": 0, "changedFiles": changed,
        "overallDecision": "PREP_COMPLETE_SECOND_BATCH_AWAITING_CONFIRMATION",
    }
    if args.output:
        target = ROOT / args.output
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({key: value for key, value in result.items() if key != "changedFiles"}, ensure_ascii=False))


if __name__ == "__main__":
    main()
