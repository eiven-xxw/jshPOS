from __future__ import annotations

import argparse
import json
from pathlib import Path


PREFIXES = (
    "lib/features/synchronization/",
    "lib/infrastructure/local_database/s3_sync_schema.dart",
    "lib/infrastructure/local_database/pos_local_database.dart",
)


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--input", type=Path, required=True)
    parser.add_argument("--minimum", type=float, default=0.90)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    current = ""
    files: dict[str, list[int]] = {}
    for raw in args.input.read_text(encoding="utf-8").splitlines():
        if raw.startswith("SF:"):
            current = raw[3:].replace("\\", "/")
            files.setdefault(current, [0, 0])
        elif raw.startswith("DA:") and current:
            hits = int(raw[3:].split(",", 1)[1])
            files[current][0] += 1
            files[current][1] += int(hits > 0)
    selected = {name: value for name, value in files.items() if name.startswith(PREFIXES)}
    if not selected:
        raise SystemExit("FLUTTER COVERAGE ERROR: no Sprint S3 runtime files found")
    found = sum(value[0] for value in selected.values())
    covered = sum(value[1] for value in selected.values())
    ratio = covered / found
    if ratio < args.minimum:
        raise SystemExit(f"FLUTTER COVERAGE ERROR: {ratio:.4f} below {args.minimum:.4f}")
    report = {
        "schemaVersion": "1.0", "scope": "T2 Sprint S3 formal POS synchronization runtime",
        "coveredLines": covered, "foundLines": found, "lineRatio": round(ratio, 6),
        "minimum": args.minimum,
        "files": {name: {"found": value[0], "covered": value[1]} for name, value in sorted(selected.items())},
    }
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(f"FLUTTER SPRINT3 COVERAGE OK: lines={covered}/{found} ratio={ratio:.4f} minimum={args.minimum:.2f}")


if __name__ == "__main__":
    main()
