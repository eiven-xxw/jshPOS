from __future__ import annotations

import argparse
import json
from pathlib import Path

from common import ROOT, ProbeResult, build_evidence
from data_package_probe import run_probe as run_data_package
from inbox_probe import run_probe as run_inbox
from payment_regression import run_probe as run_payment
from sqlite_atomic_probe import run_probe as run_sqlite
from tenant_probe import run_probe as run_tenant
from upgrade_probe import run_probe as run_upgrade


PROBES = {
    "sqlite": run_sqlite,
    "inbox": run_inbox,
    "tenant": run_tenant,
    "package": run_data_package,
    "upgrade": run_upgrade,
    "payment": run_payment,
}
LIMITATIONS = [
    "全部结果仅为隔离STATIC/FAKE技术探针，不是正式订单、支付、库存、促销或商业实现",
    "SQLite子进程os._exit只模拟进程崩溃，不等于Android实机物理断电",
    "10k/100k性能数据来自CI或开发机，只是趋势，不是主认证Android设备结论",
    "数据包与升级使用公开固定HMAC测试向量和不可安装合成字节，不是生产签名或APK认证",
    "支付矩阵不导入网络客户端、不读取凭据、不访问任何机构沙箱或生产环境",
    "实机、外设、支付沙箱和设计伙伴阻断项未执行且不得引用本证据关闭",
]


def run_domains(domains: list[str]) -> list[ProbeResult]:
    unknown = sorted(set(domains) - set(PROBES))
    if unknown:
        raise SystemExit(f"unknown Week 2 domains: {unknown}")
    return [PROBES[name]() for name in domains]


def main() -> None:
    parser = argparse.ArgumentParser(description="Run T1 Week 2 isolated synthetic risk probes")
    parser.add_argument(
        "--domains",
        default=",".join(PROBES),
        help="comma-separated subset: sqlite,inbox,tenant,package,upgrade,payment",
    )
    parser.add_argument(
        "--output",
        type=Path,
        default=ROOT / "artifacts" / "t1" / "week2" / "fake-evidence.json",
    )
    args = parser.parse_args()
    domains = [item.strip() for item in args.domains.split(",") if item.strip()]
    results = run_domains(domains)
    evidence = build_evidence(results, "FAKE", LIMITATIONS)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(evidence, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(
        "T1 WEEK2 FAKE OK: "
        f"domains={','.join(domains)}; probes={len(results)}; "
        f"assertions={sum(result.assertions for result in results)}; "
        f"iterations={sum(result.iterations for result in results)}; "
        "network=0 sandbox=0 real-device=0 commercial-business=0"
    )


if __name__ == "__main__":
    main()
