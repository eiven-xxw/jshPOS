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
GATE0_IDS = {"T2-IAM-001", "T2-ORG-001", "T2-RBAC-001", "T2-CFG-001",
             "T2-AUD-001", "T2-SEC-001", "T2-OBS-001", "T2-MIG-001"}
GATE1_IDS = {"T2-PRD-001", "T2-PRD-002", "T2-PRD-003", "T2-PRD-004",
             "T2-PRC-001", "T2-PRC-002", "T2-DPK-001"}
GATE2_IDS = {"T2-POS-001", "T2-POS-002", "T2-POS-003", "T2-POS-004",
             "T2-POS-005", "T2-ORD-001", "T2-ORD-002", "T2-OFF-001"}


def fail(message: str) -> None:
    raise SystemExit(f"T2-GATE2 EVIDENCE ERROR: {message}")


def sha256(path: Path) -> str:
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
    tests = 0
    done = False
    for line in reports[0].read_text(encoding="utf-8-sig").splitlines():
        try:
            event = json.loads(line)
        except json.JSONDecodeError:
            continue
        # Flutter may interleave VM-service extension records as top-level
        # JSON arrays (for example test.startedProcess). They are valid
        # machine output but are not package:test protocol events.
        if not isinstance(event, dict):
            continue
        if event.get("type") == "testDone" and not event.get("hidden") and not event.get("skipped"):
            if event.get("result") != "success":
                fail(f"Flutter test failed under {root}")
            tests += 1
        if event.get("type") == "done":
            done = event.get("success") is True
    if tests < 20 or not done:
        fail(f"incomplete Flutter report under {root}: tests={tests} done={done}")
    return {"tests": tests, "success": done}


def coverage(root: Path) -> dict[str, float | int]:
    reports = [path for path in root.rglob("jacoco.xml") if "jshpos-order" in path.as_posix()]
    if len(reports) != 1:
        fail(f"expected one order JaCoCo report, got {len(reports)}")
    counters = {node.attrib["type"]: node.attrib for node in ET.parse(reports[0]).getroot().findall("counter")}
    result: dict[str, float | int] = {}
    for name, minimum in (("LINE", 0.90), ("BRANCH", 0.85)):
        counter = counters.get(name)
        if counter is None:
            fail(f"order JaCoCo missing {name}")
        missed, covered = int(counter["missed"]), int(counter["covered"])
        ratio = covered / (covered + missed)
        if ratio < minimum:
            fail(f"order JaCoCo {name} {ratio:.4f} below {minimum:.2f}")
        result[f"{name.lower()}Ratio"] = round(ratio, 6)
        result[f"{name.lower()}Covered"] = covered
        result[f"{name.lower()}Missed"] = missed
    return result


def requirements() -> dict[str, object]:
    with (ROOT / "docs/governance/rtm.csv").open(encoding="utf-8-sig", newline="") as handle:
        rows = {row["requirement_id"]: row for row in csv.DictReader(handle)}
    result = {"gate0": {}, "gate1": {}, "gate2": {}}
    for label, ids, expected in (("gate0", GATE0_IDS, "ACCEPTED"),
                                 ("gate1", GATE1_IDS, "ACCEPTED"),
                                 ("gate2", GATE2_IDS, "VERIFIED")):
        result[label] = {item: rows[item]["status"] for item in sorted(ids)}
        if set(result[label].values()) != {expected}:
            fail(f"{label} requirements must be {expected}: {result[label]}")
    if rows["T2-SYN-001"]["status"] != "DRAFT":
        fail("T2-SYN-001 must remain DRAFT")
    return result


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--bundle-dir", type=Path, required=True)
    args = parser.parse_args()
    bundle = args.bundle_dir if args.bundle_dir.is_absolute() else ROOT / args.bundle_dir
    directories = ("governance", "server", "mysql", "tenant", "web", "pos-linux", "pos-windows", "security")
    for directory in directories:
        if not (bundle / directory).is_dir():
            fail(f"missing evidence directory {directory}")

    server_tests = surefire(bundle / "server")
    mysql_tests = surefire(bundle / "mysql")
    tenant_tests = surefire(bundle / "tenant")
    linux_tests = flutter_tests(bundle / "pos-linux")
    windows_tests = flutter_tests(bundle / "pos-windows")
    jacoco = coverage(bundle / "server")
    flutter_coverage_files = list((bundle / "pos-linux").rglob("flutter-coverage.json"))
    if len(flutter_coverage_files) != 1:
        fail("Flutter coverage report missing")
    flutter_coverage = json.loads(flutter_coverage_files[0].read_text(encoding="utf-8"))
    if flutter_coverage.get("lineRatio", 0) < 0.90:
        fail("Flutter Gate 2 line coverage below 90%")

    attacks = list((bundle / "tenant").rglob("tenant-attacks.json"))
    if len(attacks) != 1:
        fail("tenant attack report missing")
    attack = json.loads(attacks[0].read_text(encoding="utf-8"))
    if len(attack.get("surfaces", [])) != 9 or any(item.get("result") != "PASS" for item in attack["surfaces"]):
        fail("tenant attack matrix is incomplete")
    for root in (bundle / "pos-linux", bundle / "pos-windows"):
        crash = "\n".join(path.read_text(encoding="utf-8", errors="replace")
                           for path in root.rglob("process-termination.txt"))
        for token in ('"killBeforeCommit":"ROLLED_BACK"', '"killAfterCommit":"DURABLE"', '"quickCheck":"ok"'):
            if token not in crash:
                fail(f"process termination evidence missing {token} under {root}")
    apk = list((bundle / "pos-linux").rglob("app-debug.apk"))
    if len(apk) != 1 or apk[0].stat().st_size < 1_000_000:
        fail("debug APK artifact missing or unexpectedly small")
    for name in ("flutter-cyclonedx.json", "flutter-license-inventory.json"):
        if len(list((bundle / "pos-linux").rglob(name))) != 1:
            fail(f"Flutter supply-chain artifact missing {name}")

    files = []
    for path in sorted(bundle.rglob("*")):
        if path.is_file() and path.name != "t2-gate2-evidence-index.json":
            files.append({"path": path.relative_to(bundle).as_posix(), "size": path.stat().st_size,
                          "sha256": sha256(path)})
    if not files:
        fail("evidence bundle is empty")
    index = {
        "schemaVersion": "1.0",
        "phase": "T2-GATE2-S2",
        "evidenceLevel": "STATIC+UNIT+MYSQL_INTEGRATION+SQLITE_FAULT+PROCESS_TERMINATION",
        "generatedAt": datetime.now(timezone.utc).isoformat().replace("+00:00", "Z"),
        "baselineTag": "t2-prep-baseline-2026-08-16",
        "branchStart": "6a94bc6af2938fba6b9a1af123eb94b6312af9b2",
        "commitSha": subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=ROOT, text=True).strip(),
        "requirements": requirements(),
        "tests": {"server": server_tests, "mysql": mysql_tests, "tenant": tenant_tests,
                  "flutterLinux": linux_tests, "flutterWindows": windows_tests},
        "coverage": {"server": jacoco, "flutter": flutter_coverage},
        "tenantAttackSurfaces": 9,
        "apk": {"size": apk[0].stat().st_size, "sha256": sha256(apk[0])},
        "externalEvidence": {"sandbox": 0, "realDevice": 0, "pilot": 0},
        "files": files,
        "limitations": [
            "Gate 2 local cash, shift, basket, immutable snapshot and Outbox fact creation only",
            "remote sync contract is design-only and has no transport runtime",
            "process termination is not physical power-loss or REAL_DEVICE evidence",
            "does not contain payment SANDBOX REAL_DEVICE PILOT Alpha or commercial acceptance",
        ],
    }
    (bundle / "t2-gate2-evidence-index.json").write_text(
        json.dumps(index, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(f"T2-GATE2 EVIDENCE OK: files={len(files)} serverTests={server_tests['tests']} "
          f"mysqlTests={mysql_tests['tests']} flutterLinux={linux_tests['tests']} "
          f"flutterWindows={windows_tests['tests']} serverBranch={jacoco['branchRatio']:.4f} "
          f"flutterLine={flutter_coverage['lineRatio']:.4f}")


if __name__ == "__main__":
    main()
