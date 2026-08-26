#!/usr/bin/env python3
"""校验 G9A-R4 范围、正式栈测试边界与外部零执行。"""
from __future__ import annotations

import argparse
import json
import pathlib
import re
import subprocess
import sys


ROOT = pathlib.Path(__file__).resolve().parents[1]
BASELINE = "059f47ebd6877b683345d1e6f7c0cd9a18d712b5"
BRANCH = "t2/gate9b-sprint27i-g9a-r4-runtime"
APPROVED_FORWARD_MIGRATION = (
    "server/ruoyi-modules/jshpos-transfer/src/main/resources/db/migration/"
    "V202608260087__g9a_r4_transfer_outbox_version_constraint.sql"
)
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
    postflight = ROOT / "scripts/run_t2_g9a_r4_postflight.py"
    fault_runner = ROOT / "scripts/run_t2_g9a_r4_faults.py"
    flutter = ROOT / "pos-flutter/test/gate9b/g9a_r4_formal_jar_e2e_test.dart"

    require(admission.get("findingId") == "G9A-E2E-P1-001", "finding identity drift")
    require(admission.get("findingState") == "OPEN", "finding must remain OPEN before sponsor confirmation")
    require(admission.get("newRequirementIds") == [], "new Requirement ID is forbidden")
    external = admission.get("externalExecution", {})
    require(isinstance(external, dict) and external and all(value == 0 for value in external.values()),
            "external execution must remain zero")
    require({item.lower() for item in checkpoints.get("owners", [])} == OWNERS, "22 Owner set drift")
    require(len(faults.get("seeds", [])) == 12, "twelve fixed fault seeds are required")
    require(red.is_file() and bootstrap.is_file() and postflight.is_file()
            and fault_runner.is_file() and flutter.is_file(),
            "R0/bootstrap/postflight/fault/Flutter harness missing")

    postflight_text = postflight.read_text(encoding="utf-8")
    require("/api/v1/" in postflight_text, "postflight must use formal HTTP APIs")
    require(not re.search(r"(?im)^\s*(?:import|from)\s+(?:sqlite3|pymysql|mysql|redis)\b", postflight_text),
            "postflight must not connect directly to MySQL, Redis or SQLite")
    require("ownerCheckpointCount\": len(checkpoints)" in postflight_text,
            "postflight must emit 22 Owner checkpoint evidence")
    require("conservationCheckCount\": len(invariants)" in postflight_text,
            "postflight must emit twelve conservation checks per journey")
    require("--backup-key-version" in postflight_text
            and "SYNTHETIC_RESTORE" in postflight_text
            and "EXTERNAL_ONBOARDING_CHECKS" in postflight_text,
            "postflight must prove synthetic restore while all external onboarding P0 stay blocked")
    require("onboarding-catalog-package" in postflight_text
            and "onboarding-inventory-policy" in postflight_text
            and 'context["onboardingTargetStoreId"]' in postflight_text,
            "postflight must provision target-store Catalog and Inventory readiness through formal APIs")
    require("def canonical_occurred_at()" in postflight_text
            and "replace(microsecond=0)" in postflight_text
            and "occurred_at = canonical_occurred_at()" in postflight_text,
            "report source timestamps must round-trip through Java Instant without hash drift")
    scripts_path = str(ROOT / "scripts")
    if scripts_path not in sys.path:
        sys.path.insert(0, scripts_path)
    from run_t2_g9a_r4_postflight import (
        canonical_decimal_text,
        synthetic_release_headers,
        synthetic_release_compatibility,
        synthetic_release_version,
    )
    require(canonical_decimal_text("0", "regression") == "0.000000",
            "report inventory payload and hash must use DECIMAL(25,6) canonical text")
    release_version = synthetic_release_version("r4-community", "journey")
    require(re.fullmatch(r"[0-9]+(?:\.[0-9]+){0,3}(?:[-+][A-Za-z0-9.-]+)?", release_version) is not None,
            "formal release fixture must satisfy the accepted Release Owner version contract")
    require(release_version == synthetic_release_version("r4-community", "journey"),
            "formal release fixture version must be deterministic for original-command recovery")
    compatibility = synthetic_release_compatibility()
    for field in ("minAppVersion", "maxAppVersion", "minProtocolVersion", "maxProtocolVersion",
                  "minSchemaVersion", "maxSchemaVersion", "minSystemVersion", "maxSystemVersion"):
        require(re.fullmatch(r"[0-9]+(?:\.[0-9]+){0,3}(?:[-+][A-Za-z0-9.-]+)?",
                             str(compatibility.get(field, ""))) is not None,
                f"formal release compatibility fixture must satisfy Release Owner contract: {field}")
    release_headers = synthetic_release_headers("r4-release-key", "01ARZ3NDEKTSV4RRFFQ69G5FAV")
    require(release_headers == {
        "X-Idempotency-Key": "r4-release-key",
        "X-Correlation-ID": "01ARZ3NDEKTSV4RRFFQ69G5FAV",
    }, "formal release mutations must provide the stable ULID correlation required by persisted evidence")
    fault_text = fault_runner.read_text(encoding="utf-8")
    require("R4-F01" in fault_text and "R4-F12" in fault_text,
            "R4-R5 runner must emit the complete fixed seed ledger")
    require('owner="R4FAULT"' not in fault_text and 'owner="ORDER"' in fault_text
            and ':R4-F09' in fault_text,
            "late reporting seed must use an accepted authoritative Owner in an isolated fault partition")
    require("directBusinessDatabaseWrites\": 0" in fault_text,
            "fault runner must declare zero direct business database writes")
    workflow = (ROOT / ".github/workflows/t2-g9a-r4-runtime.yml").read_text(encoding="utf-8")
    require("FLUSHDB" in workflow and "launch_server 2" in workflow,
            "formal job must inject a real Redis loss and JAR restart")
    require(workflow.count("openssl rand -base64 32") >= 3
            and "export JSH_MEMBER_LOOKUP_KEY_B64=" in workflow
            and "export JSH_MEMBER_ENCRYPTION_KEY_B64=" in workflow
            and "export JSH_MEMBER_KEY_VERSION=" in workflow,
            "formal member journey must receive ephemeral external identity keys")
    require("export JSH_RESILIENCE_EVIDENCE_LEVEL=SYNTHETIC_RESTORE" in workflow
            and "export JSH_INTERNAL_SYNTHETIC_RESILIENCE_ACK=INTERNAL_ONLY_NOT_PRODUCTION" in workflow
            and "export JSH_BACKUP_OBJECT_ROOT=" in workflow
            and "export JSH_SYNTHETIC_RESTORE_ROOT=" in workflow
            and "export JSH_BACKUP_KEY_VERSION=" in workflow
            and "export JSH_BACKUP_KEY_B64=" in workflow
            and '--backup-key-version "$JSH_BACKUP_KEY_VERSION"' in workflow,
            "formal JAR must receive an ephemeral, file-isolated synthetic restore configuration")

    resilience_configuration = (ROOT / "server/ruoyi-modules/jshpos-resilience/src/main/java/com/jingshanghui/pos/resilience/config/ResilienceAutoConfiguration.java").read_text(encoding="utf-8")
    require("INTERNAL_ONLY_NOT_PRODUCTION" in resilience_configuration
            and "production" in resilience_configuration
            and "JSH_SYNTHETIC_RESTORE_ROOT" in resilience_configuration,
            "synthetic restore adapter must be double-confirmed, file-isolated and forbidden in production")

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
    inventory_policy = '"/api/v1/inventory/policies"'
    lot_prepare = "prepare_community_lots("
    require(inventory_policy in create_tenant, "formal journey must publish an Inventory Owner policy through API")
    require(create_tenant.index(inventory_policy) < create_tenant.index(lot_prepare),
            "inventory policy must be effective before community procurement receipt")
    require('"effectiveFrom": instant_timestamp(now - timedelta(days=1))' in create_tenant,
            "Inventory Instant must use UTC ISO-8601 instead of the platform LocalDateTime formatter")
    cost_policy = '"/api/inventory/cost-policies"'
    require(cost_policy in create_tenant, "formal journey must publish a Costing Owner policy through API")
    require(create_tenant.index(inventory_policy) < create_tenant.index(cost_policy)
            < create_tenant.index(lot_prepare),
            "inventory and costing policies must precede community procurement receipt")

    flutter_text = flutter.read_text(encoding="utf-8")
    require("HttpServer.bind" not in flutter_text, "formal Flutter journey must not bind an embedded HTTP server")
    require("R4_BASE_URL" in flutter_text, "formal Flutter journey must receive the live JAR URL")
    require("PosLocalDatabase.openPath" not in flutter_text, "test must use production assembler, not direct database writes")
    require("FilePosBusinessRuntimeAssembler" in flutter_text, "file SQLite production assembler is required")
    require("OpenMode.readOnly" in flutter_text, "SQLite evidence must be read-only after the journey")
    require("G9A_R4_JOURNEY_EVIDENCE" in flutter_text,
            "each completed formal journey must emit non-secret diagnostic evidence")
    require("OBSERVED_PENDING_VALIDATION" in flutter_text,
            "partial formal evidence must survive a later aggregate assertion failure")
    require("T2-PAY-002" not in flutter_text, "blocked Provider capability must not be implemented in Flutter test")

    sync_rules = (ROOT / "server/ruoyi-modules/jshpos-sync/src/main/java/com/jingshanghui/pos/sync/domain/SyncRules.java").read_text(encoding="utf-8")
    sync_processor = (ROOT / "server/ruoyi-modules/jshpos-sync/src/main/java/com/jingshanghui/pos/sync/application/service/SyncFactProcessor.java").read_text(encoding="utf-8")
    lot_adapter = ROOT / "server/ruoyi-modules/jshpos-inventory/src/main/java/com/jingshanghui/pos/inventory/infrastructure/integration/PosLotSaleCommandAdapter.java"
    lot_event = "inventory.lot-sale.requested.v1"
    require(lot_event in sync_rules and lot_event in sync_processor,
            "formal community lot-sale Outbox event must be accepted and dispatched by Sync")
    require(lot_adapter.is_file(), "formal lot sale must enter Inventory Owner through a narrow adapter")
    lot_adapter_text = lot_adapter.read_text(encoding="utf-8")
    require("InventoryLedgerService" in lot_adapter_text and "AuthoritativeLotMovementPort" in lot_adapter_text,
            "lot sale adapter must reuse authoritative inventory ledger and server FEFO ports")

    # MyBatis 的 int/long/boolean 别名对应包装类型；record 构造器的 primitive 参数必须使用下划线别名。
    # 正式 MySQL 旅程会真实回读 Owner record，静态阻断可避免单元 Mock 掩盖构造器类型不匹配。
    wrapper_alias = re.compile(r'javaType="(?:int|long|boolean|double|float|short|byte|char)"')
    wrapper_alias_hits = []
    mapper_root = ROOT / "server/ruoyi-modules"
    for mapper in sorted(mapper_root.glob("**/src/main/resources/mapper/**/*.xml")):
        if wrapper_alias.search(mapper.read_text(encoding="utf-8")):
            wrapper_alias_hits.append(mapper.relative_to(ROOT).as_posix())
    require(not wrapper_alias_hits,
            f"MyBatis record primitive fields must use underscore aliases: {wrapper_alias_hits}")

    changed = subprocess.check_output(
        ["git", "diff", "--name-only", BASELINE], cwd=ROOT, text=True,
    ).splitlines()
    migration_status = subprocess.check_output(
        ["git", "diff", "--name-status", BASELINE, "--", "server/ruoyi-modules"],
        cwd=ROOT, text=True,
    ).splitlines()
    migration_status = [item for item in migration_status if "/db/migration/" in item.replace("\\", "/")]
    require(migration_status == [f"A\t{APPROVED_FORWARD_MIGRATION}"],
            f"only the approved V87 addition is allowed: {migration_status}")
    require((ROOT / APPROVED_FORWARD_MIGRATION).is_file(), "approved V87 forward migration is missing")
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
        "approvedForwardMigrationAdditions": 1,
        "providerNetworkCalls": 0,
        "realDeviceOrPeripheralCommands": 0,
        "externalExecution": 0,
        "wrapperPrimitiveAliasViolations": 0,
    }
    target = args.output if args.output.is_absolute() else ROOT / args.output
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_text(json.dumps(payload, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print("T2-G9A-R4 GOVERNANCE OK: finding OPEN owners=22 faults=12 external=0")


if __name__ == "__main__":
    main()
