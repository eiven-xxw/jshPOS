#!/usr/bin/env python3
"""校验受控预检签名并生成四方言只前进清理包；不连接或修改数据库。"""
from __future__ import annotations

import argparse
import datetime as dt
import hashlib
import hmac
import json
import os
import pathlib


ROOT = pathlib.Path(__file__).resolve().parents[1]
POLICY = ROOT / "contracts/t2/gate9b-r2/forward-cleanup-policy-v1.json"
KEY_ENV = "JSH_G9A_R2_PREFLIGHT_HMAC"


def canonical(value: dict) -> bytes:
    unsigned = {key: item for key, item in value.items() if key != "signature"}
    return json.dumps(unsigned, ensure_ascii=False, sort_keys=True, separators=(",", ":")).encode()


def require(condition: bool, message: str) -> None:
    if not condition:
        raise ValueError(message)


def parse_time(value: str) -> dt.datetime:
    parsed = dt.datetime.fromisoformat(value.replace("Z", "+00:00"))
    require(parsed.tzinfo is not None, "预检时间必须包含时区")
    return parsed.astimezone(dt.timezone.utc)


def sql_for(dialect: str, ids: list[int]) -> str:
    values = ", ".join(str(item) for item in ids)
    header = (
        "-- G9A-R2 只前进清理：仅可由已验签且未过期的预检生成。\n"
        "-- 禁止人工扩展目标；执行前后均须保留数据库快照摘要与本文件 SHA-256。\n"
    )
    if dialect == "mysql":
        return header + f"""START TRANSACTION;
DELETE FROM sys_role_menu WHERE menu_id IN ({values});
DELETE FROM sys_menu WHERE menu_id IN ({values});
COMMIT;
DROP TABLE IF EXISTS test_tree;
DROP TABLE IF EXISTS test_demo;
"""
    if dialect == "postgres":
        return header + f"""BEGIN;
DELETE FROM sys_role_menu WHERE menu_id IN ({values});
DELETE FROM sys_menu WHERE menu_id IN ({values});
DROP TABLE IF EXISTS test_tree;
DROP TABLE IF EXISTS test_demo;
COMMIT;
"""
    if dialect == "sqlserver":
        return header + f"""SET XACT_ABORT ON;
BEGIN TRANSACTION;
DELETE FROM sys_role_menu WHERE menu_id IN ({values});
DELETE FROM sys_menu WHERE menu_id IN ({values});
IF OBJECT_ID(N'dbo.test_tree', N'U') IS NOT NULL DROP TABLE dbo.test_tree;
IF OBJECT_ID(N'dbo.test_demo', N'U') IS NOT NULL DROP TABLE dbo.test_demo;
COMMIT TRANSACTION;
"""
    return header + f"""DELETE FROM sys_role_menu WHERE menu_id IN ({values});
DELETE FROM sys_menu WHERE menu_id IN ({values});
BEGIN EXECUTE IMMEDIATE 'DROP TABLE test_tree PURGE'; EXCEPTION WHEN OTHERS THEN IF SQLCODE != -942 THEN RAISE; END IF; END;
/
BEGIN EXECUTE IMMEDIATE 'DROP TABLE test_demo PURGE'; EXCEPTION WHEN OTHERS THEN IF SQLCODE != -942 THEN RAISE; END IF; END;
/
COMMIT;
"""


def validate(document: dict, policy: dict, now: dt.datetime) -> None:
    require(document.get("schemaVersion") == "1.0", "预检 Schema 版本不匹配")
    require(document.get("findingId") == policy["findingId"], "缺陷标识不匹配")
    require(document.get("dialect") in policy["supportedDialects"], "数据库方言不受支持")
    require(document.get("signatureAlgorithm") == "HMAC-SHA256", "签名算法不受支持")
    key = os.environ.get(KEY_ENV, "").encode()
    require(len(key) >= 32, f"缺少至少32字节的受控环境变量 {KEY_ENV}")
    expected = hmac.new(key, canonical(document), hashlib.sha256).hexdigest()
    require(hmac.compare_digest(expected, str(document.get("signature", ""))), "预检签名无效")
    require(len(str(document.get("snapshotSha256", ""))) == 64, "数据库快照摘要无效")
    observed = parse_time(document["observedAt"])
    expires = parse_time(document["expiresAt"])
    require(observed <= now <= expires, "预检尚未生效或已经过期")
    require(expires - observed <= dt.timedelta(minutes=policy["execution"]["requiresFreshPreflightMinutes"]), "预检有效期超过策略上限")
    counts = policy["baselineCounts"]
    require(document.get("targetMenuCount") == counts["targetMenus"], "目标菜单数量漂移")
    require(document.get("targetRoleBindingCount") == counts["targetRoleBindings"], "角色绑定数量漂移")
    require(document.get("demoTableCount") == counts["demoTables"], "演示表数量漂移")
    require(document.get("demoRowCount") == counts["demoRows"], "演示数据数量漂移")
    for field in ("targetMenuMismatchCount", "nonBaselineRoleBindingCount", "nonBaselineDemoRowCount", "ownerFactReferenceCount", "schemaDriftCount"):
        require(document.get(field) == 0, f"{field} 非零，必须失败关闭并人工评审")


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("preflight", type=pathlib.Path)
    parser.add_argument("--output-dir", type=pathlib.Path, required=True)
    parser.add_argument("--now", help="测试专用 ISO-8601 当前时间；正式运行省略")
    args = parser.parse_args()
    policy = json.loads(POLICY.read_text(encoding="utf-8"))
    document = json.loads(args.preflight.read_text(encoding="utf-8"))
    now = parse_time(args.now) if args.now else dt.datetime.now(dt.timezone.utc)
    validate(document, policy, now)
    sql = sql_for(document["dialect"], policy["targetMenuIds"])
    args.output_dir.mkdir(parents=True, exist_ok=True)
    sql_path = args.output_dir / f"g9a-r2-forward-cleanup-{document['dialect']}.sql"
    sql_path.write_text(sql, encoding="utf-8", newline="\n")
    admission = {
        "schemaVersion": "1.0",
        "findingId": policy["findingId"],
        "environmentRef": document["environmentRef"],
        "dialect": document["dialect"],
        "preflightSha256": hashlib.sha256(canonical(document)).hexdigest(),
        "snapshotSha256": document["snapshotSha256"],
        "cleanupSqlSha256": hashlib.sha256(sql.encode()).hexdigest(),
        "expiresAt": document["expiresAt"],
        "custodian": document["custodian"],
        "decision": "EXECUTION_PACKAGE_CANDIDATE_REQUIRES_CHANGE_APPROVAL",
        "rollback": policy["execution"]["rollback"],
    }
    admission_path = args.output_dir / "admission.json"
    admission_path.write_text(json.dumps(admission, ensure_ascii=False, sort_keys=True, indent=2) + "\n", encoding="utf-8")
    print(f"G9A-R2 FORWARD CLEANUP PACKAGE OK: dialect={document['dialect']} sqlSha256={admission['cleanupSqlSha256']}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
