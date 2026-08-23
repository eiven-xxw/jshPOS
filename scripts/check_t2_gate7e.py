#!/usr/bin/env python3
"""T2 Gate 7E 准入、三业态汇总契约与外部证据边界门禁。"""
from __future__ import annotations

import argparse
import csv
import json
import pathlib
import subprocess
from decimal import Decimal, InvalidOperation


ROOT = pathlib.Path(__file__).resolve().parents[1]
BASELINE = "feeecec5e1b438ba46f4225954e950d4e45ceb0c"
REQUIREMENT = "T2-E2E-004"
ACCEPTED_GATE7 = {
    "T2-POS-010", "T2-POS-011", "T2-ORD-004", "T2-EXG-001", "T2-PAY-004",
    "T2-PRD-005", "T2-LBL-001", "T2-RPL-001", "T2-DMT-001", "T2-ONB-001",
    "T2-LOT-001", "T2-CLS-001", "T2-EXC-001", "T2-MEM-003",
}
PRESERVED = {
    "T2-SAA-001": "DRAFT", "T2-SUB-001": "DRAFT", "T2-SVC-001": "DRAFT",
    "T2-PAY-002": "BLOCKED", "T2-HWD-001": "BLOCKED", "T2-PRN-001": "BLOCKED",
    "T2-PAR-001": "BLOCKED", "T2-UAT-001": "DRAFT", "T2-REL-001": "DRAFT",
    "T2-JSH-001": "DEFERRED", "T2-LIC-001": "DEFERRED",
}
INDUSTRIES = {"CONVENIENCE", "SNACK_DISCOUNT", "COMMUNITY_SUPERMARKET"}
EXPECTED_PHASES = {
    "BACKOFFICE_INITIALIZE_AND_MIGRATE", "STORE_ONBOARDING_PREFLIGHT",
    "CATALOG_PRICE_BENEFIT_PACKAGE_PUBLISH", "TERMINAL_LOGIN_AND_SHIFT_OPEN",
    "STANDARD_OR_WEIGHTED_SCAN", "PROMOTION_MEMBER_PRICE_AND_MANUAL_ADJUSTMENT",
    "SUSPEND_AND_RESUME", "CASH_OR_PARTIAL_CASH_SETTLEMENT",
    "OUTBOX_SYNC_AND_ORDER_CONSUMPTION", "INVENTORY_COSTING_AND_REPORTING",
    "PARTIAL_AND_FINAL_RETURN", "EXCHANGE_ORCHESTRATION",
    "PROCUREMENT_STOCKTAKE_TRANSFER_REPLENISHMENT", "DAILY_CLOSE_AND_EXCEPTION_RESOLUTION",
    "SHIFT_CLOSE", "SYNTHETIC_BACKUP_RESTORE_AND_UPGRADE_FORWARD_REPAIR",
}
EXPECTED_INVARIANTS = {
    "ORDER_AMOUNT_CONSERVATION", "TENDER_SHARE_CONSERVATION",
    "REFUND_ORIGINAL_SNAPSHOT_LIMIT", "PROMOTION_MEMBER_ALLOCATION_CONSERVATION",
    "INVENTORY_LEDGER_REBUILD", "LOT_FEFO_AND_RETURN_LINEAGE", "COST_LEDGER_REBUILD",
    "REPORT_OWNER_DAILY_RECONCILIATION", "DAILY_CLOSE_APPEND_ONLY_CORRECTION",
    "TRUSTED_TENANT_STORE_TERMINAL_CONTEXT", "NO_EXTERNAL_SUCCESS_FABRICATION",
}


def require(condition: bool, message: str) -> None:
    if not condition:
        raise SystemExit(f"T2-GATE7E ERROR: {message}")


def load_json(path: str) -> dict:
    return json.loads((ROOT / path).read_text(encoding="utf-8"))


def load_rtm() -> dict[str, dict[str, str]]:
    with (ROOT / "docs/governance/rtm.csv").open(encoding="utf-8", newline="") as handle:
        return {row["requirement_id"]: row for row in csv.DictReader(handle)}


def exact_decimal(value: object, field: str) -> Decimal:
    try:
        number = Decimal(str(value))
    except InvalidOperation as exception:
        raise SystemExit(f"T2-GATE7E ERROR: 非法精确数值 {field}") from exception
    require(number.is_finite() and number.as_tuple().exponent >= -6, f"数值精度越界: {field}")
    return number


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--output")
    args = parser.parse_args()

    ancestor = subprocess.run(
        ["git", "merge-base", "--is-ancestor", BASELINE, "HEAD"], cwd=ROOT, check=False
    )
    require(ancestor.returncode == 0, "MEM003 最终封存提交不是 HEAD 祖先")

    rows = load_rtm()
    for requirement in ACCEPTED_GATE7:
        require(rows[requirement]["status"] == "ACCEPTED", f"前置需求未 ACCEPTED: {requirement}")
    for requirement, expected in PRESERVED.items():
        require(rows[requirement]["status"] == expected, f"外部或后续状态漂移: {requirement}")
    require(rows[REQUIREMENT]["status"] in {"IN_PROGRESS", "VERIFIED"}, "E2E004 状态越界")

    admission = load_json("contracts/t2/gate7e/e2e004-admission.json")
    require(admission["requirementId"] == REQUIREMENT, "准入 Requirement 身份漂移")
    require(admission["status"] == rows[REQUIREMENT]["status"], "准入与 RTM 状态不一致")
    require(admission["baselineCommit"] == BASELINE, "准入基线漂移")
    require(set(admission["predecessors"]) == ACCEPTED_GATE7, "实际获批前置清单不完整")
    require(set(admission["industries"]) == INDUSTRIES, "三业态范围漂移")
    topology = admission["syntheticTopology"]
    require(topology["tenantCount"] == 2 and topology["storeCount"] >= 6
            and topology["terminalCount"] >= 6, "合成拓扑不足")
    require(admission["businessFactOwnership"] == "READ_ONLY_EVIDENCE_REFERENCES_ONLY",
            "汇总器越权拥有业务事实")
    require(admission["newDomainAlgorithmAllowed"] is False
            and admission["newBusinessTableOrMigrationAllowed"] is False,
            "E2E004 不得新增领域算法或业务迁移")
    require(admission["defectPolicy"] == {
        "p0OpenMax": 0, "p1OpenMax": 0, "flakyAllowed": False, "automaticRetryAllowed": False
    }, "P0/P1 或 Flaky 策略漂移")
    require(admission["preservedStates"] == PRESERVED, "准入状态边界漂移")
    require(all(value == 0 for value in admission["externalExecution"].values()), "出现外部执行")

    vector = load_json("contracts/t2/gate7e/internal-v1-business-complete-v1.json")
    require(vector["requirementId"] == REQUIREMENT, "汇总向量 Requirement 漂移")
    require(vector["evidenceLevel"] == "INTERNAL_V1_BUSINESS_COMPLETE_CANDIDATE",
            "证据等级越界")
    journeys = vector["journeys"]
    require(len(journeys) == 6 and len({item["id"] for item in journeys}) == 6, "旅程必须唯一六条")
    require({item["industry"] for item in journeys} == INDUSTRIES, "旅程未覆盖三业态")
    require({item["tenant"] for item in journeys} == {"TENANT_A", "TENANT_B"}, "旅程租户漂移")
    require(len({item["store"] for item in journeys}) == 6
            and len({item["terminal"] for item in journeys}) == 6, "门店或终端不唯一")
    require(all(item["lotEnabled"] == (item["industry"] == "COMMUNITY_SUPERMARKET")
                for item in journeys), "批次能力没有按行业模板失败关闭")
    for journey in journeys:
        facts = journey["facts"]
        discount = facts["memberDiscountMinor"] + facts["promotionDiscountMinor"]
        require(facts["grossMinor"] - discount + facts["surchargeMinor"]
                == facts["receivableMinor"], f"订单金额不守恒: {journey['id']}")
        require(facts["cashMinor"] == facts["receivableMinor"], f"现金份额不守恒: {journey['id']}")
        require(facts["partialRefundMinor"] + facts["finalRefundMinor"]
                == facts["receivableMinor"], f"原快照退款不守恒: {journey['id']}")
        opening = exact_decimal(facts["openingQuantity"], f"{journey['id']}.opening")
        sale = exact_decimal(facts["saleQuantity"], f"{journey['id']}.sale")
        returned = exact_decimal(facts["returnQuantity"], f"{journey['id']}.return")
        closing = exact_decimal(facts["closingQuantity"], f"{journey['id']}.closing")
        require(opening - sale + returned == closing, f"库存数量不守恒: {journey['id']}")
        unit_cost = exact_decimal(facts["unitCostMinor"], f"{journey['id']}.unitCost")
        sale_cost = exact_decimal(facts["saleCostMinor"], f"{journey['id']}.saleCost")
        return_cost = exact_decimal(facts["returnCostMinor"], f"{journey['id']}.returnCost")
        require(unit_cost * sale == sale_cost == return_cost, f"原成本快照不守恒: {journey['id']}")
        lot_quantity = exact_decimal(facts["lotAllocationQuantity"], f"{journey['id']}.lot")
        require(lot_quantity == (sale if journey["lotEnabled"] else Decimal("0")),
                f"批次数量与行业模板不一致: {journey['id']}")
    require(set(vector["requiredPhases"]) == EXPECTED_PHASES, "正式旅程阶段不完整")
    require(set(vector["requiredInvariants"]) == EXPECTED_INVARIANTS, "守恒清单不完整")
    seeds = vector["failureSeeds"]
    require(len(seeds) == 16 and {item["seed"] for item in seeds}
            == {f"G7E-{index:04d}" for index in range(1, 17)}, "固定故障 seed 不完整")
    require(vector["defectLedger"] == {"p0": [], "p1": []}, "存在开放 P0/P1")
    require(all(value == 0 for value in vector["externalExecution"].values()), "向量出现外部执行")
    require(vector["commercialClaimAllowed"] is False, "内部候选不得形成商业声明")
    require((ROOT / vector["performanceReference"]).is_file(), "性能基线引用缺失")

    required_files = [
        "docs/adr/ADR-054-gate7e-internal-v1-business-complete.md",
        "docs/t2-gate7e/01_T2_E2E004设计准入与验收冻结.md",
        "docs/t2-gate7e/02_T2_E2E004可重复运行手册.md",
        "docs/t2-gate7d-mem003/11_T2_MEM003项目发起人接受记录.md",
        "scripts/run_t2_gate7e_internal_v1_business_complete.py",
        "scripts/build_t2_gate7e_runtime_stack_smoke.py",
        "scripts/build_t2_gate7e_evidence.py",
        ".github/workflows/t2-gate7e.yml",
    ]
    require(all((ROOT / path).is_file() for path in required_files), "治理或接受记录缺失")

    result = {
        "gate": admission["gate"], "status": "PASS",
        "requirementStatus": rows[REQUIREMENT]["status"],
        "acceptedPredecessorCount": len(ACCEPTED_GATE7),
        "journeyCount": len(journeys), "failureSeedCount": len(seeds),
        "evidenceCeiling": vector["evidenceLevel"],
        "preservedStates": PRESERVED, "externalExecution": admission["externalExecution"],
    }
    if args.output:
        target = ROOT / args.output
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(result, ensure_ascii=False))


if __name__ == "__main__":
    main()
