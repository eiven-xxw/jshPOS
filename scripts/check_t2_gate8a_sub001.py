#!/usr/bin/env python3
"""T2-SUB-001 Owner、状态机、迁移、租户、Secret 与外部零执行门禁。"""
from __future__ import annotations
import argparse, csv, json, pathlib, re, subprocess

ROOT=pathlib.Path(__file__).resolve().parents[1]
BASE="abcf34ab2e99a75ba476fade6f203b38a2ed3c75"
BRANCH="t2/gate8a-sprint24b-sub001-runtime"
CONTRACT=ROOT/"contracts/t2/gate8a-sub001"
PRESERVED={"T2-SVC-001":"DRAFT","T2-PAY-002":"BLOCKED","T2-HWD-001":"BLOCKED","T2-PRN-001":"BLOCKED","T2-PAR-001":"BLOCKED","T2-UAT-001":"DRAFT","T2-REL-001":"DRAFT","T2-LIC-001":"DEFERRED","T2-JSH-001":"DEFERRED"}

def require(value: bool,message: str)->None:
    if not value: raise SystemExit("T2-GATE8A-SUB001 ERROR: "+message)
def read(path:str)->str:return (ROOT/path).read_text(encoding="utf-8")
def git(*args:str)->str:
    p=subprocess.run(["git","-c","core.quotepath=false",*args],cwd=ROOT,capture_output=True,text=True,encoding="utf-8")
    require(p.returncode==0,"git failed: "+p.stderr.strip());return p.stdout.strip()
def rows():
    with (ROOT/"docs/governance/rtm.csv").open(encoding="utf-8-sig",newline="") as f:return {r["requirement_id"]:r for r in csv.DictReader(f)}

def main()->None:
    parser=argparse.ArgumentParser();parser.add_argument("--output",type=pathlib.Path);args=parser.parse_args()
    rtm=rows();admission=json.loads((CONTRACT/"sub001-admission.json").read_text(encoding="utf-8"))
    require(admission["base_commit"]==BASE and admission["branch"]==BRANCH,"基线或分支漂移")
    require(rtm["T2-SAA-001"]["status"]=="ACCEPTED","SAA 前置依赖未接受")
    require(rtm["T2-SUB-001"]["status"] in {"IN_PROGRESS","VERIFIED"},"SUB 状态非法")
    for key,value in PRESERVED.items():require(rtm[key]["status"]==value,f"{key} 状态漂移")
    require(all(v==0 for v in admission["external_execution"].values()),"外部执行必须为零")
    require(len(json.loads((CONTRACT/"fixed-vectors.json").read_text(encoding="utf-8"))["vectors"])>=20,"固定向量不足")
    registry=list(csv.DictReader((CONTRACT/"persistence-design-registry.csv").open(encoding="utf-8-sig",newline="")))
    require(len(registry)==10 and {x["access_strategy"] for x in registry}>={"CONTROLLED_WRITE","APPEND_ONLY"},"持久化登记错误")
    required=["docs/adr/ADR-058-gate8a-subscription-runtime.md","server/ruoyi-modules/jshpos-subscription/pom.xml",
      "server/ruoyi-modules/jshpos-subscription/src/main/java/com/jingshanghui/pos/subscription/application/service/SubscriptionApplicationService.java",
      "server/ruoyi-modules/jshpos-subscription/src/main/resources/db/migration/V202608230083__gate8a_subscription_lifecycle.sql",
      "server/ruoyi-modules/jshpos-subscription/src/main/resources/db/migration/V202608230084__gate8a_subscription_permissions.sql",
      "admin-web/src/views/subscription/operations/index.vue","pos-flutter/lib/features/session/domain/saas_restriction_notice.dart"]
    require(all((ROOT/x).is_file() for x in required),"运行时必要文件缺失")
    service=read(required[2]);require("SaasSubscriptionControlPort" in service and ".mapper." not in service,"Owner 端口边界异常")
    require("platformActor()" in service and "authorization.requirePlatformAdministrator()" in service,"平台写操作未授权")
    require(not re.search(r"\b(delete|truncate)\b",service,re.I),"运行时出现物理删除")
    migration=read(required[3]);require(migration.count("CREATE TABLE ")==10,"V83 表数量错误")
    require(migration.count("CREATE TRIGGER ")>=10 and "FLOAT" not in migration.upper() and "DOUBLE" not in migration.upper(),"迁移历史保护或精度错误")
    require(git("merge-base","--is-ancestor",BASE,"HEAD")=="","基线不是当前祖先")
    current=git("branch","--show-current");require(current in {BRANCH,""},"当前分支错误: "+current)
    changed=set(filter(None,git("diff","--name-only",BASE).splitlines()))|set(filter(None,git("ls-files","--others","--exclude-standard").splitlines()))
    forbidden=re.compile(r"(?i)(okhttp|retrofit|webclient|resttemplate|provider[_-]?url|merchant[_-]?secret|-----BEGIN [A-Z ]*PRIVATE KEY-----)")
    runtime_files = [name for name in changed if name.startswith(("server/", "admin-web/", "pos-flutter/"))]
    for name in runtime_files:
        path=ROOT/name
        if path.is_file():require(not forbidden.search(path.read_text(encoding="utf-8",errors="ignore")),"出现 Provider 网络或 Secret: "+name)
    result={"gate":admission["gate"],"status":"PASS","requirementStatus":rtm["T2-SUB-001"]["status"],"baseline":BASE,
      "externalExecution":admission["external_execution"],"preservedStates":PRESERVED,"changedFiles":len(changed)}
    if args.output:args.output.parent.mkdir(parents=True,exist_ok=True);args.output.write_text(json.dumps(result,ensure_ascii=False,indent=2)+"\n",encoding="utf-8")
    print(json.dumps(result,ensure_ascii=False))
if __name__=="__main__":main()
