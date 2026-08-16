from __future__ import annotations

import argparse
import ast
import csv
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
RTM = ROOT / "docs" / "governance" / "rtm.csv"
ACTIVE_IDS = {"T1-HWD-001", "T1-OFF-001", "T1-SYN-001", "T1-TEN-001", "T1-PAY-001", "T1-DPK-001", "T1-UPG-001", "T1-SEC-001", "T1-CI-001"}
BLOCKED_IDS = {"T1-HWD-002", "T1-PRN-001", "T1-SCN-001", "T1-SCL-001", "T1-IO-001", "T1-PAY-002", "T1-PAR-001"}
DEFERRED_IDS = {"T1-JSH-001", "T1-LIC-001"}
ACCEPTED_IDS = {"T1-GOV-001", "T1-SCP-001"}
READY_IDS = {"T1-UAT-001"}
REQUIRED_DOCS = {
    "01_T1证据总清单.md",
    "02_T1风险差距成本与T2建议.md",
    "03_T1可重复运行手册.md",
}


def fail(message: str) -> None:
    print(f"T1-WEEK4 ERROR: {message}", file=sys.stderr)
    raise SystemExit(1)


def git(*args: str) -> str:
    completed = subprocess.run(
        ["git", *args], cwd=ROOT, check=False, capture_output=True, text=True,
        encoding="utf-8", errors="replace",
    )
    if completed.returncode != 0:
        fail(f"git {' '.join(args)} failed: {completed.stderr.strip()}")
    return completed.stdout.strip()


def check_baseline_and_scope() -> None:
    if git("cat-file", "-t", BASELINE_TAG) != "tag" or git("rev-list", "-n", "1", BASELINE_TAG) != BASELINE_COMMIT:
        fail("approved annotated T0 baseline changed")
    if subprocess.run(["git", "merge-base", "--is-ancestor", BASELINE_TAG, "HEAD"], cwd=ROOT).returncode != 0:
        fail("HEAD is not based on approved T0 baseline")
    changed = set(filter(None, git("-c", "core.quotepath=false", "diff", "--name-only", BASELINE_TAG).splitlines()))
    changed.update(filter(None, git("-c", "core.quotepath=false", "ls-files", "--others", "--exclude-standard").splitlines()))
    exact = {
        "AGENTS.md", ".gitignore", ".github/workflows/t1-week1.yml", ".github/workflows/t1-week2.yml",
        ".github/workflows/t1-week3.yml", ".github/workflows/t1-week4.yml",
        "docs/governance/rtm.csv", "docs/governance/change-log.md", "docs/adr/README.md",
        "docs/adr/ADR-017-t1-risk-poc-scope-and-integration-depth.md",
        "docs/adr/ADR-018-t1-exit-and-t2-entry-recommendation.md",
        "scripts/check_contracts.py", "scripts/check_t1_prep.py", "scripts/check_t1_week1.py",
        "scripts/check_t1_week2.py", "scripts/check_t1_week3.py", "scripts/check_t1_week4.py",
        "scripts/run_t1_reproducibility.py", "scripts/compare_t1_reproducibility.py",
        "scripts/build_t1_poc_inventory.py",
    }
    prefixes = (
        "docs/t1-prep/", "docs/t1-week1/", "docs/t1-week2/", "docs/t1-week3/", "docs/t1-week4/",
        "contracts/poc/t1/", "poc/t1-week1/", "poc/t1-week2/", "poc/t1-week3/",
    )
    unexpected = sorted(path for path in changed if path.replace("\\", "/") not in exact and not path.replace("\\", "/").startswith(prefixes))
    if unexpected:
        fail(f"changes outside authorized Week 4 scope: {unexpected}")
    production = sorted(path for path in changed if path.startswith(("server/", "admin-web/", "pos-flutter/", "packages/pos_device_adapter/", "infra/")))
    if production:
        fail(f"Week 4 changed production areas: {production}")


def check_rtm_and_governance() -> None:
    with RTM.open(encoding="utf-8-sig", newline="") as handle:
        rows = {row["requirement_id"]: row for row in csv.DictReader(handle) if row["requirement_id"].startswith("T1-")}
    groups: dict[str, set[str]] = {}
    for key, row in rows.items():
        groups.setdefault(row["status"], set()).add(key)
    expected = {"IN_PROGRESS": ACTIVE_IDS, "BLOCKED": BLOCKED_IDS, "DEFERRED": DEFERRED_IDS, "ACCEPTED": ACCEPTED_IDS, "READY": READY_IDS}
    for status, ids in expected.items():
        if groups.get(status, set()) != ids:
            fail(f"{status} drift: expected={sorted(ids)} actual={sorted(groups.get(status, set()))}")
    change_log = (ROOT / "docs" / "governance" / "change-log.md").read_text(encoding="utf-8")
    if "CR-T1-007" not in change_log:
        fail("Week 4 authorization CR-T1-007 is missing")
    adr = (ROOT / "docs" / "adr" / "ADR-018-t1-exit-and-t2-entry-recommendation.md").read_text(encoding="utf-8")
    if "状态：Proposed" not in adr or "待项目发起人确认" not in adr:
        fail("ADR-018 must remain Proposed before sponsor confirmation")


def check_docs_and_workflow() -> None:
    doc_root = ROOT / "docs" / "t1-week4"
    actual = {path.name for path in doc_root.glob("*.md")}
    if not REQUIRED_DOCS.issubset(actual):
        fail(f"Week 4 exit documents missing: {sorted(REQUIRED_DOCS - actual)}")
    corpus = "\n".join((doc_root / name).read_text(encoding="utf-8") for name in sorted(REQUIRED_DOCS))
    for term in ("STATIC", "FAKE", "BLOCKED", "SANDBOX", "REAL_DEVICE", "T2-Prep", "不得"):
        if term not in corpus:
            fail(f"Week 4 document corpus missing {term}")
    report = doc_root / "04_T1_Week4周门禁暨退出评审准备报告.md"
    if report.exists() and "AWAITING SP CONFIRMATION" not in report.read_text(encoding="utf-8"):
        fail("Week 4 report must wait for sponsor confirmation")

    workflow = (ROOT / ".github" / "workflows" / "t1-week4.yml").read_text(encoding="utf-8")
    for token in ("ubuntu-latest", "windows-latest", "run_t1_reproducibility.py", "compare_t1_reproducibility.py", "build_t1_poc_inventory.py"):
        if token not in workflow:
            fail(f"Week 4 workflow missing {token}")
    if re.search(r"continue-on-error:\s*true", workflow, re.IGNORECASE) or "|| true" in workflow:
        fail("Week 4 workflow contains a gate bypass")


def check_poc_boundaries() -> dict[str, int]:
    paths = sorted(ROOT.glob("poc/t1-week*/src/*.py")) + sorted(ROOT.glob("poc/t1-week*/tests/*.py"))
    forbidden_network = {"requests", "httpx", "urllib", "socket", "ftplib", "aiohttp"}
    network_imports = credential_reads = non_synthetic_tables = third_party = 0
    local_modules = {path.stem for path in ROOT.glob("poc/t1-week*/src/*.py")}
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
                credential_reads += 1
        network_imports += len(imports & forbidden_network)
        third_party += len(imports - set(sys.stdlib_module_names) - local_modules)
        for match in re.finditer(r"CREATE\s+TABLE\s+(?:IF\s+NOT\s+EXISTS\s+)?([A-Za-z_][A-Za-z0-9_]*)", content, re.IGNORECASE):
            non_synthetic_tables += not match.group(1).lower().startswith("syn_")
    metrics = {
        "pythonFiles": len(paths), "networkImports": network_imports, "credentialReads": credential_reads,
        "nonSyntheticTables": non_synthetic_tables, "thirdPartyPythonImports": third_party,
    }
    if any(metrics[key] for key in ("networkImports", "credentialReads", "nonSyntheticTables", "thirdPartyPythonImports")):
        fail(f"PoC boundary audit failed: {metrics}")
    return metrics


def check_fixture_usage() -> int:
    fixtures = sorted(ROOT.glob("poc/t1-week*/fixtures/*.json"))
    reference_paths = (
        sorted(ROOT.glob("poc/t1-week*/src/*.py"))
        + sorted(ROOT.glob("poc/t1-week*/tests/*.py"))
        + sorted(ROOT.glob("scripts/check_t1_week*.py"))
    )
    corpus = "\n".join(path.read_text(encoding="utf-8") for path in reference_paths)
    unused = [str(path.relative_to(ROOT)) for path in fixtures if path.name not in corpus]
    if unused:
        fail(f"unused T1 fixtures detected: {unused}")
    return len(fixtures)


def validate_comparison(path: Path) -> dict[str, Any]:
    try:
        document = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as exc:
        fail(f"cannot load comparison evidence {path}: {exc}")
    required = {
        "schemaVersion": "4.0", "phase": "T1-WEEK4", "evidenceLevel": "STATIC",
        "sourceEvidenceLevel": "FAKE", "baselineTag": BASELINE_TAG, "reproducible": True,
    }
    for key, value in required.items():
        if document.get(key) != value:
            fail(f"comparison evidence has invalid {key}")
    if document.get("platforms") != ["ubuntu", "windows"] or document.get("failedSeedSummary", {}).get("untracked") != 0:
        fail("comparison platforms or failed seed summary invalid")
    for key in ("normalizedEvidenceSha256", "inputTreeDigest", "failedSeedLedgerDigest"):
        if not re.fullmatch(r"[a-f0-9]{64}", document.get(key, "")):
            fail(f"comparison has invalid {key}")
    return document


def main() -> None:
    parser = argparse.ArgumentParser(description="Validate T1 Week 4 closure boundaries")
    parser.add_argument("--output", type=Path, default=ROOT / "artifacts" / "t1" / "week4" / "static-audit.json")
    parser.add_argument("--validate-comparison", type=Path)
    args = parser.parse_args()
    check_baseline_and_scope()
    check_rtm_and_governance()
    check_docs_and_workflow()
    metrics = check_poc_boundaries()
    metrics["fixturesReferenced"] = check_fixture_usage()
    comparison = validate_comparison(args.validate_comparison) if args.validate_comparison else None
    document = {
        "schemaVersion": "4.0", "phase": "T1-WEEK4", "evidenceLevel": "STATIC",
        "generatedAt": datetime.now(timezone.utc).isoformat().replace("+00:00", "Z"),
        "baselineTag": BASELINE_TAG, "commitSha": git("rev-parse", "HEAD"),
        "result": "PASS", "metrics": {**metrics, "blockedRequirementsUnchanged": len(BLOCKED_IDS), "comparisonValidated": int(comparison is not None)},
        "limitations": [
            "Week 4静态审计和双平台复现只覆盖内部STATIC/FAKE风险PoC",
            "七个外部实证需求继续BLOCKED且没有绿色占位",
            "本证据不得替代SANDBOX、REAL_DEVICE、物理断电、PILOT或商业验收",
        ],
    }
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(document, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(
        "T1 WEEK4 STATIC OK: "
        f"pythonFiles={metrics['pythonFiles']} externalBoundaries=0 blockersUnchanged={len(BLOCKED_IDS)} "
        f"comparisonValidated={int(comparison is not None)}"
    )


if __name__ == "__main__":
    main()
