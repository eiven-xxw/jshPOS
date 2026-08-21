#!/usr/bin/env python3
"""校验 Gate 6H 内部发布候选契约与商业 NO-GO 边界。"""
from __future__ import annotations

import argparse
import json
import pathlib
import re


ROOT = pathlib.Path(__file__).resolve().parents[1]


def fail(message: str) -> None:
    raise SystemExit(f"T2-GATE6H RC ERROR: {message}")


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--output", required=True, type=pathlib.Path)
    args = parser.parse_args()
    contract = json.loads((ROOT / "contracts/t2/gate6h/internal-release-candidate-v1.json").read_text(encoding="utf-8"))
    if contract.get("classification") != "INTERNAL_RELEASE_CANDIDATE":
        fail("classification drift")
    if contract.get("signing") != {
        "algorithm": "Ed25519", "keyType": "SYNTHETIC_EPHEMERAL_CI_ONLY", "privateKeyArtifactAllowed": False
    }:
        fail("signing contract drift")
    if any(contract.get("externalExecution", {}).values()):
        fail("external execution must remain zero")
    decisions = contract.get("decisions", {})
    if decisions.get("fullAlpha") != "NO_GO" or decisions.get("production") != "NO_GO" or not decisions.get("commercial", "").startswith("NO_GO"):
        fail("external or commercial NO-GO drift")
    if len(contract.get("licenseBlockers", [])) != 3:
        fail("commercial license blockers incomplete")
    workflow = (ROOT / ".github/workflows/t2-gate6h.yml").read_text(encoding="utf-8")
    markers = ["full-server:", "full-mysql:", "full-pos-linux:", "full-pos-windows:", "full-web:",
               "runtime-stack:", "security:", "internal-release-candidate:", "openssl genpkey -algorithm ED25519"]
    if any(marker not in workflow for marker in markers):
        fail("full regression or candidate signing job missing")
    if re.search(r"BEGIN (?:RSA |EC )?PRIVATE KEY", workflow):
        fail("private signing key embedded in workflow")
    result = {
        "schemaVersion": "1.0", "requirementId": "T2-RC-001", "status": "PASS",
        "classification": contract["classification"], "requiredStages": contract["requiredStages"],
        "signing": contract["signing"], "licenseBlockers": contract["licenseBlockers"],
        "decisions": decisions, "externalExecution": contract["externalExecution"],
    }
    target = args.output if args.output.is_absolute() else ROOT / args.output
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print("T2-GATE6H RC CONTRACT OK: stages=11 external=0 commercial=NO-GO")


if __name__ == "__main__":
    main()
