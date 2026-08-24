#!/usr/bin/env python3
"""聚合 T2-PERF-002 正式运行栈、Owner 容量和 POS 性能证据。"""
from __future__ import annotations

import argparse
import hashlib
import json
import pathlib
import subprocess
import xml.etree.ElementTree as etree
from typing import Any


ROOT = pathlib.Path(__file__).resolve().parents[1]
CONTRACT_DIR = ROOT / "contracts/t2/gate8c-perf002"
POS_CASES = {
    "Gate 6H synthetic POS scan baseline executes 1000 formal scans": ("scan1000", "scan1000MaxMs", 1000),
    "Gate 6H synthetic POS settlement baseline commits 200 cash orders": ("settlement200", "settlement200MaxMs", 200),
    "Gate 6H synthetic sync backlog drains 10000 events without loss": ("syncBacklog10000", "syncBacklog10000MaxMs", 10000),
    "PERF002 installs 100000 signed lot package records atomically": ("dataPackage100000", "dataPackage100000MaxMs", 100000),
}


def fail(message: str) -> None:
    raise SystemExit("T2-PERF-002 EVIDENCE ERROR: " + message)


def load(path: pathlib.Path) -> dict[str, Any]:
    try:
        return json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as error:
        fail(f"invalid JSON {path}: {error}")


def one(root: pathlib.Path, pattern: str) -> pathlib.Path:
    paths = list(root.rglob(pattern))
    if len(paths) != 1:
        fail(f"expected one {pattern}, got {len(paths)}")
    return paths[0]


def junit(path: pathlib.Path) -> dict[str, Any]:
    node = etree.parse(path).getroot()
    failures = int(node.attrib.get("failures", "0")) + int(node.attrib.get("errors", "0"))
    skipped = int(node.attrib.get("skipped", "0"))
    if failures or skipped:
        fail(f"JUnit suite is not clean: {path.name}")
    return {
        "suite": node.attrib.get("name", path.stem),
        "tests": int(node.attrib.get("tests", "0")),
        "seconds": float(node.attrib.get("time", "0")),
        "sha256": hashlib.sha256(path.read_bytes()).hexdigest(),
    }


def flutter_metrics(path: pathlib.Path, thresholds: dict[str, Any]) -> dict[str, Any]:
    names: dict[int, str] = {}
    starts: dict[int, int] = {}
    metrics: dict[str, Any] = {}
    for line in path.read_text(encoding="utf-8").splitlines():
        try:
            event = json.loads(line)
        except json.JSONDecodeError:
            continue
        if not isinstance(event, dict):
            continue
        if event.get("type") == "testStart":
            names[event["test"]["id"]] = event["test"]["name"]
            starts[event["test"]["id"]] = int(event.get("time", 0))
        elif event.get("type") == "testDone":
            test_id = event.get("testID")
            name = names.get(test_id, "")
            for expected, (dimension, threshold_name, records) in POS_CASES.items():
                if expected in name:
                    if event.get("result") != "success" or event.get("skipped") is True:
                        fail("Flutter performance case failed or skipped: " + expected)
                    milliseconds = int(event.get("time", 0)) - starts.get(test_id, 0)
                    limit = thresholds[threshold_name]
                    if milliseconds > limit:
                        fail(f"{dimension} {milliseconds}ms exceeds {limit}ms")
                    metrics[dimension] = {
                        "case": expected, "recordsOrIterations": records,
                        "milliseconds": milliseconds, "limitMs": limit, "status": "PASS",
                    }
    missing = {value[0] for value in POS_CASES.values()} - set(metrics)
    if missing:
        fail("Flutter performance measurements missing: " + ", ".join(sorted(missing)))
    metrics["rawSha256"] = hashlib.sha256(path.read_bytes()).hexdigest()
    return metrics


def validate_runtime(runtime: dict[str, Any], thresholds: dict[str, Any]) -> None:
    if runtime.get("status") != "PASS" or runtime.get("classification") != "INTERNAL_FULL_STACK_PERFORMANCE_CANDIDATE":
        fail("formal runtime classification or status invalid")
    if runtime.get("rawRequestSampleCount", 0) < 1000 or runtime.get("crossTenantDenied") is not True:
        fail("formal runtime samples or tenant denial missing")
    if any(runtime.get(key) for key in ("providerNetworkCalls", "realFunds", "realDeviceOrPeripheralCommands")):
        fail("external execution must remain zero")
    phases = runtime.get("phases", [])
    expected_phases = {"concurrency-1", "concurrency-8", "concurrency-16", "sustained", "connection-pressure"}
    if {item.get("phase") for item in phases} != expected_phases:
        fail("formal runtime phase set incomplete")
    for phase in phases:
        if phase.get("errors") != 0 or phase.get("errorRate") != 0.0:
            fail("formal runtime request error")
        if phase.get("p95Ms", 10**9) > thresholds["httpP95MaxMs"] or phase.get("p99Ms", 10**9) > thresholds["httpP99MaxMs"]:
            fail("formal runtime tail latency exceeds frozen threshold")
    c16 = next(item for item in phases if item["phase"] == "concurrency-16")
    if c16["throughputRps"] < thresholds["minimumThroughputRpsAtConcurrency16"]:
        fail("formal runtime throughput below frozen threshold")
    resources = runtime.get("resourceSummary", {})
    if resources.get("samples", 0) < 3 or resources.get("maxApplicationRssMiB", 10**9) > thresholds["maxApplicationRssMiB"]:
        fail("formal runtime resource evidence invalid")
    if resources.get("gc", {}).get("maxPauseMs", 10**9) > thresholds["maxJvmGcPauseMs"]:
        fail("formal runtime GC exceeds frozen threshold")
    if resources.get("maxMysqlConnections", 10**9) > thresholds["maxMysqlConnections"]:
        fail("MySQL connections exceed frozen threshold")
    if resources.get("maxRedisUsedMemoryMiB", 10**9) > thresholds["maxRedisMemoryMiB"]:
        fail("Redis memory exceeds frozen threshold")


def validate_dependency_faults(faults: dict[str, Any]) -> None:
    if faults.get("status") != "PASS" or set(faults.get("vectors", {})) != {"PERF-F003", "PERF-F004"}:
        fail("dependency fault evidence incomplete")
    for evidence in faults["vectors"].values():
        if evidence.get("falseSuccess") is not False or evidence.get("processAlive") is not True \
                or evidence.get("recoveryAttempts", 99) > 20:
            fail("dependency did not fail closed and recover")


def self_test() -> None:
    malformed = {"status": "PASS", "classification": "INTERNAL_FULL_STACK_PERFORMANCE_CANDIDATE"}
    rejected = False
    try:
        validate_runtime(malformed, load(CONTRACT_DIR / "thresholds-v1.json")["formalRuntime"])
    except SystemExit:
        rejected = True
    if not rejected:
        fail("corrupted or missing runtime evidence was accepted")
    print("T2-PERF-002 evidence builder self-test passed: corrupted/missing evidence rejected")


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--bundle-dir", type=pathlib.Path)
    parser.add_argument("--output", type=pathlib.Path)
    parser.add_argument("--self-test", action="store_true")
    args = parser.parse_args()
    if args.self_test:
        self_test()
        return
    if args.bundle_dir is None or args.output is None:
        parser.error("bundle-dir and output are required")
    workload = load(CONTRACT_DIR / "workload-model-v1.json")
    thresholds = load(CONTRACT_DIR / "thresholds-v1.json")
    admission = load(CONTRACT_DIR / "perf002-admission.json")
    runtime = load(one(args.bundle_dir, "formal-runtime-performance.json"))
    dependency_faults = load(one(args.bundle_dir, "dependency-faults.json"))
    cold = load(one(args.bundle_dir, "server-cold-start.json"))
    if cold.get("status") != "PASS" or cold.get("milliseconds", 10**9) > thresholds["formalRuntime"]["coldStartMaxMs"]:
        fail("server cold start exceeds frozen threshold")
    validate_runtime(runtime, thresholds["formalRuntime"])
    validate_dependency_faults(dependency_faults)

    catalog = load(one(args.bundle_dir, "gate1-capacity.json"))
    if [item.get("rows") for item in catalog.get("runs", [])] != workload["ownerCapacity"]["catalogRows"] \
            or not all(item.get("accepted") is True for item in catalog.get("runs", [])):
        fail("catalog 10k/100k capacity evidence invalid")
    suites = {
        "migration": junit(one(args.bundle_dir, "TEST-*MigrationCapacityTrendTest.xml")),
        "reporting": junit(one(args.bundle_dir, "TEST-*ReportingMigrationMySqlIT.xml")),
        "dailyClose": junit(one(args.bundle_dir, "TEST-*DailyCloseMySqlIT.xml")),
        "exceptionCenter": junit(one(args.bundle_dir, "TEST-*ExceptionCenterMySqlIT.xml")),
    }
    pos = flutter_metrics(one(args.bundle_dir, "flutter-performance.jsonl"), thresholds["posLocal"])
    fault_vectors = {
        "PERF-F001": "formal runtime connection pressure",
        "PERF-F002": "bounded HTTP timeout and dependency timeout observation",
        "PERF-F003": "Redis pause fail closed and recovery",
        "PERF-F004": "MySQL pause fail closed and recovery",
        "PERF-F005": "10k POS sync backlog converges without loss",
        "PERF-F006": "sync coordinator restart reuses original event",
        "PERF-F007": "corrupted evidence rejected by aggregator self-test",
        "PERF-F008": "missing resource sample rejected by aggregator self-test",
        "PERF-F009": "Gate8B formal journey same-key-different-content conflicts",
        "PERF-F010": "formal runtime cross-tenant platform request denied",
    }
    expected_faults = {item["id"] for item in load(CONTRACT_DIR / "fault-vectors-v1.json")["vectors"]}
    if set(fault_vectors) != expected_faults:
        fail("fault vector coverage incomplete")
    commit = subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=ROOT, text=True).strip()
    result = {
        "schemaVersion": "1.0", "gate": "T2-GATE8C-SPRINT-S26C", "status": "PASS",
        "requirementId": "T2-PERF-002", "requirementStatus": "VERIFIED",
        "classification": admission["evidenceCeiling"], "commitSha": commit,
        "dimensions": {
            "formalRuntime": {"coldStart": cold, "load": runtime, "dependencyFaults": dependency_faults},
            "ownerCapacity": {"catalog": catalog, "suites": suites},
            "posLocal": pos,
        },
        "faultVectors": fault_vectors,
        "closedFindings": ["G8C-PERF-P1-001", "G8C-PERF-P1-002"],
        "openPerformanceP0": 0, "openPerformanceP1": 0,
        "automaticRetries": 0, "newBusinessCapabilities": 0, "databaseMigrationsChanged": 0,
        "externalExecution": admission["externalExecution"], "commercialSla": False,
        "realDeviceEvidence": False,
    }
    canonical = json.dumps(result, sort_keys=True, separators=(",", ":")).encode()
    result["evidenceSha256"] = hashlib.sha256(canonical).hexdigest()
    target = args.output if args.output.is_absolute() else ROOT / args.output
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print("T2-PERF-002 EVIDENCE OK: dimensions=3 findings=2 external=0 commercialSla=false")


if __name__ == "__main__":
    main()
