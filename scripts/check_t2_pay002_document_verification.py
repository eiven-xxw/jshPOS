#!/usr/bin/env python3
"""T2-PAY-002 拉卡拉受控材料离线验真与零网络边界门禁。"""
from __future__ import annotations

import argparse
import csv
import json
import pathlib
import re
import subprocess


ROOT = pathlib.Path(__file__).resolve().parents[1]
BASELINE = "7ccc50112d954c1d4c9bdd3464da4b3a2eb9ec3e"
INTAKE = ROOT / "contracts/t2/pay002-document-verification/pay002-document-intake.json"
METADATA_SCHEMA = ROOT / "contracts/t2/pay002-document-verification/controlled-material-metadata.schema.json"
RTM = ROOT / "docs/governance/rtm.csv"


def fail(condition: bool, message: str) -> None:
    if not condition:
        raise SystemExit(f"T2-PAY002-DOC ERROR: {message}")


def load(path: pathlib.Path) -> dict:
    try:
        return json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as exception:
        raise SystemExit(f"T2-PAY002-DOC ERROR: invalid {path.relative_to(ROOT)}: {exception}")


def git(*args: str) -> str:
    completed = subprocess.run(
        ["git", "-c", "core.quotepath=false", *args], cwd=ROOT,
        check=False, capture_output=True, text=True, encoding="utf-8",
    )
    fail(completed.returncode == 0, f"git {' '.join(args)} failed: {completed.stderr.strip()}")
    return completed.stdout.strip()


def validate_rtm() -> dict[str, dict[str, str]]:
    with RTM.open(encoding="utf-8-sig", newline="") as stream:
        rows = {row["requirement_id"]: row for row in csv.DictReader(stream)}
    expected = {
        "T2-ADM-002": "ACCEPTED", "T2-POS-009": "ACCEPTED", "T2-E2E-002": "ACCEPTED",
        "T2-PAY-002": "BLOCKED", "T2-HWD-001": "BLOCKED", "T2-PAR-001": "BLOCKED",
        "T2-UAT-001": "DRAFT", "T2-REL-001": "DRAFT",
        "T2-JSH-001": "DEFERRED", "T2-LIC-001": "DEFERRED",
    }
    for requirement_id, status in expected.items():
        fail(rows.get(requirement_id, {}).get("status") == status, f"RTM status mismatch: {requirement_id}")
    return rows


def validate_intake() -> dict:
    intake = load(INTAKE)
    fail(intake.get("baselineCommit") == BASELINE, "baseline drift")
    fail(intake.get("branch") == "t2/pay002-controlled-doc-verification-20260821", "branch contract drift")
    fail(intake.get("requirementId") == "T2-PAY-002" and intake.get("status") == "BLOCKED",
         "requirement identity/status drift")
    provider = intake.get("provider", {})
    fail(provider.get("code") == "LAKALA" and
         provider.get("scope") == "CONTROLLED_DOCUMENT_VERIFICATION_CANDIDATE_ONLY",
         "Provider candidate scope drift")
    authorization = intake.get("authorization", {})
    fail(authorization.get("decision") == "APPROVED_OFFLINE_VERIFICATION_ONLY",
         "offline authorization missing")
    fail(authorization.get("providerNetworkAllowed") is False and
         authorization.get("executionAdmissionAllowed") is False,
         "network/execution must not be authorized")
    fail(intake.get("submittedValueClassification") == "UNFILLED_PLACEHOLDER_NOT_A_MATERIAL_ID",
         "placeholder classification drift")
    fail(intake.get("receivedMaterialIds") == [] and intake.get("receivedMaterialCount") == 0,
         "actual material IDs must remain zero")
    fail(intake.get("requiredMaterialCount") == 11, "required material count drift")
    items = intake.get("items", [])
    expected_ids = {
        "PAY-AUTH", "PAY-MERCHANT", "PAY-TERMINAL", "PAY-API", "PAY-SIGN", "PAY-CALLBACK",
        "PAY-BILL", "PAY-LIMIT", "PAY-NETWORK", "PAY-SECRET", "PAY-CONTACT",
    }
    fail(len(items) == 11 and {item.get("id") for item in items} == expected_ids,
         "payment controlled-material mapping incomplete")
    fail(all(item.get("materialId") is None and item.get("status") == "MISSING" for item in items),
         "missing material must not be upgraded")
    verification_fields = set(intake.get("requiredVerificationFields", []))
    expected_fields = {
        "source", "authorizationSignature", "applicableProduct", "interfaceVersion", "scope",
        "sha256", "custodian", "effectiveAt", "expiresAt", "rotationRule", "deletionRevocationRule",
    }
    fail(verification_fields == expected_fields, "verification field set drift")
    fail(intake.get("bundleStatus") == "MISSING" and
         intake.get("verifiedDocumentStatus") == "NOT_ACHIEVED" and
         intake.get("executionAdmission") == "NO_GO", "bundle must remain missing/no-go")
    counters = intake.get("counters", {})
    fail(counters == {
        "providerNetworkCalls": 0, "realFunds": 0,
        "productionSecretsReceived": 0, "realPaymentRecordsReceived": 0,
    }, "payment external/sensitive counters must remain zero")
    return intake


def validate_metadata_schema() -> dict:
    schema = load(METADATA_SCHEMA)
    required = set(schema.get("required", []))
    expected = {
        "materialId", "category", "provider", "source", "authorizationSignatureStatus",
        "applicableProduct", "interfaceVersion", "scope", "sha256", "sizeBytes", "custodianRef",
        "classification", "receivedAt", "effectiveAt", "expiresAt", "rotationRule",
        "deletionRevocationRule", "controlledLocationRef",
    }
    fail(required == expected and schema.get("additionalProperties") is False,
         "controlled metadata schema is incomplete or permissive")
    properties = schema.get("properties", {})
    fail(properties.get("provider", {}).get("const") == "LAKALA", "metadata Provider constraint drift")
    fail(properties.get("sha256", {}).get("pattern") == "^[0-9a-f]{64}$", "SHA-256 constraint drift")
    fail("value" not in properties and "secret" not in {key.lower() for key in properties},
         "metadata schema must not accept raw Secret values")
    return schema


def validate_docs_and_scope() -> list[str]:
    required_docs = [
        "docs/t2-pay002-document-verification/README.md",
        "docs/t2-pay002-document-verification/01_授权范围与安全收件边界.md",
        "docs/t2-pay002-document-verification/02_材料映射与逐项验真清单.md",
        "docs/t2-pay002-document-verification/03_T2_PAY002受控材料离线验真缺件报告.md",
        "docs/t2-pay002-document-verification/04_拉卡拉受控材料补件下一步操作指令.md",
    ]
    fail(all((ROOT / path).is_file() for path in required_docs), "payment document-verification report set incomplete")
    fail(git("merge-base", "--is-ancestor", BASELINE, "HEAD") == "", "Gate 6F closure is not an ancestor")
    changed = git("diff", "--name-only", BASELINE).splitlines()
    untracked = git("ls-files", "--others", "--exclude-standard").splitlines()
    changed = sorted(set(filter(None, changed + untracked)))
    allowed_exact = {
        "AGENTS.md", ".github/workflows/t2-pay002-document-verification.yml",
        "docs/governance/change-log.md", "docs/governance/rtm.csv",
        "docs/t2-gate6f-prep/README.md",
        "scripts/check_t2_pay002_document_verification.py",
        "scripts/build_t2_pay002_document_verification_evidence.py",
    }
    allowed_prefixes = (
        "contracts/t2/pay002-document-verification/",
        "docs/t2-pay002-document-verification/",
    )
    illegal = [path for path in changed if path not in allowed_exact and not path.startswith(allowed_prefixes)]
    fail(not illegal, f"runtime or out-of-scope files changed: {illegal}")
    runtime_roots = ("server/", "admin-web/", "pos-flutter/", "packages/", "infrastructure/", "infra/")
    fail(not [path for path in changed if path.startswith(runtime_roots)], "runtime/dependency surface changed")
    sensitive_name = re.compile(r"(^|/)(\.env($|\.)|.*\.(pem|p12|pfx|jks|keystore|key)$)", re.IGNORECASE)
    fail(not [path for path in changed if sensitive_name.search(path)], "sensitive file name found")
    secret_patterns = [
        re.compile(r"-----BEGIN [A-Z ]*PRIVATE KEY-----"),
        re.compile(r"AKIA[0-9A-Z]{16}"),
        re.compile(r"(?i)(password|secret|token)\s*[:=]\s*['\"][^'\"]{8,}['\"]"),
    ]
    for name in changed:
        path = ROOT / name
        if path.is_file():
            content = path.read_text(encoding="utf-8", errors="ignore")
            fail(not any(pattern.search(content) for pattern in secret_patterns), f"possible secret in {name}")
    return changed


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--output", type=pathlib.Path)
    args = parser.parse_args()
    validate_rtm()
    intake = validate_intake()
    validate_metadata_schema()
    changed = validate_docs_and_scope()
    result = {
        "gate": "T2-PAY002-CONTROLLED-DOCUMENT-VERIFICATION",
        "status": "PASS",
        "evidenceLevel": "STATIC_GOVERNANCE_AND_MISSING_INTAKE",
        "requirementStatus": "BLOCKED",
        "provider": "LAKALA",
        "authorization": intake["authorization"]["decision"],
        "receivedMaterialCount": intake["receivedMaterialCount"],
        "requiredMaterialCount": intake["requiredMaterialCount"],
        "verifiedDocumentStatus": intake["verifiedDocumentStatus"],
        "executionAdmission": intake["executionAdmission"],
        "counters": intake["counters"],
        "changedFiles": len(changed),
    }
    if args.output:
        target = ROOT / args.output if not args.output.is_absolute() else args.output
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(result, ensure_ascii=False))


if __name__ == "__main__":
    main()
