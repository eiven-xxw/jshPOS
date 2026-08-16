from __future__ import annotations

import argparse
import csv
import json
import re
import subprocess
import sys
from collections import Counter
from datetime import datetime, timezone
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
BASE_COMMIT = "962c4ed5e631bccd5c6fff737ed8e97fb665fd03"
T0_TAG = "t0-baseline-2026-08-16"
CANDIDATE_TAG = "t2-prep-baseline-2026-08-16"
RTM = ROOT / "docs" / "governance" / "rtm.csv"
DOC_DIR = ROOT / "docs" / "t2-prep"

REQUIRED_DOCS = {
    "README.md",
    "01_T2项目章程.md",
    "02_T2范围与非目标.md",
    "03_T2_RTM与需求准入.md",
    "04_三类业态边界与商业V1冻结清单.md",
    "05_模块准入与依赖图.md",
    "06_详细设计31至40复核与决策清单.md",
    "07_T2迭代计划与RACI.md",
    "08_环境迁移测试与CI门禁.md",
    "09_灰度回退证据与运行手册.md",
    "10_外部依赖解阻计划.md",
    "11_风险台账与GoNoGo.md",
    "12_T2-Prep候选基线与Tag计划.md",
    "13_T2正式开发启动评审报告.md",
}

T2P_IDS = {
    "T2P-GOV-001",
    "T2P-SCP-001",
    "T2P-RTM-001",
    "T2P-ARC-001",
    "T2P-PLN-001",
    "T2P-QLT-001",
    "T2P-EXT-001",
    "T2P-REV-001",
}

T2_DRAFT_IDS = {
    "T2-IAM-001", "T2-ORG-001", "T2-RBAC-001", "T2-CFG-001", "T2-AUD-001", "T2-TRM-001",
    "T2-PRD-001", "T2-PRD-002", "T2-PRD-003", "T2-PRD-004", "T2-PRC-001", "T2-PRC-002", "T2-DPK-001",
    "T2-POS-001", "T2-POS-002", "T2-POS-003", "T2-POS-004", "T2-POS-005", "T2-ORD-001", "T2-ORD-002",
    "T2-OFF-001", "T2-SYN-001", "T2-PAY-001", "T2-PAY-003", "T2-REF-001", "T2-REC-001",
    "T2-INV-001", "T2-INV-002", "T2-INV-003", "T2-INV-004", "T2-PUR-001", "T2-CST-001", "T2-TRF-001",
    "T2-PRM-001", "T2-PRM-002", "T2-PRM-003", "T2-MEM-001", "T2-MEM-002", "T2-RPT-001", "T2-RPT-002",
    "T2-SEC-001", "T2-OBS-001", "T2-BAK-001", "T2-UPG-001", "T2-MIG-001", "T2-UAT-001", "T2-REL-001",
}
T2_BLOCKED_IDS = {"T2-PRN-001", "T2-PAY-002", "T2-HWD-001", "T2-PAR-001"}
T2_DEFERRED_IDS = {"T2-JSH-001", "T2-LIC-001"}
T2_IDS = T2_DRAFT_IDS | T2_BLOCKED_IDS | T2_DEFERRED_IDS

T1_EXPECTED_COUNTS = {
    "ACCEPTED": 2,
    "IN_PROGRESS": 9,
    "READY": 1,
    "BLOCKED": 7,
    "DEFERRED": 2,
}

PRODUCTION_PREFIXES = (
    "server/",
    "admin-web/",
    "pos-flutter/",
    "packages/pos_device_adapter/",
    "infra/",
)
DEPENDENCY_FILES = {
    "server/pom.xml",
    "admin-web/package.json",
    "admin-web/pnpm-lock.yaml",
    "pos-flutter/pubspec.yaml",
    "pos-flutter/pubspec.lock",
    "packages/pos_device_adapter/pubspec.yaml",
    "packages/pos_device_adapter/pubspec.lock",
}


def fail(message: str) -> None:
    print(f"T2-PREP ERROR: {message}", file=sys.stderr)
    raise SystemExit(1)


def run_git(*args: str, check: bool = True) -> str:
    result = subprocess.run(
        ["git", *args],
        cwd=ROOT,
        check=False,
        capture_output=True,
        text=True,
        encoding="utf-8",
        errors="replace",
    )
    if check and result.returncode != 0:
        fail(f"git {' '.join(args)} failed: {result.stderr.strip()}")
    return result.stdout.strip()


def changed_paths() -> list[str]:
    tracked = set(filter(None, run_git("-c", "core.quotepath=false", "diff", "--name-only", BASE_COMMIT).splitlines()))
    untracked = set(filter(None, run_git("-c", "core.quotepath=false", "ls-files", "--others", "--exclude-standard").splitlines()))
    return sorted(tracked | untracked)


def check_baseline_and_tag() -> None:
    if run_git("rev-parse", BASE_COMMIT) != BASE_COMMIT:
        fail("Week 4 final baseline commit is unavailable")
    ancestor = subprocess.run(["git", "merge-base", "--is-ancestor", BASE_COMMIT, "HEAD"], cwd=ROOT, check=False)
    if ancestor.returncode != 0:
        fail(f"HEAD is not based on {BASE_COMMIT}")
    if run_git("cat-file", "-t", T0_TAG) != "tag":
        fail(f"{T0_TAG} must remain annotated")
    candidate_ref = run_git("show-ref", "--verify", f"refs/tags/{CANDIDATE_TAG}", check=False)
    if candidate_ref:
        fail(f"candidate tag {CANDIDATE_TAG} must not exist before sponsor confirmation")


def allowed_path(path: str) -> bool:
    normalized = path.replace("\\", "/")
    if normalized == "AGENTS.md" or normalized.startswith("docs/t2-prep/"):
        return True
    if normalized in {
        ".github/workflows/t2-prep.yml",
        "docs/governance/rtm.csv",
        "docs/governance/change-log.md",
        "docs/adr/README.md",
        "docs/adr/ADR-018-t1-exit-and-t2-entry-recommendation.md",
        "docs/adr/ADR-019-t2-alpha-module-gates-and-industry-templates.md",
        "scripts/check_t2_prep.py",
        "scripts/build_t2_prep_evidence.py",
    }:
        return True
    return False


def check_scope(paths: list[str]) -> None:
    if not paths:
        fail("no T2-Prep changes found")
    unexpected = [path for path in paths if not allowed_path(path)]
    if unexpected:
        fail(f"changes outside T2-Prep scope: {unexpected}")
    production = [path for path in paths if path.replace("\\", "/").startswith(PRODUCTION_PREFIXES)]
    if production:
        fail(f"formal product paths changed during T2-Prep: {production}")
    dependencies = [path for path in paths if path.replace("\\", "/") in DEPENDENCY_FILES]
    if dependencies:
        fail(f"dependency manifests changed during T2-Prep: {dependencies}")


def check_docs() -> tuple[int, list[str]]:
    actual = {path.name for path in DOC_DIR.glob("*.md")}
    missing = sorted(REQUIRED_DOCS - actual)
    if missing:
        fail(f"missing T2-Prep documents: {missing}")
    link_pattern = re.compile(r"\[[^\]]+\]\(([^)]+\.md)(?:#[^)]*)?\)")
    corpus: list[str] = []
    for name in sorted(REQUIRED_DOCS):
        path = DOC_DIR / name
        try:
            content = path.read_text(encoding="utf-8")
        except UnicodeDecodeError as exc:
            fail(f"{path.relative_to(ROOT)} is not UTF-8: {exc}")
        if not content.startswith("# "):
            fail(f"{path.relative_to(ROOT)} must start with H1")
        corpus.append(content)
        for target in link_pattern.findall(content):
            if target.startswith(("http://", "https://")):
                continue
            if not (path.parent / target).resolve().exists():
                fail(f"broken Markdown link in {path.relative_to(ROOT)}: {target}")
    combined = "\n".join(corpus)
    required_terms = (
        "BLOCKED", "DEFERRED", "DRAFT", "SANDBOX", "REAL_DEVICE", "PILOT",
        "未经", "不得", "便利店", "零食折扣店", "社区超市", "T2 CODING",
    )
    missing_terms = [term for term in required_terms if term not in combined]
    if missing_terms:
        fail(f"T2-Prep corpus missing governance terms: {missing_terms}")
    design_doc = (DOC_DIR / "06_详细设计31至40复核与决策清单.md").read_text(encoding="utf-8")
    missing_designs = [str(number) for number in range(31, 41) if f"文档 {number}" not in design_doc]
    if missing_designs:
        fail(f"detailed design reviews missing: {missing_designs}")
    decision_ids = sorted(set(re.findall(r"DEC-(\d{2})", design_doc)))
    if decision_ids != [f"{number:02d}" for number in range(1, 13)]:
        fail(f"expected DEC-01..DEC-12, got {decision_ids}")
    report = (DOC_DIR / "13_T2正式开发启动评审报告.md").read_text(encoding="utf-8")
    if "AWAITING SP CONFIRMATION" not in report or "T2 CODING NO-GO" not in report:
        fail("startup review report must remain awaiting sponsor and coding NO-GO")
    return len(REQUIRED_DOCS), decision_ids


def read_rtm() -> list[dict[str, str]]:
    with RTM.open(encoding="utf-8-sig", newline="") as handle:
        return list(csv.DictReader(handle))


def check_rtm() -> dict[str, int]:
    rows = read_rtm()
    by_id = {row["requirement_id"]: row for row in rows}
    t2p = {key: by_id[key] for key in by_id.keys() & T2P_IDS}
    t2 = {key: by_id[key] for key in by_id.keys() & T2_IDS}
    if set(t2p) != T2P_IDS:
        fail(f"T2P ID mismatch: missing={sorted(T2P_IDS - set(t2p))} extra={sorted(set(t2p) - T2P_IDS)}")
    all_t2_rows = {key: row for key, row in by_id.items() if key.startswith("T2-")}
    if set(all_t2_rows) != T2_IDS:
        fail(f"T2 ID mismatch: missing={sorted(T2_IDS - set(all_t2_rows))} extra={sorted(set(all_t2_rows) - T2_IDS)}")
    if any(row["status"] != "READY" for row in t2p.values()):
        fail("all T2P governance requirements must be READY awaiting sponsor confirmation")
    actual_draft = {key for key, row in t2.items() if row["status"] == "DRAFT"}
    actual_blocked = {key for key, row in t2.items() if row["status"] == "BLOCKED"}
    actual_deferred = {key for key, row in t2.items() if row["status"] == "DEFERRED"}
    if actual_draft != T2_DRAFT_IDS:
        fail(f"T2 DRAFT mismatch: missing={sorted(T2_DRAFT_IDS - actual_draft)} extra={sorted(actual_draft - T2_DRAFT_IDS)}")
    if actual_blocked != T2_BLOCKED_IDS:
        fail(f"T2 BLOCKED mismatch: {sorted(actual_blocked)}")
    if actual_deferred != T2_DEFERRED_IDS:
        fail(f"T2 DEFERRED mismatch: {sorted(actual_deferred)}")
    for key, row in t2.items():
        if row["implementation"].strip():
            fail(f"{key} must not have implementation evidence during T2-Prep")
        if not row["source"].strip() or not row["acceptance"].strip() or not row["owner"].strip():
            fail(f"{key} missing source acceptance or owner")
    for key in T2_BLOCKED_IDS:
        row = t2[key]
        if "缺" not in row["test_evidence"] and "BLOCKED" not in row["notes"]:
            fail(f"{key} lacks an explicit blocker")
    t1_counts = Counter(row["status"] for row in rows if row["requirement_id"].startswith("T1-"))
    if dict(t1_counts) != T1_EXPECTED_COUNTS:
        fail(f"T1 status counts changed: expected={T1_EXPECTED_COUNTS} actual={dict(t1_counts)}")
    v1_bad = [row["requirement_id"] for row in rows if row["requirement_id"].startswith("V1-") and row["status"] != "DRAFT"]
    if v1_bad:
        fail(f"commercial V1 epics changed from DRAFT: {v1_bad}")
    return {
        "t2pReady": len(T2P_IDS),
        "t2Draft": len(T2_DRAFT_IDS),
        "t2Blocked": len(T2_BLOCKED_IDS),
        "t2Deferred": len(T2_DEFERRED_IDS),
    }


def check_adrs() -> None:
    adr18 = (ROOT / "docs" / "adr" / "ADR-018-t1-exit-and-t2-entry-recommendation.md").read_text(encoding="utf-8")
    adr19 = (ROOT / "docs" / "adr" / "ADR-019-t2-alpha-module-gates-and-industry-templates.md").read_text(encoding="utf-8")
    if "- 状态：Accepted" not in adr18:
        fail("ADR-018 must be Accepted after sponsor T1 exit confirmation")
    if "- 状态：Proposed" not in adr19:
        fail("ADR-019 must remain Proposed until T2 coding confirmation")


def check_secrets() -> int:
    patterns = {
        "private key": re.compile(r"-----BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY-----"),
        "GitHub token": re.compile(r"gh[pousr]_[A-Za-z0-9]{30,}"),
        "AWS key": re.compile(r"AKIA[0-9A-Z]{16}"),
        "generic secret": re.compile(r"\bsk-[A-Za-z0-9]{20,}\b"),
    }
    scanned = 0
    for path in sorted(DOC_DIR.glob("*.md")):
        content = path.read_text(encoding="utf-8")
        scanned += 1
        for label, pattern in patterns.items():
            if pattern.search(content):
                fail(f"{label} detected in {path.relative_to(ROOT)}")
    return scanned


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--output", type=Path)
    args = parser.parse_args()
    check_baseline_and_tag()
    paths = changed_paths()
    check_scope(paths)
    doc_count, decisions = check_docs()
    status_counts = check_rtm()
    check_adrs()
    scanned = check_secrets()
    evidence = {
        "schemaVersion": "1.0",
        "phase": "T2-PREP",
        "evidenceLevel": "STATIC",
        "generatedAt": datetime.now(timezone.utc).isoformat().replace("+00:00", "Z"),
        "baseCommit": BASE_COMMIT,
        "commitSha": run_git("rev-parse", "HEAD"),
        "candidateTag": CANDIDATE_TAG,
        "candidateTagState": "PREPARED_NOT_CREATED",
        "changedFiles": paths,
        "productionPathChanges": 0,
        "dependencyManifestChanges": 0,
        "documents": doc_count,
        "designReviews": 10,
        "decisionItems": len(decisions),
        "requirements": status_counts,
        "secretScannedDocuments": scanned,
        "externalEvidence": {"sandbox": 0, "realDevice": 0, "pilot": 0},
        "limitations": [
            "T2-Prep only; formal business coding remains NO-GO",
            "T1 Fake does not replace SANDBOX REAL_DEVICE PILOT or commercial acceptance",
        ],
    }
    if args.output:
        output = args.output if args.output.is_absolute() else ROOT / args.output
        output.parent.mkdir(parents=True, exist_ok=True)
        output.write_text(json.dumps(evidence, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(
        "T2-PREP OK: "
        f"base={BASE_COMMIT[:8]} docs={doc_count} designs=10 decisions={len(decisions)} "
        f"T2P_READY={status_counts['t2pReady']} T2_DRAFT={status_counts['t2Draft']} "
        f"T2_BLOCKED={status_counts['t2Blocked']} T2_DEFERRED={status_counts['t2Deferred']} "
        "productionChanges=0 candidateTag=PREPARED_NOT_CREATED coding=NO-GO"
    )


if __name__ == "__main__":
    main()
