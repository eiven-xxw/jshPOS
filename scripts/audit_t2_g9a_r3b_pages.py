#!/usr/bin/env python3
"""生成 G9A-R3B 十一个 Owner 运营页面的可达性、权限、状态与测试证据。"""
from __future__ import annotations

import argparse
import csv
import hashlib
import json
import pathlib
import re
import subprocess


ROOT = pathlib.Path(__file__).resolve().parents[1]
CONTRACT = ROOT / "contracts/t2/gate9b-r3b-prep"


def load(name: str) -> dict:
    return json.loads((CONTRACT / name).read_text(encoding="utf-8"))


def read(relative: str) -> str:
    return (ROOT / relative).read_text(encoding="utf-8", errors="replace")


def sha256(relative: str) -> str:
    return hashlib.sha256((ROOT / relative).read_bytes()).hexdigest()


def git(*args: str) -> str:
    return subprocess.check_output(
        ["git", "-c", "core.quotepath=false", *args], cwd=ROOT, text=True, encoding="utf-8"
    ).strip()


def metrics(text: str) -> dict[str, int]:
    return {
        "buttons": len(re.findall(r"<el-button\b", text)),
        "permissionSignals": text.count("v-hasPermi"),
        "loadingSignals": len(re.findall(r"\b(?:loading|busy|submitting|pending|pageState)\b", text, re.I)),
        "emptySignals": len(re.findall(r"el-empty|empty-text|暂无|无数据|没有记录", text, re.I)),
        "errorSignals": len(re.findall(r"ElMessage\.(?:error|warning)|lastError|错误码|关联标识|catch\s*\(", text)),
        "controlledOperationSignals": len(re.findall(r"runControlled|createSingleFlight|execute\s*=\s*async|run\s*=\s*async", text)),
        "idempotencySignals": len(re.findall(r"idempotency|commandId|operationCommandId|actionKeys|identity\(", text, re.I)),
        "confirmationSignals": text.count("ElMessageBox.confirm"),
        "businessDateSignals": len(re.findall(r"businessDate|业务日", text, re.I)),
        "directRuntimePrimitiveSignals": len(re.findall(r"\bfetch\s*\(|axios\.create|MethodChannel|rawQuery|rawInsert|rawUpdate|\bMapper\b", text)),
        "clientTenantAuthoritySignals": len(re.findall(r"tenant_?id\s*[:=]", text, re.I)),
    }


def write_csv(path: pathlib.Path, rows: list[dict]) -> None:
    with path.open("w", encoding="utf-8-sig", newline="") as handle:
        writer = csv.DictWriter(handle, fieldnames=list(rows[0]))
        writer.writeheader()
        writer.writerows(rows)


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--output-dir", required=True)
    args = parser.parse_args()
    output = pathlib.Path(args.output_dir)
    if not output.is_absolute():
        output = ROOT / output
    output.mkdir(parents=True, exist_ok=True)

    freeze = load("surface-freeze-v1.json")
    seeds = load("failure-seeds-v1.json")
    invariants = load("owner-invariant-matrix-v1.json")
    matrix = load("test-matrix-v1.json")
    failures: list[str] = []
    surfaces = freeze["surfaces"]
    expected_ids = [f"VUE-{value:02d}" for value in range(5, 16)]
    ids = [item["surfaceId"] for item in surfaces]
    if ids != expected_ids or freeze["surfaceCount"] != 11:
        failures.append("surface freeze must be ordered VUE-05..VUE-15")
    if {item["surfaceId"] for item in invariants["entries"]} != set(expected_ids):
        failures.append("Owner invariant matrix must cover exactly 11 surfaces")
    if len(matrix["dimensions"]) != 12:
        failures.append("test matrix must freeze 12 dimensions")

    test_needles = {
        "VUE-05": "business-migration/index.vue",
        "VUE-06": "CustomerPromotionPanel.vue",
        "VUE-07": "InventoryCostPanel.vue",
        "VUE-08": "MemberBenefitPolicyPanel.vue",
        "VUE-09": "ReleasePanel.vue",
        "VUE-10": "ReplenishmentPanel.vue",
        "VUE-11": "SupplyPanel.vue",
        "VUE-12": "daily-close/index.vue",
        "VUE-15": "store-onboarding/index.vue",
    }
    route_needles = {
        "VUE-05": "operations/business-migration/index",
        "VUE-06": "CustomerPromotionPanel",
        "VUE-07": "InventoryCostPanel",
        "VUE-08": "MemberBenefitPolicyPanel",
        "VUE-09": "ReleasePanel",
        "VUE-10": "ReplenishmentPanel",
        "VUE-11": "SupplyPanel",
        "VUE-12": "operations/daily-close/index",
        "VUE-13": "operations/exception-center/index",
        "VUE-14": "operations/lot-expiry/index",
        "VUE-15": "operations/store-onboarding/index",
    }
    rows: list[dict] = []
    hashes: list[dict] = []
    for item in surfaces:
        source_path = ROOT / item["path"]
        if not source_path.is_file():
            failures.append(f"missing surface: {item['path']}")
            continue
        text = read(item["path"])
        route_ok = route_needles[item["surfaceId"]] in read(item["routeEvidence"])
        if not route_ok:
            failures.append(f"route chain missing: {item['surfaceId']}")
        test_level = item["currentDirectTest"]
        test_ok = test_level == "NONE"
        mounted = False
        if item["testEvidence"]:
            test_text = read(item["testEvidence"])
            test_ok = test_needles[item["surfaceId"]] in test_text
            mounted = bool(re.search(r"\bmount\s*\(", test_text))
            if not test_ok:
                failures.append(f"test evidence drift: {item['surfaceId']}")
        signal = metrics(text)
        if signal["directRuntimePrimitiveSignals"]:
            failures.append(f"direct runtime primitive detected: {item['surfaceId']}")
        rows.append({
            "surfaceId": item["surfaceId"],
            "title": item["title"],
            "path": item["path"],
            "owner": item["owner"],
            "requirements": "|".join(item["requirements"]),
            "routeDepth": len(item["routeChain"]),
            "routeReachable": route_ok,
            "testLevel": test_level,
            "testEvidenceValid": test_ok,
            "mountedInteractionEvidence": mounted,
            **signal,
        })
        hashes.append({"surfaceId": item["surfaceId"], "path": item["path"], "sha256": sha256(item["path"])})

    levels = {
        "STATIC_SOURCE_ASSERTION": sum(row["testLevel"] == "STATIC_SOURCE_ASSERTION" for row in rows),
        "NONE": sum(row["testLevel"] == "NONE" for row in rows),
        "MOUNTED_INTERACTION": sum(row["mountedInteractionEvidence"] for row in rows),
    }
    if levels != {"STATIC_SOURCE_ASSERTION": 9, "NONE": 2, "MOUNTED_INTERACTION": 0}:
        failures.append(f"direct test baseline drift: {levels}")
    member = next(row for row in rows if row["surfaceId"] == "VUE-08")
    if member["buttons"] != 12 or member["permissionSignals"] != 8:
        failures.append("VUE-08 four-button permission seed no longer reproduces")
    if "const i = identity();" not in read("admin-web/src/views/operations/exception-center/index.vue"):
        failures.append("VUE-13 fresh command seed no longer reproduces")
    if "publishLotPolicy(command, newOperationCommandId())" not in read("admin-web/src/views/operations/lot-expiry/index.vue"):
        failures.append("VUE-14 fresh command seed no longer reproduces")
    correction = seeds["auditCorrections"][0]
    if correction["surfaceId"] != "VUE-10" or not next(row for row in rows if row["surfaceId"] == "VUE-10")["routeReachable"]:
        failures.append("VUE-10 corrected two-hop reachability not proven")

    summary = {
        "schemaVersion": "1.0",
        "gate": "T2-GATE9B-G9A-R3B-PREP",
        "findingId": "G9A-UI-P1-001",
        "findingState": "OPEN",
        "commitSha": git("rev-parse", "HEAD"),
        "surfaceCount": len(rows),
        "runtimeReachable": sum(row["routeReachable"] for row in rows),
        "testEvidenceLevels": levels,
        "reproducedP1SeedCount": seeds["summary"]["openP1"],
        "auditCorrectionCount": seeds["summary"]["auditCorrections"],
        "runtimeChanges": 0,
        "hardFailures": failures,
        "result": "PASS" if not failures else "FAIL",
        "decision": "PREP_CONDITIONAL_PASS_AWAITING_PROJECT_SPONSOR" if not failures else "NO_GO_PREP_INCOMPLETE",
        "externalExecution": load("gate-admission-v1.json")["externalExecution"],
    }
    write_csv(output / "surface-audit.csv", rows)
    (output / "surface-source-sha256.json").write_text(json.dumps(hashes, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    (output / "failure-seeds.json").write_text(json.dumps(seeds, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    (output / "owner-invariants.json").write_text(json.dumps(invariants, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    (output / "test-matrix.json").write_text(json.dumps(matrix, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    (output / "summary.json").write_text(json.dumps(summary, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(
        f"G9A-R3B PAGE AUDIT {summary['result']}: surfaces={len(rows)} reachable={summary['runtimeReachable']} "
        f"static={levels['STATIC_SOURCE_ASSERTION']} none={levels['NONE']} mounted={levels['MOUNTED_INTERACTION']} "
        f"P1Seeds={summary['reproducedP1SeedCount']} finding=OPEN"
    )
    if failures:
        raise SystemExit("\n".join(f"- {item}" for item in failures))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
