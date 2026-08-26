#!/usr/bin/env python3
"""校验 Gate 10A-R1 的 Action 版本账、依赖冻结和范围停止线。"""
from __future__ import annotations

import csv
import difflib
import hashlib
import json
import pathlib
import re
import subprocess


ROOT = pathlib.Path(__file__).resolve().parents[1]
START = "cdf0d3a5e60e679b483e1ea89b046958e4877c22"
TAG = "t2-internal-product-completeness-seal-2026-08-26"
SEALED = "9ca6778f315e4d702af704be3c0bad2de3d2e8bb"
TAG_OBJECT = "e091439de230099f057014810e686baa704112be"
REMOTE_ACTION = re.compile(
    r"^\s*-?\s*uses:\s*([A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+)@([0-9a-f]{40})(?:\s+#\s+(\S+))?\s*$"
)
ANY_REMOTE_ACTION = re.compile(r"^\s*-?\s*uses:\s*([^\s#]+@[^\s#]+)")
ALLOWED_PREFIXES = (
    ".github/workflows/",
    "AGENTS.md",
    "contracts/t2/gate10a-r1/",
    "docs/adr/ADR-073-",
    "docs/adr/README.md",
    "docs/governance/CR-T2G10A-",
    "docs/governance/change-log.md",
    "docs/t2-gate10a-r1/",
    "scripts/build_t2_gate10a_r1_evidence.py",
    "scripts/check_t2_gate10a_r1.py",
    "scripts/snapshot_t2_gate10a_r1.py",
)


def git(*args: str) -> str:
    return subprocess.check_output(
        ["git", *args], cwd=ROOT, text=True, encoding="utf-8"
    ).strip()


def sha256(path: pathlib.Path) -> str:
    value = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            value.update(chunk)
    return value.hexdigest()


def git_blob(revision: str, relative: str) -> bytes:
    """读取 Git 对象中的规范字节，避免 Windows checkout 换行影响跨平台摘要。"""
    return subprocess.check_output(["git", "show", f"{revision}:{relative}"], cwd=ROOT)


def sha256_git_blob(revision: str, relative: str) -> str:
    return hashlib.sha256(git_blob(revision, relative)).hexdigest()


def changed_paths() -> set[str]:
    changed = set(
        filter(
            None,
            git("-c", "core.quotepath=false", "diff", "--name-only", START).splitlines(),
        )
    )
    changed.update(
        filter(
            None,
            git(
                "-c",
                "core.quotepath=false",
                "ls-files",
                "--others",
                "--exclude-standard",
            ).splitlines(),
        )
    )
    return changed


def check_tag() -> None:
    evidence = json.loads(
        (ROOT / "contracts/t2/gate10a-r1/gate9c-tag-seal-evidence-v1.json").read_text(
            encoding="utf-8"
        )
    )
    if git("cat-file", "-t", TAG) != "tag":
        raise AssertionError("Gate9C ref is not an annotated tag object")
    if git("rev-parse", TAG) != TAG_OBJECT:
        raise AssertionError("Gate9C tag object drift")
    if git("rev-parse", f"{TAG}^{{}}") != SEALED:
        raise AssertionError("Gate9C peeled commit drift")
    content = subprocess.check_output(
        ["git", "for-each-ref", "--format=%(contents)", f"refs/tags/{TAG}"], cwd=ROOT
    )
    if hashlib.sha256(content).hexdigest() != evidence["messageSha256"]:
        raise AssertionError("Gate9C tag message drift")


def check_actions() -> tuple[int, int]:
    ledger = json.loads(
        (ROOT / "contracts/t2/gate10a-r1/action-version-ledger-v1.json").read_text(
            encoding="utf-8"
        )
    )
    expected = {item["repository"]: item for item in ledger["actions"]}
    workflows = sorted((ROOT / ".github/workflows").glob("*.y*ml"))
    active = set(ledger["activeWorkflows"])
    missing_active = sorted(name for name in active if not (ROOT / ".github/workflows" / name).is_file())
    if missing_active:
        raise AssertionError(f"active workflow missing: {missing_active}")
    failures: list[str] = []
    remote_uses = 0
    for workflow in workflows:
        for line_no, line in enumerate(workflow.read_text(encoding="utf-8").splitlines(), start=1):
            if "uses:" not in line or "uses: ./" in line:
                continue
            any_match = ANY_REMOTE_ACTION.match(line)
            if not any_match:
                continue
            remote_uses += 1
            match = REMOTE_ACTION.match(line)
            if not match:
                failures.append(f"{workflow.name}:{line_no}: floating or malformed remote Action")
                continue
            repository, action_sha, version = match.groups()
            item = expected.get(repository)
            if item is None:
                failures.append(f"{workflow.name}:{line_no}: unregistered Action {repository}")
                continue
            current = item["current"]
            if action_sha != current["sha"] or version != current["version"]:
                failures.append(
                    f"{workflow.name}:{line_no}: {repository}@{action_sha} #{version} != ledger"
                )
    node_drift = [
        item["repository"]
        for item in ledger["actions"]
        if item["current"]["runtime"] not in {"node24", "composite"}
    ]
    if node_drift:
        failures.append(f"non-Node24 Action runtime: {node_drift}")
    if failures:
        sample = failures[:20]
        raise AssertionError(
            f"Action ledger violations={len(failures)} sample={sample}"
        )
    historical = len(workflows) - len(active)
    return remote_uses, historical


def check_historical_workflow_semantics() -> int:
    """历史工作流只允许等价替换远程 Action pin，不得改写步骤和门禁语义。"""
    failures: list[str] = []
    checked = 0
    for workflow in sorted((ROOT / ".github/workflows").glob("*.y*ml")):
        if workflow.name == "t2-gate10a-r1.yml":
            continue
        relative = workflow.relative_to(ROOT).as_posix()
        try:
            previous = subprocess.check_output(
                ["git", "show", f"{START}:{relative}"],
                cwd=ROOT,
                text=True,
                encoding="utf-8",
            ).splitlines()
        except subprocess.CalledProcessError:
            failures.append(f"historical workflow missing at baseline: {relative}")
            continue
        current = workflow.read_text(encoding="utf-8").splitlines()
        checked += 1
        for line in difflib.ndiff(previous, current):
            if not line.startswith(("- ", "+ ")):
                continue
            changed = line[2:].lstrip()
            if not changed.startswith("- uses:"):
                failures.append(f"{relative}: non-Action semantic diff: {changed[:100]}")
    if failures:
        raise AssertionError(
            f"historical workflow semantic drift={len(failures)} sample={failures[:20]}"
        )
    return checked


def check_dependency_freeze() -> int:
    baseline = json.loads(
        (ROOT / "contracts/t2/gate10a-r1/ecosystem-baseline-v1.json").read_text(
            encoding="utf-8"
        )
    )
    drift = [
        path
        for path, expected in baseline["manifestDigests"].items()
        if sha256_git_blob("HEAD", path) != expected
    ]
    for name, input_set in baseline["inputSets"].items():
        files = sorted(
            {
                path
                for pattern in input_set["patterns"]
                for path in ROOT.glob(pattern)
                if path.is_file()
            },
            key=lambda path: path.relative_to(ROOT).as_posix(),
        )
        value = hashlib.sha256()
        for path in files:
            relative = path.relative_to(ROOT).as_posix()
            value.update(relative.encode("utf-8"))
            value.update(b"\0")
            value.update(git_blob("HEAD", relative))
            value.update(b"\0")
        actual_digest = value.hexdigest()
        if len(files) != input_set["fileCount"] or actual_digest != input_set["aggregateSha256"]:
            drift.append(
                f"{name}_INPUT_SET(count={len(files)}/{input_set['fileCount']},"
                f"sha={actual_digest}/{input_set['aggregateSha256']})"
            )
    if drift:
        raise AssertionError(f"application dependency or lockfile drift: {drift}")
    return sum(item["fileCount"] for item in baseline["inputSets"].values())


def check_scope_and_states() -> int:
    changed = changed_paths()
    illegal = sorted(
        path
        for path in changed
        if not any(path == prefix or path.startswith(prefix) for prefix in ALLOWED_PREFIXES)
    )
    if illegal:
        raise AssertionError(f"R1 scope escaped: {illegal}")
    migrations = sorted(
        path
        for path in changed
        if "/db/migration/" in f"/{path}" or "sqlite/migration" in path.lower()
    )
    if migrations:
        raise AssertionError(f"published migration changed: {migrations}")
    admission = json.loads(
        (ROOT / "contracts/t2/gate10a-r1/r1-admission-v1.json").read_text(encoding="utf-8")
    )
    if admission["startCommit"] != START or admission["sealedCommit"] != SEALED:
        raise AssertionError("R1 admission baseline drift")
    if admission["newBusinessCapabilities"] != 0 or admission["publishedMigrationChangesAllowed"]:
        raise AssertionError("R1 semantic stop line drift")
    if any(admission["externalExecution"].values()):
        raise AssertionError("external execution must remain zero")
    with (ROOT / "docs/governance/rtm.csv").open(encoding="utf-8-sig", newline="") as handle:
        states = {row["requirement_id"]: row["status"] for row in csv.DictReader(handle)}
    for requirement_id, expected in admission["externalStates"].items():
        if states.get(requirement_id) != expected:
            raise AssertionError(
                f"external state drift: {requirement_id}={states.get(requirement_id)}"
            )
    adr = (ROOT / "docs/adr/ADR-073-gate10a-internal-quality-hardening-sequence.md").read_text(
        encoding="utf-8"
    )
    if "- 状态：Accepted" not in adr:
        raise AssertionError("ADR-073 must be Accepted")
    return len(changed)


def main() -> int:
    check_tag()
    changed = check_scope_and_states()
    remote_uses, historical = check_actions()
    historical_checked = check_historical_workflow_semantics()
    frozen = check_dependency_freeze()
    print(
        "T2 Gate10A R1 SCOPE OK: "
        f"changed={changed} remoteActionUses={remote_uses} historicalWorkflows={historical} "
        f"historicalSemanticsChecked={historical_checked} "
        f"dependencyInputsFrozen={frozen} migration=0 external=0"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
