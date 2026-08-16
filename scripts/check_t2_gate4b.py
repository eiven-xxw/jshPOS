from __future__ import annotations

import argparse
import csv
import hashlib
import json
import subprocess
from datetime import datetime, timezone
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
BASELINE_TAG = "t2-prep-baseline-2026-08-16"
BASELINE_COMMIT = "557ba270479935d6b44968cf70b47033f7d3d656"
BRANCH_START = "9557112a4eb573ec4a54ef30be477f1ab8f09d31"
GATE4B_IDS = {"T2-INV-003", "T2-PUR-001"}
DESIGN_ONLY = {"T2-CST-001", "T2-TRF-001"}
PRIOR_ACCEPTED = {
    "T2-IAM-001", "T2-ORG-001", "T2-RBAC-001", "T2-CFG-001", "T2-AUD-001", "T2-SEC-001",
    "T2-OBS-001", "T2-MIG-001", "T2-PRD-001", "T2-PRD-002", "T2-PRD-003", "T2-PRD-004",
    "T2-PRC-001", "T2-PRC-002", "T2-DPK-001", "T2-POS-001", "T2-POS-002", "T2-POS-003",
    "T2-POS-004", "T2-POS-005", "T2-ORD-001", "T2-ORD-002", "T2-OFF-001", "T2-SYN-001",
    "T2-PAY-001", "T2-PAY-003", "T2-REF-001", "T2-REC-001", "T2-INV-001", "T2-INV-002",
    "T2-INV-004",
}
EXTERNAL = {"T2-HWD-001": "BLOCKED", "T2-PAY-002": "BLOCKED", "T2-PAR-001": "BLOCKED",
            "T2-JSH-001": "DEFERRED", "T2-LIC-001": "DEFERRED"}
STAGE_STATUS = {"admitted": "IN_PROGRESS", "closure": "VERIFIED"}
DESIGN_FILES = [
    "docs/adr/ADR-024-gate4b-stocktake-procurement-boundaries.md",
    "docs/t2-gate4b/01_范围非目标与逐项准入.md",
    "docs/t2-gate4b/02_数据主权状态机与不变量.md",
    "docs/t2-gate4b/03_权限审计API事件与跨模块契约.md",
    "docs/t2-gate4b/04_Flyway容量兼容与回退.md",
    "docs/t2-gate4b/05_测试矩阵CI与证据.md",
    "contracts/t2/gate4b/gate4b-admission.json",
    "contracts/t2/gate4b/openapi-stocktake-procurement-v1.yaml",
    "contracts/t2/gate4b/schemas/stocktake-posted.v1.schema.json",
    "contracts/t2/gate4b/schemas/purchase-receipt-confirmed.v1.schema.json",
    "contracts/t2/gate4b/schemas/cost-transfer-design.v1.schema.json",
    "contracts/t2/gate4b/test-vectors/gate4b-fixed-vectors-v1.json",
]
LEDGER = ROOT / "contracts/t2/gate4b/migration-checksums.json"


def fail(message: str) -> None:
    raise SystemExit(f"T2-GATE4B ERROR: {message}")


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
    for revision in (BASELINE_TAG, "451a48f982d3a88c68ff20ca283a190e7bf53ccf", BRANCH_START):
        if subprocess.run(["git", "merge-base", "--is-ancestor", revision, "HEAD"], cwd=ROOT).returncode:
            fail(f"required ancestor missing: {revision}")
    return {"baselineTag": BASELINE_TAG, "peeledCommit": peeled, "branchStart": BRANCH_START}


def check_design() -> dict[str, object]:
    contents = {path: require_file(path) for path in DESIGN_FILES}
    joined = "\n".join(contents.values())
    if "状态：Accepted" not in contents["docs/adr/ADR-024-gate4b-stocktake-procurement-boundaries.md"]:
        fail("ADR-024 must be Accepted")
    for requirement in sorted(GATE4B_IDS | DESIGN_ONLY | {"T2-PAY-002"}):
        if requirement not in joined:
            fail(f"design does not map {requirement}")
    for dimension in ("数据主权", "状态", "不变量", "权限", "审计", "API", "事件", "Flyway", "容量", "回退", "CI"):
        if dimension not in joined:
            fail(f"admission dimension missing: {dimension}")
    admission = json.loads(contents["contracts/t2/gate4b/gate4b-admission.json"])
    if set(admission.get("admittedRequirements", [])) != GATE4B_IDS:
        fail("Gate 4B admitted set changed")
    if set(admission.get("designOnlyRequirements", [])) != DESIGN_ONLY:
        fail("Gate 4B design-only set changed")
    boundary = admission.get("runtimeBoundary", {})
    if boundary.get("providerNetworkCallsAllowed") != 0 or boundary.get("costRuntimeAllowed") \
            or boundary.get("transferRuntimeAllowed") or boundary.get("promotionRuntimeAllowed"):
        fail("Gate 4B runtime boundary was relaxed")
    vectors = json.loads(contents["contracts/t2/gate4b/test-vectors/gate4b-fixed-vectors-v1.json"])
    if len(vectors.get("fixedVectors", [])) != 20:
        fail("Gate 4B fixed vector ledger is incomplete")
    return {"files": len(contents), "fixedVectors": 20, "providerNetworkCallsAllowed": 0}


def read_rtm() -> dict[str, dict[str, str]]:
    with (ROOT / "docs/governance/rtm.csv").open(encoding="utf-8-sig", newline="") as handle:
        return {row["requirement_id"]: row for row in csv.DictReader(handle)}


def check_requirements(stage: str) -> dict[str, object]:
    rows = read_rtm()
    for requirement in sorted(PRIOR_ACCEPTED):
        if rows.get(requirement, {}).get("status") != "ACCEPTED":
            fail(f"prior requirement {requirement} must be ACCEPTED")
    expected = STAGE_STATUS[stage]
    for requirement in sorted(GATE4B_IDS):
        if rows.get(requirement, {}).get("status") != expected:
            fail(f"{requirement} must be {expected} at {stage}")
    for requirement in sorted(DESIGN_ONLY):
        if rows.get(requirement, {}).get("status") != "DRAFT":
            fail(f"{requirement} must remain DRAFT")
    for requirement, status in EXTERNAL.items():
        if rows.get(requirement, {}).get("status") != status:
            fail(f"{requirement} must remain {status}")
    return {"priorAccepted": len(PRIOR_ACCEPTED), "gate4b": expected, "paymentSandbox": "BLOCKED"}


def check_runtime(stage: str) -> dict[str, object]:
    inventory = ROOT / "server/ruoyi-modules/jshpos-inventory/src/main/java"
    procurement = ROOT / "server/ruoyi-modules/jshpos-procurement/src/main/java"
    text = "\n".join(path.read_text(encoding="utf-8", errors="replace")
                       for root in (inventory, procurement) if root.is_dir() for path in sorted(root.rglob("*.java")))
    lowered = text.lower()
    forbidden = ("java.net.http", "resttemplate", "webclient", "okhttp", "hutool.http", "httpurlconnection",
                 "costbalanceservice", "costledgerservice", "transferservice", "promotionservice")
    violations = [token for token in forbidden if token in lowered]
    if violations:
        fail(f"forbidden Provider/later-Gate runtime found: {violations}")
    if stage == "closure":
        for marker in ("stocktake_gain", "stocktake_loss", "purchase_receipt_in", "purchase_return_out",
                       "authoritativeinventorymovementport", "trustedtenantcontext"):
            if marker not in lowered:
                fail(f"formal runtime marker missing: {marker}")
    return {"stocktakePresent": "stocktakeservice" in lowered,
            "procurementPresent": procurement.is_dir(), "providerNetworkCalls": 0, "forbiddenRuntime": 0}


def check_migrations(stage: str) -> dict[str, object]:
    if stage != "closure":
        if LEDGER.exists():
            fail("migration checksum ledger must not be sealed before closure")
        return {"sealed": False}
    if not LEDGER.is_file():
        fail("Gate 4B migration checksum ledger missing")
    files = json.loads(LEDGER.read_text(encoding="utf-8")).get("files", [])
    if len(files) != 3:
        fail("Gate 4B must seal V13 through V15")
    for item in files:
        path = ROOT / item["path"]
        if not path.is_file() or hashlib.sha256(path.read_bytes()).hexdigest() != item["sha256"]:
            fail(f"sealed migration changed: {item['path']}")
    return {"sealed": True, "files": len(files)}


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--stage", choices=sorted(STAGE_STATUS), default="admitted")
    parser.add_argument("--output", type=Path)
    args = parser.parse_args()
    evidence = {
        "schemaVersion": "1.0", "phase": "T2-GATE4B", "stage": args.stage,
        "generatedAt": datetime.now(timezone.utc).isoformat().replace("+00:00", "Z"),
        "commitSha": git("rev-parse", "HEAD"), "ancestry": check_ancestry(),
        "design": check_design(), "requirements": check_requirements(args.stage),
        "runtime": check_runtime(args.stage), "migrations": check_migrations(args.stage),
        "externalEvidence": {"sandbox": 0, "realDevice": 0, "pilot": 0},
    }
    if args.output:
        target = args.output if args.output.is_absolute() else ROOT / args.output
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(json.dumps(evidence, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(f"T2-GATE4B OK: stage={args.stage} prior={len(PRIOR_ACCEPTED)} accepted "
          f"gate4b={STAGE_STATUS[args.stage]} vectors=20 paymentNetwork=0 external=0")


if __name__ == "__main__":
    main()
