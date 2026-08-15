from __future__ import annotations

import csv
import re
import subprocess
import sys
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
BASELINE_TAG = "t0-baseline-2026-08-16"
BASELINE_COMMIT = "04b176f2cd44ae4738a7bfd855548b17fa1bd380"
RTM = ROOT / "docs" / "governance" / "rtm.csv"
T1_DIR = ROOT / "docs" / "t1-prep"

REQUIRED_DOCS = {
    "README.md",
    "01_T1风险PoC项目章程.md",
    "02_T1范围非目标与需求准入清单.md",
    "03_T1七类风险PoC方案.md",
    "04_Android设备与外设选型输入清单.md",
    "05_支付沙箱与鲸熵汇外部资料清单.md",
    "06_T1四周迭代计划与RACI.md",
    "07_T1测试矩阵故障注入与量化验收.md",
    "08_T1_CI质量门禁制品与证据规范.md",
    "09_T1风险阻断GoNoGo与回退方案.md",
    "10_T1启动评审报告.md",
}

REQUIRED_T1_IDS = {
    "T1-GOV-001",
    "T1-SCP-001",
    "T1-HWD-001",
    "T1-HWD-002",
    "T1-PRN-001",
    "T1-SCN-001",
    "T1-SCL-001",
    "T1-IO-001",
    "T1-OFF-001",
    "T1-SYN-001",
    "T1-TEN-001",
    "T1-PAY-001",
    "T1-PAY-002",
    "T1-DPK-001",
    "T1-UPG-001",
    "T1-SEC-001",
    "T1-PAR-001",
    "T1-JSH-001",
    "T1-CI-001",
    "T1-LIC-001",
    "T1-UAT-001",
}

EXPECTED_BLOCKED = {
    "T1-HWD-002",
    "T1-PRN-001",
    "T1-SCN-001",
    "T1-SCL-001",
    "T1-IO-001",
    "T1-PAY-002",
    "T1-PAR-001",
}
EXPECTED_DEFERRED = {"T1-JSH-001", "T1-LIC-001"}
FORBIDDEN_ACTIVE_STATUSES = {"IN_PROGRESS", "IMPLEMENTED", "VERIFIED", "ACCEPTED"}


def run_git(*args: str) -> str:
    completed = subprocess.run(
        ["git", *args],
        cwd=ROOT,
        check=False,
        capture_output=True,
        text=True,
        encoding="utf-8",
        errors="replace",
    )
    if completed.returncode != 0:
        fail(f"git {' '.join(args)} failed: {completed.stderr.strip()}")
    return completed.stdout.strip()


def fail(message: str) -> None:
    print(f"T1-PREP ERROR: {message}", file=sys.stderr)
    raise SystemExit(1)


def check_baseline() -> None:
    if run_git("cat-file", "-t", BASELINE_TAG) != "tag":
        fail(f"{BASELINE_TAG} must be an annotated tag")
    if run_git("rev-list", "-n", "1", BASELINE_TAG) != BASELINE_COMMIT:
        fail(f"{BASELINE_TAG} does not point to {BASELINE_COMMIT}")
    ancestor = subprocess.run(
        ["git", "merge-base", "--is-ancestor", BASELINE_TAG, "HEAD"],
        cwd=ROOT,
        check=False,
    )
    if ancestor.returncode != 0:
        fail(f"HEAD is not based on {BASELINE_TAG}")


def allowed_prep_path(name: str) -> bool:
    normalized = name.replace("\\", "/")
    if normalized == "AGENTS.md":
        return True
    if normalized.startswith("docs/t1-prep/"):
        return True
    if normalized in {
        "docs/governance/rtm.csv",
        "docs/governance/change-log.md",
        "docs/adr/README.md",
        "docs/adr/ADR-017-t1-risk-poc-scope-and-integration-depth.md",
        "scripts/check_t1_prep.py",
    }:
        return True
    return False


def check_change_scope() -> None:
    tracked = set(
        filter(
            None,
            run_git("-c", "core.quotepath=false", "diff", "--name-only", BASELINE_TAG).splitlines(),
        )
    )
    untracked = set(
        filter(
            None,
            run_git(
                "-c",
                "core.quotepath=false",
                "ls-files",
                "--others",
                "--exclude-standard",
            ).splitlines(),
        )
    )
    changed = tracked | untracked
    unexpected = sorted(name for name in changed if not allowed_prep_path(name))
    if unexpected:
        fail(f"changes outside T1-Prep governance scope: {unexpected}")
    if not changed:
        fail("no T1-Prep changes found")


def check_docs() -> None:
    actual = {path.name for path in T1_DIR.glob("*.md")}
    missing = sorted(REQUIRED_DOCS - actual)
    if missing:
        fail(f"missing T1-Prep documents: {missing}")

    corpus_parts: list[str] = []
    link_pattern = re.compile(r"\[[^\]]+\]\(([^)]+\.md)(?:#[^)]*)?\)")
    for name in sorted(REQUIRED_DOCS):
        path = T1_DIR / name
        try:
            content = path.read_text(encoding="utf-8")
        except UnicodeDecodeError as exc:
            fail(f"{path.relative_to(ROOT)} is not UTF-8: {exc}")
        if not content.startswith("# "):
            fail(f"{path.relative_to(ROOT)} must start with an H1")
        corpus_parts.append(content)
        for target in link_pattern.findall(content):
            if target.startswith(("http://", "https://")):
                continue
            resolved = (path.parent / target).resolve()
            if not resolved.exists():
                fail(
                    f"broken markdown link in {path.relative_to(ROOT)}: {target}"
                )

    corpus = "\n".join(corpus_parts)
    for required_term in (
        "BLOCKED",
        "ASSUMPTION",
        "FAKE",
        "SANDBOX",
        "REAL_DEVICE",
        "未经",
        "不得",
    ):
        if required_term not in corpus:
            fail(f"T1-Prep corpus missing required governance term {required_term!r}")

    seven_headings = re.findall(r"^## [3-9]\. PoC-[1-7]：", (T1_DIR / "03_T1七类风险PoC方案.md").read_text(encoding="utf-8"), re.MULTILINE)
    if len(seven_headings) != 7:
        fail(f"expected 7 PoC sections, found {len(seven_headings)}")


def check_rtm() -> None:
    with RTM.open(encoding="utf-8-sig", newline="") as handle:
        rows = list(csv.DictReader(handle))

    t1_rows = {row["requirement_id"]: row for row in rows if row["requirement_id"].startswith("T1-")}
    ids = set(t1_rows)
    if ids != REQUIRED_T1_IDS:
        fail(
            "T1 requirement IDs mismatch: "
            f"missing={sorted(REQUIRED_T1_IDS - ids)}, extra={sorted(ids - REQUIRED_T1_IDS)}"
        )

    for requirement_id, row in t1_rows.items():
        if row["phase"] not in {"T1", "T1-Prep"}:
            fail(f"{requirement_id} has invalid phase {row['phase']!r}")
        if row["status"] in FORBIDDEN_ACTIVE_STATUSES:
            fail(f"{requirement_id} must not be active/accepted before startup review")
        if not row["acceptance"].strip() or not row["owner"].strip():
            fail(f"{requirement_id} lacks acceptance or owner")

    blocked = {key for key, row in t1_rows.items() if row["status"] == "BLOCKED"}
    deferred = {key for key, row in t1_rows.items() if row["status"] == "DEFERRED"}
    if blocked != EXPECTED_BLOCKED:
        fail(f"BLOCKED requirements mismatch: {sorted(blocked)}")
    if deferred != EXPECTED_DEFERRED:
        fail(f"DEFERRED requirements mismatch: {sorted(deferred)}")

    for requirement_id in EXPECTED_BLOCKED:
        row = t1_rows[requirement_id]
        if "BLOCKED" not in row["notes"] and "缺" not in row["test_evidence"]:
            fail(f"{requirement_id} does not explain its blocker")


def check_no_secret_material() -> None:
    suspicious_patterns = {
        "private key material": re.compile(r"-----BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY-----"),
        "GitHub token": re.compile(r"gh[pousr]_[A-Za-z0-9]{30,}"),
        "AWS access key": re.compile(r"AKIA[0-9A-Z]{16}"),
        "generic sk token": re.compile(r"\bsk-[A-Za-z0-9]{20,}\b"),
    }
    for path in T1_DIR.glob("*.md"):
        content = path.read_text(encoding="utf-8")
        for label, pattern in suspicious_patterns.items():
            if pattern.search(content):
                fail(f"{label} detected in {path.relative_to(ROOT)}")


def main() -> None:
    check_baseline()
    check_change_scope()
    check_docs()
    check_rtm()
    check_no_secret_material()
    print(
        "T1-PREP OK: annotated baseline verified; "
        f"{len(REQUIRED_DOCS)} documents; {len(REQUIRED_T1_IDS)} unique requirements; "
        f"{len(EXPECTED_BLOCKED)} BLOCKED; {len(EXPECTED_DEFERRED)} DEFERRED; "
        "no PoC/business implementation changes"
    )


if __name__ == "__main__":
    main()
