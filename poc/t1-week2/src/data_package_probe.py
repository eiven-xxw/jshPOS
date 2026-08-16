from __future__ import annotations

import copy
import hashlib
import hmac
import json
import os
import sqlite3
import tempfile
import time
import tracemalloc
from pathlib import Path
from typing import Any

from common import FIXTURE_ROOT, ProbeResult, canonical_hash, fixture_digest, load_json, require


FAKE_MAC_KEY = b"JSH-POS-WEEK2-PUBLIC-FAKE-TEST-VECTOR"


def write_records(path: Path, count: int, start: int = 0) -> None:
    with path.open("w", encoding="utf-8", newline="\n") as handle:
        for index in range(start, start + count):
            record = {
                "recordId": f"SYN-RECORD-{index:08d}",
                "tenantId": "TENANT_ALPHA",
                "priceMinor": 100 + index % 10000,
                "schemaVersion": 1,
                "synthetic": True,
            }
            handle.write(json.dumps(record, ensure_ascii=False, sort_keys=True, separators=(",", ":")) + "\n")


def sha256_stream(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def count_records(path: Path) -> int:
    with path.open("rb") as handle:
        return sum(1 for _ in handle)


def sign_manifest(fields: dict[str, Any]) -> str:
    encoded = json.dumps(fields, ensure_ascii=False, sort_keys=True, separators=(",", ":")).encode("utf-8")
    return hmac.new(FAKE_MAC_KEY, encoded, hashlib.sha256).hexdigest()


def make_manifest(
    path: Path,
    package_id: str,
    mode: str,
    version: int,
    base_version: int | None,
    record_count: int,
) -> dict[str, Any]:
    fields: dict[str, Any] = {
        "manifestVersion": "2.0",
        "packageId": package_id,
        "tenantId": "TENANT_ALPHA",
        "mode": mode,
        "version": version,
        "baseVersion": base_version,
        "schemaVersion": 1,
        "recordCount": record_count,
        "contentSha256": sha256_stream(path),
        "signatureMode": "HMAC_SHA256_FAKE_TEST_VECTOR_ONLY",
        "synthetic": True,
    }
    fields["testVectorMac"] = sign_manifest(fields)
    return fields


def validate_package(
    path: Path,
    manifest: dict[str, Any],
    active_version: int,
    expected_tenant: str = "TENANT_ALPHA",
    supported_schema: int = 1,
) -> tuple[bool, str]:
    if not manifest.get("synthetic"):
        return False, "NON_SYNTHETIC"
    if manifest.get("signatureMode") != "HMAC_SHA256_FAKE_TEST_VECTOR_ONLY":
        return False, "SIGNATURE_MODE"
    if manifest.get("tenantId") != expected_tenant:
        return False, "TENANT"
    if manifest.get("schemaVersion") != supported_schema:
        return False, "SCHEMA"
    signed = {key: value for key, value in manifest.items() if key != "testVectorMac"}
    if not hmac.compare_digest(sign_manifest(signed), manifest.get("testVectorMac", "")):
        return False, "TEST_MAC"
    if not path.exists() or sha256_stream(path) != manifest.get("contentSha256"):
        return False, "DIGEST"
    if count_records(path) != manifest.get("recordCount"):
        return False, "COUNT"
    if manifest.get("version", 0) <= active_version:
        return False, "REPLAY"
    if manifest.get("mode") == "INCREMENTAL" and manifest.get("baseVersion") != active_version:
        return False, "VERSION_GAP"
    return True, "OK"


class ActivePackageStore:
    def __init__(self) -> None:
        self.connection = sqlite3.connect(":memory:")
        self.connection.execute(
            "CREATE TABLE syn_active_package(tenant_id TEXT PRIMARY KEY, version INTEGER NOT NULL, package_id TEXT NOT NULL, content_hash TEXT NOT NULL)"
        )
        self.connection.execute(
            "INSERT INTO syn_active_package VALUES ('TENANT_ALPHA', 0, 'SYN-PKG-EMPTY', ?) ",
            ("0" * 64,),
        )
        self.connection.commit()

    def active_version(self) -> int:
        return self.connection.execute(
            "SELECT version FROM syn_active_package WHERE tenant_id='TENANT_ALPHA'"
        ).fetchone()[0]

    def activate(self, manifest: dict[str, Any], fail_before_commit: bool = False) -> None:
        self.connection.execute("BEGIN IMMEDIATE")
        try:
            self.connection.execute(
                "UPDATE syn_active_package SET version=?, package_id=?, content_hash=? WHERE tenant_id='TENANT_ALPHA'",
                (manifest["version"], manifest["packageId"], manifest["contentSha256"]),
            )
            if fail_before_commit:
                raise RuntimeError("SYNTHETIC_SWITCH_CRASH")
            self.connection.commit()
        except Exception:
            self.connection.rollback()
            raise

    def close(self) -> None:
        self.connection.close()


def copy_with_resume(source: Path, target: Path) -> None:
    size = source.stat().st_size
    split = size // 3
    with source.open("rb") as reader, target.open("wb") as writer:
        writer.write(reader.read(split))
    require(target.stat().st_size == split, "synthetic interruption offset mismatch")
    with source.open("rb") as reader, target.open("ab") as writer:
        reader.seek(target.stat().st_size)
        for chunk in iter(lambda: reader.read(1024 * 1024), b""):
            writer.write(chunk)


def resign(manifest: dict[str, Any]) -> dict[str, Any]:
    updated = copy.deepcopy(manifest)
    unsigned = {key: value for key, value in updated.items() if key != "testVectorMac"}
    updated["testVectorMac"] = sign_manifest(unsigned)
    return updated


def run_probe() -> ProbeResult:
    fixture_path = FIXTURE_ROOT / "data-package-plan.json"
    plan = load_json(fixture_path)
    assertions = 0
    validations = 0
    rejected = 0
    started = time.perf_counter()
    tracemalloc.start()

    with tempfile.TemporaryDirectory(prefix="jshpos-t1-w2-package-") as directory:
        root = Path(directory)
        full10 = root / "synthetic-full-10k.jsonl"
        full100 = root / "synthetic-full-100k.jsonl"
        write_records(full10, 10000)
        write_records(full100, 100000)
        manifest10 = make_manifest(full10, "SYN-PKG-FULL-100", "FULL", 100, None, 10000)
        manifest100 = make_manifest(full100, "SYN-PKG-FULL-200", "FULL", 200, None, 100000)

        validation_times: dict[str, list[float]] = {"10000": [], "100000": []}
        for count, path, manifest in ((10000, full10, manifest10), (100000, full100, manifest100)):
            for _ in range(plan["fullValidationRuns"][str(count)]):
                run_started = time.perf_counter()
                valid, reason = validate_package(path, manifest, 0)
                validation_times[str(count)].append(time.perf_counter() - run_started)
                require(valid and reason == "OK", f"{count} full package validation failed")
                assertions += 1
                validations += 1

        resumed = root / "synthetic-full-100k.resumed"
        copy_with_resume(full100, resumed)
        require(sha256_stream(resumed) == manifest100["contentSha256"], "resumed package digest mismatch")
        assertions += 1

        store = ActivePackageStore()
        try:
            valid, reason = validate_package(full10, manifest10, store.active_version())
            require(valid and reason == "OK", "10k full activation preflight failed")
            store.activate(manifest10)
            require(store.active_version() == 100, "10k full package did not become active")
            assertions += 2

            incremental = root / "synthetic-incremental.jsonl"
            write_records(incremental, plan["incrementalRecords"], 100000)
            for index in range(1, plan["incrementalPackages"] + 1):
                active = store.active_version()
                manifest = make_manifest(
                    incremental,
                    f"SYN-PKG-INCREMENTAL-{index:02d}",
                    "INCREMENTAL",
                    active + 1,
                    active,
                    plan["incrementalRecords"],
                )
                valid, reason = validate_package(incremental, manifest, active)
                require(valid and reason == "OK", f"incremental {index} validation failed")
                store.activate(manifest)
                require(store.active_version() == active + 1, f"incremental {index} did not switch")
                assertions += 2
                validations += 1

            candidate_version = store.active_version() + 1
            valid_candidate = make_manifest(
                full10,
                "SYN-PKG-CANDIDATE",
                "FULL",
                candidate_version,
                None,
                10000,
            )
            truncated = root / "synthetic-truncated.jsonl"
            with full10.open("rb") as source, truncated.open("wb") as target:
                target.write(source.read(full10.stat().st_size // 2))

            for fault in plan["faults"]:
                for repetition in range(plan["faultRepetitions"]):
                    active_before = store.active_version()
                    path = full10
                    manifest = copy.deepcopy(valid_candidate)
                    if fault == "TRUNCATED":
                        path = truncated
                    elif fault == "BAD_DIGEST":
                        manifest["contentSha256"] = "f" * 64
                        manifest = resign(manifest)
                    elif fault == "BAD_TEST_MAC":
                        manifest["testVectorMac"] = "e" * 64
                    elif fault == "TENANT_SWAP":
                        manifest["tenantId"] = "TENANT_BETA"
                        manifest = resign(manifest)
                    elif fault == "UNKNOWN_SCHEMA":
                        manifest["schemaVersion"] = 2
                        manifest = resign(manifest)
                    elif fault == "OLD_REPLAY":
                        manifest["version"] = active_before
                        manifest = resign(manifest)
                    elif fault == "VERSION_GAP":
                        manifest["mode"] = "INCREMENTAL"
                        manifest["baseVersion"] = active_before - 2
                        manifest["version"] = active_before + 1
                        manifest = resign(manifest)
                    else:
                        raise AssertionError(f"unsupported data package fault {fault}")
                    valid, _ = validate_package(path, manifest, active_before)
                    require(not valid, f"{fault}/{repetition} was accepted")
                    require(store.active_version() == active_before, f"{fault}/{repetition} changed active version")
                    assertions += 2
                    validations += 1
                    rejected += 1

            for repetition in range(plan["atomicCrashRepetitions"]):
                active_before = store.active_version()
                manifest = make_manifest(
                    full10,
                    f"SYN-PKG-ATOMIC-{repetition:02d}",
                    "FULL",
                    active_before + 1,
                    None,
                    10000,
                )
                valid, reason = validate_package(full10, manifest, active_before)
                require(valid and reason == "OK", "atomic switch preflight failed")
                try:
                    store.activate(manifest, fail_before_commit=True)
                except RuntimeError as exc:
                    require(str(exc) == "SYNTHETIC_SWITCH_CRASH", "unexpected switch error")
                require(store.active_version() == active_before, "failed switch exposed half version")
                store.activate(manifest)
                require(store.active_version() == active_before + 1, "recovered switch did not activate complete version")
                assertions += 4
                validations += 1

            active_final = store.active_version()
        finally:
            store.close()

        final_size = full100.stat().st_size
        temporary_bytes = sum(path.stat().st_size for path in root.iterdir() if path.is_file())

    _, peak_bytes = tracemalloc.get_traced_memory()
    tracemalloc.stop()
    duration = time.perf_counter() - started
    return ProbeResult(
        requirementId="T1-DPK-001",
        domain="DATA_PACKAGE",
        result="PASS",
        assertions=assertions,
        iterations=validations,
        metrics={
            "fullRecordCounts": plan["fullCounts"],
            "fullValidationRuns": plan["fullValidationRuns"],
            "incrementalPackages": plan["incrementalPackages"],
            "faultCasesRejected": rejected,
            "atomicCrashIterations": plan["atomicCrashRepetitions"],
            "halfSwitches": 0,
            "activeFinalVersion": active_final,
            "max100kValidationSeconds": round(max(validation_times["100000"]), 3),
            "totalProbeSeconds": round(duration, 3),
            "pythonPeakMemoryMiB": round(peak_bytes / 1024 / 1024, 3),
            "temporaryTo100kFileRatio": round(temporary_bytes / final_size, 3),
            "performanceEvidence": "FAKE_CI_TREND_ONLY_NOT_ANDROID_CERTIFICATION",
            "signatureMode": plan["signatureMode"],
        },
        fixtureDigests=[fixture_digest(fixture_path)],
    )
