from __future__ import annotations

import argparse
import csv
import hashlib
import json
import re
import subprocess
from datetime import datetime, timezone
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
BASELINE_TAG = "t2-prep-baseline-2026-08-16"
BASELINE_COMMIT = "557ba270479935d6b44968cf70b47033f7d3d656"
BRANCH_START = "63537b5cb7ceeb1fe6b04107b53e2e68941b25ad"
SEQUENCE = ("T2-PRM-001", "T2-PRM-002", "T2-PRM-003")
PRIOR_ACCEPTED = {
    "T2-IAM-001", "T2-ORG-001", "T2-RBAC-001", "T2-CFG-001", "T2-AUD-001", "T2-SEC-001",
    "T2-OBS-001", "T2-MIG-001", "T2-PRD-001", "T2-PRD-002", "T2-PRD-003", "T2-PRD-004",
    "T2-PRC-001", "T2-PRC-002", "T2-DPK-001", "T2-POS-001", "T2-POS-002", "T2-POS-003",
    "T2-POS-004", "T2-POS-005", "T2-ORD-001", "T2-ORD-002", "T2-OFF-001", "T2-SYN-001",
    "T2-PAY-001", "T2-PAY-003", "T2-REF-001", "T2-REC-001", "T2-INV-001", "T2-INV-002",
    "T2-INV-003", "T2-INV-004", "T2-PUR-001", "T2-CST-001", "T2-TRF-001",
}
EXTERNAL = {
    "T2-HWD-001": "BLOCKED", "T2-PAY-002": "BLOCKED", "T2-PAR-001": "BLOCKED",
    "T2-JSH-001": "DEFERRED", "T2-LIC-001": "DEFERRED",
}
STAGE_STATUS = {
    "prm1": {"T2-PRM-001": "IN_PROGRESS", "T2-PRM-002": "DRAFT", "T2-PRM-003": "DRAFT"},
    "prm2": {"T2-PRM-001": "VERIFIED", "T2-PRM-002": "IN_PROGRESS", "T2-PRM-003": "DRAFT"},
    "prm3": {"T2-PRM-001": "VERIFIED", "T2-PRM-002": "VERIFIED", "T2-PRM-003": "IN_PROGRESS"},
    "closure": {"T2-PRM-001": "VERIFIED", "T2-PRM-002": "VERIFIED", "T2-PRM-003": "VERIFIED"},
}
STAGE_ADMITTED = {
    "prm1": ["T2-PRM-001"],
    "prm2": ["T2-PRM-001", "T2-PRM-002"],
    "prm3": list(SEQUENCE),
    "closure": list(SEQUENCE),
}
DESIGN_FILES = [
    "docs/adr/ADR-028-gate5a-deterministic-promotion-allocation.md",
    "docs/t2-gate5a/01_范围非目标与顺序准入.md",
    "docs/t2-gate5a/02_数据主权状态机规则流水线与不变量.md",
    "docs/t2-gate5a/03_权限审计API事件离线包与跨模块契约.md",
    "docs/t2-gate5a/04_持久化策略Flyway容量兼容与回退.md",
    "docs/t2-gate5a/05_测试矩阵CI与证据.md",
    "contracts/t2/gate5a/gate5a-admission.json",
    "contracts/t2/gate5a/persistence-strategy-registry.json",
    "contracts/t2/gate5a/openapi-promotion-v1.yaml",
    "contracts/t2/gate5a/test-vectors/promotion-golden-vectors-v1.json",
]
LEDGER = ROOT / "contracts/t2/gate5a/migration-checksums.json"


def fail(message: str) -> None:
    raise SystemExit(f"T2-GATE5A ERROR: {message}")


def git(*args: str) -> str:
    result = subprocess.run(["git", *args], cwd=ROOT, text=True, capture_output=True)
    if result.returncode:
        fail(result.stderr.strip() or f"git {' '.join(args)} failed")
    return result.stdout.strip()


def require_file(relative: str) -> str:
    path = ROOT / relative
    if not path.is_file():
        fail(f"missing {relative}")
    content = path.read_text(encoding="utf-8")
    if not content.strip():
        fail(f"empty {relative}")
    return content


def check_ancestry() -> dict[str, str]:
    peeled = git("rev-list", "-n", "1", BASELINE_TAG)
    if peeled != BASELINE_COMMIT:
        fail(f"baseline tag moved: {peeled}")
    for revision in (BASELINE_TAG, BRANCH_START):
        if subprocess.run(["git", "merge-base", "--is-ancestor", revision, "HEAD"], cwd=ROOT).returncode:
            fail(f"required ancestor missing: {revision}")
    return {"baselineTag": BASELINE_TAG, "peeledCommit": peeled, "branchStart": BRANCH_START}


def read_rtm() -> dict[str, dict[str, str]]:
    with (ROOT / "docs/governance/rtm.csv").open(encoding="utf-8-sig", newline="") as handle:
        return {row["requirement_id"]: row for row in csv.DictReader(handle)}


def check_requirements(stage: str) -> dict[str, object]:
    rows = read_rtm()
    for requirement in sorted(PRIOR_ACCEPTED):
        if rows.get(requirement, {}).get("status") != "ACCEPTED":
            fail(f"prior requirement {requirement} must be ACCEPTED")
    for requirement, expected in STAGE_STATUS[stage].items():
        actual = rows.get(requirement, {}).get("status")
        if actual != expected:
            fail(f"{requirement} must be {expected} at {stage}, got {actual}")
    for requirement, status in EXTERNAL.items():
        if rows.get(requirement, {}).get("status") != status:
            fail(f"{requirement} must remain {status}")
    return {"priorAccepted": len(PRIOR_ACCEPTED), "gate5a": STAGE_STATUS[stage], "paymentSandbox": "BLOCKED"}


def check_design(stage: str) -> dict[str, object]:
    contents = {path: require_file(path) for path in DESIGN_FILES}
    joined = "\n".join(contents.values())
    adr = contents["docs/adr/ADR-028-gate5a-deterministic-promotion-allocation.md"]
    if "状态：Accepted" not in adr:
        fail("ADR-028 must be Accepted")
    for requirement in (*SEQUENCE, "T2-PAY-002"):
        if requirement not in joined:
            fail(f"design does not map {requirement}")
    for dimension in ("数据主权", "状态", "不变量", "权限", "审计", "API", "事件", "Flyway", "容量", "回退", "CI"):
        if dimension not in joined:
            fail(f"admission dimension missing: {dimension}")
    admission = json.loads(contents["contracts/t2/gate5a/gate5a-admission.json"])
    if admission.get("sequence") != list(SEQUENCE):
        fail("Gate 5A sequence changed")
    if admission.get("admittedRequirements") != STAGE_ADMITTED[stage]:
        fail(f"admitted requirements do not match stage {stage}")
    expected_drafts = [item for item in SEQUENCE if STAGE_STATUS[stage][item] == "DRAFT"]
    if admission.get("draftRequirements") != expected_drafts:
        fail(f"draft requirements do not match stage {stage}")
    policy = admission.get("enginePolicy", {})
    if policy.get("moneyRepresentation") != "MINOR_UNIT_INT64":
        fail("money representation must remain minor-unit int64")
    if policy.get("candidateOrder") != ["priority_desc", "rule_version_id_asc"]:
        fail("candidate order changed without ADR")
    boundary = admission.get("runtimeBoundary", {})
    if boundary.get("providerNetworkCallsAllowed") != 0:
        fail("Provider network boundary relaxed")
    for key in ("couponRuntimeAllowed", "memberRuntimeAllowed", "loyaltyRuntimeAllowed",
                "storedValueRuntimeAllowed", "budgetReservationAllowed", "reportRuntimeAllowed"):
        if boundary.get(key):
            fail(f"later-Gate boundary relaxed: {key}")
    return {"files": len(contents), "sequence": list(SEQUENCE), "providerNetworkCallsAllowed": 0}


def check_registry() -> dict[str, object]:
    registry = json.loads(require_file("contracts/t2/gate5a/persistence-strategy-registry.json"))
    tables = registry.get("tables", [])
    names = [item.get("table") for item in tables]
    if len(tables) != 16 or len(set(names)) != 16:
        fail("persistence registry must contain sixteen unique tables")
    if {item.get("strategy") for item in tables} != {"MP_ENTITY", "XML_ONLY"}:
        fail("persistence registry must contain MP_ENTITY and XML_ONLY")
    mp = [item for item in tables if item.get("strategy") == "MP_ENTITY"]
    if [item.get("table") for item in mp] != ["prm_rule"]:
        fail("only prm_rule may use MyBatis-Plus entity strategy")
    return {"tables": 16, "mybatisPlusEntities": 1, "xmlOnly": 15}


def check_vectors(stage: str) -> dict[str, object]:
    vectors = json.loads(require_file("contracts/t2/gate5a/test-vectors/promotion-golden-vectors-v1.json"))
    scenarios = vectors.get("scenarios", [])
    if len(scenarios) < 16:
        fail("PRM-001 golden vector ledger must contain at least sixteen scenarios")
    ids = [item.get("id") for item in scenarios]
    if len(ids) != len(set(ids)):
        fail("golden vector scenario ids must be unique")
    ulid = re.compile(r"^[0-9A-HJKMNP-TV-Z]{26}$")
    for scenario in scenarios:
        for line in scenario.get("lines", []):
            if not ulid.fullmatch(str(line.get("lineId", ""))):
                fail(f"invalid line ULID in {scenario.get('id')}: {line.get('lineId')}")
        for rule in scenario.get("rules", []):
            if not ulid.fullmatch(str(rule.get("ruleVersionId", ""))):
                fail(f"invalid rule-version ULID in {scenario.get('id')}: {rule.get('ruleVersionId')}")
        expected = scenario.get("expected", {})
        gross = expected.get("grossAmountMinor")
        discount = expected.get("discountAmountMinor")
        payable = expected.get("payableAmountMinor")
        if not all(isinstance(value, int) for value in (gross, discount, payable)):
            fail(f"non-integer money in {scenario.get('id')}")
        if gross - discount != payable or sum(expected.get("lineDiscounts", {}).values()) != discount:
            fail(f"amount conservation failed in {scenario.get('id')}")
    if stage != "prm1" and vectors.get("currentMilestone") == "PRM1":
        fail(f"golden vectors were not advanced for {stage}")
    return {"scenarios": len(scenarios), "amountConservation": True}


def runtime_text() -> str:
    roots = [
        ROOT / "server/ruoyi-modules/jshpos-promotion/src/main",
        ROOT / "pos-flutter/lib",
    ]
    parts: list[str] = []
    for root in roots:
        if root.is_dir():
            for path in sorted(root.rglob("*")):
                if path.is_file() and path.suffix.lower() in {".java", ".xml", ".dart", ".yaml", ".sql"}:
                    parts.append(path.read_text(encoding="utf-8", errors="replace"))
    return "\n".join(parts).lower()


def check_runtime(stage: str) -> dict[str, object]:
    text = runtime_text()
    forbidden = ("java.net.http", "resttemplate", "webclient", "okhttp", "hutool.http",
                 "httpurlconnection", "couponservice", "memberservice", "loyaltyservice",
                 "storedvalueservice", "budgetreservationservice", "reportservice")
    violations = [token for token in forbidden if token in text]
    if violations:
        fail(f"forbidden Provider/later-Gate runtime found: {violations}")
    required = {
        "prm1": (),
        "prm2": ("promotionengine", "promotionruleentity", "trustedtenantcontext"),
        "prm3": ("promotionengine", "manualprice", "transactionalloc", "trustedtenantcontext"),
        "closure": ("promotionengine", "manualprice", "transactionalloc", "refundalloc", "trustedtenantcontext"),
    }[stage]
    for marker in required:
        if marker not in text:
            fail(f"runtime marker missing for {stage}: {marker}")
    return {"promotionPresent": bool(text), "providerNetworkCalls": 0, "forbiddenRuntime": 0}


def check_migrations(stage: str) -> dict[str, object]:
    if stage != "closure":
        if LEDGER.exists():
            fail("migration checksum ledger must not be sealed before closure")
        return {"sealed": False}
    if not LEDGER.is_file():
        fail("Gate 5A migration checksum ledger missing")
    files = json.loads(LEDGER.read_text(encoding="utf-8")).get("files", [])
    if len(files) not in (2, 3):
        fail("Gate 5A must seal server V20/V21 and POS V3 migration artifacts")
    for item in files:
        path = ROOT / item["path"]
        if not path.is_file() or hashlib.sha256(path.read_bytes()).hexdigest() != item["sha256"]:
            fail(f"sealed migration changed: {item['path']}")
    return {"sealed": True, "files": len(files)}


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--stage", choices=tuple(STAGE_STATUS), default="prm1")
    parser.add_argument("--output", type=Path)
    args = parser.parse_args()
    evidence = {
        "schemaVersion": "1.0", "phase": "T2-GATE5A", "stage": args.stage,
        "generatedAt": datetime.now(timezone.utc).isoformat().replace("+00:00", "Z"),
        "commitSha": git("rev-parse", "HEAD"), "ancestry": check_ancestry(),
        "design": check_design(args.stage), "requirements": check_requirements(args.stage),
        "persistence": check_registry(), "vectors": check_vectors(args.stage),
        "runtime": check_runtime(args.stage), "migrations": check_migrations(args.stage),
        "externalEvidence": {"sandbox": 0, "realDevice": 0, "pilot": 0},
    }
    if args.output:
        target = args.output if args.output.is_absolute() else ROOT / args.output
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(json.dumps(evidence, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(f"T2-GATE5A OK: stage={args.stage} prior={len(PRIOR_ACCEPTED)} accepted "
          f"gate5a={STAGE_STATUS[args.stage]} paymentNetwork=0 external=0")


if __name__ == "__main__":
    main()
