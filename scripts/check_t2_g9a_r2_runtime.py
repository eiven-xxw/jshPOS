#!/usr/bin/env python3
"""校验 G9A-R2 正式整改的授权、历史守恒和证据边界。"""
from __future__ import annotations

import csv
import json
import pathlib
import subprocess


ROOT = pathlib.Path(__file__).resolve().parents[1]
BASELINE = "b9333b85f1b46ac444b83346a6a3d44204e7d723"
CONTRACT = ROOT / "contracts/t2/gate9b-r2"


def fail(message: str) -> None:
    raise AssertionError(message)


def git(*args: str) -> str:
    return subprocess.check_output(["git", "-c", "core.quotepath=false", *args], cwd=ROOT, text=True, encoding="utf-8").strip()


def rtm_states(path: pathlib.Path) -> dict[str, str]:
    with path.open(encoding="utf-8-sig", newline="") as handle:
        return {row["requirement_id"]: row["status"] for row in csv.DictReader(handle)}


def main() -> int:
    required = [
        CONTRACT / "gate-admission-v1.json",
        CONTRACT / "defect-closure-v1.json",
        CONTRACT / "test-matrix-v1.json",
        CONTRACT / "forward-cleanup-policy-v1.json",
        CONTRACT / "forward-cleanup-preflight.schema.json",
        ROOT / "scripts/build_t2_g9a_r2_forward_cleanup.py",
        ROOT / "scripts/test_t2_g9a_r2_forward_cleanup.py",
        ROOT / ".github/workflows/t2-g9a-r2-runtime.yml",
    ]
    missing = [path.relative_to(ROOT).as_posix() for path in required if not path.is_file()]
    if missing:
        fail(f"G9A-R2 正式整改文件缺失: {missing}")
    admission = json.loads((CONTRACT / "gate-admission-v1.json").read_text(encoding="utf-8"))
    closure = json.loads((CONTRACT / "defect-closure-v1.json").read_text(encoding="utf-8"))
    if subprocess.run(["git", "merge-base", "--is-ancestor", BASELINE, "HEAD"], cwd=ROOT).returncode != 0:
        fail("G9A-R2 准备阶段最终封存提交不是当前分支祖先")
    if admission["baselineCommit"] != BASELINE or admission["findingId"] != "G9A-ASM-P1-001":
        fail("正式整改准入与项目发起人授权不一致")
    if admission["reusedRequirements"] != ["T2-CORE-001", "T2-SEC-002", "T2-RDY-001"]:
        fail("复用需求集合漂移")

    current_states = rtm_states(ROOT / "docs/governance/rtm.csv")
    baseline_rtm = subprocess.check_output(["git", "show", f"{BASELINE}:docs/governance/rtm.csv"], cwd=ROOT)
    baseline_path = ROOT / "build/t2-g9a-r2/baseline-rtm.csv"
    baseline_path.parent.mkdir(parents=True, exist_ok=True)
    baseline_path.write_bytes(baseline_rtm)
    baseline_states = rtm_states(baseline_path)
    if current_states != baseline_states:
        fail("本缺陷修复不得新增 Requirement ID 或改变 RTM 状态")
    preserved = {key: current_states.get(key) for key in admission["preservedStates"]}
    if preserved != admission["preservedStates"]:
        fail(f"外部/UAT/许可证状态漂移: {preserved}")

    if "状态：Accepted" not in (ROOT / "docs/adr/ADR-070-g9a-r2-commercial-assembly-isolation.md").read_text(encoding="utf-8"):
        fail("ADR-070 尚未 Accepted")
    if "| ADR-070 |" not in (ROOT / "docs/adr/README.md").read_text(encoding="utf-8") or \
            "ADR-070-g9a-r2-commercial-assembly-isolation.md) | Accepted |" not in (ROOT / "docs/adr/README.md").read_text(encoding="utf-8"):
        fail("ADR 索引与 ADR-070 状态不一致")

    changed = git("diff", "--name-only", BASELINE).splitlines()
    migration_changes = [path for path in changed if "/db/migration/" in path or "sqlite_migrations" in path]
    if migration_changes:
        fail(f"已发布迁移被修改: {migration_changes}")
    immutable = [
        "contracts/t2/gate9a-prep/defect-register-v1.json",
        "contracts/t2/gate9b-r2-prep/assembly-baseline-v1.json",
        "scripts/audit_t2_g9a_r2_assembly.py",
    ]
    drift = [path for path in immutable if subprocess.run(["git", "diff", "--quiet", BASELINE, "--", path], cwd=ROOT).returncode != 0]
    if drift:
        fail(f"历史缺陷或基线证据被改写: {drift}")
    if closure["externalExecution"] != admission["externalExecution"] or any(admission["externalExecution"].values()):
        fail("外部零执行边界漂移")
    if closure["newRequirementIds"] != 0 or closure["newBusinessCapabilities"] != 0 or closure["publishedMigrationChanges"] != 0:
        fail("缺陷关闭候选扩张了范围")
    print(f"G9A-R2 RUNTIME SCOPE OK: baseline={BASELINE[:8]} changed={len(changed)} rtmStates={len(current_states)} external=0")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
