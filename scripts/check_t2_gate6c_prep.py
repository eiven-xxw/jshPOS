#!/usr/bin/env python3
"""Gate 6C-Prep 外部证据治理、状态和禁止执行边界门禁。"""
from __future__ import annotations

import argparse
import csv
import json
import pathlib
import re
import subprocess

ROOT = pathlib.Path(__file__).resolve().parents[1]


def fail(condition: bool, message: str) -> None:
    if not condition:
        raise SystemExit(message)


def load_json(path: str) -> dict:
    return json.loads((ROOT / path).read_text(encoding="utf-8"))


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--output", required=True)
    parser.add_argument("--baseline", default="f9b733adb2fe1715fd663bae6d2419c4eca668ff")
    args = parser.parse_args()

    with (ROOT / "docs/governance/rtm.csv").open(encoding="utf-8-sig", newline="") as stream:
        rtm = {row["requirement_id"]: row for row in csv.DictReader(stream)}
    expected = {
        "T2-UPG-001": "ACCEPTED",
        "T2-PAY-002": "BLOCKED",
        "T2-HWD-001": "BLOCKED",
        "T2-PAR-001": "BLOCKED",
        "T2-UAT-001": "DRAFT",
        "T2-REL-001": "DRAFT",
    }
    for requirement_id, status in expected.items():
        fail(rtm[requirement_id]["status"] == status, f"RTM status mismatch: {requirement_id}")

    admission = load_json("contracts/t2/gate6c-prep/gate6c-prep-admission.json")
    statuses = {item["id"]: item["status"] for item in admission["requirements"]}
    fail(statuses == expected, "Gate 6C admission/RTM mismatch")
    forbidden = set(admission["forbiddenActions"])
    fail("PAYMENT_PROVIDER_NETWORK" in forbidden, "payment network must remain forbidden")
    fail("REAL_DEVICE_COMMAND" in forbidden, "real device commands must remain forbidden")
    fail("FULL_ALPHA_UAT" in forbidden, "full Alpha must remain forbidden")

    register = load_json("contracts/t2/gate6c-prep/external-p0-evidence-register.json")
    tracks = {item["requirementId"]: item for item in register["tracks"]}
    fail(set(tracks) == {"T2-PAY-002", "T2-HWD-001", "T2-PAR-001"}, "three external tracks required")
    fail(all(item["status"] == "BLOCKED" and item["currentEvidenceLevel"] == "MISSING" for item in tracks.values()), "external evidence must remain missing/blocked")
    fail(tracks["T2-PAY-002"]["networkCalls"] == 0, "payment network calls must be zero")
    fail(tracks["T2-HWD-001"]["realDeviceCommands"] == 0, "real device commands must be zero")
    fail(tracks["T2-PAR-001"]["verifiedPartners"] == 0 and tracks["T2-PAR-001"]["verifiedWrittenIntent"] == 0, "partner evidence must not be pre-filled")
    fail(not register["fullAlphaAllowed"] and not register["commercialClaimAllowed"], "Alpha/commercial claims must remain false")

    required_docs = [
        "docs/adr/ADR-035-gate6c-external-p0-evidence-and-alpha-admission.md",
        "docs/t2-gate6c-prep/02_T2_PAY002支付沙箱解阻评审报告.md",
        "docs/t2-gate6c-prep/03_T2_HWD001真实硬件解阻评审报告.md",
        "docs/t2-gate6c-prep/04_T2_PAR001设计伙伴解阻评审报告.md",
        "docs/t2-gate6c-prep/05_Alpha_UAT差距与证据目录.md",
        "docs/t2-gate6c-prep/06_RACI截止点与升级机制.md",
        "docs/t2-gate6c-prep/07_GoNoGo与安全回退模板.md",
        "docs/t2-gate6c-prep/08_T2_Gate6CPrep启动评审报告.md",
        "docs/t2-gate6c-prep/09_Gate6C外部证据收件下一步操作指令.md",
        "contracts/t2/gate6c-prep/schemas/external-evidence-manifest.v1.schema.json",
    ]
    missing_docs = [path for path in required_docs if not (ROOT / path).is_file()]
    fail(not missing_docs, f"required Gate 6C files missing: {missing_docs}")

    changed = subprocess.check_output(
        ["git", "-c", "core.quotepath=false", "diff", "--name-only", args.baseline],
        cwd=ROOT, text=True, encoding="utf-8"
    ).splitlines()
    allowed_exact = {
        "AGENTS.md", ".github/workflows/t2-gate6c-prep.yml",
        "contracts/t2/gate6b/gate6b-admission.json",
        "docs/adr/ADR-035-gate6c-external-p0-evidence-and-alpha-admission.md", "docs/adr/README.md",
        "docs/governance/change-log.md", "docs/governance/rtm.csv",
        "docs/t2-gate6b/08_T2_Gate6B_SprintS14周门禁报告.md",
        "scripts/check_t2_gate6b.py", "scripts/check_t2_gate6c_prep.py", "scripts/build_t2_gate6c_prep_evidence.py",
    }
    allowed_prefixes = ("contracts/t2/gate6c-prep/", "docs/t2-gate6c-prep/")
    illegal = [path for path in changed if path not in allowed_exact and not path.startswith(allowed_prefixes)]
    fail(not illegal, f"runtime or out-of-scope files changed: {illegal}")

    sensitive_name = re.compile(r"(^|/)(\.env($|\.)|.*\.(pem|p12|pfx|jks|keystore|key)$)", re.IGNORECASE)
    fail(not [path for path in changed if sensitive_name.search(path)], "sensitive file name found")
    secret_patterns = [
        re.compile(r"-----BEGIN [A-Z ]*PRIVATE KEY-----"),
        re.compile(r"AKIA[0-9A-Z]{16}"),
        re.compile(r"(?i)(password|secret|token)\s*[:=]\s*['\"][^'\"]{8,}['\"]"),
    ]
    for name in changed:
        path = ROOT / name
        if path.is_file():
            content = path.read_text(encoding="utf-8", errors="ignore")
            fail(not any(pattern.search(content) for pattern in secret_patterns), f"possible secret in {name}")

    output = {
        "gate": "T2-GATE6C-PREP",
        "status": "PASS",
        "requirements": expected,
        "changedFiles": len(changed),
        "paymentNetworkCalls": 0,
        "realDeviceCommands": 0,
        "verifiedPartners": 0,
        "verifiedWrittenIntent": 0,
        "fullAlphaAllowed": False,
        "commercialClaimAllowed": False,
    }
    target = ROOT / args.output
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_text(json.dumps(output, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(output, ensure_ascii=False))


if __name__ == "__main__":
    main()
