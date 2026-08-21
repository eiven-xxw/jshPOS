#!/usr/bin/env python3
"""汇总 Gate 6H 内部合成性能证据；时间仅作同执行器趋势。"""
from __future__ import annotations

import argparse
import hashlib
import json
import pathlib
import subprocess
import xml.etree.ElementTree as etree


ROOT = pathlib.Path(__file__).resolve().parents[1]
CONTRACT = ROOT / "contracts/t2/gate6h/performance-baseline-v1.json"
REQUIRED_FLUTTER = {
    "Gate 6H synthetic POS scan baseline executes 1000 formal scans": "posScan",
    "Gate 6H synthetic POS settlement baseline commits 200 cash orders": "posSettlement",
    "Gate 6H synthetic sync backlog drains 10000 events without loss": "syncBacklog",
}


def fail(message: str) -> None:
    raise SystemExit(f"T2-GATE6H PERF ERROR: {message}")


def load(path: pathlib.Path) -> dict:
    try:
        return json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as exception:
        fail(f"invalid JSON {path}: {exception}")


def one(root: pathlib.Path, pattern: str) -> pathlib.Path:
    paths = list(root.rglob(pattern))
    if len(paths) != 1:
        fail(f"expected one {pattern}, got {len(paths)}")
    return paths[0]


def junit(path: pathlib.Path) -> dict:
    node = etree.parse(path).getroot()
    if int(node.attrib.get("failures", "0")) or int(node.attrib.get("errors", "0")):
        fail(f"failed JUnit suite: {path.name}")
    return {"tests": int(node.attrib.get("tests", "0")), "seconds": float(node.attrib.get("time", "0"))}


def flutter_metrics(path: pathlib.Path) -> dict:
    names: dict[int, str] = {}
    starts: dict[int, int] = {}
    metrics: dict[str, dict] = {}
    for line in path.read_text(encoding="utf-8").splitlines():
        try:
            event = json.loads(line)
        except json.JSONDecodeError:
            continue
        if not isinstance(event, dict):
            continue
        if event.get("type") == "testStart":
            test = event["test"]
            names[test["id"]] = test["name"]
            starts[test["id"]] = int(event.get("time", 0))
        elif event.get("type") == "testDone":
            test_id = event.get("testID")
            name = names.get(test_id, "")
            for expected, dimension in REQUIRED_FLUTTER.items():
                if expected in name:
                    if event.get("result") != "success":
                        fail(f"Flutter performance case failed: {expected}")
                    metrics[dimension] = {
                        "case": expected,
                        "milliseconds": int(event.get("time", 0)) - starts.get(test_id, 0),
                        "status": "PASS",
                    }
    missing = set(REQUIRED_FLUTTER.values()) - set(metrics)
    if missing:
        fail(f"Flutter performance measurements missing: {sorted(missing)}")
    return metrics


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--bundle-dir", required=True, type=pathlib.Path)
    parser.add_argument("--output", required=True, type=pathlib.Path)
    args = parser.parse_args()
    contract = load(CONTRACT)
    if contract.get("classification") != "INTERNAL_SYNTHETIC_TREND":
        fail("classification drift")
    if any(contract.get("externalExecution", {}).values()):
        fail("external execution must remain zero")
    bundle = args.bundle_dir
    catalog = load(one(bundle, "gate1-capacity.json"))
    if [item.get("rows") for item in catalog.get("runs", [])] != [10000, 100000] or not all(
            item.get("accepted") is True for item in catalog.get("runs", [])):
        fail("catalog 10k/100k capacity evidence invalid")
    reporting = junit(one(bundle, "TEST-*ReportingMigrationMySqlIT.xml"))
    concurrency = junit(one(bundle, "TEST-*PaymentReconciliationServiceTest.xml"))
    flutter = flutter_metrics(one(bundle, "flutter-performance.jsonl"))
    cold = load(one(bundle, "server-cold-start.json"))
    if cold.get("status") != "PASS" or not isinstance(cold.get("milliseconds"), int):
        fail("server cold-start evidence invalid")
    dimensions = {
        "capacity": {"catalog": catalog["runs"], "status": "PASS"},
        "concurrency": {**concurrency, "status": "PASS"},
        "serverColdStart": cold,
        **flutter,
        "reporting": {**reporting, "projectionRows": 1000000, "reconciliationRows": 100000, "status": "PASS"},
    }
    if set(dimensions) != set(contract["requiredDimensions"]):
        fail("required dimension set is incomplete")
    commit = subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=ROOT, text=True).strip()
    result = {
        "schemaVersion": "1.0", "requirementId": "T2-PERF-001", "status": "PASS",
        "classification": contract["classification"], "commitSha": commit,
        "dimensions": dimensions, "externalExecution": contract["externalExecution"],
        "commercialSlaAllowed": False,
        "note": "Wall-clock measurements are GitHub executor-specific internal synthetic trends only.",
    }
    canonical = json.dumps(result, sort_keys=True, separators=(",", ":")).encode()
    result["evidenceSha256"] = hashlib.sha256(canonical).hexdigest()
    output = args.output if args.output.is_absolute() else ROOT / args.output
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print("T2-GATE6H PERF OK: dimensions=7 external=0 commercialSla=false")


if __name__ == "__main__":
    main()
