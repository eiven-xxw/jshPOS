#!/usr/bin/env python3
"""校验 G9A-R4 准备阶段的范围、状态、契约和外部零执行边界。"""
from __future__ import annotations

import csv
import json
import pathlib
import subprocess


ROOT = pathlib.Path(__file__).resolve().parents[1]
BASELINE = "28b4da44ed529860970412a632c807df0d1d2d3e"
BRANCH = "t2/gate9b-sprint27h-g9a-r4-prep"
CONTRACT = ROOT / "contracts/t2/gate9b-r4-prep"
ALLOWED_EXACT = {
    "AGENTS.md",
    ".github/workflows/t2-g9a-r4-prep.yml",
    "docs/governance/change-log.md",
}
ALLOWED_PREFIXES = (
    "contracts/t2/gate9b-r4-prep/",
    "docs/t2-gate9b-r4-prep/",
    "docs/governance/CR-T2G9R4-",
    "scripts/audit_t2_g9a_r4_",
    "scripts/check_t2_g9a_r4_",
    "scripts/build_t2_g9a_r4_",
)


def fail(message: str) -> None:
    raise SystemExit(f"G9A-R4-PREP ERROR: {message}")


def git(*args: str) -> str:
    return subprocess.check_output(
        ["git", "-c", "core.quotepath=false", *args], cwd=ROOT, text=True, encoding="utf-8"
    ).strip()


def load(name: str) -> dict:
    try:
        return json.loads((CONTRACT / name).read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as exception:
        fail(f"invalid contract {name}: {exception}")


def rtm_states() -> dict[str, str]:
    with (ROOT / "docs/governance/rtm.csv").open(encoding="utf-8-sig", newline="") as handle:
        return {row["requirement_id"]: row["status"] for row in csv.DictReader(handle)}


def main() -> None:
    if subprocess.run(["git", "merge-base", "--is-ancestor", BASELINE, "HEAD"], cwd=ROOT).returncode != 0:
        fail("authorized baseline is not an ancestor")
    branch = git("branch", "--show-current")
    if branch and branch != BRANCH:
        fail(f"unexpected branch {branch}")
    changed = {item for item in git("diff", "--name-only", BASELINE).splitlines() if item}
    changed.update(item for item in git("ls-files", "--others", "--exclude-standard").splitlines() if item)
    changed = sorted(changed)
    illegal = [item for item in changed if item not in ALLOWED_EXACT and not item.startswith(ALLOWED_PREFIXES)]
    if illegal:
        fail(f"preparation-only scope violated: {illegal}")

    admission = load("gate-admission-v1.json")
    topology = load("formal-runtime-topology-v1.json")
    inventory = load("owner-runtime-inventory-v1.json")
    journeys = load("three-industry-journeys-v1.json")
    conservation = load("data-conservation-v1.json")
    seeds = load("failure-seeds-v1.json")
    repair = load("repair-plan-v1.json")
    freeze = load("source-freeze-v1.json")

    if admission["baselineCommit"] != BASELINE or admission["branch"] != BRANCH:
        fail("admission baseline or branch drift")
    if admission["findingState"] != "OPEN" or admission["runtimeRepairAllowed"] is not False:
        fail("R4 finding or runtime preparation boundary drift")
    if admission["newRequirementIds"] or admission["databaseMigrationAllowed"] or admission["externalExecutionAllowed"]:
        fail("new requirement, migration or external execution was admitted")
    if admission["r3dAcceptance"]["state"] != "CLOSED_IN_GATE9B":
        fail("sponsor-authorized R3D finding transition missing")
    if any(admission["externalExecution"].values()):
        fail("external execution must remain zero")

    states = rtm_states()
    for requirement, expected in admission["externalStates"].items():
        if states.get(requirement) != expected:
            fail(f"RTM external state drift: {requirement}={states.get(requirement)} expected={expected}")
    for requirement in admission["reusedRequirements"]:
        if states.get(requirement) != "ACCEPTED":
            fail(f"reused requirement not ACCEPTED: {requirement}")

    modules = [item["module"] for item in inventory["owners"]]
    if inventory["expectedOwnerCount"] != 22 or len(modules) != 22 or len(set(modules)) != 22:
        fail("owner inventory must contain exactly 22 unique modules")
    catalog_modules = [item["module"] for item in load_json(ROOT / "contracts/t2/gate9a-prep/owner-catalog-v1.json")["owners"]]
    if set(modules) != set(catalog_modules):
        fail("owner inventory differs from the frozen Gate9A catalog")
    for module in modules:
        if not (ROOT / f"server/ruoyi-modules/jshpos-{module}").is_dir():
            fail(f"owner module missing: {module}")

    industries = {item["industry"] for item in journeys["journeys"]}
    if journeys["journeyCount"] != 3 or industries != {"CONVENIENCE", "SNACK_DISCOUNT", "COMMUNITY_SUPERMARKET"}:
        fail("three-industry journey freeze incomplete")
    if set(journeys["ownerCoverage"]) != set(modules) or journeys["directDatabaseBusinessWrites"] != 0:
        fail("journey owner coverage or no-backdoor rule drift")
    if len(conservation["checks"]) != 12 or conservation["numericRules"]["floatingPointAllowed"] is not False:
        fail("data conservation matrix incomplete")
    open_seeds = [item for item in seeds["seeds"] if item["state"] == "OPEN"]
    if seeds["openP0"] != 0 or seeds["openP1"] != 4 or len(open_seeds) != 4 or seeds["findingClosureAllowed"]:
        fail("preparation finding and seed state drift")
    if [item["order"] for item in repair["batches"]] != list(range(6)) or not repair["serial"]:
        fail("serial repair plan incomplete")
    if repair["automaticEntryToRuntime"] is not False:
        fail("automatic runtime entry must remain forbidden")
    if topology["currentUnifiedClosureAchieved"] is not False:
        fail("current full-stack gap was incorrectly closed")

    for source in freeze["sources"]:
        actual = git("rev-parse", f"HEAD:{source['path']}")
        if actual != source["gitBlob"]:
            fail(f"historical source changed: {source['path']}")

    required_docs = [
        "README.md", "01_范围与证据边界.md", "02_二十二Owner正式栈审计.md",
        "03_三业态跨Owner旅程冻结.md", "04_失败Seed与数据守恒矩阵.md",
        "05_分批整改计划与测试矩阵.md", "06_G9A-R4启动评审报告.md",
        "07_下一步操作指令.md", "08_证据索引.md",
    ]
    missing = [name for name in required_docs if not (ROOT / "docs/t2-gate9b-r4-prep" / name).is_file()]
    if missing:
        fail(f"required review documents missing: {missing}")
    if not (ROOT / ".github/workflows/t2-g9a-r4-prep.yml").is_file():
        fail("R4 preparation CI workflow missing")

    print(f"G9A-R4 PREP GOVERNANCE OK: owners=22 industries=3 openP1=4 external=0 changed={len(changed)}")


def load_json(path: pathlib.Path) -> dict:
    return json.loads(path.read_text(encoding="utf-8"))


if __name__ == "__main__":
    main()
