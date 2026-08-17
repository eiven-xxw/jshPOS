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


def fail(message: str) -> None:
    raise SystemExit(f"T2-GATE6A EVIDENCE ERROR: {message}")


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


def jacoco(root: Path, module: str, label: str) -> dict[str, float | int]:
    reports = [path for path in root.rglob("jacoco.xml") if module in path.as_posix()]
    if len(reports) != 1:
        fail(f"expected one {label} JaCoCo report, got {len(reports)}")
    counters = {node.attrib["type"]: node.attrib for node in ET.parse(reports[0]).getroot().findall("counter")}
    result: dict[str, float | int] = {}
    for name, minimum in (("LINE", 0.90), ("BRANCH", 0.85)):
        value = counters.get(name)
        if value is None:
            fail(f"{label} JaCoCo missing {name}")
        missed, covered = int(value["missed"]), int(value["covered"])
        ratio = covered / (covered + missed)
        if ratio < minimum:
            fail(f"{label} JaCoCo {name} {ratio:.4f} below {minimum:.2f}")
        result[f"{name.lower()}Ratio"] = round(ratio, 6)
        result[f"{name.lower()}Covered"] = covered
        result[f"{name.lower()}Missed"] = missed
    return result


def requirement_state() -> tuple[str, dict[str, str]]:
    with (ROOT / "docs/governance/rtm.csv").open(encoding="utf-8-sig", newline="") as handle:
        rows = {row["requirement_id"]: row for row in csv.DictReader(handle)}
    trm, bak = rows["T2-TRM-001"]["status"], rows["T2-BAK-001"]["status"]
    if trm not in {"IN_PROGRESS", "VERIFIED"} or bak not in {"DRAFT", "IN_PROGRESS", "VERIFIED"}:
        fail(f"invalid Gate 6A evidence state TRM={trm} BAK={bak}")
    if trm != "VERIFIED" and bak != "DRAFT":
        fail("BAK runtime/admission appeared before TRM verification")
    if rows["T2-PAY-002"]["status"] != "BLOCKED" or rows["T2-HWD-001"]["status"] != "BLOCKED":
        fail("payment or real-device blocker changed")
    if bak == "VERIFIED":
        stage = "closure"
    elif bak == "IN_PROGRESS":
        checksums = json.loads((ROOT / "contracts/t2/gate6a/migration-checksums.json").read_text(encoding="utf-8"))
        stage = "bak" if len(checksums.get("files", [])) == 4 else "bak-admission"
    elif trm == "VERIFIED":
        stage = "trm"
    else:
        stage = "trm-candidate"
    return stage, {"T2-TRM-001": trm, "T2-BAK-001": bak, "T2-PAY-002": "BLOCKED",
                   "T2-HWD-001": "BLOCKED", "T2-UPG-001": rows["T2-UPG-001"]["status"]}


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--bundle-dir", type=Path, required=True)
    args = parser.parse_args()
    bundle = args.bundle_dir if args.bundle_dir.is_absolute() else ROOT / args.bundle_dir
    directories = ("governance", "server", "mysql", "resilience", "vectors", "web",
                   "pos-linux", "pos-windows", "security")
    for name in directories:
        if not (bundle / name).is_dir():
            fail(f"missing evidence directory {name}")

    tests = {"server": surefire(bundle / "server"), "mysql": surefire(bundle / "mysql"),
             "flutterLinux": flutter(bundle / "pos-linux"),
             "flutterWindows": flutter(bundle / "pos-windows")}
    if not list((bundle / "mysql").rglob("TEST-*SyncMigrationMySqlIT.xml")):
        fail("Gate 6A MySQL migration and 100k terminal capacity evidence missing")
    if not list((bundle / "mysql").rglob("TEST-*ResilienceMigrationMySqlIT.xml")):
        fail("Gate 6A empty-database full migration and backup guard evidence missing")
    sync_coverage = jacoco(bundle / "server", "jshpos-sync", "Sync")
    resilience_coverage = jacoco(bundle / "server", "jshpos-resilience", "Resilience")
    coverage_files = list((bundle / "pos-linux").rglob("flutter-*-coverage.json"))
    if len(coverage_files) != 3:
        fail("Gate 5A, Gate 5B and Gate 5C Flutter coverage reports are required")
    flutter_coverage = {path.name: json.loads(path.read_text(encoding="utf-8")) for path in coverage_files}
    if any(value.get("lineRatio", 0) < 0.90 for value in flutter_coverage.values()):
        fail("Flutter coverage below 90%")

    attacks = list((bundle / "resilience").rglob("trm-attacks.json"))
    matrices = list((bundle / "vectors").rglob("fixed-matrix.json"))
    if len(attacks) != 1 or len(matrices) != 1:
        fail("terminal attack or fixed matrix evidence missing")
    attack = json.loads(attacks[0].read_text(encoding="utf-8"))
    matrix = json.loads(matrices[0].read_text(encoding="utf-8"))
    if len(attack.get("surfaces", [])) < 38 or any(item.get("result") != "PASS" for item in attack["surfaces"]):
        fail("terminal attack matrix incomplete")
    if matrix.get("fixedScenarios", 0) < 18 or matrix.get("providerNetworkCalls") != 0:
        fail("terminal fixed matrix incomplete or network boundary changed")

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

    stage, states = requirement_state()
    restore_reports = list((bundle / "resilience").rglob("restore-drill.json"))
    if stage in {"bak", "closure"} and len(restore_reports) != 1:
        fail("backup restore drill evidence missing")
    restore = json.loads(restore_reports[0].read_text(encoding="utf-8")) if restore_reports else None
    if restore:
        required_checks = {"MANIFEST", "SHA256", "ENCRYPTION", "FLYWAY_VALIDATE", "PROJECTION_REBUILD",
                           "TENANT_RECONCILIATION", "BUSINESS_DAY_RECONCILIATION", "CURSOR", "AUDIT"}
        if (restore.get("evidenceLevel") != "SYNTHETIC_RESTORE"
                or restore.get("rpoSeconds", 901) > 900 or not 1 <= restore.get("rtoSeconds", 0) <= 3600
                or restore.get("result") != "PASS" or set(restore.get("checks", [])) != required_checks
                or restore.get("syntheticFactRows") != 1_000_000
                or restore.get("cloudDrEvidence") != 0 or restore.get("commercialSla") is not False):
            fail("Alpha-candidate synthetic RPO/RTO and reconciliation target not proven or evidence was upgraded")

    files = [{"artifactGroup": path.relative_to(bundle).parts[0],
              "path": path.relative_to(bundle).as_posix(), "size": path.stat().st_size,
              "sha256": digest(path)} for path in sorted(bundle.rglob("*")) if path.is_file()]
    limitations = [
        "All tenants, stores, terminals, business facts, backups and restore targets are synthetic",
        "T2-PAY-002 remains BLOCKED and Provider network calls are zero",
        "T2-HWD-001 remains BLOCKED; software-generated credentials do not establish REAL_DEVICE evidence",
        "Cloud cross-region storage, production KMS and production disaster failover remain BLOCKED",
        "RPO/RTO values are Alpha-candidate internal exercise evidence and are not a commercial SLA",
        "CONDITIONAL PASS does not establish Alpha, pilot readiness or commercial usability",
    ]
    index = {
        "schemaVersion": "1.0", "phase": "T2-GATE6A", "stage": stage,
        "evidenceLevel": "STATIC+UNIT+MYSQL8.4_SYNTHETIC+SYNTHETIC_RESTORE",
        "generatedAt": datetime.now(timezone.utc).isoformat().replace("+00:00", "Z"),
        "baselineTag": "t2-prep-baseline-2026-08-16",
        "branchStart": "1bd27f70d39dd2056ffecf3b25f07aa9c7953606",
        "commitSha": subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=ROOT, text=True).strip(),
        "requirements": states, "tests": tests,
        "coverage": {"serverSync": sync_coverage, "serverResilience": resilience_coverage,
                     "flutter": flutter_coverage},
        "terminalAttackSurfaces": len(attack["surfaces"]), "fixedScenarios": matrix["fixedScenarios"],
        "syntheticTerminalCapacityRows": 100_000, "restoreDrill": restore,
        "providerNetworkCalls": 0, "realPiiRecords": 0, "realDeviceCommands": 0,
        "apk": {"size": apk[0].stat().st_size, "sha256": digest(apk[0])},
        "artifactPolicy": "Each producer artifact is retained once; final artifact uploads this index only.",
        "externalEvidence": {"sandbox": 0, "realDevice": 0, "pilot": 0, "cloudDr": 0},
        "files": files, "limitations": limitations,
    }
    output = bundle / "t2-gate6a-evidence-index.json"
    output.write_text(json.dumps(index, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(f"T2-GATE6A EVIDENCE OK: stage={stage} files={len(files)} network=0 realDevice=0")


if __name__ == "__main__":
    main()
