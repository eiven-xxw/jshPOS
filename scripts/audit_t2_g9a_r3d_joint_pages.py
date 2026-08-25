#!/usr/bin/env python3
"""只读汇总 G9A-R3A/R3B/R3C 的 26 页验收证据。"""
from __future__ import annotations

import argparse
import hashlib
import json
import pathlib


ROOT = pathlib.Path(__file__).resolve().parents[1]
CONTRACT = ROOT / "contracts/t2/gate9b-r3d-prep"


def sha256(path: pathlib.Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for block in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def load(path: pathlib.Path) -> dict:
    return json.loads(path.read_text(encoding="utf-8"))


def collect() -> tuple[list[dict], list[dict]]:
    rollup = load(CONTRACT / "surface-rollup-v1.json")
    surfaces: list[dict] = []
    sources: list[dict] = []
    for source in rollup["sources"]:
        path = ROOT / source["path"]
        actual_hash = sha256(path)
        if actual_hash != source["sha256"]:
            raise AssertionError(f"immutable source digest drift: {source['path']}")
        document = load(path)
        if document["batchResult"] != "VERIFIED_CANDIDATE":
            raise AssertionError(f"batch is not verified candidate: {source['batch']}")
        if document["findingId"] != "G9A-UI-P1-001" or document["overallFindingState"] != "OPEN":
            raise AssertionError(f"finding boundary drift: {source['batch']}")
        actual_ids = [item["surfaceId"] for item in document["surfaces"]]
        if actual_ids != source["surfaceIds"]:
            raise AssertionError(f"surface identity drift: {source['batch']}")
        for item in document["surfaces"]:
            if len(item["statuses"]) != 12 or item["statuses"][-1] != "PASS":
                raise AssertionError(f"direct evidence incomplete: {item['surfaceId']}")
            if any(status not in {"PASS", "NOT_APPLICABLE"} for status in item["statuses"]):
                raise AssertionError(f"batch dimension not accepted: {item['surfaceId']}")
            for evidence in item["evidence"]:
                if not (ROOT / evidence).is_file():
                    raise AssertionError(f"missing page evidence: {item['surfaceId']} -> {evidence}")
            surfaces.append({"batch": source["batch"], **item})
        sources.append({
            "batch": source["batch"],
            "path": source["path"],
            "sha256": actual_hash,
            "surfaceCount": len(actual_ids),
            "acceptedEvidenceRun": source["acceptedEvidenceRun"],
        })
    expected = set(rollup["expectedSurfaceIds"])
    actual = {item["surfaceId"] for item in surfaces}
    if len(surfaces) != 26 or len(actual) != 26 or actual != expected:
        raise AssertionError("26-page identity closure failed")
    return surfaces, sources


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--output-dir")
    args = parser.parse_args()
    surfaces, sources = collect()
    journeys = load(CONTRACT / "cross-page-journeys-v1.json")
    journey_ids = [surface for journey in journeys["journeys"] for surface in journey["surfaces"]]
    if len(journey_ids) != 26 or len(set(journey_ids)) != 26:
        raise AssertionError("cross-page journeys must cover every surface exactly once")
    seeds = load(CONTRACT / "joint-failure-seeds-v1.json")
    if seeds["summary"] != {"openP0": 0, "openP1": 3}:
        raise AssertionError("joint seed register drift")
    summary = {
        "schemaVersion": "1.0",
        "gate": "T2-GATE9B-G9A-R3D-PREP",
        "result": "PASS_PREP_SOURCE_CLOSURE",
        "surfaceCount": 26,
        "vueCount": 20,
        "flutterCount": 6,
        "sourceBatchCount": 3,
        "journeyCount": 3,
        "openP0": 0,
        "openP1": 3,
        "findingState": "OPEN",
        "runtimeChanges": 0,
        "externalExecution": 0,
    }
    if args.output_dir:
        output = pathlib.Path(args.output_dir)
        output.mkdir(parents=True, exist_ok=True)
        (output / "summary.json").write_text(
            json.dumps(summary, ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
        )
        (output / "surface-rollup.json").write_text(
            json.dumps({"sources": sources, "surfaces": surfaces}, ensure_ascii=False, indent=2) + "\n",
            encoding="utf-8",
        )
        (output / "journey-summary.json").write_text(
            json.dumps(journeys, ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
        )
    print(
        "G9A-R3D JOINT AUDIT OK: surfaces=26 vue=20 flutter=6 "
        "journeys=3 openP0=0 openP1=3 finding=OPEN"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
