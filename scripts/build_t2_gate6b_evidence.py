#!/usr/bin/env python3
"""Gate 6B 证据制品存在性、摘要和边界索引；不复制生产者大制品。"""
from __future__ import annotations
import argparse, hashlib, json, pathlib
REQUIRED={"governance","server","mysql","release-security","vectors","pos-linux","pos-windows","web","security"}
def main() -> None:
    p=argparse.ArgumentParser(); p.add_argument("--bundle-dir",required=True); a=p.parse_args(); root=pathlib.Path(a.bundle_dir)
    missing=[name for name in REQUIRED if not (root/name).exists()]
    if missing: raise SystemExit(f"missing evidence producers: {missing}")
    files=[]
    for path in sorted(p for p in root.rglob("*") if p.is_file() and p.name!="t2-gate6b-evidence-index.json"):
        files.append({"path":path.relative_to(root).as_posix(),"size":path.stat().st_size,
                      "sha256":hashlib.sha256(path.read_bytes()).hexdigest()})
    if not files: raise SystemExit("empty evidence bundle")
    payload={"schemaVersion":"1.0","gate":"T2-GATE6B-S14","status":"PASS","fileCount":len(files),
             "evidenceCeiling":["STATIC","UNIT","MYSQL8.4_SYNTHETIC","SYNTHETIC_PACKAGE","SOFTWARE_EXECUTION"],
             "sandbox":0,"realDevice":0,"pilot":0,"production":0,"files":files}
    encoded=json.dumps(payload,sort_keys=True,separators=(",",":"),ensure_ascii=False).encode(); payload["indexSha256"]=hashlib.sha256(encoded).hexdigest()
    target=root/"t2-gate6b-evidence-index.json"; target.write_text(json.dumps(payload,ensure_ascii=False,indent=2)+"\n",encoding="utf-8")
    print(json.dumps({"status":"PASS","files":len(files),"sha256":payload["indexSha256"]}))
if __name__=="__main__": main()
