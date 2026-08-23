#!/usr/bin/env python3
"""T2-MEM-003 串行准入、Owner 主权、跨端一致性与外部证据边界门禁。"""
from __future__ import annotations

import argparse
import csv
import json
import pathlib
import re
import subprocess

ROOT = pathlib.Path(__file__).resolve().parents[1]
GATE = "T2-GATE7D-SPRINT-S22B-MEM003"
BASELINE = "fd474767c182cbdb5c3df0a9e2e4688371f0587f"


def fail(condition: bool, message: str) -> None:
    if not condition:
        raise SystemExit(f"T2-GATE7D-MEM003 ERROR: {message}")


def read(path: str) -> str:
    return (ROOT / path).read_text(encoding="utf-8", errors="strict")


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--output")
    args = parser.parse_args()

    ancestor = subprocess.run(
        ["git", "merge-base", "--is-ancestor", BASELINE, "HEAD"], cwd=ROOT, check=False
    )
    fail(ancestor.returncode == 0, "准备封存提交不是当前 HEAD 祖先")

    with (ROOT / "docs/governance/rtm.csv").open(encoding="utf-8", newline="") as handle:
        rows = {row["requirement_id"]: row for row in csv.DictReader(handle)}
    fail(rows["T2-MEM-003"]["status"] in {"IN_PROGRESS", "VERIFIED"}, "MEM003 状态越界")
    for requirement in ("T2-SAA-001", "T2-SUB-001", "T2-SVC-001"):
        fail(rows[requirement]["status"] == "DRAFT", f"{requirement} 被提前准入")
    for requirement in ("T2-PAY-002", "T2-HWD-001", "T2-PRN-001", "T2-PAR-001"):
        fail(rows[requirement]["status"] == "BLOCKED", f"{requirement} 阻断状态漂移")
    for requirement in ("T2-JSH-001", "T2-LIC-001"):
        fail(rows[requirement]["status"] == "DEFERRED", f"{requirement} 延后状态漂移")

    adr = read("docs/adr/ADR-053-gate7d-member-benefit-price-prep.md")
    fail(re.search(r"(?im)^-?\s*状态[：:]\s*Accepted\s*$", adr) is not None, "ADR-053 未 Accepted")
    admission = json.loads(read("contracts/t2/gate7d-mem003/mem003-admission.json"))
    fail(admission["status"] == rows["T2-MEM-003"]["status"], "RTM 与准入状态不一致")
    expected_order = ["MEMBER", "PRICING", "PROMOTION", "ORDER_REFUND", "PACKAGE_POS_WEB", "CROSS_PLATFORM_E2E"]
    fail(admission["serialOrder"] == expected_order, "串行 Owner 顺序漂移")
    fail(admission["defaultEnabled"] is False, "三业态默认关闭边界漂移")
    fail(admission["defaultCombinationPolicy"] == "BEST_PRICE", "默认组合策略漂移")
    fail(admission["stackingRequiresDoubleOptIn"] is True, "双向显式叠加约束漂移")
    external = admission["externalExecution"]
    fail(all(value == 0 for value in external.values()), "出现外部执行证据")
    for stage in expected_order[:-1]:
        fail(admission["stageStatus"].get(stage) == "VERIFIED_LOCAL", f"前置阶段未独立验证: {stage}")
    final_stage = admission["stageStatus"].get("CROSS_PLATFORM_E2E")
    fail(final_stage in {"IN_PROGRESS", "VERIFIED_LOCAL"}, "最终阶段状态越界")

    vectors = json.loads(read("contracts/t2/gate7d-mem003/member-benefit-price-vectors.json"))
    fail(vectors["status"] == "ACTIVE_SYNTHETIC_REGRESSION", "固定向量未激活")
    fail(vectors["evidenceBoundary"] == "INTERNAL_SYNTHETIC_ONLY", "向量证据越界")
    cases = vectors["vectors"]
    ids = {item["id"] for item in cases}
    fail(len(cases) == 40 and len(ids) == 40, "固定向量不是唯一 40 项")
    fail(ids == {f"MBP-{index:03d}" for index in range(1, 41)}, "固定向量 ID 不连续")
    calculations = [item for item in cases if item["mode"] == "CALCULATION"]
    fail(len(calculations) >= 10, "跨端可执行金额向量少于 10")
    for item in calculations:
        expected = item.get("expected", {})
        fail(expected.get("grossAmountMinor") == expected.get("discountAmountMinor", 0)
             + expected.get("payableAmountMinor", -1), f"金额向量不守恒: {item['id']}")

    required = [
        "docs/governance/CR-T2G7D-005_member-benefit-price-scope.md",
        "contracts/t2/gate7d-mem003/openapi-member-benefit-price-v1.yaml",
        "contracts/t2/gate7d-mem003/member-benefit-events-v1.schema.json",
        "server/ruoyi-modules/jshpos-member/src/main/java/com/jingshanghui/pos/member/application/service/MemberBenefitService.java",
        "server/ruoyi-modules/jshpos-catalog/src/main/java/com/jingshanghui/pos/catalog/application/service/MemberPriceService.java",
        "server/ruoyi-modules/jshpos-promotion/src/main/java/com/jingshanghui/pos/promotion/domain/MemberBenefitCombinationEngine.java",
        "server/ruoyi-modules/jshpos-promotion/src/main/java/com/jingshanghui/pos/promotion/application/service/MemberBenefitPackageService.java",
        "server/ruoyi-modules/jshpos-order/src/main/resources/db/migration/V202608230079__gate7d_order_member_benefit_binding.sql",
        "server/ruoyi-admin/src/test/java/org/dromara/test/MemberBenefitMigrationMySqlIT.java",
        "server/ruoyi-modules/jshpos-integration/src/main/resources/db/migration/beforeEachMigrate__repair_gate4c_gate7c_menu_ids.sql",
        "server/ruoyi-modules/jshpos-integration/src/test/java/com/jingshanghui/pos/integration/infrastructure/migration/MenuIdCollisionForwardRepairPolicyTest.java",
        "pos-flutter/lib/infrastructure/local_database/gate7d_member_benefit_schema.dart",
        "pos-flutter/lib/features/promotion/infrastructure/member_benefit_package_installer.dart",
        "pos-flutter/lib/features/promotion/domain/member_benefit_engine.dart",
        "pos-flutter/test/gate7d/member_benefit_cross_platform_vector_test.dart",
        "pos-flutter/test/gate7d/member_benefit_runtime_test.dart",
        "admin-web/src/views/operations/components/MemberBenefitPolicyPanel.vue",
        "admin-web/src/api/member-benefit/contract.ts",
    ]
    for item in required:
        fail((ROOT / item).is_file(), f"缺少正式实现或证据文件: {item}")

    migrations = {
        75: "server/ruoyi-modules/jshpos-member/src/main/resources/db/migration/V202608230075__gate7d_member_benefit.sql",
        76: "server/ruoyi-modules/jshpos-member/src/main/resources/db/migration/V202608230076__gate7d_member_benefit_permissions.sql",
        77: "server/ruoyi-modules/jshpos-catalog/src/main/resources/db/migration/V202608230077__gate7d_member_price.sql",
        78: "server/ruoyi-modules/jshpos-promotion/src/main/resources/db/migration/V202608230078__gate7d_member_benefit_promotion.sql",
        79: "server/ruoyi-modules/jshpos-order/src/main/resources/db/migration/V202608230079__gate7d_order_member_benefit_binding.sql",
        80: "server/ruoyi-modules/jshpos-promotion/src/main/resources/db/migration/V202608230080__gate7d_member_benefit_package.sql",
    }
    migration_text = "\n".join(read(path).lower() for path in migrations.values())
    for path in migrations.values():
        fail((ROOT / path).is_file(), f"缺少前向迁移: {path}")
    for table in ("mbr_benefit_version", "mbr_entitlement_snapshot", "prc_member_price_version",
                  "prc_member_price_item", "prm_quote_member_benefit", "prm_member_benefit_package",
                  "ord_member_benefit_binding"):
        fail(f"create table {table}" in migration_text, f"缺少 Owner 表: {table}")
    fail("float" not in migration_text and " double " not in migration_text, "迁移出现浮点数")
    fail(migration_text.count("tenant_id varchar(20)") >= 16, "可信租户列覆盖不足")
    fail(migration_text.count("immutable") >= 8, "只追加/不可变触发器覆盖不足")

    forward_repair = read(
        "server/ruoyi-modules/jshpos-integration/src/main/resources/db/migration/"
        "beforeEachMigrate__repair_gate4c_gate7c_menu_ids.sql"
    ).lower()
    for token in ("9200540", "9200543", "9201540", "9201543",
                  "insert ignore into sys_role_menu", "signal sqlstate '45000'"):
        fail(token in forward_repair, f"组合根菜单冲突前向修复不完整: {token}")

    registry = list(csv.DictReader(read("contracts/t2/gate7d-mem003/persistence-registry.csv").splitlines()))
    fail(len(registry) >= 22, "持久化登记少于 22 个正式对象")
    fail({row["owner"] for row in registry} == {"Member", "Pricing", "Promotion", "Order", "POS"},
         "持久化 Owner 集合漂移")
    fail(all(row["tenant_guard"] in {"TRUSTED_CONTEXT", "TRUSTED_TERMINAL_CONTEXT"} for row in registry),
         "持久化对象缺少可信租户边界")

    mapper_paths = [
        "server/ruoyi-modules/jshpos-member/src/main/resources/mapper/member/BenefitPersistenceMapper.xml",
        "server/ruoyi-modules/jshpos-catalog/src/main/resources/mapper/catalog/MemberPricePersistenceMapper.xml",
        "server/ruoyi-modules/jshpos-promotion/src/main/resources/mapper/promotion/PromotionPersistenceMapper.xml",
        "server/ruoyi-modules/jshpos-order/src/main/resources/mapper/order/PromotedOrderMapper.xml",
    ]
    for path in mapper_paths:
        mapper = re.sub(r"\s+", "", read(path)).lower()
        fail("tenant_id" in mapper and "#{tenantid}" in mapper, f"Mapper 缺少可信租户绑定: {path}")
        fail("select*" not in mapper and "deletefrom" not in mapper, f"Mapper 出现宽查询或物理删除: {path}")

    changed = subprocess.check_output(
        ["git", "diff", "--name-only", f"{BASELINE}...HEAD"], cwd=ROOT, text=True, encoding="utf-8"
    ).splitlines()
    runtime_files = [ROOT / path for path in changed if path.endswith((".java", ".dart")) and "/src/main/" in path.replace("\\", "/") or path.startswith("pos-flutter/lib/")]
    runtime_text = "\n".join(path.read_text(encoding="utf-8") for path in runtime_files if path.is_file())
    fail(re.search(r"\b(?:float|double|Float|Double)\s+[A-Za-z_]", runtime_text) is None,
         "MEM003 正式运行时出现浮点数类型")
    for token in ("RestTemplate", "WebClient", "OkHttpClient", "provider.sdk", "http://", "https://"):
        fail(token not in runtime_text, f"出现禁止的 Provider/外部网络实现: {token}")
    chinese_core = [read(path) for path in required if path.endswith((".java", ".dart"))]
    fail(all(re.search(r"[\u4e00-\u9fff]", text) for text in chinese_core), "核心类缺少有效中文注释")

    flutter_schema = read("pos-flutter/lib/infrastructure/local_database/gate7d_member_benefit_schema.dart")
    for token in ("local_member_benefit_package_slot", "local_member_benefit_level",
                  "local_member_price_item", "local_promotion_quote_member_benefit",
                  "local_order_member_benefit_snapshot"):
        fail(token in flutter_schema, f"SQLite v16 对象缺失: {token}")
    installer = read("pos-flutter/lib/features/promotion/infrastructure/member_benefit_package_installer.dart")
    for token in ("payloadSha256", "verify", "transaction", "requireActive", "tenantId", "storeId"):
        fail(token in installer, f"离线包验签或原子切换缺失: {token}")
    web = read("admin-web/src/views/operations/components/MemberBenefitPolicyPanel.vue")
    fail("tenantId" not in web and "fetch(" not in web and "Math." not in web,
         "Vue 存在租户自报、API 绕过或前端领域重算")

    result = {
        "gate": GATE,
        "status": "PASS",
        "requirementStatus": rows["T2-MEM-003"]["status"],
        "vectorCount": len(cases),
        "calculationVectorCount": len(calculations),
        "migrationVersions": sorted(migrations),
        "persistenceObjectCount": len(registry),
        "externalExecution": external,
        "preservedStates": {key: rows[key]["status"] for key in
                            ("T2-SAA-001", "T2-SUB-001", "T2-SVC-001", "T2-PAY-002",
                             "T2-HWD-001", "T2-PRN-001", "T2-PAR-001", "T2-JSH-001", "T2-LIC-001")},
    }
    if args.output:
        target = pathlib.Path(args.output)
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(result, ensure_ascii=False))


if __name__ == "__main__":
    main()
