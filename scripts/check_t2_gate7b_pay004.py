#!/usr/bin/env python3
"""T2 Gate 7B S20-B：PAY-004 Provider 无关组合支付运行时与边界门禁。"""
from __future__ import annotations

import argparse
import csv
import json
import pathlib
import re
import subprocess


ROOT = pathlib.Path(__file__).resolve().parents[1]
BASELINE = "2cc9f88b8ad2d0530a77a9341d396dd1a2fc1e5f"
BRANCH = "t2/gate7b-sprint20b-pay004-runtime"
GATE = "T2-GATE7B-SPRINT-S20B-PAY004-RUNTIME"
PRESERVED = {
    "T2-PAY-002": "BLOCKED", "T2-HWD-001": "BLOCKED", "T2-PRN-001": "BLOCKED",
    "T2-PAR-001": "BLOCKED", "T2-UAT-001": "DRAFT", "T2-REL-001": "DRAFT",
    "T2-JSH-001": "DEFERRED", "T2-LIC-001": "DEFERRED",
}
ZERO_FIELDS = (
    "providerNetworkCalls", "realFundsTransactions", "realDeviceCommands",
    "realPeripheralCommands", "partnerContacts", "fullAlphaRuns", "productionDeployments",
)


def fail(condition: bool, message: str) -> None:
    if not condition:
        raise SystemExit(f"T2-GATE7B-PAY004 ERROR: {message}")


def read(path: str) -> str:
    target = ROOT / path
    fail(target.is_file(), f"缺少 {path}")
    return target.read_text(encoding="utf-8")


def read_json(path: str) -> dict:
    try:
        return json.loads(read(path))
    except json.JSONDecodeError as exception:
        raise SystemExit(f"T2-GATE7B-PAY004 ERROR: {path} JSON 无效: {exception}")


def git(*args: str) -> str:
    result = subprocess.run(
        ["git", "-c", "core.quotepath=false", *args], cwd=ROOT, check=False,
        capture_output=True, text=True, encoding="utf-8",
    )
    fail(result.returncode == 0, result.stderr.strip() or f"git {' '.join(args)} failed")
    return result.stdout.strip()


def rtm() -> dict[str, dict[str, str]]:
    with (ROOT / "docs/governance/rtm.csv").open(encoding="utf-8-sig", newline="") as stream:
        rows = list(csv.DictReader(stream))
    return {row["requirement_id"]: row for row in rows}


def validate() -> dict:
    rows = rtm()
    contract = read_json("contracts/t2/gate7b-pay004/pay004-admission.json")
    status = rows.get("T2-PAY-004", {}).get("status")
    fail(status in {"IN_PROGRESS", "VERIFIED"}, f"PAY004 状态未准入或越界: {status}")
    fail(rows.get("T2-EXG-001", {}).get("status") == "ACCEPTED", "EXG001 必须保持 ACCEPTED")
    fail(contract.get("gate") == GATE and contract.get("baselineCommit") == BASELINE
         and contract.get("branch") == BRANCH, "gate、基线或分支契约漂移")
    fail(contract.get("requirement", {}).get("status") == status, "RTM 与准入契约状态不一致")
    invariants = contract.get("invariants", {})
    fail(invariants.get("allocationCountMin") == 2 and invariants.get("allocationCountMax") == 8,
         "份额数量边界漂移")
    fail(invariants.get("cashAllocationMax") == 1 and invariants.get("cashMustBeLast") is True
         and invariants.get("sequentialCollection") is True, "现金或串行规则漂移")
    fail(invariants.get("unknownCreatesReplacementCommand") is False
         and invariants.get("refundToOriginalTenderOnly") is True, "UNKNOWN 或原路退款规则漂移")
    for requirement_id, expected in PRESERVED.items():
        fail(rows.get(requirement_id, {}).get("status") == expected,
             f"保留状态漂移: {requirement_id}")
        fail(contract.get("preservedStates", {}).get(requirement_id) == expected,
             f"准入契约保留状态漂移: {requirement_id}")
    external = contract.get("externalExecution", {})
    for field in ZERO_FIELDS:
        fail(external.get(field) == 0, f"外部执行必须为零: {field}")
    fail(external.get("commercialClaimAllowed") is False, "不得形成商业可用声明")
    fail(git("merge-base", "--is-ancestor", BASELINE, "HEAD") == "", "EXG 封板提交不是祖先")

    required = [
        "contracts/t2/gate7b-pay004/openapi-tender-runtime-v1.yaml",
        "contracts/t2/gate7b-pay004/tender-events-v1.schema.json",
        "contracts/t2/gate7b-pay004/pay004-fault-vectors.json",
        "contracts/t2/gate7b-pay004/tender-golden-vectors-v1.json",
        "docs/t2-gate7b-pay004/01_T2_PAY004正式实现准入与切面.md",
        "server/ruoyi-modules/jshpos-payment/src/main/java/com/jingshanghui/pos/payment/application/service/TenderPlanService.java",
        "server/ruoyi-modules/jshpos-payment/src/main/java/com/jingshanghui/pos/payment/domain/TenderRules.java",
        "server/ruoyi-modules/jshpos-payment/src/main/resources/mapper/payment/PaymentMapper.xml",
        "server/ruoyi-modules/jshpos-payment/src/main/resources/db/migration/V202608220057__gate7b_tender_plan.sql",
        "server/ruoyi-modules/jshpos-order/src/main/resources/db/migration/V202608220058__gate7b_cash_tender.sql",
        "pos-flutter/lib/infrastructure/local_database/gate7b_tender_schema.dart",
        "pos-flutter/lib/features/tender/infrastructure/local_pos_tender_application_service.dart",
        "pos-flutter/lib/features/tender/presentation/pos_tender_page.dart",
        "pos-flutter/test/gate7b/tender_plan_test.dart",
    ]
    for path in required:
        read(path)
    openapi = read(required[0])
    for token in ("openapi: 3.1.0", "T2-PAY-004", "x-provider-network-calls: 0",
                  "/payments/tender-plans", "cancelTenderPlan", "recoverTenderPlan",
                  "PAYMENT_EXTERNAL_BLOCKED"):
        fail(token in openapi, f"OpenAPI 缺少 {token}")
    events = read_json(required[1])
    fail(events.get("additionalProperties") is False and len(events.get("oneOf", [])) >= 5,
         "事件 payload 契约未失败关闭")
    vectors = read_json(required[2])
    items = vectors.get("vectors", [])
    fail(len(items) >= 12 and len({item.get("id") for item in items}) == len(items),
         "固定故障向量不足或 ID 重复")
    golden = read_json(required[3])
    golden_cases = golden.get("cases", [])
    fail(golden.get("requirementId") == "T2-PAY-004" and len(golden_cases) >= 1,
         "Java/Dart 摘要金标缺失")
    fail(all(re.fullmatch(r"[a-f0-9]{64}", item.get("expectedContentSha256", ""))
             for item in golden_cases), "摘要金标格式无效")

    server_runtime = "\n".join(read(path) for path in required[5:7])
    for token in ("PAYMENT_EXTERNAL_BLOCKED", "TenderCashCollectionPort", "TenderOrderSettlementPort",
                  "requireCollectable", "requireCancellable", "UNKNOWN"):
        fail(token in server_runtime, f"服务端运行时缺少 {token}")
    fail(not re.search(r"java\.net\.http|RestTemplate|WebClient|OkHttp|Retrofit|https?://", server_runtime),
         "PAY004 服务端不得引入 Provider 网络客户端或端点")
    mapper = read(required[7]).lower()
    fail("tenant_id=#{tenantid}" in re.sub(r"\s+", "", mapper), "Payment XML 缺少显式可信租户谓词")
    fail(not re.search(r"\b(update|delete)\s+(ord_|shf_|inv_|prm_)", mapper), "Payment Mapper 跨 Owner 写入")
    mysql = (read(required[8]) + read(required[9])).lower()
    for token in ("create table pay_tender_plan", "create table pay_tender_allocation",
                  "create table pay_tender_history", "create table ord_tender_settlement",
                  "create table ord_cash_tender", "append-only", "is immutable"):
        fail(token in mysql, f"MySQL 前向迁移缺少 {token}")
    fail("update ord_sales_order" not in mysql and "float" not in mysql and " double" not in mysql,
         "迁移不得覆盖订单快照或使用浮点数")
    sqlite = read(required[10]).lower()
    for token in ("static const int version = 13", "local_tender_plan", "local_tender_allocation",
                  "local_tender_event", "append-only", "cannot be deleted"):
        fail(token in sqlite, f"SQLite V13 缺少 {token}")
    flutter = read(required[11]) + read(required[12])
    for token in ("PAYMENT_EXTERNAL_BLOCKED", "TENDER-UNKNOWN-001", "local_outbox", "刷新原计划"):
        fail(token in flutter, f"Flutter 失败关闭或恢复边界缺少 {token}")
    fail("MethodChannel" not in flutter and "package:http" not in flutter and "Dio(" not in flutter,
         "Flutter PAY004 不得直连设备或 Provider")

    changed = sorted(filter(None, git("diff", "--name-only", BASELINE).splitlines()))
    sensitive = re.compile(r"(^|/)(\.env($|\.)|.*\.(pem|p12|pfx|jks|keystore|key)$)", re.I)
    fail(not [path for path in changed if sensitive.search(path)], "检测到敏感文件名")
    return {
        "schemaVersion": "1.0", "gate": GATE, "status": "PASS",
        "evidenceLevel": "INTERNAL_SOFTWARE_EXECUTION", "baselineCommit": BASELINE,
        "requirementStatus": status, "preservedStates": PRESERVED,
        "externalExecution": external, "faultVectorCount": len(items),
        "changedFileCount": len(changed),
        "decision": "PAY004_RUNTIME_GREEN_AWAITING_SPONSOR_ACCEPTANCE"
                    if status == "VERIFIED" else "PAY004_RUNTIME_IN_PROGRESS",
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
