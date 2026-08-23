#!/usr/bin/env python3
"""T2-SVC-001 Owner、租户门店、附件、状态机、Secret 与外部零执行门禁。"""
from __future__ import annotations
import argparse, csv, json, pathlib, re, subprocess

ROOT = pathlib.Path(__file__).resolve().parents[1]
BASE = "ae0c145b82306dd08f9a0999ba3f030d54e8e3e9"
BRANCH = "t2/gate8a-sprint24c-svc001-runtime"
CONTRACT = ROOT / "contracts/t2/gate8a-svc001"
PRESERVED = {
    "T2-PAY-002": "BLOCKED", "T2-HWD-001": "BLOCKED", "T2-PRN-001": "BLOCKED",
    "T2-PAR-001": "BLOCKED", "T2-UAT-001": "DRAFT", "T2-REL-001": "DRAFT",
    "T2-LIC-001": "DEFERRED", "T2-JSH-001": "DEFERRED"
}

def require(value: bool, message: str) -> None:
    if not value:
        raise SystemExit("T2-GATE8A-SVC001 ERROR: " + message)

def read(path: str) -> str:
    return (ROOT / path).read_text(encoding="utf-8")

def git(*args: str) -> str:
    process = subprocess.run(["git", "-c", "core.quotepath=false", *args], cwd=ROOT,
                             capture_output=True, text=True, encoding="utf-8")
    require(process.returncode == 0, "git failed: " + process.stderr.strip())
    return process.stdout.strip()

def rtm_rows() -> dict[str, dict[str, str]]:
    with (ROOT / "docs/governance/rtm.csv").open(encoding="utf-8-sig", newline="") as stream:
        return {row["requirement_id"]: row for row in csv.DictReader(stream)}

def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--output", type=pathlib.Path)
    args = parser.parse_args()
    rows = rtm_rows()
    admission = json.loads((CONTRACT / "svc001-admission.json").read_text(encoding="utf-8"))
    require(admission["base_commit"] == BASE and admission["branch"] == BRANCH, "基线或分支漂移")
    require(rows["T2-SAA-001"]["status"] == "ACCEPTED", "SAA 前置依赖未接受")
    require(rows["T2-SUB-001"]["status"] == "ACCEPTED", "SUB 前置依赖未接受")
    require(rows["T2-SVC-001"]["status"] in {"IN_PROGRESS", "VERIFIED"}, "SVC 状态非法")
    for requirement, expected in PRESERVED.items():
        require(rows[requirement]["status"] == expected, f"{requirement} 状态漂移")
    require(all(value == 0 for value in admission["external_execution"].values()), "外部执行必须为零")
    vectors = json.loads((CONTRACT / "fixed-vectors.json").read_text(encoding="utf-8"))["vectors"]
    require(len(vectors) >= 28, "固定向量少于 28")
    registry = list(csv.DictReader((CONTRACT / "persistence-design-registry.csv").open(encoding="utf-8-sig", newline="")))
    require(len(registry) == 10, "持久化登记必须覆盖 10 张表")
    require({row["access_strategy"] for row in registry} == {"CONTROLLED_WRITE", "APPEND_ONLY"}, "持久化策略登记错误")
    require({row["sql_mode"] for row in registry} >= {"HYBRID", "XML"}, "MyBatis-Plus/XML 边界未登记")

    required = [
        "docs/adr/ADR-059-gate8a-service-operations-runtime.md",
        "server/ruoyi-modules/jshpos-service/pom.xml",
        "server/ruoyi-modules/jshpos-service/src/main/java/com/jingshanghui/pos/service/application/service/ServiceApplicationService.java",
        "server/ruoyi-modules/jshpos-service/src/main/java/com/jingshanghui/pos/service/infrastructure/storage/RuoYiServiceAttachmentStorageAdapter.java",
        "server/ruoyi-modules/jshpos-service/src/main/resources/db/migration/V202608240085__gate8a_service_operations.sql",
        "server/ruoyi-modules/jshpos-service/src/main/resources/db/migration/V202608240086__gate8a_service_permissions.sql",
        "admin-web/src/api/service/contract.ts", "admin-web/src/views/service/operations/index.vue"
    ]
    require(all((ROOT / path).is_file() for path in required), "运行时必要文件缺失")
    service = read(required[2])
    require("SaasEntitlementService" in service and "ScopeAuthorizationService" in service, "SaaS/Subscription 或门店授权未装配")
    require("registerObjectRollbackCleanup" in service and "temporaryDownload" in service, "附件回滚补偿或短期下载缺失")
    require(".mapper." not in service and "ruoyi-system" not in service, "应用层越过 Owner 或写入框架模块")
    require(not re.search(r"\b(deleteById|removeById|truncate)\b", service, re.I), "应用层出现通用物理删除")
    storage = read(required[3])
    require("OssFactory" in storage and "Files." not in storage and "Path." not in storage, "附件正文未失败关闭到受控对象存储")
    migration = read(required[4])
    require(migration.count("CREATE TABLE svc_") == 10, "V85 表数量错误")
    require(migration.count("CREATE TRIGGER ") >= 8, "只追加事实数据库保护不足")
    require("FLOAT" not in migration.upper() and "DOUBLE" not in migration.upper(), "迁移出现浮点数")
    require("attachment_body" not in migration.lower() and "public_url" not in migration.lower(), "数据库保存附件正文或永久地址")
    permissions = read(required[5])
    for permission in ["service:project:read", "service:ticket:read", "service:attachment:upload",
                       "service:attachment:download", "service:attachment:cleanup"]:
        require(permission in permissions, "权限迁移缺失: " + permission)
    state = json.loads((CONTRACT / "state-machines.json").read_text(encoding="utf-8"))
    require(state["ticket"]["closeRequiresIndependentReviewer"] is True, "工单关闭职责分离未冻结")
    require(state["attachment"]["bodyInDatabase"] is False and state["attachment"]["maxSignedUrlSeconds"] <= 300,
            "附件正文或短期链接边界错误")
    require(git("merge-base", "--is-ancestor", BASE, "HEAD") == "", "基线不是当前祖先")
    current = git("branch", "--show-current")
    require(current in {BRANCH, ""}, "当前分支错误: " + current)
    changed = set(filter(None, git("diff", "--name-only", BASE).splitlines())) | set(
        filter(None, git("ls-files", "--others", "--exclude-standard").splitlines()))
    forbidden = re.compile(r"(?i)(okhttp|retrofit|webclient|resttemplate|provider[_-]?url|merchant[_-]?secret|-----BEGIN [A-Z ]*PRIVATE KEY-----)")
    for name in changed:
        if not name.startswith(("server/", "admin-web/", "pos-flutter/")):
            continue
        path = ROOT / name
        if path.is_file():
            require(not forbidden.search(path.read_text(encoding="utf-8", errors="ignore")), "出现 Provider 网络或 Secret: " + name)
    result = {
        "gate": admission["gate"], "status": "PASS", "requirementStatus": rows["T2-SVC-001"]["status"],
        "baseline": BASE, "externalExecution": admission["external_execution"],
        "preservedStates": PRESERVED, "changedFiles": len(changed), "fixedVectors": len(vectors)
    }
    if args.output:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(result, ensure_ascii=False))

if __name__ == "__main__":
    main()
