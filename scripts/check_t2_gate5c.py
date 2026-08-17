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
BRANCH_START = "2b55ad6154b75bce1ff19c68a50e025afe7f1e93"
SEQUENCE = ("T2-MEM-001", "T2-MEM-002")
PRIOR_ACCEPTED = {
    "T2-IAM-001", "T2-ORG-001", "T2-RBAC-001", "T2-CFG-001", "T2-AUD-001", "T2-SEC-001",
    "T2-OBS-001", "T2-MIG-001", "T2-PRD-001", "T2-PRD-002", "T2-PRD-003", "T2-PRD-004",
    "T2-PRC-001", "T2-PRC-002", "T2-DPK-001", "T2-POS-001", "T2-POS-002", "T2-POS-003",
    "T2-POS-004", "T2-POS-005", "T2-ORD-001", "T2-ORD-002", "T2-OFF-001", "T2-SYN-001",
    "T2-PAY-001", "T2-PAY-003", "T2-REF-001", "T2-REC-001", "T2-INV-001", "T2-INV-002",
    "T2-INV-003", "T2-INV-004", "T2-PUR-001", "T2-CST-001", "T2-TRF-001",
    "T2-PRM-001", "T2-PRM-002", "T2-PRM-003", "T2-POS-006", "T2-ORD-003", "T2-REF-002",
}
EXTERNAL = {"T2-HWD-001": "BLOCKED", "T2-PAY-002": "BLOCKED", "T2-PAR-001": "BLOCKED",
            "T2-JSH-001": "DEFERRED", "T2-LIC-001": "DEFERRED"}
REPORT_DRAFT = ("T2-RPT-001", "T2-RPT-002")
STAGES = {
    "design": {"T2-MEM-001": "IN_PROGRESS", "T2-MEM-002": "DRAFT"},
    "mem1": {"T2-MEM-001": "VERIFIED", "T2-MEM-002": "DRAFT"},
    "mem2": {"T2-MEM-001": "VERIFIED", "T2-MEM-002": "VERIFIED"},
    "closure": {"T2-MEM-001": "VERIFIED", "T2-MEM-002": "VERIFIED"},
}
DESIGN_FILES = (
    "docs/adr/ADR-030-gate5c-member-privacy-points.md",
    "docs/t2-gate5c/01_范围非目标与顺序准入.md",
    "docs/t2-gate5c/02_会员数据主权状态不变量与隐私.md",
    "docs/t2-gate5c/03_权限审计API事件与Owner端口.md",
    "docs/t2-gate5c/04_迁移容量兼容回退与修复.md",
    "docs/t2-gate5c/05_测试矩阵CI与证据规范.md",
    "docs/t2-gate5c/06_报表DRAFT设计准备.md",
    "contracts/t2/gate5c/gate5c-admission.json",
    "contracts/t2/gate5c/persistence-registry.csv",
    "contracts/t2/gate5c/openapi-member-v1.yaml",
    "contracts/t2/gate5c/member-events-v1.yaml",
    "contracts/t2/gate5c/migration-checksums.json",
)


def fail(message: str) -> None:
    raise SystemExit(f"T2-GATE5C ERROR: {message}")


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
        fail("prior accepted Gate requirements changed")
    for item, status in STAGES[stage].items():
        if rtm[item]["status"] != status:
            fail(f"{item} expected {status}, got {rtm[item]['status']}")
    for item in REPORT_DRAFT:
        if rtm[item]["status"] != "DRAFT":
            fail(f"design-only {item} must remain DRAFT")
    for item, status in EXTERNAL.items():
        if rtm[item]["status"] != status:
            fail(f"external boundary changed {item}")
    return {"priorAccepted": len(PRIOR_ACCEPTED), "gate5c": STAGES[stage],
            "reportingDraft": list(REPORT_DRAFT), "paymentSandbox": "BLOCKED"}


def contracts(stage: str) -> dict[str, object]:
    content = {name: required(name) for name in DESIGN_FILES}
    admission = json.loads(content["contracts/t2/gate5c/gate5c-admission.json"])
    if admission.get("sequence") != list(SEQUENCE):
        fail("Gate 5C sequence changed")
    if admission.get("requirements", {}).get("T2-PAY-002") != "BLOCKED":
        fail("payment sandbox boundary changed")
    forbidden = set(admission.get("forbidden", []))
    required_forbidden = {"REAL_PII", "PROVIDER_NETWORK", "PRODUCTION_KEY", "REPORTING_RUNTIME"}
    if not required_forbidden.issubset(forbidden):
        fail("privacy/network/reporting forbidden boundary incomplete")
    if stage == "design" and admission.get("activeRequirement") != "T2-MEM-001":
        fail("design stage must activate MEM-001 only")
    openapi = content["contracts/t2/gate5c/openapi-member-v1.yaml"]
    for marker in ("member:profile:create", "member:profile:read", "member:identity:bind",
                   "member:consent:record", "member:privacy:request"):
        if marker not in openapi:
            fail(f"Member OpenAPI permission missing {marker}")
    events = content["contracts/t2/gate5c/member-events-v1.yaml"]
    for marker in ("member.profile.changed.v1", "member.consent.changed.v1", "pii: FORBIDDEN"):
        if marker not in events:
            fail(f"Member event contract missing {marker}")
    if stage in {"mem2", "closure"}:
        for marker in ("member:points:read", "member:points:freeze", "member:points:settle",
                       "member:points:adjust", "member:level:manage", "member:points:rebuild"):
            if marker not in openapi:
                fail(f"Member points OpenAPI permission missing {marker}")
        for marker in ("member.level.changed.v1", "member.points.posted.v1", "status: ACTIVE"):
            if marker not in events:
                fail(f"Member points event contract missing {marker}")
    checksum = json.loads(content["contracts/t2/gate5c/migration-checksums.json"])
    expected_count = 5 if stage in {"mem2", "closure"} else 3
    if len(checksum.get("files", [])) != expected_count:
        fail(f"migration checksum ledger expected {expected_count} files")
    for item in checksum["files"]:
        path = ROOT / item["path"]
        if not path.is_file() or hashlib.sha256(path.read_bytes()).hexdigest() != item["sha256"]:
            fail(f"published migration checksum mismatch {item['path']}")
    return {"designFiles": len(content), "sequence": list(SEQUENCE), "providerNetworkCalls": 0}


def scope() -> dict[str, int]:
    member_root = ROOT / "server/ruoyi-modules/jshpos-member/src/main/java"
    text = "\n".join(path.read_text(encoding="utf-8", errors="replace")
                       for path in member_root.rglob("*.java")) if member_root.exists() else ""
    lowered = text.lower()
    for token in ("java.net.http", "resttemplate", "webclient", "okhttp", "hutool.http",
                  "httpurlconnection", "https://", "http://"):
        if token in lowered:
            fail(f"Provider/network client detected {token}")
    if (ROOT / "server/ruoyi-modules/jshpos-reporting").exists():
        fail("reporting runtime module is forbidden in Gate 5C")
    return {"providerNetworkCalls": 0, "reportingRuntime": 0, "realPii": 0}


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--stage", choices=tuple(STAGES), default="design")
    parser.add_argument("--output", type=Path)
    args = parser.parse_args()
    report = {
        "schemaVersion": "1.0", "phase": "T2-GATE5C", "stage": args.stage,
        "generatedAt": datetime.now(timezone.utc).isoformat().replace("+00:00", "Z"),
        "commitSha": git("rev-parse", "HEAD"), "ancestry": ancestry(),
        "requirements": requirements(args.stage), "contracts": contracts(args.stage),
        "scope": scope(), "externalEvidence": {"sandbox": 0, "realDevice": 0, "pilot": 0},
    }
    if args.output:
        output = args.output if args.output.is_absolute() else ROOT / args.output
        output.parent.mkdir(parents=True, exist_ok=True)
        output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(f"T2-GATE5C OK: stage={args.stage} sequence={','.join(SEQUENCE)} providerNetwork=0 realPii=0")


if __name__ == "__main__":
    main()
