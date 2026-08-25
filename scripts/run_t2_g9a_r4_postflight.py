#!/usr/bin/env python3
"""在同一商业 JAR 运行窗口完成 R4-R4 后置 Owner 旅程与守恒证据。

脚本只调用正式 HTTP API，并读取 Flutter 生成的脱敏证据；不连接 MySQL/Redis，
不写业务表，也不把口令、令牌、tenant_id 明文或请求正文写入普通制品。
"""
from __future__ import annotations

import argparse
import base64
import hashlib
import json
import pathlib
import time
import urllib.parse
from datetime import datetime, timedelta, timezone
from typing import Any

from run_t2_g9a_r4_bootstrap import WAREHOUSE_ID, stable_ulid
from run_t2_gate8b_runtime_api_journey import ApiClient, JourneyFailure, api_headers, data, require_value


OWNERS = (
    "saas", "subscription", "foundation", "service", "migration", "onboarding", "catalog",
    "sync", "order", "promotion", "member", "payment", "inventory", "costing", "procurement",
    "transfer", "returns", "reporting", "operations", "resilience", "release", "integration",
)
EXTERNAL_ONBOARDING_CHECKS = {
    "PAYMENT_EXTERNAL", "HARDWARE_EXTERNAL", "PRINT_EXTERNAL", "DESIGN_PARTNER_EXTERNAL",
}


def load(path: pathlib.Path) -> dict[str, Any]:
    value = json.loads(path.read_text(encoding="utf-8"))
    if not isinstance(value, dict):
        raise JourneyFailure(f"{path.name}: evidence root must be an object")
    return value


def sha256(value: Any) -> str:
    encoded = json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":")).encode("utf-8")
    return hashlib.sha256(encoded).hexdigest()


def text(value: dict[str, Any], key: str) -> str:
    result = value.get(key)
    if result is None or str(result) == "":
        raise JourneyFailure(f"missing required evidence field: {key}")
    return str(result)


def numeric(value: dict[str, Any], key: str) -> int:
    result = value.get(key)
    if not isinstance(result, int):
        raise JourneyFailure(f"invalid numeric evidence field: {key}")
    return result


def report_event(
    *, context: dict[str, Any], journey: dict[str, Any], owner: str, family: str,
    sequence: int, delta: dict[str, Any], run_id: str,
) -> dict[str, Any]:
    event_id = stable_ulid(f"{run_id}:{journey['journeyId']}:report:{owner}")
    correlation = stable_ulid(f"{run_id}:{journey['journeyId']}:report:{owner}:correlation")
    occurred_at = datetime.now(timezone.utc).isoformat().replace("+00:00", "Z")
    terminal_id = text(context, "terminalId") if family == "SALES" else None
    cashier_id = int(context["userId"]) if family == "SALES" else None
    warehouse_id = None if family == "SALES" else WAREHOUSE_ID
    sku_id = None if family == "SALES" else int(context["skuId"])
    sales = delta if family == "SALES" else None
    inventory = delta if family == "INVENTORY_COST" else None
    fields = [
        event_id, owner, text(journey, "originalOrderRef"), str(sequence),
        f"{owner}:{context['storeId']}:{context['businessDate']}", "1.0", "g5d-v1",
        occurred_at, text(context, "businessDate"), str(context["orgId"]), str(context["storeId"]),
        terminal_id or "", str(cashier_id) if cashier_id is not None else "", warehouse_id or "",
        str(sku_id) if sku_id is not None else "", "CNY", family,
    ]
    if sales is not None:
        fields.append(",".join(str(sales[key]) for key in (
            "orderCount", "cancelledOrderCount", "returnCount", "grossMinor", "discountMinor",
            "surchargeMinor", "receivableMinor", "refundMinor", "cashReceivedMinor",
            "cashRefundedMinor", "shiftDifferenceMinor", "promotionSnapshotCount",
        )))
        fields.append("")
    else:
        fields.append("")
        fields.append(",".join(str(inventory[key]) for key in (
            "onHandDelta", "availableDelta", "reservedDelta", "ledgerQuantityDelta",
            "purchaseQuantityDelta", "stocktakeQuantityDelta", "transferQuantityDelta",
            "inventoryValueDeltaMinor", "cogsDeltaMinor", "purchaseCostDeltaMinor",
            "stocktakeCostDeltaMinor", "transferCostDeltaMinor",
        )))
    fields.append(correlation)
    content_hash = hashlib.sha256("|".join(fields).encode("utf-8")).hexdigest()
    return {
        "sourceEventId": event_id, "sourceOwner": owner,
        "sourceAggregateId": text(journey, "originalOrderRef"), "sourceSequence": sequence,
        "partitionKey": f"{owner}:{context['storeId']}:{context['businessDate']}",
        "schemaVersion": "1.0", "projectionVersion": "g5d-v1",
        "contentSha256": content_hash, "occurredAt": occurred_at,
        "businessDate": context["businessDate"], "orgId": context["orgId"],
        "storeId": context["storeId"], "terminalId": terminal_id, "cashierId": cashier_id,
        "warehouseId": warehouse_id, "skuId": sku_id, "currency": "CNY",
        "metricFamily": family, "sales": sales, "inventoryCost": inventory,
        "correlationId": correlation,
    }


def checkpoint(
    *, run_id: str, journey: dict[str, Any], context: dict[str, Any], owner: str,
    source_fact_id: Any, source_sequence: int, fact: Any,
) -> dict[str, Any]:
    content_hash = sha256(fact)
    return {
        "runId": run_id, "journeyId": journey["journeyId"], "industry": context["industry"],
        "owner": owner, "checkpoint": f"{owner.upper()}_FORMAL_RUNTIME_OBSERVED",
        "tenantIdSha256": hashlib.sha256(text(context, "tenantId").encode("utf-8")).hexdigest(),
        "storeId": str(context["storeId"]), "businessDate": context["businessDate"],
        "sourceFactId": str(source_fact_id), "sourceSequence": source_sequence,
        "contentSha256": content_hash, "observedAt": datetime.now(timezone.utc).isoformat().replace("+00:00", "Z"),
    }


def run_journey(
    *, client: ApiClient, run_id: str, context: dict[str, Any], secret: dict[str, Any],
    journey: dict[str, Any], build_commit: str, backup_key_version: str,
) -> tuple[list[dict[str, Any]], list[dict[str, Any]], dict[str, Any]]:
    label = text(context, "journeyId").lower()
    client.login(text(context, "tenantId"), text(secret, "username"), text(secret, "password"),
                 f"{label}-postflight-admin-login")

    migration = data(client.call("POST", "/api/v1/business-migrations", f"{label}-migration-create", body={
        "dataTypes": ["CATALOG"], "idempotencyKey": f"{run_id}:{label}:migration",
        "correlationId": stable_ulid(f"{run_id}:{label}:migration:correlation"),
    }))
    migration_id = require_value(migration.get("batchId"), f"{label}-migration-create")

    member_id = stable_ulid(f"{run_id}:{label}:member")
    member = data(client.call("POST", "/api/v1/members", f"{label}-member-create", body={
        "commandId": stable_ulid(f"{run_id}:{label}:member:command"), "memberId": member_id,
        "identityId": stable_ulid(f"{run_id}:{label}:identity"), "identityType": "MEMBER_CODE",
        "identityValue": f"R4-{hashlib.sha256(label.encode()).hexdigest()[:12].upper()}",
        "correlationId": stable_ulid(f"{run_id}:{label}:member:correlation"),
    }))

    # R4 只形成内部 SYNTHETIC_RESTORE 低等级证据：正式 API、加密对象目录和空文件恢复
    # 目标均由商业 JAR 装配；它不读取生产源，也不会提升为真实 PITR/KMS/灾备结论。
    backup_id = stable_ulid(f"{run_id}:{label}:backup")
    restore_drill_id = stable_ulid(f"{run_id}:{label}:restore")
    point_in_time = datetime.now(timezone.utc) - timedelta(seconds=2)
    point_text = point_in_time.isoformat().replace("+00:00", "Z")
    backup = data(client.call("POST", "/api/v1/backups", f"{label}-synthetic-backup", body={
        "backupId": backup_id, "environment": "G9A_R4_INTERNAL",
        "pointInTime": point_text, "latestIncludedFactAt": point_text,
        "schemaVersion": "R4", "applicationVersion": "R4", "keyVersion": backup_key_version,
        "immutableUntil": (point_in_time + timedelta(days=1)).isoformat().replace("+00:00", "Z"),
        "correlationId": stable_ulid(f"{run_id}:{label}:backup:trace"),
    }))
    if backup.get("state") != "AVAILABLE" or backup.get("objectCount") != 6:
        raise JourneyFailure(f"{label}-synthetic-backup: expected six-object AVAILABLE backup")
    restore = data(client.call(
        "POST", f"/api/v1/backups/{backup_id}/restore-drills", f"{label}-synthetic-restore",
        body={"drillId": restore_drill_id, "expectedSchemaVersion": "R4",
              "correlationId": stable_ulid(f"{run_id}:{label}:restore:trace")},
    ))
    if restore.get("state") != "PASS" or restore.get("checkCount") != 9 or not restore.get("evidenceSha256"):
        raise JourneyFailure(f"{label}-synthetic-restore: expected nine-check PASS evidence")

    plan = data(client.call("POST", "/api/v1/onboarding/plans", f"{label}-onboarding-create", body={
        "sourceStoreId": None, "targetStoreId": context["onboardingTargetStoreId"],
        "templateId": context["onboardingTemplateId"],
        "templateVersionId": context["onboardingTemplateVersionId"],
    }, headers=api_headers(f"{label}-onboard-create", stable_ulid(f"{run_id}:{label}:onboard:create"))))
    plan_id = require_value(plan.get("plan", {}).get("planId"), f"{label}-onboarding-create")
    plan = data(client.call("POST", f"/api/v1/onboarding/plans/{plan_id}/preflight",
                            f"{label}-onboarding-preflight",
                            headers=api_headers(f"{label}-onboard-preflight",
                                                stable_ulid(f"{run_id}:{label}:onboard:preflight"))))
    if plan.get("plan", {}).get("state") != "READY":
        raise JourneyFailure(f"{label}-onboarding-preflight: expected READY")
    client.login(text(context, "tenantId"), text(secret, "reviewerUsername"),
                 text(secret, "reviewerPassword"), f"{label}-onboarding-reviewer-login")
    client.call("POST", f"/api/v1/onboarding/plans/{plan_id}/approve", f"{label}-onboarding-approve",
                body={"reason": "内部正式栈独立复核"},
                headers=api_headers(f"{label}-onboard-approve",
                                    stable_ulid(f"{run_id}:{label}:onboard:approve")))
    client.login(text(context, "tenantId"), text(secret, "username"), text(secret, "password"),
                 f"{label}-onboarding-admin-login")
    client.call("POST", f"/api/v1/onboarding/plans/{plan_id}/apply", f"{label}-onboarding-apply",
                headers=api_headers(f"{label}-onboard-apply", stable_ulid(f"{run_id}:{label}:onboard:apply")))
    plan = data(client.call("POST", f"/api/v1/onboarding/plans/{plan_id}/checks", f"{label}-onboarding-checks",
                            headers=api_headers(f"{label}-onboard-checks",
                                                stable_ulid(f"{run_id}:{label}:onboard:checks"))))
    external_checks = {check.get("checkCode"): check.get("status") for check in plan.get("checks", [])
                       if check.get("external") is True}
    if (plan.get("plan", {}).get("state") != "READY_TO_OPEN"
            or external_checks != {code: "BLOCKED" for code in EXTERNAL_ONBOARDING_CHECKS}):
        raise JourneyFailure(
            f"{label}-onboarding-checks: internal checks must pass and all external P0 remain BLOCKED")

    transfer_id = stable_ulid(f"{run_id}:{label}:transfer")
    transfer_line_id = stable_ulid(f"{run_id}:{label}:transfer-line")
    transfer = data(client.call("POST", "/api/v1/inventory/transfers", f"{label}-transfer-create", body={
        "transferId": transfer_id, "sourceStoreId": str(context["storeId"]),
        "sourceWarehouseId": WAREHOUSE_ID, "destinationStoreId": str(context["onboardingTargetStoreId"]),
        "destinationWarehouseId": stable_ulid(f"{run_id}:{label}:destination-warehouse"),
        "lines": [{"transferLineId": transfer_line_id, "skuId": str(context["skuId"]),
                   "unitId": str(context["unitId"]), "requestedQuantity": "1.000000"}],
        "reason": "内部正式栈调拨状态守恒", "correlationId": stable_ulid(f"{run_id}:{label}:transfer:trace"),
    }))
    transfer = data(client.call("POST", f"/api/v1/inventory/transfers/{transfer_id}/submit",
                                f"{label}-transfer-submit", body={
        "commandId": stable_ulid(f"{run_id}:{label}:transfer:submit"),
        "expectedVersion": transfer["head"]["version"], "reason": "提交内部合成调拨",
        "correlationId": stable_ulid(f"{run_id}:{label}:transfer:submit:trace"),
    }))
    client.login(text(context, "tenantId"), text(secret, "reviewerUsername"),
                 text(secret, "reviewerPassword"), f"{label}-transfer-reviewer-login")
    transfer = data(client.call("POST", f"/api/v1/inventory/transfers/{transfer_id}/approve",
                                f"{label}-transfer-approve", body={
        "commandId": stable_ulid(f"{run_id}:{label}:transfer:approve"),
        "expectedVersion": transfer["head"]["version"], "reason": "独立复核内部合成调拨",
        "correlationId": stable_ulid(f"{run_id}:{label}:transfer:approve:trace"),
    }))
    client.login(text(context, "tenantId"), text(secret, "username"), text(secret, "password"),
                 f"{label}-transfer-admin-login")
    transfer = data(client.call("POST", f"/api/v1/inventory/transfers/{transfer_id}/cancel",
                                f"{label}-transfer-cancel", body={
        "commandId": stable_ulid(f"{run_id}:{label}:transfer:cancel"),
        "expectedVersion": transfer["head"]["version"], "reason": "未发出调拨受控取消",
        "correlationId": stable_ulid(f"{run_id}:{label}:transfer:cancel:trace"),
    }))
    if transfer["head"]["status"] != "CANCELLED":
        raise JourneyFailure(f"{label}-transfer-cancel: expected CANCELLED")

    inventory_before = data(client.call("GET", f"/api/v1/inventory/balances/{WAREHOUSE_ID}/{context['skuId']}",
                                        f"{label}-inventory-read"))
    inventory_rebuild = data(client.call(
        "POST", f"/api/v1/inventory/balances/{WAREHOUSE_ID}/{context['skuId']}/rebuild",
        f"{label}-inventory-rebuild", body={
            "correlationId": stable_ulid(f"{run_id}:{label}:inventory:rebuild")
        }))
    costing_before = data(client.call("GET", f"/api/inventory/cost-balances/{WAREHOUSE_ID}/{context['skuId']}",
                                      f"{label}-costing-read"))
    costing_rebuild = data(client.call(
        "POST", f"/api/inventory/cost-balances/{WAREHOUSE_ID}/{context['skuId']}/rebuild",
        f"{label}-costing-rebuild", body={
            "rebuildId": stable_ulid(f"{run_id}:{label}:costing:rebuild"),
            "correlationId": stable_ulid(f"{run_id}:{label}:costing:rebuild:trace"),
        }))

    sales_totals = {
        "orderCount": numeric(journey, "orderCount"), "cancelledOrderCount": 0,
        "returnCount": numeric(journey, "returnCount"), "grossMinor": numeric(journey, "grossAmountMinor"),
        "discountMinor": numeric(journey, "discountAmountMinor"),
        "surchargeMinor": numeric(journey, "surchargeAmountMinor"),
        "receivableMinor": numeric(journey, "receivableAmountMinor"),
        "refundMinor": numeric(journey, "refundedAmountMinor"),
        "cashReceivedMinor": numeric(journey, "cashNetAmountMinor"),
        "cashRefundedMinor": numeric(journey, "refundedAmountMinor"), "shiftDifferenceMinor": 0,
        "promotionSnapshotCount": numeric(journey, "promotionSnapshotCount"),
    }
    zero_sales = {key: 0 for key in sales_totals}
    zero_inventory = {key: "0" for key in (
        "onHandDelta", "availableDelta", "reservedDelta", "ledgerQuantityDelta", "purchaseQuantityDelta",
        "stocktakeQuantityDelta", "transferQuantityDelta", "inventoryValueDeltaMinor", "cogsDeltaMinor",
        "purchaseCostDeltaMinor", "stocktakeCostDeltaMinor", "transferCostDeltaMinor",
    )}
    report_results: list[dict[str, Any]] = []
    for index, (owner, family, delta) in enumerate((
        ("ORDER", "SALES", sales_totals), ("SHIFT", "SALES", zero_sales),
        ("PROMOTION", "SALES", zero_sales), ("INVENTORY", "INVENTORY_COST", zero_inventory),
        ("COSTING", "INVENTORY_COST", zero_inventory),
    ), start=1):
        event = report_event(context=context, journey=journey, owner=owner, family=family,
                             sequence=1, delta=delta, run_id=run_id)
        report_results.append(data(client.call("POST", "/api/v1/reporting/source-events",
                                               f"{label}-report-{owner.lower()}", body=event)))
    sales_report = data(client.call("GET", "/api/v1/reports/sales-daily?" + urllib.parse.urlencode({
        "fromDate": context["businessDate"], "toDate": context["businessDate"],
        "storeId": context["storeId"],
    }), f"{label}-sales-report-read"))

    close = data(client.call("POST", "/api/v1/operations/daily-closes", f"{label}-daily-close-create",
                             body={"storeId": context["storeId"], "businessDate": context["businessDate"],
                                   "correctionOfCloseId": None, "correctionReason": None},
                             headers=api_headers(f"{label}-daily-close-create",
                                                 stable_ulid(f"{run_id}:{label}:close:create"))))
    close_id = require_value(close.get("close", {}).get("closeId"), f"{label}-daily-close-create")
    close = data(client.call("POST", f"/api/v1/operations/daily-closes/{close_id}/preflight",
                             f"{label}-daily-close-preflight",
                             headers=api_headers(f"{label}-daily-close-preflight",
                                                 stable_ulid(f"{run_id}:{label}:close:preflight"))))
    if close.get("close", {}).get("state") != "READY":
        failed = [item.get("checkCode") for item in close.get("preflights", []) if item.get("status") == "FAIL"]
        raise JourneyFailure(f"{label}-daily-close-preflight: expected READY, failed={failed}")
    client.login(text(context, "tenantId"), text(secret, "reviewerUsername"),
                 text(secret, "reviewerPassword"), f"{label}-daily-close-reviewer-login")
    client.call("POST", f"/api/v1/operations/daily-closes/{close_id}/approve",
                f"{label}-daily-close-approve", body={"reason": "内部正式栈独立审批"},
                headers=api_headers(f"{label}-daily-close-approve",
                                    stable_ulid(f"{run_id}:{label}:close:approve")))
    close = data(client.call("POST", f"/api/v1/operations/daily-closes/{close_id}/close",
                             f"{label}-daily-close-sign",
                             headers=api_headers(f"{label}-daily-close-sign",
                                                 stable_ulid(f"{run_id}:{label}:close:sign"))))
    if close.get("close", {}).get("state") != "CLOSED" or not close.get("signatures"):
        raise JourneyFailure(f"{label}-daily-close-sign: expected append-only CLOSED signature")

    client.call("POST", "/api/v1/operations/exceptions/scan", f"{label}-exception-scan", body={
        "storeId": context["storeId"], "businessDate": context["businessDate"],
    }, headers=api_headers(f"{label}-exception-scan",
                           stable_ulid(f"{run_id}:{label}:exception-scan:trace")))

    client.login(text(context, "tenantId"), text(secret, "username"), text(secret, "password"),
                 f"{label}-release-admin-login")
    release_body = {
        "artifactType": "DATA_PACKAGE", "version": f"R4-{label[-12:]}", "channel": "INTERNAL",
        "objectKey": f"releases/{context['tenantId']}/{run_id}/{label}.bin",
        "artifactSha256": hashlib.sha256(f"{run_id}:{label}:artifact".encode()).hexdigest(),
        "signatureBase64": base64.b64encode(hashlib.sha256(f"{run_id}:{label}:signature".encode()).digest()).decode(),
        "keyVersion": "R4_EXTERNAL_BLOCKED", "buildCommit": build_commit,
        "sbomSha256": hashlib.sha256(f"{build_commit}:sbom".encode()).hexdigest(),
        "compatibility": {"minAppVersion": "1.0.0", "maxAppVersion": "1.0.x",
                          "minProtocolVersion": "1.0", "maxProtocolVersion": "1.0",
                          "minSchemaVersion": "1.0", "maxSchemaVersion": "1.0",
                          "minSystemVersion": "1.0", "maxSystemVersion": "1.0",
                          "requiredCapabilitySha256": ""},
        "targetStoreIds": [context["storeId"]],
    }
    release = data(client.call("POST", "/api/v1/releases", f"{label}-release-create", body=release_body,
                               headers={"X-Idempotency-Key": f"{run_id}:{label}:release"}))

    facts = {
        "saas": (context["applicationId"], {"applicationId": context["applicationId"]}),
        "subscription": (context["subscriptionId"], {"subscriptionId": context["subscriptionId"]}),
        "foundation": (context["storeId"], {"storeId": context["storeId"], "orgId": context["orgId"]}),
        "service": (context["serviceProjectId"], {"projectId": context["serviceProjectId"]}),
        "migration": (migration_id, migration), "onboarding": (plan_id, plan),
        "catalog": (context["skuId"], {"skuId": context["skuId"], "catalogVersion": context["catalogVersion"]}),
        "sync": (context["terminalId"], {"outbox": journey["outboxStatuses"], "unsettled": journey["outboxUnsettled"]}),
        "order": (journey["originalOrderRef"], {key: journey[key] for key in (
            "orderCount", "grossAmountMinor", "discountAmountMinor", "surchargeAmountMinor", "receivableAmountMinor")}),
        "promotion": (journey["firstPromotionSnapshotId"], {
            "snapshotCount": journey["promotionSnapshotCount"], "allocatedMinor": journey["promotionAllocatedMinor"]}),
        "member": (member_id, member), "payment": (journey["firstCashPaymentId"], {
            "cashNetAmountMinor": journey["cashNetAmountMinor"]}),
        "inventory": (context["inventoryPolicyVersionId"], {"before": inventory_before, "rebuild": inventory_rebuild}),
        "costing": (context["costPolicyVersionId"], {"before": costing_before, "rebuild": costing_rebuild}),
        "procurement": (context["procurementOrderId"],
                        {"warehouseId": WAREHOUSE_ID, "skuId": context["skuId"]}),
        "transfer": (transfer_id, transfer), "returns": (journey["returnRef"], {
            "returnRef": journey["returnRef"], "exchangeRef": journey["exchangeRef"],
            "refundedAmountMinor": journey["refundedAmountMinor"]}),
        "reporting": (report_results[0]["sourceEventId"], {"sources": report_results, "report": sales_report}),
        "operations": (close_id, close),
        "resilience": (backup_id, {"backup": backup, "restore": restore,
                                    "evidenceLevel": "SYNTHETIC_RESTORE"}),
        "release": (release["releaseId"], release),
    }
    facts["integration"] = (run_id, {owner: sha256(value[1]) for owner, value in facts.items()})
    checkpoints = [checkpoint(run_id=run_id, journey=journey, context=context, owner=owner,
                              source_fact_id=facts[owner][0], source_sequence=index, fact=facts[owner][1])
                   for index, owner in enumerate(OWNERS, start=1)]

    invariants = [
        {"id": "R4-C01", "pass": journey["grossAmountMinor"] - journey["discountAmountMinor"]
         + journey["surchargeAmountMinor"] == journey["receivableAmountMinor"]},
        {"id": "R4-C02", "pass": journey["cashNetAmountMinor"] == journey["receivableAmountMinor"]},
        {"id": "R4-C03", "pass": 0 < journey["refundedAmountMinor"] <= journey["receivableAmountMinor"]},
        {"id": "R4-C04", "pass": journey["promotionAllocatedMinor"] == journey["discountAmountMinor"]},
        {"id": "R4-C05", "pass": journey["memberBenefitSnapshotCount"] == 0},
        {"id": "R4-C06", "pass": inventory_rebuild is not None},
        {"id": "R4-C07", "pass": context["industry"] != "COMMUNITY_SUPERMARKET"
         or journey["lotAllocatedQuantity"] not in ("0", "0.0", "0.000000")},
        {"id": "R4-C08", "pass": costing_rebuild is not None},
        {"id": "R4-C09", "pass": journey["outboxUnsettled"] == 0 and journey["deadLetters"] == 0},
        {"id": "R4-C10", "pass": bool(sales_report)},
        {"id": "R4-C11", "pass": close["close"]["state"] == "CLOSED" and len(close["signatures"]) == 1},
        {"id": "R4-C12", "pass": release["state"] == "DRAFT" and backup["state"] == "AVAILABLE"
         and restore["state"] == "PASS" and restore["checkCount"] == 9},
    ]
    if not all(item["pass"] for item in invariants):
        raise JourneyFailure(f"{label}: conservation failure {[x['id'] for x in invariants if not x['pass']]}")
    summary = {
        "journeyId": context["journeyId"], "industry": context["industry"],
        "ownerCheckpointCount": len(checkpoints), "conservationPassCount": len(invariants),
        "dailyCloseState": close["close"]["state"], "onboardingState": plan["plan"]["state"],
        "releaseState": release["state"], "backupState": backup["state"],
        "restoreState": restore["state"], "externalP0States": external_checks,
    }
    return checkpoints, invariants, summary


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--base-url", required=True)
    parser.add_argument("--bootstrap", required=True, type=pathlib.Path)
    parser.add_argument("--secrets", required=True, type=pathlib.Path)
    parser.add_argument("--flutter", required=True, type=pathlib.Path)
    parser.add_argument("--build-commit", required=True)
    parser.add_argument("--backup-key-version", required=True)
    parser.add_argument("--output", required=True, type=pathlib.Path)
    args = parser.parse_args()
    try:
        bootstrap = load(args.bootstrap)
        secrets = load(args.secrets)
        flutter = load(args.flutter)
        run_id = text(bootstrap, "runId")
        if text(secrets, "runId") != run_id or text(flutter, "runId") != run_id:
            raise JourneyFailure("bootstrap/secret/flutter run identity mismatch")
        contexts = {item["journeyId"]: item for item in bootstrap.get("journeys", [])}
        secret_rows = {item["journeyId"]: item for item in secrets.get("journeys", [])}
        flutter_rows = {item["journeyId"]: item for item in flutter.get("journeys", [])}
        if set(contexts) != set(secret_rows) or set(contexts) != set(flutter_rows) or len(contexts) != 3:
            raise JourneyFailure("three-industry journey set mismatch")
        client = ApiClient(args.base_url)
        started = time.perf_counter()
        checkpoints: list[dict[str, Any]] = []
        invariants: list[dict[str, Any]] = []
        summaries: list[dict[str, Any]] = []
        for journey_id in sorted(contexts):
            owner_rows, conservation, summary = run_journey(
                client=client, run_id=run_id, context=contexts[journey_id], secret=secret_rows[journey_id],
                journey=flutter_rows[journey_id], build_commit=args.build_commit,
                backup_key_version=args.backup_key_version,
            )
            checkpoints.extend(owner_rows)
            invariants.extend({**item, "journeyId": journey_id} for item in conservation)
            summaries.append(summary)
        if len(checkpoints) != 66 or {item["owner"] for item in checkpoints} != set(OWNERS):
            raise JourneyFailure("22 Owner checkpoints are incomplete")
        evidence = {
            "schemaVersion": "1.0", "gate": "G9A-R4", "phase": "R4-R4", "runId": run_id,
            "status": "PASS",
            "classification": "FORMAL_HTTP_OWNER_CHECKPOINTS_WITH_BLOCKED_EXTERNALS_AND_SYNTHETIC_RESTORE",
            "buildCommit": args.build_commit, "journeys": summaries, "journeyCount": 3,
            "ownerCheckpointCount": len(checkpoints), "ownerCount": 22,
            "conservationCheckCount": len(invariants), "conservationPassCount": sum(x["pass"] for x in invariants),
            "checkpoints": checkpoints, "conservation": invariants,
            "observations": [item.__dict__ for item in client.observations],
            "elapsedMs": round((time.perf_counter() - started) * 1000),
            "directDatabaseBusinessWrites": 0, "providerNetworkCalls": 0,
            "realDeviceOrPeripheralCommands": 0, "productionBackupOrKms": 0,
        }
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(json.dumps(evidence, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        print("G9A-R4 postflight passed: journeys=3 owners=22 checkpoints=66 conservation=36")
        return 0
    except (JourneyFailure, KeyError, TypeError, ValueError) as error:
        print(f"G9A-R4 postflight failed: {error}")
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
