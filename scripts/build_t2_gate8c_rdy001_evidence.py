#!/usr/bin/env python3
"""聚合 T2-RDY-001 内部制品、签名、供应链、部署与运维证据。"""
from __future__ import annotations

import argparse
import hashlib
import json
import pathlib
import re


PRODUCERS = {"governance-ubuntu","governance-windows","server","web","flutter-ubuntu","flutter-windows","mysql-operations","security","release-readiness"}


def fail(message: str) -> None:
    raise SystemExit("T2-RDY-001 EVIDENCE ERROR: " + message)


def one(root: pathlib.Path, pattern: str) -> pathlib.Path:
    matches = list(root.rglob(pattern))
    if len(matches) != 1:
        fail(f"expected one {root.name}/{pattern}, got {len(matches)}")
    return matches[0]


def load(path: pathlib.Path) -> dict:
    try:
        return json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as error:
        fail(f"invalid JSON {path}: {error}")


def sha(path: pathlib.Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--bundle-dir", required=True, type=pathlib.Path)
    parser.add_argument("--output", required=True, type=pathlib.Path)
    args = parser.parse_args()
    bundle = args.bundle_dir.resolve()
    present = {path.name for path in bundle.iterdir() if path.is_dir()}
    missing = PRODUCERS - present
    if missing:
        fail("missing producers: " + ", ".join(sorted(missing)))
    governance = [load(one(bundle / name, "rdy001-governance.json")) for name in ("governance-ubuntu","governance-windows")]
    if any(item.get("status") != "PASS" or item.get("requirementStatus") != "VERIFIED" for item in governance):
        fail("governance evidence invalid")
    release_root = bundle / "release-readiness"
    manifest = load(one(release_root, "artifact-manifest.json"))
    decision = load(one(release_root, "release-decision.json"))
    release_bom = load(one(release_root, "release-bom.json"))
    faults = load(one(release_root, "fault-results.json"))
    verification = one(release_root, "signature-verification.txt").read_text(encoding="utf-8", errors="replace")
    if manifest.get("status") != "PASS" or manifest.get("artifactCount") != 10 or manifest.get("productionEligible") is not False:
        fail("artifact manifest invalid")
    if len({item["artifactId"] for item in manifest["artifacts"]}) != 10 or any(item["productionEligible"] for item in manifest["artifacts"]):
        fail("artifact identities or production boundary invalid")
    for item in manifest["artifacts"]:
        path = release_root / item["path"]
        if not path.is_file() or path.stat().st_size != item["size"] or sha(path) != item["sha256"]:
            fail("artifact digest mismatch: " + item["artifactId"])
    if "Signature Verified Successfully" not in verification:
        fail("synthetic signature was not verified")
    private_markers = re.compile(rb"BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY")
    if any(private_markers.search(path.read_bytes()) for path in release_root.rglob("*") if path.is_file()):
        fail("private key present in release artifact")
    if faults.get("status") != "PASS" or faults.get("seedCount") != 14 or faults.get("failedSeeds"):
        fail("failure-close matrix incomplete")
    if release_bom.get("thirdPartyLicenseClosure") != {"closed":0,"required":3,"status":"DEFERRED_NO_GO"}:
        fail("third-party license blocker drift")
    if decision.get("internalReleaseReadiness") != "GO_INTERNAL_RELEASE_READINESS" \
            or not decision.get("fullAlpha", "").startswith("NO_GO") \
            or not decision.get("production", "").startswith("NO_GO") \
            or not decision.get("commercial", "").startswith("NO_GO"):
        fail("Go/No-Go decision drift")
    security = load(one(bundle / "security", "security-summary.json"))
    operations = load(one(bundle / "mysql-operations", "operations-evidence.json"))
    migration = load(one(bundle / "mysql-operations", "mysql-migration.json"))
    if security.get("status") != "PASS" or operations.get("status") != "PASS" or migration.get("status") != "PASS":
        fail("security migration or operations evidence invalid")
    external = manifest.get("externalExecution", {})
    if any(external.values()):
        fail("external execution non-zero")
    result = {
        "schemaVersion":"1.0","gate":"T2-GATE8C-SPRINT-S26D","status":"PASS",
        "requirementId":"T2-RDY-001","requirementStatus":"VERIFIED",
        "classification":"INTERNAL_RELEASE_READINESS_CANDIDATE",
        "releaseId":manifest["releaseId"],"commitSha":manifest["commitSha"],"githubRunId":manifest["githubRunId"],
        "artifactCount":10,"signatureVerified":True,"faultVectorCount":14,
        "internalReadinessP0":0,"internalReadinessP1":0,
        "openCommercialP0":2,"openProductionP1":2,
        "licenseClosure":{"closed":0,"required":3,"status":"DEFERRED"},
        "decisions":{"internalReleaseReadiness":"GO_INTERNAL_RELEASE_READINESS","fullAlpha":decision["fullAlpha"],"production":decision["production"],"commercial":decision["commercial"]},
        "externalExecution":external,"newBusinessCapabilities":0,"databaseMigrationsChanged":0,"dependenciesChanged":0,
        "commercialSla":False,"productionEligible":False,
    }
    canonical = json.dumps(result, ensure_ascii=False, sort_keys=True, separators=(",", ":")).encode()
    result["evidenceSha256"] = hashlib.sha256(canonical).hexdigest()
    target = args.output if args.output.is_absolute() else pathlib.Path.cwd() / args.output
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print("T2-RDY-001 EVIDENCE OK: artifacts=10 signature=PASS faults=14 internal=GO commercial=NO_GO")


if __name__ == "__main__":
    main()
