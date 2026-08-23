#!/usr/bin/env python3
"""Gate 8A-Prep 商业 SaaS 运营 CR、契约、范围和零运行时门禁。"""
from __future__ import annotations

import argparse
import csv
import json
import pathlib
import re
import subprocess


ROOT = pathlib.Path(__file__).resolve().parents[1]
BASELINE = "b47533eba707d486abe44dbf70ec7b651081b3af"
BRANCH = "t2/gate8a-prep-commercial-operations"
GATE = "T2-GATE8A-PREP-COMMERCIAL-SAAS-OPERATIONS"
CONTRACT_DIR = ROOT / "contracts/t2/gate8a-prep"
RTM = ROOT / "docs/governance/rtm.csv"
PREP_REQUIREMENTS = {"T2-SAA-001": "DRAFT", "T2-SUB-001": "DRAFT", "T2-SVC-001": "DRAFT"}
PRESERVED = {
    "T2-PAY-002": "BLOCKED", "T2-HWD-001": "BLOCKED", "T2-PRN-001": "BLOCKED",
    "T2-PAR-001": "BLOCKED", "T2-UAT-001": "DRAFT", "T2-REL-001": "DRAFT",
    "T2-LIC-001": "DEFERRED", "T2-JSH-001": "DEFERRED",
}


def require(condition: bool, message: str) -> None:
    if not condition:
        raise SystemExit(f"T2-GATE8A-PREP ERROR: {message}")


def load(name: str) -> dict:
    path = CONTRACT_DIR / name
    try:
        return json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as exception:
        raise SystemExit(f"T2-GATE8A-PREP ERROR: invalid {path.relative_to(ROOT)}: {exception}")


def git(*args: str) -> str:
    completed = subprocess.run(
        ["git", "-c", "core.quotepath=false", *args], cwd=ROOT, check=False,
        capture_output=True, text=True, encoding="utf-8",
    )
    require(completed.returncode == 0, f"git {' '.join(args)} failed: {completed.stderr.strip()}")
    return completed.stdout.strip()


def load_rtm() -> dict[str, dict[str, str]]:
    with RTM.open(encoding="utf-8-sig", newline="") as handle:
        return {row["requirement_id"]: row for row in csv.DictReader(handle)}


def validate_admission(rows: dict[str, dict[str, str]]) -> dict:
    admission = load("gate8a-prep-admission.json")
    require(admission["phase"] == GATE, "阶段身份漂移")
    require(admission["baselineCommit"] == BASELINE and admission["branch"] == BRANCH,
            "基线或准备分支漂移")
    require(admission["evidenceLevel"] == "STATIC_DESIGN_AND_CONTRACT_PREP", "证据等级越界")
    require(list(admission["requirements"]) == ["T2-SAA-001", "T2-SUB-001", "T2-SVC-001"],
            "Requirement 顺序漂移")
    require(all(value["status"] == "DRAFT" for value in admission["requirements"].values()),
            "准备需求被提前提升")
    require(admission["strictSequence"] == ["T2-SAA-001", "T2-SUB-001", "T2-SVC-001"],
            "SAA→SUB→SVC 串行依赖漂移")
    require(all(value is False for value in admission["runtimeFlags"].values()), "出现运行时准入标志")
    require(admission["modulePlan"] == {
        "T2-SAA-001": "jshpos-saas", "T2-SUB-001": "jshpos-subscription",
        "T2-SVC-001": "jshpos-service"}, "Owner 模块计划漂移")
    require(admission["preservedStates"] == PRESERVED, "外部/验收保留状态漂移")
    for requirement, state in {**PREP_REQUIREMENTS, **PRESERVED}.items():
        require(rows[requirement]["status"] == state, f"RTM 状态漂移: {requirement}")
    require(all(value == 0 for value in admission["externalExecution"].values()), "外部执行必须为0")
    return admission


def validate_domain_contracts() -> tuple[dict, dict, dict, dict]:
    state = load("state-machines-and-invariants.json")
    require(state["status"] == "DRAFT_NON_EXECUTABLE", "状态机被标记为可执行")
    subscription_states = ["DRAFT", "PENDING_ACTIVATION", "ACTIVE", "GRACE_PERIOD", "SUSPENDED",
                           "EXPIRED", "TERMINATION_PENDING", "TERMINATED", "RESTORED"]
    require(state["stateMachines"]["subscription"]["states"] == subscription_states,
            "订阅状态机不完整")
    require(state["stateMachines"]["tenantLifecycle"]["physicalDeletionAllowed"] is False,
            "租户生命周期错误允许物理删除")
    require(state["stateMachines"]["subscription"]["fundsEffectAllowed"] is False,
            "订阅错误允许资金效果")
    invariants = set(state["invariants"])
    required_invariants = {
        "TENANT_ID_SERVER_ALLOCATED_AND_TRUSTED", "CLIENT_TENANT_ID_NEVER_AUTHORITY",
        "NO_CROSS_OWNER_MAPPER", "NO_DIRECT_RUOYI_SYSTEM_TABLE_WRITE",
        "BUSINESS_FACTS_NEVER_PHYSICALLY_DELETED_BY_SUSPENSION_OR_EXPIRY",
        "CONTROLLED_RECOVERY_CAPABILITIES_ALWAYS_PRESERVED", "SUBSCRIPTION_HAS_NO_FUNDS_EFFECT",
        "FRONTEND_VISIBILITY_NEVER_REPLACES_SERVER_AUTHORIZATION",
        "SERVICE_WORK_ORDER_NEVER_GRANTS_DEVICE_PARTNER_OR_PRODUCTION_AUTHORIZATION",
        "INTERNAL_TARGET_NEVER_COMMERCIAL_SLA",
    }
    require(required_invariants <= invariants, "关键不变量不完整")
    recovery = set(state["controlledRecoveryCapabilities"])
    require({"REFUND", "RECONCILIATION", "AUDIT", "BACKUP_RESTORE", "LEGAL_EXPORT",
             "DATA_MIGRATION", "DATA_DELETION_REQUEST"} <= recovery, "受控恢复能力不完整")

    errors = load("error-codes-draft.json")
    codes = [item["code"] for item in errors["errors"]]
    require(errors["status"] == "DRAFT_NON_EXECUTABLE" and len(codes) >= 19
            and len(codes) == len(set(codes)), "错误码草案不完整或重复")

    events = load("commercial-operations-events-draft.schema.json")
    require(events["xContractStatus"] == "DRAFT_NON_EXECUTABLE", "事件契约被标记可执行")
    event_types = events["properties"]["eventType"]["enum"]
    contexts = events["properties"]["authorityContext"]["properties"]["contextKind"]["enum"]
    require(len(event_types) >= 8 and set(contexts) == {"PRE_TENANT_APPLICATION", "TRUSTED_TENANT"},
            "事件类型或权威上下文不完整")
    require("payloadSha256" in events["required"] and "aggregateVersion" in events["required"],
            "事件摘要或聚合版本缺失")

    ui = load("ui-journeys-draft.json")
    require(ui["status"] == "DRAFT_NON_EXECUTABLE" and len(ui["vueJourneys"]) >= 8
            and len(ui["flutterContractOnly"]) == 4, "Vue/Flutter 旅程不完整")
    require({"CALCULATE_ENTITLEMENT", "CALCULATE_SUBSCRIPTION_STATE", "TRUST_CLIENT_TENANT_ID",
             "MENU_ONLY_AUTHORIZATION"} <= set(ui["forbiddenFrontendBehaviors"]), "前端禁止边界不完整")
    return state, errors, events, ui


def validate_api_and_persistence() -> tuple[int, dict[str, int]]:
    api_path = CONTRACT_DIR / "openapi-commercial-operations-draft.yaml"
    api = api_path.read_text(encoding="utf-8")
    for marker in ("openapi: 3.1.0", "DRAFT_NON_EXECUTABLE", "x-idempotency-required",
                   "x-server-allocated-tenant-id: true", "If-Match-Version"):
        require(marker in api, f"OpenAPI 缺少 {marker}")
    require("tenantId:" not in api and "tenant_id:" not in api, "OpenAPI 请求暴露 tenant_id 字段")
    operation_ids = re.findall(r"^\s+operationId:\s*(\S+)", api, flags=re.MULTILINE)
    require(len(operation_ids) >= 12 and len(operation_ids) == len(set(operation_ids)),
            "OpenAPI 操作数量不足或重复")

    registry = CONTRACT_DIR / "persistence-design-registry.csv"
    with registry.open(encoding="utf-8-sig", newline="") as handle:
        rows = list(csv.DictReader(handle))
    require(len(rows) >= 19, "持久化设计登记不足")
    counts = {requirement: 0 for requirement in PREP_REQUIREMENTS}
    strategies = {"CRUD_ENTITY", "CONTROLLED_WRITE", "APPEND_ONLY", "READ_PROJECTION"}
    sql_modes = {"MP", "XML", "HYBRID"}
    for row in rows:
        require(row["requirement_id"] in PREP_REQUIREMENTS, "持久化登记出现越界 Requirement")
        counts[row["requirement_id"]] += 1
        require(row["data_access_strategy"] in strategies and row["sql_mode"] in sql_modes,
                f"访问策略或 SQL 模式无效: {row['planned_table']}")
        require(row["tenant_rule"] and "COMMENT" in row["comment_requirement"]
                and "设计" in row["migration_note"], f"持久化边界不完整: {row['planned_table']}")
    require(all(value >= 4 for value in counts.values()), "某个 Requirement 的持久化设计不足")
    return len(operation_ids), counts


def validate_vectors_and_docs() -> tuple[int, dict[str, int]]:
    vectors = load("test-vectors.json")
    require(vectors["runtimeExecutable"] is False and vectors["requirements"] == PREP_REQUIREMENTS,
            "测试向量状态漂移")
    items = vectors["vectors"]
    ids = [item["id"] for item in items]
    require(len(items) >= 40 and len(ids) == len(set(ids)), "测试向量不足或 ID 重复")
    counts = {requirement: 0 for requirement in PREP_REQUIREMENTS}
    for item in items:
        require(item["requirementId"] in counts and item["scenario"] and item["expected"],
                "测试向量字段不完整")
        counts[item["requirementId"]] += 1
    require(all(value >= 12 for value in counts.values()), "每项 Requirement 测试覆盖不足")

    required_docs = [
        "docs/t2-gate8a-prep/README.md",
        "docs/t2-gate8a-prep/01_商业SaaS运营领域边界与串行依赖.md",
        "docs/t2-gate8a-prep/02_T2_SAA001独立CR与正式开发启动评审报告.md",
        "docs/t2-gate8a-prep/03_T2_SUB001独立CR与依赖影响报告.md",
        "docs/t2-gate8a-prep/04_T2_SVC001独立CR与依赖影响报告.md",
        "docs/t2-gate8a-prep/05_API事件错误码与迁移设计.md",
        "docs/t2-gate8a-prep/06_Vue与Flutter旅程及权限边界.md",
        "docs/t2-gate8a-prep/07_测试矩阵CI与量化验收.md",
        "docs/t2-gate8a-prep/08_T2_Gate8A_Prep启动评审报告.md",
        "docs/t2-gate8a-prep/09_T2_SAA001第一批正式开发操作指令.md",
        "docs/t2-gate8a-prep/10_Gate8A_Prep证据索引.md",
        "docs/adr/ADR-056-gate8a-commercial-saas-operations-prep.md",
        "docs/governance/CR-T2G8A-001_saa-onboarding-entitlement.md",
        "docs/governance/CR-T2G8A-002_subscription-lifecycle.md",
        "docs/governance/CR-T2G8A-003_service-work-order.md",
    ]
    require(all((ROOT / item).is_file() for item in required_docs), "Gate 8A-Prep 文档集不完整")
    return len(items), counts


def validate_scope_and_secrets() -> list[str]:
    require(git("merge-base", "--is-ancestor", BASELINE, "HEAD") == "", "Gate 7F-Prep 基线不是祖先")
    current_branch = git("branch", "--show-current")
    require(current_branch == BRANCH or current_branch == "", f"当前分支错误: {current_branch}")
    changed = sorted(set(filter(None, git("diff", "--name-only", BASELINE).splitlines()
                                    + git("ls-files", "--others", "--exclude-standard").splitlines())))
    allowed_exact = {
        "AGENTS.md", ".github/workflows/t2-gate8a-prep.yml", "docs/adr/README.md",
        "docs/governance/change-log.md", "docs/governance/rtm.csv",
        "scripts/check_t2_gate8a_prep.py", "scripts/build_t2_gate8a_prep_evidence.py",
    }
    prefixes = ("contracts/t2/gate8a-prep/", "docs/t2-gate8a-prep/",
                "docs/adr/ADR-056-", "docs/governance/CR-T2G8A-")
    illegal = [item for item in changed if item not in allowed_exact and not item.startswith(prefixes)]
    require(not illegal, f"出现越界文件: {illegal}")
    runtime_roots = ("server/", "admin-web/", "pos-flutter/", "packages/", "infrastructure/", "infra/")
    require(not [item for item in changed if item.startswith(runtime_roots)], "运行时目录发生变化")
    migration_pattern = re.compile(r"(^|/)(db/migration|migrations?)(/|$)", re.I)
    require(not [item for item in changed if migration_pattern.search(item)], "数据库迁移文件发生变化")
    dependency_names = {"pom.xml", "package.json", "pnpm-lock.yaml", "pubspec.yaml", "pubspec.lock",
                        "build.gradle", "build.gradle.kts", "settings.gradle", "settings.gradle.kts"}
    require(not [item for item in changed if pathlib.PurePosixPath(item).name in dependency_names],
            "依赖清单发生变化")
    sensitive_name = re.compile(r"(^|/)(\.env($|\.)|.*\.(pem|p12|pfx|jks|keystore|key)$)", re.I)
    require(not [item for item in changed if sensitive_name.search(item)], "发现敏感文件名")
    secret_patterns = [
        re.compile(r"-----BEGIN [A-Z ]*PRIVATE KEY-----"), re.compile(r"AKIA[0-9A-Z]{16}"),
        re.compile(r"(?i)(password|secret|token)\s*[:=]\s*['\"][^'\"]{8,}['\"]"),
    ]
    for item in changed:
        path = ROOT / item
        if path.is_file():
            content = path.read_text(encoding="utf-8", errors="ignore")
            require(not any(pattern.search(content) for pattern in secret_patterns), f"疑似 Secret: {item}")
    return changed


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--output", type=pathlib.Path)
    args = parser.parse_args()
    rows = load_rtm()
    admission = validate_admission(rows)
    state, errors, events, ui = validate_domain_contracts()
    operations, table_counts = validate_api_and_persistence()
    vector_count, vector_counts = validate_vectors_and_docs()
    changed = validate_scope_and_secrets()
    result = {
        "gate": GATE, "status": "PASS", "evidenceLevel": admission["evidenceLevel"],
        "requirements": PREP_REQUIREMENTS, "strictSequence": admission["strictSequence"],
        "stateMachines": len(state["stateMachines"]), "errorCodes": len(errors["errors"]),
        "eventTypes": len(events["properties"]["eventType"]["enum"]),
        "vueJourneys": len(ui["vueJourneys"]), "flutterContractOnly": len(ui["flutterContractOnly"]),
        "openApiOperations": operations, "plannedTables": table_counts,
        "testVectors": vector_count, "testVectorsByRequirement": vector_counts,
        "runtimeFlags": admission["runtimeFlags"], "preservedStates": admission["preservedStates"],
        "externalExecution": admission["externalExecution"], "changedFiles": len(changed),
        "decision": "PREPARED_CONDITIONAL_PASS_AWAITING_SPONSOR_NO_RUNTIME_ADMISSION",
    }
    if args.output:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(result, ensure_ascii=False))


if __name__ == "__main__":
    main()
