#!/usr/bin/env python3
"""检查 Gate 6H 内部运维配置、恢复与升级边界。"""
from __future__ import annotations

import argparse
import json
import pathlib
import re


ROOT = pathlib.Path(__file__).resolve().parents[1]


def fail(message: str) -> None:
    raise SystemExit(f"T2-GATE6H OPS ERROR: {message}")


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--output", required=True, type=pathlib.Path)
    args = parser.parse_args()
    profile = json.loads((ROOT / "contracts/t2/gate6h/ops-profile-v1.json").read_text(encoding="utf-8"))
    if profile.get("classification") != "INTERNAL_SYNTHETIC_OPERATIONS":
        fail("classification drift")
    if any(profile.get("externalExecution", {}).values()):
        fail("external execution must remain zero")
    compose = (ROOT / "infra/compose/compose.yaml").read_text(encoding="utf-8")
    images = re.findall(r"^\s*image:\s*(\S+)", compose, re.MULTILINE)
    if len(images) != 2 or any("@sha256:" not in image for image in images):
        fail("MySQL and Redis images must be digest pinned")
    for forbidden in ("privileged: true", "network_mode: host", "MYSQL_ROOT_PASSWORD: root"):
        if forbidden in compose:
            fail(f"unsafe compose marker: {forbidden}")
    if compose.count("healthcheck:") != 2 or ":?copy .env.example" not in compose:
        fail("compose health or fail-closed secret contract incomplete")
    override = (ROOT / "infra/internal-rc/application-internal-rc.yml").read_text(encoding="utf-8")
    required = ["${JSH_POS_MYSQL_JDBC_URL}", "${JSH_POS_MYSQL_PASSWORD}",
                "${JSH_POS_REDIS_PASSWORD}", "health,info,metrics,logfile", "when_authorized"]
    if any(marker not in override for marker in required):
        fail("internal runtime override is incomplete")
    if "include: '*'" in override or "show-details: ALWAYS" in override:
        fail("internal runtime exposes unsafe actuator details")
    for path in (
        "docs/t2-gate6a/03_备份恢复设计与运行手册.md",
        "docs/t2-gate6b/05_运行手册故障注入测试与CI.md",
        "docs/t2-gate6h/06_T2_OPS001设计准入与运行手册.md",
        "scripts/collect_t2_gate6h_diagnostics.py",
    ):
        if not (ROOT / path).is_file():
            fail(f"required operations asset missing: {path}")
    result = {
        "schemaVersion": "1.0", "requirementId": "T2-OPS-001", "status": "PASS",
        "classification": profile["classification"], "pinnedImages": images,
        "requiredCapabilities": profile["requiredCapabilities"],
        "healthSignals": profile["healthSignals"], "externalExecution": profile["externalExecution"],
        "commercialSla": False,
    }
    target = args.output if args.output.is_absolute() else ROOT / args.output
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print("T2-GATE6H OPS OK: capabilities=8 external=0 commercialSla=false")


if __name__ == "__main__":
    main()
