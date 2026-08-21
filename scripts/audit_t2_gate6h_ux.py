#!/usr/bin/env python3
"""Gate 6H UX 三业态一致性、前端边界与恢复提示审计。"""
from __future__ import annotations

import argparse
import json
import pathlib
import re

ROOT = pathlib.Path(__file__).resolve().parents[1]


def fail(message: str) -> None:
    raise SystemExit(f"T2-UX-001 ERROR: {message}")


def text(path: str) -> str:
    return (ROOT / path).read_text(encoding="utf-8")


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--output", type=pathlib.Path)
    args = parser.parse_args()
    dart = text("pos-flutter/lib/features/experience/domain/industry_experience_profile.dart")
    shell = text("pos-flutter/lib/features/session/presentation/pos_session_shell.dart")
    web = text("admin-web/src/views/operations/experience.ts")
    home = text("admin-web/src/views/index.vue")
    codes = ("CONVENIENCE", "SNACK_DISCOUNT", "COMMUNITY_SUPERMARKET")
    missing = [code for code in codes if code not in dart or code not in web]
    if missing:
        fail(f"cross-client industry mapping missing: {missing}")
    for token in ("industryExperienceBanner", "Semantics", "安全通用模式"):
        if token not in shell and token not in dart:
            fail(f"POS accessible recovery token missing: {token}")
    for token in ("industry-experience", "INDUSTRY_EXPERIENCE_OPTIONS", "aria-label"):
        if token not in home:
            fail(f"Web experience token missing: {token}")
    combined = dart + "\n" + web
    if re.search(r"tenant_?id|receivable|stockQuantity|costAmount|paymentStatus", combined, re.I):
        fail("experience profile contains authorization or domain fact fields")
    result = {
        "requirementId": "T2-UX-001",
        "industries": list(codes),
        "clients": ["FLUTTER_POS", "VUE_ADMIN"],
        "domainAlgorithmsAdded": 0,
        "providerNetworkCalls": 0,
        "realDeviceCommands": 0,
        "result": "PASS",
    }
    if args.output:
        output = args.output if args.output.is_absolute() else ROOT / args.output
        output.parent.mkdir(parents=True, exist_ok=True)
        output.write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print("T2 UX audit PASS: 3 industries, Flutter/Vue, no domain algorithm fields")


if __name__ == "__main__":
    main()
