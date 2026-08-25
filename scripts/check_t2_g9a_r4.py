#!/usr/bin/env python3
"""校验 G9A-R4 范围、正式栈测试边界与外部零执行。"""
from __future__ import annotations

import argparse
import json
import pathlib
import re
import subprocess


ROOT = pathlib.Path(__file__).resolve().parents[1]
BASELINE = "059f47ebd6877b683345d1e6f7c0cd9a18d712b5"
BRANCH = "t2/gate9b-sprint27i-g9a-r4-runtime"
OWNERS = {
    "saas", "subscription", "foundation", "service", "migration", "onboarding", "catalog",
    "sync", "order", "promotion", "member", "payment", "inventory", "costing", "procurement",
    "transfer", "returns", "reporting", "operations", "resilience", "release", "integration",
}


def require(condition: bool, message: str) -> None:
    if not condition:
        raise SystemExit(f"T2-G9A-R4 ERROR: {message}")


def load(relative: str) -> dict:
    path = ROOT / relative
    require(path.is_file(), f"missing {relative}")
    try:
        return json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as error:
        raise SystemExit(f"T2-G9A-R4 ERROR: invalid {relative}: {error}") from error


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--output", required=True, type=pathlib.Path)
    args = parser.parse_args()

    subprocess.run(["git", "merge-base", "--is-ancestor", BASELINE, "HEAD"], cwd=ROOT, check=True)
    admission = load("contracts/t2/gate9b-r4/gate-admission-v1.json")
    checkpoints = load("contracts/t2/gate9b-r4/owner-checkpoint-v1.json")
    faults = load("contracts/t2/gate9b-r4/fault-seeds-v1.json")
    red = ROOT / "scripts/run_t2_g9a_r4_r0_red_regressions.py"
    bootstrap = ROOT / "scripts/run_t2_g9a_r4_bootstrap.py"
    flutter = ROOT / "pos-flutter/test/gate9b/g9a_r4_formal_jar_e2e_test.dart"

    require(admission.get("findingId") == "G9A-E2E-P1-001", "finding identity drift")
    require(admission.get("findingState") == "OPEN", "finding must remain OPEN before sponsor confirmation")
    require(admission.get("newRequirementIds") == [], "new Requirement ID is forbidden")
    external = admission.get("externalExecution", {})
    require(isinstance(external, dict) and external and all(value == 0 for value in external.values()),
            "external execution must remain zero")
    require({item.lower() for item in checkpoints.get("owners", [])} == OWNERS, "22 Owner set drift")
    require(len(faults.get("seeds", [])) == 12, "twelve fixed fault seeds are required")
    require(red.is_file() and bootstrap.is_file() and flutter.is_file(), "R0/bootstrap/Flutter harness missing")

    bootstrap_text = bootstrap.read_text(encoding="utf-8")
    activate_marker = 'f"{label}-store-activate"'
    publish_marker = 'f"{label}-price-book-publish"'
    require(activate_marker in bootstrap_text, "formal bootstrap must activate PREPARING stores through API")
    require(bootstrap_text.index(activate_marker) < bootstrap_text.index(publish_marker),
            "store activation must precede tenant price publication")
    create_tenant = bootstrap_text.split("def create_tenant", 1)[1]
    platform_login = 'f"{label}-platform-login-before-application"'
    application_create = '"/api/v1/saas/applications"'
    require(platform_login in create_tenant, "each onboarding journey must restore the trusted platform context")
    require(create_tenant.index(platform_login) < create_tenant.index(application_create),
            "trusted platform context must be restored before application creation")

    flutter_text = flutter.read_text(encoding="utf-8")
    require("HttpServer.bind" not in flutter_text, "formal Flutter journey must not bind an embedded HTTP server")
    require("R4_BASE_URL" in flutter_text, "formal Flutter journey must receive the live JAR URL")
    require("PosLocalDatabase.openPath" not in flutter_text, "test must use production assembler, not direct database writes")
    require("FilePosBusinessRuntimeAssembler" in flutter_text, "file SQLite production assembler is required")
    require("OpenMode.readOnly" in flutter_text, "SQLite evidence must be read-only after the journey")
    require("T2-PAY-002" not in flutter_text, "blocked Provider capability must not be implemented in Flutter test")

    changed = subprocess.check_output(
        ["git", "diff", "--name-only", f"{BASELINE}...HEAD"], cwd=ROOT, text=True,
    ).splitlines()
    published_migrations = [item for item in changed if "/db/migration/" in item.replace("\\", "/")]
    require(not published_migrations, f"published migrations changed: {published_migrations}")
    provider_source = [item for item in changed if re.search(r"(?i)(provider|lakala|lakara|pay-sdk|callback)", item)]
    require(not provider_source, f"Provider implementation is forbidden: {provider_source}")

    payload = {
        "schemaVersion": "1.0",
        "gate": "G9A-R4",
        "findingId": "G9A-E2E-P1-001",
        "findingState": "OPEN",
        "status": "PASS",
        "baselineCommit": BASELINE,
        "expectedBranch": BRANCH,
        "ownerCount": len(OWNERS),
        "faultSeedCount": 12,
        "newRequirementIds": 0,
        "publishedMigrationChanges": 0,
        "providerNetworkCalls": 0,
        "realDeviceOrPeripheralCommands": 0,
        "externalExecution": 0,
    }
    target = args.output if args.output.is_absolute() else ROOT / args.output
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_text(json.dumps(payload, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print("T2-G9A-R4 GOVERNANCE OK: finding OPEN owners=22 faults=12 external=0")


if __name__ == "__main__":
    main()
