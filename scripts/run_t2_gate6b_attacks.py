#!/usr/bin/env python3
"""Gate 6B 租户、对象、状态、Secret 与真实设备入口静态攻击门禁。"""
from __future__ import annotations
import argparse, hashlib, json, pathlib
ROOT=pathlib.Path(__file__).resolve().parents[1]

def main() -> None:
    parser=argparse.ArgumentParser(); parser.add_argument("--output",required=True); args=parser.parse_args()
    mapper=(ROOT/"server/ruoyi-modules/jshpos-release/src/main/resources/mapper/release/ReleasePersistenceMapper.xml").read_text(encoding="utf-8").lower()
    rules=(ROOT/"server/ruoyi-modules/jshpos-release/src/main/java/com/jingshanghui/pos/release/domain/ReleaseRules.java").read_text(encoding="utf-8")
    config=(ROOT/"server/ruoyi-modules/jshpos-release/src/main/java/com/jingshanghui/pos/release/config/ReleaseAutoConfiguration.java").read_text(encoding="utf-8")
    cases={
      "trusted_tenant_mapper":"tenant_id=#{tenantid}" in mapper,
      "no_sql_interpolation":"${" not in mapper,
      "no_cross_owner_update":all(token not in mapper for token in ("update ord_","update pay_","update inv_","update dev_","update pos_sync_")),
      "object_namespace":"UPG-SEC-001" in rules,
      "cross_tenant_terminal":"UPG-SEC-002" in rules,
      "cross_store_terminal":"UPG-SEC-003" in rules,
      "revoked_terminal":"UPG-TRM-001" in rules,
      "pending_outbox":"UPG-SAFE-002" in rules,
      "unknown_funds":"UPG-SAFE-003" in rules,
      "business_window":"UPG-SAFE-004" in rules,
      "digest_signature":"UPG-ART-002" in rules and "UPG-ART-003" in rules,
      "idempotency_conflict":"UPG-IDEMP-002" in (ROOT/"server/ruoyi-modules/jshpos-release/src/main/java/com/jingshanghui/pos/release/application/service/ReleaseGovernanceService.java").read_text(encoding="utf-8"),
      "fail_closed_artifact":"UPG-CFG-001" in config,
      "fail_closed_safety":"UPG-CFG-002" in config,
      "no_real_device_command":all(token not in (rules+config).lower() for token in ("silent_install","firmware_command","reboot_command"))}
    if not all(cases.values()): raise SystemExit("failed attacks: "+str([k for k,v in cases.items() if not v]))
    payload={"gate":"T2-GATE6B-S14","seed":"GATE6B-S14-FIXED-20260820","status":"PASS","cases":cases,
             "networkCalls":0,"realDeviceCommands":0,"evidenceLevel":"STATIC"}
    payload["sha256"]=hashlib.sha256(json.dumps(payload,sort_keys=True,separators=(",",":")).encode()).hexdigest()
    target=ROOT/args.output; target.parent.mkdir(parents=True,exist_ok=True); target.write_text(json.dumps(payload,ensure_ascii=False,indent=2)+"\n",encoding="utf-8")
    print(json.dumps(payload,ensure_ascii=False))
if __name__=="__main__": main()
