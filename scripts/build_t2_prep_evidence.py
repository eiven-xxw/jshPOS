from __future__ import annotations

import argparse
import csv
import hashlib
import json
import subprocess
from collections import Counter
from datetime import datetime, timezone
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
BASE_COMMIT = "962c4ed5e631bccd5c6fff737ed8e97fb665fd03"


def git(*args: str) -> str:
    return subprocess.check_output(
        ["git", *args], cwd=ROOT, text=True, encoding="utf-8", errors="replace"
    ).strip()


def sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--output-dir", type=Path, required=True)
    args = parser.parse_args()
    output = args.output_dir if args.output_dir.is_absolute() else ROOT / args.output_dir
    output.mkdir(parents=True, exist_ok=True)

    with (ROOT / "docs" / "governance" / "rtm.csv").open(encoding="utf-8-sig", newline="") as handle:
        rows = list(csv.DictReader(handle))
    t1 = Counter(row["status"] for row in rows if row["requirement_id"].startswith("T1-"))
    t2p = Counter(row["status"] for row in rows if row["requirement_id"].startswith("T2P-"))
    t2 = Counter(row["status"] for row in rows if row["requirement_id"].startswith("T2-"))
    changed = sorted(filter(None, git("-c", "core.quotepath=false", "diff", "--name-only", BASE_COMMIT).splitlines()))
    inventory = {
        "schemaVersion": "1.0",
        "phase": "T2-PREP",
        "generatedAt": datetime.now(timezone.utc).isoformat().replace("+00:00", "Z"),
        "baseCommit": BASE_COMMIT,
        "commitSha": git("rev-parse", "HEAD"),
        "candidateTag": "t2-prep-baseline-2026-08-16",
        "candidateTagState": "SEALED",
        "candidateTagTarget": "557ba270479935d6b44968cf70b47033f7d3d656",
        "changedFiles": changed,
        "requirements": {"T1": dict(t1), "T2P": dict(t2p), "T2": dict(t2)},
        "productionPathChanges": [],
        "dependencyManifestChanges": [],
        "runtimeDependenciesAdded": [],
        "externalEvidence": {"sandbox": 0, "realDevice": 0, "pilot": 0},
        "limitations": ["T2 coding remains NO-GO", "not commercial acceptance"],
    }
    inventory_path = output / "t2-prep-inventory.json"
    inventory_path.write_text(json.dumps(inventory, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")

    files = []
    for path in sorted(output.rglob("*")):
        if path.is_file() and path.name != "t2-prep-evidence-index.json":
            files.append(
                {
                    "path": path.relative_to(output).as_posix(),
                    "size": path.stat().st_size,
                    "sha256": sha256(path),
                }
            )
    index = {
        "schemaVersion": "1.0",
        "phase": "T2-PREP",
        "evidenceLevel": "STATIC",
        "generatedAt": datetime.now(timezone.utc).isoformat().replace("+00:00", "Z"),
        "baseCommit": BASE_COMMIT,
        "commitSha": git("rev-parse", "HEAD"),
        "files": files,
        "limitations": [
            "governance and static boundary evidence only",
            "does not contain SANDBOX REAL_DEVICE PILOT or commercial evidence",
        ],
    }
    index_path = output / "t2-prep-evidence-index.json"
    index_path.write_text(json.dumps(index, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(f"T2-PREP EVIDENCE OK: files={len(files)} index={sha256(index_path)}")


if __name__ == "__main__":
    main()
