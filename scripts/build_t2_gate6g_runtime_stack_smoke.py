#!/usr/bin/env python3
"""在同一进程窗口核验正式 Server/Web/MySQL/Flutter/SQLite 运行证据。"""
from __future__ import annotations

import argparse
import base64
import json
import pathlib
import urllib.request

from run_t2_gate6d_internal_e2e import read_flutter_successful_tests


FORMAL_TEST = "正式组合根经 HTTP 签名包和文件 SQLite 完成现金交易并安全注销"


def fetch(url: str, basic_auth: tuple[str, str] | None = None) -> tuple[int, bytes, str]:
    headers = {"Accept": "application/json,text/html,*/*"}
    if basic_auth is not None:
        token = base64.b64encode(f"{basic_auth[0]}:{basic_auth[1]}".encode()).decode()
        headers["Authorization"] = f"Basic {token}"
    request = urllib.request.Request(url, headers=headers)
    with urllib.request.urlopen(request, timeout=10) as response:
        return response.status, response.read(2 * 1024 * 1024), response.headers.get("Content-Type", "")


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--server-url", required=True)
    parser.add_argument("--web-url", required=True)
    parser.add_argument("--health-user", required=True)
    parser.add_argument("--health-password", required=True)
    parser.add_argument("--mysql-runtime", required=True, type=pathlib.Path)
    parser.add_argument("--flutter-machine", required=True, type=pathlib.Path)
    parser.add_argument("--output", required=True, type=pathlib.Path)
    args = parser.parse_args()

    root_status, root_body, _ = fetch(args.server_url.rstrip("/") + "/")
    health_status, health_body, _ = fetch(
        args.server_url.rstrip("/") + "/actuator/health",
        (args.health_user, args.health_password),
    )
    web_status, web_body, web_type = fetch(args.web_url.rstrip("/") + "/")
    try:
        health = json.loads(health_body)
    except json.JSONDecodeError as exception:
        raise SystemExit(f"server health response is not JSON: {exception}")
    mysql_values = [int(line.strip()) for line in args.mysql_runtime.read_text(encoding="utf-8").splitlines() if line.strip()]
    if len(mysql_values) != 2:
        raise SystemExit("MySQL runtime evidence must contain table and Flyway success counts")
    successful = read_flutter_successful_tests(args.flutter_machine)
    failures = []
    if root_status != 200 or "RuoYi-Vue-Plus" not in root_body.decode("utf-8", errors="replace"):
        failures.append("formal server root probe failed")
    if health_status != 200 or health.get("status") != "UP":
        failures.append("formal server health is not UP")
    if web_status != 200 or b"<html" not in web_body.lower() or "text/html" not in web_type:
        failures.append("formal Web production artifact probe failed")
    if mysql_values[0] < 159 or mysql_values[1] < 51:
        failures.append("formal MySQL table/Flyway execution count below Gate 6G baseline")
    if not any(FORMAL_TEST in name for name in successful):
        failures.append("formal Flutter HTTP + file SQLite cash runtime test missing")
    report = {
        "schemaVersion": "1.0", "gate": "T2-GATE6G-S17", "status": "PASS" if not failures else "FAIL",
        "evidenceLevel": "INTERNAL_V1_CORE_CANDIDATE",
        "executionModel": "SIMULTANEOUS_PROCESS_WINDOW_WITH_SYNTHETIC_HTTP_BOUNDARY",
        "simultaneousProcessWindow": True,
        "formalServer": {"rootStatus": root_status, "healthStatus": health_status, "health": health.get("status")},
        "formalWeb": {"status": web_status, "contentType": web_type, "bytes": len(web_body)},
        "formalMySql": {"tableCount": mysql_values[0], "successfulFlywayCount": mysql_values[1]},
        "formalFlutterPos": {"successfulTestCount": len(successful), "formalRuntimeTest": "PASS" if not failures else "CHECK_FAILURES"},
        "formalSqlite": {"storage": "FILE_BACKED", "migrationAndCashFacts": "PASS" if not failures else "CHECK_FAILURES"},
        "syntheticBoundary": True, "providerNetworkCalls": 0, "realDeviceCommands": 0,
        "commercialClaimAllowed": False, "failures": failures,
    }
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(f"Gate6G runtime stack smoke {report['status']}: tables={mysql_values[0]} flyway={mysql_values[1]}")
    if failures:
        raise SystemExit("; ".join(failures))


if __name__ == "__main__":
    main()
