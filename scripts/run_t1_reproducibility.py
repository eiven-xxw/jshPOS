from __future__ import annotations

import argparse
import hashlib
import json
import platform
import subprocess
import sys
import time
from pathlib import Path
from typing import Any


ROOT = Path(__file__).resolve().parents[1]
BASELINE_TAG = "t0-baseline-2026-08-16"
VOLATILE_METRIC_FRAGMENTS = ("second", "duration", "p95", "memory", "mib", "ratio", "peak")
RUNS = (
    ("T1-WEEK1", ROOT / "poc" / "t1-week1" / "src" / "t1_fake_harness.py"),
    ("T1-WEEK2", ROOT / "poc" / "t1-week2" / "src" / "t1_week2_harness.py"),
    ("T1-WEEK3", ROOT / "poc" / "t1-week3" / "src" / "t1_week3_harness.py"),
)


def fail(message: str) -> None:
    print(f"T1 REPRODUCIBILITY ERROR: {message}", file=sys.stderr)
    raise SystemExit(1)


def git(*args: str) -> str:
    completed = subprocess.run(
        ["git", *args], cwd=ROOT, check=False, capture_output=True, text=True,
        encoding="utf-8", errors="replace",
    )
    if completed.returncode != 0:
        fail(f"git {' '.join(args)} failed: {completed.stderr.strip()}")
    return completed.stdout.strip()


def sha256_bytes(value: bytes) -> str:
    return hashlib.sha256(value).hexdigest()


def canonical_bytes(value: Any) -> bytes:
    return json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":")).encode("utf-8")


def normalize(value: Any, parent: str = "") -> Any:
    if isinstance(value, dict):
        result: dict[str, Any] = {}
        for key in sorted(value):
            lowered = key.lower()
            if key == "generatedAt":
                continue
            if parent == "metrics" and any(fragment in lowered for fragment in VOLATILE_METRIC_FRAGMENTS):
                continue
            result[key] = normalize(value[key], key)
        return result
    if isinstance(value, list):
        normalized = [normalize(item, parent) for item in value]
        if all(isinstance(item, dict) and "requirementId" in item for item in normalized):
            return sorted(normalized, key=lambda item: (item["requirementId"], item.get("domain", "")))
        return normalized
    return value


def input_tree_digest() -> tuple[str, int]:
    tracked = git("-c", "core.quotepath=false", "ls-files").splitlines()
    reproducibility_scripts = {
        "scripts/run_t1_reproducibility.py",
        "scripts/compare_t1_reproducibility.py",
        "scripts/build_t1_poc_inventory.py",
    }
    selected = sorted(
        path for path in tracked
        if path.startswith(("contracts/poc/t1/", "poc/t1-week1/", "poc/t1-week2/", "poc/t1-week3/"))
        or (path.startswith("scripts/check_t1_") and path.endswith(".py"))
        or path in reproducibility_scripts
        or (path.startswith(".github/workflows/t1-week") and path.endswith(".yml"))
    )
    if not selected:
        fail("no tracked T1 reproducibility inputs found")
    entries = []
    for path in selected:
        blob = git("rev-parse", f"HEAD:{path}")
        entries.append({"path": path, "blob": blob})
    return sha256_bytes(canonical_bytes(entries)), len(entries)


def run_evidence(phase: str, script: Path, output: Path) -> tuple[dict[str, Any], float, str]:
    started = time.perf_counter()
    completed = subprocess.run(
        [sys.executable, str(script), "--output", str(output)],
        cwd=ROOT,
        check=False,
        capture_output=True,
        text=True,
        encoding="utf-8",
        errors="replace",
    )
    elapsed = time.perf_counter() - started
    if completed.returncode != 0:
        fail(f"{phase} failed: {completed.stdout}\n{completed.stderr}")
    document = json.loads(output.read_text(encoding="utf-8"))
    if document.get("phase") != phase or document.get("evidenceLevel") != "FAKE":
        fail(f"{phase} produced wrong evidence classification")
    failures = [item for item in document.get("results", []) if item.get("result") != "PASS"]
    if failures:
        fail(f"{phase} contains non-PASS results: {failures}")
    return document, elapsed, completed.stdout.strip()


def main() -> None:
    parser = argparse.ArgumentParser(description="Repeat T1 Week 1-3 and build normalized evidence")
    parser.add_argument("--platform", required=True, choices=("ubuntu", "windows", "local"))
    parser.add_argument("--output-dir", required=True, type=Path)
    args = parser.parse_args()
    args.output_dir.mkdir(parents=True, exist_ok=True)

    commit = git("rev-parse", "HEAD")
    if git("cat-file", "-t", BASELINE_TAG) != "tag":
        fail("approved T0 baseline is no longer annotated")
    if subprocess.run(["git", "merge-base", "--is-ancestor", BASELINE_TAG, "HEAD"], cwd=ROOT).returncode != 0:
        fail("HEAD is not based on approved T0 baseline")

    documents: list[dict[str, Any]] = []
    durations: dict[str, float] = {}
    raw_hashes: dict[str, str] = {}
    outputs: dict[str, str] = {}
    for phase, script in RUNS:
        output = args.output_dir / f"{phase.lower()}-raw-evidence.json"
        document, elapsed, stdout = run_evidence(phase, script, output)
        documents.append(document)
        durations[phase] = round(elapsed, 3)
        raw_hashes[phase] = sha256_bytes(output.read_bytes())
        outputs[phase] = stdout

    normalized = {"schemaVersion": "4.0", "baselineTag": BASELINE_TAG, "commitSha": commit, "evidence": [normalize(item) for item in documents]}
    normalized_path = args.output_dir / "normalized-evidence.json"
    normalized_path.write_bytes(canonical_bytes(normalized) + b"\n")
    tree_digest, input_count = input_tree_digest()
    ledger_path = ROOT / "poc" / "t1-week3" / "fixtures" / "failed-seed-ledger.json"
    ledger = json.loads(ledger_path.read_text(encoding="utf-8"))
    observed = set(ledger["observedFailedSeeds"])
    fixed = set(ledger["fixedFailedSeeds"])
    if observed - fixed:
        fail(f"unfixed failed seeds: {sorted(observed - fixed)}")

    phase_stats = {}
    for document in documents:
        results = document.get("results", [])
        phase_stats[document["phase"]] = {
            "results": len(results),
            "assertions": sum(int(item.get("assertions", 0)) for item in results),
            "iterations": sum(int(item.get("iterations", 0)) for item in results),
        }
    summary = {
        "schemaVersion": "4.0",
        "phase": "T1-WEEK4",
        "evidenceLevel": "STATIC",
        "sourceEvidenceLevel": "FAKE",
        "platformLabel": args.platform,
        "runtimePlatform": platform.platform(),
        "pythonVersion": platform.python_version(),
        "baselineTag": BASELINE_TAG,
        "commitSha": commit,
        "inputTreeDigest": tree_digest,
        "inputFileCount": input_count,
        "normalizedEvidenceSha256": sha256_bytes(normalized_path.read_bytes()),
        "failedSeedLedgerDigest": sha256_bytes(canonical_bytes(ledger)),
        "failedSeedSummary": {"observed": len(observed), "fixed": len(fixed), "untracked": len(observed - fixed)},
        "phaseStats": phase_stats,
        "rawEvidenceSha256": raw_hashes,
        "volatileDurationsSeconds": durations,
        "runnerOutput": outputs,
        "limitations": [
            "跨平台摘要排除生成时间和性能趋势字段，原始证据及其SHA-256单独保留",
            "重复验证只覆盖Week 1至3的STATIC/FAKE，不包含支付沙箱、真实网络或真实资金",
            "进程故障不等于Android实机物理断电，虚构App和数据包不等于APK或设备认证",
        ],
    }
    summary_path = args.output_dir / "reproducibility-summary.json"
    summary_path.write_text(json.dumps(summary, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(
        "T1 REPRODUCIBILITY OK: "
        f"platform={args.platform} inputFiles={input_count} normalizedSha256={summary['normalizedEvidenceSha256']} "
        f"failedSeeds={len(observed - fixed)}"
    )


if __name__ == "__main__":
    main()
