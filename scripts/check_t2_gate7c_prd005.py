#!/usr/bin/env python3
"""T2-PRD-005 准入、范围和外部证据边界门禁。"""
from __future__ import annotations
import csv, json, pathlib

ROOT = pathlib.Path(__file__).resolve().parents[1]

def fail(condition: bool, message: str) -> None:
    if not condition: raise SystemExit(f"T2-GATE7C-PRD005 ERROR: {message}")

def main() -> None:
    with (ROOT / "docs/governance/rtm.csv").open(encoding="utf-8", newline="") as handle:
        rows = {row["requirement_id"]: row for row in csv.DictReader(handle)}
    fail(rows["T2-PAY-004"]["status"] == "ACCEPTED", "PAY004 未按发起人指令接受")
    fail(rows["T2-PRD-005"]["status"] in {"IN_PROGRESS", "VERIFIED"}, "PRD005 未准入或越界")
    for requirement in ("T2-LBL-001", "T2-RPL-001", "T2-DMT-001", "T2-ONB-001", "T2-LOT-001"):
        fail(rows[requirement]["status"] == "DRAFT", f"{requirement} 被提前准入")
    for requirement in ("T2-PAY-002", "T2-HWD-001", "T2-PRN-001", "T2-PAR-001"):
        fail(rows[requirement]["status"] == "BLOCKED", f"{requirement} 阻断漂移")
    admission = json.loads((ROOT / "contracts/t2/gate7c-prd005/prd005-admission.json").read_text(encoding="utf-8"))
    fail(admission["requirement"]["status"] == rows["T2-PRD-005"]["status"], "RTM/准入状态不一致")
    fail(all(value == 0 for value in admission["externalExecution"].values() if isinstance(value, int)), "出现外部执行")
    vectors = json.loads((ROOT / "contracts/t2/gate7c-prd005/prd005-fault-vectors.json").read_text(encoding="utf-8"))
    fail(len(vectors["vectors"]) >= 12, "故障向量不足")
    feature_roots = [ROOT / "server/ruoyi-modules/jshpos-catalog/src",
                     ROOT / "pos-flutter/lib/features/weighted_barcode"]
    sources = "\n".join(path.read_text(encoding="utf-8", errors="ignore") for base in feature_roots if base.exists()
        for path in base.rglob("*") if path.is_file() and path.suffix in {".java", ".dart", ".xml"}).lower()
    for token in ("serialport", "bluetooth", "methodchannel('scale", "provider sdk"):
        fail(token not in sources, f"发现未准入外部能力: {token}")
    print(json.dumps({"gate":"T2-GATE7C-SPRINT-S21A-PRD005","status":"PASS",
        "requirementStatus":rows["T2-PRD-005"]["status"],"faultVectorCount":len(vectors["vectors"]),
        "realDeviceCommands":0,"commercialClaimAllowed":False}, ensure_ascii=False))

if __name__ == "__main__": main()
