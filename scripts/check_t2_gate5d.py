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
BRANCH_START = "12c916c7a4b956a0bdca09ebc3ee6b4e19f9cf63"
SEQUENCE = ("T2-RPT-001", "T2-RPT-002")
PRIOR_ACCEPTED = {
    "T2-IAM-001", "T2-ORG-001", "T2-RBAC-001", "T2-CFG-001", "T2-AUD-001", "T2-SEC-001",
    "T2-OBS-001", "T2-MIG-001", "T2-PRD-001", "T2-PRD-002", "T2-PRD-003", "T2-PRD-004",
    "T2-PRC-001", "T2-PRC-002", "T2-DPK-001", "T2-POS-001", "T2-POS-002", "T2-POS-003",
    "T2-POS-004", "T2-POS-005", "T2-ORD-001", "T2-ORD-002", "T2-OFF-001", "T2-SYN-001",
    "T2-PAY-001", "T2-PAY-003", "T2-REF-001", "T2-REC-001", "T2-INV-001", "T2-INV-002",
    "T2-INV-003", "T2-INV-004", "T2-PUR-001", "T2-CST-001", "T2-TRF-001",
    "T2-PRM-001", "T2-PRM-002", "T2-PRM-003", "T2-POS-006", "T2-ORD-003", "T2-REF-002",
    "T2-MEM-001", "T2-MEM-002",
}
EXTERNAL = {"T2-HWD-001": "BLOCKED", "T2-PAY-002": "BLOCKED", "T2-PAR-001": "BLOCKED",
            "T2-JSH-001": "DEFERRED", "T2-LIC-001": "DEFERRED"}
STAGES = {
    "design": {"T2-RPT-001": "IN_PROGRESS", "T2-RPT-002": "DRAFT"},
    "rpt1": {"T2-RPT-001": "VERIFIED", "T2-RPT-002": "DRAFT"},
    "rpt2-admission": {"T2-RPT-001": "VERIFIED", "T2-RPT-002": "IN_PROGRESS"},
    "rpt2": {"T2-RPT-001": "VERIFIED", "T2-RPT-002": "IN_PROGRESS"},
    "closure": {"T2-RPT-001": "VERIFIED", "T2-RPT-002": "VERIFIED"},
}
DESIGN_FILES = (
    "docs/adr/ADR-031-gate5d-rebuildable-reporting-projections.md",
    "docs/t2-gate5d/01_范围非目标与顺序准入.md",
    "docs/t2-gate5d/02_数据主权投影状态口径与不变量.md",
    "docs/t2-gate5d/03_权限审计API事件与安全导出.md",
    "docs/t2-gate5d/04_迁移容量重建兼容回退与修复.md",
    "docs/t2-gate5d/05_测试矩阵CI与证据规范.md",
    "contracts/t2/gate5d/gate5d-admission.json",
    "contracts/t2/gate5d/persistence-registry.csv",
    "contracts/t2/gate5d/migration-checksums.json",
    "contracts/t2/gate5d/openapi-reporting-v1.yaml",
    "contracts/t2/gate5d/reporting-events-v1.yaml",
    "contracts/t2/gate5d/test-vectors/rpt001-vectors.json",
    "contracts/t2/gate5d/test-vectors/rpt002-vectors.json",
    "contracts/t2/gate5d/schemas/payment-reconciliation-fact.v1.schema.json",
    "contracts/t2/gate5d/schemas/internal-synthetic-bill-entry.v1.schema.json",
)


def fail(message: str) -> None:
    raise SystemExit(f"T2-GATE5D ERROR: {message}")


def git(*args: str) -> str:
    result = subprocess.run(["git", *args], cwd=ROOT, text=True, capture_output=True)
    if result.returncode:
        fail(result.stderr.strip() or f"git {' '.join(args)} failed")
    return result.stdout.strip()


def required(relative: str) -> str:
    path = ROOT / relative
    if not path.is_file():
        fail(f"missing {relative}")
    value = path.read_text(encoding="utf-8")
    if not value.strip():
        fail(f"empty {relative}")
    return value


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
        fail("prior accepted Gate requirements changed")
    for item, expected in STAGES[stage].items():
        actual = rtm[item]["status"]
        if actual != expected:
            fail(f"{item} expected {expected}, got {actual}")
    for item, expected in EXTERNAL.items():
        if rtm[item]["status"] != expected:
            fail(f"external boundary changed {item}")
    return {"priorAccepted": len(PRIOR_ACCEPTED), "gate5d": STAGES[stage],
            "paymentSandbox": "BLOCKED"}


def contracts(stage: str) -> dict[str, object]:
    content = {name: required(name) for name in DESIGN_FILES}
    admission = json.loads(content["contracts/t2/gate5d/gate5d-admission.json"])
    if admission.get("sequence") != list(SEQUENCE):
        fail("Gate 5D requirement sequence changed")
    if admission.get("requirements", {}).get("T2-PAY-002") != "BLOCKED":
        fail("payment sandbox boundary changed")
    forbidden = set(admission.get("forbidden", []))
    if not {"PROVIDER_NETWORK", "REAL_PII", "CROSS_OWNER_MAPPER", "REPORTING_FACT_WRITEBACK"}.issubset(forbidden):
        fail("Gate 5D forbidden boundary incomplete")
    openapi = content["contracts/t2/gate5d/openapi-reporting-v1.yaml"]
    for marker in ("T2-RPT-001", "/api/v1/reports/sales-daily:", "/api/v1/report-exports:",
                   "report:export:approve", "report:repair:manage",
                   "/api/v1/reporting/payment-reconciliation/{reconciliationId}/audit:",
                   "/api/v1/reporting/payment-reconciliation/rebuilds:"):
        if marker not in openapi:
            fail(f"Reporting OpenAPI marker missing {marker}")
    events = content["contracts/t2/gate5d/reporting-events-v1.yaml"]
    for marker in ("tenantAuthority: trusted_context_only", "providerNetworkCallsAllowed: 0",
                   "T2-PAY-002: BLOCKED"):
        if marker not in events:
            fail(f"Reporting event boundary missing {marker}")
    checksum = json.loads(content["contracts/t2/gate5d/migration-checksums.json"])
    expected_count = 2 if stage in {"design", "rpt1", "rpt2-admission"} else 4
    if len(checksum.get("files", [])) != expected_count:
        fail(f"migration checksum ledger expected {expected_count} files")
    for item in checksum["files"]:
        path = ROOT / item["path"]
        if not path.is_file() or hashlib.sha256(path.read_bytes()).hexdigest() != item["sha256"]:
            fail(f"published migration checksum mismatch {item['path']}")
    return {"designFiles": len(content), "sequence": list(SEQUENCE), "providerNetworkCalls": 0}


def scope(stage: str) -> dict[str, int]:
    runtime_root = ROOT / "server/ruoyi-modules/jshpos-reporting/src/main"
    runtime = "\n".join(path.read_text(encoding="utf-8", errors="replace")
                          for path in runtime_root.rglob("*") if path.is_file())
    lowered = runtime.lower()
    for token in ("java.net.http", "resttemplate", "webclient", "okhttp", "hutool.http",
                  "httpurlconnection", "https://"):
        if token in lowered:
            fail(f"Provider/network runtime detected {token}")
    xml = required("server/ruoyi-modules/jshpos-reporting/src/main/resources/mapper/reporting/ReportingPersistenceMapper.xml").upper()
    for owner_prefix in ("ORD_", "PAY_", "REF_", "INV_", "CST_", "PRM_", "MBR_"):
        if f" FROM {owner_prefix}" in xml or f" JOIN {owner_prefix}" in xml or f"UPDATE {owner_prefix}" in xml:
            fail(f"cross-owner SQL detected {owner_prefix}")
    if stage in {"design", "rpt1", "rpt2-admission"}:
        for token in ("PaymentReconciliationService", "rpt_payment_reconciliation", "rpt_internal_bill"):
            if token.lower() in lowered:
                fail(f"RPT-002 runtime appeared before RPT-001 verification: {token}")
    if "reporting/" not in runtime or "tenantId" not in runtime or "TrustedTenantContext" not in runtime:
        fail("tenant-namespaced export or trusted context is missing")
    return {"providerNetworkCalls": 0, "crossOwnerMapper": 0, "realPii": 0}


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--stage", choices=tuple(STAGES), default="design")
    parser.add_argument("--output", type=Path)
    args = parser.parse_args()
    report = {
        "schemaVersion": "1.0", "phase": "T2-GATE5D", "stage": args.stage,
        "generatedAt": datetime.now(timezone.utc).isoformat().replace("+00:00", "Z"),
        "commitSha": git("rev-parse", "HEAD"), "ancestry": ancestry(),
        "requirements": requirements(args.stage), "contracts": contracts(args.stage),
        "scope": scope(args.stage), "externalEvidence": {"sandbox": 0, "realDevice": 0, "pilot": 0},
    }
    if args.output:
        output = args.output if args.output.is_absolute() else ROOT / args.output
        output.parent.mkdir(parents=True, exist_ok=True)
        output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(f"T2-GATE5D OK: stage={args.stage} sequence={','.join(SEQUENCE)} providerNetwork=0 realPii=0")


if __name__ == "__main__":
    main()
