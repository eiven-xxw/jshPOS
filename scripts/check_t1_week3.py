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
POC_ROOT = ROOT / "poc" / "t1-week3"
CONTRACT_ROOT = ROOT / "contracts" / "poc" / "t1" / "week3"
RTM = ROOT / "docs" / "governance" / "rtm.csv"
ACTIVE_IDS = {
    "T1-HWD-001", "T1-OFF-001", "T1-SYN-001", "T1-TEN-001", "T1-PAY-001",
    "T1-DPK-001", "T1-UPG-001", "T1-SEC-001", "T1-CI-001",
}
WEEK3_FAKE_IDS = {"T1-OFF-001", "T1-SYN-001", "T1-TEN-001", "T1-PAY-001", "T1-DPK-001", "T1-UPG-001"}
WEEK3_STATIC_IDS = {"T1-SEC-001", "T1-CI-001"}
BLOCKED_IDS = {"T1-HWD-002", "T1-PRN-001", "T1-SCN-001", "T1-SCL-001", "T1-IO-001", "T1-PAY-002", "T1-PAR-001"}
DEFERRED_IDS = {"T1-JSH-001", "T1-LIC-001"}
SCHEMA_NAMES = {"cross-fault-plan.schema.json", "failed-seed-ledger.schema.json", "evidence.schema.json"}
FIXTURE_NAMES = {
    "sync-cross-fault-plan.json", "payment-convergence-plan.json", "package-recovery-plan.json",
    "upgrade-compat-plan.json", "failed-seed-ledger.json",
}


def fail(message: str) -> None:
    print(f"T1-WEEK3 ERROR: {message}", file=sys.stderr)
    raise SystemExit(1)


def run_git(*args: str) -> str:
    completed = subprocess.run(
        ["git", *args], cwd=ROOT, check=False, capture_output=True, text=True,
        encoding="utf-8", errors="replace",
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
        fail(f"{BASELINE_TAG} must remain annotated")
    if run_git("rev-list", "-n", "1", BASELINE_TAG) != BASELINE_COMMIT:
        fail(f"{BASELINE_TAG} target changed")
    if subprocess.run(["git", "merge-base", "--is-ancestor", BASELINE_TAG, "HEAD"], cwd=ROOT).returncode != 0:
        fail("HEAD is not based on the approved T0 tag")

    changed = set(filter(None, run_git("-c", "core.quotepath=false", "diff", "--name-only", BASELINE_TAG).splitlines()))
    changed.update(filter(None, run_git("-c", "core.quotepath=false", "ls-files", "--others", "--exclude-standard").splitlines()))
    exact = {
        "AGENTS.md", ".gitignore", ".github/workflows/t1-week1.yml", ".github/workflows/t1-week2.yml",
        ".github/workflows/t1-week3.yml", "docs/governance/rtm.csv", "docs/governance/change-log.md",
        "docs/adr/README.md", "docs/adr/ADR-017-t1-risk-poc-scope-and-integration-depth.md",
        "scripts/check_contracts.py", "scripts/check_t1_prep.py", "scripts/check_t1_week1.py",
        "scripts/check_t1_week2.py", "scripts/check_t1_week3.py",
    }
    prefixes = (
        "docs/t1-prep/", "docs/t1-week1/", "docs/t1-week2/", "docs/t1-week3/",
        "contracts/poc/t1/", "poc/t1-week1/", "poc/t1-week2/", "poc/t1-week3/",
    )
    unexpected = sorted(
        name for name in changed
        if name.replace("\\", "/") not in exact and not name.replace("\\", "/").startswith(prefixes)
    )
    if unexpected:
        fail(f"changes outside authorized T1 Week 3 STATIC/FAKE scope: {unexpected}")


def check_rtm_boundary() -> None:
    with RTM.open(encoding="utf-8-sig", newline="") as handle:
        rows = {row["requirement_id"]: row for row in csv.DictReader(handle)}
    active = {key for key, row in rows.items() if key.startswith("T1-") and row["status"] == "IN_PROGRESS"}
    blocked = {key for key, row in rows.items() if key.startswith("T1-") and row["status"] == "BLOCKED"}
    deferred = {key for key, row in rows.items() if key.startswith("T1-") and row["status"] == "DEFERRED"}
    if active != ACTIVE_IDS:
        fail(f"IN_PROGRESS drift: {sorted(active)}")
    if blocked != BLOCKED_IDS:
        fail(f"BLOCKED drift: {sorted(blocked)}")
    if deferred != DEFERRED_IDS:
        fail(f"DEFERRED drift: {sorted(deferred)}")
    change_log = (ROOT / "docs" / "governance" / "change-log.md").read_text(encoding="utf-8")
    if "CR-T1-005" not in change_log:
        fail("Week 3 authorization CR-T1-005 is missing")


def check_contracts_and_fixtures() -> list[Path]:
    schemas = {path.name for path in CONTRACT_ROOT.glob("*.schema.json")}
    if schemas != SCHEMA_NAMES:
        fail(f"Week 3 schema set mismatch: expected={sorted(SCHEMA_NAMES)} actual={sorted(schemas)}")
    schema_paths = [CONTRACT_ROOT / name for name in sorted(SCHEMA_NAMES)]
    ids: set[str] = set()
    for path in schema_paths:
        document = load_json(path)
        if document.get("$schema") != "https://json-schema.org/draft/2020-12/schema":
            fail(f"{path.name} is not JSON Schema 2020-12")
        if not document.get("$id") or document["$id"] in ids:
            fail(f"{path.name} has missing or duplicate $id")
        ids.add(document["$id"])

    fixtures = {path.name for path in (POC_ROOT / "fixtures").glob("*.json")}
    if fixtures != FIXTURE_NAMES:
        fail(f"Week 3 fixture set mismatch: expected={sorted(FIXTURE_NAMES)} actual={sorted(fixtures)}")
    fixture_paths = [POC_ROOT / "fixtures" / name for name in sorted(FIXTURE_NAMES)]
    for path in fixture_paths:
        document = load_json(path)
        if document.get("evidenceLevel") != "FAKE" or document.get("synthetic") is not True:
            fail(f"{path.name} must be synthetic FAKE")

    plans = [load_json(POC_ROOT / "fixtures" / name) for name in sorted(FIXTURE_NAMES) if name != "failed-seed-ledger.json"]
    if any(plan.get("fixtureVersion") != "3.0" or len(set(plan.get("seeds", []))) < 5 for plan in plans):
        fail("cross-fault plans must be version 3.0 with at least five unique seeds")
    if any(len(plan.get("faults", [])) < 5 for plan in plans):
        fail("cross-fault matrix is below minimum")

    ledger = load_json(POC_ROOT / "fixtures" / "failed-seed-ledger.json")
    observed = set(ledger.get("observedFailedSeeds", []))
    fixed = set(ledger.get("fixedFailedSeeds", []))
    adversarial = set(ledger.get("adversarialSeeds", []))
    all_plan_seeds = {seed for plan in plans for seed in plan["seeds"]}
    if ledger.get("ledgerVersion") != "3.0" or not all_plan_seeds.issubset(adversarial):
        fail("failed-seed ledger does not cover every adversarial plan seed")
    if observed - fixed:
        fail(f"unfixed observed failed seeds remain: {sorted(observed - fixed)}")
    return schema_paths + fixture_paths


def check_sources_are_isolated() -> list[Path]:
    paths = sorted((POC_ROOT / "src").glob("*.py")) + sorted((POC_ROOT / "tests").glob("*.py"))
    forbidden_modules = {"requests", "httpx", "urllib", "socket", "ftplib", "aiohttp"}
    local_modules = {path.stem for path in (POC_ROOT / "src").glob("*.py")}
    external_imports: set[str] = set()
    for path in paths:
        content = path.read_text(encoding="utf-8")
        tree = ast.parse(content, filename=str(path))
        imports: set[str] = set()
        for node in ast.walk(tree):
            if isinstance(node, ast.Import):
                imports.update(alias.name.split(".")[0] for alias in node.names)
            elif isinstance(node, ast.ImportFrom) and node.module:
                imports.add(node.module.split(".")[0])
            if isinstance(node, ast.Attribute) and node.attr in {"environ", "getenv"}:
                fail(f"{path.relative_to(ROOT)} may not read credentials/environment")
        found = sorted(imports & forbidden_modules)
        if found:
            fail(f"{path.relative_to(ROOT)} imports network modules: {found}")
        external_imports.update(imports - set(sys.stdlib_module_names) - local_modules)
        for match in re.finditer(r"CREATE\s+TABLE\s+(?:IF\s+NOT\s+EXISTS\s+)?([A-Za-z_][A-Za-z0-9_]*)", content, re.IGNORECASE):
            if not match.group(1).lower().startswith("syn_"):
                fail(f"{path.relative_to(ROOT)} creates non-synthetic table {match.group(1)}")
    if external_imports:
        fail(f"Week 3 introduced third-party Python imports: {sorted(external_imports)}")
    return paths


def check_sensitive_material(paths: list[Path]) -> tuple[int, int]:
    secret_patterns = {
        "private key": re.compile(r"-----BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY-----"),
        "GitHub token": re.compile(r"gh[pousr]_[A-Za-z0-9]{30,}"),
        "AWS key": re.compile(r"AKIA[0-9A-Z]{16}"),
        "generic sk token": re.compile(r"\bsk-[A-Za-z0-9]{20,}\b"),
    }
    pii_patterns = {
        "mainland mobile": re.compile(r"(?<!\d)1[3-9]\d{9}(?!\d)"),
        "mainland identity": re.compile(r"(?<!\d)\d{17}[0-9Xx](?!\d)"),
        "email": re.compile(r"\b[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}\b"),
    }
    for path in paths:
        content = path.read_text(encoding="utf-8")
        for label, pattern in {**secret_patterns, **pii_patterns}.items():
            if pattern.search(content):
                fail(f"{label} detected in {path.relative_to(ROOT)}")

    # Self-test the detectors without placing a complete sensitive-looking value in source text.
    samples = ["138" + "00138000", "110105" + "19491231002X", "person" + "@example.invalid"]
    if not all(any(pattern.search(sample) for pattern in pii_patterns.values()) for sample in samples):
        fail("PII detector self-test failed")
    return len(secret_patterns), len(pii_patterns)


def validate_evidence(path: Path, require_complete: bool = False) -> dict[str, Any]:
    document = load_json(path)
    if document.get("schemaVersion") != "3.0" or document.get("phase") != "T1-WEEK3":
        fail(f"{path} is not Week 3 evidence")
    if document.get("scope") != "INTERNAL_SYNTHETIC_CROSS_FAULT_ONLY":
        fail(f"{path} has invalid scope")
    level = document.get("evidenceLevel")
    if level not in {"STATIC", "FAKE"}:
        fail(f"{path} has forbidden evidence level")
    if document.get("baselineTag") != BASELINE_TAG or not re.fullmatch(r"[a-f0-9]{40}", document.get("commitSha", "")):
        fail(f"{path} has invalid baseline/commit")
    summary = document.get("failedSeedSummary", {})
    if summary.get("untracked") != 0:
        fail(f"{path} contains untracked failed seeds")
    if not document.get("limitations"):
        fail(f"{path} must disclose limitations")
    ids: set[str] = set()
    for result in document.get("results", []):
        requirement_id = result.get("requirementId")
        allowed = WEEK3_STATIC_IDS if level == "STATIC" else WEEK3_FAKE_IDS
        if requirement_id not in allowed or requirement_id in ids:
            fail(f"{path} has non-admitted or duplicate result {requirement_id}")
        ids.add(requirement_id)
        if result.get("result") not in {"PASS", "FAIL", "BLOCKED"}:
            fail(f"{path} has invalid result")
        for field in ("assertions", "iterations"):
            if not isinstance(result.get(field), int) or result[field] < 0:
                fail(f"{path} has invalid {field}")
        metrics = result.get("metrics")
        if not isinstance(metrics, dict) or metrics.get("failedSeeds", 0) != 0:
            fail(f"{path} has failed or missing seed metrics")
        if not all(re.fullmatch(r"sha256:[a-f0-9]{64}", value) for value in result.get("fixtureDigests", [])):
            fail(f"{path} has invalid fixture digest")
    if require_complete:
        expected = WEEK3_STATIC_IDS if level == "STATIC" else WEEK3_FAKE_IDS
        if ids != expected:
            fail(f"{path} incomplete: expected={sorted(expected)} actual={sorted(ids)}")
    encoded = json.dumps(document, ensure_ascii=False)
    for forbidden in ("SANDBOX_PASS", "REAL_DEVICE_PASS", "PHYSICAL_POWER_LOSS_PASS", "PILOT_PASS", "COMMERCIAL_PASS"):
        if forbidden in encoded:
            fail(f"{path} contains forbidden claim {forbidden}")
    return document


def seed_summary() -> dict[str, int]:
    ledger = load_json(POC_ROOT / "fixtures" / "failed-seed-ledger.json")
    observed = set(ledger["observedFailedSeeds"])
    fixed = set(ledger["fixedFailedSeeds"])
    return {"observed": len(observed), "fixed": len(fixed), "untracked": len(observed - fixed)}


def write_static_evidence(output: Path, inputs: list[Path], secret_rules: int, pii_rules: int) -> None:
    input_digests = sorted({digest(path) for path in inputs})
    results = [
        {
            "requirementId": "T1-SEC-001", "domain": "SECRET_PII_DEPENDENCY",
            "result": "PASS", "assertions": len(inputs) + secret_rules + pii_rules, "iterations": len(inputs),
            "metrics": {"secretPatternsDetected": 0, "piiPatternsDetected": 0, "networkModulesDetected": 0, "thirdPartyPythonImports": 0, "nonSyntheticTablesDetected": 0, "failedSeeds": 0},
            "fixtureDigests": input_digests,
        },
        {
            "requirementId": "T1-CI-001", "domain": "CI_EVIDENCE_CLASSIFICATION",
            "result": "PASS", "assertions": len(inputs), "iterations": len(inputs),
            "metrics": {"schemas": len(SCHEMA_NAMES), "fixtures": len(FIXTURE_NAMES), "blockedRequirementsUnchanged": len(BLOCKED_IDS), "untrackedFailedSeeds": 0, "failedSeeds": 0},
            "fixtureDigests": input_digests,
        },
    ]
    document = {
        "schemaVersion": "3.0", "phase": "T1-WEEK3", "scope": "INTERNAL_SYNTHETIC_CROSS_FAULT_ONLY",
        "evidenceLevel": "STATIC", "generatedAt": datetime.now(timezone.utc).isoformat().replace("+00:00", "Z"),
        "baselineTag": BASELINE_TAG, "commitSha": run_git("rev-parse", "HEAD"), "results": results,
        "failedSeedSummary": seed_summary(),
        "limitations": [
            "STATIC通过只证明范围、格式、依赖边界、Secret与PII规则和证据等级可机器校验",
            "未执行支付沙箱、Android实机、外设、真实网络、真实商户数据或正式商业业务",
            "STATIC证据不得替代FAKE、SANDBOX、REAL_DEVICE、PILOT或商业验收",
        ],
    }
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(document, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    validate_evidence(output, require_complete=True)


def merge_fake_evidence(paths: list[Path], output: Path) -> None:
    documents = [validate_evidence(path) for path in paths]
    if not documents or any(document["evidenceLevel"] != "FAKE" for document in documents):
        fail("merge inputs must be FAKE evidence")
    if len({document["commitSha"] for document in documents}) != 1:
        fail("cannot merge evidence from different commits")
    results: list[dict[str, Any]] = []
    limitations: list[str] = []
    for document in documents:
        results.extend(document["results"])
        for limitation in document["limitations"]:
            if limitation not in limitations:
                limitations.append(limitation)
    merged = {
        "schemaVersion": "3.0", "phase": "T1-WEEK3", "scope": "INTERNAL_SYNTHETIC_CROSS_FAULT_ONLY",
        "evidenceLevel": "FAKE", "generatedAt": datetime.now(timezone.utc).isoformat().replace("+00:00", "Z"),
        "baselineTag": BASELINE_TAG, "commitSha": documents[0]["commitSha"], "results": results,
        "failedSeedSummary": seed_summary(), "limitations": limitations,
    }
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(merged, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    validate_evidence(output, require_complete=True)


def write_manifest(evidence_paths: list[Path], output: Path) -> None:
    documents = [validate_evidence(path, require_complete=True) for path in evidence_paths]
    if len({document["commitSha"] for document in documents}) != 1:
        fail("manifest evidence commits differ")
    manifest = {
        "schemaVersion": "3.0", "phase": "T1-WEEK3", "baselineTag": BASELINE_TAG,
        "commitSha": documents[0]["commitSha"], "generatedAt": datetime.now(timezone.utc).isoformat().replace("+00:00", "Z"),
        "failedSeedSummary": seed_summary(),
        "files": [
            {"path": str(path.resolve().relative_to(ROOT)).replace("\\", "/"), "sha256": hashlib.sha256(path.read_bytes()).hexdigest(), "evidenceLevel": document["evidenceLevel"]}
            for path, document in sorted(zip(evidence_paths, documents), key=lambda item: str(item[0]))
        ],
    }
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def main() -> None:
    parser = argparse.ArgumentParser(description="Validate T1 Week 3 STATIC/FAKE gates")
    parser.add_argument("--output", type=Path, default=ROOT / "artifacts" / "t1" / "week3" / "static-evidence.json")
    parser.add_argument("--validate-evidence", type=Path, nargs="*")
    parser.add_argument("--merge-evidence", type=Path, nargs="*")
    parser.add_argument("--merged-output", type=Path)
    parser.add_argument("--manifest-output", type=Path)
    args = parser.parse_args()

    check_baseline_and_scope()
    check_rtm_boundary()
    inputs = check_contracts_and_fixtures()
    sources = check_sources_are_isolated()
    secret_rules, pii_rules = check_sensitive_material(inputs + sources)
    write_static_evidence(args.output, inputs + sources + [Path(__file__)], secret_rules, pii_rules)
    for path in args.validate_evidence or []:
        validate_evidence(path)
    if args.merge_evidence:
        if not args.merged_output:
            fail("--merged-output is required with --merge-evidence")
        merge_fake_evidence(args.merge_evidence, args.merged_output)
    if args.manifest_output:
        if not args.merged_output:
            fail("--merged-output is required with --manifest-output")
        write_manifest([args.output, args.merged_output], args.manifest_output)
    print(
        "T1 WEEK3 STATIC OK: "
        f"schemas={len(SCHEMA_NAMES)} fixtures={len(FIXTURE_NAMES)} sources={len(sources)} "
        f"fakeRequirements={len(WEEK3_FAKE_IDS)} blockersUnchanged={len(BLOCKED_IDS)} failedSeeds=0"
    )


if __name__ == "__main__":
    main()
