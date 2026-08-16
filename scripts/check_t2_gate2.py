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
GATE0_SEAL = "cf2ef29bd74c5d0f8fa5845a689305ebb56c7ef2"
GATE1_SEAL = "6a94bc6af2938fba6b9a1af123eb94b6312af9b2"
GATE0_IDS = {
    "T2-IAM-001", "T2-ORG-001", "T2-RBAC-001", "T2-CFG-001",
    "T2-AUD-001", "T2-SEC-001", "T2-OBS-001", "T2-MIG-001",
}
GATE1_IDS = {
    "T2-PRD-001", "T2-PRD-002", "T2-PRD-003", "T2-PRD-004",
    "T2-PRC-001", "T2-PRC-002", "T2-DPK-001",
}
GATE2_IDS = {
    "T2-POS-001", "T2-POS-002", "T2-POS-003", "T2-POS-004",
    "T2-POS-005", "T2-ORD-001", "T2-ORD-002", "T2-OFF-001",
}
EXTERNAL_STATES = {
    "T2-HWD-001": "BLOCKED",
    "T2-PAY-002": "BLOCKED",
    "T2-PAR-001": "BLOCKED",
    "T2-JSH-001": "DEFERRED",
    "T2-LIC-001": "DEFERRED",
}
STAGE_STATUS = {"design": "DRAFT", "admitted": "IN_PROGRESS", "closure": "VERIFIED"}
DESIGN_FILES = [
    "docs/t2-gate2/01_范围决策与逐项准入.md",
    "docs/t2-gate2/02_数据主权状态机与不变量.md",
    "docs/t2-gate2/03_权限审计API事件与同步契约.md",
    "docs/t2-gate2/04_SQLite迁移兼容故障与回退.md",
    "docs/t2-gate2/05_测试矩阵CI与证据.md",
    "docs/ADR/ADR-020-gate2-local-order-cash-atomicity.md",
    "contracts/t2/gate2/gate2-admission.json",
    "contracts/t2/gate2/openapi-pos-order-v1.yaml",
    "contracts/t2/gate2/sync-design-only-v1.yaml",
    "contracts/t2/gate2/test-vectors/two-tenant-cash-order-v1.json",
]
MIGRATION_LEDGER = ROOT / "contracts/t2/gate2/migration-checksums.json"
SERVER_MODULE = ROOT / "server/ruoyi-modules/jshpos-order"
POS_RUNTIME = ROOT / "pos-flutter/lib/features/checkout"


def fail(message: str) -> None:
    raise SystemExit(f"T2-GATE2 ERROR: {message}")


def git(*args: str, check: bool = True) -> str:
    result = subprocess.run(["git", *args], cwd=ROOT, text=True, capture_output=True)
    if check and result.returncode:
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
    ancestors: dict[str, bool] = {}
    for label, revision in (("baseline", BASELINE_TAG), ("gate0", GATE0_SEAL), ("gate1", GATE1_SEAL)):
        ok = subprocess.run(["git", "merge-base", "--is-ancestor", revision, "HEAD"], cwd=ROOT).returncode == 0
        if not ok:
            fail(f"{label} is not an ancestor of HEAD")
        ancestors[label] = ok
    return {"tag": BASELINE_TAG, "peeledCommit": peeled, "ancestors": ancestors}


def check_design() -> dict[str, object]:
    contents = {name: require_file(name) for name in DESIGN_FILES}
    adr = contents["docs/ADR/ADR-020-gate2-local-order-cash-atomicity.md"]
    if "状态：Accepted" not in adr or "T1 `syn_*`" not in adr:
        fail("ADR-020 is not accepted or does not prohibit T1 probe reuse")
    joined = "\n".join(contents.values())
    for requirement_id in sorted(GATE2_IDS | {"T2-SYN-001"}):
        if requirement_id not in joined:
            fail(f"design does not map {requirement_id}")
    for dimension in ("数据主权", "状态", "不变量", "权限", "审计", "API", "SQLite", "Flyway", "回退", "测试"):
        if dimension not in joined:
            fail(f"admission dimension missing: {dimension}")
    admission = json.loads(contents["contracts/t2/gate2/gate2-admission.json"])
    if set(admission.get("admittedRequirements", [])) != GATE2_IDS:
        fail("Gate 2 admission requirement set mismatch")
    if admission.get("designOnlyRequirements") != ["T2-SYN-001"]:
        fail("T2-SYN-001 must remain design-only")
    if any(admission.get("externalEvidence", {}).values()):
        fail("external evidence must remain zero")
    sync = contents["contracts/t2/gate2/sync-design-only-v1.yaml"]
    for token in ("runtimeAllowed: false", "transportCallsAllowed: 0", "EVENT_HASH_MISMATCH_AND_BLOCK"):
        if token not in sync:
            fail(f"sync design boundary missing {token}")
    return {"files": len(contents), "admitted": len(GATE2_IDS), "syncRuntimeAllowed": False}


def read_rtm() -> dict[str, dict[str, str]]:
    with (ROOT / "docs/governance/rtm.csv").open(encoding="utf-8-sig", newline="") as handle:
        return {row["requirement_id"]: row for row in csv.DictReader(handle)}


def check_rtm(stage: str) -> dict[str, object]:
    rows = read_rtm()
    for requirement_id in sorted(GATE0_IDS | GATE1_IDS):
        if rows.get(requirement_id, {}).get("status") != "ACCEPTED":
            fail(f"{requirement_id} must be ACCEPTED")
    expected = STAGE_STATUS[stage]
    for requirement_id in sorted(GATE2_IDS):
        if rows.get(requirement_id, {}).get("status") != expected:
            fail(f"{requirement_id} must be {expected} at {stage}")
    if rows.get("T2-SYN-001", {}).get("status") != "DRAFT":
        fail("T2-SYN-001 must remain DRAFT in Sprint S2")
    for requirement_id, state in EXTERNAL_STATES.items():
        if rows.get(requirement_id, {}).get("status") != state:
            fail(f"external item {requirement_id} must remain {state}")
    return {"gate0": "ACCEPTED", "gate1": "ACCEPTED", "gate2": expected, "sync": "DRAFT"}


def verify_migrations(stage: str) -> dict[str, object]:
    if stage != "closure":
        if MIGRATION_LEDGER.exists():
            fail("Gate 2 migration ledger exists before runtime closure")
        return {"present": False}
    if not MIGRATION_LEDGER.is_file():
        fail("Gate 2 migration checksum ledger missing")
    ledger = json.loads(MIGRATION_LEDGER.read_text(encoding="utf-8"))
    files = ledger.get("files", [])
    if len(files) != 3:
        fail("Gate 2 ledger must seal exactly two Flyway migrations and one SQLite schema")
    for item in files:
        path = ROOT / item["path"]
        if not path.is_file() or hashlib.sha256(path.read_bytes()).hexdigest() != item["sha256"]:
            fail(f"published migration changed: {item['path']}")
    return {"present": True, "files": len(files)}


def check_runtime(stage: str) -> dict[str, object]:
    present = SERVER_MODULE.is_dir() and POS_RUNTIME.is_dir()
    if stage in {"design", "admitted"} and present:
        fail("formal Gate 2 runtime exists before completed admission transition")
    if stage == "closure" and not present:
        fail("formal Gate 2 server/POS runtime is missing")
    if stage != "closure":
        return {"present": False, "syncNetworkEntrypoints": 0}

    roots = [SERVER_MODULE / "src/main", ROOT / "pos-flutter/lib/features", ROOT / "pos-flutter/lib/infrastructure/local_database"]
    runtime = "\n".join(
        path.read_text(encoding="utf-8", errors="replace")
        for root in roots if root.exists()
        for path in sorted(root.rglob("*")) if path.is_file()
    )
    lower = runtime.lower()
    if "syn_" in lower:
        fail("T1 syn_* probe naming leaked into formal runtime")
    network_tokens = ("package:http/", "dart:io", "websocket", "sync/push", "sync/pull", "sync/ack")
    found = [token for token in network_tokens if token in lower]
    if found:
        fail(f"T2-SYN-001 runtime network entrypoints found: {found}")
    forbidden = ("refundservice", "inventoryservice", "promotionservice", "channelpayment")
    violations = [token for token in forbidden if token in lower]
    if violations:
        fail(f"later Gate runtime found: {violations}")
    return {"present": True, "syncNetworkEntrypoints": 0, "forbiddenLaterGateRuntime": 0}


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--stage", choices=sorted(STAGE_STATUS), default="design")
    parser.add_argument("--output", type=Path)
    args = parser.parse_args()
    evidence = {
        "schemaVersion": "1.0",
        "phase": "T2-GATE2-S2",
        "stage": args.stage,
        "evidenceLevel": "STATIC" if args.stage != "closure" else "STATIC+UNIT+INTEGRATION",
        "generatedAt": datetime.now(timezone.utc).isoformat().replace("+00:00", "Z"),
        "commitSha": git("rev-parse", "HEAD"),
        "baseline": check_baseline(),
        "design": check_design(),
        "requirements": check_rtm(args.stage),
        "migrations": verify_migrations(args.stage),
        "runtime": check_runtime(args.stage),
        "externalEvidence": {"sandbox": 0, "realDevice": 0, "pilot": 0},
    }
    if args.output:
        target = args.output if args.output.is_absolute() else ROOT / args.output
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(json.dumps(evidence, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(
        f"T2-GATE2 OK: stage={args.stage} admission=8/8 gate0=ACCEPTED gate1=ACCEPTED "
        f"gate2={STAGE_STATUS[args.stage]} sync=DRAFT network=0 external=0"
    )


if __name__ == "__main__":
    main()
