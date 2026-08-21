#!/usr/bin/env python3
"""T2 Gate 7B S20-B：T2-EXG-001 独立运行时、边界与证据门禁。"""
from __future__ import annotations

import argparse
import csv
import json
import pathlib
import re
import subprocess


ROOT = pathlib.Path(__file__).resolve().parents[1]
BASELINE = "4d184a1cf5998c91d9cc9db359a4d5ed0d16e0c3"
BRANCH = "t2/gate7b-sprint20b-exg-runtime"
GATE = "T2-GATE7B-SPRINT-S20B-EXG001-RUNTIME"
PRESERVED = {
    "T2-PAY-004": "DRAFT", "T2-PAY-002": "BLOCKED", "T2-HWD-001": "BLOCKED",
    "T2-PRN-001": "BLOCKED", "T2-PAR-001": "BLOCKED", "T2-UAT-001": "DRAFT",
    "T2-REL-001": "DRAFT", "T2-JSH-001": "DEFERRED", "T2-LIC-001": "DEFERRED",
}
ZERO_FIELDS = (
    "providerNetworkCalls", "realFundsTransactions", "realDeviceCommands", "realPeripheralCommands",
    "partnerContacts", "onsitePilots", "fullAlphaRuns", "productionDeployments",
)


def fail(condition: bool, message: str) -> None:
    if not condition:
        raise SystemExit(f"T2-GATE7B-EXG ERROR: {message}")


def git(*args: str) -> str:
    result = subprocess.run(
        ["git", "-c", "core.quotepath=false", *args], cwd=ROOT, check=False,
        capture_output=True, text=True, encoding="utf-8",
    )
    fail(result.returncode == 0, result.stderr.strip() or f"git {' '.join(args)} failed")
    return result.stdout.strip()


def read(path: str) -> str:
    target = ROOT / path
    fail(target.is_file(), f"缺少 {path}")
    return target.read_text(encoding="utf-8")


def read_json(path: str) -> dict:
    try:
        return json.loads(read(path))
    except json.JSONDecodeError as exception:
        raise SystemExit(f"T2-GATE7B-EXG ERROR: {path} JSON 无效: {exception}")


def rtm() -> dict[str, dict[str, str]]:
    with (ROOT / "docs/governance/rtm.csv").open(encoding="utf-8", newline="") as stream:
        rows = list(csv.DictReader(stream))
    identifiers = [row["requirement_id"] for row in rows]
    fail(len(identifiers) == len(set(identifiers)), "RTM Requirement ID 重复")
    return {row["requirement_id"]: row for row in rows}


def validate() -> dict:
    rows = rtm()
    contract = read_json("contracts/t2/gate7b-exg/exg-admission.json")
    fail(contract.get("gate") == GATE, "gate identity 漂移")
    fail(contract.get("baselineCommit") == BASELINE and contract.get("branch") == BRANCH,
         "基线或分支契约漂移")
    status = rows.get("T2-EXG-001", {}).get("status")
    fail(status in {"IN_PROGRESS", "VERIFIED"}, f"EXG001 状态未准入或越界: {status}")
    fail(contract.get("requirement", {}).get("status") == status, "RTM 与准入契约状态不一致")
    fail(contract.get("requirement", {}).get("implementation") ==
         "ORIGINAL_RETURN_REFUND_PLUS_NEW_SALE_PLUS_APPEND_ONLY_LINK", "换货实现语义漂移")
    for requirement_id, expected in PRESERVED.items():
        fail(rows.get(requirement_id, {}).get("status") == expected,
             f"保留状态漂移: {requirement_id}")
        fail(contract.get("preservedStates", {}).get(requirement_id) == expected,
             f"准入契约保留状态漂移: {requirement_id}")
    external = contract.get("externalExecution", {})
    for field in ZERO_FIELDS:
        fail(external.get(field) == 0, f"外部执行必须为零: {field}")
    fail(external.get("commercialClaimAllowed") is False, "不得形成商业可用声明")
    fail(contract.get("nextRequirement", {}).get("id") == "T2-PAY-004"
         and contract["nextRequirement"].get("status") == "DRAFT"
         and contract["nextRequirement"].get("automaticStartAllowed") is False,
         "PAY004 串行准入边界漂移")
    fail(git("merge-base", "--is-ancestor", BASELINE, "HEAD") == "", "S20-B 准备封存提交不是祖先")

    required = [
        "contracts/t2/gate7b-exg/openapi-exchange-v1.yaml",
        "contracts/t2/gate7b-exg/exchange-events-v1.schema.json",
        "contracts/t2/gate7b-exg/test-vectors/exg-fault-vectors.json",
        "docs/t2-gate7b-exg/01_T2_EXG001正式实现准入与切面.md",
        "server/ruoyi-modules/jshpos-returns/src/main/java/com/jingshanghui/pos/returns/application/service/ExchangeOrchestrationService.java",
        "server/ruoyi-modules/jshpos-returns/src/main/java/com/jingshanghui/pos/returns/application/service/ExchangeSagaCoordinator.java",
        "server/ruoyi-modules/jshpos-returns/src/main/resources/mapper/returns/ExchangeMapper.xml",
        "server/ruoyi-modules/jshpos-returns/src/main/resources/db/migration/V202608220055__gate7b_exchange_orchestration.sql",
        "server/ruoyi-modules/jshpos-returns/src/main/resources/db/migration/V202608220056__gate7b_exchange_permissions.sql",
        "pos-flutter/lib/infrastructure/local_database/gate7b_exchange_schema.dart",
        "pos-flutter/lib/features/exchange/infrastructure/http_pos_exchange_application_service.dart",
        "pos-flutter/lib/features/exchange/presentation/pos_exchange_page.dart",
        "pos-flutter/test/gate7b/exchange_runtime_test.dart",
    ]
    for path in required:
        read(path)

    openapi = read(required[0])
    for token in ("openapi: 3.1.0", "T2-EXG-001", "x-provider-network-calls: 0",
                  "/pos/exchanges/{exchangeId}/observe", "pos:exchange:recover"):
        fail(token in openapi, f"正式 OpenAPI 缺少 {token}")
    events = read_json(required[1])
    fail(events.get("additionalProperties") is False, "事件契约必须拒绝未知字段")
    fail({"tenantId", "payloadSha256", "aggregateVersion"}.issubset(events.get("required", [])),
         "事件租户、摘要或版本信封不完整")
    vectors = read_json(required[2])
    vector_items = vectors.get("vectors", [])
    fail(len(vector_items) >= 20 and len({item.get("id") for item in vector_items}) == len(vector_items),
         "固定故障向量不足或 ID 重复")
    for field in ZERO_FIELDS:
        fail(vectors.get("externalExecution", {}).get(field) == 0, f"向量外部执行漂移: {field}")

    java = read(required[4]) + read(required[5])
    for token in ("ReturnOrchestrationService", "ExchangeOrderSnapshotPort", "acceptReturn",
                  "acceptSale", "MANUAL_RECOVERY_REQUIRED", "processNext"):
        fail(token in java, f"可恢复 Saga 缺少 {token}")
    fail("HttpClient" not in java and "RestTemplate" not in java and "WebClient" not in java,
         "服务端换货运行时不得引入 Provider/外部网络客户端")
    mapper = read(required[6]).lower()
    fail("tenant_id=#{tenantid}" in re.sub(r"\s+", "", mapper), "Exchange Mapper 缺少显式可信租户谓词")
    fail(not re.search(r"\b(update|delete)\s+(ord_|pay_|inv_|prm_)", mapper),
         "Exchange Mapper 禁止跨 Owner 修改")

    mysql = read(required[7]).lower()
    for token in ("create table ret_exchange", "create table ret_exchange_leg",
                  "create table ret_exchange_event", "append-only", "cannot be deleted"):
        fail(token in mysql, f"MySQL 前向迁移缺少 {token}")
    fail("create table pay_tender" not in mysql, "EXG Sprint 禁止实现 PAY004 持久化")
    sqlite = read(required[9])
    for token in ("static const int version = 12", "local_exchange_command",
                  "local_exchange_event", "append-only", "cannot be deleted"):
        fail(token in sqlite, f"SQLite 前向迁移缺少 {token}")
    flutter = read(required[10]) + read(required[11])
    for token in ("EXCHANGE_RESULT_UNKNOWN", "refresh", "originalReturn", "newSale",
                  "展示差额", "只查询原换货"):
        fail(token in flutter, f"Flutter 换货恢复或展示边界缺少 {token}")
    fail("MethodChannel" not in flutter
         and "/providers/" not in flutter.lower()
         and "payment/provider" not in flutter.lower(),
         "Flutter 换货功能不得调用设备或支付 Provider")

    changed = sorted(filter(None, git("diff", "--name-only", BASELINE, "HEAD").splitlines()))
    runtime_changed = [path for path in changed if path.startswith(("server/", "pos-flutter/", "admin-web/"))]
    forbidden_runtime = [path for path in runtime_changed if any(
        token in path.lower() for token in ("tender_plan", "mixed_tender", "pay004", "provider")
    )]
    fail(not forbidden_runtime, f"PAY004/Provider 运行时提前实现: {forbidden_runtime}")
    sensitive = re.compile(r"(^|/)(\.env($|\.)|.*\.(pem|p12|pfx|jks|keystore|key)$)", re.I)
    fail(not [path for path in changed if sensitive.search(path)], "检测到敏感文件名")
    return {
        "schemaVersion": "1.0", "gate": GATE, "status": "PASS",
        "evidenceLevel": "INTERNAL_SOFTWARE_EXECUTION", "baselineCommit": BASELINE,
        "requirementStatus": status, "preservedStates": PRESERVED,
        "externalExecution": external, "faultVectorCount": len(vector_items),
        "changedFileCount": len(changed), "runtimeChangedFileCount": len(runtime_changed),
        "decision": "EXG001_RUNTIME_GREEN_AWAITING_SPONSOR_ACCEPTANCE" if status == "VERIFIED"
                    else "EXG001_RUNTIME_IN_PROGRESS",
    }


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--output")
    args = parser.parse_args()
    result = validate()
    if args.output:
        target = ROOT / args.output
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(result, ensure_ascii=False))


if __name__ == "__main__":
    main()
