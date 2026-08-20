#!/usr/bin/env python3
"""Gate 6B 治理、状态、迁移、持久化双登记和证据边界门禁。"""
from __future__ import annotations
import argparse, csv, hashlib, json, pathlib, subprocess

ROOT=pathlib.Path(__file__).resolve().parents[1]

def fail(condition: bool, message: str) -> None:
    if not condition: raise SystemExit(message)

def main() -> None:
    parser=argparse.ArgumentParser(); parser.add_argument("--output",required=True); parser.add_argument("--baseline",default="ec26f18715c205f29f7b9ec7c8a6478aeffcc557")
    args=parser.parse_args()
    with (ROOT/"docs/governance/rtm.csv").open(encoding="utf-8-sig",newline="") as stream:
        rtm={row["requirement_id"]:row for row in csv.DictReader(stream)}
    fail(rtm["T2-TRM-001"]["status"]=="ACCEPTED","TRM must be ACCEPTED")
    fail(rtm["T2-BAK-001"]["status"]=="ACCEPTED","BAK must be ACCEPTED")
    fail(rtm["T2-UPG-001"]["status"] in {"IN_PROGRESS","VERIFIED","ACCEPTED"},"UPG must be IN_PROGRESS/VERIFIED/ACCEPTED")
    for item in ("T2-PAY-002","T2-HWD-001","T2-PAR-001"):
        fail(rtm[item]["status"]=="BLOCKED",f"{item} must remain BLOCKED")
    for item in ("T2-UAT-001","T2-REL-001"):
        fail(rtm[item]["status"]=="DRAFT",f"{item} must remain DRAFT")
    admission=json.loads((ROOT/"contracts/t2/gate6b/gate6b-admission.json").read_text(encoding="utf-8"))
    statuses={item["id"]:item["status"] for item in admission["requirements"]}
    for item in ("T2-TRM-001","T2-BAK-001","T2-UPG-001","T2-PAY-002","T2-HWD-001","T2-PAR-001","T2-UAT-001","T2-REL-001"):
        fail(statuses[item]==rtm[item]["status"],f"admission/RTM mismatch: {item}")
    fail(not admission["requirements"][2]["realDeviceCommandsAllowed"],"real device commands must remain false")

    registry=list(csv.DictReader((ROOT/"contracts/t2/gate6b/persistence-registry.csv").open(encoding="utf-8-sig",newline="")))
    fail(len(registry)==7,"exactly seven Gate 6B tables must be registered")
    expected={
        "upg_release":("CONTROLLED_WRITE","XML"),"upg_target_scope":("APPEND_ONLY","XML"),
        "upg_rollout":("CONTROLLED_WRITE","XML"),"upg_terminal_task":("CONTROLLED_WRITE","XML"),
        "upg_command_result":("APPEND_ONLY","XML"),"upg_release_event":("APPEND_ONLY","XML"),
        "upg_audit":("APPEND_ONLY","XML")}
    fail({row["table"]:(row["access_strategy"],row["sql_mode"]) for row in registry}==expected,"persistence dual registry mismatch")
    checks=json.loads((ROOT/"contracts/t2/gate6b/migration-checksums.json").read_text(encoding="utf-8"))
    for item in checks["files"]:
        actual=hashlib.sha256((ROOT/item["path"]).read_bytes()).hexdigest()
        fail(actual==item["sha256"],f"migration checksum mismatch: {item['path']}")
    changed=subprocess.check_output(["git","diff","--name-only",args.baseline,"HEAD"],cwd=ROOT,text=True).splitlines()
    old_migrations=[path for path in changed if "/db/migration/" in path.replace("\\","/") and "V20260820004" not in path]
    fail(not old_migrations,f"published migrations changed: {old_migrations}")
    source="\n".join(path.read_text(encoding="utf-8",errors="ignore").lower() for path in (ROOT/"server/ruoyi-modules/jshpos-release/src/main").rglob("*") if path.is_file())
    for forbidden in ("okhttpclient","resttemplate","webclient.builder","provider_url","production_key","silent_install","firmware_command","reboot_command"):
        fail(forbidden not in source,f"forbidden network/real-device runtime token: {forbidden}")
    output={"gate":"T2-GATE6B-S14","status":"PASS","rtmStatus":rtm["T2-UPG-001"]["status"],
            "migrationCount":len(checks["files"]),"registeredTables":len(registry),"changedFiles":len(changed),
            "networkCalls":0,"realDeviceCommands":0,"alphaClaimAllowed":False}
    target=ROOT/args.output; target.parent.mkdir(parents=True,exist_ok=True); target.write_text(json.dumps(output,ensure_ascii=False,indent=2)+"\n",encoding="utf-8")
    print(json.dumps(output,ensure_ascii=False))
if __name__=="__main__": main()
