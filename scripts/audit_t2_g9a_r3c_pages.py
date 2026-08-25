#!/usr/bin/env python3
"""生成 G9A-R3C 报表、终端、商业运营与 POS 辅助页静态审计证据。"""
from __future__ import annotations

import argparse
import csv
import hashlib
import json
import pathlib
import re
import subprocess


ROOT = pathlib.Path(__file__).resolve().parents[1]
CONTRACT = ROOT / "contracts/t2/gate9b-r3c-prep"


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


def metrics(text: str, kind: str) -> dict[str, int]:
    button_pattern = r"<el-button\b" if kind == "VUE" else r"\b(?:FilledButton|OutlinedButton|TextButton|IconButton)(?:\.|\()"
    return {
        "buttons": len(re.findall(button_pattern, text)),
        "permissionSignals": len(re.findall(r"v-hasPermi|allow[A-Z]|PosPermission", text)),
        "loadingSignals": len(re.findall(r"\b(?:loading|busy|submitting|pending|pageState|phase)\b", text, re.I)),
        "emptySignals": len(re.findall(r"el-empty|empty-text|暂无|无数据|没有记录|empty", text, re.I)),
        "errorSignals": len(re.findall(r"ElMessage\.(?:error|warning)|safeMessage|lastError|错误码|关联标识|catch\s*\(|Failure", text)),
        "idempotencySignals": len(re.findall(r"idempotency|commandId|operationCommandId|identity\(|recoverable", text, re.I)),
        "confirmationSignals": len(re.findall(r"ElMessageBox\.(?:confirm|prompt)|showDialog<", text)),
        "businessDateSignals": len(re.findall(r"businessDate|业务日", text, re.I)),
        "offlineSignals": len(re.findall(r"offline|离线|网络|BLOCKED_EXTERNAL|UNAVAILABLE", text, re.I)),
        "exportAttachmentSecretSignals": len(re.findall(r"export|download|attachment|secret|导出|附件|秘密|凭据", text, re.I)),
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
    expected_ids = ["VUE-16", "VUE-17", "VUE-18", "VUE-19", "VUE-20", "FLT-01", "FLT-02", "FLT-05"]
    failures: list[str] = []
    if [item["surfaceId"] for item in freeze["surfaces"]] != expected_ids:
        failures.append("surface freeze order drift")
    if {item["surfaceId"] for item in invariants["entries"]} != set(expected_ids):
        failures.append("Owner invariant matrix must cover exactly eight surfaces")
    if len(matrix["dimensions"]) != 12:
        failures.append("test matrix must freeze twelve dimensions")

    rows: list[dict] = []
    hashes: list[dict] = []
    for item in freeze["surfaces"]:
        if not (ROOT / item["path"]).is_file():
            failures.append(f"missing surface: {item['path']}")
            continue
        source = read(item["path"])
        route_ok = item["routeNeedle"] in read(item["routeEvidence"])
        if not route_ok:
            failures.append(f"route/navigation chain missing: {item['surfaceId']}")
        direct_test_valid = item["currentDirectTest"] == "NONE"
        mounted = False
        if item["testEvidence"]:
            test_text = read(item["testEvidence"])
            direct_test_valid = item["testNeedle"] in test_text
            mounted = "testWidgets(" in test_text if item["kind"] == "FLUTTER" else bool(re.search(r"\bmount\s*\(", test_text))
            if not direct_test_valid:
                failures.append(f"direct test evidence drift: {item['surfaceId']}")
        signal = metrics(source, item["kind"])
        if signal["directRuntimePrimitiveSignals"]:
            failures.append(f"direct runtime primitive detected in page: {item['surfaceId']}")
        rows.append({
            "surfaceId": item["surfaceId"],
            "kind": item["kind"],
            "title": item["title"],
            "path": item["path"],
            "owner": item["owner"],
            "requirements": "|".join(item["requirements"]),
            "routeDepth": len(item["routeChain"]),
            "routeReachable": route_ok,
            "testLevel": item["currentDirectTest"],
            "directTestEvidenceValid": direct_test_valid,
            "mountedInteractionEvidence": mounted,
            **signal,
        })
        hashes.append({"surfaceId": item["surfaceId"], "path": item["path"], "sha256": sha256(item["path"])})

    levels = {
        "VUE_NONE": sum(row["kind"] == "VUE" and row["testLevel"] == "NONE" for row in rows),
        "FLUTTER_WIDGET": sum(row["kind"] == "FLUTTER" and row["testLevel"] == "WIDGET_INTERACTION" for row in rows),
        "FLUTTER_NONE": sum(row["kind"] == "FLUTTER" and row["testLevel"] == "NONE" for row in rows),
        "MOUNTED_OR_WIDGET": sum(row["mountedInteractionEvidence"] for row in rows),
    }
    expected_levels = {"VUE_NONE": 5, "FLUTTER_WIDGET": 2, "FLUTTER_NONE": 1, "MOUNTED_OR_WIDGET": 2}
    if levels != expected_levels:
        failures.append(f"direct test baseline drift: {levels}")

    reporting = read("admin-web/src/views/reporting/operation/index.vue")
    terminal = read("admin-web/src/views/terminal/registry/index.vue")
    cash = read("pos-flutter/lib/features/shift/presentation/pos_cash_management_page.dart")
    if reporting.count("newUlid()") < 5 or "ElMessageBox.confirm" in reporting:
        failures.append("VUE-16 fresh-operation-key or missing-confirmation seed no longer reproduces")
    if "Date.now()" not in terminal or "crypto.randomUUID()" not in terminal:
        failures.append("VUE-20 fresh idempotency seed no longer reproduces")
    if "microsecondsSinceEpoch" not in cash or "_newKey('cash')" not in cash or "_newKey('drawer')" not in cash:
        failures.append("FLT-05 fresh idempotency seed no longer reproduces")
    for surface in ("VUE-17", "VUE-18", "VUE-19"):
        row = next(item for item in rows if item["surfaceId"] == surface)
        if row["confirmationSignals"]:
            failures.append(f"{surface} missing-confirmation seed no longer reproduces")

    summary = {
        "schemaVersion": "1.0",
        "gate": "T2-GATE9B-G9A-R3C-PREP",
        "findingId": "G9A-UI-P1-001",
        "findingState": "OPEN",
        "commitSha": git("rev-parse", "HEAD"),
        "surfaceCount": len(rows),
        "vueCount": sum(row["kind"] == "VUE" for row in rows),
        "flutterCount": sum(row["kind"] == "FLUTTER" for row in rows),
        "runtimeReachable": sum(row["routeReachable"] for row in rows),
        "testEvidenceLevels": levels,
        "reproducedP1SeedCount": seeds["summary"]["openP1"],
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
        f"G9A-R3C PAGE AUDIT {summary['result']}: surfaces={len(rows)} reachable={summary['runtimeReachable']} "
        f"vueNone={levels['VUE_NONE']} flutterWidget={levels['FLUTTER_WIDGET']} flutterNone={levels['FLUTTER_NONE']} "
        f"P1Seeds={summary['reproducedP1SeedCount']} finding=OPEN"
    )
    if failures:
        raise SystemExit("\n".join(f"- {item}" for item in failures))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
