#!/usr/bin/env python3
"""T2 Gate 8B-Prep 基线、范围、正式 API 旅程与外部零执行门禁。"""
from __future__ import annotations

import argparse
import csv
import json
import pathlib
import re
import subprocess

ROOT = pathlib.Path(__file__).resolve().parents[1]
BASE = "16912142cadd7f3038a10e9deee02eb97ff44d3f"
BRANCH = "t2/gate8b-prep-commercial-saas-operations-acceptance"
CONTRACT = ROOT / "contracts/t2/gate8b-prep"
PRESERVED = {
    "T2-PAY-002": "BLOCKED", "T2-HWD-001": "BLOCKED", "T2-PRN-001": "BLOCKED",
    "T2-PAR-001": "BLOCKED", "T2-UAT-001": "DRAFT", "T2-REL-001": "DRAFT",
    "T2-LIC-001": "DEFERRED", "T2-JSH-001": "DEFERRED"
}


def require(value: bool, message: str) -> None:
    if not value:
        raise SystemExit("T2-GATE8B-PREP ERROR: " + message)


def git(*args: str) -> str:
    process = subprocess.run(["git", "-c", "core.quotepath=false", *args], cwd=ROOT,
                             capture_output=True, text=True, encoding="utf-8")
    require(process.returncode == 0, "git failed: " + process.stderr.strip())
    return process.stdout.strip()


def read(path: str) -> str:
    return (ROOT / path).read_text(encoding="utf-8")


def rtm_rows() -> dict[str, dict[str, str]]:
    with (ROOT / "docs/governance/rtm.csv").open(encoding="utf-8-sig", newline="") as stream:
        return {row["requirement_id"]: row for row in csv.DictReader(stream)}


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--output", type=pathlib.Path)
    args = parser.parse_args()

    admission = json.loads((CONTRACT / "gate8b-prep-admission.json").read_text(encoding="utf-8"))
    require(admission["base_commit"] == BASE and admission["branch"] == BRANCH, "基线或分支漂移")
    require(admission["evidence_ceiling"] == "INTERNAL_SYNTHETIC_API_JOURNEY", "证据等级漂移")
    require(all(value == 0 for value in admission["external_execution"].values()), "外部执行必须为零")

    rows = rtm_rows()
    for requirement in ("T2-SAA-001", "T2-SUB-001", "T2-SVC-001"):
        require(rows[requirement]["status"] == "ACCEPTED", requirement + " 未接受")
    for requirement, expected in PRESERVED.items():
        require(rows[requirement]["status"] == expected, requirement + " 状态漂移")

    required = [
        "docs/adr/ADR-060-gate8b-commercial-operations-aggregate-prep.md",
        "docs/t2-gate8b-prep/08_T2_Gate8B_Prep启动评审报告.md",
        "docs/t2-gate8b-prep/09_下一步操作指令.md",
        "contracts/t2/gate8b-prep/formal-api-journey.json",
        "contracts/t2/gate8b-prep/owner-api-event-migration-matrix.csv",
        "contracts/t2/gate8b-prep/fixed-vectors.json",
        "contracts/t2/gate8b-prep/go-no-go-register.json",
        "server/ruoyi-modules/jshpos-service/src/test/java/com/jingshanghui/pos/service/e2e/CommercialSaasOperationsFormalApiE2ETest.java",
        ".github/workflows/t2-gate8b-prep.yml"
    ]
    require(all((ROOT / path).is_file() for path in required), "必要文件缺失")
    require(git("merge-base", "--is-ancestor", BASE, "HEAD") == "", "SVC 封存提交不是当前祖先")
    require(git("branch", "--show-current") in {BRANCH, ""}, "当前分支错误")

    matrix = list(csv.DictReader((CONTRACT / "owner-api-event-migration-matrix.csv").open(
        encoding="utf-8-sig", newline="")))
    require([row["requirement_id"] for row in matrix] == ["T2-SAA-001", "T2-SUB-001", "T2-SVC-001"],
            "Owner 汇总矩阵不完整或顺序漂移")
    require(all(row["status"] == "ACCEPTED" for row in matrix), "汇总矩阵状态不一致")
    for row in matrix:
        for reference in (row["formal_api"], row["event_contract"], row["evidence"]):
            require((ROOT / reference).is_file(), "汇总引用不存在: " + reference)

    vectors = json.loads((CONTRACT / "fixed-vectors.json").read_text(encoding="utf-8"))["vectors"]
    require(len(vectors) >= 20 and len({item["id"] for item in vectors}) == len(vectors), "固定向量不足或重复")
    go_no_go = json.loads((CONTRACT / "go-no-go-register.json").read_text(encoding="utf-8"))
    require(go_no_go["fullAlpha"]["runs"] == 0 and go_no_go["release"]["productionDeployments"] == 0,
            "Alpha 或生产执行不为零")
    require(go_no_go["license"]["closed"] == 0 and go_no_go["license"]["required"] == 3,
            "许可证关闭数漂移")

    test = read(required[7])
    for controller in ("SaasOperationsController", "SubscriptionController", "ServiceOperationsController"):
        require(controller in test, "正式 Controller 未进入旅程: " + controller)
    for endpoint in ("/api/v1/saas", "/api/v1/subscriptions", "/api/v1/service"):
        require(endpoint in test, "正式 API 根路径缺失: " + endpoint)
    require("MockMvcBuilders.standaloneSetup" in test, "未通过正式 Controller 装配")
    require(not re.search(r"(?i)(jdbc:|entitymanager|sqlsession|\.mapper\.|directdatabase|testbackdoor)", test),
            "合成旅程出现数据库或 Mapper 后门")

    changed = set(filter(None, git("diff", "--name-only", BASE).splitlines())) | set(
        filter(None, git("ls-files", "--others", "--exclude-standard").splitlines()))
    allowed_test = required[7]
    for name in changed:
        normalized = name.replace("\\", "/")
        if normalized == allowed_test:
            continue
        require("/src/main/" not in normalized, "Prep 禁止修改正式运行时: " + normalized)
        require("/db/migration/" not in normalized, "Prep 禁止新增或修改迁移: " + normalized)
        require(not normalized.startswith(("admin-web/src/", "pos-flutter/lib/", "pos-flutter/android/")),
                "Prep 禁止修改前端或设备运行时: " + normalized)

    forbidden = re.compile(r"(?i)(-----BEGIN [A-Z ]*PRIVATE KEY-----|merchant[_-]?secret|provider[_-]?url|okhttp|retrofit)")
    for name in changed:
        normalized = name.replace("\\", "/")
        if not normalized.startswith(("server/", "admin-web/", "pos-flutter/")):
            continue
        path = ROOT / name
        if path.is_file():
            require(not forbidden.search(path.read_text(encoding="utf-8", errors="ignore")), "Secret 或 Provider 能力: " + name)

    result = {
        "gate": admission["gate"], "status": "PASS", "baseline": BASE,
        "requirements": admission["requirements"], "evidenceLevel": admission["evidence_ceiling"],
        "preservedStates": PRESERVED, "externalExecution": admission["external_execution"],
        "fixedVectors": len(vectors), "changedFiles": len(changed)
    }
    if args.output:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(result, ensure_ascii=False))


if __name__ == "__main__":
    main()
