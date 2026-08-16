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
GATE_SEALS = {
    "gate0": "cf2ef29bd74c5d0f8fa5845a689305ebb56c7ef2",
    "gate1": "6a94bc6af2938fba6b9a1af123eb94b6312af9b2",
    "gate2": "968ae7be34ab144c970e5c92fb7ffbddf60bf5e1",
}
ACCEPTED_IDS = {
    "T2-IAM-001", "T2-ORG-001", "T2-RBAC-001", "T2-CFG-001",
    "T2-AUD-001", "T2-SEC-001", "T2-OBS-001", "T2-MIG-001",
    "T2-PRD-001", "T2-PRD-002", "T2-PRD-003", "T2-PRD-004",
    "T2-PRC-001", "T2-PRC-002", "T2-DPK-001",
    "T2-POS-001", "T2-POS-002", "T2-POS-003", "T2-POS-004",
    "T2-POS-005", "T2-ORD-001", "T2-ORD-002", "T2-OFF-001",
}
DESIGN_ONLY = {"T2-PAY-001", "T2-PAY-003", "T2-REF-001", "T2-REC-001"}
EXTERNAL_STATES = {
    "T2-HWD-001": "BLOCKED", "T2-PAY-002": "BLOCKED", "T2-PAR-001": "BLOCKED",
    "T2-JSH-001": "DEFERRED", "T2-LIC-001": "DEFERRED",
}
STAGE_STATUS = {"design": "DRAFT", "admitted": "IN_PROGRESS", "closure": "VERIFIED"}
DESIGN_FILES = [
    "docs/t2-sprint3/01_范围非目标与逐项准入.md",
    "docs/t2-sprint3/02_同步数据主权状态与不变量.md",
    "docs/t2-sprint3/03_API事件权限审计与冲突契约.md",
    "docs/t2-sprint3/04_迁移容量故障恢复与回退.md",
    "docs/t2-sprint3/05_Gate3支付退款对账准备.md",
    "docs/t2-sprint3/06_测试矩阵CI与证据.md",
    "docs/adr/ADR-021-sprint3-formal-pos-sync.md",
    "contracts/t2/sprint3/s3-admission.json",
    "contracts/t2/sprint3/openapi-pos-sync-v1.yaml",
    "contracts/t2/sprint3/payment-gate3-prep.yaml",
    "contracts/t2/sprint3/test-vectors/sync-failure-seeds-v1.json",
]
MIGRATION_LEDGER = ROOT / "contracts/t2/sprint3/migration-checksums.json"
SERVER_RUNTIME = ROOT / "server/ruoyi-modules/jshpos-sync"
POS_RUNTIME = ROOT / "pos-flutter/lib/features/synchronization"


def fail(message: str) -> None:
    raise SystemExit(f"T2-SPRINT3 ERROR: {message}")


def git(*args: str) -> str:
    result = subprocess.run(["git", *args], cwd=ROOT, text=True, capture_output=True)
    if result.returncode:
        fail(result.stderr.strip() or f"git {' '.join(args)} failed")
    return result.stdout.strip()


def require_file(relative: str) -> str:
    path = ROOT / relative
    if not path.is_file() or not path.read_text(encoding="utf-8").strip():
        fail(f"missing or empty {relative}")
    return path.read_text(encoding="utf-8")


def check_baseline() -> dict[str, object]:
    peeled = git("rev-list", "-n", "1", BASELINE_TAG)
    if peeled != BASELINE_COMMIT:
        fail(f"baseline tag moved: {peeled}")
    ancestors = {}
    for label, revision in (("baseline", BASELINE_TAG), *GATE_SEALS.items()):
        result = subprocess.run(["git", "merge-base", "--is-ancestor", revision, "HEAD"], cwd=ROOT)
        if result.returncode:
            fail(f"{label} is not an ancestor of HEAD")
        ancestors[label] = True
    return {"tag": BASELINE_TAG, "peeledCommit": peeled, "ancestors": ancestors}


def check_design() -> dict[str, object]:
    contents = {name: require_file(name) for name in DESIGN_FILES}
    joined = "\n".join(contents.values())
    if "状态：Accepted" not in contents["docs/adr/ADR-021-sprint3-formal-pos-sync.md"]:
        fail("ADR-021 must be Accepted")
    for requirement_id in sorted({"T2-SYN-001", "T2-PAY-002"} | DESIGN_ONLY):
        if requirement_id not in joined:
            fail(f"design does not map {requirement_id}")
    for dimension in (
        "数据主权", "状态", "ACK", "游标", "冲突", "权限", "审计", "API",
        "Schema", "迁移", "容量", "回退", "测试",
    ):
        if dimension not in joined:
            fail(f"admission dimension missing: {dimension}")
    admission = json.loads(contents["contracts/t2/sprint3/s3-admission.json"])
    if admission.get("admittedRequirements") != ["T2-SYN-001"]:
        fail("only T2-SYN-001 may be admitted")
    if set(admission.get("designOnlyRequirements", [])) != DESIGN_ONLY:
        fail("Gate 3 design-only set changed")
    if admission.get("blockedRequirements") != ["T2-PAY-002"]:
        fail("T2-PAY-002 must remain blocked")
    if any(admission.get("externalEvidence", {}).values()):
        fail("external evidence must remain zero")
    openapi = contents["contracts/t2/sprint3/openapi-pos-sync-v1.yaml"]
    for token in ("/sync/bootstrap:", "/sync/push:", "/sync/results/{eventId}:", "/sync/pull:", "/sync/ack:"):
        if token not in openapi:
            fail(f"formal sync API missing {token}")
    payment = contents["contracts/t2/sprint3/payment-gate3-prep.yaml"]
    for token in ("runtimeAllowed: false", "providerNetworkCallsAllowed: 0", "T2-PAY-002: BLOCKED"):
        if token not in payment:
            fail(f"payment preparation boundary missing {token}")
    return {"files": len(contents), "admitted": 1, "paymentRuntimeAllowed": False}


def read_rtm() -> dict[str, dict[str, str]]:
    with (ROOT / "docs/governance/rtm.csv").open(encoding="utf-8-sig", newline="") as handle:
        return {row["requirement_id"]: row for row in csv.DictReader(handle)}


def check_rtm(stage: str) -> dict[str, object]:
    rows = read_rtm()
    for requirement_id in sorted(ACCEPTED_IDS):
        if rows.get(requirement_id, {}).get("status") != "ACCEPTED":
            fail(f"{requirement_id} must be ACCEPTED")
    expected = STAGE_STATUS[stage]
    if rows.get("T2-SYN-001", {}).get("status") != expected:
        fail(f"T2-SYN-001 must be {expected} at {stage}")
    for requirement_id in sorted(DESIGN_ONLY):
        if rows.get(requirement_id, {}).get("status") != "DRAFT":
            fail(f"{requirement_id} must remain DRAFT")
    for requirement_id, state in EXTERNAL_STATES.items():
        if rows.get(requirement_id, {}).get("status") != state:
            fail(f"external item {requirement_id} must remain {state}")
    return {"acceptedPriorGates": len(ACCEPTED_IDS), "sync": expected, "gate3": "DRAFT"}


def verify_migrations(stage: str) -> dict[str, object]:
    if stage != "closure":
        if MIGRATION_LEDGER.exists():
            fail("Sprint S3 migration ledger exists before runtime closure")
        return {"present": False}
    if not MIGRATION_LEDGER.is_file():
        fail("Sprint S3 migration checksum ledger missing")
    ledger = json.loads(MIGRATION_LEDGER.read_text(encoding="utf-8"))
    files = ledger.get("files", [])
    if len(files) != 3:
        fail("Sprint S3 ledger must seal two Flyway migrations and one SQLite V2 schema")
    for item in files:
        path = ROOT / item["path"]
        if not path.is_file() or hashlib.sha256(path.read_bytes()).hexdigest() != item["sha256"]:
            fail(f"published migration changed: {item['path']}")
    return {"present": True, "files": len(files)}


def check_runtime(stage: str) -> dict[str, object]:
    present = SERVER_RUNTIME.is_dir() or POS_RUNTIME.is_dir()
    if stage == "design" and present:
        fail("formal sync runtime exists before design admission")
    if stage == "closure" and not (SERVER_RUNTIME.is_dir() and POS_RUNTIME.is_dir()):
        fail("formal server and POS sync runtimes are required at closure")
    roots = [SERVER_RUNTIME / "src/main", POS_RUNTIME, ROOT / "pos-flutter/lib/infrastructure/local_database"]
    runtime = "\n".join(
        path.read_text(encoding="utf-8", errors="replace")
        for root in roots if root.exists()
        for path in sorted(root.rglob("*")) if path.is_file()
    ).lower()
    probe_sql = ("create table syn_", "insert into syn_", " from syn_", " join syn_", "update syn_")
    if any(token in runtime for token in probe_sql):
        fail("T1 syn_* probe table usage leaked into formal runtime")
    forbidden = ("paymentprovider", "providercallbackcontroller", "refundservice", "reconciliationjob", "inventoryservice", "promotionservice")
    violations = [token for token in forbidden if token in runtime]
    if violations:
        fail(f"forbidden later-Gate runtime found: {violations}")
    required = ("sync/push", "sync/pull", "sync/ack", "device_blocked", "dead_letter")
    if stage == "closure":
        missing = [token for token in required if token not in runtime]
        if missing:
            fail(f"formal synchronization runtime markers missing: {missing}")
    return {"present": present, "forbiddenLaterGateRuntime": 0, "paymentProviderNetworkCalls": 0}


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--stage", choices=sorted(STAGE_STATUS), default="design")
    parser.add_argument("--output", type=Path)
    args = parser.parse_args()
    evidence = {
        "schemaVersion": "1.0", "phase": "T2-GATE23-S3", "stage": args.stage,
        "evidenceLevel": "STATIC" if args.stage != "closure" else "STATIC+UNIT+INTEGRATION+FAULT",
        "generatedAt": datetime.now(timezone.utc).isoformat().replace("+00:00", "Z"),
        "commitSha": git("rev-parse", "HEAD"), "baseline": check_baseline(),
        "design": check_design(), "requirements": check_rtm(args.stage),
        "migrations": verify_migrations(args.stage), "runtime": check_runtime(args.stage),
        "externalEvidence": {"sandbox": 0, "realDevice": 0, "pilot": 0},
    }
    if args.output:
        target = args.output if args.output.is_absolute() else ROOT / args.output
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(json.dumps(evidence, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(
        f"T2-SPRINT3 OK: stage={args.stage} prior={len(ACCEPTED_IDS)} accepted "
        f"sync={STAGE_STATUS[args.stage]} gate3=DRAFT paymentNetwork=0 external=0"
    )


if __name__ == "__main__":
    main()
