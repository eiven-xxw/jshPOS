from __future__ import annotations

import argparse
import hashlib
import json
import sys
from datetime import datetime, timezone
from pathlib import Path
from typing import Any


BASELINE_TAG = "t0-baseline-2026-08-16"


def fail(message: str) -> None:
    print(f"T1 REPRODUCIBILITY COMPARE ERROR: {message}", file=sys.stderr)
    raise SystemExit(1)


def load(path: Path) -> dict[str, Any]:
    try:
        return json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as exc:
        fail(f"cannot read {path}: {exc}")


def sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def main() -> None:
    parser = argparse.ArgumentParser(description="Compare Ubuntu and Windows T1 reproduction evidence")
    parser.add_argument("--ubuntu-dir", required=True, type=Path)
    parser.add_argument("--windows-dir", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    args = parser.parse_args()
    pairs = {"ubuntu": args.ubuntu_dir, "windows": args.windows_dir}
    summaries = {name: load(path / "reproducibility-summary.json") for name, path in pairs.items()}

    for name, summary in summaries.items():
        if summary.get("platformLabel") != name:
            fail(f"{name} artifact has label {summary.get('platformLabel')}")
        if summary.get("baselineTag") != BASELINE_TAG:
            fail(f"{name} artifact has wrong baseline")
        if summary.get("failedSeedSummary", {}).get("untracked") != 0:
            fail(f"{name} has untracked failed seeds")

    stable_fields = (
        "commitSha", "inputTreeDigest", "inputFileCount", "normalizedEvidenceSha256",
        "failedSeedLedgerDigest", "failedSeedSummary", "phaseStats",
    )
    mismatches = {
        field: {name: summary.get(field) for name, summary in summaries.items()}
        for field in stable_fields
        if summaries["ubuntu"].get(field) != summaries["windows"].get(field)
    }
    normalized_hashes = {name: sha256(path / "normalized-evidence.json") for name, path in pairs.items()}
    if normalized_hashes["ubuntu"] != normalized_hashes["windows"]:
        mismatches["normalizedArtifactBytes"] = normalized_hashes
    if mismatches:
        fail(f"cross-platform semantic evidence mismatch: {json.dumps(mismatches, ensure_ascii=False)}")

    result = {
        "schemaVersion": "4.0",
        "phase": "T1-WEEK4",
        "evidenceLevel": "STATIC",
        "sourceEvidenceLevel": "FAKE",
        "generatedAt": datetime.now(timezone.utc).isoformat().replace("+00:00", "Z"),
        "baselineTag": BASELINE_TAG,
        "commitSha": summaries["ubuntu"]["commitSha"],
        "platforms": ["ubuntu", "windows"],
        "reproducible": True,
        "normalizedEvidenceSha256": normalized_hashes["ubuntu"],
        "inputTreeDigest": summaries["ubuntu"]["inputTreeDigest"],
        "inputFileCount": summaries["ubuntu"]["inputFileCount"],
        "failedSeedLedgerDigest": summaries["ubuntu"]["failedSeedLedgerDigest"],
        "failedSeedSummary": summaries["ubuntu"]["failedSeedSummary"],
        "phaseStats": summaries["ubuntu"]["phaseStats"],
        "rawEvidenceSha256ByPlatform": {name: summary["rawEvidenceSha256"] for name, summary in summaries.items()},
        "volatileDurationsSecondsByPlatform": {name: summary["volatileDurationsSeconds"] for name, summary in summaries.items()},
        "limitations": [
            "字节一致仅适用于移除生成时间和性能趋势后的normalized-evidence.json",
            "两平台原始证据因时间戳和执行器性能允许不同并分别保留SHA-256",
            "此结果仍是STATIC对FAKE复跑的证明，不替代SANDBOX、REAL_DEVICE、物理断电或商业验收",
        ],
    }
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(
        "T1 REPRODUCIBILITY COMPARE OK: "
        f"commit={result['commitSha']} normalizedSha256={result['normalizedEvidenceSha256']} platforms=ubuntu,windows"
    )


if __name__ == "__main__":
    main()
