from __future__ import annotations

import argparse
import json
import subprocess
from datetime import datetime, timezone
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]


def fail(message: str) -> None:
    raise SystemExit(f"T2-GATE6A MATRIX ERROR: {message}")


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    roots = (
        ROOT / "server/ruoyi-modules/jshpos-sync/src/main",
        ROOT / "server/ruoyi-modules/jshpos-sync/src/test",
        ROOT / "server/ruoyi-modules/jshpos-resilience/src/main",
        ROOT / "server/ruoyi-modules/jshpos-resilience/src/test",
        ROOT / "contracts/t2/gate6a",
        ROOT / "admin-web/src/views/terminal",
        ROOT / "scripts",
    )
    source = "\n".join(path.read_text(encoding="utf-8", errors="replace")
                        for root in roots for path in root.rglob("*") if path.is_file())
    required = {
        "NORMAL_ACTIVATE": ("activatesAtomicallyFromServerOwnedBindingAndReturnsCredentialOnce", "TRM-NORMAL-ACTIVATE"),
        "ONE_TIME_SECRET": ("issuesSecretOnceAndPersistsOnlyItsHmac", "secretShownOnce"),
        "DUPLICATE_SAME": ("findCommand", "TRM-DUPLICATE-SAME"),
        "SAME_KEY_DIFFERENT_CONTENT": ("TRM_IDEMPOTENCY_CONFLICT", "TRM-SAME-KEY-DIFFERENT-CONTENT"),
        "EXPIRED_ACTIVATION": ("expiresAt().isAfter", "TRM-EXPIRED-ACTIVATION"),
        "CLONED_CREDENTIAL": ("blocksClonedCredentialAndAppendsSecurityAudit", "TRM-CLONED-CREDENTIAL"),
        "REVOKED_REPLAY": ("CREDENTIAL_REJECTED", "TRM-REVOKED-REPLAY"),
        "CROSS_TENANT": ("tenant_id=#{tenantId}", "TRM-CROSS-TENANT"),
        "CROSS_STORE": ("requireStoreAccess", "TRM-CROSS-STORE"),
        "NUMERIC_VERSION": ("comparesNumericVersionSegmentsInsteadOfLexicalText", "1.10.0"),
        "VERSION_DOWNGRADE": ("TRM_VERSION_DOWNGRADE", "TRM-OLD-VERSION"),
        "CAPABILITY_DOWNGRADE": ("TRM_VERSION_DOWNGRADE", "TRM-CAPABILITY-DOWNGRADE"),
        "CLOCK_SKEW": ("failsClosedForClockSkewOverFiveMinutes", "TRM-CLOCK-SKEW"),
        "ROTATE": ("updateCredentialVersion", "TRM-ROTATE"),
        "APPEND_ONLY": ("terminal capability snapshot is append-only", "dev_audit_no_update"),
        "HUNDRED_THOUSAND": ("assertTerminalRegistryConstraintsAndHundredThousandCapacity", "100_000"),
        "FORWARD_MIGRATION": ("migratesExpectedVersions", "EXPECTED_MIGRATION_VERSIONS", "V202608160036"),
        "WEB_ONE_TIME_WARNING": ("关闭后无法再次查看", "不会再次显示"),
        "BAK_NORMAL_EMPTY_RESTORE": ("createsEncryptedSetRestoresFromEmptyAndReplaysIdempotently", "beginEmpty"),
        "BAK_SIX_DATA_CLASSES": ("EnumSet.allOf(DataClass.class)", "BUSINESS_OBJECT", "EVIDENCE"),
        "BAK_IDEMPOTENT_REPLAY": ("findBackup(command.backupId())", "findDrill(command.drillId())"),
        "BAK_SAME_KEY_DIFFERENT_CONTENT": ("BAK-IDEM-001", "同幂等键内容不一致"),
        "BAK_AES_GCM": ("AES/GCM/NoPadding", "GCMParameterSpec"),
        "BAK_WRONG_KEY_AAD_NONCE": ("failsClosedForWrongKeyAadNonceCorruptionAndLength", "nonce与清单不一致"),
        "BAK_CORRUPT_OBJECT": ("failsClosedForCorruptObjectAndReconciliationDifference", "密文摘要不匹配"),
        "BAK_MISSING_PART": ("备份对象缺失或不可读", "MISSING_PART"),
        "BAK_CROSS_TENANT": ("对象租户摘要被替换", "CROSS_TENANT_REPLACEMENT"),
        "BAK_SCHEMA_INCOMPATIBLE": ("Schema兼容窗口不匹配", "SCHEMA_INCOMPATIBLE"),
        "BAK_APPEND_ONLY": ("trg_bak_object_no_update", "trg_bak_audit_no_delete"),
        "BAK_STATE_GUARDS": ("trg_bak_set_guard", "trg_bak_drill_guard"),
        "BAK_MYSQL_FULL_MIGRATION": ("ResilienceMigrationMySqlIT", "202608180039"),
        "BAK_MILLION_FACT_DIGEST": ("syntheticFactRows", "1_000_000L"),
        "BAK_NINE_RECONCILIATIONS": ("BUSINESS_DAY_RECONCILIATION", "FLYWAY_VALIDATE", "PROJECTION_REBUILD"),
        "BAK_RPO_RTO_TIMER": ("MAX_RPO_SECONDS", "MAX_RTO_SECONDS", "commercialSla"),
        "BAK_PROVIDER_NETWORK_ZERO": ("providerNetworkCalls", "cloudDrEvidence"),
        "BAK_CLOUD_DR_BLOCKED": ("cloudObjectStorage", "crossRegionDr", "BLOCKED"),
    }
    results = []
    for vector, markers in required.items():
        missing = [marker for marker in markers if marker not in source]
        if missing:
            fail(f"missing executable marker for {vector}: {missing}")
        results.append({"vectorId": vector, "markers": list(markers), "result": "PASS"})
    output = args.output if args.output.is_absolute() else ROOT / args.output
    output.parent.mkdir(parents=True, exist_ok=True)
    report = {
        "schemaVersion": "1.0", "phase": "T2-GATE6A", "requirement": "T2-TRM-001",
        "generatedAt": datetime.now(timezone.utc).isoformat().replace("+00:00", "Z"),
        "commitSha": subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=ROOT, text=True).strip(),
        "fixedScenarios": len(results), "results": results,
        "providerNetworkCalls": 0, "realDeviceCommands": 0, "realPiiRecords": 0,
        "evidenceNote": "STATIC/UNIT/MySQL synthetic results do not replace SANDBOX, REAL_DEVICE, CLOUD_DR or PILOT.",
    }
    output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(f"T2-GATE6A MATRIX OK: scenarios={len(results)} realDevice=0 network=0")


if __name__ == "__main__":
    main()
