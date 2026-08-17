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
BRANCH_START = "1bd27f70d39dd2056ffecf3b25f07aa9c7953606"
SEQUENCE = ("T2-TRM-001", "T2-BAK-001")
PRIOR_ACCEPTED = {
    "T2-IAM-001", "T2-ORG-001", "T2-RBAC-001", "T2-CFG-001", "T2-AUD-001", "T2-SEC-001",
    "T2-OBS-001", "T2-MIG-001", "T2-PRD-001", "T2-PRD-002", "T2-PRD-003", "T2-PRD-004",
    "T2-PRC-001", "T2-PRC-002", "T2-DPK-001", "T2-POS-001", "T2-POS-002", "T2-POS-003",
    "T2-POS-004", "T2-POS-005", "T2-ORD-001", "T2-ORD-002", "T2-OFF-001", "T2-SYN-001",
    "T2-PAY-001", "T2-PAY-003", "T2-REF-001", "T2-REC-001", "T2-INV-001", "T2-INV-002",
    "T2-INV-003", "T2-INV-004", "T2-PUR-001", "T2-CST-001", "T2-TRF-001", "T2-PRM-001",
    "T2-PRM-002", "T2-PRM-003", "T2-POS-006", "T2-ORD-003", "T2-REF-002", "T2-MEM-001",
    "T2-MEM-002", "T2-RPT-001", "T2-RPT-002",
}
EXTERNAL = {
    "T2-HWD-001": "BLOCKED", "T2-PAY-002": "BLOCKED", "T2-PAR-001": "BLOCKED",
    "T2-JSH-001": "DEFERRED", "T2-LIC-001": "DEFERRED",
}
STAGES = {
    "design": {"T2-TRM-001": "IN_PROGRESS", "T2-BAK-001": "DRAFT"},
    "trm": {"T2-TRM-001": "VERIFIED", "T2-BAK-001": "DRAFT"},
    "bak-admission": {"T2-TRM-001": "VERIFIED", "T2-BAK-001": "IN_PROGRESS"},
    "bak": {"T2-TRM-001": "VERIFIED", "T2-BAK-001": "IN_PROGRESS"},
    "closure": {"T2-TRM-001": "VERIFIED", "T2-BAK-001": "VERIFIED"},
}
DESIGN_FILES = (
    "docs/adr/ADR-032-gate6a-terminal-and-recovery.md",
    "docs/t2-gate6a/01_范围非目标与顺序准入.md",
    "docs/t2-gate6a/02_终端数据主权状态机密钥权限与审计.md",
    "docs/t2-gate6a/03_备份恢复设计与运行手册.md",
    "docs/t2-gate6a/04_UPG001设计准备与实机阻断.md",
    "docs/t2-gate6a/05_测试矩阵CI与证据规范.md",
    "contracts/t2/gate6a/gate6a-admission.json",
    "contracts/t2/gate6a/persistence-registry.csv",
    "contracts/t2/gate6a/migration-checksums.json",
    "contracts/t2/gate6a/openapi-terminal-v1.yaml",
    "contracts/t2/gate6a/terminal-events-v1.yaml",
    "contracts/t2/gate6a/upgrade-design-only-v1.yaml",
    "contracts/t2/gate6a/test-vectors/terminal-vectors.json",
    "contracts/t2/gate6a/test-vectors/backup-design-vectors.json",
    "contracts/t2/gate6a/schemas/terminal-activation.v1.schema.json",
    "contracts/t2/gate6a/schemas/backup-manifest.v1.schema.json",
    "contracts/t2/gate6a/schemas/restore-evidence.v1.schema.json",
)


def fail(message: str) -> None:
    raise SystemExit(f"T2-GATE6A ERROR: {message}")


def git(*args: str) -> str:
    result = subprocess.run(["git", *args], cwd=ROOT, text=True, capture_output=True)
    if result.returncode:
        fail(result.stderr.strip() or f"git {' '.join(args)} failed")
    return result.stdout.strip()


def required(relative: str) -> str:
    path = ROOT / relative
    if not path.is_file():
        fail(f"missing {relative}")
    content = path.read_text(encoding="utf-8")
    if not content.strip():
        fail(f"empty {relative}")
    return content


def rtm_rows() -> dict[str, dict[str, str]]:
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
    rows = rtm_rows()
    changed = sorted(item for item in PRIOR_ACCEPTED if rows[item]["status"] != "ACCEPTED")
    if changed:
        fail(f"prior accepted requirements changed {changed}")
    for item, expected in STAGES[stage].items():
        if rows[item]["status"] != expected:
            fail(f"{item} expected {expected}, got {rows[item]['status']}")
    for item in ("T2-UPG-001", "T2-UAT-001", "T2-REL-001"):
        if rows[item]["status"] != "DRAFT":
            fail(f"design-only requirement changed {item}")
    for item, expected in EXTERNAL.items():
        if rows[item]["status"] != expected:
            fail(f"external boundary changed {item}")
    return {"priorAccepted": len(PRIOR_ACCEPTED), "gate6a": STAGES[stage], "upgradeRuntime": 0}


def contracts(stage: str) -> dict[str, object]:
    content = {path: required(path) for path in DESIGN_FILES}
    admission = json.loads(content["contracts/t2/gate6a/gate6a-admission.json"])
    if admission.get("sequence") != list(SEQUENCE):
        fail("Gate 6A sequence changed")
    if admission.get("requirements", {}).get("T2-PAY-002") != "BLOCKED":
        fail("payment sandbox boundary changed")
    forbidden = set(admission.get("forbidden", []))
    if not {"PROVIDER_NETWORK", "REAL_DEVICE_COMMAND", "RAW_ACTIVATION_SECRET_AT_REST", "UPGRADE_RUNTIME"}.issubset(forbidden):
        fail("forbidden boundary incomplete")
    openapi = content["contracts/t2/gate6a/openapi-terminal-v1.yaml"]
    for marker in ("T2-TRM-001", "/api/v1/terminal-activations:", "/api/pos/v1/terminals/activate:",
                   "terminal:credential:rotate", "rawSecretAtRestAllowed: false", "realDeviceEvidence: BLOCKED"):
        if marker not in openapi:
            fail(f"terminal OpenAPI marker missing {marker}")
    upgrade = content["contracts/t2/gate6a/upgrade-design-only-v1.yaml"]
    for marker in ("status: DRAFT", "runtimeAllowed: false", "realApkInstall: BLOCKED"):
        if marker not in upgrade:
            fail(f"upgrade design boundary missing {marker}")
    for schema in ("terminal-activation.v1.schema.json", "backup-manifest.v1.schema.json", "restore-evidence.v1.schema.json"):
        json.loads(content[f"contracts/t2/gate6a/schemas/{schema}"])
    checksum = json.loads(content["contracts/t2/gate6a/migration-checksums.json"])
    expected_count = 0 if stage == "design" else (2 if stage in {"trm", "bak-admission"} else 4)
    if len(checksum.get("files", [])) != expected_count:
        fail(f"migration checksum ledger expected {expected_count} files")
    for item in checksum["files"]:
        path = ROOT / item["path"]
        if not path.is_file() or hashlib.sha256(path.read_bytes()).hexdigest() != item["sha256"]:
            fail(f"published migration checksum mismatch {item['path']}")
    return {"designFiles": len(content), "sequence": list(SEQUENCE), "publishedMigrations": expected_count}


def runtime_scope(stage: str) -> dict[str, int]:
    sync_root = ROOT / "server/ruoyi-modules/jshpos-sync/src/main"
    sync_content = "\n".join(path.read_text(encoding="utf-8", errors="replace")
                               for path in sync_root.rglob("*") if path.is_file())
    lowered = sync_content.lower()
    for token in ("java.net.http", "resttemplate", "webclient", "okhttp", "hutool.http", "httpurlconnection"):
        if token in lowered:
            fail(f"forbidden outbound network runtime detected {token}")
    resilience = ROOT / "server/ruoyi-modules/jshpos-resilience"
    if stage in {"design", "trm"} and resilience.exists():
        fail("backup runtime appeared before TRM verification and BAK admission")
    if stage != "design":
        for marker in ("TerminalRegistryService", "TerminalSecretProtector", "dev_terminal_activation"):
            if marker.lower() not in lowered:
                fail(f"terminal runtime marker missing {marker}")
    tracked = git("ls-files", "server", "admin-web", "flutter-pos").splitlines()
    repository = "\n".join(
        (ROOT / name).read_text(encoding="utf-8", errors="replace")
        for name in tracked
        if (ROOT / name).suffix in {".java", ".kt", ".dart", ".vue", ".ts"}
    )
    for marker in ("executeUpgrade", "sendRealTerminalCommand", "ProviderHttpClient"):
        if marker in repository:
            fail(f"forbidden runtime marker detected {marker}")
    return {"providerNetworkCalls": 0, "realDeviceCommands": 0, "upgradeRuntime": 0}


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--stage", choices=tuple(STAGES), default="design")
    parser.add_argument("--output", type=Path)
    args = parser.parse_args()
    report = {
        "schemaVersion": "1.0", "phase": "T2-GATE6A", "stage": args.stage,
        "generatedAt": datetime.now(timezone.utc).isoformat().replace("+00:00", "Z"),
        "commitSha": git("rev-parse", "HEAD"), "ancestry": ancestry(),
        "requirements": requirements(args.stage), "contracts": contracts(args.stage),
        "scope": runtime_scope(args.stage),
        "externalEvidence": {"sandbox": 0, "realDevice": 0, "pilot": 0, "cloudDr": 0},
    }
    if args.output:
        output = args.output if args.output.is_absolute() else ROOT / args.output
        output.parent.mkdir(parents=True, exist_ok=True)
        output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(f"T2-GATE6A OK: stage={args.stage} sequence={','.join(SEQUENCE)} realDevice=0 cloudDr=0")


if __name__ == "__main__":
    main()
