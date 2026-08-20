#!/usr/bin/env python3
"""Gate 6C 外部证据首批收件、状态守恒和离线边界门禁。"""
from __future__ import annotations

import argparse
import csv
import hashlib
import json
import pathlib
import re
import subprocess
from urllib.parse import urlparse

ROOT = pathlib.Path(__file__).resolve().parents[1]


def fail(condition: bool, message: str) -> None:
    if not condition:
        raise SystemExit(message)


def load_json(path: str) -> dict:
    return json.loads((ROOT / path).read_text(encoding="utf-8"))


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--output", required=True)
    parser.add_argument("--baseline", default="74f5100b89a7cf98acd28622e5ccdc4beb7626b7")
    args = parser.parse_args()

    with (ROOT / "docs/governance/rtm.csv").open(encoding="utf-8-sig", newline="") as stream:
        rtm = {row["requirement_id"]: row for row in csv.DictReader(stream)}
    expected = {
        "T2-PAY-002": "BLOCKED",
        "T2-HWD-001": "BLOCKED",
        "T2-PAR-001": "BLOCKED",
        "T2-UAT-001": "DRAFT",
        "T2-REL-001": "DRAFT",
    }
    for requirement_id, status in expected.items():
        fail(rtm[requirement_id]["status"] == status, f"RTM status mismatch: {requirement_id}")

    admission = load_json("contracts/t2/gate6c-intake/gate6c-intake-admission.json")
    statuses = {item["id"]: item["status"] for item in admission["requirements"]}
    fail(statuses == expected, "Gate 6C intake admission/RTM mismatch")
    counters = admission["counters"]
    fail(all(value == 0 for value in counters.values()), "external execution or partner counters must remain zero")
    forbidden = set(admission["forbiddenActions"])
    required_forbidden = {
        "PAYMENT_PROVIDER_NETWORK", "PAYMENT_SECRET_IN_REPOSITORY",
        "REAL_DEVICE_INSTALL_OR_COMMAND", "ONSITE_PILOT", "FULL_ALPHA_UAT",
        "STATUS_UNBLOCK_WITH_PUBLIC_DOCUMENT_ONLY",
    }
    fail(required_forbidden <= forbidden, "forbidden action boundary incomplete")

    manifest = load_json("contracts/t2/gate6c-intake/public-evidence-manifest.json")
    fail(manifest["evidenceLevel"] == "PUBLIC_OFFICIAL_DOCUMENT", "public evidence label changed")
    fail(not manifest["repositoryStoresRawFiles"], "raw public responses must not be stored in Git")
    sources = manifest["sources"]
    fail(len(sources) == 16, "expected 16 public source responses")
    ids = [item["evidenceId"] for item in sources]
    fail(len(ids) == len(set(ids)), "duplicate public evidence id")
    allow_domains = {
        "i.lakala.com", "paas.huifu.com", "open.yeepay.com",
        "open.chinaums.com", "prodoc.allinpay.com", "www.sunmi.com",
        "developer.sunmi.com", "www.imin.com", "oss-sg.imin.sg",
        "www.hprt.com", "www.zebra.com",
    }
    sha256 = re.compile(r"[0-9a-f]{64}")
    local_verified = 0
    local_cache = ROOT / "artifacts/t2/gate6c-intake/public-source"
    for item in sources:
        parsed = urlparse(item["url"])
        fail(parsed.scheme == "https", f"non-HTTPS public evidence: {item['evidenceId']}")
        fail(parsed.hostname == item["officialDomain"] and parsed.hostname in allow_domains,
             f"unapproved public evidence domain: {item['evidenceId']}")
        fail(item["size"] > 1000 and sha256.fullmatch(item["sha256"]) is not None,
             f"invalid size/hash: {item['evidenceId']}")
        fail(item["verification"] == "OFFICIAL_DOMAIN_HASHED", "unexpected verification label")
        fail(pathlib.PurePosixPath(item["localName"]).name == item["localName"],
             f"public evidence local name must not contain a path: {item['evidenceId']}")
        local_file = local_cache / item["localName"]
        if local_file.is_file():
            fail(local_file.stat().st_size == item["size"], f"local evidence size mismatch: {item['evidenceId']}")
            fail(hashlib.sha256(local_file.read_bytes()).hexdigest() == item["sha256"],
                 f"local evidence hash mismatch: {item['evidenceId']}")
            local_verified += 1

    payment = load_json("contracts/t2/gate6c-intake/payment-rfi-matrix.json")
    fail(payment["status"] == "BLOCKED" and payment["overallBundleStatus"] == "INCOMPLETE", "payment bundle must remain incomplete/blocked")
    fail(not payment["networkAllowed"] and payment["providerNetworkCalls"] == 0, "Provider network must remain zero")
    fail(payment["priorityOrder"] == ["LAKALA", "HUIFU", "YEEPAY", "CHINAUMS", "ALLINPAY"], "payment priority order changed")
    fail(len(payment["providers"]) == 5 and all(item["controlledMissing"] for item in payment["providers"]), "each Provider needs controlled evidence gaps")
    fail(payment["selectionDecision"] == "NOT_SELECTED" and payment["executionAdmission"] == "NO_GO", "payment execution must be NO-GO")

    hardware = load_json("contracts/t2/gate6c-intake/hardware-candidate-matrix.json")
    fail(hardware["status"] == "BLOCKED" and hardware["overallBundleStatus"] == "INCOMPLETE", "hardware bundle must remain incomplete/blocked")
    fail(not hardware["realDeviceCommandsAllowed"] and hardware["realDeviceCommands"] == 0, "real device commands must remain zero")
    fail({host["role"] for host in hardware["hosts"]} == {"PRIMARY_CANDIDATE", "COMPATIBLE_CANDIDATE"}, "primary and compatible host candidates required")
    fail(all(host["selectionStatus"] == "CANDIDATE_ONLY" and host["controlledMissing"] for host in hardware["hosts"]), "host candidate cannot be certified")
    expected_peripherals = {"PRINTER_INTEGRATED", "PRINTER_EXTERNAL", "SCANNER", "SCALE", "CASH_DRAWER", "CUSTOMER_DISPLAY"}
    fail({item["type"] for item in hardware["peripherals"]} == expected_peripherals, "hardware peripheral coverage incomplete")
    fail(hardware["executionAdmission"] == "NO_GO", "hardware execution must be NO-GO")

    partner = load_json("contracts/t2/gate6c-intake/partner-intake-status.json")
    fail(partner["status"] == "BLOCKED" and partner["realTargetCount"] == 0, "real partner targets must remain zero")
    fail(partner["targetRequired"] == 5 and partner["verifiedWrittenIntentRequired"] == 3, "partner threshold changed")
    fail(partner["verifiedWrittenIntentCount"] == 0 and partner["authorizedMaskedSampleCount"] == 0, "partner evidence must not be fabricated")
    fail(not partner["pilotAllowed"] and partner["executionAdmission"] == "NO_GO", "partner execution must be NO-GO")

    alpha = load_json("contracts/t2/gate6c-intake/alpha-p0-gap-register.json")
    fail(alpha["decision"] == "NO_GO_FULL_ALPHA", "full Alpha must remain NO-GO")
    fail(not alpha["fullAlphaAllowed"] and not alpha["commercialClaimAllowed"], "Alpha/commercial claims must be false")
    fail(all(item["status"] == "BLOCKED" and item["executionEvidence"] == 0 for item in alpha["gaps"]), "Alpha P0 gap cannot be green")

    required_docs = [
        "docs/t2-gate6c-intake/01_收件范围验真方法与证据边界.md",
        "docs/t2-gate6c-intake/02_五家支付统一RFI与首批收件台账.md",
        "docs/t2-gate6c-intake/03_T2_PAY002独立执行准入报告.md",
        "docs/t2-gate6c-intake/04_硬件RFI候选BOM与首批收件台账.md",
        "docs/t2-gate6c-intake/05_T2_HWD001独立执行准入报告.md",
        "docs/t2-gate6c-intake/06_设计伙伴RFI与证据收件包.md",
        "docs/t2-gate6c-intake/07_T2_PAR001独立执行准入报告.md",
        "docs/t2-gate6c-intake/08_Alpha_P0差距证据目录与RACI.md",
        "docs/t2-gate6c-intake/09_T2_Gate6C首批收件与离线验真评审报告.md",
        "docs/t2-gate6c-intake/10_Gate6C受控材料补件下一步操作指令.md",
    ]
    fail(all((ROOT / path).is_file() for path in required_docs), "Gate 6C intake report set incomplete")

    changed = subprocess.check_output(
        ["git", "-c", "core.quotepath=false", "diff", "--name-only", args.baseline],
        cwd=ROOT, text=True, encoding="utf-8"
    ).splitlines()
    untracked = subprocess.check_output(
        ["git", "-c", "core.quotepath=false", "ls-files", "--others", "--exclude-standard"],
        cwd=ROOT, text=True, encoding="utf-8"
    ).splitlines()
    changed = sorted(set(changed + untracked))
    tracked_raw = subprocess.check_output(
        ["git", "ls-files", "artifacts/t2/gate6c-intake/public-source"],
        cwd=ROOT, text=True, encoding="utf-8"
    ).splitlines()
    fail(not tracked_raw, "raw public response files must stay outside Git")
    allowed_exact = {
        "AGENTS.md", ".github/workflows/t2-gate6c-intake.yml",
        "docs/governance/change-log.md", "docs/governance/rtm.csv",
        "scripts/check_t2_gate6c_intake.py", "scripts/build_t2_gate6c_intake_evidence.py",
    }
    allowed_prefixes = ("contracts/t2/gate6c-intake/", "docs/t2-gate6c-intake/")
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
        "gate": "T2-GATE6C-EVIDENCE-INTAKE",
        "status": "PASS",
        "evidenceLevel": "PUBLIC_OFFICIAL_DOCUMENT_AND_STATIC_GOVERNANCE",
        "requirements": expected,
        "publicSourceResponses": len(sources),
        "localPublicSnapshotsVerified": local_verified,
        "paymentProviders": len(payment["providers"]),
        "hostCandidates": len(hardware["hosts"]),
        "peripheralTypes": len(hardware["peripherals"]),
        "providerNetworkCalls": 0,
        "realDeviceCommands": 0,
        "verifiedRealPartners": 0,
        "verifiedWrittenIntents": 0,
        "fullAlphaAllowed": False,
        "commercialClaimAllowed": False,
        "changedFiles": len(changed),
    }
    target = ROOT / args.output
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_text(json.dumps(output, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(output, ensure_ascii=False))


if __name__ == "__main__":
    main()
