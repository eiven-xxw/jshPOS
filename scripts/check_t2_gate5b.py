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
BRANCH_START = "e947a229782865f7759525cfa3e2e90819ebfba5"
SEQUENCE = ("T2-POS-006", "T2-ORD-003", "T2-REF-002")
PRIOR_ACCEPTED = {
    "T2-IAM-001", "T2-ORG-001", "T2-RBAC-001", "T2-CFG-001", "T2-AUD-001", "T2-SEC-001",
    "T2-OBS-001", "T2-MIG-001", "T2-PRD-001", "T2-PRD-002", "T2-PRD-003", "T2-PRD-004",
    "T2-PRC-001", "T2-PRC-002", "T2-DPK-001", "T2-POS-001", "T2-POS-002", "T2-POS-003",
    "T2-POS-004", "T2-POS-005", "T2-ORD-001", "T2-ORD-002", "T2-OFF-001", "T2-SYN-001",
    "T2-PAY-001", "T2-PAY-003", "T2-REF-001", "T2-REC-001", "T2-INV-001", "T2-INV-002",
    "T2-INV-003", "T2-INV-004", "T2-PUR-001", "T2-CST-001", "T2-TRF-001",
    "T2-PRM-001", "T2-PRM-002", "T2-PRM-003",
}
EXTERNAL = {"T2-HWD-001": "BLOCKED", "T2-PAY-002": "BLOCKED", "T2-PAR-001": "BLOCKED",
            "T2-JSH-001": "DEFERRED", "T2-LIC-001": "DEFERRED"}
DESIGN_ONLY = ("T2-MEM-001", "T2-MEM-002", "T2-RPT-001", "T2-RPT-002")
STAGES = {
    "ref": {"T2-POS-006": "VERIFIED", "T2-ORD-003": "VERIFIED", "T2-REF-002": "IN_PROGRESS"},
    "closure": {item: "VERIFIED" for item in SEQUENCE},
}
DESIGN_FILES = (
    "docs/adr/ADR-029-gate5b-sale-refund-owner-orchestration.md",
    "docs/t2-gate5b/01_范围非目标与顺序准入.md",
    "docs/t2-gate5b/02_数据主权状态冻结事务与不变量.md",
    "docs/t2-gate5b/03_权限审计API事件与Owner端口.md",
    "docs/t2-gate5b/04_迁移容量兼容回退与修复.md",
    "docs/t2-gate5b/05_测试矩阵CI与证据规范.md",
    "docs/t2-gate5b/06_会员与报表DRAFT设计准备.md",
    "contracts/t2/gate5b/gate5b-admission.json",
    "contracts/t2/gate5b/persistence-registry.csv",
    "contracts/t2/gate5b/openapi-returns-v1.yaml",
    "contracts/t2/gate5b/return-owner-events-v1.yaml",
    "contracts/t2/gate5b/schemas/promoted-cash-settlement.v1.schema.json",
    "contracts/t2/gate5b/events/order.submitted.v2.schema.json",
    "contracts/t2/gate5b/events/order.completed.v2.schema.json",
    "contracts/t2/gate5b/schemas/return-owner-envelope.v1.schema.json",
    "contracts/t2/gate5b/test-vectors/settlement-order-vectors-v1.json",
    "contracts/t2/gate5b/migration-checksums.json",
)


def fail(message: str) -> None:
    raise SystemExit(f"T2-GATE5B ERROR: {message}")


def git(*args: str) -> str:
    result = subprocess.run(["git", *args], cwd=ROOT, text=True, capture_output=True)
    if result.returncode:
        fail(result.stderr.strip() or f"git {' '.join(args)} failed")
    return result.stdout.strip()


def required(relative: str) -> str:
    path = ROOT / relative
    if not path.is_file():
        fail(f"missing {relative}")
    text = path.read_text(encoding="utf-8")
    if not text.strip():
        fail(f"empty {relative}")
    return text


def rows() -> dict[str, dict[str, str]]:
    with (ROOT / "docs/governance/rtm.csv").open(encoding="utf-8-sig", newline="") as handle:
        return {row["requirement_id"]: row for row in csv.DictReader(handle)}


def ancestry() -> dict[str, str]:
    peeled = git("rev-list", "-n", "1", BASELINE_TAG)
    if peeled != BASELINE_COMMIT:
        fail(f"baseline tag moved to {peeled}")
    for revision in (BASELINE_TAG, BRANCH_START):
        if subprocess.run(["git", "merge-base", "--is-ancestor", revision, "HEAD"], cwd=ROOT).returncode:
            fail(f"required ancestor missing {revision}")
    return {"baselineTag": BASELINE_TAG, "peeledCommit": peeled, "branchStart": BRANCH_START}


def requirements(stage: str) -> dict[str, object]:
    rtm = rows()
    if any(rtm[item]["status"] != "ACCEPTED" for item in PRIOR_ACCEPTED):
        fail("prior Gate requirements or accepted Gate 5A status changed")
    for item, status in STAGES[stage].items():
        if rtm[item]["status"] != status:
            fail(f"{item} expected {status}, got {rtm[item]['status']}")
        for field in ("source", "acceptance", "implementation", "owner"):
            if not rtm[item].get(field, "").strip():
                fail(f"{item} missing {field}")
    for item in DESIGN_ONLY:
        if rtm[item]["status"] != "DRAFT":
            fail(f"design-only {item} must remain DRAFT")
    for item, status in EXTERNAL.items():
        if rtm[item]["status"] != status:
            fail(f"external boundary changed {item}")
    return {"priorAccepted": len(PRIOR_ACCEPTED), "gate5b": STAGES[stage],
            "designOnlyDraft": list(DESIGN_ONLY), "paymentSandbox": "BLOCKED"}


def contracts(stage: str) -> dict[str, object]:
    content = {name: required(name) for name in DESIGN_FILES}
    admission = json.loads(content["contracts/t2/gate5b/gate5b-admission.json"])
    boundary = admission.get("runtimeBoundary", {})
    if (admission.get("sequence") != list(SEQUENCE)
            or boundary.get("providerSdkAllowed") is not False
            or boundary.get("providerHttpAllowed") is not False
            or boundary.get("providerNetworkCallsAllowed") != 0):
        fail("admission sequence or Provider boundary changed")
    if stage == "closure" and admission.get("verifiedRequirements") != list(SEQUENCE):
        fail("closure admission does not record all three VERIFIED requirements in order")
    openapi = content["contracts/t2/gate5b/openapi-returns-v1.yaml"]
    for marker in ("return:request:create", "return:request:approve", "return:request:read"):
        if marker not in openapi:
            fail(f"Return OpenAPI permission missing {marker}")
    events = content["contracts/t2/gate5b/return-owner-events-v1.yaml"]
    for marker in ("return.promotion.allocate.requested.v1", "return.cash.refund.requested.v1",
                   "return.payment.refund.requested.v1", "return.inventory.receipt.requested.v1",
                   "regenerateBusinessCommand: FORBIDDEN"):
        if marker not in events:
            fail(f"Return event contract missing {marker}")
    return {"designFiles": len(content), "sequence": list(SEQUENCE), "providerNetworkCalls": 0}


def checksums() -> dict[str, object]:
    ledger = json.loads(required("contracts/t2/gate5b/migration-checksums.json"))
    checked = []
    for item in ledger.get("files", []):
        path = ROOT / item["path"]
        if not path.is_file():
            fail(f"migration checksum target missing {item['path']}")
        digest = hashlib.sha256(path.read_bytes()).hexdigest()
        if digest != item["sha256"]:
            fail(f"sealed migration changed {item['path']}")
        checked.append(item["path"])
    if len(checked) != 5:
        fail("expected five Gate 5B sealed migration/schema inputs")
    return {"files": len(checked), "policy": ledger.get("immutabilityPolicy")}


def scope() -> dict[str, object]:
    roots = [ROOT / "server/ruoyi-modules/jshpos-returns/src/main/java",
             ROOT / "server/ruoyi-modules/jshpos-order/src/main/java",
             ROOT / "server/ruoyi-modules/jshpos-payment/src/main/java"]
    text = "\n".join(path.read_text(encoding="utf-8", errors="replace")
                       for root in roots for path in root.rglob("*.java")).lower()
    network = ("java.net.http", "resttemplate", "webclient", "okhttp", "hutool.http",
               "httpurlconnection", "https://", "http://")
    if any(token in text for token in network):
        fail("Provider network client, URL or SDK detected")
    for forbidden in ("couponservice", "memberservice", "loyaltyservice", "storedvalueservice",
                      "reportservice", "budgetreservationservice", "accountspayableservice", "generalledgerservice"):
        if forbidden in text:
            fail(f"later-Gate runtime detected {forbidden}")
    return {"providerNetworkCalls": 0, "forbiddenLaterRuntime": 0}


def workflow(stage: str) -> dict[str, object]:
    if stage != "closure":
        return {"required": False}
    text = required(".github/workflows/t2-gate5b.yml")
    for marker in ("governance:", "server:", "mysql-migration:", "tenant-security:",
                   "cross-runtime-vectors:", "pos-linux:", "pos-windows:", "admin-web:",
                   "security-sbom-license:", "evidence:", "ReturnsMigrationMySqlIT",
                   "check_flutter_gate5b_coverage.py", "build_t2_gate5b_evidence.py"):
        if marker not in text:
            fail(f"Gate 5B workflow marker missing {marker}")
    if "continue-on-error: true" in text or "--rerun-failures" in text:
        fail("workflow weakens failures or hides flaky tests")
    return {"jobs": 10, "artifactPolicy": "NO_DUPLICATE_FULL_BUNDLE"}


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--stage", choices=tuple(STAGES), default="closure")
    parser.add_argument("--output", type=Path)
    args = parser.parse_args()
    report = {
        "schemaVersion": "1.0", "phase": "T2-GATE5B", "stage": args.stage,
        "generatedAt": datetime.now(timezone.utc).isoformat().replace("+00:00", "Z"),
        "commitSha": git("rev-parse", "HEAD"), "ancestry": ancestry(),
        "requirements": requirements(args.stage), "contracts": contracts(args.stage),
        "migrationChecksums": checksums(), "scope": scope(), "workflow": workflow(args.stage),
        "externalEvidence": {"sandbox": 0, "realDevice": 0, "pilot": 0},
    }
    if args.output:
        output = args.output if args.output.is_absolute() else ROOT / args.output
        output.parent.mkdir(parents=True, exist_ok=True)
        output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(f"T2-GATE5B OK: stage={args.stage} sequence={','.join(SEQUENCE)} providerNetwork=0")


if __name__ == "__main__":
    main()
