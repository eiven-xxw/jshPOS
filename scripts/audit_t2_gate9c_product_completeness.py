#!/usr/bin/env python3
"""复审商业 V1 当前 88 项需求、22 Owner、300 API、26 页面与 Gate 9B 关闭证据。"""
from __future__ import annotations

import argparse
import csv
import importlib.util
import json
import pathlib
import subprocess


ROOT = pathlib.Path(__file__).resolve().parents[1]
CONTRACT = ROOT / "contracts/t2/gate9c-prep"
HISTORICAL_CONTRACT = ROOT / "contracts/t2/gate9a-prep"
RTM = ROOT / "docs/governance/rtm.csv"


def load_json(path: pathlib.Path) -> dict:
    return json.loads(path.read_text(encoding="utf-8"))


def load_module(name: str, path: pathlib.Path):
    spec = importlib.util.spec_from_file_location(name, path)
    if spec is None or spec.loader is None:
        raise RuntimeError(f"cannot load module: {path}")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def git(*args: str) -> str:
    return subprocess.check_output(
        ["git", *args], cwd=ROOT, text=True, encoding="utf-8"
    ).strip()


def write_csv(path: pathlib.Path, rows: list[dict]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    if not rows:
        path.write_text("\n", encoding="utf-8")
        return
    with path.open("w", encoding="utf-8-sig", newline="") as handle:
        writer = csv.DictWriter(handle, fieldnames=list(rows[0]))
        writer.writeheader()
        writer.writerows(rows)


def reference_exists(value: str) -> bool:
    if value.startswith(("http://", "https://")):
        return True
    return (ROOT / value).exists()


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--output-dir", required=True, type=pathlib.Path)
    args = parser.parse_args()
    output = args.output_dir if args.output_dir.is_absolute() else ROOT / args.output_dir
    output.mkdir(parents=True, exist_ok=True)

    historical = load_module(
        "gate9a_product_audit",
        ROOT / "scripts/audit_t2_gate9a_product_completeness.py",
    )
    admission = load_json(CONTRACT / "gate-admission-v1.json")
    closure = load_json(CONTRACT / "finding-closure-register-v1.json")
    gaps = load_json(CONTRACT / "gap-register-v1.json")
    owner_catalog = load_json(HISTORICAL_CONTRACT / "owner-catalog-v1.json")
    ui_catalog = load_json(HISTORICAL_CONTRACT / "ui-surface-catalog-v1.json")

    accepted = historical.accepted_requirements()
    states = historical.all_t2_states()
    modules = [item["module"] for item in owner_catalog["owners"]]
    module_rows = historical.module_assets(modules)
    coverage, coverage_failures = historical.requirement_coverage(
        accepted, module_rows, owner_catalog, ui_catalog
    )
    surfaces, _ = historical.surface_quality(ui_catalog)
    allowed_markers, unresolved_markers = historical.production_markers()
    api = historical.api_drift()
    client_roots = historical.client_api_roots(ui_catalog, api)

    failures: list[str] = list(coverage_failures)
    expected_closures = set(admission["closureFindings"])
    actual_closures = {
        item["findingId"]
        for item in closure["findings"]
        if item["state"] == "CLOSED_IN_GATE9B"
    }
    if actual_closures != expected_closures:
        failures.append(
            f"finding closure drift: {sorted(actual_closures)} != {sorted(expected_closures)}"
        )
    for item in closure["findings"]:
        for evidence in item["evidence"]:
            if not reference_exists(evidence):
                failures.append(f"missing closure evidence: {item['findingId']} {evidence}")
        for key in ("candidateCommit",):
            try:
                git("cat-file", "-e", f"{item[key]}^{{commit}}")
            except subprocess.CalledProcessError:
                failures.append(f"missing closure commit: {item['findingId']} {item[key]}")

    if len(accepted) != admission["expectedAcceptedT2Requirements"]:
        failures.append(f"accepted requirement count drift: {len(accepted)}")
    if len(modules) != admission["expectedOwnerModules"]:
        failures.append(f"owner count drift: {len(modules)}")
    incomplete_owners = [
        row["module"]
        for row in module_rows
        if not row["pom"]
        or not row["javaFiles"]
        or not row["tests"]
        or not row["adminAssembly"]
        or not row["reactorAssembly"]
        or not row["dependencyManagement"]
    ]
    if incomplete_owners:
        failures.append(f"owner assembly incomplete: {incomplete_owners}")
    if api["controllerCount"] != admission["expectedControllerOperations"]:
        failures.append(f"controller operation drift: {api['controllerCount']}")
    if api["openApiOperationCount"] != admission["expectedOpenApiOperations"]:
        failures.append(f"OpenAPI operation drift: {api['openApiOperationCount']}")
    if api["controllerWithoutOpenApi"] or api["openApiWithoutController"]:
        failures.append("Controller/OpenAPI bidirectional difference is not zero")
    if len(ui_catalog["vueSurfaces"]) != admission["expectedVueSurfaces"]:
        failures.append("Vue surface count drift")
    if len(ui_catalog["flutterSurfaces"]) != admission["expectedFlutterSurfaces"]:
        failures.append("Flutter surface count drift")
    missing_surfaces = [row["path"] for row in surfaces if not (ROOT / row["path"]).is_file()]
    direct_surface_access = [
        row["path"] for row in surfaces if row["directRuntimeAccessViolations"]
    ]
    if missing_surfaces:
        failures.append(f"missing formal surfaces: {missing_surfaces}")
    if direct_surface_access:
        failures.append(f"UI direct runtime access: {direct_surface_access}")
    unbacked_roots = [row["apiRoot"] for row in client_roots if not row["formalServerBacked"]]
    if unbacked_roots:
        failures.append(f"client API roots without formal server: {unbacked_roots}")
    if unresolved_markers:
        failures.append(f"unclassified production markers: {len(unresolved_markers)}")
    for requirement_id, expected in admission["externalStates"].items():
        if states.get(requirement_id) != expected:
            failures.append(
                f"external state drift: {requirement_id}={states.get(requirement_id)} != {expected}"
            )
    if gaps["internalAcceptedScope"]["openP0"] != 0 or gaps["internalAcceptedScope"]["openP1"] != 0:
        failures.append("Gate 9C internal P0/P1 summary is not zero")

    summary = {
        "schemaVersion": "1.0",
        "gate": admission["gate"],
        "requirementId": admission["requirementId"],
        "commitSha": git("rev-parse", "HEAD"),
        "evidenceLevel": admission["evidenceLevel"],
        "acceptedRequirementCount": len(accepted),
        "ownerModuleCount": len(modules),
        "api": {
            "controllerOperations": api["controllerCount"],
            "openApiOperations": api["openApiOperationCount"],
            "controllerWithoutOpenApi": len(api["controllerWithoutOpenApi"]),
            "openApiWithoutController": len(api["openApiWithoutController"]),
            "clientRootsWithoutFormalServer": len(unbacked_roots),
        },
        "ui": {
            "vue": len(ui_catalog["vueSurfaces"]),
            "flutter": len(ui_catalog["flutterSurfaces"]),
            "total": len(surfaces),
            "missing": len(missing_surfaces),
            "directRuntimeAccess": len(direct_surface_access),
        },
        "productionMarkers": {
            "reviewed": len(allowed_markers),
            "unclassified": len(unresolved_markers),
        },
        "findings": {
            "closedInGate9B": len(actual_closures),
            "openInternalP0": gaps["internalAcceptedScope"]["openP0"],
            "openInternalP1": gaps["internalAcceptedScope"]["openP1"],
        },
        "externalExecution": admission["externalExecution"],
        "hardFailures": failures,
        "result": "PASS" if not failures else "FAIL",
        "recommendation": (
            "CONDITIONAL_PASS_PREP_AWAITING_SPONSOR_CONFIRMATION"
            if not failures
            else "NO_GO_REVIEW_INCOMPLETE"
        ),
    }

    write_csv(output / "requirement-coverage.csv", coverage)
    write_csv(output / "owner-module-matrix.csv", module_rows)
    write_csv(output / "page-api-matrix.csv", surfaces)
    (output / "api-drift.json").write_text(
        json.dumps(api, ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
    )
    (output / "client-api-roots.json").write_text(
        json.dumps(client_roots, ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
    )
    (output / "production-markers.json").write_text(
        json.dumps(
            {"reviewed": allowed_markers, "unclassified": unresolved_markers},
            ensure_ascii=False,
            indent=2,
        )
        + "\n",
        encoding="utf-8",
    )
    (output / "finding-closure.json").write_text(
        json.dumps(closure, ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
    )
    (output / "summary.json").write_text(
        json.dumps(summary, ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
    )
    print(
        "T2 Gate9C audit "
        f"{summary['result']}: accepted={len(accepted)} owners={len(modules)} "
        f"api={api['controllerCount']}/{api['openApiOperationCount']} "
        f"surfaces={len(surfaces)} closed={len(actual_closures)} "
        f"P0={summary['findings']['openInternalP0']} "
        f"P1={summary['findings']['openInternalP1']} hard={len(failures)}"
    )
    if failures:
        raise SystemExit("\n".join(f"- {failure}" for failure in failures))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
