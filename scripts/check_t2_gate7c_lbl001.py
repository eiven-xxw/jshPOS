#!/usr/bin/env python3
"""T2-LBL-001 准入、运行时完整性与外部证据边界门禁。"""
from __future__ import annotations

import argparse
import csv
import json
import pathlib

ROOT = pathlib.Path(__file__).resolve().parents[1]


def fail(condition: bool, message: str) -> None:
    if not condition:
        raise SystemExit(f"T2-GATE7C-LBL001 ERROR: {message}")


def source_text(paths: list[pathlib.Path]) -> str:
    files: list[pathlib.Path] = []
    for base in paths:
        files.extend([base] if base.is_file() else [p for p in base.rglob("*") if p.is_file()])
    return "\n".join(
        path.read_text(encoding="utf-8", errors="ignore") for path in files
        if path.suffix.lower() in {".java", ".xml", ".ts", ".vue"}
    ).lower()


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--output")
    args = parser.parse_args()
    with (ROOT / "docs/governance/rtm.csv").open(encoding="utf-8", newline="") as handle:
        rows = {row["requirement_id"]: row for row in csv.DictReader(handle)}
    fail(rows["T2-PRD-005"]["status"] == "ACCEPTED", "PRD005 未按项目发起人确认接受")
    fail(rows["T2-LBL-001"]["status"] in {"IN_PROGRESS", "VERIFIED"}, "LBL001 未准入或越界")
    for requirement in ("T2-RPL-001", "T2-DMT-001", "T2-ONB-001", "T2-LOT-001"):
        fail(rows[requirement]["status"] == "DRAFT", f"{requirement} 被提前准入")
    for requirement in ("T2-PAY-002", "T2-HWD-001", "T2-PRN-001", "T2-PAR-001"):
        fail(rows[requirement]["status"] == "BLOCKED", f"{requirement} 阻断状态漂移")

    admission = json.loads((ROOT / "contracts/t2/gate7c-lbl001/lbl001-admission.json").read_text(encoding="utf-8"))
    fail(admission["requirement"]["status"] == rows["T2-LBL-001"]["status"], "RTM/准入状态不一致")
    fail(all(value == 0 for value in admission["externalExecution"].values() if isinstance(value, int)), "出现外部执行")
    fail(admission["externalExecution"].get("commercialClaimAllowed") is False, "商用声明边界漂移")
    fail(admission["externalExecution"].get("realPrintSuccesses") == 0, "出现真实打印成功占位")
    fail(len(admission.get("allowedTemplateFields", [])) == 11, "模板批准字段集漂移")

    vectors = json.loads((ROOT / "contracts/t2/gate7c-lbl001/lbl001-fault-vectors.json").read_text(encoding="utf-8"))
    fail(len(vectors.get("vectors", [])) >= 16, "故障向量少于 16")
    fail(vectors.get("printerEvidence") == "BLOCKED", "打印证据越级")

    required = [
        "contracts/t2/gate7c-lbl001/openapi-shelf-label-v1.yaml",
        "contracts/t2/gate7c-lbl001/shelf-label-events-v1.schema.json",
        "server/ruoyi-modules/jshpos-catalog/src/main/resources/db/migration/V202608220061__gate7c_shelf_label.sql",
        "server/ruoyi-modules/jshpos-catalog/src/main/java/com/jingshanghui/pos/catalog/domain/ShelfLabelRules.java",
        "server/ruoyi-modules/jshpos-catalog/src/main/java/com/jingshanghui/pos/catalog/application/service/ShelfLabelService.java",
        "server/ruoyi-modules/jshpos-catalog/src/main/java/com/jingshanghui/pos/catalog/application/port/ShelfLabelPrintPort.java",
        "server/ruoyi-modules/jshpos-catalog/src/main/java/com/jingshanghui/pos/catalog/infrastructure/device/UnavailableShelfLabelPrintAdapter.java",
        "server/ruoyi-modules/jshpos-catalog/src/main/java/com/jingshanghui/pos/catalog/interfaces/rest/ShelfLabelController.java",
        "admin-web/src/views/catalog/components/ShelfLabelPanel.vue",
        "admin-web/src/views/catalog/__tests__/shelf-label-boundary.spec.ts",
    ]
    for item in required:
        fail((ROOT / item).is_file(), f"缺少正式实现或证据文件: {item}")

    source = source_text([
        ROOT / "server/ruoyi-modules/jshpos-catalog/src/main/java/com/jingshanghui/pos/catalog",
        ROOT / "admin-web/src/views/catalog/components/ShelfLabelPanel.vue",
        ROOT / "admin-web/src/api/catalog",
    ])
    for token in ("serialport", "bluetooth", "methodchannel", "usbmanager", "print_success", "v-html"):
        fail(token not in source, f"发现未准入外设或不安全渲染: {token}")
    fail("unavailableshelflabelprintadapter" in source and "dispatch_blocked" in source,
         "失败关闭打印边界不完整")
    fail("trustedcatalogpayload" in source and "requiretenantid" in source, "租户可信上下文或 Web 载荷防线缺失")

    result = {
        "gate": "T2-GATE7C-SPRINT-S21B-LBL001",
        "status": "PASS",
        "requirementStatus": rows["T2-LBL-001"]["status"],
        "faultVectorCount": len(vectors["vectors"]),
        "templateFieldCount": len(admission["allowedTemplateFields"]),
        "preservedStates": admission["preservedStates"],
        "externalExecution": admission["externalExecution"],
    }
    if args.output:
        target = pathlib.Path(args.output)
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(result, ensure_ascii=False))


if __name__ == "__main__":
    main()
