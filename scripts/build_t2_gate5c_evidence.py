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
GATE5C = ("T2-MEM-001", "T2-MEM-002")
REPORT_DRAFT = ("T2-RPT-001", "T2-RPT-002")


def fail(message: str) -> None:
    raise SystemExit(f"T2-GATE5C EVIDENCE ERROR: {message}")


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


def flutter(root: Path) -> dict[str, int | bool]:
    reports = list(root.rglob("flutter-tests.jsonl"))
    if len(reports) != 1:
        fail(f"expected one Flutter report under {root}, got {len(reports)}")
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
                fail(f"Flutter failure under {root}")
            tests += 1
        if event.get("type") == "done":
            done = event.get("success") is True
    if tests < 60 or not done:
        fail(f"incomplete Flutter report under {root}: tests={tests} done={done}")
    return {"tests": tests, "success": done}


def jacoco(root: Path) -> dict[str, float | int]:
    reports = [path for path in root.rglob("jacoco.xml") if "jshpos-member" in path.as_posix()]
    if len(reports) != 1:
        fail(f"expected one Member JaCoCo report, got {len(reports)}")
    counters = {node.attrib["type"]: node.attrib for node in ET.parse(reports[0]).getroot().findall("counter")}
    result: dict[str, float | int] = {}
    for name, minimum in (("LINE", 0.90), ("BRANCH", 0.85)):
        value = counters.get(name)
        if value is None:
            fail(f"Member JaCoCo missing {name}")
        missed, covered = int(value["missed"]), int(value["covered"])
        ratio = covered / (covered + missed)
        if ratio < minimum:
            fail(f"Member JaCoCo {name} {ratio:.4f} below {minimum:.2f}")
        result[f"{name.lower()}Ratio"] = round(ratio, 6)
        result[f"{name.lower()}Covered"] = covered
        result[f"{name.lower()}Missed"] = missed
    return result


def requirement_state() -> dict[str, object]:
    with (ROOT / "docs/governance/rtm.csv").open(encoding="utf-8-sig", newline="") as handle:
        rows = {row["requirement_id"]: row for row in csv.DictReader(handle)}
    if any(rows[item]["status"] != "VERIFIED" for item in GATE5C):
        fail("Gate 5C requirements must remain VERIFIED pending sponsor confirmation")
    if any(rows[item]["status"] != "DRAFT" for item in REPORT_DRAFT):
        fail("report requirements must remain DRAFT")
    if rows["T2-PAY-002"]["status"] != "BLOCKED":
        fail("T2-PAY-002 must remain BLOCKED")
    return {"gate5c": "VERIFIED", "reporting": "DRAFT", "paymentSandbox": "BLOCKED"}


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--bundle-dir", type=Path, required=True)
    args = parser.parse_args()
    bundle = args.bundle_dir if args.bundle_dir.is_absolute() else ROOT / args.bundle_dir
    directories = ("governance", "server", "mysql", "tenant", "vectors", "web",
                   "pos-linux", "pos-windows", "security")
    for name in directories:
        if not (bundle / name).is_dir():
            fail(f"missing evidence directory {name}")
    tests = {"server": surefire(bundle / "server"), "mysql": surefire(bundle / "mysql"),
             "tenant": surefire(bundle / "tenant"), "flutterLinux": flutter(bundle / "pos-linux"),
             "flutterWindows": flutter(bundle / "pos-windows")}
    member_coverage = jacoco(bundle / "server")
    coverage_files = list((bundle / "pos-linux").rglob("flutter-*-coverage.json"))
    if len(coverage_files) != 3:
        fail("Gate 5A, Gate 5B and Gate 5C Flutter coverage reports are required")
    flutter_coverage = {path.name: json.loads(path.read_text(encoding="utf-8"))
                        for path in coverage_files}
    if any(value.get("lineRatio", 0) < 0.90 for value in flutter_coverage.values()):
        fail("Flutter coverage below 90%")
    attacks = list((bundle / "tenant").rglob("tenant-attacks.json"))
    if len(attacks) != 1:
        fail("tenant/PII attack report missing")
    attack = json.loads(attacks[0].read_text(encoding="utf-8"))
    if len(attack.get("surfaces", [])) != 34 or any(x.get("result") != "PASS" for x in attack["surfaces"]):
        fail("tenant/PII attack matrix incomplete")
    matrices = list((bundle / "vectors").rglob("fixed-matrix.json"))
    if len(matrices) != 1:
        fail("fixed matrix missing")
    matrix = json.loads(matrices[0].read_text(encoding="utf-8"))
    if matrix.get("fixedScenarios") != 15 or matrix.get("providerNetworkCalls") != 0:
        fail("fixed matrix incomplete or network boundary changed")
    apk = list((bundle / "pos-linux").rglob("app-debug.apk"))
    if len(apk) != 1 or apk[0].stat().st_size < 1_000_000:
        fail("debug APK missing or unexpectedly small")
    for name in ("flutter-cyclonedx.json", "flutter-license-inventory.json"):
        if len(list((bundle / "pos-linux").rglob(name))) != 1:
            fail(f"Flutter supply-chain evidence missing {name}")
    for pattern in ("trivy-server-vuln.json", "trivy-flutter-vuln.json", "trivy-secret.json",
                    "trivy-config.json", "server-license-policy.txt"):
        if len(list((bundle / "security").rglob(pattern))) != 1:
            fail(f"security evidence missing {pattern}")
    files = [{"artifactGroup": path.relative_to(bundle).parts[0],
              "path": path.relative_to(bundle).as_posix(), "size": path.stat().st_size,
              "sha256": digest(path)} for path in sorted(bundle.rglob("*")) if path.is_file()]
    index = {
        "schemaVersion": "1.0", "phase": "T2-GATE5C", "stage": "closure",
        "evidenceLevel": "STATIC+UNIT+MYSQL_INTEGRATION+SQLITE_INTEGRATION+SYNTHETIC_VECTOR",
        "generatedAt": datetime.now(timezone.utc).isoformat().replace("+00:00", "Z"),
        "baselineTag": "t2-prep-baseline-2026-08-16",
        "branchStart": "2b55ad6154b75bce1ff19c68a50e025afe7f1e93",
        "commitSha": subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=ROOT, text=True).strip(),
        "requirements": requirement_state(), "tests": tests,
        "coverage": {"serverMember": member_coverage, "flutter": flutter_coverage},
        "tenantPiiAttackSurfaces": 34, "fixedScenarios": 15,
        "providerNetworkCalls": 0, "realPiiRecords": 0,
        "apk": {"size": apk[0].stat().st_size, "sha256": digest(apk[0])},
        "artifactPolicy": "Each producer artifact is retained once; final artifact uploads this index only.",
        "externalEvidence": {"sandbox": 0, "realDevice": 0, "pilot": 0}, "files": files,
        "limitations": [
            "All identities, tenants, stores, members, orders, returns and points are synthetic",
            "T2-PAY-002 and Provider network remain BLOCKED with zero network calls",
            "Reporting remains DRAFT with no runtime tables, jobs, controllers or exports",
            "REAL_DEVICE, real PII operational audit and PILOT evidence remain zero",
            "CONDITIONAL PASS does not establish Alpha, pilot readiness or commercial usability",
        ],
    }
    output = bundle / "t2-gate5c-evidence-index.json"
    output.write_text(json.dumps(index, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(f"T2-GATE5C EVIDENCE OK: files={len(files)} serverTests={tests['server']['tests']} network=0 realPii=0")


if __name__ == "__main__":
    main()
