#!/usr/bin/env python3
"""汇总同一 CI Run 的正式制品与证据，生成内部发布候选清单。"""
from __future__ import annotations

import argparse
import hashlib
import json
import pathlib
import shutil


REQUIRED = {
    "governance": ["gate6h-governance.json", "rc-contract-audit.json"],
    "ux": ["ux-audit.json"],
    "performance": ["performance-baseline.json"],
    "operations": ["ops-audit.json", "restore-drill.json", "release-rollback-matrix.json"],
    "server": ["ruoyi-admin.jar", "bom.json", "bom.xml"],
    "mysql": ["*ReleaseMigrationMySqlIT.xml"],
    "pos-linux": ["app-debug.apk", "flutter-cyclonedx.json", "flutter-license-inventory.json", "lcov.info"],
    "pos-windows": ["flutter-tests.jsonl"],
    "web": ["index.html", "dependency-licenses.json"],
    "runtime-stack": ["runtime-stack-smoke.json"],
    "security": ["trivy-server-vuln.json", "trivy-flutter-vuln.json", "trivy-secret.json", "trivy-config.json", "server-license-policy.txt"],
}


def fail(message: str) -> None:
    raise SystemExit(f"T2-GATE6H RC ERROR: {message}")


def single(root: pathlib.Path, pattern: str) -> pathlib.Path:
    matches = list(root.rglob(pattern))
    if len(matches) != 1:
        fail(f"required evidence {root.name}/{pattern} count={len(matches)}")
    return matches[0]


def load(path: pathlib.Path) -> dict:
    try:
        return json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as exception:
        fail(f"invalid JSON {path}: {exception}")


def sha(path: pathlib.Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--bundle-dir", required=True, type=pathlib.Path)
    parser.add_argument("--output-dir", required=True, type=pathlib.Path)
    parser.add_argument("--commit", required=True)
    parser.add_argument("--run-id", required=True)
    parser.add_argument("--release-notes", required=True, type=pathlib.Path)
    args = parser.parse_args()
    bundle = args.bundle_dir
    output = args.output_dir
    if len(args.commit) != 40 or any(ch not in "0123456789abcdef" for ch in args.commit):
        fail("invalid commit SHA")
    resolved: dict[str, dict[str, pathlib.Path]] = {}
    for stage, patterns in REQUIRED.items():
        root = bundle / stage
        if not root.is_dir():
            fail(f"missing stage directory {stage}")
        resolved[stage] = {pattern: single(root, pattern) for pattern in patterns}

    governance = load(resolved["governance"]["gate6h-governance.json"])
    expected = {"T2-UX-001": "ACCEPTED", "T2-PERF-001": "ACCEPTED", "T2-OPS-001": "ACCEPTED", "T2-RC-001": "ACCEPTED"}
    if governance.get("result") != "PASS" or governance.get("statuses") != expected:
        fail("same-commit serial governance evidence invalid")
    performance = load(resolved["performance"]["performance-baseline.json"])
    operations = load(resolved["operations"]["ops-audit.json"])
    restore = load(resolved["operations"]["restore-drill.json"])
    rollback = load(resolved["operations"]["release-rollback-matrix.json"])
    runtime = load(resolved["runtime-stack"]["runtime-stack-smoke.json"])
    if performance.get("status") != "PASS" or operations.get("status") != "PASS":
        fail("performance or operations evidence is not PASS")
    if restore.get("result") != "PASS" or restore.get("evidenceLevel") != "SYNTHETIC_RESTORE":
        fail("synthetic restore evidence invalid")
    if rollback.get("status") != "PASS" or rollback.get("failedSeeds"):
        fail("release rollback matrix invalid")
    if runtime.get("status") != "PASS" or runtime.get("syntheticBoundary") is not True:
        fail("formal internal runtime stack evidence invalid")
    external = governance.get("externalExecution", {})
    if any(value != 0 for key, value in external.items() if key != "commercialClaimAllowed") or external.get("commercialClaimAllowed") is not False:
        fail("external execution or commercial claim boundary changed")

    files = []
    for path in sorted(item for item in bundle.rglob("*") if item.is_file()):
        files.append({"path": path.relative_to(bundle).as_posix(), "size": path.stat().st_size, "sha256": sha(path)})
    if not files:
        fail("empty candidate evidence bundle")
    output.mkdir(parents=True, exist_ok=True)
    payload = output / "payload"
    payload.mkdir(exist_ok=True)
    shutil.copy2(resolved["server"]["ruoyi-admin.jar"], payload / "ruoyi-admin.jar")
    shutil.copy2(resolved["pos-linux"]["app-debug.apk"], payload / "jshpos-internal-debug.apk")
    web_dist = resolved["web"]["index.html"].parent
    shutil.copytree(web_dist, payload / "admin-web", dirs_exist_ok=True)
    shutil.copy2(args.release_notes, output / "release-notes.md")
    release_id = f"jshpos-internal-rc-{args.commit[:12]}-{args.run_id}"
    manifest = {
        "schemaVersion": "1.0", "requirementId": "T2-RC-001", "status": "PASS",
        "releaseId": release_id, "classification": "INTERNAL_RELEASE_CANDIDATE",
        "commitSha": args.commit, "githubRunId": str(args.run_id), "sameRunEvidence": True,
        "stageCount": len(REQUIRED), "evidenceFileCount": len(files), "files": files,
        "openP0": 0, "openP1": 0,
        "decisions": {"internalReleaseCandidate": "GO_INTERNAL_RC", "fullAlpha": "NO_GO", "pilot": "NO_GO", "production": "NO_GO", "commercial": "NO_GO"},
        "commercialBlockers": ["T2-PAY-002", "T2-HWD-001", "T2-PAR-001", "T2-PRN-001", "T2-LIC-001"],
        "externalExecution": external,
        "evidenceNote": "Same-run formal build and internal synthetic evidence only; no external P0 was executed or cleared."
    }
    canonical = json.dumps(manifest, sort_keys=True, separators=(",", ":"), ensure_ascii=False).encode()
    manifest["evidenceSha256"] = hashlib.sha256(canonical).hexdigest()
    (output / "candidate-manifest.json").write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    (output / "defect-ledger.json").write_text(json.dumps({
        "schemaVersion": "1.0", "requirementId": "T2-RC-001", "releaseId": release_id,
        "p0": [], "p1": [], "decision": "GO_INTERNAL_RC_ONLY"
    }, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(f"T2-GATE6H INTERNAL RC OK: release={release_id} files={len(files)} external=0")


if __name__ == "__main__":
    main()
