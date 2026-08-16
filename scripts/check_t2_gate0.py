from __future__ import annotations

import argparse
import csv
import hashlib
import json
import re
import subprocess
import sys
from datetime import datetime, timezone
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
BASELINE_TAG = "t2-prep-baseline-2026-08-16"
BASELINE_COMMIT = "557ba270479935d6b44968cf70b47033f7d3d656"
BRANCH_START = "7fe4391069d8ee6c641d1b3e509d9f90050be5ef"
EXPECTED_BRANCH = "t2/gate0-sprint0-20260816"
ADMISSION = ROOT / "contracts" / "t2" / "gate0" / "gate0-admission.json"
RTM = ROOT / "docs" / "governance" / "rtm.csv"
DOC_DIR = ROOT / "docs" / "t2-gate0"
GATE1_DOC_DIR = ROOT / "docs" / "t2-gate1-contracts"
GATE1_CONTRACT_DIR = ROOT / "contracts" / "t2" / "gate1"
MIGRATION_CHECKSUMS = ROOT / "contracts" / "t2" / "gate0" / "migration-checksums.json"

GATE0_IDS = {
    "T2-IAM-001", "T2-ORG-001", "T2-RBAC-001", "T2-CFG-001",
    "T2-AUD-001", "T2-SEC-001", "T2-OBS-001", "T2-MIG-001",
}
GATE1_IDS = {
    "T2-PRD-001", "T2-PRD-002", "T2-PRD-003", "T2-PRD-004",
    "T2-PRC-001", "T2-PRC-002", "T2-DPK-001",
}
GATE0_ALLOWED_STATUSES = {"DRAFT", "READY", "IN_PROGRESS", "IMPLEMENTED", "VERIFIED"}
REQUIRED_DOCS = {
    "README.md",
    "01_范围非目标与逐项准入.md",
    "02_RuoYi能力复核与框架边界.md",
    "03_领域数据主权状态与不变量.md",
    "04_权限审计API与事件契约.md",
    "05_Flyway容量兼容与回退设计.md",
    "06_测试矩阵CI与证据规范.md",
}
REQUIRED_GATE1_DOCS = {
    "README.md",
    "01_领域模型状态不变量与数据主权.md",
    "02_API事件迁移容量与回退设计.md",
    "03_合成向量与验收用例.md",
}
FORBIDDEN_RUNTIME_SEGMENTS = {
    "order", "payment", "refund", "inventory", "procurement", "cost", "promotion",
    "product", "pricing",
}
RUNTIME_ROOTS = (
    ROOT / "server" / "ruoyi-modules" / "jshpos-foundation" / "src" / "main",
    ROOT / "admin-web" / "src" / "api" / "foundation",
    ROOT / "admin-web" / "src" / "views" / "foundation",
)


def fail(message: str) -> None:
    print(f"T2-GATE0 ERROR: {message}", file=sys.stderr)
    raise SystemExit(1)


def git(*args: str, check: bool = True) -> str:
    result = subprocess.run(
        ["git", *args], cwd=ROOT, capture_output=True, text=True,
        encoding="utf-8", errors="replace", check=False,
    )
    if check and result.returncode:
        fail(f"git {' '.join(args)} failed: {result.stderr.strip()}")
    return result.stdout.strip()


def check_baseline() -> dict[str, str]:
    if git("cat-file", "-t", BASELINE_TAG) != "tag":
        fail(f"{BASELINE_TAG} must remain annotated")
    if git("rev-parse", f"{BASELINE_TAG}^{{}}") != BASELINE_COMMIT:
        fail(f"{BASELINE_TAG} moved from sealed commit")
    for ancestor in (BASELINE_TAG, BRANCH_START):
        result = subprocess.run(
            ["git", "merge-base", "--is-ancestor", ancestor, "HEAD"], cwd=ROOT, check=False,
        )
        if result.returncode:
            fail(f"{ancestor} is not an ancestor of HEAD")
    branch = git("branch", "--show-current")
    if branch != EXPECTED_BRANCH and not branch.startswith("review/"):
        fail(f"unexpected branch {branch!r}; expected {EXPECTED_BRANCH!r}")
    return {"tag": BASELINE_TAG, "tagTarget": BASELINE_COMMIT, "branchStart": BRANCH_START, "branch": branch}


def check_docs_and_admission() -> dict[str, object]:
    actual = {path.name for path in DOC_DIR.glob("*.md")}
    missing = sorted(REQUIRED_DOCS - actual)
    if missing:
        fail(f"missing Gate 0 documents: {missing}")
    corpus = "\n".join((DOC_DIR / name).read_text(encoding="utf-8") for name in sorted(REQUIRED_DOCS))
    for term in ("tenant_id", "Flyway", "审计", "权限", "容量", "回退", "Gate 1", "DRAFT", "不得"):
        if term not in corpus:
            fail(f"Gate 0 design corpus missing {term!r}")
    adr = (ROOT / "docs" / "adr" / "ADR-019-t2-alpha-module-gates-and-industry-templates.md").read_text(encoding="utf-8")
    if "- 状态：Accepted" not in adr:
        fail("ADR-019 must be Accepted after sponsor authorization")
    payload = json.loads(ADMISSION.read_text(encoding="utf-8"))
    requirements = payload.get("requirements", {})
    if set(requirements) != GATE0_IDS:
        fail(f"Gate 0 admission IDs mismatch: {sorted(requirements)}")
    required_checks = {
        "source", "scope", "nonGoals", "owner", "invariants", "permissions",
        "audit", "api", "migration", "capacity", "rollback", "tests",
    }
    for requirement_id, checks in requirements.items():
        if set(checks) != required_checks or not all(checks.values()):
            fail(f"{requirement_id} has incomplete admission checks")
    if set(payload.get("gate1ContractOnly", [])) != GATE1_IDS:
        fail("Gate 1 contract-only IDs mismatch")
    return {"documents": len(REQUIRED_DOCS), "admitted": sorted(GATE0_IDS)}


def check_gate1_contract_only() -> dict[str, object]:
    actual_docs = {path.name for path in GATE1_DOC_DIR.glob("*.md")}
    missing_docs = sorted(REQUIRED_GATE1_DOCS - actual_docs)
    if missing_docs:
        fail(f"missing Gate 1 contract-prep documents: {missing_docs}")
    required_files = {
        "openapi-product-price-draft.yaml",
        "schemas/product-master.v1.schema.json",
        "schemas/product-import-batch.v1.schema.json",
        "schemas/price-book.v1.schema.json",
        "schemas/data-package-manifest.v1.schema.json",
        "events/product.changed.v1.schema.json",
        "events/price-book.published.v1.schema.json",
        "events/data-package.available.v1.schema.json",
        "test-vectors/two-tenant-product-price-v1.json",
    }
    missing_files = sorted(
        item for item in required_files if not (GATE1_CONTRACT_DIR / item).is_file()
    )
    if missing_files:
        fail(f"missing Gate 1 contract-prep files: {missing_files}")
    corpus = "\n".join(path.read_text(encoding="utf-8") for path in GATE1_DOC_DIR.glob("*.md"))
    for requirement_id in sorted(GATE1_IDS):
        if requirement_id not in corpus:
            fail(f"Gate 1 contract-prep corpus missing {requirement_id}")
    if "全部需求继续保持 `DRAFT`" not in corpus:
        fail("Gate 1 contract preparation must explicitly remain DRAFT")
    vector = json.loads((GATE1_CONTRACT_DIR / "test-vectors/two-tenant-product-price-v1.json").read_text(encoding="utf-8"))
    if vector.get("syntheticOnly") is not True or len(vector.get("tenants", [])) != 2:
        fail("Gate 1 test vector must contain exactly two synthetic tenants")
    return {"documents": len(REQUIRED_GATE1_DOCS), "contracts": len(required_files), "status": "DRAFT"}


def check_rtm() -> dict[str, object]:
    with RTM.open(encoding="utf-8-sig", newline="") as handle:
        rows = {row["requirement_id"]: row for row in csv.DictReader(handle)}
    for requirement_id in GATE0_IDS:
        row = rows.get(requirement_id)
        if row is None or row["status"] not in GATE0_ALLOWED_STATUSES:
            fail(f"{requirement_id} missing or invalid status")
        if row["status"] != "DRAFT" and not row["implementation"].strip():
            fail(f"{requirement_id} left DRAFT without implementation/design trace")
    changed_gate1 = [requirement_id for requirement_id in GATE1_IDS if rows.get(requirement_id, {}).get("status") != "DRAFT"]
    if changed_gate1:
        fail(f"Gate 1 requirements must remain DRAFT: {changed_gate1}")
    unexpected = [
        row["requirement_id"] for row in rows.values()
        if row["requirement_id"].startswith("T2-")
        and row["requirement_id"] not in GATE0_IDS | GATE1_IDS
        and row["status"] not in {"DRAFT", "BLOCKED", "DEFERRED"}
    ]
    if unexpected:
        fail(f"requirements outside Gate 0 changed state: {unexpected}")
    return {
        "gate0Statuses": {requirement_id: rows[requirement_id]["status"] for requirement_id in sorted(GATE0_IDS)},
        "gate1Draft": len(GATE1_IDS),
    }


def check_runtime_scope() -> list[str]:
    violations: list[str] = []
    for root in RUNTIME_ROOTS:
        if not root.exists():
            continue
        for path in root.rglob("*"):
            if not path.is_file():
                continue
            relative_parts = {part.lower() for part in path.relative_to(root).parts}
            stem_tokens = set(re.split(r"[^a-z]+", path.stem.lower()))
            if (relative_parts | stem_tokens) & FORBIDDEN_RUNTIME_SEGMENTS:
                violations.append(path.relative_to(ROOT).as_posix())
    if violations:
        fail(f"forbidden Gate 1/later runtime code found: {violations}")
    return violations


def check_migration_checksums() -> dict[str, str]:
    ledger = json.loads(MIGRATION_CHECKSUMS.read_text(encoding="utf-8"))
    results: dict[str, str] = {}
    for item in ledger.get("files", []):
        path = ROOT / item["path"]
        if not path.is_file():
            fail(f"migration checksum target missing: {item['path']}")
        actual = hashlib.sha256(path.read_bytes()).hexdigest()
        if actual != item["sha256"]:
            fail(f"published migration changed: {item['path']}")
        results[item["path"]] = actual
    if len(results) != 2:
        fail("Gate 0 requires exactly two sealed migration files")
    return results


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--output", type=Path)
    args = parser.parse_args()
    evidence = {
        "schemaVersion": "1.0",
        "phase": "T2-GATE0-S0",
        "evidenceLevel": "STATIC",
        "generatedAt": datetime.now(timezone.utc).isoformat().replace("+00:00", "Z"),
        "commitSha": git("rev-parse", "HEAD"),
        "baseline": check_baseline(),
        "design": check_docs_and_admission(),
        "gate1ContractOnly": check_gate1_contract_only(),
        "requirements": check_rtm(),
        "migrationChecksums": check_migration_checksums(),
        "forbiddenRuntimeViolations": check_runtime_scope(),
        "externalEvidence": {"sandbox": 0, "realDevice": 0, "pilot": 0},
    }
    if args.output:
        output = args.output if args.output.is_absolute() else ROOT / args.output
        output.parent.mkdir(parents=True, exist_ok=True)
        output.write_text(json.dumps(evidence, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print("T2-GATE0 OK: baseline=sealed admission=8/8 gate1-contracts=DRAFT forbidden-runtime=0")


if __name__ == "__main__":
    main()
