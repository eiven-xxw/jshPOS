#!/usr/bin/env python3
"""生成 Gate 10A-Prep 可重复的仓库质量事实，不进行网络或运行时整改。"""
from __future__ import annotations

import argparse
import csv
import json
import pathlib
import re
import subprocess
import xml.etree.ElementTree as ET


ROOT = pathlib.Path(__file__).resolve().parents[1]
CONTRACT = ROOT / "contracts/t2/gate10a-prep"
NS = {"m": "http://maven.apache.org/POM/4.0.0"}


def git(*args: str) -> str:
    return subprocess.check_output(["git", *args], cwd=ROOT, text=True, encoding="utf-8").strip()


def tracked_files(prefix: str, suffix: str) -> list[pathlib.Path]:
    return [ROOT / line for line in git("ls-files", prefix).splitlines() if line.endswith(suffix)]


def line_count(path: pathlib.Path) -> int:
    return len(path.read_text(encoding="utf-8", errors="replace").splitlines())


def dependency_graph() -> dict[str, list[str]]:
    graph: dict[str, list[str]] = {}
    for pom in sorted((ROOT / "server/ruoyi-modules").glob("jshpos-*/pom.xml")):
        tree = ET.parse(pom)
        artifact = tree.findtext("m:artifactId", namespaces=NS) or pom.parent.name
        graph[artifact] = [
            node.findtext("m:artifactId", default="", namespaces=NS)
            for node in tree.findall("m:dependencies/m:dependency", NS)
            if node.findtext("m:groupId", default="", namespaces=NS) == "com.jingshanghui.pos"
        ]
    return graph


def cycles(graph: dict[str, list[str]]) -> list[list[str]]:
    found: set[tuple[str, ...]] = set()
    visiting: list[str] = []

    def visit(node: str) -> None:
        if node in visiting:
            cycle = visiting[visiting.index(node):] + [node]
            found.add(tuple(cycle))
            return
        visiting.append(node)
        for child in graph.get(node, []):
            visit(child)
        visiting.pop()

    for node in graph:
        visit(node)
    return [list(item) for item in sorted(found)]


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--output-dir", required=True, type=pathlib.Path)
    args = parser.parse_args()
    output = args.output_dir if args.output_dir.is_absolute() else ROOT / args.output_dir
    output.mkdir(parents=True, exist_ok=True)

    admission = json.loads((CONTRACT / "gate-admission-v1.json").read_text(encoding="utf-8"))
    findings = json.loads((CONTRACT / "findings-register-v1.json").read_text(encoding="utf-8"))
    workflows = tracked_files(".github/workflows", ".yml")
    workflow_text = "\n".join(path.read_text(encoding="utf-8") for path in workflows)
    action_patterns = {
        "checkout": "actions/checkout@11d596",
        "setupPython": "actions/setup-python@a26af",
        "setupJavaV4": "actions/setup-java@cf277",
        "setupNodeV4": "actions/setup-node@49933",
        "uploadArtifact": "actions/upload-artifact@ea165",
        "downloadArtifact": "actions/download-artifact@3e5f",
        "pnpmSetup": "pnpm/action-setup@f40f",
        "flutterAction": "subosito/flutter-action@1a449",
    }
    action_usage = {key: workflow_text.count(value) for key, value in action_patterns.items()}
    node20_era = sum(action_usage.values())

    java_files = [path for path in tracked_files("server/ruoyi-modules", ".java") if "/src/main/" in path.as_posix() and "/jshpos-" in path.as_posix()]
    dart_files = tracked_files("pos-flutter/lib", ".dart")
    large_java = sorted([{"path": path.relative_to(ROOT).as_posix(), "lines": line_count(path)} for path in java_files if line_count(path) >= 400], key=lambda item: (-item["lines"], item["path"]))
    large_dart = sorted([{"path": path.relative_to(ROOT).as_posix(), "lines": line_count(path)} for path in dart_files if line_count(path) >= 500], key=lambda item: (-item["lines"], item["path"]))

    mapper_xml = [path for path in tracked_files("server/ruoyi-modules", ".xml") if "/src/main/resources/mapper/" in path.as_posix() and "/jshpos-" in path.as_posix()]
    select_count = sum(len(re.findall(r"<select\b", path.read_text(encoding="utf-8"), re.I)) for path in mapper_xml)
    mapper_sql = [re.sub(r"<!--.*?-->", "", path.read_text(encoding="utf-8"), flags=re.S) for path in mapper_xml]
    production_select_star = sum(len(re.findall(r"select\s+\*", text, re.I)) for text in mapper_sql)
    query_plan_tests = [path for path in tracked_files("server/ruoyi-modules", ".java") if "/src/test/" in path.as_posix() and re.search(r"\bEXPLAIN\b", path.read_text(encoding="utf-8", errors="replace"), re.I)]

    metric_files = [path for path in java_files if "MeterRegistry" in path.read_text(encoding="utf-8", errors="replace")]
    sqlite_text = (ROOT / "pos-flutter/lib/infrastructure/local_database/pos_local_database.dart").read_text(encoding="utf-8")
    workload = json.loads((ROOT / "contracts/t2/gate8c-perf002/workload-model-v1.json").read_text(encoding="utf-8"))
    graph = dependency_graph()
    graph_cycles = cycles(graph)

    hard_failures: list[str] = []
    expected = {
        "workflows": 77, "java": 656, "largeJava": 19, "dart": 89, "largeDart": 18,
        "mapperXml": 49, "selects": 365, "metricFiles": 1, "soakSeconds": 120,
    }
    actual = {
        "workflows": len(workflows), "java": len(java_files), "largeJava": len(large_java),
        "dart": len(dart_files), "largeDart": len(large_dart), "mapperXml": len(mapper_xml),
        "selects": select_count, "metricFiles": len(metric_files),
        "soakSeconds": workload["formalRuntime"]["sustainedSeconds"],
    }
    for key, value in expected.items():
        if actual[key] != value:
            hard_failures.append(f"audit fact drift {key}: {actual[key]} != {value}")
    if graph_cycles:
        hard_failures.append(f"owner dependency cycles: {graph_cycles}")
    if production_select_star:
        hard_failures.append(f"production SELECT star: {production_select_star}")
    if len(findings["findings"]) != admission["openP2"]:
        hard_failures.append("P2 finding count drift")
    if "PRAGMA wal_checkpoint(TRUNCATE)" not in sqlite_text:
        hard_failures.append("SQLite WAL close checkpoint missing")

    summary = {
        "schemaVersion": "1.0", "gate": admission["gate"], "commitSha": git("rev-parse", "HEAD"),
        "baselineCommit": admission["baselineCommit"], "facts": actual,
        "actions": {**action_usage, "node20EraPinnedUses": node20_era, "setupJavaV4Files": sum(1 for path in workflows if action_patterns["setupJavaV4"] in path.read_text(encoding="utf-8"))},
        "ownerDependencyCycles": graph_cycles, "productionSelectStar": production_select_star,
        "queryPlanTestFiles": len(query_plan_tests), "sqlite": {"wal": "PRAGMA journal_mode=WAL" in sqlite_text, "fullSync": "PRAGMA synchronous=FULL" in sqlite_text, "closeCheckpoint": "PRAGMA wal_checkpoint(TRUNCATE)" in sqlite_text, "autoVacuum": "auto_vacuum" in sqlite_text.lower()},
        "findings": {"openP0": 0, "openP1": 0, "openP2": len(findings["findings"])},
        "runtimeChangesApplied": 0, "externalExecution": 0, "hardFailures": hard_failures,
        "result": "PASS" if not hard_failures else "FAIL",
        "recommendation": "CONDITIONAL_PASS_R1_AWAITING_SPONSOR_CONFIRMATION" if not hard_failures else "NO_GO_AUDIT_DRIFT"
    }
    (output / "summary.json").write_text(json.dumps(summary, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    (output / "action-usage.json").write_text(json.dumps(action_usage, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    with (output / "large-source-files.csv").open("w", encoding="utf-8-sig", newline="") as handle:
        writer = csv.DictWriter(handle, fieldnames=["runtime", "path", "lines"])
        writer.writeheader()
        for item in large_java:
            writer.writerow({"runtime": "JAVA", **item})
        for item in large_dart:
            writer.writerow({"runtime": "DART", **item})
    print(f"T2 Gate10A PREP AUDIT {summary['result']}: P2={len(findings['findings'])} javaLarge={len(large_java)} dartLarge={len(large_dart)} sql={select_count} soak={actual['soakSeconds']}s")
    if hard_failures:
        raise SystemExit("\n".join(hard_failures))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
