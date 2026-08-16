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
BRANCH_START = "60828acbbe18c30914a076709905eeb525ffeb5f"
GATE3A_IDS = {"T2-PAY-001", "T2-PAY-003", "T2-REF-001", "T2-REC-001"}
PRIOR_ACCEPTED = {
    "T2-IAM-001", "T2-ORG-001", "T2-RBAC-001", "T2-CFG-001", "T2-AUD-001", "T2-SEC-001",
    "T2-OBS-001", "T2-MIG-001", "T2-PRD-001", "T2-PRD-002", "T2-PRD-003", "T2-PRD-004",
    "T2-PRC-001", "T2-PRC-002", "T2-DPK-001", "T2-POS-001", "T2-POS-002", "T2-POS-003",
    "T2-POS-004", "T2-POS-005", "T2-ORD-001", "T2-ORD-002", "T2-OFF-001", "T2-SYN-001",
}
EXTERNAL = {
    "T2-HWD-001": "BLOCKED", "T2-PAY-002": "BLOCKED", "T2-PAR-001": "BLOCKED",
    "T2-JSH-001": "DEFERRED", "T2-LIC-001": "DEFERRED",
}
STAGE_STATUS = {"admitted": "IN_PROGRESS", "closure": "VERIFIED"}
DESIGN_FILES = [
    "docs/adr/ADR-022-gate3a-provider-neutral-payment-core.md",
    "docs/t2-gate3a/01_范围非目标与逐项准入.md",
    "docs/t2-gate3a/02_数据主权状态机与不变量.md",
    "docs/t2-gate3a/03_权限审计API事件与Provider端口.md",
    "docs/t2-gate3a/04_Flyway容量兼容与回退.md",
    "docs/t2-gate3a/05_测试矩阵CI与证据.md",
    "contracts/t2/gate3a/gate3a-admission.json",
    "contracts/t2/gate3a/openapi-payment-core-v1.yaml",
    "contracts/t2/gate3a/schemas/payment-intent-command.v1.schema.json",
    "contracts/t2/gate3a/schemas/provider-observation.v1.schema.json",
    "contracts/t2/gate3a/schemas/refund-command.v1.schema.json",
    "contracts/t2/gate3a/schemas/reconciliation-entry.v1.schema.json",
    "contracts/t2/gate3a/test-vectors/payment-fake-vectors-v1.json",
]
RUNTIME = ROOT / "server/ruoyi-modules/jshpos-payment"
LEDGER = ROOT / "contracts/t2/gate3a/migration-checksums.json"


def fail(message: str) -> None:
    raise SystemExit(f"T2-GATE3A ERROR: {message}")


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


def check_ancestry() -> dict[str, object]:
    peeled = git("rev-list", "-n", "1", BASELINE_TAG)
    if peeled != BASELINE_COMMIT:
        fail(f"baseline tag moved: {peeled}")
    for revision in (BASELINE_TAG, "968ae7be34ab144c970e5c92fb7ffbddf60bf5e1", BRANCH_START):
        result = subprocess.run(["git", "merge-base", "--is-ancestor", revision, "HEAD"], cwd=ROOT)
        if result.returncode:
            fail(f"required ancestor missing: {revision}")
    return {"baselineTag": BASELINE_TAG, "peeledCommit": peeled, "branchStart": BRANCH_START}


def check_design() -> dict[str, object]:
    contents = {path: require_file(path) for path in DESIGN_FILES}
    joined = "\n".join(contents.values())
    if "状态：Accepted" not in contents["docs/adr/ADR-022-gate3a-provider-neutral-payment-core.md"]:
        fail("ADR-022 must be Accepted")
    for requirement in sorted(GATE3A_IDS | {"T2-PAY-002"}):
        if requirement not in joined:
            fail(f"design does not map {requirement}")
    for dimension in ("数据主权", "状态", "不变量", "权限", "审计", "API", "事件", "Flyway", "容量", "回退", "Fake", "CI"):
        if dimension not in joined:
            fail(f"admission dimension missing: {dimension}")
    admission = json.loads(contents["contracts/t2/gate3a/gate3a-admission.json"])
    if set(admission.get("admittedRequirements", [])) != GATE3A_IDS:
        fail("Gate 3A admitted set changed")
    if admission.get("blockedRequirements") != ["T2-PAY-002"]:
        fail("T2-PAY-002 must remain the blocked sandbox requirement")
    boundary = admission.get("runtimeBoundary", {})
    if boundary.get("providerNetworkCallsAllowed") != 0 or any(
        boundary.get(key) for key in (
            "providerHttpAdaptersAllowed", "providerSdkAllowed", "externalCallbackControllersAllowed",
            "providerCredentialConfigurationAllowed",
        )
    ):
        fail("provider network boundary was relaxed")
    vectors = json.loads(contents["contracts/t2/gate3a/test-vectors/payment-fake-vectors-v1.json"])
    if len(vectors.get("fixedVectors", [])) != 16 or vectors.get("providerNetworkCallsAllowed") != 0:
        fail("Gate 3A fixed Fake vector ledger is incomplete")
    openapi = contents["contracts/t2/gate3a/openapi-payment-core-v1.yaml"].lower()
    if "callback:" in openapi or "webhook:" in openapi:
        fail("external callback path is forbidden in Gate 3A OpenAPI")
    return {"files": len(contents), "fixedFakeVectors": 16, "providerNetworkCallsAllowed": 0}


def read_rtm() -> dict[str, dict[str, str]]:
    with (ROOT / "docs/governance/rtm.csv").open(encoding="utf-8-sig", newline="") as handle:
        return {row["requirement_id"]: row for row in csv.DictReader(handle)}


def check_requirements(stage: str) -> dict[str, object]:
    rows = read_rtm()
    for requirement in sorted(PRIOR_ACCEPTED):
        if rows.get(requirement, {}).get("status") != "ACCEPTED":
            fail(f"prior requirement {requirement} must be ACCEPTED")
    expected = STAGE_STATUS[stage]
    for requirement in sorted(GATE3A_IDS):
        if rows.get(requirement, {}).get("status") != expected:
            fail(f"{requirement} must be {expected} at {stage}")
    for requirement, status in EXTERNAL.items():
        if rows.get(requirement, {}).get("status") != status:
            fail(f"{requirement} must remain {status}")
    return {"priorAccepted": len(PRIOR_ACCEPTED), "gate3a": expected, "paymentSandbox": "BLOCKED"}


def check_runtime(stage: str) -> dict[str, object]:
    if stage == "closure" and not RUNTIME.is_dir():
        fail("jshpos-payment runtime is missing at closure")
    runtime = "\n".join(
        path.read_text(encoding="utf-8", errors="replace")
        for path in sorted((RUNTIME / "src/main").rglob("*")) if path.is_file()
    ).lower() if RUNTIME.is_dir() else ""
    forbidden = (
        "java.net.http", "resttemplate", "webclient", "okhttp", "hutool.http", "httpurlconnection",
        "@postmapping(\"/callback", "@requestmapping(\"/callback", "providersecret", "merchantprivatekey",
        "inventoryservice", "promotionservice",
    )
    violations = [token for token in forbidden if token in runtime]
    if violations:
        fail(f"forbidden Provider/later-Gate runtime found: {violations}")
    if stage == "closure":
        for marker in ("unknown", "providerobservation", "refund", "reconciliation", "dead_letter"):
            if marker not in runtime:
                fail(f"formal runtime marker missing: {marker}")
    return {"present": RUNTIME.is_dir(), "providerNetworkCalls": 0, "forbiddenRuntime": 0}


def check_migrations(stage: str) -> dict[str, object]:
    if stage != "closure":
        if LEDGER.exists():
            fail("migration checksum ledger must not be sealed before closure")
        return {"sealed": False}
    if not LEDGER.is_file():
        fail("Gate 3A migration checksum ledger missing")
    ledger = json.loads(LEDGER.read_text(encoding="utf-8"))
    files = ledger.get("files", [])
    if len(files) != 2:
        fail("Gate 3A must seal V9 and V10")
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
        "schemaVersion": "1.0", "phase": "T2-GATE3A", "stage": args.stage,
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
    print(
        f"T2-GATE3A OK: stage={args.stage} prior={len(PRIOR_ACCEPTED)} accepted "
        f"gate3a={STAGE_STATUS[args.stage]} fakeSeeds=16 paymentNetwork=0 external=0"
    )


if __name__ == "__main__":
    main()
