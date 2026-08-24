#!/usr/bin/env python3
"""在正式 MySQL/Redis/JAR 栈执行 T2-PERF-002 并发、持续与资源基线。

脚本只使用公开 HTTP API，不连接数据库、不读取业务表、不记录令牌、密码或请求正文。
所有门槛来自运行前已冻结的 contracts/t2/gate8c-perf002 契约。
"""
from __future__ import annotations

import argparse
import concurrent.futures
import hashlib
import json
import math
import os
import pathlib
import platform
import re
import shutil
import subprocess
import threading
import time
import urllib.error
import urllib.parse
import urllib.request
from dataclasses import asdict, dataclass
from typing import Any

from run_t2_gate8b_runtime_api_journey import ApiClient, CLIENT_ID, PLATFORM_TENANT


ROOT = pathlib.Path(__file__).resolve().parents[1]
CONTRACT_DIR = ROOT / "contracts/t2/gate8c-perf002"


class PerformanceFailure(RuntimeError):
    """表示性能正确性、证据完整性或冻结阈值失败。"""


@dataclass(frozen=True)
class RequestSample:
    phase: str
    path: str
    duration_ms: float
    http_status: int
    business_code: int | None
    success: bool


def load_contract(name: str) -> dict[str, Any]:
    return json.loads((CONTRACT_DIR / name).read_text(encoding="utf-8"))


def percentile(values: list[float], quantile: float) -> float:
    if not values:
        raise PerformanceFailure("缺少延迟样本")
    ordered = sorted(values)
    index = max(0, min(len(ordered) - 1, math.ceil(len(ordered) * quantile) - 1))
    return round(ordered[index], 3)


def request(base_url: str, token: str, path: str, phase: str, timeout: float = 10.0) -> RequestSample:
    headers = {"Accept": "application/json", "clientid": CLIENT_ID, "Authorization": f"Bearer {token}"}
    started = time.perf_counter()
    status = 0
    raw = b""
    try:
        with urllib.request.urlopen(
            urllib.request.Request(base_url.rstrip("/") + path, headers=headers, method="GET"), timeout=timeout,
        ) as response:
            status = response.status
            raw = response.read()
    except urllib.error.HTTPError as error:
        status = error.code
        raw = error.read()
    except Exception:
        return RequestSample(phase, path.split("?", 1)[0], (time.perf_counter() - started) * 1000, 0, None, False)
    duration = (time.perf_counter() - started) * 1000
    try:
        payload = json.loads(raw.decode("utf-8")) if raw else {}
    except Exception:
        return RequestSample(phase, path.split("?", 1)[0], duration, status, None, False)
    code = payload.get("code") if isinstance(payload, dict) else None
    success = 200 <= status < 300 and code in (None, 200)
    return RequestSample(phase, path.split("?", 1)[0], duration, status, code if isinstance(code, int) else None, success)


def execute_counted_phase(
    base_url: str, token: str, paths: list[str], phase: str, concurrency: int, requests: int,
) -> tuple[list[RequestSample], float]:
    started = time.perf_counter()
    with concurrent.futures.ThreadPoolExecutor(max_workers=concurrency) as executor:
        futures = [
            executor.submit(request, base_url, token, paths[index % len(paths)], phase)
            for index in range(requests)
        ]
        samples = [future.result() for future in futures]
    return samples, time.perf_counter() - started


def execute_sustained_phase(
    base_url: str, token: str, paths: list[str], concurrency: int, seconds: int,
) -> tuple[list[RequestSample], float]:
    started = time.perf_counter()
    deadline = started + seconds
    samples: list[RequestSample] = []
    cursor = 0
    with concurrent.futures.ThreadPoolExecutor(max_workers=concurrency) as executor:
        while time.perf_counter() < deadline:
            batch = [
                executor.submit(request, base_url, token, paths[(cursor + index) % len(paths)], "sustained")
                for index in range(concurrency)
            ]
            samples.extend(future.result() for future in batch)
            cursor += concurrency
    return samples, time.perf_counter() - started


def summarize(phase: str, concurrency: int, samples: list[RequestSample], elapsed: float) -> dict[str, Any]:
    durations = [sample.duration_ms for sample in samples]
    errors = sum(not sample.success for sample in samples)
    return {
        "phase": phase,
        "concurrency": concurrency,
        "requests": len(samples),
        "errors": errors,
        "errorRate": round(errors / len(samples), 6) if samples else 1.0,
        "elapsedSeconds": round(elapsed, 3),
        "throughputRps": round(len(samples) / elapsed, 3) if elapsed > 0 else 0.0,
        "p50Ms": percentile(durations, 0.50),
        "p95Ms": percentile(durations, 0.95),
        "p99Ms": percentile(durations, 0.99),
        "maxMs": round(max(durations), 3),
        "pathCounts": {path: sum(sample.path == path for sample in samples) for path in sorted({item.path for item in samples})},
    }


def _memory_mib(text: str) -> float | None:
    match = re.search(r"([0-9.]+)\s*([KMG]i?B)", text)
    if not match:
        return None
    value = float(match.group(1))
    unit = match.group(2).upper()
    if unit.startswith("K"):
        value /= 1024
    elif unit.startswith("G"):
        value *= 1024
    return round(value, 3)


def _docker_memory(container: str) -> float | None:
    if not container:
        return None
    process = subprocess.run(
        ["docker", "stats", "--no-stream", "--format", "{{.MemUsage}}", container],
        capture_output=True, text=True, timeout=10,
    )
    return _memory_mib(process.stdout) if process.returncode == 0 else None


def _mysql_connections(container: str) -> int | None:
    password = os.environ.get("T2_PERF_MYSQL_PASSWORD")
    if not container or not password:
        return None
    process = subprocess.run(
        ["docker", "exec", "-e", f"MYSQL_PWD={password}", container, "mysql", "-uroot", "-Nse",
         "SHOW STATUS LIKE 'Threads_connected'"],
        capture_output=True, text=True, timeout=10,
    )
    if process.returncode != 0:
        return None
    match = re.search(r"Threads_connected\s+(\d+)", process.stdout)
    return int(match.group(1)) if match else None


def _redis_memory(container: str) -> float | None:
    password = os.environ.get("T2_PERF_REDIS_PASSWORD")
    if not container or not password:
        return None
    process = subprocess.run(
        ["docker", "exec", "-e", f"REDISCLI_AUTH={password}", container, "redis-cli", "INFO", "memory"],
        capture_output=True, text=True, timeout=10,
    )
    if process.returncode != 0:
        return None
    match = re.search(r"used_memory:(\d+)", process.stdout)
    return round(int(match.group(1)) / 1024 / 1024, 3) if match else None


def _process_sample(pid: int, previous: tuple[float, int] | None) -> tuple[dict[str, Any], tuple[float, int]]:
    now = time.monotonic()
    stat = pathlib.Path(f"/proc/{pid}/stat").read_text(encoding="utf-8").split()
    ticks = int(stat[13]) + int(stat[14])
    status = pathlib.Path(f"/proc/{pid}/status").read_text(encoding="utf-8")
    rss_match = re.search(r"^VmRSS:\s+(\d+)\s+kB", status, re.MULTILINE)
    rss_mib = round(int(rss_match.group(1)) / 1024, 3) if rss_match else None
    cpu = None
    if previous:
        elapsed = now - previous[0]
        cpu = round(((ticks - previous[1]) / os.sysconf("SC_CLK_TCK")) / elapsed * 100, 3) if elapsed > 0 else None
    return {"timestampOffsetSeconds": now, "applicationRssMiB": rss_mib, "applicationCpuPercent": cpu}, (now, ticks)


def resource_sampler(
    pid: int, mysql_container: str, redis_container: str, interval: int,
    stop: threading.Event, output: list[dict[str, Any]], errors: list[str],
) -> None:
    previous: tuple[float, int] | None = None
    origin = time.monotonic()
    sequence = 0
    while not stop.is_set():
        try:
            sample, previous = _process_sample(pid, previous)
            sample["timestampOffsetSeconds"] = round(time.monotonic() - origin, 3)
            sample["mysqlMemoryMiB"] = _docker_memory(mysql_container)
            sample["redisMemoryMiB"] = _docker_memory(redis_container)
            if sequence % 5 == 0:
                sample["mysqlConnections"] = _mysql_connections(mysql_container)
                sample["redisUsedMemoryMiB"] = _redis_memory(redis_container)
            output.append(sample)
        except Exception as error:  # pragma: no cover - 仅正式 Linux 执行器路径
            errors.append(type(error).__name__)
        sequence += 1
        stop.wait(interval)


def parse_gc_log(path: pathlib.Path) -> dict[str, Any]:
    if not path.is_file():
        raise PerformanceFailure("GC 日志缺失")
    pauses: list[float] = []
    for line in path.read_text(encoding="utf-8", errors="replace").splitlines():
        if "Pause" not in line:
            continue
        match = re.search(r"([0-9.]+)ms(?:\s|$)", line)
        if match:
            pauses.append(float(match.group(1)))
    return {
        "pauseCount": len(pauses),
        "maxPauseMs": round(max(pauses), 3) if pauses else 0.0,
        "totalPauseMs": round(sum(pauses), 3),
        "logSha256": hashlib.sha256(path.read_bytes()).hexdigest(),
    }


def executor_fingerprint() -> dict[str, Any]:
    cpu_model = "unknown"
    cpu_info = pathlib.Path("/proc/cpuinfo")
    if cpu_info.is_file():
        match = re.search(r"^model name\s*:\s*(.+)$", cpu_info.read_text(encoding="utf-8"), re.MULTILINE)
        if match:
            cpu_model = match.group(1).strip()
    java = subprocess.run(["java", "-version"], capture_output=True, text=True, timeout=10)
    java_version = (java.stderr or java.stdout).splitlines()[0] if java.returncode == 0 else "unavailable"
    disk = shutil.disk_usage(ROOT)
    memory_mib = round(os.sysconf("SC_PAGE_SIZE") * os.sysconf("SC_PHYS_PAGES") / 1024 / 1024)
    commit = subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=ROOT, text=True).strip()
    return {
        "commitSha": commit,
        "runnerOs": platform.platform(),
        "kernel": platform.release(),
        "cpuModel": cpu_model,
        "logicalCpu": os.cpu_count(),
        "memoryMiB": memory_mib,
        "freeDiskMiB": round(disk.free / 1024 / 1024),
        "javaVersion": java_version,
        "mysqlImage": os.environ.get("T2_PERF_MYSQL_IMAGE", "unknown"),
        "redisImage": os.environ.get("T2_PERF_REDIS_IMAGE", "unknown"),
    }


def validate_fingerprint(fingerprint: dict[str, Any], spec: dict[str, Any]) -> None:
    missing = [key for key in spec["requiredFingerprintFields"] if not fingerprint.get(key)]
    if missing:
        raise PerformanceFailure("执行器指纹缺失: " + ", ".join(missing))
    minimum = spec["minimumObservedResources"]
    if fingerprint["logicalCpu"] < minimum["logicalCpu"] or fingerprint["memoryMiB"] < minimum["memoryMiB"] \
            or fingerprint["freeDiskMiB"] < minimum["freeDiskMiB"]:
        raise PerformanceFailure("执行器资源低于冻结最小值")


def find_tenant_and_store(base_url: str, platform_password: str, tenant_password: str) -> tuple[str, str, list[str]]:
    platform_client = ApiClient(base_url)
    platform_client.login(PLATFORM_TENANT, "admin", platform_password, "perf-platform-login")
    query = urllib.parse.urlencode({"companyName": "Gate8B虚构便利商户", "pageNum": 1, "pageSize": 50})
    tenants = platform_client.call("GET", "/system/tenant/list?" + query, "perf-tenant-list").get("rows", [])
    matches = [row for row in tenants if row.get("companyName") == "Gate8B虚构便利商户"]
    if len(matches) != 1 or not matches[0].get("tenantId"):
        raise PerformanceFailure("无法从正式 API 唯一解析 Gate8B 合成租户")
    tenant_id = str(matches[0]["tenantId"])
    tenant_client = ApiClient(base_url)
    tenant_client.login(tenant_id, "gate8b_tenant_admin", tenant_password, "perf-tenant-login")
    stores = tenant_client.call("GET", "/api/v1/foundation/stores", "perf-store-list").get("data", [])
    if len(stores) != 1 or not stores[0].get("storeId"):
        raise PerformanceFailure("无法从正式 API 唯一解析 Gate8B 合成门店")
    store_id = str(stores[0]["storeId"])
    paths = [
        "/system/user/getInfo",
        "/api/v1/foundation/stores",
        "/api/v1/subscriptions/current",
        "/api/v1/service/projects?" + urllib.parse.urlencode({"storeId": store_id, "limit": 50}),
        "/api/v1/service/tickets?" + urllib.parse.urlencode({"storeId": store_id, "limit": 50}),
    ]
    return tenant_id, tenant_client.token or "", paths


def self_test() -> None:
    if percentile([1, 2, 3, 4, 5], 0.95) != 5:
        raise PerformanceFailure("百分位算法错误")
    parsed = _memory_mib("123.5MiB / 7.7GiB")
    if parsed != 123.5:
        raise PerformanceFailure("资源解析错误")
    print("T2-PERF-002 runtime harness self-test passed")


def run(args: argparse.Namespace) -> dict[str, Any]:
    workload = load_contract("workload-model-v1.json")
    thresholds = load_contract("thresholds-v1.json")
    executor_spec = load_contract("executor-spec-v1.json")
    platform_password = os.environ.get("GATE8B_PLATFORM_PASSWORD")
    tenant_password = os.environ.get("GATE8B_BOOTSTRAP_PASSWORD")
    if not platform_password or not tenant_password:
        raise PerformanceFailure("缺少受控合成凭据环境变量")
    fingerprint = executor_fingerprint()
    validate_fingerprint(fingerprint, executor_spec)
    tenant_id, token, paths = find_tenant_and_store(args.base_url, platform_password, tenant_password)
    warmup = workload["warmup"]["httpRequests"]
    warmup_samples, _ = execute_counted_phase(args.base_url, token, paths, "warmup", 1, warmup)
    if any(not sample.success for sample in warmup_samples):
        raise PerformanceFailure("预热请求失败")

    resources: list[dict[str, Any]] = []
    resource_errors: list[str] = []
    stop = threading.Event()
    sampler = threading.Thread(
        target=resource_sampler,
        args=(args.pid, args.mysql_container, args.redis_container,
              workload["formalRuntime"]["resourceSampleSeconds"], stop, resources, resource_errors),
        daemon=True,
    )
    sampler.start()
    phases: list[dict[str, Any]] = []
    all_samples: list[RequestSample] = []
    try:
        total = workload["formalRuntime"]["requestsPerConcurrency"]
        for concurrency in workload["formalRuntime"]["httpConcurrency"]:
            samples, elapsed = execute_counted_phase(
                args.base_url, token, paths, f"concurrency-{concurrency}", concurrency, total,
            )
            all_samples.extend(samples)
            phases.append(summarize(f"concurrency-{concurrency}", concurrency, samples, elapsed))
        sustained_samples, sustained_elapsed = execute_sustained_phase(
            args.base_url, token, paths, max(workload["formalRuntime"]["httpConcurrency"]),
            workload["formalRuntime"]["sustainedSeconds"],
        )
        all_samples.extend(sustained_samples)
        phases.append(summarize("sustained", max(workload["formalRuntime"]["httpConcurrency"]),
                                sustained_samples, sustained_elapsed))
        pressure = workload["formalRuntime"]["connectionPressureConcurrency"]
        pressure_samples, pressure_elapsed = execute_counted_phase(
            args.base_url, token, paths, "connection-pressure", pressure, pressure * 4,
        )
        all_samples.extend(pressure_samples)
        phases.append(summarize("connection-pressure", pressure, pressure_samples, pressure_elapsed))
    finally:
        stop.set()
        sampler.join(timeout=15)

    # 商户令牌访问平台 SaaS 申请详情必须拒绝；使用合法格式但不存在的 ULID，避免泄漏真实身份。
    cross_tenant = request(
        args.base_url, token, "/api/v1/saas/applications/01K2A000000000000000000099",
        "cross-tenant-denial",
    )
    cross_tenant_denied = cross_tenant.http_status in {401, 403} or cross_tenant.business_code in {401, 403}
    if not cross_tenant_denied:
        raise PerformanceFailure("跨租户平台资源访问未失败关闭")

    if resource_errors or len(resources) < 3:
        raise PerformanceFailure("资源采样缺失或失败")
    gc = parse_gc_log(pathlib.Path(args.gc_log))
    limits = thresholds["formalRuntime"]
    if any(not sample.success for sample in all_samples):
        raise PerformanceFailure("正式并发或持续请求存在错误")
    if any(phase["p95Ms"] > limits["httpP95MaxMs"] or phase["p99Ms"] > limits["httpP99MaxMs"] for phase in phases):
        raise PerformanceFailure("HTTP 尾延迟超过冻结阈值")
    concurrency16 = next(item for item in phases if item["phase"] == "concurrency-16")
    if concurrency16["throughputRps"] < limits["minimumThroughputRpsAtConcurrency16"]:
        raise PerformanceFailure("并发16吞吐低于冻结阈值")
    rss_values = [item["applicationRssMiB"] for item in resources if item.get("applicationRssMiB") is not None]
    mysql_connections = [item["mysqlConnections"] for item in resources if item.get("mysqlConnections") is not None]
    redis_memory = [item["redisUsedMemoryMiB"] for item in resources if item.get("redisUsedMemoryMiB") is not None]
    if not rss_values or not mysql_connections or not redis_memory:
        raise PerformanceFailure("应用、MySQL 或 Redis 资源指标缺失")
    if max(rss_values) > limits["maxApplicationRssMiB"] or gc["maxPauseMs"] > limits["maxJvmGcPauseMs"]:
        raise PerformanceFailure("JVM 内存或 GC 超过冻结阈值")
    if max(mysql_connections) > limits["maxMysqlConnections"] or max(redis_memory) > limits["maxRedisMemoryMiB"]:
        raise PerformanceFailure("MySQL 连接或 Redis 内存超过冻结阈值")

    raw_samples = [asdict(sample) for sample in all_samples]
    canonical = json.dumps(raw_samples, sort_keys=True, separators=(",", ":")).encode()
    return {
        "schemaVersion": "1.0",
        "requirementId": "T2-PERF-002",
        "classification": "INTERNAL_FULL_STACK_PERFORMANCE_CANDIDATE",
        "status": "PASS",
        "environment": {"mysql": "FORMAL_RUNTIME", "redis": "FORMAL_RUNTIME", "transport": "HTTP_REST"},
        "executorFingerprint": fingerprint,
        "syntheticTenantSha256": hashlib.sha256(tenant_id.encode()).hexdigest(),
        "phases": phases,
        "resourceSummary": {
            "samples": len(resources),
            "maxApplicationRssMiB": max(rss_values),
            "maxApplicationCpuPercent": max((item.get("applicationCpuPercent") or 0) for item in resources),
            "maxMysqlMemoryMiB": max(item.get("mysqlMemoryMiB") or 0 for item in resources),
            "maxMysqlConnections": max(mysql_connections),
            "maxRedisContainerMemoryMiB": max(item.get("redisMemoryMiB") or 0 for item in resources),
            "maxRedisUsedMemoryMiB": max(redis_memory),
            "gc": gc,
        },
        "rawRequestSampleSha256": hashlib.sha256(canonical).hexdigest(),
        "rawRequestSampleCount": len(raw_samples),
        "resourceSamples": resources,
        "crossTenantDenied": True,
        "providerNetworkCalls": 0,
        "realFunds": 0,
        "realDeviceOrPeripheralCommands": 0,
        "commercialSla": False,
    }


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--base-url")
    parser.add_argument("--pid", type=int)
    parser.add_argument("--mysql-container", default="")
    parser.add_argument("--redis-container", default="")
    parser.add_argument("--gc-log")
    parser.add_argument("--output")
    parser.add_argument("--self-test", action="store_true")
    args = parser.parse_args()
    if args.self_test:
        self_test()
        return 0
    if not all((args.base_url, args.pid, args.mysql_container, args.redis_container, args.gc_log, args.output)):
        parser.error("正式运行需要 base-url、pid、容器、gc-log 和 output")
    try:
        result = run(args)
    except PerformanceFailure as error:
        print("T2-PERF-002 runtime failed: " + str(error))
        return 1
    output = pathlib.Path(args.output)
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(f"T2-PERF-002 runtime passed: samples={result['rawRequestSampleCount']}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
