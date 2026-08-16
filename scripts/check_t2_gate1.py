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
BRANCH_START = "cf2ef29bd74c5d0f8fa5845a689305ebb56c7ef2"
EXPECTED_BRANCH = "t2/gate1-sprint1-20260816"
RTM = ROOT / "docs" / "governance" / "rtm.csv"
ADMISSION = ROOT / "contracts" / "t2" / "gate1" / "gate1-admission.json"
DOC_DIR = ROOT / "docs" / "t2-gate1"
GATE0_MIGRATIONS = ROOT / "contracts" / "t2" / "gate0" / "migration-checksums.json"
GATE1_MIGRATIONS = ROOT / "contracts" / "t2" / "gate1" / "migration-checksums.json"

GATE0_IDS = {
    "T2-IAM-001", "T2-ORG-001", "T2-RBAC-001", "T2-CFG-001",
    "T2-AUD-001", "T2-SEC-001", "T2-OBS-001", "T2-MIG-001",
}
GATE1_IDS = {
    "T2-PRD-001", "T2-PRD-002", "T2-PRD-003", "T2-PRD-004",
    "T2-PRC-001", "T2-PRC-002", "T2-DPK-001",
}
EXTERNAL_STATUSES = {
    "T2-HWD-001": "BLOCKED",
    "T2-PAR-001": "BLOCKED",
    "T2-PAY-002": "BLOCKED",
    "T2-JSH-001": "DEFERRED",
    "T2-LIC-001": "DEFERRED",
}
REQUIRED_DOCS = {
    "README.md",
    "01_范围决策与逐项准入.md",
    "02_数据主权状态与不变量.md",
    "03_权限审计API与事件.md",
    "04_Flyway容量兼容与回退.md",
    "05_测试矩阵CI与证据.md",
}
REQUIRED_CHECKS = {
    "source", "scope", "nonGoals", "owner", "invariants", "permissions",
    "audit", "api", "migration", "capacity", "rollback", "tests",
}
STAGE_STATUS = {"design": "DRAFT", "implementation": "IN_PROGRESS", "closure": "VERIFIED"}
FORBIDDEN_DOMAINS = {
    "order", "payment", "refund", "inventory", "procurement", "cost", "promotion",
}
RUNTIME_ROOTS = (
    ROOT / "server" / "ruoyi-modules" / "jshpos-catalog" / "src" / "main",
    ROOT / "admin-web" / "src" / "api" / "catalog",
    ROOT / "admin-web" / "src" / "views" / "catalog",
)


def fail(message: str) -> None:
    print(f"T2-GATE1 ERROR: {message}", file=sys.stderr)
    raise SystemExit(1)


def git(*args: str) -> str:
    result = subprocess.run(
        ["git", *args], cwd=ROOT, capture_output=True, text=True,
        encoding="utf-8", errors="replace", check=False,
    )
    if result.returncode:
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
    return {
        "tag": BASELINE_TAG,
        "tagTarget": BASELINE_COMMIT,
        "branchStart": BRANCH_START,
        "branch": branch,
    }


def check_design_and_admission() -> dict[str, object]:
    actual = {path.name for path in DOC_DIR.glob("*.md")}
    missing = sorted(REQUIRED_DOCS - actual)
    if missing:
        fail(f"missing Gate 1 design documents: {missing}")
    corpus = "\n".join((DOC_DIR / name).read_text(encoding="utf-8") for name in sorted(REQUIRED_DOCS))
    for term in (
        "tenant_id", "SPU", "SKU", "Flyway", "最小货币单位", "DECIMAL(19,6)",
        "预检", "幂等", "原子切换", "摘要", "回退", "10k", "100k", "Ed25519",
    ):
        if term not in corpus:
            fail(f"Gate 1 design corpus missing {term!r}")
    for requirement_id in sorted(GATE1_IDS):
        if requirement_id not in corpus:
            fail(f"Gate 1 design corpus missing {requirement_id}")
    agents = (ROOT / "AGENTS.md").read_text(encoding="utf-8")
    if "## 4.9 当前 T2 Gate 1 / Sprint S1 条件准入" not in agents:
        fail("AGENTS.md is missing Gate 1 boundary")
    change_log = (ROOT / "docs" / "governance" / "change-log.md").read_text(encoding="utf-8")
    for change_id in ("CR-T2G0-004", "CR-T2G1-001"):
        if change_id not in change_log:
            fail(f"change log is missing {change_id}")
    payload = json.loads(ADMISSION.read_text(encoding="utf-8"))
    requirements = payload.get("requirements", {})
    if set(requirements) != GATE1_IDS:
        fail(f"Gate 1 admission IDs mismatch: {sorted(requirements)}")
    for requirement_id, checks in requirements.items():
        if set(checks) != REQUIRED_CHECKS or not all(checks.values()):
            fail(f"{requirement_id} has incomplete admission checks")
    expected_decisions = {f"DEC-G1-{number:02d}" for number in range(1, 11)}
    if set(payload.get("decisions", [])) != expected_decisions:
        fail("Gate 1 decisions must be DEC-G1-01..DEC-G1-10")
    external = payload.get("externalEvidence", {})
    if external != {"sandbox": 0, "realDevice": 0, "pilot": 0}:
        fail("external evidence must remain zero")
    return {"documents": len(REQUIRED_DOCS), "admitted": sorted(GATE1_IDS), "decisions": 10}


def read_rtm() -> dict[str, dict[str, str]]:
    with RTM.open(encoding="utf-8-sig", newline="") as handle:
        return {row["requirement_id"]: row for row in csv.DictReader(handle)}


def check_rtm(stage: str) -> dict[str, object]:
    rows = read_rtm()
    for requirement_id in GATE0_IDS:
        if rows.get(requirement_id, {}).get("status") != "ACCEPTED":
            fail(f"{requirement_id} must remain ACCEPTED")
    expected = STAGE_STATUS[stage]
    for requirement_id in GATE1_IDS:
        row = rows.get(requirement_id)
        if row is None or row["status"] != expected:
            fail(f"{requirement_id} must be {expected} during {stage}")
        if stage != "design" and not row["implementation"].strip():
            fail(f"{requirement_id} left DRAFT without design/implementation trace")
        if stage == "closure" and not row["test_evidence"].strip():
            fail(f"{requirement_id} is VERIFIED without test evidence")
    for requirement_id, status in EXTERNAL_STATUSES.items():
        if rows.get(requirement_id, {}).get("status") != status:
            fail(f"{requirement_id} must remain {status}")
    unexpected = [
        row["requirement_id"] for row in rows.values()
        if row["requirement_id"].startswith("T2-")
        and row["requirement_id"] not in GATE0_IDS | GATE1_IDS | set(EXTERNAL_STATUSES)
        and row["status"] not in {"DRAFT", "BLOCKED", "DEFERRED"}
    ]
    if unexpected:
        fail(f"requirements outside Gate 1 changed state: {unexpected}")
    return {
        "gate0Accepted": len(GATE0_IDS),
        "gate1Status": expected,
        "gate1Count": len(GATE1_IDS),
        "externalEvidenceStatuses": EXTERNAL_STATUSES,
    }


def verify_checksum_ledger(path: Path, expected_count: int) -> dict[str, str]:
    if not path.is_file():
        fail(f"migration checksum ledger missing: {path.relative_to(ROOT)}")
    ledger = json.loads(path.read_text(encoding="utf-8"))
    results: dict[str, str] = {}
    for item in ledger.get("files", []):
        migration = ROOT / item["path"]
        if not migration.is_file():
            fail(f"migration checksum target missing: {item['path']}")
        actual = hashlib.sha256(migration.read_bytes()).hexdigest()
        if actual != item["sha256"]:
            fail(f"published migration changed: {item['path']}")
        results[item["path"]] = actual
    if len(results) != expected_count:
        fail(f"{path.relative_to(ROOT)} must seal exactly {expected_count} migration files")
    return results


def check_migrations(stage: str) -> dict[str, dict[str, str]]:
    result = {"gate0": verify_checksum_ledger(GATE0_MIGRATIONS, 2)}
    if stage != "design":
        result["gate1"] = verify_checksum_ledger(GATE1_MIGRATIONS, 2)
    elif GATE1_MIGRATIONS.exists():
        fail("Gate 1 migration ledger must not exist before design admission")
    return result


def check_runtime(stage: str) -> dict[str, object]:
    catalog_root = RUNTIME_ROOTS[0]
    if stage == "design" and catalog_root.exists():
        fail("formal Gate 1 runtime code exists before design admission")
    if stage != "design" and not catalog_root.exists():
        fail("formal Gate 1 catalog module is missing")
    violations: list[str] = []
    for root in RUNTIME_ROOTS:
        if not root.exists():
            continue
        for path in root.rglob("*"):
            if not path.is_file():
                continue
            relative = path.relative_to(root)
            tokens = {part.lower() for part in relative.parts}
            tokens |= set(re.split(r"[^a-z]+", path.stem.lower()))
            if tokens & FORBIDDEN_DOMAINS:
                violations.append(path.relative_to(ROOT).as_posix())
    if violations:
        fail(f"forbidden later-domain runtime code found: {violations}")
    return {"catalogRuntimePresent": catalog_root.exists(), "forbiddenViolations": violations}


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--stage", choices=sorted(STAGE_STATUS), default="design")
    parser.add_argument("--output", type=Path)
    args = parser.parse_args()
    evidence = {
        "schemaVersion": "1.0",
        "phase": "T2-GATE1-S1",
        "stage": args.stage,
        "evidenceLevel": "STATIC",
        "generatedAt": datetime.now(timezone.utc).isoformat().replace("+00:00", "Z"),
        "commitSha": git("rev-parse", "HEAD"),
        "baseline": check_baseline(),
        "design": check_design_and_admission(),
        "requirements": check_rtm(args.stage),
        "migrations": check_migrations(args.stage),
        "runtime": check_runtime(args.stage),
        "externalEvidence": {"sandbox": 0, "realDevice": 0, "pilot": 0},
    }
    if args.output:
        output = args.output if args.output.is_absolute() else ROOT / args.output
        output.parent.mkdir(parents=True, exist_ok=True)
        output.write_text(json.dumps(evidence, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(
        f"T2-GATE1 OK: stage={args.stage} baseline=sealed admission=7/7 "
        f"gate0=ACCEPTED gate1={STAGE_STATUS[args.stage]} forbidden-runtime=0 external=0"
    )


if __name__ == "__main__":
    main()
