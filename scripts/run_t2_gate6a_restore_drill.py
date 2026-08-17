from __future__ import annotations

import argparse
import json
import re
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
REQUIRED_CHECKS = {
    "MANIFEST", "SHA256", "ENCRYPTION", "FLYWAY_VALIDATE", "PROJECTION_REBUILD",
    "TENANT_RECONCILIATION", "BUSINESS_DAY_RECONCILIATION", "CURSOR", "AUDIT",
}


def fail(message: str) -> None:
    raise SystemExit(f"T2-GATE6A RESTORE ERROR: {message}")


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--input", type=Path,
                        default=Path("server/ruoyi-modules/jshpos-resilience/target/gate6a/restore-drill.json"))
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    source = args.input if args.input.is_absolute() else ROOT / args.input
    if not source.is_file():
        fail(f"JUnit restore evidence missing: {source}")
    data = json.loads(source.read_text(encoding="utf-8"))
    if data.get("requirement") != "T2-BAK-001" or data.get("evidenceLevel") != "SYNTHETIC_RESTORE":
        fail("evidence identity or level changed")
    if data.get("isolatedEnvironment") is not True or data.get("result") != "PASS":
        fail("empty synthetic restore did not pass")
    if set(data.get("checks", [])) != REQUIRED_CHECKS or len(data.get("checks", [])) != len(REQUIRED_CHECKS):
        fail("restore reconciliation checks are incomplete or duplicated")
    if not 0 <= data.get("rpoSeconds", -1) <= 900 or not 1 <= data.get("rtoSeconds", 0) <= 3600:
        fail("Alpha-candidate internal RPO/RTO target not proven")
    if data.get("syntheticFactRows") != 1_000_000:
        fail("one-million synthetic fact replay digest missing")
    for name in ("syntheticFactDigestSha256", "evidenceSha256"):
        if not re.fullmatch(r"[a-f0-9]{64}", str(data.get(name, ""))):
            fail(f"invalid {name}")
    if any(data.get(name) != 0 for name in ("providerNetworkCalls", "realDeviceCommands", "cloudDrEvidence")):
        fail("external evidence or command boundary changed")
    if data.get("commercialSla") is not False:
        fail("synthetic exercise must not be represented as a commercial SLA")
    output = args.output if args.output.is_absolute() else ROOT / args.output
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(data, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print("T2-GATE6A RESTORE OK: level=SYNTHETIC_RESTORE facts=1000000 cloudDr=0 commercialSla=false")


if __name__ == "__main__":
    main()
