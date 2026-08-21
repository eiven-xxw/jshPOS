#!/usr/bin/env python3
"""审计商业 V1 Owner 组合根、显式端口、正式 JAR 装配和关键回归证据。"""
from __future__ import annotations

import argparse
import json
import pathlib
import re
import xml.etree.ElementTree as ET
import zipfile


ROOT = pathlib.Path(__file__).resolve().parents[1]
CONTRACT = ROOT / "contracts/t2/gate6g/owner-integration-v1.json"
MODULES = (
    "foundation", "catalog", "order", "sync", "payment", "inventory",
    "procurement", "transfer", "costing", "promotion", "returns", "member",
    "reporting", "resilience", "release", "integration",
)
REQUIRED_SUITES = {
    "CommercialV1AssemblyContractTest",
    "CommercialV1AssemblyVerifierTest",
    "TerminalRegistryServiceTest",
    "PromotedOrderEventDispatcherTest",
    "PromotedCashOrderServiceTest",
    "InventoryLedgerServiceTest",
    "CostingServiceTest",
    "ReturnSagaCoordinatorTest",
    "ReportingProjectionServiceTest",
    "BackupRecoveryServiceTest",
    "ReleaseGovernanceServiceTest",
}
ALLOWED_MECHANISMS = {"APPLICATION_PORT", "READ_ONLY_PORT", "VERSIONED_EVENT", "INBOX_OUTBOX"}


def relative(path: pathlib.Path) -> str:
    return path.relative_to(ROOT).as_posix()


def module_from(path: pathlib.Path) -> str:
    return path.parts[path.parts.index("ruoyi-modules") + 1].removeprefix("jshpos-")


def inspect_cross_owner_imports() -> tuple[list[dict], list[dict]]:
    forbidden: list[dict] = []
    shared_types: list[dict] = []
    pattern = re.compile(r"^import com\.jingshanghui\.pos\.([a-z0-9]+)\.([^;]+);", re.MULTILINE)
    for path in (ROOT / "server/ruoyi-modules").glob("jshpos-*/src/main/java/**/*.java"):
        source_owner = module_from(path)
        for target_owner, symbol in pattern.findall(path.read_text(encoding="utf-8", errors="replace")):
            if target_owner in {source_owner, "foundation"}:
                continue
            item = {"path": relative(path), "source": source_owner, "target": target_owner, "symbol": symbol}
            if source_owner != "integration" and (
                symbol.startswith("application.service.") or symbol.startswith("infrastructure.")
            ):
                forbidden.append(item)
            elif not symbol.startswith("application.port."):
                shared_types.append(item)
    return forbidden, shared_types


def inspect_surefire(root: pathlib.Path | None) -> dict:
    if root is None:
        return {"checked": False, "requiredSuites": sorted(REQUIRED_SUITES)}
    reports = list(root.glob("jshpos-*/target/surefire-reports/TEST-*.xml"))
    suites: dict[str, dict] = {}
    for report in reports:
        element = ET.parse(report).getroot()
        name = element.attrib.get("name", "").split(".")[-1]
        suites[name] = {
            "path": relative(report),
            "tests": int(element.attrib.get("tests", 0)),
            "failures": int(element.attrib.get("failures", 0)),
            "errors": int(element.attrib.get("errors", 0)),
            "skipped": int(element.attrib.get("skipped", 0)),
        }
    missing = sorted(REQUIRED_SUITES - suites.keys())
    failed = sorted(name for name, value in suites.items()
                    if name in REQUIRED_SUITES and (value["failures"] or value["errors"] or value["skipped"] or not value["tests"]))
    return {"checked": True, "reports": len(reports), "missing": missing, "failedOrSkipped": failed,
            "required": {name: suites[name] for name in sorted(REQUIRED_SUITES & suites.keys())}}


def inspect_server_jar(path: pathlib.Path | None) -> dict:
    if path is None:
        return {"checked": False, "requiredModules": list(MODULES)}
    if not path.is_file():
        return {"checked": True, "missingJar": relative(path), "missingModules": list(MODULES)}
    with zipfile.ZipFile(path) as archive:
        libraries = [name for name in archive.namelist() if name.startswith("BOOT-INF/lib/jshpos-") and name.endswith(".jar")]
    missing = [module for module in MODULES if not any(f"jshpos-{module}-" in name for name in libraries)]
    return {"checked": True, "path": relative(path), "jshposLibraries": sorted(libraries), "missingModules": missing}


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--output", required=True, type=pathlib.Path)
    parser.add_argument("--server-jar", type=pathlib.Path)
    parser.add_argument("--surefire-root", type=pathlib.Path)
    args = parser.parse_args()
    server_jar = args.server_jar.resolve() if args.server_jar is not None else None
    surefire_root = args.surefire_root.resolve() if args.surefire_root is not None else None
    contract = json.loads(CONTRACT.read_text(encoding="utf-8"))
    failures: list[str] = []

    if contract.get("requirementId") != "T2-INT-001" or contract.get("compositionRoot") != "jshpos-integration":
        failures.append("integration contract identity drift")
    owners = contract.get("owners", [])
    if len(owners) != 16 or len(set(owners)) != 16:
        failures.append("owner set must contain 16 unique capabilities")
    collaborations = contract.get("collaborations", [])
    invalid_collaborations = [item for item in collaborations if item.get("mechanism") not in ALLOWED_MECHANISMS
                              or not re.search(r"\.v[1-9][0-9]*$", str(item.get("contract", "")))]
    if invalid_collaborations:
        failures.append(f"invalid or unversioned collaborations: {len(invalid_collaborations)}")
    seeds = contract.get("failureSeeds", [])
    if len(seeds) != 12 or len(set(seeds)) != 12:
        failures.append("failure seed ledger must contain 12 unique seeds")

    module_pom = (ROOT / "server/ruoyi-modules/pom.xml").read_text(encoding="utf-8")
    admin_pom = (ROOT / "server/ruoyi-admin/pom.xml").read_text(encoding="utf-8")
    parent_pom = (ROOT / "server/pom.xml").read_text(encoding="utf-8")
    for document_name, document in (("reactor", module_pom), ("admin", admin_pom), ("dependencyManagement", parent_pom)):
        if "jshpos-integration" not in document:
            failures.append(f"jshpos-integration missing from {document_name}")
    auto_import = ROOT / "server/ruoyi-modules/jshpos-integration/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports"
    if not auto_import.is_file() or "CommercialV1IntegrationAutoConfiguration" not in auto_import.read_text(encoding="utf-8"):
        failures.append("integration auto-configuration import missing")

    forbidden_imports, shared_types = inspect_cross_owner_imports()
    if forbidden_imports:
        failures.append(f"cross-owner service or infrastructure imports: {len(forbidden_imports)}")
    surefire = inspect_surefire(surefire_root)
    if surefire.get("missing") or surefire.get("failedOrSkipped"):
        failures.append("required integration regression suite missing, failed or skipped")
    server_jar_evidence = inspect_server_jar(server_jar)
    if server_jar_evidence.get("missingJar") or server_jar_evidence.get("missingModules"):
        failures.append("formal server JAR does not contain all Owner and composition modules")

    result = {
        "requirementId": "T2-INT-001",
        "evidenceLevel": "STATIC_AND_SOFTWARE_EXECUTION",
        "owners": owners,
        "collaborationCount": len(collaborations),
        "failureSeeds": seeds,
        "forbiddenCrossOwnerImports": forbidden_imports,
        "sharedContractTypeImports": shared_types,
        "serverJar": server_jar_evidence,
        "surefire": surefire,
        "externalBoundaries": contract.get("externalBoundaries"),
        "hardFailures": failures,
        "result": "PASS" if not failures else "FAIL",
    }
    output = args.output if args.output.is_absolute() else ROOT / args.output
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(f"T2 INT audit {result['result']}: owners={len(owners)}, collaborations={len(collaborations)}, hardFailures={len(failures)}")
    if failures:
        raise SystemExit("\n".join(f"- {failure}" for failure in failures))


if __name__ == "__main__":
    main()
