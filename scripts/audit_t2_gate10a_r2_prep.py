#!/usr/bin/env python3
"""生成 Gate 10A-R2 当前 Server、SQL 与资源红基线，不修改运行时。"""
from __future__ import annotations

import argparse
import csv
import hashlib
import json
import pathlib
import re
import subprocess
import xml.etree.ElementTree as ET


ROOT = pathlib.Path(__file__).resolve().parents[1]
NS = {"m": "http://maven.apache.org/POM/4.0.0"}


def git(*args: str) -> str:
    return subprocess.check_output(["git", *args], cwd=ROOT, text=True, encoding="utf-8").strip()


def tracked(prefix: str, suffix: str) -> list[pathlib.Path]:
    return [ROOT / line for line in git("ls-files", prefix).splitlines() if line.endswith(suffix)]


def owner_graph() -> dict[str, list[str]]:
    graph: dict[str, list[str]] = {}
    for pom in sorted((ROOT / "server/ruoyi-modules").glob("jshpos-*/pom.xml")):
        root = ET.parse(pom)
        owner = root.findtext("m:artifactId", namespaces=NS) or pom.parent.name
        graph[owner] = sorted({
            node.findtext("m:artifactId", default="", namespaces=NS)
            for node in root.findall("m:dependencies/m:dependency", NS)
            if node.findtext("m:groupId", default="", namespaces=NS) == "com.jingshanghui.pos"
            and node.findtext("m:artifactId", default="", namespaces=NS) != owner
        })
    return graph


def dependency_cycles(graph: dict[str, list[str]]) -> list[list[str]]:
    cycles: set[tuple[str, ...]] = set()
    path: list[str] = []
    active: set[str] = set()
    complete: set[str] = set()

    def visit(node: str) -> None:
        if node in active:
            cycle = path[path.index(node):] + [node]
            cycles.add(tuple(cycle))
            return
        if node in complete:
            return
        active.add(node)
        path.append(node)
        for child in graph.get(node, []):
            visit(child)
        path.pop()
        active.remove(node)
        complete.add(node)

    for item in graph:
        visit(item)
    return [list(item) for item in sorted(cycles)]


def duplicate_windows(files: list[pathlib.Path]) -> int:
    fingerprints: dict[str, set[str]] = {}
    for path in files:
        significant: list[str] = []
        for line in path.read_text(encoding="utf-8", errors="replace").splitlines():
            value = line.strip()
            if not value or value.startswith(("package ", "import ", "//", "*", "/*", "@")) or value in {"{", "}", "};"}:
                continue
            value = re.sub(r'"(?:\\.|[^"\\])*"', '"S"', value)
            value = re.sub(r"\b\d+(?:\.\d+)?\b", "N", value)
            value = re.sub(r"\s+", " ", value)
            significant.append(value)
        for index in range(max(0, len(significant) - 7)):
            digest = hashlib.sha256("\n".join(significant[index:index + 8]).encode()).hexdigest()
            fingerprints.setdefault(digest, set()).add(path.as_posix())
    return sum(1 for paths in fingerprints.values() if len(paths) >= 2)


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--output-dir", required=True, type=pathlib.Path)
    args = parser.parse_args()
    output = args.output_dir if args.output_dir.is_absolute() else ROOT / args.output_dir
    output.mkdir(parents=True, exist_ok=True)

    java_files = [path for path in tracked("server/ruoyi-modules", ".java") if "/jshpos-" in path.as_posix() and "/src/main/" in path.as_posix()]
    large: list[dict[str, object]] = []
    for path in java_files:
        text = path.read_text(encoding="utf-8", errors="replace")
        lines = len(text.splitlines())
        if lines >= 400:
            module = next(part for part in path.parts if part.startswith("jshpos-"))
            large.append({
                "module": module, "path": path.relative_to(ROOT).as_posix(), "lines": lines,
                "transactionAnnotations": len(re.findall(r"@Transactional\b", text)),
                "broadCatchBlocks": len(re.findall(r"catch\s*\(\s*(?:Exception|RuntimeException|Throwable)\b", text)),
            })
    large.sort(key=lambda item: (-int(item["lines"]), str(item["path"])))
    graph = owner_graph()
    cycles = dependency_cycles(graph)

    mapper_files = [path for path in tracked("server/ruoyi-modules", ".xml") if "/jshpos-" in path.as_posix() and "/src/main/resources/mapper/" in path.as_posix()]
    selects = 0
    complex_rows: list[dict[str, object]] = []
    select_star = 0
    for path in mapper_files:
        text = re.sub(r"<!--.*?-->", "", path.read_text(encoding="utf-8", errors="replace"), flags=re.S)
        file_selects = len(re.findall(r"<select\b", text, re.I))
        joins = len(re.findall(r"\bjoin\b", text, re.I))
        groups = len(re.findall(r"\bgroup\s+by\b", text, re.I))
        subqueries = len(re.findall(r"\(\s*select\b", text, re.I))
        selects += file_selects
        select_star += len(re.findall(r"select\s+\*", text, re.I))
        if joins + groups + subqueries:
            complex_rows.append({"path": path.relative_to(ROOT).as_posix(), "selects": file_selects, "joins": joins, "groups": groups, "subqueries": subqueries})
    query_plan_tests = [path for path in tracked("server/ruoyi-modules", ".java") if "/src/test/" in path.as_posix() and re.search(r"\bEXPLAIN(?:\s+ANALYZE)?\b", path.read_text(encoding="utf-8", errors="replace"), re.I)]

    production_text = "\n".join(path.read_text(encoding="utf-8", errors="replace") for path in java_files)
    temp_create_files = [path for path in java_files if "Files.createTempFile" in path.read_text(encoding="utf-8", errors="replace")]
    temp_cleanup_files = [path for path in java_files if "deleteIfExists" in path.read_text(encoding="utf-8", errors="replace")]
    workload = json.loads((ROOT / "contracts/t2/gate8c-perf002/workload-model-v1.json").read_text(encoding="utf-8"))
    prod_config = (ROOT / "server/ruoyi-admin/src/main/resources/application-prod.yml").read_text(encoding="utf-8")

    facts = {
        "ownerModules": len(graph), "productionJavaFiles": len(java_files), "filesAtLeast400Lines": len(large),
        "affectedLargeClassOwnerModules": len({str(item["module"]) for item in large}),
        "largestClassLines": int(large[0]["lines"]), "ownerDependencyCycles": len(cycles),
        "crossFileDuplicateEightLineWindowsForTriage": duplicate_windows([path for path in java_files if len(path.read_text(encoding="utf-8", errors="replace").splitlines()) >= 400]),
        "mapperXmlFiles": len(mapper_files), "selectStatements": selects, "complexMapperFiles": len(complex_rows),
        "explainRegressionFiles": len(query_plan_tests), "selectStarViolations": select_star,
        "existingSustainedSeconds": workload["formalRuntime"]["sustainedSeconds"],
        "ownerScheduledAnnotations": len(re.findall(r"(?m)^\s*@Scheduled(?:\s|\()", production_text)),
        "temporaryFileCreatorFiles": len(temp_create_files), "temporaryFileCleanupFiles": len(temp_cleanup_files),
        "prodHikariMaxPoolSizeConfigured": "JSH_DB_MAX_POOL_SIZE:20" in prod_config,
        "prodHikariMinIdleConfigured": "JSH_DB_MIN_IDLE:10" in prod_config,
        "prodRedissonThreads16": bool(re.search(r"(?m)^\s*threads:\s*16\s*$", prod_config)),
        "prodRedissonPool64": "connectionPoolSize: 64" in prod_config,
    }
    expected = {
        "ownerModules": 22, "productionJavaFiles": 656, "filesAtLeast400Lines": 19,
        "affectedLargeClassOwnerModules": 13, "largestClassLines": 760, "ownerDependencyCycles": 0,
        "mapperXmlFiles": 49, "selectStatements": 365, "complexMapperFiles": 27,
        "explainRegressionFiles": 0, "selectStarViolations": 0, "existingSustainedSeconds": 120,
        "ownerScheduledAnnotations": 0, "temporaryFileCreatorFiles": 3, "temporaryFileCleanupFiles": 3,
        "prodHikariMaxPoolSizeConfigured": True, "prodHikariMinIdleConfigured": True,
        "prodRedissonThreads16": True, "prodRedissonPool64": True,
    }
    drift = [f"{key}: {facts.get(key)} != {value}" for key, value in expected.items() if facts.get(key) != value]
    summary = {
        "schemaVersion": "1.0", "gate": "T2_GATE_10A_R2_PREP", "commitSha": git("rev-parse", "HEAD"),
        "facts": facts, "observedRedSeeds": ["G10A-R2-MTN-RED-001", "G10A-R2-SQL-RED-001", "G10A-R2-RES-RED-001"],
        "baselineDrift": drift, "runtimeChangesApplied": 0, "databaseChangesApplied": 0,
        "result": "PASS" if not drift else "FAIL", "recommendation": "CONDITIONAL_PASS_AWAITING_SPONSOR_RUNTIME_ADMISSION" if not drift else "NO_GO_BASELINE_DRIFT",
    }
    (output / "summary.json").write_text(json.dumps(summary, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    (output / "owner-dependency-graph.json").write_text(json.dumps({"owners": graph, "cycles": cycles}, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    with (output / "large-java-classes.csv").open("w", encoding="utf-8-sig", newline="") as stream:
        writer = csv.DictWriter(stream, fieldnames=["module", "path", "lines", "transactionAnnotations", "broadCatchBlocks"])
        writer.writeheader(); writer.writerows(large)
    with (output / "complex-mapper-files.csv").open("w", encoding="utf-8-sig", newline="") as stream:
        writer = csv.DictWriter(stream, fieldnames=["path", "selects", "joins", "groups", "subqueries"])
        writer.writeheader(); writer.writerows(sorted(complex_rows, key=lambda item: str(item["path"])))
    print(f"T2 Gate10A R2 PREP AUDIT {summary['result']}: owners={len(graph)} java={len(java_files)} large={len(large)} mapper={len(mapper_files)} selects={selects} explain={len(query_plan_tests)} soak={facts['existingSustainedSeconds']}s")
    if drift:
        raise SystemExit("; ".join(drift))


if __name__ == "__main__":
    main()
