#!/usr/bin/env python3
"""生成 G9A-R3 26 个正式页面的路由、状态、权限与测试基线证据。"""
from __future__ import annotations

import argparse
import csv
import hashlib
import json
import pathlib
import re
import subprocess


ROOT = pathlib.Path(__file__).resolve().parents[1]
CONTRACT = ROOT / "contracts/t2/gate9b-r3-prep"


def load(name: str) -> dict:
    return json.loads((CONTRACT / name).read_text(encoding="utf-8"))


def read(path: pathlib.Path) -> str:
    return path.read_text(encoding="utf-8", errors="replace")


def digest(path: pathlib.Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def git(*args: str) -> str:
    return subprocess.check_output(["git", *args], cwd=ROOT, text=True, encoding="utf-8").strip()


def signals(kind: str, text: str) -> dict[str, int]:
    if kind == "VUE":
        return {
            "buttons": len(re.findall(r"<el-button\b", text)),
            "permissionSignals": text.count("v-hasPermi"),
            "loadingSignals": len(re.findall(r"\b(?:loading|busy|submitting|pending|pageState)\b", text, re.I)),
            "emptySignals": len(re.findall(r"el-empty|empty-text|暂无|无数据|没有记录", text, re.I)),
            "errorSignals": len(re.findall(r"ElMessage\.(?:error|warning)|lastError|错误码|关联标识|catch\s*\(", text)),
            "singleFlightSignals": len(re.findall(r"singleFlight|runControlled|executeCommand|\b(?:run|execute)\s*=\s*async", text)),
            "offlineSignals": len(re.findall(r"offline|离线|断网|网络", text, re.I)),
            "businessDateSignals": len(re.findall(r"businessDate|business-date|业务日", text, re.I)),
            "recoverySignals": len(re.findall(r"retry|recover|resume|refresh|重试|恢复|重新加载|查询原", text, re.I)),
            "directRuntimePrimitiveSignals": len(re.findall(r"\bfetch\s*\(|axios\.create|MethodChannel|rawQuery|rawInsert|rawUpdate|Mapper", text)),
            "clientTenantAuthoritySignals": len(re.findall(r"tenant_?id\s*[:=]", text, re.I)),
        }
    return {
        "buttons": len(re.findall(r"\b(?:FilledButton|ElevatedButton|OutlinedButton|TextButton|IconButton|InkWell|GestureDetector)\b", text)),
        "permissionSignals": len(re.findall(r"permission|hasPermission|allow[A-Z]|authorized|denied", text, re.I)),
        "loadingSignals": len(re.findall(r"busy|loading|submitting|processing", text, re.I)),
        "emptySignals": len(re.findall(r"empty|暂无|无数据|没有记录", text, re.I)),
        "errorSignals": len(re.findall(r"safeMessage|errorCode|SnackBar|showDialog|_SafeError|catch\s*\(", text)),
        "singleFlightSignals": len(re.findall(r"busy|_flight|singleFlight", text, re.I)),
        "offlineSignals": len(re.findall(r"offline|离线|断网|网络|sync|同步|outbox", text, re.I)),
        "businessDateSignals": len(re.findall(r"businessDate|业务日", text, re.I)),
        "recoverySignals": len(re.findall(r"retry|recover|refresh|重试|恢复|查询原", text, re.I)),
        "directRuntimePrimitiveSignals": len(re.findall(r"MethodChannel|sqflite|rawQuery|rawInsert|rawUpdate|rawDelete", text)),
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
    audit = load("page-audit-v1.json")
    gaps = load("test-gap-register-v1.json")
    acceptance = load("acceptance-matrix-v1.json")
    surfaces = freeze["surfaces"]
    failures: list[str] = []
    ids = [item["surfaceId"] for item in surfaces]
    paths = [item["path"] for item in surfaces]
    if len(surfaces) != 26 or len(set(ids)) != 26 or len(set(paths)) != 26:
        failures.append("surface inventory must contain 26 unique IDs and paths")
    if sum(item["kind"] == "VUE" for item in surfaces) != 20 or sum(item["kind"] == "FLUTTER" for item in surfaces) != 6:
        failures.append("surface kind count drift")

    gap_ids = {item["gapId"] for item in gaps["gaps"]}
    assessments = {item["surfaceId"]: item for item in audit["surfaceAssessments"]}
    if set(assessments) != set(ids):
        failures.append("page assessment does not cover exactly 26 surfaces")
    referenced_gaps = {gap for item in assessments.values() for gap in item["gaps"]}
    if referenced_gaps != gap_ids:
        failures.append("page assessment and gap register IDs differ")

    rows: list[dict] = []
    route_rows: list[dict] = []
    hashes: list[dict] = []
    for item in surfaces:
        path = ROOT / item["path"]
        if not path.is_file():
            failures.append(f"missing surface: {item['path']}")
            continue
        text = read(path)
        route = item["route"]
        route_path = ROOT / route["evidencePath"]
        present = route_path.is_file() and route["needle"] in read(route_path)
        if present != route["expectedPresent"]:
            failures.append(f"route expectation drift: {item['surfaceId']}")
        test = item["test"]
        test_ok = test["level"] == "NONE"
        if test["level"] != "NONE":
            test_path = ROOT / test["evidencePath"]
            test_ok = test_path.is_file() and test["needle"] in read(test_path)
            if not test_ok:
                failures.append(f"test evidence drift: {item['surfaceId']}")
        metric = signals(item["kind"], text)
        if metric["directRuntimePrimitiveSignals"]:
            failures.append(f"UI direct runtime primitive detected: {item['surfaceId']}")
        row = {
            "surfaceId": item["surfaceId"],
            "kind": item["kind"],
            "path": item["path"],
            "requirements": "|".join(item["requirements"]),
            "routeMode": route["mode"],
            "runtimeReachable": route["expectedPresent"],
            "testLevel": test["level"],
            "testEvidenceValid": test_ok,
            "classification": assessments[item["surfaceId"]]["classification"],
            "gapIds": "|".join(assessments[item["surfaceId"]]["gaps"]),
            **metric,
        }
        rows.append(row)
        route_rows.append({
            "surfaceId": item["surfaceId"],
            "mode": route["mode"],
            "evidencePath": route["evidencePath"],
            "needle": route["needle"],
            "expectedPresent": route["expectedPresent"],
            "observedPresent": present,
            "testLevel": test["level"],
            "testEvidenceValid": test_ok,
        })
        hashes.append({"surfaceId": item["surfaceId"], "path": item["path"], "sha256": digest(path)})

    if sum(row["runtimeReachable"] for row in rows) != 24:
        failures.append("expected exactly 24 runtime-reachable surfaces")
    levels = {level: sum(row["testLevel"] == level for row in rows) for level in ("WIDGET_INTERACTION", "STATIC_SOURCE_ASSERTION", "NONE")}
    if levels != {"WIDGET_INTERACTION": 4, "STATIC_SOURCE_ASSERTION": 13, "NONE": 9}:
        failures.append(f"test evidence level drift: {levels}")
    if len(acceptance["checks"]) != 12:
        failures.append("acceptance matrix must freeze 12 dimensions")

    summary = {
        "schemaVersion": "1.0",
        "gate": "T2-GATE9B-G9A-R3-PREP",
        "findingId": "G9A-UI-P1-001",
        "commitSha": git("rev-parse", "HEAD"),
        "surfaceCount": len(rows),
        "vueSurfaceCount": sum(row["kind"] == "VUE" for row in rows),
        "flutterSurfaceCount": sum(row["kind"] == "FLUTTER" for row in rows),
        "runtimeReachable": sum(row["runtimeReachable"] for row in rows),
        "testEvidenceLevels": levels,
        "openP0": gaps["summary"]["openP0"],
        "openP1": gaps["summary"]["openP1"],
        "finalUiAcceptance": "NOT_ACHIEVED",
        "findingState": "OPEN",
        "hardFailures": failures,
        "result": "PASS" if not failures else "FAIL",
        "recommendation": "CONDITIONAL_PASS_PREP_ONLY_AWAITING_RUNTIME_CONFIRMATION" if not failures else "NO_GO_PREP_INCOMPLETE",
        "externalExecution": load("gate-admission-v1.json")["externalExecution"],
    }
    write_csv(output / "page-audit.csv", rows)
    (output / "route-and-test-evidence.json").write_text(json.dumps(route_rows, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    (output / "surface-source-sha256.json").write_text(json.dumps(hashes, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    (output / "gap-register.json").write_text(json.dumps(gaps, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    (output / "acceptance-matrix.json").write_text(json.dumps(acceptance, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    (output / "summary.json").write_text(json.dumps(summary, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(
        f"G9A-R3 PAGE AUDIT {summary['result']}: surfaces={len(rows)} reachable={summary['runtimeReachable']} "
        f"widget={levels['WIDGET_INTERACTION']} static={levels['STATIC_SOURCE_ASSERTION']} none={levels['NONE']} P1={summary['openP1']}"
    )
    if failures:
        raise SystemExit("\n".join(f"- {item}" for item in failures))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
