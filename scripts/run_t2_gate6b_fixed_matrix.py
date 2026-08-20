#!/usr/bin/env python3
"""验证 Gate 6B 固定故障向量完整性并生成规范摘要。"""
from __future__ import annotations
import argparse, hashlib, json, pathlib
ROOT=pathlib.Path(__file__).resolve().parents[1]
EXPECTED={"GOOD_APK","BAD_SIGNATURE","DIGEST_MISMATCH","OLD_PACKAGE_REPLAY","CROSS_TENANT_PACKAGE",
"DOWNLOAD_INTERRUPTED","PENDING_OUTBOX","UNKNOWN_PAYMENT","UNKNOWN_REFUND","BUSINESS_HOURS","REVOKED_TERMINAL",
"VERSION_MISMATCH","CAPABILITY_MISMATCH","APK_HEALTH_FAIL","MYSQL_MIGRATION_FAIL","IDEMPOTENCY_CONFLICT","CANARY_FAIL_STOP"}
def main() -> None:
    p=argparse.ArgumentParser(); p.add_argument("--output",required=True); a=p.parse_args()
    source=json.loads((ROOT/"contracts/t2/gate6b/test-vectors/release-fault-vectors.json").read_text(encoding="utf-8"))
    ids=[item["id"] for item in source["vectors"]]
    if set(ids)!=EXPECTED or len(ids)!=len(set(ids)): raise SystemExit("Gate 6B vector set mismatch")
    if source["evidenceLevel"]!="SYNTHETIC_PACKAGE" or "REAL_DEVICE" not in source["forbiddenEvidenceUpgrades"]: raise SystemExit("evidence boundary mismatch")
    canonical=json.dumps(source,sort_keys=True,separators=(",",":"),ensure_ascii=False).encode()
    result={"gate":"T2-GATE6B-S14","status":"PASS","seed":source["seed"],"vectorCount":len(ids),
            "vectorIds":sorted(ids),"sourceSha256":hashlib.sha256(canonical).hexdigest(),"failedSeeds":[]}
    target=ROOT/a.output; target.parent.mkdir(parents=True,exist_ok=True); target.write_text(json.dumps(result,ensure_ascii=False,indent=2)+"\n",encoding="utf-8")
    print(json.dumps(result,ensure_ascii=False))
if __name__=="__main__": main()
