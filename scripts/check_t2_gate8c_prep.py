#!/usr/bin/env python3
"""Gate 8C-Prep 基线、范围、P0/P1 发现与零外部执行静态门禁。"""
from __future__ import annotations

import argparse
import csv
import json
import pathlib
import re
import subprocess


ROOT = pathlib.Path(__file__).resolve().parents[1]
BASE = "68d94211b93156d0d87139e4ab5bef421802ad95"
BRANCH = "t2/gate8c-prep-v1-quality-release-audit"
GATE = "T2-GATE8C-PREP"
CONTRACT_DIR = ROOT / "contracts/t2/gate8c-prep"
PRESERVED = {
    "T2-PAY-002": "BLOCKED", "T2-HWD-001": "BLOCKED", "T2-PRN-001": "BLOCKED",
    "T2-PAR-001": "BLOCKED", "T2-UAT-001": "DRAFT", "T2-REL-001": "DRAFT",
    "T2-LIC-001": "DEFERRED", "T2-JSH-001": "DEFERRED",
}
CANDIDATES = ["T2-SEC-002", "T2-MTN-001", "T2-PERF-002", "T2-RDY-001"]


def require(condition: bool, message: str) -> None:
    if not condition:
        raise SystemExit("T2-GATE8C-PREP ERROR: " + message)


def git(*args: str) -> str:
    process = subprocess.run(
        ["git", "-c", "core.quotepath=false", *args], cwd=ROOT,
        capture_output=True, text=True, encoding="utf-8",
    )
    require(process.returncode == 0, "git failed: " + process.stderr.strip())
    return process.stdout.strip()


def read(path: str) -> str:
    return (ROOT / path).read_text(encoding="utf-8")


def read_rtm() -> dict[str, dict[str, str]]:
    with (ROOT / "docs/governance/rtm.csv").open(encoding="utf-8-sig", newline="") as stream:
        return {row["requirement_id"]: row for row in csv.DictReader(stream)}


def changed_files() -> list[str]:
    committed = set(filter(None, git("diff", "--name-only", BASE).splitlines()))
    untracked = set(filter(None, git("ls-files", "--others", "--exclude-standard").splitlines()))
    return sorted(committed | untracked)


def validate_scope() -> list[str]:
    require(git("merge-base", "--is-ancestor", BASE, "HEAD") == "", "Gate 8B 封存提交不是当前祖先")
    require(git("branch", "--show-current") in {BRANCH, ""}, "当前分支不属于 Gate 8C-Prep")
    changed = changed_files()
    runtime_roots = ("server/", "admin-web/", "pos-flutter/", "packages/", "infra/")
    runtime = [path for path in changed if path.startswith(runtime_roots)]
    require(not runtime, "准备阶段修改了运行时或基础设施: " + ", ".join(runtime))
    dependency_names = {
        "pom.xml", "package.json", "pnpm-lock.yaml", "pubspec.yaml", "pubspec.lock",
        "gradle.lockfile", "settings.gradle.kts", "build.gradle.kts",
    }
    dependency_changes = [path for path in changed if pathlib.PurePosixPath(path).name in dependency_names]
    require(not dependency_changes, "准备阶段修改了依赖: " + ", ".join(dependency_changes))
    allowed_exact = {
        "AGENTS.md", "README.md", "docs/adr/README.md", "docs/governance/change-log.md",
        "docs/governance/rtm.csv", ".github/workflows/t2-gate8c-prep.yml",
        "scripts/check_t2_gate8c_prep.py", "scripts/build_t2_gate8c_prep_evidence.py",
        "docs/governance/CR-T2G8C-001_gate8b接受与gate8c质量发布差距复核.md",
        "docs/t2-gate8b/README.md", "docs/t2-gate8b/08_T2_E2E005项目发起人接受记录.md",
        "docs/adr/ADR-062-gate8c-quality-performance-release-gap-audit.md",
    }
    prefixes = ("docs/t2-gate8c-prep/", "contracts/t2/gate8c-prep/")
    illegal = [path for path in changed if path not in allowed_exact and not path.startswith(prefixes)]
    require(not illegal, "准备阶段存在越界变更: " + ", ".join(illegal))
    return changed


def validate_rtm_and_contract() -> tuple[dict, dict, dict[str, dict[str, str]]]:
    admission = json.loads((CONTRACT_DIR / "gate8c-prep-admission.json").read_text(encoding="utf-8"))
    findings = json.loads((CONTRACT_DIR / "findings-register.json").read_text(encoding="utf-8"))
    rows = read_rtm()
    require(admission["baseCommit"] == BASE and admission["branch"] == BRANCH, "基线或分支契约漂移")
    require(admission["evidenceCeiling"] == "STATIC_GOVERNANCE_AND_REPOSITORY_AUDIT", "证据等级漂移")
    require(admission["priorAccepted"]["requirementId"] == "T2-E2E-005", "Gate 8B 接受引用漂移")
    require(admission["priorAccepted"]["sealedRun"] == 32670901692, "Gate 8B 封存 Run 漂移")
    require(admission["priorAccepted"]["sealedArtifact"] == 9501459414, "Gate 8B 证据 Artifact 漂移")
    require(rows["T2-E2E-005"]["status"] == "ACCEPTED", "T2-E2E-005 未按确认更新")
    require([item["requirementId"] for item in admission["candidateRequirements"]] == CANDIDATES,
            "质量整改需求顺序漂移")
    for requirement in CANDIDATES:
        require(rows[requirement]["status"] == "DRAFT", requirement + " 不得在 Prep 准入运行时")
    for requirement, expected in PRESERVED.items():
        require(rows[requirement]["status"] == expected, requirement + " 状态漂移")
        require(admission["preservedStates"][requirement] == expected, requirement + " 契约状态漂移")
    require(all(value == 0 for value in admission["externalExecution"].values()), "外部执行必须全部为零")
    accepted = sum(1 for key, row in rows.items() if key.startswith("T2-") and row["status"] == "ACCEPTED")
    require(accepted == 83, f"T2 ACCEPTED 数量漂移: {accepted}")

    entries = findings["findings"]
    ids = [item["findingId"] for item in entries]
    require(len(entries) == 14 and len(ids) == len(set(ids)), "发现项数量或唯一性漂移")
    p0 = sum(item["severity"] == "P0" for item in entries)
    p1 = sum(item["severity"] == "P1" for item in entries)
    require((p0, p1) == (3, 11), "P0/P1 发现数量漂移")
    require(findings["summary"]["openP0"] == p0 and findings["summary"]["openP1"] == p1,
            "发现摘要不一致")
    require(findings["summary"]["runtimeFixesApplied"] == 0, "Prep 不得宣称已修复运行时")
    for item in entries:
        require(item["requirementId"] in CANDIDATES, item["findingId"] + " 未绑定候选需求")
        for evidence in item["evidence"]:
            require((ROOT / evidence).is_file(), item["findingId"] + " 证据不存在: " + evidence)
    return admission, findings, rows


def validate_repository_facts() -> dict:
    prod = read("server/ruoyi-admin/src/main/resources/application-prod.yml")
    app = read("server/ruoyi-admin/src/main/resources/application.yml")
    service = read("server/ruoyi-modules/jshpos-service/src/main/java/com/jingshanghui/pos/service/application/service/ServiceApplicationService.java")
    service_controller = read("server/ruoyi-modules/jshpos-service/src/main/java/com/jingshanghui/pos/service/interfaces/rest/ServiceOperationsController.java")
    mapper = read("server/ruoyi-modules/jshpos-foundation/src/main/java/com/jingshanghui/pos/foundation/infrastructure/persistence/mapper/ConfigTemplateVersionMapper.java")

    default_credential_markers = sum(pattern in prod for pattern in (
        "snail-job:\n  enabled: true", "password: ruoyi123", "client-secret:", "token: \"SJ_",
    ))
    require(default_credential_markers == 4, "生产默认凭据/启用项事实漂移")
    require("include: '*'" in app and "show-details: ALWAYS" in app, "管理端点配置事实漂移")
    require("file.getBytes()" in service_controller and "max-file-size: 64MB" in app,
            "附件整包读取或全局上限事实漂移")
    require("import com.jingshanghui.pos.saas.application.service.SaasEntitlementService;" in service,
            "Service 跨 Owner 依赖事实漂移")

    prep_openapi = read("contracts/t2/gate7b-s20b/openapi-pos-second-batch-v1.yaml")
    exchange_openapi = read("contracts/t2/gate7b-exg/openapi-exchange-v1.yaml")
    tender_openapi = read("contracts/t2/gate7b-pay004/openapi-tender-runtime-v1.yaml")
    require("/pos/exchanges:" in prep_openapi and "/pos/exchanges:" in exchange_openapi,
            "换货重复 OpenAPI 事实漂移")
    require("/payments/tender-plans:" in prep_openapi and "/payments/tender-plans:" in tender_openapi,
            "组合支付重复 OpenAPI 事实漂移")
    callback = ROOT / "server/ruoyi-modules/jshpos-integration/src/main/resources/db/migration/beforeEachMigrate__repair_gate4c_gate7c_menu_ids.sql"
    require(callback.is_file() and 're.match(r"V([0-9]+)__"' in read("scripts/audit_t2_gate6g_data.py"),
            "Flyway 回调/旧审计器事实漂移")
    require("SELECT *" in mapper and "@Select" in mapper and "FOR UPDATE" in mapper,
            "Foundation SQL 边界事实漂移")

    checkout = ROOT / "pos-flutter/lib/features/checkout/application/checkout_local_service.dart"
    page = ROOT / "pos-flutter/lib/features/sale/presentation/pos_checkout_page.dart"
    checkout_lines = len(checkout.read_text(encoding="utf-8").splitlines())
    page_lines = len(page.read_text(encoding="utf-8").splitlines())
    require(checkout_lines >= 3000 and page_lines >= 1200, "POS 超大文件事实漂移")
    require(read("packages/pos_device_adapter/LICENSE").startswith("TODO:"), "设备适配包许可证占位事实漂移")
    perf = json.loads(read("contracts/t2/gate6h/performance-baseline-v1.json"))
    require(perf["requirementId"] == "T2-PERF-001", "既有性能基线身份漂移")

    controllers = list((ROOT / "server/ruoyi-modules").glob("jshpos-*/src/main/java/**/*Controller.java"))
    missing_permission = []
    direct_mapper = []
    for path in controllers:
        source = path.read_text(encoding="utf-8", errors="replace")
        relative = path.relative_to(ROOT).as_posix()
        if "@SaCheckPermission" not in source and "@SaIgnore" not in source:
            missing_permission.append(relative)
        if re.search(r"import .*\.(?:mapper|repository)\.", source, re.IGNORECASE):
            direct_mapper.append(relative)
    require(len(controllers) == 47, f"正式 Controller 数量漂移: {len(controllers)}")
    require(not missing_permission, "正式 Controller 缺少权限注解")
    require(not direct_mapper, "Controller 直接依赖 Mapper/Repository")
    return {
        "controllerCount": len(controllers),
        "controllersMissingPermission": missing_permission,
        "controllersDirectMapper": direct_mapper,
        "defaultCredentialMarkerCount": default_credential_markers,
        "checkoutServiceLines": checkout_lines,
        "checkoutPageLines": page_lines,
        "duplicateOpenApiFamilies": 2,
        "crossOwnerDirectServiceImports": 1,
        "placeholderPackageLicenses": 1,
    }


def validate_documents() -> None:
    required = [
        "docs/adr/ADR-062-gate8c-quality-performance-release-gap-audit.md",
        "docs/governance/CR-T2G8C-001_gate8b接受与gate8c质量发布差距复核.md",
        "docs/t2-gate8b/08_T2_E2E005项目发起人接受记录.md",
        "docs/t2-gate8c-prep/README.md",
        "docs/t2-gate8c-prep/01_范围非目标与证据边界.md",
        "docs/t2-gate8c-prep/02_审计方法基线与仓库事实.md",
        "docs/t2-gate8c-prep/03_安全P0P1复核.md",
        "docs/t2-gate8c-prep/04_性能P0P1复核.md",
        "docs/t2-gate8c-prep/05_可维护性P0P1复核.md",
        "docs/t2-gate8c-prep/06_发布P0P1复核.md",
        "docs/t2-gate8c-prep/07_整改串行依赖与逐步验收计划.md",
        "docs/t2-gate8c-prep/08_CI与证据规范.md",
        "docs/t2-gate8c-prep/09_T2_Gate8C_Prep启动评审报告.md",
        "docs/t2-gate8c-prep/10_下一步操作指令.md",
        "docs/t2-gate8c-prep/11_证据索引.md",
        "contracts/t2/gate8c-prep/quality-workstreams.json",
        "contracts/t2/gate8c-prep/audit-coverage-matrix.csv",
        ".github/workflows/t2-gate8c-prep.yml",
    ]
    require(all((ROOT / path).is_file() for path in required), "Gate 8C-Prep 交付物不完整")


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--output", type=pathlib.Path)
    args = parser.parse_args()
    changed = validate_scope()
    admission, findings, _ = validate_rtm_and_contract()
    repository = validate_repository_facts()
    validate_documents()
    result = {
        "schemaVersion": "1.0",
        "gate": GATE,
        "status": "PASS",
        "classification": admission["classification"],
        "evidenceCeiling": admission["evidenceCeiling"],
        "baselineCommit": BASE,
        "acceptedT2Requirements": 83,
        "candidateRequirementStatuses": {item["requirementId"]: item["status"] for item in admission["candidateRequirements"]},
        "openP0": findings["summary"]["openP0"],
        "openP1": findings["summary"]["openP1"],
        "internalCodeP0": findings["summary"]["internalCodeP0"],
        "repositoryFacts": repository,
        "runtimeFilesChanged": 0,
        "databaseMigrationsChanged": 0,
        "dependenciesChanged": 0,
        "changedFiles": changed,
        "preservedStates": admission["preservedStates"],
        "externalExecution": admission["externalExecution"],
        "decision": "PREP_CONDITIONAL_PASS_GATE8C_RUNTIME_AWAITING_SPONSOR",
    }
    if args.output:
        target = args.output if args.output.is_absolute() else ROOT / args.output
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({key: value for key, value in result.items() if key not in {"changedFiles", "repositoryFacts"}}, ensure_ascii=False))


if __name__ == "__main__":
    main()
