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
PRIOR_ACCEPTED = {
    "T2-IAM-001", "T2-ORG-001", "T2-RBAC-001", "T2-CFG-001", "T2-AUD-001", "T2-SEC-001",
    "T2-OBS-001", "T2-MIG-001", "T2-PRD-001", "T2-PRD-002", "T2-PRD-003", "T2-PRD-004",
    "T2-PRC-001", "T2-PRC-002", "T2-DPK-001", "T2-POS-001", "T2-POS-002", "T2-POS-003",
    "T2-POS-004", "T2-POS-005", "T2-ORD-001", "T2-ORD-002", "T2-OFF-001", "T2-SYN-001",
    "T2-PAY-001", "T2-PAY-003", "T2-REF-001", "T2-REC-001",
}


def fail(message: str) -> None:
    raise SystemExit(f"T2-GATE4A EVIDENCE ERROR: {message}")


def digest(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def surefire(root: Path) -> dict[str, int]:
    totals = {"tests": 0, "failures": 0, "errors": 0, "skipped": 0}
    reports = sorted(root.rglob("TEST-*.xml"))
    if not reports:
        fail(f"no Surefire reports under {root}")
    for report in reports:
        suite = ET.parse(report).getroot()
        for key in totals:
            totals[key] += int(float(suite.attrib.get(key, "0")))
    if totals["tests"] <= 0 or any(totals[key] for key in ("failures", "errors", "skipped")):
        fail(f"invalid Surefire totals under {root}: {totals}")
    return totals


def flutter_tests(root: Path) -> dict[str, int | bool]:
    reports = list(root.rglob("flutter-tests.jsonl"))
    if len(reports) != 1:
        fail(f"expected one Flutter machine report under {root}, got {len(reports)}")
    tests, done = 0, False
    for line in reports[0].read_text(encoding="utf-8-sig").splitlines():
        try:
            event = json.loads(line)
        except json.JSONDecodeError:
            continue
        if not isinstance(event, dict):
            continue
        if event.get("type") == "testDone" and not event.get("hidden") and not event.get("skipped"):
            if event.get("result") != "success":
                fail(f"Flutter test failed under {root}")
            tests += 1
        if event.get("type") == "done":
            done = event.get("success") is True
    if tests < 30 or not done:
        fail(f"incomplete Flutter report under {root}: tests={tests} done={done}")
    return {"tests": tests, "success": done}


def jacoco(root: Path) -> dict[str, float | int]:
    reports = [path for path in root.rglob("jacoco.xml") if "jshpos-inventory" in path.as_posix()]
    if len(reports) != 1:
        fail(f"expected one inventory JaCoCo report, got {len(reports)}")
    counters = {node.attrib["type"]: node.attrib for node in ET.parse(reports[0]).getroot().findall("counter")}
    result: dict[str, float | int] = {}
    for name, minimum in (("LINE", 0.90), ("BRANCH", 0.85)):
        counter = counters.get(name)
        if counter is None:
            fail(f"inventory JaCoCo missing {name}")
        missed, covered = int(counter["missed"]), int(counter["covered"])
        ratio = covered / (covered + missed)
        if ratio < minimum:
            fail(f"inventory JaCoCo {name} {ratio:.4f} below {minimum:.2f}")
        result[f"{name.lower()}Ratio"] = round(ratio, 6)
        result[f"{name.lower()}Covered"] = covered
        result[f"{name.lower()}Missed"] = missed
    return result


def requirements(stage: str) -> dict[str, object]:
    with (ROOT / "docs/governance/rtm.csv").open(encoding="utf-8-sig", newline="") as handle:
        rows = {row["requirement_id"]: row for row in csv.DictReader(handle)}
    if any(rows[item]["status"] != "ACCEPTED" for item in PRIOR_ACCEPTED):
        fail("all prior Gate requirements must remain ACCEPTED")
    expected = "IN_PROGRESS" if stage == "admitted" else "VERIFIED"
    for item in ("T2-INV-001", "T2-INV-002", "T2-INV-004"):
        if rows[item]["status"] != expected:
            fail(f"{item} must be {expected}")
    for item in ("T2-INV-003", "T2-PUR-001", "T2-CST-001", "T2-TRF-001"):
        if rows[item]["status"] != "DRAFT":
            fail(f"{item} must remain DRAFT")
    if rows["T2-PAY-002"]["status"] != "BLOCKED":
        fail("T2-PAY-002 must remain BLOCKED")
    return {"priorAccepted": len(PRIOR_ACCEPTED), "gate4a": expected, "paymentSandbox": "BLOCKED"}


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--bundle-dir", type=Path, required=True)
    parser.add_argument("--stage", choices=("admitted", "closure"), required=True)
    args = parser.parse_args()
    bundle = args.bundle_dir if args.bundle_dir.is_absolute() else ROOT / args.bundle_dir
    directories = ("governance", "server", "mysql", "tenant", "vectors", "web", "pos-linux", "pos-windows", "security")
    for directory in directories:
        if not (bundle / directory).is_dir():
            fail(f"missing evidence directory {directory}")
    tests = {
        "server": surefire(bundle / "server"), "mysql": surefire(bundle / "mysql"),
        "tenant": surefire(bundle / "tenant"), "flutterLinux": flutter_tests(bundle / "pos-linux"),
        "flutterWindows": flutter_tests(bundle / "pos-windows"),
    }
    java_coverage = jacoco(bundle / "server")
    flutter_reports = list((bundle / "pos-linux").rglob("flutter-coverage.json"))
    if len(flutter_reports) != 1:
        fail("Flutter Gate 4A coverage report missing")
    flutter_coverage = json.loads(flutter_reports[0].read_text(encoding="utf-8"))
    if flutter_coverage.get("lineRatio", 0) < 0.90:
        fail("Flutter Gate 4A line coverage below 90%")
    attacks = list((bundle / "tenant").rglob("tenant-attacks.json"))
    if len(attacks) != 1:
        fail("tenant attack report missing")
    attack = json.loads(attacks[0].read_text(encoding="utf-8"))
    if len(attack.get("surfaces", [])) != 12 or any(item.get("result") != "PASS" for item in attack["surfaces"]):
        fail("tenant attack matrix is incomplete")
    vector_reports = list((bundle / "vectors").rglob("fixed-matrix.json"))
    if len(vector_reports) != 1:
        fail("fixed inventory vector matrix is missing")
    vectors = json.loads(vector_reports[0].read_text(encoding="utf-8"))
    if vectors.get("vectorCount") != 16 or vectors.get("providerNetworkCalls") != 0:
        fail("fixed vector matrix is incomplete or payment network boundary changed")
    apk = list((bundle / "pos-linux").rglob("app-debug.apk"))
    if len(apk) != 1 or apk[0].stat().st_size < 1_000_000:
        fail("debug APK artifact missing or unexpectedly small")
    for name in ("flutter-cyclonedx.json", "flutter-license-inventory.json"):
        if len(list((bundle / "pos-linux").rglob(name))) != 1:
            fail(f"Flutter supply-chain artifact missing {name}")
    files = []
    for path in sorted(bundle.rglob("*")):
        if path.is_file() and path.name != "t2-gate4a-evidence-index.json":
            files.append({"path": path.relative_to(bundle).as_posix(), "size": path.stat().st_size,
                          "sha256": digest(path)})
    index = {
        "schemaVersion": "1.0", "phase": "T2-GATE4A", "stage": args.stage,
        "evidenceLevel": "STATIC+UNIT+MYSQL_INTEGRATION+FLUTTER_REGRESSION+SYNTHETIC_VECTOR",
        "generatedAt": datetime.now(timezone.utc).isoformat().replace("+00:00", "Z"),
        "baselineTag": "t2-prep-baseline-2026-08-16", "branchStart": "451a48f982d3a88c68ff20ca283a190e7bf53ccf",
        "commitSha": subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=ROOT, text=True).strip(),
        "requirements": requirements(args.stage), "tests": tests,
        "coverage": {"server": java_coverage, "flutter": flutter_coverage},
        "tenantAttackSurfaces": 12, "fixedVectors": 16, "providerNetworkCalls": 0,
        "apk": {"size": apk[0].stat().st_size, "sha256": digest(apk[0])},
        "externalEvidence": {"sandbox": 0, "realDevice": 0, "pilot": 0},
        "files": files,
        "limitations": [
            "Inventory evidence uses two fictional tenants, synthetic orders/refunds and MySQL integration",
            "Stocktake, procurement, cost, transfer and promotion runtime are not implemented",
            "Provider network, payment SANDBOX, Android physical power-loss, REAL_DEVICE and PILOT evidence remain zero",
            "Synthetic vectors do not establish production capacity or commercial acceptance",
            "This evidence does not establish Alpha, pilot readiness, or commercial usability",
        ],
    }
    (bundle / "t2-gate4a-evidence-index.json").write_text(
        json.dumps(index, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(f"T2-GATE4A EVIDENCE OK: stage={args.stage} files={len(files)} serverTests={tests['server']['tests']} "
          f"flutterLinux={tests['flutterLinux']['tests']} flutterWindows={tests['flutterWindows']['tests']} "
          f"serverBranch={java_coverage['branchRatio']:.4f} flutterLine={flutter_coverage['lineRatio']:.4f}")


if __name__ == "__main__":
    main()
