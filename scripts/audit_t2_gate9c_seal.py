#!/usr/bin/env python3
"""生成 Gate 9C 内部产品完整性封板机器证据与 Go/No-Go。"""
from __future__ import annotations

import argparse
import hashlib
import json
import pathlib
import re
import subprocess
import sys


ROOT = pathlib.Path(__file__).resolve().parents[1]
CONTRACT = ROOT / "contracts/t2/gate9c"
PREP_CONTRACT = ROOT / "contracts/t2/gate9c-prep"


def load_json(path: pathlib.Path) -> dict:
    return json.loads(path.read_text(encoding="utf-8"))


def sha256(path: pathlib.Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def git(*args: str) -> str:
    return subprocess.check_output(
        ["git", *args], cwd=ROOT, text=True, encoding="utf-8"
    ).strip()


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--output-dir", required=True, type=pathlib.Path)
    args = parser.parse_args()
    output = args.output_dir if args.output_dir.is_absolute() else ROOT / args.output_dir
    output.mkdir(parents=True, exist_ok=True)
    current_state_dir = output / "current-state"

    subprocess.run(
        [
            sys.executable,
            str(ROOT / "scripts/audit_t2_gate9c_product_completeness.py"),
            "--output-dir",
            str(current_state_dir),
        ],
        cwd=ROOT,
        check=True,
    )

    admission = load_json(CONTRACT / "gate-admission-v1.json")
    seal = load_json(CONTRACT / "internal-product-seal-v1.json")
    decision = load_json(CONTRACT / "go-no-go-v1.json")
    history = load_json(CONTRACT / "failure-history-manifest-v1.json")
    prep_scope = load_json(PREP_CONTRACT / "review-scope-v1.json")
    prep_closure = load_json(PREP_CONTRACT / "finding-closure-register-v1.json")
    prep_summary = load_json(current_state_dir / "summary.json")
    source_ci = load_json(CONTRACT / "source-prep-ci-v1.json")
    seal_ci = load_json(CONTRACT / "seal-ci-evidence-v1.json")
    failures: list[str] = []

    expected = {
        "acceptedRequirementCount": admission["expectedAcceptedT2Requirements"],
        "ownerModuleCount": admission["expectedOwnerModules"],
    }
    for key, value in expected.items():
        if prep_summary.get(key) != value:
            failures.append(f"current-state {key} drift: {prep_summary.get(key)} != {value}")
    if prep_summary["api"]["controllerOperations"] != admission["expectedControllerOperations"]:
        failures.append("Controller operation count drift")
    if prep_summary["api"]["openApiOperations"] != admission["expectedOpenApiOperations"]:
        failures.append("OpenAPI operation count drift")
    if prep_summary["api"]["controllerWithoutOpenApi"]:
        failures.append("Controller without OpenAPI is not zero")
    if prep_summary["api"]["openApiWithoutController"]:
        failures.append("OpenAPI without Controller is not zero")
    if prep_summary["ui"]["vue"] != admission["expectedVueSurfaces"]:
        failures.append("Vue surface count drift")
    if prep_summary["ui"]["flutter"] != admission["expectedFlutterSurfaces"]:
        failures.append("Flutter surface count drift")
    if prep_summary["productionMarkers"]["unclassified"]:
        failures.append("unclassified production markers remain")
    if prep_summary["findings"]["openInternalP0"] or prep_summary["findings"]["openInternalP1"]:
        failures.append("internal P0/P1 is not zero")
    if prep_summary["result"] != "PASS":
        failures.append("Gate 9C-Prep current-state audit is not PASS")

    closed_findings = [
        item for item in prep_closure["findings"] if item["state"] == "CLOSED_IN_GATE9B"
    ]
    if len(closed_findings) != admission["expectedClosedGate9BFindings"]:
        failures.append("Gate 9B finding closure count drift")
    if set(seal["gate9BFindings"]) != {item["findingId"] for item in closed_findings}:
        failures.append("sealed finding identity drift")

    formal = prep_scope["formalRuntime"]
    for key, expected_value in (
        ("industries", admission["expectedIndustries"]),
        ("ownerCheckpoints", admission["expectedOwnerCheckpoints"]),
        ("conservationAssertions", admission["expectedConservationAssertions"]),
        ("fixedFaultSeeds", admission["expectedFaultSeeds"]),
    ):
        if formal[key] != expected_value:
            failures.append(f"formal runtime {key} drift: {formal[key]} != {expected_value}")

    change_log = (ROOT / history["sourceChangeLog"]).read_text(encoding="utf-8")
    for number in range(3, 24):
        cr_id = f"CR-T2G9R4-{number:03d}"
        if cr_id not in change_log:
            failures.append(f"missing preserved R4 CR: {cr_id}")
    for chain in history["closureChains"]:
        report = ROOT / chain["report"]
        if not report.is_file():
            failures.append(f"missing closure report: {chain['report']}")
        if not re.fullmatch(r"\d{11}", chain["finalRunId"]):
            failures.append(f"invalid run id: {chain['finalRunId']}")

    if source_ci["commitSha"] != admission["baselineCommit"]:
        failures.append("source prep CI commit is not Gate 9C baseline")
    if source_ci["result"] != "SUCCESS" or len(source_ci["artifacts"]) != 8:
        failures.append("source prep CI evidence incomplete")
    for artifact in source_ci["artifacts"]:
        if not re.fullmatch(r"[0-9a-f]{64}", artifact["sha256"]):
            failures.append(f"invalid artifact digest: {artifact['name']}")

    if seal_ci["result"] != "SUCCESS" or seal_ci["jobCount"] != 8:
        failures.append("Gate 9C complete CI evidence incomplete")
    if len(seal_ci["artifacts"]) != 8:
        failures.append("Gate 9C artifact count drift")
    if seal_ci["rerunFailedJob"] or seal_ci["automaticTag"]:
        failures.append("Gate 9C rerun/tag boundary drift")
    if not re.fullmatch(r"[0-9a-f]{40}", seal_ci["commitSha"]):
        failures.append("invalid Gate 9C candidate commit")
    for artifact in seal_ci["artifacts"]:
        if not re.fullmatch(r"\d{10}", artifact["artifactId"]):
            failures.append(f"invalid Gate 9C artifact id: {artifact['name']}")
        if not re.fullmatch(r"[0-9a-f]{64}", artifact["sha256"]):
            failures.append(f"invalid Gate 9C artifact digest: {artifact['name']}")
    git("cat-file", "-e", f"{seal_ci['commitSha']}^{{commit}}")

    critical_paths = [
        "docs/governance/rtm.csv",
        "docs/governance/change-log.md",
        "contracts/t2/gate9c-prep/finding-closure-register-v1.json",
        "contracts/t2/gate9c-prep/review-scope-v1.json",
        "contracts/t2/gate9a-prep/owner-catalog-v1.json",
        "contracts/t2/gate9a-prep/ui-surface-catalog-v1.json",
        "contracts/t2/gate9b-r4/gate-admission-v1.json",
        "docs/t2-gate9b-r4-runtime/02_G9A-R4最终证据索引.md",
        "contracts/t2/gate9c/source-prep-ci-v1.json",
        "contracts/t2/gate9c/seal-ci-evidence-v1.json",
        "contracts/t2/gate9c/go-no-go-v1.json",
    ]
    input_manifest = []
    for relative in critical_paths:
        path = ROOT / relative
        if not path.is_file():
            failures.append(f"missing critical seal input: {relative}")
            continue
        input_manifest.append(
            {"path": relative, "size": path.stat().st_size, "sha256": sha256(path)}
        )

    if decision["decision"] != "CONDITIONAL_PASS_AWAITING_SPONSOR_CONFIRMATION":
        failures.append("machine Go/No-Go decision drift")
    if decision["internalProductCompletenessSeal"]["automaticTag"]:
        failures.append("automatic tag must remain disabled")
    if any(admission["externalExecution"].values()):
        failures.append("external execution must remain zero")
    git("cat-file", "-e", f"{admission['baselineCommit']}^{{commit}}")

    summary = {
        "schemaVersion": "1.0",
        "gate": admission["gate"],
        "requirementId": admission["requirementId"],
        "commitSha": git("rev-parse", "HEAD"),
        "baselineCommit": admission["baselineCommit"],
        "acceptedRequirements": prep_summary["acceptedRequirementCount"],
        "owners": prep_summary["ownerModuleCount"],
        "api": prep_summary["api"],
        "ui": prep_summary["ui"],
        "formalRuntime": formal,
        "closedGate9BFindings": len(closed_findings),
        "openInternalP0": prep_summary["findings"]["openInternalP0"],
        "openInternalP1": prep_summary["findings"]["openInternalP1"],
        "externalStates": admission["externalStates"],
        "externalExecution": admission["externalExecution"],
        "criticalInputCount": len(input_manifest),
        "hardFailures": failures,
        "result": "PASS" if not failures else "FAIL",
        "recommendation": (
            "CONDITIONAL_PASS_AWAITING_SPONSOR_CONFIRMATION"
            if not failures
            else "NO_GO_SEAL_INCOMPLETE"
        ),
    }
    (output / "critical-inputs.json").write_text(
        json.dumps(input_manifest, ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
    )
    (output / "failure-history.json").write_text(
        json.dumps(history, ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
    )
    (output / "go-no-go.json").write_text(
        json.dumps(decision, ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
    )
    (output / "seal-summary.json").write_text(
        json.dumps(summary, ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
    )
    print(
        "T2 Gate9C SEAL AUDIT "
        f"{summary['result']}: accepted={summary['acceptedRequirements']} owners={summary['owners']} "
        f"api={summary['api']['controllerOperations']}/{summary['api']['openApiOperations']} "
        f"surfaces={summary['ui']['total']} findings={summary['closedGate9BFindings']} "
        f"P0={summary['openInternalP0']} P1={summary['openInternalP1']} hard={len(failures)}"
    )
    if failures:
        raise SystemExit("\n".join(f"- {failure}" for failure in failures))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
