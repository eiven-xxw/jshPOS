from __future__ import annotations

import argparse
import json
from pathlib import Path

from common import ROOT, ProbeResult, build_evidence
from package_recovery_probe import run_probe as run_package
from payment_convergence_probe import run_probe as run_payment
from sync_cross_fault_probe import run_probe as run_sync
from tenant_regression_probe import run_probe as run_tenant
from upgrade_compat_probe import run_probe as run_upgrade


PROBES = {
    "sync": run_sync,
    "payment": run_payment,
    "package": run_package,
    "upgrade": run_upgrade,
    "tenant": run_tenant,
}
LIMITATIONS = [
    "全部结果仅为隔离STATIC/FAKE交叉故障探针，不是正式订单、支付、库存、促销或商业实现",
    "SQLite关闭重开与子进程os._exit只模拟进程故障，不等于Android实机物理断电",
    "支付探针不导入网络客户端、不读取凭据、不访问机构沙箱或生产环境",
    "数据包、App、Schema、租户、支付与对账均为虚构合成数据，不是APK、生产签名或真实商户资料",
    "实机、外设、支付沙箱、真实网络和设计伙伴阻断项未执行且不得引用本证据关闭",
    "FAKE证据不得替代SANDBOX、REAL_DEVICE、PILOT或商业验收",
]


def run_domains(domains: list[str]) -> list[ProbeResult]:
    unknown = sorted(set(domains) - set(PROBES))
    if unknown:
        raise SystemExit(f"unknown Week 3 domains: {unknown}")
    results: list[ProbeResult] = []
    for name in domains:
        results.extend(PROBES[name]())
    ids = [result.requirementId for result in results]
    if len(ids) != len(set(ids)):
        raise SystemExit(f"duplicate Week 3 result requirement IDs: {ids}")
    return results


def main() -> None:
    parser = argparse.ArgumentParser(description="Run T1 Week 3 isolated synthetic cross-fault probes")
    parser.add_argument("--domains", default=",".join(PROBES), help="comma-separated subset: sync,payment,package,upgrade,tenant")
    parser.add_argument(
        "--output",
        type=Path,
        default=ROOT / "artifacts" / "t1" / "week3" / "fake-evidence.json",
    )
    args = parser.parse_args()
    domains = [item.strip() for item in args.domains.split(",") if item.strip()]
    results = run_domains(domains)
    evidence = build_evidence(results, "FAKE", LIMITATIONS)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(evidence, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(
        "T1 WEEK3 FAKE OK: "
        f"domains={','.join(domains)}; results={len(results)}; "
        f"assertions={sum(result.assertions for result in results)}; "
        f"iterations={sum(result.iterations for result in results)}; "
        "failed-seeds=0 network=0 sandbox=0 real-device=0 commercial-business=0"
    )


if __name__ == "__main__":
    main()
