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
BRANCH_START = "02e1854a9b2a63a46323f27457bd61a708f740a9"
GATE4C_ID = "T2-CST-001"
DESIGN_ONLY = "T2-TRF-001"
PRIOR_ACCEPTED = {
    "T2-IAM-001", "T2-ORG-001", "T2-RBAC-001", "T2-CFG-001", "T2-AUD-001", "T2-SEC-001",
    "T2-OBS-001", "T2-MIG-001", "T2-PRD-001", "T2-PRD-002", "T2-PRD-003", "T2-PRD-004",
    "T2-PRC-001", "T2-PRC-002", "T2-DPK-001", "T2-POS-001", "T2-POS-002", "T2-POS-003",
    "T2-POS-004", "T2-POS-005", "T2-ORD-001", "T2-ORD-002", "T2-OFF-001", "T2-SYN-001",
    "T2-PAY-001", "T2-PAY-003", "T2-REF-001", "T2-REC-001", "T2-INV-001", "T2-INV-002",
    "T2-INV-003", "T2-INV-004", "T2-PUR-001",
}
EXTERNAL = {
    "T2-HWD-001": "BLOCKED", "T2-PAY-002": "BLOCKED", "T2-PAR-001": "BLOCKED",
    "T2-JSH-001": "DEFERRED", "T2-LIC-001": "DEFERRED",
}
STAGE_STATUS = {"admitted": "IN_PROGRESS", "closure": "VERIFIED"}
DESIGN_FILES = [
    "docs/adr/ADR-025-gate4c-moving-average-cost-ledger.md",
    "docs/t2-gate4c/01_范围非目标与逐项准入.md",
    "docs/t2-gate4c/02_数据主权状态不变量与成本策略.md",
    "docs/t2-gate4c/03_权限审计API事件与跨模块契约.md",
    "docs/t2-gate4c/04_Flyway容量兼容与回退.md",
    "docs/t2-gate4c/05_测试矩阵CI与证据.md",
    "contracts/t2/gate4c/gate4c-admission.json",
    "contracts/t2/gate4c/openapi-costing-v1.yaml",
    "contracts/t2/gate4c/schemas/inventory.cost-changed.v1.schema.json",
    "contracts/t2/gate4c/schemas/transfer-design.v2.schema.json",
    "contracts/t2/gate4c/test-vectors/costing-fixed-vectors-v1.json",
]
LEDGER = ROOT / "contracts/t2/gate4c/migration-checksums.json"


def fail(message: str) -> None:
    raise SystemExit(f"T2-GATE4C ERROR: {message}")


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
    for revision in (BASELINE_TAG, "9557112a4eb573ec4a54ef30be477f1ab8f09d31", BRANCH_START):
        if subprocess.run(["git", "merge-base", "--is-ancestor", revision, "HEAD"], cwd=ROOT).returncode:
            fail(f"required ancestor missing: {revision}")
    return {"baselineTag": BASELINE_TAG, "peeledCommit": peeled, "branchStart": BRANCH_START}


def check_design() -> dict[str, object]:
    contents = {path: require_file(path) for path in DESIGN_FILES}
    joined = "\n".join(contents.values())
    if "状态：Accepted" not in contents["docs/adr/ADR-025-gate4c-moving-average-cost-ledger.md"]:
        fail("ADR-025 must be Accepted")
    for requirement in (GATE4C_ID, DESIGN_ONLY, "T2-PAY-002"):
        if requirement not in joined:
            fail(f"design does not map {requirement}")
    for dimension in ("数据主权", "状态", "不变量", "权限", "审计", "API", "事件", "Flyway", "容量", "回退", "CI"):
        if dimension not in joined:
            fail(f"admission dimension missing: {dimension}")
    admission = json.loads(contents["contracts/t2/gate4c/gate4c-admission.json"])
    if admission.get("admittedRequirements") != [GATE4C_ID]:
        fail("Gate 4C admitted set changed")
    if admission.get("designOnlyRequirements") != [DESIGN_ONLY]:
        fail("Gate 4C design-only set changed")
    policy = admission.get("costPolicy", {})
    expected = {"scope": "WAREHOUSE", "currency": "CNY", "quantityScale": 6, "costScale": 6,
                "costUnit": "MINOR_CURRENCY_UNIT", "roundingMode": "HALF_EVEN",
                "zeroQuantityMode": "ZERO_AMOUNT_KEEP_LAST_UNIT_COST"}
    if policy != expected:
        fail("cost policy changed without ADR")
    boundary = admission.get("runtimeBoundary", {})
    if boundary.get("providerNetworkCallsAllowed") != 0 or not boundary.get("costRuntimeAllowed") \
            or boundary.get("transferRuntimeAllowed") or boundary.get("promotionRuntimeAllowed") \
            or boundary.get("accountsPayableRuntimeAllowed") or boundary.get("generalLedgerRuntimeAllowed"):
        fail("Gate 4C runtime boundary was relaxed")
    vectors = json.loads(contents["contracts/t2/gate4c/test-vectors/costing-fixed-vectors-v1.json"])
    if len(vectors.get("fixedVectors", [])) != 24:
        fail("Gate 4C fixed vector ledger is incomplete")
    if json.loads(contents["contracts/t2/gate4c/schemas/transfer-design.v2.schema.json"]).get("x-runtime-allowed"):
        fail("transfer runtime must remain disabled")
    return {"files": len(contents), "fixedVectors": 24, "providerNetworkCallsAllowed": 0}


def read_rtm() -> dict[str, dict[str, str]]:
    with (ROOT / "docs/governance/rtm.csv").open(encoding="utf-8-sig", newline="") as handle:
        return {row["requirement_id"]: row for row in csv.DictReader(handle)}


def check_requirements(stage: str) -> dict[str, object]:
    rows = read_rtm()
    for requirement in sorted(PRIOR_ACCEPTED):
        if rows.get(requirement, {}).get("status") != "ACCEPTED":
            fail(f"prior requirement {requirement} must be ACCEPTED")
    expected = STAGE_STATUS[stage]
    if rows.get(GATE4C_ID, {}).get("status") != expected:
        fail(f"{GATE4C_ID} must be {expected} at {stage}")
    if rows.get(DESIGN_ONLY, {}).get("status") != "DRAFT":
        fail(f"{DESIGN_ONLY} must remain DRAFT")
    for requirement, status in EXTERNAL.items():
        if rows.get(requirement, {}).get("status") != status:
            fail(f"{requirement} must remain {status}")
    return {"priorAccepted": len(PRIOR_ACCEPTED), "gate4c": expected, "paymentSandbox": "BLOCKED"}


def java_text(root: Path) -> str:
    if not root.is_dir():
        return ""
    return "\n".join(path.read_text(encoding="utf-8", errors="replace") for path in sorted(root.rglob("*.java")))


def check_runtime(stage: str) -> dict[str, object]:
    costing = ROOT / "server/ruoyi-modules/jshpos-costing/src/main/java"
    text = java_text(costing)
    all_gate = "\n".join((
        text,
        java_text(ROOT / "server/ruoyi-modules/jshpos-inventory/src/main/java"),
        java_text(ROOT / "server/ruoyi-modules/jshpos-procurement/src/main/java"),
    )).lower()
    forbidden = ("java.net.http", "resttemplate", "webclient", "okhttp", "hutool.http", "httpurlconnection",
                 "transferservice", "promotionservice", "accountspayableservice", "generalledgerservice")
    violations = [token for token in forbidden if token in all_gate]
    if violations:
        fail(f"forbidden Provider/later-Gate runtime found: {violations}")
    if stage == "closure":
        for marker in ("authoritativecostpostingport", "costingservice", "inv_cost_ledger", "trustedtenantcontext",
                       "half_even", "rebuild"):
            if marker not in all_gate:
                fail(f"formal costing runtime marker missing: {marker}")
    return {"costingPresent": costing.is_dir(), "providerNetworkCalls": 0, "forbiddenRuntime": 0}


def check_migrations(stage: str) -> dict[str, object]:
    if stage != "closure":
        if LEDGER.exists():
            fail("migration checksum ledger must not be sealed before closure")
        return {"sealed": False}
    if not LEDGER.is_file():
        fail("Gate 4C migration checksum ledger missing")
    files = json.loads(LEDGER.read_text(encoding="utf-8")).get("files", [])
    if len(files) != 2:
        fail("Gate 4C must seal V16 and V17")
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
        "schemaVersion": "1.0", "phase": "T2-GATE4C", "stage": args.stage,
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
    print(f"T2-GATE4C OK: stage={args.stage} prior={len(PRIOR_ACCEPTED)} accepted "
          f"gate4c={STAGE_STATUS[args.stage]} vectors=24 paymentNetwork=0 external=0")


if __name__ == "__main__":
    main()

