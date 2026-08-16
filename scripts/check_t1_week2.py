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
POC_ROOT = ROOT / "poc" / "t1-week2"
CONTRACT_ROOT = ROOT / "contracts" / "poc" / "t1" / "week2"
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
WEEK2_FAKE_IDS = {
    "T1-OFF-001",
    "T1-SYN-001",
    "T1-TEN-001",
    "T1-PAY-001",
    "T1-DPK-001",
    "T1-UPG-001",
}
WEEK2_STATIC_IDS = {"T1-SEC-001", "T1-CI-001"}
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
    "offline-probe.schema.json",
    "inbox-event.schema.json",
    "tenant-attack.schema.json",
    "data-package-manifest.schema.json",
    "upgrade-plan.schema.json",
    "evidence.schema.json",
}
FIXTURE_NAMES = {
    "offline-plan.json",
    "inbox-plan.json",
    "tenant-attack-plan.json",
    "data-package-plan.json",
    "upgrade-plan.json",
    "payment-matrix.json",
}


def fail(message: str) -> None:
    print(f"T1-WEEK2 ERROR: {message}", file=sys.stderr)
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
        fail(f"cannot load {path}: {exc}")


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
    changed.update(
        filter(None, run_git("-c", "core.quotepath=false", "ls-files", "--others", "--exclude-standard").splitlines())
    )
    exact = {
        "AGENTS.md",
        ".gitignore",
        ".github/workflows/t1-week1.yml",
        ".github/workflows/t1-week2.yml",
        "docs/governance/rtm.csv",
        "docs/governance/change-log.md",
        "docs/adr/README.md",
        "docs/adr/ADR-017-t1-risk-poc-scope-and-integration-depth.md",
        "scripts/check_contracts.py",
        "scripts/check_t1_prep.py",
        "scripts/check_t1_week1.py",
        "scripts/check_t1_week2.py",
    }
    prefixes = (
        "docs/t1-prep/",
        "docs/t1-week1/",
        "docs/t1-week2/",
        "contracts/poc/t1/",
        "poc/t1-week1/",
        "poc/t1-week2/",
    )
    unexpected = sorted(
        name for name in changed
        if name.replace("\\", "/") not in exact and not name.replace("\\", "/").startswith(prefixes)
    )
    if unexpected:
        fail(f"changes outside authorized T1 Week 2 STATIC/FAKE scope: {unexpected}")


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
    if "CR-T1-003" not in (ROOT / "docs" / "governance" / "change-log.md").read_text(encoding="utf-8"):
        fail("Week 2 authorization CR-T1-003 is missing")


def check_contracts() -> list[Path]:
    actual = {path.name for path in CONTRACT_ROOT.glob("*.schema.json")}
    if actual != SCHEMA_NAMES:
        fail(f"Week 2 schema set mismatch: expected={sorted(SCHEMA_NAMES)} actual={sorted(actual)}")
    paths: list[Path] = []
    ids: set[str] = set()
    for name in sorted(SCHEMA_NAMES):
        path = CONTRACT_ROOT / name
        document = load_json(path)
        if document.get("$schema") != "https://json-schema.org/draft/2020-12/schema":
            fail(f"{name} is not JSON Schema 2020-12")
        if not document.get("$id") or document["$id"] in ids:
            fail(f"{name} has missing or duplicate $id")
        ids.add(document["$id"])
        paths.append(path)
    return paths


def check_fixtures() -> list[Path]:
    actual = {path.name for path in (POC_ROOT / "fixtures").glob("*.json")}
    if actual != FIXTURE_NAMES:
        fail(f"Week 2 fixture set mismatch: expected={sorted(FIXTURE_NAMES)} actual={sorted(actual)}")
    paths = [POC_ROOT / "fixtures" / name for name in sorted(FIXTURE_NAMES)]
    documents = {path.name: load_json(path) for path in paths}
    for name, document in documents.items():
        if document.get("fixtureVersion") != "2.0" or document.get("evidenceLevel") != "FAKE":
            fail(f"{name} must be version 2.0 FAKE")
        if document.get("synthetic") is not True:
            fail(f"{name} must be synthetic")

    offline = documents["offline-plan.json"]
    if offline.get("repetitions", 0) < 20 or len(offline.get("crashPoints", [])) < 6:
        fail("offline plan is below crash minimum")
    if offline.get("pragmas") != {"journalMode": "WAL", "synchronous": "FULL", "foreignKeys": True}:
        fail("offline SQLite pragmas drifted")

    inbox = documents["inbox-plan.json"]
    if inbox.get("eventCount") != 10000 or len(inbox.get("seeds", [])) != 20:
        fail("Inbox plan must be 10,000 events x 20 seeds")
    if len(set(inbox["seeds"])) != 20 or inbox.get("conflictsPerSeed", 0) < 1:
        fail("Inbox seeds/conflicts are invalid")

    tenant = documents["tenant-attack-plan.json"]
    expected_entries = {"API", "MAPPER", "RAW_SQL", "BACKGROUND_JOB", "CACHE", "EXPORT", "OBJECT_STORAGE"}
    if set(tenant.get("entries", {})) != expected_entries or tenant.get("repetitions", 0) < 3:
        fail("tenant attack matrix is incomplete")

    package = documents["data-package-plan.json"]
    if package.get("fullCounts") != [10000, 100000] or package.get("incrementalPackages") != 20:
        fail("data package size/incremental matrix drifted")
    if package.get("faultRepetitions", 0) < 30 or package.get("atomicCrashRepetitions", 0) < 20:
        fail("data package fault matrix is below minimum")

    upgrade = documents["upgrade-plan.json"]
    if upgrade.get("repetitions", 0) < 20 or upgrade.get("normalUpgradeRepetitions", 0) < 30:
        fail("upgrade matrix is below minimum")

    payment = documents["payment-matrix.json"]
    cases = len(payment.get("categories", [])) * payment.get("variantsPerCategory", 0)
    if cases < 60 or payment.get("minimumCasesPerProvider") != 60:
        fail("payment Fake matrix must contain at least 60 cases per provider")
    profiles = load_json(ROOT / "poc" / "t1-week1" / "provider-profiles.json").get("providers", [])
    if len(profiles) != 10 or sum(profile.get("selectedForFake") is True for profile in profiles) != 5:
        fail("payment provider candidate/Fake selection drifted")
    if any(profile.get("sandboxStatus") != "BLOCKED" for profile in profiles):
        fail("payment sandbox was enabled without authorization")
    payment_source = (POC_ROOT / "src" / "payment_regression.py").read_text(encoding="utf-8")
    provider_codes = {profile.get("providerCode", "") for profile in profiles}
    if any(code and code in payment_source for code in provider_codes):
        fail("payment core contains a provider-specific branch/name")
    return paths


def check_sources_are_isolated() -> list[Path]:
    paths = sorted((POC_ROOT / "src").glob("*.py")) + sorted((POC_ROOT / "tests").glob("*.py"))
    forbidden_modules = {"requests", "httpx", "urllib", "socket", "ftplib", "aiohttp"}
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
        for match in re.finditer(r"CREATE\s+TABLE\s+(?:IF\s+NOT\s+EXISTS\s+)?([A-Za-z_][A-Za-z0-9_]*)", content, re.IGNORECASE):
            if not match.group(1).lower().startswith("syn_"):
                fail(f"{path.relative_to(ROOT)} creates non-synthetic table {match.group(1)}")
    return paths


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


def validate_evidence(path: Path, require_complete: bool = False) -> dict[str, Any]:
    document = load_json(path)
    if document.get("schemaVersion") != "2.0" or document.get("phase") != "T1-WEEK2":
        fail(f"{path} is not Week 2 evidence")
    if document.get("scope") != "INTERNAL_SYNTHETIC_ONLY":
        fail(f"{path} has invalid scope")
    level = document.get("evidenceLevel")
    if level not in {"STATIC", "FAKE"}:
        fail(f"{path} has forbidden evidence level")
    if document.get("baselineTag") != BASELINE_TAG or not re.fullmatch(r"[a-f0-9]{40}", document.get("commitSha", "")):
        fail(f"{path} has invalid baseline/commit")
    if not document.get("limitations"):
        fail(f"{path} must disclose limitations")
    ids: set[str] = set()
    for result in document.get("results", []):
        requirement_id = result.get("requirementId")
        allowed = WEEK2_STATIC_IDS if level == "STATIC" else WEEK2_FAKE_IDS
        if requirement_id not in allowed or requirement_id in ids:
            fail(f"{path} has non-admitted or duplicate result {requirement_id}")
        ids.add(requirement_id)
        if result.get("result") not in {"PASS", "FAIL", "BLOCKED"}:
            fail(f"{path} has invalid result")
        for field in ("assertions", "iterations"):
            if not isinstance(result.get(field), int) or result[field] < 0:
                fail(f"{path} has invalid {field}")
        if not isinstance(result.get("metrics"), dict):
            fail(f"{path} has invalid metrics")
        if not all(re.fullmatch(r"sha256:[a-f0-9]{64}", value) for value in result.get("fixtureDigests", [])):
            fail(f"{path} has invalid fixture digest")
    if require_complete:
        expected = WEEK2_STATIC_IDS if level == "STATIC" else WEEK2_FAKE_IDS
        if ids != expected:
            fail(f"{path} incomplete: expected={sorted(expected)} actual={sorted(ids)}")
    encoded = json.dumps(document, ensure_ascii=False)
    for forbidden in ("SANDBOX_PASS", "REAL_DEVICE_PASS", "PILOT_PASS", "COMMERCIAL_PASS"):
        if forbidden in encoded:
            fail(f"{path} contains forbidden claim {forbidden}")
    return document


def write_static_evidence(output: Path, inputs: list[Path]) -> None:
    input_digests = sorted({digest(path) for path in inputs})
    results = [
        {
            "requirementId": "T1-SEC-001",
            "domain": "SECURITY",
            "result": "PASS",
            "assertions": len(inputs),
            "iterations": len(inputs),
            "metrics": {"secretPatternsDetected": 0, "networkModulesDetected": 0, "nonSyntheticTablesDetected": 0},
            "fixtureDigests": input_digests,
        },
        {
            "requirementId": "T1-CI-001",
            "domain": "CI_EVIDENCE",
            "result": "PASS",
            "assertions": len(inputs),
            "iterations": len(inputs),
            "metrics": {"schemas": len(SCHEMA_NAMES), "fixtures": len(FIXTURE_NAMES), "blockedRequirementsUnchanged": len(BLOCKED_IDS)},
            "fixtureDigests": input_digests,
        },
    ]
    document = {
        "schemaVersion": "2.0",
        "phase": "T1-WEEK2",
        "scope": "INTERNAL_SYNTHETIC_ONLY",
        "evidenceLevel": "STATIC",
        "generatedAt": datetime.now(timezone.utc).isoformat().replace("+00:00", "Z"),
        "baselineTag": BASELINE_TAG,
        "commitSha": run_git("rev-parse", "HEAD"),
        "results": results,
        "limitations": [
            "STATIC通过只证明范围、格式、合成标记、无网络依赖和证据规则可机器校验",
            "未执行支付沙箱、Android实机、外设、真实商户数据或正式商业业务",
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
    commit_shas = {document["commitSha"] for document in documents}
    if len(commit_shas) != 1:
        fail("cannot merge evidence from different commits")
    results: list[dict[str, Any]] = []
    limitations: list[str] = []
    for document in documents:
        results.extend(document["results"])
        for limitation in document["limitations"]:
            if limitation not in limitations:
                limitations.append(limitation)
    merged = {
        "schemaVersion": "2.0",
        "phase": "T1-WEEK2",
        "scope": "INTERNAL_SYNTHETIC_ONLY",
        "evidenceLevel": "FAKE",
        "generatedAt": datetime.now(timezone.utc).isoformat().replace("+00:00", "Z"),
        "baselineTag": BASELINE_TAG,
        "commitSha": documents[0]["commitSha"],
        "results": results,
        "limitations": limitations,
    }
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(merged, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    validate_evidence(output, require_complete=True)


def write_manifest(evidence_paths: list[Path], output: Path) -> None:
    documents = [validate_evidence(path, require_complete=True) for path in evidence_paths]
    if len({document["commitSha"] for document in documents}) != 1:
        fail("manifest evidence commits differ")
    manifest = {
        "schemaVersion": "2.0",
        "phase": "T1-WEEK2",
        "baselineTag": BASELINE_TAG,
        "commitSha": documents[0]["commitSha"],
        "generatedAt": datetime.now(timezone.utc).isoformat().replace("+00:00", "Z"),
        "files": [
            {
                "path": str(path.resolve().relative_to(ROOT)).replace("\\", "/"),
                "sha256": hashlib.sha256(path.read_bytes()).hexdigest(),
                "evidenceLevel": document["evidenceLevel"],
            }
            for path, document in sorted(zip(evidence_paths, documents), key=lambda item: str(item[0]))
        ],
    }
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def main() -> None:
    parser = argparse.ArgumentParser(description="Validate T1 Week 2 STATIC/FAKE gates")
    parser.add_argument(
        "--output",
        type=Path,
        default=ROOT / "artifacts" / "t1" / "week2" / "static-evidence.json",
    )
    parser.add_argument("--validate-evidence", type=Path, nargs="*")
    parser.add_argument("--merge-evidence", type=Path, nargs="*")
    parser.add_argument("--merged-output", type=Path)
    parser.add_argument("--manifest-output", type=Path)
    args = parser.parse_args()

    check_baseline_and_scope()
    check_rtm_boundary()
    contracts = check_contracts()
    fixtures = check_fixtures()
    sources = check_sources_are_isolated()
    security_paths = contracts + fixtures + sources + [Path(__file__)]
    check_no_secret_material(security_paths)
    write_static_evidence(args.output, security_paths)

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
        "T1 WEEK2 STATIC OK: "
        f"schemas={len(contracts)} fixtures={len(fixtures)} sources={len(sources)} "
        f"week2FakeRequirements={len(WEEK2_FAKE_IDS)} blockersUnchanged={len(BLOCKED_IDS)}"
    )


if __name__ == "__main__":
    main()
