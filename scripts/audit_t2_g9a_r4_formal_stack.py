#!/usr/bin/env python3
"""审计 G9A-R4 准备阶段的 22 Owner 正式运行栈与现有 E2E 证据缺口。"""
from __future__ import annotations

import argparse
import csv
import json
import pathlib
import re
import subprocess


ROOT = pathlib.Path(__file__).resolve().parents[1]
CONTRACT = ROOT / "contracts/t2/gate9b-r4-prep"
OWNER_SOURCE = ROOT / "contracts/t2/gate9a-prep/owner-catalog-v1.json"
RTM = ROOT / "docs/governance/rtm.csv"


def load(path: pathlib.Path) -> dict:
    return json.loads(path.read_text(encoding="utf-8"))


def text(path: pathlib.Path) -> str:
    return path.read_text(encoding="utf-8", errors="replace")


def git(*args: str) -> str:
    return subprocess.check_output(["git", *args], cwd=ROOT, text=True, encoding="utf-8").strip()


def accepted_count() -> int:
    with RTM.open(encoding="utf-8-sig", newline="") as handle:
        return sum(1 for row in csv.DictReader(handle) if row["phase"] == "T2" and row["status"] == "ACCEPTED")


def owner_rows() -> list[dict]:
    catalog = load(OWNER_SOURCE)
    admin_pom = text(ROOT / "server/ruoyi-admin/pom.xml")
    reactor_pom = text(ROOT / "server/ruoyi-modules/pom.xml")
    rows: list[dict] = []
    formal_saas = {"foundation", "saas", "subscription", "service"}
    for item in catalog["owners"]:
        module = item["module"]
        base = ROOT / f"server/ruoyi-modules/jshpos-{module}"
        main = base / "src/main"
        test_root = base / "src/test"
        java = list(main.glob("java/**/*.java"))
        java_text = "\n".join(text(path) for path in java)
        rows.append({
            "owner": item["owner"],
            "module": module,
            "moduleExists": base.is_dir(),
            "defaultReactor": f"<module>jshpos-{module}</module>" in reactor_pom,
            "commercialJarDependency": f"<artifactId>jshpos-{module}</artifactId>" in admin_pom,
            "javaFiles": len(java),
            "controllerFiles": sum("@RestController" in text(path) for path in java),
            "applicationFiles": sum("/application/" in path.as_posix() for path in java),
            "domainFiles": sum("/domain/" in path.as_posix() for path in java),
            "repositoryOrMapperFiles": sum(
                path.name.endswith(("Repository.java", "Mapper.java")) or "/infrastructure/persistence/" in path.as_posix()
                for path in java
            ) + len(list(main.glob("resources/mapper/**/*.xml"))),
            "migrationFiles": len(list(main.glob("resources/db/migration/*.sql"))),
            "testFiles": len(list(test_root.glob("**/*Test.java"))) + len(list(test_root.glob("**/*IT.java"))),
            "eventOrOutboxSignals": len(re.findall(r"\b(?:Outbox|Inbox|DomainEvent|EventPublisher|EventRecord)\b", java_text)),
            "currentFormalJourneyCoverage": (
                "FORMAL_HTTP_SUBJOURNEY" if module in formal_saas
                else "STACK_SMOKE_ONLY" if module == "integration"
                else "COMPONENT_OR_AGGREGATED_EVIDENCE_ONLY"
            ),
            "oneUnifiedR4Checkpoint": False,
        })
    return rows


def existing_evidence() -> dict:
    gate7e_workflow = text(ROOT / ".github/workflows/t2-gate7e.yml")
    gate8b_workflow = text(ROOT / ".github/workflows/t2-gate8b.yml")
    flutter_test = text(ROOT / "pos-flutter/test/gate6g/formal_pos_runtime_e2e_test.dart")
    gate8b_contract = load(ROOT / "contracts/t2/gate8b/runtime-journey-v1.json")
    return {
        "gate7e": {
            "startsMySql": "mysql:" in gate7e_workflow,
            "startsRedis": "redis:" in gate7e_workflow,
            "startsJar": "java -jar" in gate7e_workflow,
            "servesWebDist": "python -m http.server" in gate7e_workflow,
            "runsFlutterFileSqlite": "formal_pos_runtime_e2e_test.dart" in gate7e_workflow,
            "flutterUsesLocalHttpServer": "HttpServer.bind" in flutter_test,
            "flutterCallsStartedJar": "18080" in flutter_test,
            "formalBusinessApiJourney": False,
        },
        "gate8b": {
            "startsMySql": "mysql:" in gate8b_workflow,
            "startsRedis": "redis:" in gate8b_workflow,
            "startsJar": "java -jar" in gate8b_workflow,
            "runsFormalHttpJourney": "run_t2_gate8b_runtime_api_journey.py" in gate8b_workflow,
            "journey": gate8b_contract["journey"],
            "includesFlutter": False,
            "includesFileSqlite": False,
            "includesAllTwentyTwoOwners": False,
        },
        "unifiedCurrentCommitThreeIndustryTwentyTwoOwnerJourney": False,
    }


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--output", required=True, type=pathlib.Path)
    parser.add_argument("--owner-csv", type=pathlib.Path)
    args = parser.parse_args()

    owners = owner_rows()
    evidence = existing_evidence()
    failures = []
    if len(owners) != 22:
        failures.append(f"expected 22 owners, got {len(owners)}")
    for row in owners:
        if not row["moduleExists"] or not row["defaultReactor"] or not row["commercialJarDependency"]:
            failures.append(f"owner assembly incomplete: {row['module']}")
        if row["testFiles"] == 0:
            failures.append(f"owner tests missing: {row['module']}")
    if not evidence["gate7e"]["flutterUsesLocalHttpServer"]:
        failures.append("Gate7E source classification drift: local Flutter HTTP server not found")
    if evidence["gate7e"]["flutterCallsStartedJar"]:
        failures.append("Gate7E source classification drift: Flutter unexpectedly targets the started JAR")
    if not evidence["gate8b"]["runsFormalHttpJourney"]:
        failures.append("Gate8B formal HTTP journey source missing")

    report = {
        "schemaVersion": "1.0",
        "gate": "G9A-R4-PREP",
        "commitSha": git("rev-parse", "HEAD"),
        "status": "PASS" if not failures else "FAIL",
        "auditClassification": "STATIC_GOVERNANCE_AND_REPOSITORY_AUDIT",
        "acceptedT2RequirementCountObserved": accepted_count(),
        "ownerCount": len(owners),
        "owners": owners,
        "existingEvidence": evidence,
        "finding": {
            "id": "G9A-E2E-P1-001",
            "state": "OPEN",
            "decomposedOpenP0": 0,
            "decomposedOpenP1": 4,
            "closureAchieved": False,
        },
        "externalExecution": 0,
        "failures": failures,
    }
    output = args.output if args.output.is_absolute() else ROOT / args.output
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    if args.owner_csv:
        owner_csv = args.owner_csv if args.owner_csv.is_absolute() else ROOT / args.owner_csv
        owner_csv.parent.mkdir(parents=True, exist_ok=True)
        with owner_csv.open("w", encoding="utf-8-sig", newline="") as handle:
            writer = csv.DictWriter(handle, fieldnames=list(owners[0]))
            writer.writeheader()
            writer.writerows(owners)
    print(f"G9A-R4 PREP AUDIT {report['status']}: owners={len(owners)} openP1=4 unifiedJourney=false")
    if failures:
        raise SystemExit("; ".join(failures))


if __name__ == "__main__":
    main()
