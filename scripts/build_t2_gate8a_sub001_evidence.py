#!/usr/bin/env python3
"""聚合 T2-SUB-001 多执行器证据并生成不可变 SHA-256 索引。"""
from __future__ import annotations
import argparse, hashlib, json, pathlib
GATE="T2-GATE8A-SPRINT-S24B-SUB001"
REQUIRED={"governance-ubuntu","governance-windows","server","mysql-runtime","web","flutter-ubuntu","flutter-windows","runtime-stack","security"}
def main():
    p=argparse.ArgumentParser();p.add_argument("--bundle-dir",required=True);p.add_argument("--output",required=True);a=p.parse_args();bundle=pathlib.Path(a.bundle_dir)
    files=sorted(x for x in bundle.rglob("*") if x.is_file());producers={x.relative_to(bundle).parts[0] for x in files if len(x.relative_to(bundle).parts)>1}
    missing=REQUIRED-producers
    if missing:raise SystemExit(f"missing SUB001 evidence producers: {sorted(missing)}")
    reports=[]
    for x in files:
        if x.suffix==".json":
            try:r=json.loads(x.read_text(encoding="utf-8"))
            except Exception:continue
            if r.get("gate")==GATE:reports.append(r)
    if len(reports)<2 or any(r.get("status")!="PASS" or any(r.get("externalExecution",{}).values()) for r in reports):raise SystemExit("SUB001 governance or zero-execution evidence failed")
    if {r.get("requirementStatus") for r in reports}!={"VERIFIED"}:raise SystemExit("SUB001 is not consistently VERIFIED")
    entries=[{"path":x.relative_to(bundle).as_posix(),"size":x.stat().st_size,"sha256":hashlib.sha256(x.read_bytes()).hexdigest()} for x in files]
    result={"schemaVersion":"1.0","gate":GATE,"status":"PASS","requirementStatus":"VERIFIED","evidenceLevel":"INTERNAL_SYNTHETIC_SOFTWARE_ONLY",
      "decision":"SUB001_VERIFIED_AWAITING_SPONSOR_ACCEPTANCE","producers":sorted(producers),"fileCount":len(entries),"files":entries,
      "limitations":["No real billing, Provider, funds, device, partner, Full Alpha or production execution.","T2-SVC-001 remains DRAFT."]}
    result["indexSha256"]=hashlib.sha256(json.dumps(result,sort_keys=True,separators=(",",":"),ensure_ascii=False).encode()).hexdigest()
    target=pathlib.Path(a.output);target.parent.mkdir(parents=True,exist_ok=True);target.write_text(json.dumps(result,ensure_ascii=False,indent=2)+"\n",encoding="utf-8")
if __name__=="__main__":main()
