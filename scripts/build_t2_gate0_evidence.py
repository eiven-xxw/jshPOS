from __future__ import annotations

import argparse
import csv
import hashlib
import json
import subprocess
import xml.etree.ElementTree as ET
from datetime import datetime, timezone
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
GATE0_IDS = {
    "T2-IAM-001", "T2-ORG-001", "T2-RBAC-001", "T2-CFG-001",
    "T2-AUD-001", "T2-SEC-001", "T2-OBS-001", "T2-MIG-001",
}
GATE1_IDS = {
    "T2-PRD-001", "T2-PRD-002", "T2-PRD-003", "T2-PRD-004",
    "T2-PRC-001", "T2-PRC-002", "T2-DPK-001",
}


def sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def fail(message: str) -> None:
    raise SystemExit(f"T2-GATE0 EVIDENCE ERROR: {message}")


def collect_surefire(root: Path) -> dict[str, int]:
    totals = {"tests": 0, "failures": 0, "errors": 0, "skipped": 0}
    reports = sorted(root.rglob("TEST-*.xml"))
    if not reports:
        fail(f"no Surefire XML under {root}")
    for report in reports:
        suite = ET.parse(report).getroot()
        for key in totals:
            totals[key] += int(float(suite.attrib.get(key, "0")))
    if totals["tests"] <= 0 or any(totals[key] for key in ("failures", "errors", "skipped")):
        fail(f"invalid test totals under {root}: {totals}")
    return totals


def collect_junit(root: Path) -> dict[str, int]:
    totals = {"tests": 0, "failures": 0, "errors": 0, "skipped": 0}
    reports = sorted(root.rglob("gate0-vitest-junit.xml"))
    if len(reports) != 1:
        fail(f"expected one Web JUnit report under {root}, got {len(reports)}")
    root_node = ET.parse(reports[0]).getroot()
    suites = [root_node] if root_node.tag == "testsuite" else list(root_node.findall("testsuite"))
    if not suites:
        fail(f"Web JUnit has no test suites under {root}")
    for suite in suites:
        for key in totals:
            totals[key] += int(float(suite.attrib.get(key, "0")))
    if totals["tests"] <= 0 or any(totals[key] for key in ("failures", "errors", "skipped")):
        fail(f"invalid Web test totals under {root}: {totals}")
    return totals


def coverage(root: Path) -> dict[str, float | int]:
    reports = list(root.rglob("jacoco.xml"))
    if len(reports) != 1:
        fail(f"expected one JaCoCo report, got {len(reports)}")
    report = ET.parse(reports[0]).getroot()
    counters = {node.attrib["type"]: node.attrib for node in report.findall("counter")}
    result: dict[str, float | int] = {}
    for name, minimum in (("LINE", 0.90), ("BRANCH", 0.85)):
        counter = counters.get(name)
        if not counter:
            fail(f"JaCoCo missing {name} counter")
        missed, covered = int(counter["missed"]), int(counter["covered"])
        ratio = covered / (covered + missed)
        if ratio < minimum:
            fail(f"JaCoCo {name} {ratio:.4f} below {minimum:.2f}")
        result[f"{name.lower()}Covered"] = covered
        result[f"{name.lower()}Missed"] = missed
        result[f"{name.lower()}Ratio"] = round(ratio, 6)
    return result


def requirement_state() -> dict[str, object]:
    with (ROOT / "docs/governance/rtm.csv").open(encoding="utf-8-sig", newline="") as handle:
        rows = {row["requirement_id"]: row for row in csv.DictReader(handle)}
    gate0 = {item: rows[item]["status"] for item in sorted(GATE0_IDS)}
    gate1 = {item: rows[item]["status"] for item in sorted(GATE1_IDS)}
    if set(gate0.values()) - {"IN_PROGRESS", "IMPLEMENTED", "VERIFIED"}:
        fail(f"Gate 0 has invalid closure-candidate states: {gate0}")
    if set(gate1.values()) != {"DRAFT"}:
        fail(f"Gate 1 changed from DRAFT: {gate1}")
    return {"gate0": gate0, "gate1": gate1}


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--bundle-dir", type=Path, required=True)
    args = parser.parse_args()
    bundle = args.bundle_dir if args.bundle_dir.is_absolute() else ROOT / args.bundle_dir
    for directory in ("governance", "server", "mysql", "tenant", "web", "security"):
        if not (bundle / directory).is_dir():
            fail(f"missing evidence directory {directory}")

    server_tests = collect_surefire(bundle / "server")
    mysql_tests = collect_surefire(bundle / "mysql")
    tenant_tests = collect_surefire(bundle / "tenant")
    web_tests = collect_junit(bundle / "web")
    jacoco = coverage(bundle / "server")
    attack_reports = list((bundle / "tenant").rglob("tenant-attacks.json"))
    if len(attack_reports) != 1:
        fail("tenant attack matrix missing")
    attack = json.loads(attack_reports[0].read_text(encoding="utf-8"))
    if len(attack.get("surfaces", [])) != 7 or any(item.get("result") != "PASS" for item in attack["surfaces"]):
        fail("tenant attack matrix is incomplete")

    files = []
    for path in sorted(bundle.rglob("*")):
        if path.is_file() and path.name != "t2-gate0-evidence-index.json":
            files.append({
                "path": path.relative_to(bundle).as_posix(),
                "size": path.stat().st_size,
                "sha256": sha256(path),
            })
    if not files:
        fail("evidence bundle is empty")

    index = {
        "schemaVersion": "1.0",
        "phase": "T2-GATE0-S0",
        "evidenceLevel": "STATIC+UNIT+INTEGRATION",
        "generatedAt": datetime.now(timezone.utc).isoformat().replace("+00:00", "Z"),
        "baselineTag": "t2-prep-baseline-2026-08-16",
        "branchStart": "7fe4391069d8ee6c641d1b3e509d9f90050be5ef",
        "commitSha": subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=ROOT, text=True).strip(),
        "requirements": requirement_state(),
        "tests": {
            "server": server_tests,
            "mysql": mysql_tests,
            "tenantSecurity": tenant_tests,
            "web": web_tests,
        },
        "coverage": jacoco,
        "tenantAttackSurfaces": 7,
        "externalEvidence": {"sandbox": 0, "realDevice": 0, "pilot": 0},
        "files": files,
        "limitations": [
            "Gate 0 platform foundation only",
            "Gate 1 product/price/data-package artifacts are DRAFT contracts only",
            "does not contain SANDBOX REAL_DEVICE PILOT or commercial acceptance",
        ],
    }
    target = bundle / "t2-gate0-evidence-index.json"
    target.write_text(json.dumps(index, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(
        f"T2-GATE0 EVIDENCE OK: files={len(files)} serverTests={server_tests['tests']} "
        f"mysqlTests={mysql_tests['tests']} tenantTests={tenant_tests['tests']} "
        f"webTests={web_tests['tests']} "
        f"line={jacoco['lineRatio']:.4f} branch={jacoco['branchRatio']:.4f}"
    )


if __name__ == "__main__":
    main()
