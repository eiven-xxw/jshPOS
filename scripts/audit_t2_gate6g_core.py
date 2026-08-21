#!/usr/bin/env python3
"""审计已接受 Owner 的生产代码装配、临时实现和需求覆盖。"""
from __future__ import annotations

import argparse
import csv
import json
import pathlib
import re
from collections import Counter


ROOT = pathlib.Path(__file__).resolve().parents[1]
RTM = ROOT / "docs/governance/rtm.csv"
MODULES = (
    "catalog", "costing", "foundation", "inventory", "member", "order",
    "payment", "procurement", "promotion", "release", "reporting",
    "resilience", "returns", "sync", "transfer",
)
OWNER_BY_PREFIX = {
    "IAM": ("foundation",), "ORG": ("foundation",), "RBAC": ("foundation",),
    "CFG": ("foundation",), "AUD": ("foundation",), "SEC": ("foundation",),
    "OBS": ("foundation",), "MIG": ("foundation",), "PRD": ("catalog",),
    "PRC": ("catalog",), "DPK": ("catalog",), "ORD": ("order",),
    "OFF": ("order",), "SYN": ("sync",), "TRM": ("sync",),
    "PAY": ("payment",), "REF": ("returns", "payment", "promotion", "inventory"),
    "REC": ("payment", "reporting"), "INV": ("inventory",), "PUR": ("procurement",),
    "CST": ("costing",), "TRF": ("transfer",), "PRM": ("promotion",),
    "MEM": ("member",), "RPT": ("reporting",), "BAK": ("resilience",),
    "UPG": ("release",),
    "POS": ("order", "sync", "promotion", "returns"),
    "ADM": ("foundation", "catalog", "inventory", "procurement", "transfer", "costing",
            "promotion", "member", "reporting", "sync", "release"),
    "E2E": MODULES,
}
MARKER = re.compile(r"\b(Fake|Mock|Stub|InMemory|TODO|FIXME|UnsupportedOperationException|UnimplementedError)\b", re.I)


def rel(path: pathlib.Path) -> str:
    return path.relative_to(ROOT).as_posix()


def existing_reference(value: str) -> bool:
    if not value or value.startswith(("http://", "https://", "GitHub Run")):
        return bool(value)
    return (ROOT / value).exists()


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--output", required=True, type=pathlib.Path)
    args = parser.parse_args()
    with RTM.open(encoding="utf-8-sig", newline="") as handle:
        accepted = [row for row in csv.DictReader(handle) if row["phase"] == "T2" and row["status"] == "ACCEPTED"]

    module_assets = {}
    for module in MODULES:
        base = ROOT / f"server/ruoyi-modules/jshpos-{module}"
        main = base / "src/main"
        tests = base / "src/test"
        java = list(main.glob("java/**/*.java"))
        module_assets[module] = {
            "api": [rel(path) for path in java if "@RestController" in path.read_text(encoding="utf-8", errors="replace")],
            "application": [rel(path) for path in java if "/application/" in path.as_posix() and path.name.endswith(("Service.java", "Processor.java", "Receiver.java", "Dispatcher.java"))],
            "domain": [rel(path) for path in java if "/domain/" in path.as_posix()],
            "repositoryOrMapper": [rel(path) for path in main.glob("java/**/*Mapper.java")]
                + [rel(path) for path in main.glob("java/**/*Repository.java")]
                + [rel(path) for path in main.glob("resources/mapper/**/*.xml")],
            "migration": [rel(path) for path in main.glob("resources/db/migration/*.sql")],
            "test": [rel(path) for path in tests.glob("**/*Test.java")]
                + [rel(path) for path in tests.glob("**/*IT.java")],
        }

    coverage = []
    missing_refs = []
    for row in accepted:
        references = [part.strip() for part in row["implementation"].split(";") if part.strip()]
        existing = [reference for reference in references if existing_reference(reference)]
        if not existing:
            missing_refs.append(row["requirement_id"])
        prefix = row["requirement_id"].split("-")[1]
        owners = OWNER_BY_PREFIX.get(prefix, ())
        pages = [reference for reference in references if reference.startswith(("admin-web/", "pos-flutter/"))]
        chain = {
            key: sorted({path for owner in owners for path in module_assets.get(owner, {}).get(key, [])})
            for key in ("api", "application", "domain", "repositoryOrMapper", "migration", "test")
        }
        coverage.append({
            "requirementId": row["requirement_id"],
            "domain": row["domain"],
            "status": row["status"],
            "owners": list(owners),
            "page": pages or ["N/A_BY_REQUIREMENT_SCOPE"],
            **chain,
            "implementationReferenceCount": len(references),
            "existingReferenceCount": len(existing),
            "acceptanceDefined": bool(row["acceptance"].strip()),
            "testEvidenceDefined": bool(row["test_evidence"].strip()),
        })

    module_rows = []
    for module in MODULES:
        base = ROOT / f"server/ruoyi-modules/jshpos-{module}"
        main = base / "src/main"
        tests = base / "src/test"
        java = list(main.glob("java/**/*.java"))
        mapper = list(main.glob("resources/mapper/**/*.xml"))
        migrations = list(main.glob("resources/db/migration/*.sql"))
        controllers = [path for path in java if "@RestController" in path.read_text(encoding="utf-8", errors="replace")]
        services = [path for path in java if re.search(r"@(Service|Component)\b", path.read_text(encoding="utf-8", errors="replace"))]
        module_rows.append({
            "module": module,
            "pom": (base / "pom.xml").is_file(),
            "javaFiles": len(java),
            "controllers": len(controllers),
            "applicationComponents": len(services),
            "mapperFiles": len(mapper),
            "migrations": len(migrations),
            "tests": len(list(tests.glob("**/*Test.java"))) + len(list(tests.glob("**/*IT.java"))),
        })

    prod_roots = [
        *(ROOT / "server/ruoyi-modules").glob("jshpos-*/src/main"),
        ROOT / "admin-web/src/api/catalog", ROOT / "admin-web/src/api/foundation",
        ROOT / "admin-web/src/api/operations", ROOT / "admin-web/src/api/reporting",
        ROOT / "admin-web/src/api/terminal", ROOT / "pos-flutter/lib",
        ROOT / "packages/pos_device_adapter/lib",
    ]
    marker_findings = []
    allowed_fail_closed = []
    for base in prod_roots:
        if not base.exists():
            continue
        for path in base.rglob("*"):
            if not path.is_file() or path.suffix.lower() not in {".java", ".xml", ".dart", ".ts", ".vue", ".sql"}:
                continue
            text = path.read_text(encoding="utf-8", errors="replace")
            for number, line in enumerate(text.splitlines(), 1):
                if not MARKER.search(line):
                    continue
                finding = {"path": rel(path), "line": number, "text": line.strip()[:240]}
                if (
                    "locked_pos_" in path.name
                    or path.name == "pos_device_adapter_platform_interface.dart"
                    or path.name == "pos_local_database.dart" and "inMemory" in line
                    or "FAKE_TEST" in line
                ):
                    allowed_fail_closed.append(finding)
                else:
                    marker_findings.append(finding)

    admin_pom = (ROOT / "server/ruoyi-admin/pom.xml").read_text(encoding="utf-8")
    missing_modules = [module for module in MODULES if f"jshpos-{module}" not in admin_pom]
    ownership = {
        "catalog": ("cat_", "prc_", "dpk_"), "costing": ("inv_cost_",),
        "foundation": ("jsh_",), "inventory": ("inv_stock_", "inv_audit_", "inv_event_"),
        "member": ("mem_",), "order": ("ord_", "shf_"), "payment": ("pay_",),
        "procurement": ("sup_", "pur_"), "promotion": ("prm_",), "release": ("rel_",),
        "reporting": ("rpt_",), "resilience": ("bak_",), "returns": ("ret_",),
        "sync": ("pos_sync_",), "transfer": ("inv_transfer_",),
    }
    table_pattern = re.compile(r"\b(?:FROM|JOIN|INTO|UPDATE|DELETE\s+FROM)\s+([a-z][a-z0-9_]*)", re.I)
    cross_owner = []
    for path in (ROOT / "server/ruoyi-modules").glob("jshpos-*/src/main/**/*Mapper.*"):
        source = path.read_text(encoding="utf-8", errors="replace")
        owner = path.parts[path.parts.index("ruoyi-modules") + 1].removeprefix("jshpos-")
        for table in table_pattern.findall(source):
            table_owner = next((candidate for candidate, prefixes in ownership.items()
                                if any(table.lower().startswith(prefix) for prefix in prefixes)), None)
            if table_owner and table_owner != owner:
                cross_owner.append({"path": rel(path), "owner": owner, "foreignOwner": table_owner, "table": table})

    hard_failures = []
    if missing_refs:
        hard_failures.append(f"accepted requirements without reachable implementation evidence: {missing_refs}")
    if missing_modules:
        hard_failures.append(f"ruoyi-admin missing formal modules: {missing_modules}")
    if marker_findings:
        hard_failures.append(f"unclassified production temporary markers: {len(marker_findings)}")
    if cross_owner:
        hard_failures.append(f"cross-owner Mapper references: {len(cross_owner)}")
    if any(not item["pom"] or not item["javaFiles"] or not item["tests"] for item in module_rows):
        hard_failures.append("one or more formal modules lack pom, production Java, or tests")

    result = {
        "requirementId": "T2-CORE-001",
        "evidenceLevel": "STATIC_AND_SOFTWARE_EXECUTION",
        "acceptedRequirementCount": len(accepted),
        "coverage": coverage,
        "modules": module_rows,
        "moduleTotals": dict(Counter({
            "modules": len(module_rows),
            "controllers": sum(item["controllers"] for item in module_rows),
            "applicationComponents": sum(item["applicationComponents"] for item in module_rows),
            "mappers": sum(item["mapperFiles"] for item in module_rows),
            "migrations": sum(item["migrations"] for item in module_rows),
            "tests": sum(item["tests"] for item in module_rows),
        })),
        "runtimeAssembly": {"ruoyiAdminFormalModules": list(MODULES), "missing": missing_modules},
        "productionMarkerFindings": marker_findings,
        "allowedFailClosedBoundaries": allowed_fail_closed,
        "crossOwnerMapperFindings": cross_owner,
        "hardFailures": hard_failures,
        "result": "PASS" if not hard_failures else "FAIL",
    }
    output = args.output if args.output.is_absolute() else ROOT / args.output
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(f"T2 CORE audit {result['result']}: accepted={len(accepted)}, modules={len(module_rows)}, hardFailures={len(hard_failures)}")
    if hard_failures:
        for failure in hard_failures:
            print(f"- {failure}")
        raise SystemExit(1)


if __name__ == "__main__":
    main()
