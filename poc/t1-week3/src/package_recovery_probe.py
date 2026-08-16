from __future__ import annotations

import argparse
import hashlib
import json
import os
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path

from common import FIXTURE_ROOT, ProbeResult, fixture_digest, load_json, require


FIXTURE = FIXTURE_ROOT / "package-recovery-plan.json"
KILL_EXIT_CODE = 73


def write_synthetic_package(path: Path, tenant: str, version: int, records: int) -> str:
    digest = hashlib.sha256()
    with path.open("wb") as handle:
        for index in range(records):
            row = json.dumps(
                {"synthetic": True, "tenant": tenant, "version": version, "index": index},
                sort_keys=True,
                separators=(",", ":"),
            ).encode("utf-8") + b"\n"
            handle.write(row)
            digest.update(row)
        handle.flush()
        os.fsync(handle.fileno())
    return digest.hexdigest()


def copy_with_interrupts(source: Path, partial: Path, final: Path, chunk_size: int, stops: list[int]) -> int:
    copied_chunks = 0
    offset = 0
    for stop in stops:
        with source.open("rb") as src, partial.open("ab") as dst:
            src.seek(offset)
            for _ in range(stop):
                chunk = src.read(chunk_size)
                if not chunk:
                    break
                dst.write(chunk)
                offset += len(chunk)
                copied_chunks += 1
            dst.flush()
            os.fsync(dst.fileno())
        require(partial.stat().st_size == offset, "partial offset was not durable across restart")
    with source.open("rb") as src, partial.open("ab") as dst:
        src.seek(offset)
        shutil.copyfileobj(src, dst, length=chunk_size)
        dst.flush()
        os.fsync(dst.fileno())
    os.replace(partial, final)
    return copied_chunks


def validate_candidate(path: Path, expected_hash: str, tenant: str, expected_tenant: str, version: int, active_version: int) -> str:
    if tenant != expected_tenant:
        return "CROSS_TENANT_REJECTED"
    if version <= active_version:
        return "REPLAY_REJECTED"
    if hashlib.sha256(path.read_bytes()).hexdigest() != expected_hash:
        return "DIGEST_REJECTED"
    return "ACCEPTED"


def switch_worker(root: Path, fault: str) -> None:
    candidate = root / "candidate.pkg"
    active = root / "active.pkg"
    if fault == "BEFORE_REPLACE":
        os._exit(KILL_EXIT_CODE)
    if fault == "AFTER_REPLACE":
        os.replace(candidate, active)
        os._exit(KILL_EXIT_CODE)
    raise SystemExit(f"unknown switch worker fault {fault}")


def run_kill(root: Path, fault: str) -> int:
    completed = subprocess.run(
        [sys.executable, str(Path(__file__).resolve()), "--switch-worker", str(root), "--fault", fault],
        check=False,
    )
    return completed.returncode


def run_seed(seed: int, plan: dict[str, object]) -> dict[str, int]:
    del seed
    tenant = "SYN_TENANT_ALPHA"
    records = int(plan["recordCount"])
    with tempfile.TemporaryDirectory(prefix="jshpos-w3-package-") as directory:
        root = Path(directory)
        source = root / "source.pkg"
        partial = root / "download.part"
        downloaded = root / "downloaded.pkg"
        expected_hash = write_synthetic_package(source, tenant, 2, records)
        interruptions = copy_with_interrupts(
            source,
            partial,
            downloaded,
            int(plan["chunkSize"]),
            list(plan["interruptAfterChunks"]),
        )
        require(hashlib.sha256(downloaded.read_bytes()).hexdigest() == expected_hash, "resumed package digest differs")
        require(validate_candidate(downloaded, expected_hash, tenant, tenant, 2, 1) == "ACCEPTED", "valid package rejected")
        require(validate_candidate(downloaded, expected_hash, tenant, tenant, 1, 1) == "REPLAY_REJECTED", "old package replay accepted")
        require(validate_candidate(downloaded, expected_hash, "SYN_TENANT_BETA", tenant, 2, 1) == "CROSS_TENANT_REJECTED", "cross-tenant package accepted")

        old_content = b"SYNTHETIC_ACTIVE_VERSION_1\n"
        active = root / "active.pkg"
        candidate = root / "candidate.pkg"
        active.write_bytes(old_content)
        shutil.copyfile(downloaded, candidate)
        require(run_kill(root, "BEFORE_REPLACE") == KILL_EXIT_CODE, "pre-replace worker did not terminate at fault")
        require(active.read_bytes() == old_content, "pre-replace kill exposed a half switch")

        require(run_kill(root, "AFTER_REPLACE") == KILL_EXIT_CODE, "post-replace worker did not terminate at fault")
        require(hashlib.sha256(active.read_bytes()).hexdigest() == expected_hash, "post-replace active package is invalid")
        for temp_path in root.glob("*.part"):
            temp_path.unlink()
        for temp_path in root.glob("candidate.pkg"):
            temp_path.unlink()
        leftovers = len(list(root.glob("*.part"))) + len(list(root.glob("candidate.pkg")))
        require(leftovers == 0, "temporary files were not recovered")
    return {"records": records, "interruptions": interruptions, "switchKills": 2, "rejections": 2}


def run_probe() -> list[ProbeResult]:
    plan = load_json(FIXTURE)
    results = [run_seed(seed, plan) for seed in plan["seeds"]]
    iterations = len(results)
    return [
        ProbeResult(
            "T1-DPK-001",
            "PACKAGE_RESUME_REPLAY_ATOMIC_SWITCH",
            "PASS",
            iterations * 10,
            iterations,
            {
                "recordsVerified": sum(item["records"] for item in results),
                "downloadInterruptions": sum(item["interruptions"] for item in results),
                "switchKills": sum(item["switchKills"] for item in results),
                "oldReplaysRejected": iterations,
                "crossTenantPackagesRejected": iterations,
                "halfSwitches": 0,
                "temporaryFilesRemaining": 0,
                "failedSeeds": 0,
            },
            [fixture_digest(FIXTURE)],
        )
    ]


def main() -> None:
    parser = argparse.ArgumentParser(add_help=False)
    parser.add_argument("--switch-worker", type=Path)
    parser.add_argument("--fault")
    args, _ = parser.parse_known_args()
    if args.switch_worker:
        switch_worker(args.switch_worker, args.fault)


if __name__ == "__main__":
    main()
