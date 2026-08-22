#!/usr/bin/env python3
"""T2-PRD-005 准入、范围和外部证据边界门禁。"""
from __future__ import annotations
import argparse
import csv
import json
import pathlib

ROOT = pathlib.Path(__file__).resolve().parents[1]

def fail(condition: bool, message: str) -> None:
    if not condition: raise SystemExit(f"T2-GATE7C-PRD005 ERROR: {message}")

def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--output")
    args = parser.parse_args()
    with (ROOT / "docs/governance/rtm.csv").open(encoding="utf-8", newline="") as handle:
        rows = {row["requirement_id"]: row for row in csv.DictReader(handle)}
    fail(rows["T2-PAY-004"]["status"] == "ACCEPTED", "PAY004 未按发起人指令接受")
    fail(rows["T2-PRD-005"]["status"] in {"IN_PROGRESS", "VERIFIED", "ACCEPTED"}, "PRD005 未准入或越界")
    fail(rows["T2-LBL-001"]["status"] in ({"DRAFT"} if rows["T2-PRD-005"]["status"] != "ACCEPTED"
         else {"DRAFT", "IN_PROGRESS", "VERIFIED"}), "LBL001 准入时序越界")
    for requirement in ("T2-RPL-001", "T2-DMT-001", "T2-ONB-001", "T2-LOT-001"):
        fail(rows[requirement]["status"] == "DRAFT", f"{requirement} 被提前准入")
    for requirement in ("T2-PAY-002", "T2-HWD-001", "T2-PRN-001", "T2-PAR-001"):
        fail(rows[requirement]["status"] == "BLOCKED", f"{requirement} 阻断漂移")
    admission = json.loads((ROOT / "contracts/t2/gate7c-prd005/prd005-admission.json").read_text(encoding="utf-8"))
    fail(admission["requirement"]["status"] == rows["T2-PRD-005"]["status"], "RTM/准入状态不一致")
    fail(all(value == 0 for value in admission["externalExecution"].values() if isinstance(value, int)), "出现外部执行")
    vectors = json.loads((ROOT / "contracts/t2/gate7c-prd005/prd005-fault-vectors.json").read_text(encoding="utf-8"))
    fail(len(vectors["vectors"]) >= 12, "故障向量不足")
    required = [
        "server/ruoyi-modules/jshpos-catalog/src/main/resources/db/migration/V202608220059__gate7c_weighted_barcode.sql",
        "server/ruoyi-modules/jshpos-catalog/src/main/java/com/jingshanghui/pos/catalog/domain/WeightedBarcodeRules.java",
        "server/ruoyi-modules/jshpos-catalog/src/main/java/com/jingshanghui/pos/catalog/application/service/WeightedBarcodeService.java",
        "server/ruoyi-modules/jshpos-catalog/src/main/java/com/jingshanghui/pos/catalog/application/port/WeightedBarcodeSnapshotVerificationPort.java",
        "server/ruoyi-modules/jshpos-catalog/src/main/java/com/jingshanghui/pos/catalog/interfaces/rest/WeightedBarcodeController.java",
        "server/ruoyi-modules/jshpos-order/src/main/resources/db/migration/V202608220060__gate7c_order_measurement_snapshot.sql",
        "server/ruoyi-modules/jshpos-order/src/main/java/com/jingshanghui/pos/order/application/model/PromotedOrderCommands.java",
        "server/ruoyi-modules/jshpos-sync/src/main/java/com/jingshanghui/pos/sync/application/service/PromotedOrderEventDispatcher.java",
        "pos-flutter/lib/features/catalog/domain/weighted_barcode.dart",
        "pos-flutter/lib/infrastructure/local_database/gate7c_weighted_barcode_schema.dart",
        "pos-flutter/test/gate7c/weighted_barcode_runtime_test.dart",
        "contracts/t2/gate7c-prd005/weighted-barcode-golden-vectors-v1.json",
    ]
    for item in required:
        fail((ROOT / item).is_file(), f"缺少正式实现或证据文件: {item}")
    golden = json.loads((ROOT / required[-1]).read_text(encoding="utf-8"))
    fail(golden.get("roundingMode") == "HALF_EVEN", "跨端金标舍入模式漂移")
    fail({case["kind"] for case in golden.get("cases", [])} == {"WEIGHT", "AMOUNT"}, "跨端金标未覆盖两类条码")
    fail(all(len(case["expected"]["parseSha256"]) == 64 for case in golden["cases"]), "跨端摘要金标无效")
    feature_roots = [ROOT / "server/ruoyi-modules/jshpos-catalog/src/main/java/com/jingshanghui/pos/catalog/domain/WeightedBarcodeRules.java",
                     ROOT / "server/ruoyi-modules/jshpos-catalog/src/main/java/com/jingshanghui/pos/catalog/application/service/WeightedBarcodeService.java",
                     ROOT / "server/ruoyi-modules/jshpos-catalog/src/main/java/com/jingshanghui/pos/catalog/application/port/WeightedBarcodeSnapshotVerificationPort.java",
                     ROOT / "server/ruoyi-modules/jshpos-catalog/src/main/java/com/jingshanghui/pos/catalog/interfaces/rest/WeightedBarcodeController.java",
                     ROOT / "pos-flutter/lib/features/catalog",
                     ROOT / "pos-flutter/lib/infrastructure/local_database/gate7c_weighted_barcode_schema.dart"]
    sources = "\n".join(path.read_text(encoding="utf-8", errors="ignore") for base in feature_roots if base.exists()
        for path in ([base] if base.is_file() else base.rglob("*")) if path.is_file() and path.suffix in {".java", ".dart", ".xml"}).lower()
    for token in ("serialport", "bluetooth", "methodchannel('scale", "provider sdk"):
        fail(token not in sources, f"发现未准入外部能力: {token}")
    fail("float " not in sources and "double " not in sources, "计量正式路径出现浮点数")
    result = {
        "gate": "T2-GATE7C-SPRINT-S21A-PRD005",
        "status": "PASS",
        "requirementStatus": rows["T2-PRD-005"]["status"],
        "faultVectorCount": len(vectors["vectors"]),
        "goldenVectorCount": len(golden["cases"]),
        "preservedStates": admission["preservedStates"],
        "externalExecution": admission["externalExecution"],
    }
    if args.output:
        target = pathlib.Path(args.output)
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(result, ensure_ascii=False))

if __name__ == "__main__": main()
