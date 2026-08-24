#!/usr/bin/env python3
"""对正式运行栈注入 Redis/MySQL 暂停并验证失败关闭及恢复。"""
from __future__ import annotations

import argparse
import json
import os
import pathlib
import subprocess
import time
import urllib.error
import urllib.parse
import urllib.request
from typing import Any

from run_t2_gate8b_runtime_api_journey import ApiClient, CLIENT_ID, PLATFORM_TENANT


class FaultFailure(RuntimeError):
    """表示依赖故障未能失败关闭或恢复。"""


def docker(action: str, container: str) -> None:
    process = subprocess.run(["docker", action, container], capture_output=True, text=True, timeout=20)
    if process.returncode != 0:
        raise FaultFailure(f"docker {action} failed")


def alive(pid: int) -> bool:
    try:
        os.kill(pid, 0)
        return True
    except OSError:
        return False


def raw_login(base_url: str, password: str, timeout: float) -> bool:
    body = json.dumps({
        "tenantId": PLATFORM_TENANT,
        "username": "admin",
        "password": password,
        "clientId": CLIENT_ID,
        "grantType": "password",
    }, separators=(",", ":")).encode()
    request = urllib.request.Request(
        base_url.rstrip("/") + "/auth/login", data=body,
        headers={"Content-Type": "application/json", "Accept": "application/json", "clientid": CLIENT_ID},
        method="POST",
    )
    try:
        with urllib.request.urlopen(request, timeout=timeout) as response:
            payload = json.loads(response.read().decode("utf-8"))
            return response.status < 300 and payload.get("code") in (None, 200)
    except (urllib.error.HTTPError, urllib.error.URLError, TimeoutError, ConnectionError):
        return False


def raw_get(base_url: str, token: str, path: str, timeout: float) -> bool:
    request = urllib.request.Request(
        base_url.rstrip("/") + path,
        headers={"Accept": "application/json", "clientid": CLIENT_ID, "Authorization": f"Bearer {token}"},
        method="GET",
    )
    try:
        with urllib.request.urlopen(request, timeout=timeout) as response:
            payload = json.loads(response.read().decode("utf-8"))
            return response.status < 300 and payload.get("code") in (None, 200)
    except (urllib.error.HTTPError, urllib.error.URLError, TimeoutError, ConnectionError):
        return False


def recover(check: Any, attempts: int = 20) -> int:
    for attempt in range(1, attempts + 1):
        if check():
            return attempt
        time.sleep(1)
    raise FaultFailure("依赖恢复后应用未在预算内恢复")


def run(args: argparse.Namespace) -> dict[str, Any]:
    password = os.environ.get("GATE8B_PLATFORM_PASSWORD")
    if not password:
        raise FaultFailure("缺少受控合成平台凭据")
    client = ApiClient(args.base_url)
    client.login(PLATFORM_TENANT, "admin", password, "fault-baseline-login")
    token = client.token or ""
    tenant_query = "/system/tenant/list?" + urllib.parse.urlencode({"pageNum": 1, "pageSize": 10})
    if not raw_get(args.base_url, token, tenant_query, 5):
        raise FaultFailure("故障前 MySQL 读取基线失败")

    redis_paused = False
    try:
        docker("pause", args.redis_container)
        redis_paused = True
        started = time.perf_counter()
        redis_false_success = raw_login(args.base_url, password, args.failure_timeout)
        redis_failure_ms = round((time.perf_counter() - started) * 1000)
    finally:
        if redis_paused:
            docker("unpause", args.redis_container)
    if redis_false_success:
        raise FaultFailure("Redis 不可用时登录仍伪造成功")
    redis_recovery_attempt = recover(lambda: raw_login(args.base_url, password, 5))
    if not alive(args.pid):
        raise FaultFailure("Redis 故障后应用进程退出")

    mysql_paused = False
    try:
        docker("pause", args.mysql_container)
        mysql_paused = True
        started = time.perf_counter()
        mysql_false_success = raw_get(args.base_url, token, tenant_query, args.failure_timeout)
        mysql_failure_ms = round((time.perf_counter() - started) * 1000)
    finally:
        if mysql_paused:
            docker("unpause", args.mysql_container)
    if mysql_false_success:
        raise FaultFailure("MySQL 不可用时权威读取仍伪造成功")
    mysql_recovery_attempt = recover(lambda: raw_get(args.base_url, token, tenant_query, 5))
    if not alive(args.pid):
        raise FaultFailure("MySQL 故障后应用进程退出")

    return {
        "schemaVersion": "1.0",
        "requirementId": "T2-PERF-002",
        "status": "PASS",
        "classification": "INTERNAL_DEPENDENCY_DEGRADATION",
        "vectors": {
            "PERF-F003": {"dependency": "REDIS", "falseSuccess": False, "failureObservedMs": redis_failure_ms,
                          "recoveryAttempts": redis_recovery_attempt, "processAlive": True},
            "PERF-F004": {"dependency": "MYSQL", "falseSuccess": False, "failureObservedMs": mysql_failure_ms,
                          "recoveryAttempts": mysql_recovery_attempt, "processAlive": True},
        },
        "providerNetworkCalls": 0,
        "realFunds": 0,
        "realDeviceOrPeripheralCommands": 0,
        "commercialSla": False,
    }


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--base-url", required=True)
    parser.add_argument("--pid", required=True, type=int)
    parser.add_argument("--mysql-container", required=True)
    parser.add_argument("--redis-container", required=True)
    parser.add_argument("--failure-timeout", type=float, default=3.0)
    parser.add_argument("--output", required=True)
    args = parser.parse_args()
    try:
        evidence = run(args)
    except FaultFailure as error:
        print("T2-PERF-002 dependency fault failed: " + str(error))
        return 1
    target = pathlib.Path(args.output)
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_text(json.dumps(evidence, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print("T2-PERF-002 dependency faults passed: redis/mysql fail closed and recovered")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
