#!/usr/bin/env python3
"""Gate 7B 第一批串行准入、外部边界与正式实现静态门禁。"""
from __future__ import annotations

import argparse
import csv
import json
import pathlib
import subprocess


ROOT = pathlib.Path(__file__).resolve().parents[1]
BASELINE = "fd255f45115015fa0bab91f1fd8b1c14c2acc51e"
BRANCH = "t2/gate7b-sprint20-pos-operations"
ORDER = ["T2-POS-010", "T2-POS-011", "T2-ORD-004"]
PRESERVED = {
    "T2-EXG-001": "DRAFT", "T2-PAY-004": "DRAFT", "T2-PAY-002": "BLOCKED",
    "T2-HWD-001": "BLOCKED", "T2-PRN-001": "BLOCKED", "T2-PAR-001": "BLOCKED",
    "T2-UAT-001": "DRAFT", "T2-REL-001": "DRAFT", "T2-JSH-001": "DEFERRED",
    "T2-LIC-001": "DEFERRED",
}
ALLOWED_STAGES = {
    ("IN_PROGRESS", "DRAFT", "DRAFT"): "POS010_IMPLEMENTATION",
    ("VERIFIED", "DRAFT", "DRAFT"): "POS010_INDEPENDENT_VERIFIED",
    ("VERIFIED", "IN_PROGRESS", "DRAFT"): "POS011_IMPLEMENTATION",
    ("VERIFIED", "VERIFIED", "DRAFT"): "POS011_INDEPENDENT_VERIFIED",
    ("VERIFIED", "VERIFIED", "IN_PROGRESS"): "ORD004_IMPLEMENTATION",
    ("VERIFIED", "VERIFIED", "VERIFIED"): "FIRST_BATCH_VERIFIED",
}


def fail(condition: bool, message: str) -> None:
    if not condition:
        raise SystemExit(f"T2-GATE7B ERROR: {message}")


def git(*args: str) -> str:
    completed = subprocess.run(["git", "-c", "core.quotepath=false", *args], cwd=ROOT,
                               capture_output=True, text=True, encoding="utf-8", check=False)
    fail(completed.returncode == 0, completed.stderr.strip() or f"git {' '.join(args)} failed")
    return completed.stdout.strip()


def rtm() -> dict[str, dict[str, str]]:
    with (ROOT / "docs/governance/rtm.csv").open(encoding="utf-8", newline="") as stream:
        rows = list(csv.DictReader(stream))
    ids = [row["requirement_id"] for row in rows]
    fail(len(ids) == len(set(ids)), "RTM Requirement ID 重复")
    return {row["requirement_id"]: row for row in rows}


def text(path: str) -> str:
    target = ROOT / path
    fail(target.is_file(), f"缺少 {path}")
    return target.read_text(encoding="utf-8")


def validate() -> dict:
    rows = rtm()
    contract = json.loads(text("contracts/t2/gate7b/gate7b-admission.json"))
    fail(contract.get("baselineCommit") == BASELINE and contract.get("branch") == BRANCH,
         "基线或分支契约漂移")
    fail(contract.get("serialOrder") == ORDER, "串行顺序漂移")
    statuses = tuple(rows[item]["status"] for item in ORDER)
    fail(statuses in ALLOWED_STAGES, f"非法串行状态: {statuses}")
    stage = ALLOWED_STAGES[statuses]
    for requirement_id, status in zip(ORDER, statuses):
        fail(contract["requirements"][requirement_id]["status"] == status,
             f"契约与 RTM 状态不一致: {requirement_id}")
    for requirement_id, status in PRESERVED.items():
        fail(rows[requirement_id]["status"] == status, f"外部/后续状态漂移: {requirement_id}")
        fail(contract["preservedStates"][requirement_id] == status,
             f"契约外部/后续状态漂移: {requirement_id}")
    for name, value in contract["externalExecution"].items():
        fail(value is False if name == "commercialClaimAllowed" else value == 0,
             f"外部执行必须为零: {name}")
    fail(git("merge-base", "--is-ancestor", BASELINE, "HEAD") == "", "Gate 7A 封板提交不是祖先")

    required_pos010 = [
        "docs/t2-gate7b/02_T2_POS010设计准入.md",
        "contracts/t2/gate7b/openapi-pos-operations-v1.yaml",
        "contracts/t2/gate7b/shift-cash-operation-events-v1.schema.json",
        "pos-flutter/lib/infrastructure/local_database/gate7b_cash_operation_schema.dart",
        "pos-flutter/test/gate7b/shift_cash_operation_test.dart",
        "server/ruoyi-modules/jshpos-order/src/main/resources/db/migration/V202608210052__gate7b_shift_cash_drawer.sql",
    ]
    for path in required_pos010:
        text(path)
    sqlite = text(required_pos010[3])
    mysql = text(required_pos010[5]).lower()
    checkout = text("pos-flutter/lib/features/checkout/application/checkout_local_service.dart")
    sync = text("server/ruoyi-modules/jshpos-sync/src/main/java/com/jingshanghui/pos/sync/domain/SyncRules.java")
    fail("BLOCKED_EXTERNAL" in sqlite and "append-only" in sqlite, "SQLite 钱箱阻断或只追加保护缺失")
    fail("blocked_external" in mysql and "append-only" in mysql and "float" not in mysql and "double" not in mysql,
         "MySQL 钱箱阻断、只追加或精度边界失效")
    for token in ("recordShiftCashMovement", "requestNoSaleDrawer", "shift.cash-movement.recorded.v1",
                  "shift.drawer-requested.v1"):
        fail(token in checkout or token in sync, f"POS010 运行时/同步契约缺失: {token}")
    changed = git("diff", "--name-only", BASELINE).splitlines()
    fail(not any("provider" in item.lower() and item.startswith(("server/", "pos-flutter/")) for item in changed),
         "本批次禁止新增 Provider 运行时")
    forbidden = ["V202608210053", "V202608210054"]
    if stage in {"POS010_IMPLEMENTATION", "POS010_INDEPENDENT_VERIFIED"}:
        fail(not any(any(version in item for version in forbidden) for item in changed),
             "POS010 未验证前不得预建 POS011/ORD004 迁移")
    if stage in {"POS011_IMPLEMENTATION", "POS011_INDEPENDENT_VERIFIED",
                 "ORD004_IMPLEMENTATION", "FIRST_BATCH_VERIFIED"}:
        required_pos011 = [
            "docs/t2-gate7b/04_T2_POS011设计准入.md",
            "contracts/t2/gate7b/receipt-events-v1.schema.json",
            "pos-flutter/lib/infrastructure/local_database/gate7b_receipt_schema.dart",
            "server/ruoyi-modules/jshpos-order/src/main/resources/db/migration/V202608210053__gate7b_receipt_reprint.sql",
        ]
        for path in required_pos011:
            text(path)
        receipt_sqlite = text(required_pos011[2])
        receipt_mysql = text(required_pos011[3]).lower()
        for token in ("BLOCKED_EXTERNAL", "immutable", "append-only"):
            fail(token in receipt_sqlite, f"POS011 SQLite 边界缺失: {token}")
        for token in ("blocked_external", "immutable", "append-only"):
            fail(token in receipt_mysql, f"POS011 MySQL 边界缺失: {token}")
        for token in ("requestReceiptReprint", "receipt.document-frozen.v1",
                      "receipt.reprint-requested.v1"):
            fail(token in checkout or token in sync, f"POS011 运行时/同步契约缺失: {token}")
    if stage in {"POS011_IMPLEMENTATION", "POS011_INDEPENDENT_VERIFIED"}:
        fail(not any("V202608210054" in item for item in changed),
             "POS011 未验证前不得预建 ORD004 迁移")
    return {
        "schemaVersion": "1.0", "gate": "T2-GATE7B-SPRINT-S20-POS-OPERATIONS-FIRST-BATCH",
        "status": "PASS", "stage": stage, "baselineCommit": BASELINE,
        "requirementStatuses": dict(zip(ORDER, statuses)), "preservedStates": PRESERVED,
        "externalExecution": contract["externalExecution"], "changedFileCount": len(changed),
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
