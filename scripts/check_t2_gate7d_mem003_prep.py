#!/usr/bin/env python3
"""T2-MEM-003 独立 CR 准备的状态、契约、范围与零运行时门禁。"""
from __future__ import annotations

import argparse
import csv
import json
import pathlib
import re
import subprocess


ROOT = pathlib.Path(__file__).resolve().parents[1]
BASELINE = "39a72c65a08899f305ee0c04a5e337e1ee9ffbc9"
BRANCH = "t2/gate7d-sprint22a-mem003-prep"
GATE = "T2-GATE7D-SPRINT-S22A-MEM003-PREP"
CONTRACT = ROOT / "contracts/t2/gate7d-mem003-prep"
PRESERVED = {
    "T2-PAY-002": "BLOCKED", "T2-HWD-001": "BLOCKED",
    "T2-PRN-001": "BLOCKED", "T2-PAR-001": "BLOCKED",
    "T2-JSH-001": "DEFERRED", "T2-LIC-001": "DEFERRED",
    "T2-SAA-001": "DRAFT", "T2-SUB-001": "DRAFT", "T2-SVC-001": "DRAFT",
}


def fail(condition: bool, message: str) -> None:
    if not condition:
        raise SystemExit(f"T2-GATE7D-MEM003-PREP ERROR: {message}")


def git(*args: str) -> str:
    result = subprocess.run(
        ["git", "-c", "core.quotepath=false", *args], cwd=ROOT,
        capture_output=True, text=True, encoding="utf-8", check=False,
    )
    fail(result.returncode == 0, f"git {' '.join(args)} failed: {result.stderr.strip()}")
    return result.stdout.strip()


def load_json(name: str) -> dict:
    try:
        return json.loads((CONTRACT / name).read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as exc:
        raise SystemExit(f"T2-GATE7D-MEM003-PREP ERROR: invalid {name}: {exc}")


def rows() -> dict[str, dict[str, str]]:
    with (ROOT / "docs/governance/rtm.csv").open(encoding="utf-8", newline="") as stream:
        data = list(csv.DictReader(stream))
    return {row["requirement_id"]: row for row in data}


def changed_files() -> list[str]:
    fail(git("merge-base", "--is-ancestor", BASELINE, "HEAD") == "", "封存基线不是 HEAD 祖先")
    changed = sorted(filter(None, git("diff", "--name-only", BASELINE, "HEAD").splitlines()))
    runtime_prefixes = ("server/", "admin-web/", "pos-flutter/", "infrastructure/", "infra/", "packages/")
    illegal_runtime = [path for path in changed if path.startswith(runtime_prefixes)]
    fail(not illegal_runtime, f"准备阶段出现运行时或依赖工程变更: {illegal_runtime}")
    fail(not [path for path in changed if "/db/migration/" in path or "local_database/migrations/" in path],
         "准备阶段新增或修改数据库迁移")
    allowed_exact = {
        "AGENTS.md", "README.md", "docs/adr/README.md",
        ".github/workflows/t2-gate7d-mem003-prep.yml",
        "contracts/t2/gate7d-exc001/exc001-admission.json",
        "docs/governance/rtm.csv", "docs/governance/change-log.md",
        "docs/governance/CR-T2G7D-005_member-benefit-price-scope.md",
        "docs/t2-gate7d-exc001/README.md",
        "docs/t2-gate7d-exc001/05_T2_EXC001项目发起人接受记录.md",
        "scripts/check_t2_gate7d_exc001.py", "scripts/check_t2_gate7d_mem003_prep.py",
        "scripts/build_t2_gate7d_mem003_prep_evidence.py",
    }
    prefixes = (
        "contracts/t2/gate7d-mem003-prep/", "docs/t2-gate7d-mem003-prep/",
        "docs/adr/ADR-053-",
    )
    illegal = [path for path in changed if path not in allowed_exact and not path.startswith(prefixes)]
    fail(not illegal, f"准备阶段越界文件: {illegal}")
    return changed


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--output")
    args = parser.parse_args()
    rtm = rows()
    fail(rtm["T2-EXC-001"]["status"] == "ACCEPTED", "EXC001 未按发起人确认更新为 ACCEPTED")
    fail(rtm["T2-MEM-003"]["status"] == "DRAFT", "MEM003 被提前准入")
    for requirement, expected in PRESERVED.items():
        fail(rtm[requirement]["status"] == expected, f"{requirement} 状态漂移")

    admission = load_json("mem003-prep-admission.json")
    fail(admission["phase"] == GATE and admission["baselineCommit"] == BASELINE, "阶段或基线漂移")
    fail(admission["branch"] == BRANCH and admission["status"] == "DRAFT", "分支或需求状态漂移")
    fail(admission["decision"] == "CONDITIONAL_GO_RECOMMENDED_AWAITING_SPONSOR", "决策边界漂移")
    for flag in ("runtimeImplementation", "databaseMigration", "controller", "flutterBusinessPage",
                 "vueBusinessPage", "backgroundJob"):
        fail(admission[flag] is False, f"准备阶段出现运行时准入: {flag}")
    fail(admission["capability"]["defaultEnabled"] is False, "会员权益能力必须默认关闭")
    fail(admission["capability"]["defaultCombinationPolicy"] == "BEST_PRICE", "默认组合策略漂移")
    fail(admission["capability"]["explicitDoubleOptInForStacking"] is True, "叠加双向许可缺失")
    fail(all(value == 0 for value in admission["externalEvidence"].values()), "外部执行不为零")
    fail(admission["preservedStates"] == PRESERVED, "契约保留状态漂移")

    calculation = load_json("calculation-order-v1.json")
    fail(calculation["status"] == "DRAFT_NON_EXECUTABLE", "计算契约被误标执行态")
    steps = calculation["steps"]
    fail([item["order"] for item in steps] == list(range(1, 12)), "计算顺序不连续")
    fail(steps[7]["name"] == "BEST_PRICE_SELECTION" and steps[7]["tieBreaker"] == "NORMAL_PRICE_PROMOTION",
         "BEST_PRICE 或平价稳定规则漂移")
    fail(calculation["stacking"]["default"] == "DENY" and
         len(calculation["stacking"]["allowOnlyWhen"]) == 2, "叠加规则未失败关闭")

    vectors = load_json("member-benefit-price-vectors.json")["vectors"]
    fail(len(vectors) >= 32 and len({item["id"] for item in vectors}) == len(vectors), "固定向量少于32或重复")
    cases = {item["case"] for item in vectors}
    for case in ("active-level-member-price-wins", "normal-promotion-wins", "double-opt-in-stacking",
                 "offline-package-expired", "cross-tenant-entitlement", "partial-refund-after-level-change",
                 "final-refund-rounding-remainder", "migration-interrupted"):
        fail(case in cases, f"关键测试向量缺失: {case}")

    required = [
        "docs/governance/CR-T2G7D-005_member-benefit-price-scope.md",
        "docs/adr/ADR-053-gate7d-member-benefit-price-prep.md",
        *[f"docs/t2-gate7d-mem003-prep/{name}" for name in (
            "README.md", "01_CR与商业价值范围影响分析.md", "02_数据主权状态机计算顺序与不变量.md",
            "03_隐私离线多门店兼容与回退.md", "04_API事件迁移与跨端契约准备.md",
            "05_测试矩阵CI与量化验收.md", "06_T2_MEM003独立CR与正式开发启动评审报告.md",
            "07_T2_MEM003正式开发下一步操作指令.md",
        )],
        *[f"contracts/t2/gate7d-mem003-prep/{name}" for name in (
            "mem003-prep-admission.json", "calculation-order-v1.json",
            "openapi-member-benefit-price-draft.yaml", "member-benefit-events-draft.schema.json",
            "member-benefit-price-vectors.json", "persistence-design-registry.csv",
        )],
    ]
    fail(all((ROOT / path).is_file() for path in required), "CR、设计或契约交付物不完整")

    openapi = (CONTRACT / "openapi-member-benefit-price-draft.yaml").read_text(encoding="utf-8")
    for token in ("DRAFT_NON_EXECUTABLE", "x-no-pii: true", "Idempotency", "BEST_PRICE"):
        fail(token.lower() in openapi.lower(), f"DRAFT OpenAPI 缺少 {token}")
    fail("tenantId:" not in openapi and "tenant_id:" not in openapi, "客户端契约暴露租户覆盖字段")
    sensitive = re.compile(r"(^|/)(\.env($|\.)|.*\.(pem|p12|pfx|jks|keystore|key)$)", re.I)
    changed = changed_files()
    fail(not [path for path in changed if sensitive.search(path)], "出现敏感文件")

    result = {
        "schemaVersion": "1.0", "gate": GATE, "status": "PASS",
        "decision": admission["decision"], "requirementStatus": "DRAFT",
        "evidenceLevel": admission["evidenceLevel"], "baselineCommit": BASELINE,
        "faultVectorCount": len(vectors), "runtimeFilesChanged": 0,
        "databaseMigrationsAdded": 0, "externalExecution": admission["externalEvidence"],
        "preservedStates": PRESERVED, "changedFiles": changed,
    }
    if args.output:
        target = ROOT / args.output
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({key: value for key, value in result.items() if key != "changedFiles"}, ensure_ascii=False))


if __name__ == "__main__":
    main()
