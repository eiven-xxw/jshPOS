from __future__ import annotations

import argparse
import ast
import csv
import hashlib
import json
import re
import subprocess
import sys
from datetime import datetime, timezone
from pathlib import Path
from typing import Any


ROOT = Path(__file__).resolve().parents[1]
BASELINE_TAG = "t0-baseline-2026-08-16"
BASELINE_COMMIT = "04b176f2cd44ae4738a7bfd855548b17fa1bd380"
POC_ROOT = ROOT / "poc" / "t1-week1"
CONTRACT_ROOT = ROOT / "contracts" / "poc" / "t1"
RTM = ROOT / "docs" / "governance" / "rtm.csv"
ACTIVE_IDS = {
    "T1-HWD-001",
    "T1-OFF-001",
    "T1-SYN-001",
    "T1-TEN-001",
    "T1-PAY-001",
    "T1-DPK-001",
    "T1-UPG-001",
    "T1-SEC-001",
    "T1-CI-001",
}
BLOCKED_IDS = {
    "T1-HWD-002",
    "T1-PRN-001",
    "T1-SCN-001",
    "T1-SCL-001",
    "T1-IO-001",
    "T1-PAY-002",
    "T1-PAR-001",
}
DEFERRED_IDS = {"T1-JSH-001", "T1-LIC-001"}
SCHEMA_NAMES = {
    "provider-profile.schema.json",
    "payment-operation.schema.json",
    "device-operation.schema.json",
    "fault-script.schema.json",
    "sync-event.schema.json",
    "data-package.schema.json",
    "upgrade-case.schema.json",
    "evidence.schema.json",
}
FIXTURE_DOMAINS = {
    "device-faults.json": "DEVICE",
    "payment-faults.json": "PAYMENT",
    "offline-faults.json": "OFFLINE",
    "sync-faults.json": "SYNC",
    "tenant-faults.json": "TENANT",
}


def fail(message: str) -> None:
    print(f"T1-WEEK1 ERROR: {message}", file=sys.stderr)
    raise SystemExit(1)


def run_git(*args: str) -> str:
    completed = subprocess.run(
        ["git", *args],
        cwd=ROOT,
        check=False,
        capture_output=True,
        text=True,
        encoding="utf-8",
        errors="replace",
    )
    if completed.returncode != 0:
        fail(f"git {' '.join(args)} failed: {completed.stderr.strip()}")
    return completed.stdout.strip()


def load_json(path: Path) -> dict[str, Any]:
    try:
        with path.open(encoding="utf-8") as handle:
            return json.load(handle)
    except (OSError, json.JSONDecodeError) as exc:
        fail(f"cannot load {path.relative_to(ROOT)}: {exc}")


def digest(path: Path) -> str:
    return "sha256:" + hashlib.sha256(path.read_bytes()).hexdigest()


def check_baseline_and_scope() -> None:
    if run_git("cat-file", "-t", BASELINE_TAG) != "tag":
        fail(f"{BASELINE_TAG} must remain an annotated tag")
    if run_git("rev-list", "-n", "1", BASELINE_TAG) != BASELINE_COMMIT:
        fail(f"{BASELINE_TAG} must point to {BASELINE_COMMIT}")
    ancestor = subprocess.run(
        ["git", "merge-base", "--is-ancestor", BASELINE_TAG, "HEAD"],
        cwd=ROOT,
        check=False,
    )
    if ancestor.returncode != 0:
        fail("HEAD is not based on the approved T0 tag")

    changed = set(
        filter(None, run_git("-c", "core.quotepath=false", "diff", "--name-only", BASELINE_TAG).splitlines())
    )
    changed.update(
        filter(
            None,
            run_git("-c", "core.quotepath=false", "ls-files", "--others", "--exclude-standard").splitlines(),
        )
    )

    exact = {
        "AGENTS.md",
        ".gitignore",
        ".github/workflows/t1-week1.yml",
        ".github/workflows/t1-week2.yml",
        ".github/workflows/t1-week3.yml",
        ".github/workflows/t1-week4.yml",
        "docs/governance/rtm.csv",
        "docs/governance/change-log.md",
        "docs/adr/README.md",
        "docs/adr/ADR-017-t1-risk-poc-scope-and-integration-depth.md",
        "docs/adr/ADR-018-t1-exit-and-t2-entry-recommendation.md",
        "scripts/check_t1_prep.py",
        "scripts/check_t1_week1.py",
        "scripts/check_t1_week2.py",
        "scripts/check_t1_week3.py",
        "scripts/check_t1_week4.py",
        "scripts/run_t1_reproducibility.py",
        "scripts/compare_t1_reproducibility.py",
        "scripts/build_t1_poc_inventory.py",
        "scripts/check_contracts.py",
    }
    prefixes = (
        "docs/t1-prep/",
        "docs/t1-week1/",
        "docs/t1-week2/",
        "docs/t1-week3/",
        "docs/t1-week4/",
        "contracts/poc/t1/",
        "poc/t1-week1/",
        "poc/t1-week2/",
        "poc/t1-week3/",
    )
    unexpected = sorted(
        name for name in changed
        if name.replace("\\", "/") not in exact
        and not name.replace("\\", "/").startswith(prefixes)
    )
    if unexpected:
        fail(f"changes outside authorized Week 1 STATIC/FAKE scope: {unexpected}")


def check_rtm_boundary() -> None:
    with RTM.open(encoding="utf-8-sig", newline="") as handle:
        rows = {row["requirement_id"]: row for row in csv.DictReader(handle)}
    active = {key for key, row in rows.items() if key.startswith("T1-") and row["status"] == "IN_PROGRESS"}
    blocked = {key for key, row in rows.items() if key.startswith("T1-") and row["status"] == "BLOCKED"}
    deferred = {key for key, row in rows.items() if key.startswith("T1-") and row["status"] == "DEFERRED"}
    if active != ACTIVE_IDS:
        fail(f"IN_PROGRESS scope drift: expected {sorted(ACTIVE_IDS)}, got {sorted(active)}")
    if blocked != BLOCKED_IDS:
        fail(f"BLOCKED scope drift: expected {sorted(BLOCKED_IDS)}, got {sorted(blocked)}")
    if deferred != DEFERRED_IDS:
        fail(f"DEFERRED scope drift: expected {sorted(DEFERRED_IDS)}, got {sorted(deferred)}")


def check_contracts() -> list[Path]:
    actual = {path.name for path in CONTRACT_ROOT.glob("*.json")}
    if actual != SCHEMA_NAMES:
        fail(f"T1 contract set mismatch: expected {sorted(SCHEMA_NAMES)}, got {sorted(actual)}")
    paths: list[Path] = []
    ids: set[str] = set()
    for name in sorted(SCHEMA_NAMES):
        path = CONTRACT_ROOT / name
        document = load_json(path)
        if document.get("$schema") != "https://json-schema.org/draft/2020-12/schema":
            fail(f"{name} must use JSON Schema 2020-12")
        schema_id = document.get("$id")
        if not schema_id or schema_id in ids:
            fail(f"{name} has missing or duplicate $id")
        ids.add(schema_id)
        paths.append(path)
    return paths


def check_provider_profiles() -> tuple[Path, int]:
    path = POC_ROOT / "provider-profiles.json"
    document = load_json(path)
    if document.get("evidenceLevel") != "STATIC":
        fail("provider profiles must be STATIC")
    providers = document.get("providers", [])
    if len(providers) != 10:
        fail("exactly ten payment candidate profiles are required")
    codes: set[str] = set()
    selected = 0
    required_capabilities = {"wechat", "alipay", "unionpay", "query", "refund", "asyncNotify", "reversal"}
    for profile in providers:
        code = profile.get("providerCode", "")
        if not re.fullmatch(r"[A-Z0-9_]+", code) or code in codes:
            fail(f"invalid or duplicate providerCode: {code!r}")
        codes.add(code)
        if profile.get("evidenceLevel") != "STATIC":
            fail(f"{code} must be STATIC")
        if profile.get("integrationStatus") != "CANDIDATE_ONLY":
            fail(f"{code} must remain CANDIDATE_ONLY")
        if profile.get("sandboxStatus") != "BLOCKED":
            fail(f"{code} sandbox must remain BLOCKED")
        if set(profile.get("capabilities", {})) != required_capabilities:
            fail(f"{code} capability matrix is incomplete")
        if not profile.get("publicDocs") or not all(url.startswith("https://") for url in profile["publicDocs"]):
            fail(f"{code} publicDocs must contain HTTPS references")
        selected += profile.get("selectedForFake") is True
    if selected != 5:
        fail(f"exactly five candidates must be selected for Fake, found {selected}")
    return path, len(providers)


def check_fixtures() -> list[Path]:
    paths: list[Path] = []
    for name, domain in FIXTURE_DOMAINS.items():
        path = POC_ROOT / "fixtures" / name
        fixture = load_json(path)
        if fixture.get("fixtureVersion") != "1.0" or fixture.get("evidenceLevel") != "FAKE":
            fail(f"{name} must be version 1.0 and FAKE")
        if fixture.get("domain") != domain:
            fail(f"{name} domain must be {domain}")
        scenarios = fixture.get("scenarios", [])
        if not scenarios or len({item.get("id") for item in scenarios}) != len(scenarios):
            fail(f"{name} scenarios must be non-empty and unique")
        paths.append(path)

    sync = load_json(POC_ROOT / "fixtures" / "sync-faults.json")
    for event in sync.get("events", []):
        if event.get("synthetic") is not True or event.get("kind") != "SYNTHETIC_FACT":
            fail("sync fixture contains non-synthetic event")
        if event.get("tenantId") not in {"TENANT_ALPHA", "TENANT_BETA"}:
            fail("sync fixture contains an unknown tenant")

    payment = load_json(POC_ROOT / "fixtures" / "payment-faults.json")
    operations = {command.get("operation") for command in payment.get("commands", [])}
    if operations != {"CREATE", "QUERY", "REFUND", "NOTIFY"}:
        fail("payment fixture must instantiate CREATE, QUERY, REFUND and NOTIFY")
    if not all(command.get("synthetic") is True for command in payment["commands"]):
        fail("payment commands must be synthetic")

    device = load_json(POC_ROOT / "fixtures" / "device-faults.json")
    results = device.get("operationResults", [])
    if {result.get("capability") for result in results} != set(device.get("virtualCapabilities", [])):
        fail("device operation results must cover all five virtual capabilities")
    if not all(result.get("synthetic") is True for result in results):
        fail("device operations must be synthetic")

    for name in ("data-package-cases.json", "upgrade-cases.json"):
        path = POC_ROOT / "fixtures" / name
        document = load_json(path)
        if document.get("fixtureVersion") != "1.0" or document.get("evidenceLevel") != "FAKE":
            fail(f"{name} must be version 1.0 and FAKE")
        if len(document.get("cases", [])) < 3:
            fail(f"{name} lacks fault cases")
        paths.append(path)
    return paths


def check_harness_is_offline() -> Path:
    path = POC_ROOT / "src" / "t1_fake_harness.py"
    tree = ast.parse(path.read_text(encoding="utf-8"), filename=str(path))
    forbidden_modules = {"requests", "httpx", "urllib", "socket", "ftplib"}
    imports: set[str] = set()
    for node in ast.walk(tree):
        if isinstance(node, ast.Import):
            imports.update(alias.name.split(".")[0] for alias in node.names)
        elif isinstance(node, ast.ImportFrom) and node.module:
            imports.add(node.module.split(".")[0])
    found = sorted(imports & forbidden_modules)
    if found:
        fail(f"Fake harness may not import network modules: {found}")
    return path


def check_no_secret_material(paths: list[Path]) -> None:
    patterns = {
        "private key": re.compile(r"-----BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY-----"),
        "GitHub token": re.compile(r"gh[pousr]_[A-Za-z0-9]{30,}"),
        "AWS key": re.compile(r"AKIA[0-9A-Z]{16}"),
        "generic sk token": re.compile(r"\bsk-[A-Za-z0-9]{20,}\b"),
    }
    for path in paths:
        content = path.read_text(encoding="utf-8")
        for label, pattern in patterns.items():
            if pattern.search(content):
                fail(f"{label} detected in {path.relative_to(ROOT)}")


def validate_evidence(path: Path) -> None:
    document = load_json(path)
    if document.get("schemaVersion") != "1.0" or document.get("phase") != "T1-WEEK1":
        fail(f"{path} is not T1 Week 1 evidence")
    if document.get("evidenceLevel") not in {"STATIC", "FAKE"}:
        fail(f"{path} has forbidden evidence level")
    if document.get("baselineTag") != BASELINE_TAG:
        fail(f"{path} has wrong baseline tag")
    if not document.get("limitations"):
        fail(f"{path} must state limitations")
    for result in document.get("results", []):
        if result.get("requirementId") not in ACTIVE_IDS:
            fail(f"{path} references non-admitted requirement {result.get('requirementId')}")
        if result.get("result") not in {"PASS", "FAIL", "BLOCKED"}:
            fail(f"{path} contains invalid result")
        if not isinstance(result.get("assertions"), int) or result["assertions"] < 0:
            fail(f"{path} contains invalid assertion count")
        if not all(re.fullmatch(r"sha256:[a-f0-9]{64}", value) for value in result.get("fixtureDigests", [])):
            fail(f"{path} contains invalid fixture digest")
    encoded = json.dumps(document, ensure_ascii=False)
    for forbidden in ("SANDBOX_PASS", "REAL_DEVICE_PASS", "PILOT_PASS", "COMMERCIAL_PASS"):
        if forbidden in encoded:
            fail(f"{path} contains forbidden claim {forbidden}")


def write_static_evidence(output: Path, inputs: list[Path]) -> None:
    digest_map = {path.name: digest(path) for path in inputs}
    shared = sorted(set(digest_map.values()))
    results = [
        {"requirementId": requirement_id, "result": "PASS", "assertions": 1, "fixtureDigests": shared}
        for requirement_id in sorted(ACTIVE_IDS)
    ]
    document = {
        "schemaVersion": "1.0",
        "phase": "T1-WEEK1",
        "evidenceLevel": "STATIC",
        "generatedAt": datetime.now(timezone.utc).isoformat().replace("+00:00", "Z"),
        "baselineTag": BASELINE_TAG,
        "results": results,
        "limitations": [
            "静态通过只证明范围、格式、资料分级和安全规则可由机器校验",
            "未执行支付沙箱、Android实机、外设、真实网络、真实商户数据或商业业务",
            "STATIC证据不得替代FAKE、SANDBOX、REAL_DEVICE或PILOT证据",
        ],
    }
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(document, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    validate_evidence(output)


def write_manifest(evidence_paths: list[Path], output: Path) -> None:
    for path in evidence_paths:
        validate_evidence(path)
    document = {
        "schemaVersion": "1.0",
        "phase": "T1-WEEK1",
        "baselineTag": BASELINE_TAG,
        "generatedAt": datetime.now(timezone.utc).isoformat().replace("+00:00", "Z"),
        "files": [
            {
                "path": str(path.resolve().relative_to(ROOT)).replace("\\", "/"),
                "sha256": hashlib.sha256(path.read_bytes()).hexdigest(),
                "evidenceLevel": load_json(path)["evidenceLevel"],
            }
            for path in sorted(evidence_paths)
        ],
    }
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(document, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def main() -> None:
    parser = argparse.ArgumentParser(description="Validate T1 Week 1 STATIC/FAKE gates")
    parser.add_argument(
        "--output",
        type=Path,
        default=ROOT / "artifacts" / "t1" / "week1" / "static-evidence.json",
    )
    parser.add_argument("--validate-evidence", type=Path, nargs="*")
    parser.add_argument("--manifest-output", type=Path)
    args = parser.parse_args()

    check_baseline_and_scope()
    check_rtm_boundary()
    contracts = check_contracts()
    profile_path, provider_count = check_provider_profiles()
    fixtures = check_fixtures()
    harness_path = check_harness_is_offline()
    security_paths = contracts + fixtures + [profile_path, harness_path, Path(__file__)]
    check_no_secret_material(security_paths)
    write_static_evidence(args.output, security_paths)

    evidence_paths = list(args.validate_evidence or [])
    for path in evidence_paths:
        validate_evidence(path)
    if args.manifest_output:
        write_manifest([args.output, *evidence_paths], args.manifest_output)

    print(
        "T1 WEEK1 STATIC OK: "
        f"{len(contracts)} schemas, {provider_count} payment candidates / 5 Fake-selected, "
        f"{len(fixtures)} fixture files, {len(ACTIVE_IDS)} admitted requirements; "
        f"{len(BLOCKED_IDS)} blockers unchanged"
    )


if __name__ == "__main__":
    main()
