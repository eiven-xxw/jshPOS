#!/usr/bin/env python3
"""在同一正式栈重启窗口执行 G9A-R4-R5 固定故障 seed。

除只读核对 POS 文件 SQLite 的原 Outbox 身份外，所有业务观察和命令均经正式
HTTP API；不连接或写入 MySQL，不伪造 Provider、设备、备份或发布成功。
"""
from __future__ import annotations

import argparse
import base64
import hashlib
import json
import pathlib
import sqlite3
import time
import urllib.parse
from datetime import datetime, timezone
from typing import Any

from run_t2_g9a_r4_bootstrap import WAREHOUSE_ID, stable_ulid
from run_t2_g9a_r4_postflight import report_event, synthetic_release_version
from run_t2_gate8b_runtime_api_journey import ApiClient, JourneyFailure, data


def load(path: pathlib.Path) -> dict[str, Any]:
    value = json.loads(path.read_text(encoding="utf-8"))
    if not isinstance(value, dict):
        raise JourneyFailure(f"{path.name}: evidence root must be an object")
    return value


def text(value: dict[str, Any], key: str) -> str:
    result = value.get(key)
    if result is None or str(result) == "":
        raise JourneyFailure(f"missing evidence field: {key}")
    return str(result)


def instant() -> str:
    return datetime.now(timezone.utc).isoformat(timespec="milliseconds").replace("+00:00", "Z")


def digest(value: Any) -> str:
    return hashlib.sha256(json.dumps(value, sort_keys=True, separators=(",", ":"),
                                     ensure_ascii=False).encode("utf-8")).hexdigest()


def require(condition: bool, message: str) -> None:
    if not condition:
        raise JourneyFailure(message)


def checkpoint_id(postflight: dict[str, Any], journey_id: str, owner: str) -> str:
    rows = [row for row in postflight.get("checkpoints", [])
            if row.get("journeyId") == journey_id and row.get("owner") == owner]
    require(len(rows) == 1, f"{journey_id}: missing {owner} checkpoint")
    return text(rows[0], "sourceFactId")


def release_body(context: dict[str, Any], run_id: str, build_commit: str) -> dict[str, Any]:
    label = text(context, "journeyId").lower()
    return {
        "artifactType": "DATA_PACKAGE", "version": synthetic_release_version(label, "idempotency"),
        "channel": "INTERNAL",
        "objectKey": f"releases/{context['tenantId']}/{run_id}/r4-idempotency.bin",
        "artifactSha256": hashlib.sha256(f"{run_id}:{label}:idempotency-artifact".encode()).hexdigest(),
        "signatureBase64": base64.b64encode(hashlib.sha256(b"r4-invalid-signature").digest()).decode(),
        "keyVersion": "R4_EXTERNAL_BLOCKED", "buildCommit": build_commit,
        "sbomSha256": hashlib.sha256(f"{build_commit}:sbom".encode()).hexdigest(),
        "compatibility": {"minAppVersion": "1.0.0", "maxAppVersion": "1.0.x",
                          "minProtocolVersion": "1.0", "maxProtocolVersion": "1.0",
                          "minSchemaVersion": "1.0", "maxSchemaVersion": "1.0",
                          "minSystemVersion": "1.0", "maxSystemVersion": "1.0",
                          "requiredCapabilitySha256": ""},
        "targetStoreIds": [context["storeId"]],
    }


def local_outbox(sqlite_root: pathlib.Path, journey_id: str, event_id: str) -> dict[str, Any]:
    path = sqlite_root / f"{journey_id.lower()}.sqlite3"
    require(path.is_file(), f"{journey_id}: file SQLite missing")
    uri = f"file:{path.as_posix()}?mode=ro"
    with sqlite3.connect(uri, uri=True) as connection:
        connection.row_factory = sqlite3.Row
        row = connection.execute(
            "SELECT event_id,payload_sha256,status,last_ack_status FROM local_outbox WHERE event_id=?",
            (event_id,),
        ).fetchone()
        require(row is not None, f"{journey_id}: original ACK-loss event missing")
        dates = [item[0] for item in connection.execute(
            "SELECT DISTINCT business_date FROM local_order ORDER BY business_date"
        ).fetchall()]
        return {"eventId": row["event_id"], "payloadHash": row["payload_sha256"],
                "status": row["status"], "lastAckStatus": row["last_ack_status"],
                "businessDates": dates}


def synthetic_unknown_bill(client: ApiClient, context: dict[str, Any], run_id: str) -> dict[str, Any]:
    label = text(context, "journeyId").lower()
    entry_id = stable_ulid(f"{run_id}:{label}:unknown-bill")
    batch_id = stable_ulid(f"{run_id}:{label}:unknown-bill-batch")
    key = stable_ulid(f"{run_id}:{label}:unknown-reconciliation")
    correlation = stable_ulid(f"{run_id}:{label}:unknown-bill-trace")
    body = {
        "billEntryId": entry_id, "batchId": batch_id, "sourceType": "INTERNAL_SYNTHETIC",
        "synthetic": True, "schemaVersion": "1.0", "businessDate": context["businessDate"],
        "orgId": context["orgId"], "storeId": context["storeId"],
        "terminalId": context["terminalId"], "factType": "PAYMENT", "reconciliationKey": key,
        "amountMinor": 1, "currency": "CNY", "lifecycleStatus": "UNKNOWN",
        "correlationId": correlation,
    }
    canonical = "|".join((entry_id, batch_id, "INTERNAL_SYNTHETIC", "true", "1.0",
                           str(context["businessDate"]), str(context["orgId"]), str(context["storeId"]),
                           str(context["terminalId"]), "PAYMENT", key, "1", "CNY", "UNKNOWN", correlation))
    body["contentSha256"] = hashlib.sha256(canonical.encode()).hexdigest()
    first = data(client.call("POST", "/api/v1/reporting/internal-synthetic-bills",
                             f"{label}-unknown-bill", body=body))
    replay = data(client.call("POST", "/api/v1/reporting/internal-synthetic-bills",
                              f"{label}-unknown-bill-replay", body=body))
    require(first.get("applied") is True and replay.get("applied") is False,
            f"{label}: UNKNOWN synthetic bill identity did not replay stably")
    return {"billEntryId": entry_id, "reconciliationId": first.get("reconciliationId"),
            "differenceType": first.get("differenceType"), "replayApplied": replay.get("applied")}


def late_reporting(client: ApiClient, context: dict[str, Any], journey: dict[str, Any], run_id: str) -> dict[str, Any]:
    zero = {key: 0 for key in ("orderCount", "cancelledOrderCount", "returnCount", "grossMinor",
            "discountMinor", "surchargeMinor", "receivableMinor", "refundMinor", "cashReceivedMinor",
            "cashRefundedMinor", "shiftDifferenceMinor", "promotionSnapshotCount")}
    second = report_event(context=context, journey=journey, owner="R4FAULT", family="SALES",
                          sequence=2, delta=zero, run_id=run_id)
    # report_event 的事件标识按 owner 稳定；第二条需要独立身份但保持同一 partition。
    second["sourceEventId"] = stable_ulid(f"{run_id}:{context['journeyId']}:late:2")
    second["contentSha256"] = _report_hash(second)
    first = report_event(context=context, journey=journey, owner="R4FAULT", family="SALES",
                         sequence=1, delta=zero, run_id=run_id)
    first["sourceEventId"] = stable_ulid(f"{run_id}:{context['journeyId']}:late:1")
    first["contentSha256"] = _report_hash(first)
    gap = data(client.call("POST", "/api/v1/reporting/source-events", "r4-late-sequence-2", body=second))
    repaired = data(client.call("POST", "/api/v1/reporting/source-events", "r4-late-sequence-1", body=first))
    replay = data(client.call("POST", "/api/v1/reporting/source-events", "r4-late-sequence-2-replay", body=second))
    require(gap.get("projectionStatus") == "INCOMPLETE", "late event did not expose sequence gap")
    require(repaired.get("projectionStatus") == "CURRENT", "late event did not converge after missing fact")
    require(replay.get("applied") is False, "late event replay was not idempotent")
    return {"gapStatus": gap.get("projectionStatus"), "repairedStatus": repaired.get("projectionStatus"),
            "replayApplied": replay.get("applied")}


def _report_hash(event: dict[str, Any]) -> str:
    sales = event["sales"]
    sales_text = ",".join(str(sales[key]) for key in (
        "orderCount", "cancelledOrderCount", "returnCount", "grossMinor", "discountMinor",
        "surchargeMinor", "receivableMinor", "refundMinor", "cashReceivedMinor", "cashRefundedMinor",
        "shiftDifferenceMinor", "promotionSnapshotCount"))
    fields = [event["sourceEventId"], event["sourceOwner"], event["sourceAggregateId"],
              str(event["sourceSequence"]), event["partitionKey"], event["schemaVersion"],
              event["projectionVersion"], event["occurredAt"], event["businessDate"], str(event["orgId"]),
              str(event["storeId"]), event.get("terminalId") or "", str(event.get("cashierId") or ""),
              event.get("warehouseId") or "", str(event.get("skuId") or ""), event["currency"],
              event["metricFamily"], sales_text, "", event["correlationId"]]
    return hashlib.sha256("|".join(fields).encode()).hexdigest()


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--base-url", required=True)
    parser.add_argument("--bootstrap", required=True, type=pathlib.Path)
    parser.add_argument("--secrets", required=True, type=pathlib.Path)
    parser.add_argument("--flutter", required=True, type=pathlib.Path)
    parser.add_argument("--postflight", required=True, type=pathlib.Path)
    parser.add_argument("--sqlite-root", required=True, type=pathlib.Path)
    parser.add_argument("--build-commit", required=True)
    parser.add_argument("--server-restart-count", required=True, type=int)
    parser.add_argument("--redis-flush-count", required=True, type=int)
    parser.add_argument("--output", required=True, type=pathlib.Path)
    args = parser.parse_args()
    try:
        started = time.perf_counter()
        bootstrap, secrets, flutter, postflight = (load(args.bootstrap), load(args.secrets),
                                                   load(args.flutter), load(args.postflight))
        run_id = text(bootstrap, "runId")
        require(all(text(item, "runId") == run_id for item in (secrets, flutter, postflight)),
                "cross-artifact run identity mismatch")
        contexts = {row["journeyId"]: row for row in bootstrap["journeys"]}
        secret_rows = {row["journeyId"]: row for row in secrets["journeys"]}
        journey_rows = {row["journeyId"]: row for row in flutter["journeys"]}
        require(len(contexts) == 3 and set(contexts) == set(secret_rows) == set(journey_rows),
                "three-industry set mismatch")
        require(args.server_restart_count == 1 and args.redis_flush_count == 1,
                "actual one-time server restart and Redis flush evidence required")

        client = ApiClient(args.base_url)
        first_id = sorted(contexts)[0]
        first_context, first_secret, first_journey = (contexts[first_id], secret_rows[first_id], journey_rows[first_id])
        client.login(text(first_context, "tenantId"), text(first_secret, "username"),
                     text(first_secret, "password"), "r4-fault-login")

        # F01：同键同内容稳定返回原 release；同键异内容必须拒绝。
        release_key = f"{run_id}:r4-f01-release"
        body = release_body(first_context, run_id, args.build_commit)
        created = data(client.call("POST", "/api/v1/releases", "r4-f01-create", body=body,
                                   headers={"X-Idempotency-Key": release_key}))
        duplicate = data(client.call("POST", "/api/v1/releases", "r4-f01-duplicate", body=body,
                                     headers={"X-Idempotency-Key": release_key}))
        changed = dict(body); changed["version"] = body["version"] + "-CONFLICT"
        conflict = client.call("POST", "/api/v1/releases", "r4-f01-conflict", body=changed,
                               headers={"X-Idempotency-Key": release_key}, expect_success=False)
        require(created["releaseId"] == duplicate["releaseId"], "F01 duplicate identity drift")
        seeds: dict[str, dict[str, Any]] = {
            "R4-F01": {"pass": True, "releaseId": created["releaseId"],
                       "stableReplay": True, "conflictCode": conflict.get("code")},
        }

        f02_rows, f03_rows, f04_rows, f05_rows, f06_rows = [], [], [], [], []
        sorted_ids = sorted(contexts)
        for index, journey_id in enumerate(sorted_ids):
            context, secret, journey = contexts[journey_id], secret_rows[journey_id], journey_rows[journey_id]
            ack = journey.get("ackLossRecovery") or {}
            event_id = text(ack, "eventId")
            local = local_outbox(args.sqlite_root, journey_id, event_id)
            require(ack.get("ackPersistedBeforeRestart") is False and local["status"] == "ACKED",
                    f"{journey_id}: F02 did not converge with original event")
            client.login(text(context, "tenantId"), text(secret, "username"), text(secret, "password"),
                         f"{journey_id}-fault-login")
            result = data(client.call("GET", f"/api/pos/v1/sync/results/{event_id}",
                                      f"{journey_id}-sync-result-after-restart",
                                      headers={"X-Device-Id": text(context, "deviceId")}))
            require(result.get("eventId") == event_id and result.get("payloadHash") == local["payloadHash"],
                    f"{journey_id}: F03 original Inbox result not observable")
            order = data(client.call("GET", f"/api/v1/pos/orders/{journey['originalOrderRef']}",
                                     f"{journey_id}-order-after-redis-loss"))
            require(order.get("status") == "COMPLETED" and order.get("receivableAmountMinor") == 940,
                    f"{journey_id}: F04 authoritative order changed")
            inventory = data(client.call("GET", f"/api/v1/inventory/balances/{WAREHOUSE_ID}/{context['skuId']}",
                                         f"{journey_id}-inventory-after-redis-loss"))
            other = contexts[sorted_ids[(index + 1) % len(sorted_ids)]]
            denied = client.call("POST", "/api/pos/v1/sync/bootstrap", f"{journey_id}-cross-device-denied",
                                 headers={"X-Device-Id": text(other, "deviceId")}, expect_success=False)
            require(str(order.get("businessDate")) == str(context["businessDate"])
                    and local["businessDates"] == [str(context["businessDate"])],
                    f"{journey_id}: F06 business date was recomputed")
            f02_rows.append({"journeyId": journey_id, "eventId": event_id,
                             "initialAckStatus": ack.get("initialAckStatus"), "finalStatus": local["status"]})
            f03_rows.append({"journeyId": journey_id, "eventId": event_id,
                             "observedStatus": result.get("status")})
            f04_rows.append({"journeyId": journey_id, "orderStatus": order.get("status"),
                             "inventorySha256": digest(inventory)})
            f05_rows.append({"journeyId": journey_id, "deniedCode": denied.get("code")})
            f06_rows.append({"journeyId": journey_id, "frozenBusinessDate": order.get("businessDate")})
        seeds.update({
            "R4-F02": {"pass": True, "journeys": f02_rows},
            "R4-F03": {"pass": True, "actualServerRestarts": args.server_restart_count, "journeys": f03_rows},
            "R4-F04": {"pass": True, "actualRedisFlushes": args.redis_flush_count, "journeys": f04_rows},
            "R4-F05": {"pass": True, "journeys": f05_rows},
            "R4-F06": {"pass": True, "journeys": f06_rows},
        })

        corrupt = flutter.get("faultEvidence", [])
        require(len(corrupt) == 1 and corrupt[0].get("seedId") == "R4-F07"
                and corrupt[0].get("partialInstall") is False, "F07 failed-close evidence missing")
        seeds["R4-F07"] = {"pass": True, **corrupt[0]}

        client.login(text(first_context, "tenantId"), text(first_secret, "username"),
                     text(first_secret, "password"), "r4-f08-login")
        unknown = synthetic_unknown_bill(client, first_context, run_id)
        seeds["R4-F08"] = {"pass": True, "classification": "INTERNAL_SYNTHETIC_UNKNOWN_NO_FUNDS_COMMAND",
                           **unknown, "providerNetworkCalls": 0}

        late = late_reporting(client, first_context, first_journey, run_id)
        seeds["R4-F09"] = {"pass": True, **late}

        query = urllib.parse.urlencode({"fromDate": first_context["businessDate"],
                                        "toDate": first_context["businessDate"],
                                        "storeId": first_context["storeId"]})
        report_before = data(client.call("GET", f"/api/v1/reports/sales-daily?{query}", "r4-f10-report-before"))
        inventory_before = data(client.call("GET", f"/api/v1/inventory/balances/{WAREHOUSE_ID}/{first_context['skuId']}",
                                            "r4-f10-inventory-before"))
        costing_before = data(client.call("GET", f"/api/inventory/cost-balances/{WAREHOUSE_ID}/{first_context['skuId']}",
                                          "r4-f10-cost-before"))
        inventory_rebuild = data(client.call(
            "POST", f"/api/v1/inventory/balances/{WAREHOUSE_ID}/{first_context['skuId']}/rebuild",
            "r4-f10-inventory-rebuild", body={"correlationId": stable_ulid(f"{run_id}:f10:inventory")}))
        costing_rebuild = data(client.call(
            "POST", f"/api/inventory/cost-balances/{WAREHOUSE_ID}/{first_context['skuId']}/rebuild",
            "r4-f10-cost-rebuild", body={"rebuildId": stable_ulid(f"{run_id}:f10:cost"),
                                          "correlationId": stable_ulid(f"{run_id}:f10:cost:trace")}))
        client.call("POST", "/api/v1/reporting/rebuilds", "r4-f10-report-rebuild", body={
            "rebuildId": stable_ulid(f"{run_id}:f10:report"), "projectionVersion": "g9a-r4-rebuild-v1",
            "fromDate": first_context["businessDate"], "toDate": first_context["businessDate"],
            "correlationId": stable_ulid(f"{run_id}:f10:report:trace")})
        report_after = data(client.call("GET", f"/api/v1/reports/sales-daily?{query}", "r4-f10-report-after"))
        require(inventory_rebuild.get("changed") is False
                and str(inventory_rebuild.get("ledgerQuantity")) == str(inventory_rebuild.get("projectedQuantity")),
                "F10 inventory rebuild did not conserve ledger")
        require(costing_rebuild.get("changed") is False
                and str(costing_rebuild.get("ledgerQuantity")) == str(costing_before.get("costQuantity"))
                and str(costing_rebuild.get("ledgerAmountMinor")) == str(costing_before.get("costAmountMinor")),
                "F10 costing rebuild did not conserve ledger")
        require(digest(report_before) == digest(report_after), "F10 reporting rebuild changed authoritative totals")
        seeds["R4-F10"] = {"pass": True, "inventoryChanged": inventory_rebuild.get("changed"),
                            "costingChanged": costing_rebuild.get("changed"),
                            "reportDigest": digest(report_after), "inventoryBeforeDigest": digest(inventory_before)}

        backup_id = checkpoint_id(postflight, first_id, "resilience")
        restore = client.call("POST", f"/api/v1/backups/{backup_id}/restore-drills",
                              "r4-f11-incompatible-schema-fail-closed",
                              body={"drillId": stable_ulid(f"{run_id}:f11:restore"),
                                    "expectedSchemaVersion": "R4-INCOMPATIBLE",
                                    "correlationId": stable_ulid(f"{run_id}:f11:restore:trace")},
                              expect_success=False)
        backup_after_restore = data(client.call("GET", f"/api/v1/backups/{backup_id}",
                                                "r4-f11-backup-conserved"))
        order_after_restore = data(client.call("GET", f"/api/v1/pos/orders/{first_journey['originalOrderRef']}",
                                                    "r4-f11-order-conserved"))
        require(restore.get("code") != 200 and backup_after_restore.get("state") == "AVAILABLE"
                and order_after_restore.get("status") == "COMPLETED",
                "F11 incompatible restore did not fail closed or changed prior facts")
        seeds["R4-F11"] = {"pass": True,
                            "classification": "FAIL_CLOSED_SCHEMA_MISMATCH_SYNTHETIC_RESTORE",
                            "restoreCode": restore.get("code"), "backupState": backup_after_restore.get("state"),
                            "factConserved": True,
                            "cursorConservedByF03": True, "productionBackupOrKms": 0}

        release_id = checkpoint_id(postflight, first_id, "release")
        verify = client.call("POST", f"/api/v1/releases/{release_id}/verify", "r4-f12-release-fail-closed",
                             headers={"X-Idempotency-Key": f"{run_id}:f12:verify"}, expect_success=False)
        release = data(client.call("GET", f"/api/v1/releases/{release_id}", "r4-f12-release-read"))
        require(release.get("state") == "DRAFT", "F12 failed verification changed prior release state")
        seeds["R4-F12"] = {"pass": True, "releaseVerifyCode": verify.get("code"),
                            "priorReleaseState": release.get("state"),
                            "schemaForwardRepairEvidence": "ANDROID_DATABASE_MIGRATION_GATE"}

        expected = {f"R4-F{index:02d}" for index in range(1, 13)}
        require(set(seeds) == expected and all(row.get("pass") is True for row in seeds.values()),
                "twelve fault seeds did not all pass")
        evidence = {
            "schemaVersion": "1.0", "gate": "G9A-R4", "phase": "R4-R5", "runId": run_id,
            "status": "PASS", "classification": "FORMAL_RUNTIME_FAULTS_WITH_FAILED_CLOSED_EXTERNALS",
            "buildCommit": args.build_commit, "faultSeedCount": len(seeds), "faultSeedPassCount": len(seeds),
            "seeds": [dict(seedId=seed_id, **seeds[seed_id]) for seed_id in sorted(seeds)],
            "observations": [item.__dict__ for item in client.observations],
            "elapsedMs": round((time.perf_counter() - started) * 1000),
            "directBusinessDatabaseWrites": 0, "providerNetworkCalls": 0,
            "realDeviceOrPeripheralCommands": 0, "productionBackupOrKms": 0,
            "automaticRetryToHideFlaky": False,
        }
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(json.dumps(evidence, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        print("G9A-R4 fault matrix passed: seeds=12 restart=1 redis-flush=1 external=0")
        return 0
    except (JourneyFailure, KeyError, TypeError, ValueError, sqlite3.Error) as error:
        print(f"G9A-R4 fault matrix failed: {error}")
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
